// FILE: CameraRegistry.kt
// VERSION: 0.7.0
// START_MODULE_CONTRACT
//   PURPOSE: Fetch the user's Yi cameras from the probe-proven device-list source, keep an atomic Room cache of
//     the secret-free fields, and report distinct outcomes (no cameras / rejected / unreachable / unauthorized).
//   SCOPE: getCameraList (signed list fetch on the session region, dedupe, P2P credential resolution into the
//     encrypted store, Room replaceAll), cachedCameras (Room only), getCredentials, credentialsFor
//     (implements P2pCredentialsSource, M-STREAM-CORE port), clearSignedOutData (logout),
//     purgeIfSignedOut (start-up),
//     getCameraById, cacheCameraList, updateCameraStatus, getLiveStreamUrl; trace markers
//     BLOCK_FETCH_SOURCE / BLOCK_FETCH_CAMERA_LIST / BLOCK_RESOLVE_P2P_CREDENTIALS / BLOCK_CLEAR_CAMERA_DATA /
//     BLOCK_RESOLVE_STREAM_URL.
//   DEPENDS: M-HTTP (YiCloudDeviceApi, YiRequestSigner), M-LOCAL-DB (CameraDao), M-AUTH (SessionSource),
//     M-P2P-CREDS (P2pCredentialStore, TransportResolver), M-CONFIG (region is the one kept by login),
//     M-STREAM-CORE (implements P2pCredentialsSource)
//   LINKS: M-CAMERA-LIST, M-UI-SHELL, V-M-CAMERA-LIST
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import com.yicamalt.auth.AuthSession
import com.yicamalt.auth.SessionSource
import com.yicamalt.database.CameraDao
import com.yicamalt.database.CameraEntity
import com.yicamalt.network.YiRequestSigner
import com.yicamalt.auth.LogoutCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   CameraError - typed errors (FetchFailed, Unauthorized, ServerRejected, NotFound, NoStreamUrl).
//   CameraListPort - getCameraList (network refresh) + cachedCameras (Room only) for the UI.
//   CameraRegistry - facade over the cloud device API, the Room cache and the P2P credential store.
//   CameraRegistry.dedupeRecords - one record per cloud uid, first (owned) occurrence wins.
//   CameraRegistry.getCredentials - stored P2pCredentials of one camera.
//   CameraRegistry.credentialsFor - P2pCredentialsSource port (live screen); same value as getCredentials.
//   CameraRegistry.clearSignedOutData - LogoutCleanup: synchronous wipe of Room cache + P2P store (logout).
//   CameraRegistry.purgeIfSignedOut - start-up wipe when there is no session.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.7.0 - GSD plan 02-01: vendor transport rule; /v4/tnp/device_info for every camera not yet
//     verified (was: only list type 2); reuse only verified records; type-vs-id disagreement flag removed; implements
//     P2pCredentialsSource. Network failure -> stored verified record or UNKNOWN (never a guess).
//   PREV_CHANGE: v0.6.0 - SEC-04 fail-closed cleanup (security review: the logout wipe ran in a UI coroutine
//     and could be cancelled or skipped). clearAll replaced by LogoutCleanup.clearSignedOutData, called
//     synchronously from AuthRepository.logout; purgeIfSignedOut wipes at start when there is no session;
//     writes take writeLock and are dropped when a wipe happened during the refresh (wipeGeneration).
//   PREV_CHANGE: v0.5.0 - P2P credential resolution (BLOCK_RESOLVE_P2P_CREDENTIALS): transport, decrypted password
//     (null when hasPincode), TNP DID/InitString/license from /v4/tnp/device_info, reused while the stored
//     license belongs to the same list DID (or the row has no did); credentials go only to P2pCredentialStore.
//     getCredentials, clearAll (SEC-04 logout wipe).
//   PREV_CHANGE: v0.4.0 - probe-driven sources (DeviceSourcePlan.OWNED_PATHS = /v5/devices/list), owned rows
//     and shared rows from the same list (dedupe by cloud uid), ServerRejected, cachedCameras, atomic cache
//     (CameraDao.replaceAll), refresh Mutex. Code 20200 / empty data = "no cameras": cache and credential
//     store are cleared; error outcomes never clear. A non-JSON body counts as FetchFailed instead of
//     escaping as a raw SerializationException. No region switching here (addendum: the region is the
//     one kept by login discovery; tokens are per region).
//   PREV_CHANGE: v0.3.0 - Signing delegated to YiRequestSigner (single signer, M-HTTP); stream-url
//     log no longer prints device id (SEC-01).
//   PREV_CHANGE: v0.2.0 - Signed request. Confirmed on-device scheme B: GET /v5/devices/list
//     with hmac = Base64(HMAC-SHA1(key="<token>&<token_secret>", msg="seq=1&userid=<uid>")).
//   PREV_CHANGE: v0.1.0 - Created for Phase-2 M-CAMERA-LIST.
// END_CHANGE_SUMMARY

sealed class CameraError(message: String) : Error(message) {
    object FetchFailed : CameraError("CAMERA_FETCH_FAILED: cloud device list unavailable")
    object Unauthorized : CameraError("CAMERA_UNAUTHORIZED: session rejected by device API")
    object ServerRejected : CameraError("CAMERA_SERVER_ERROR: device API returned a non-success code")
    object NotFound : CameraError("CAMERA_NOT_FOUND: no such device id")
    object NoStreamUrl : CameraError("CAMERA_NO_STREAM_URL: stream url not resolved for this device")
}

/** Thin contract consumed by the M-UI camera list so the view-model needs no network/DAO. */
interface CameraListPort {
    /** Network refresh of the camera list; throws [CameraError]. An empty list means "no cameras". */
    suspend fun getCameraList(): List<CameraInfo>

    /** Last cached list from Room only (no network); never throws, empty when nothing is cached. */
    suspend fun cachedCameras(): List<CameraInfo>
}

@Singleton
class CameraRegistry @Inject constructor(
    private val api: YiCloudDeviceApi,
    private val cameraDao: CameraDao,
    private val session: SessionSource,
    private val credentialStore: P2pCredentialStore,
) : CameraListPort, LogoutCleanup, com.yicamalt.stream.P2pCredentialsSource {

    private val refreshMutex = Mutex()

    /** Guards every write of Room + the credential store against a concurrent logout wipe. */
    private val writeLock = Any()

    /** Bumped by every wipe; a refresh that started before a wipe must not write afterwards. */
    @Volatile private var wipeGeneration = 0L

    /** Records of one fetch plus how each call ended (drives the distinct error outcomes). */
    private class FetchResult(
        val records: List<DeviceRecord>,
        val successes: Int,
        val serverFailures: Int,
        val ioFailures: Int,
    )

    // START_CONTRACT: getCameraList
    //   PURPOSE: Refresh the camera list from the cloud (CAM-01, CAM-05).
    //   INPUTS: { none - session from SessionSource, region = current base URL kept by login }
    //   OUTPUTS: { List<CameraInfo> - empty = the account has no cameras on this server }
    //   SIDE_EFFECTS: <= OWNED_PATHS.size signed list GETs; Room replaced atomically on success; on a
    //     legitimate empty answer Room and the credential store are cleared. Throws Unauthorized (no session,
    //     auth code, HTTP 401), ServerRejected (only non-success codes), FetchFailed (only I/O / HTTP errors / unreadable bodies);
    //     error outcomes leave Room and the store untouched.
    //   LINKS: M-CAMERA-LIST, V-M-CAMERA-LIST
    // END_CONTRACT: getCameraList
    override suspend fun getCameraList(): List<CameraInfo> = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            // START_BLOCK_FETCH_CAMERA_LIST
            val s = session.currentSession() ?: throw CameraError.Unauthorized
            val generation = wipeGeneration
            val fetch = fetchOwned(s)
            val records = dedupeRecords(fetch.records)
            if (records.isEmpty()) {
                if (fetch.successes == 0) {
                    if (fetch.serverFailures > 0) throw CameraError.ServerRejected
                    throw CameraError.FetchFailed
                }
                Timber.d("[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] devices=0 result=no-devices")
                synchronized(writeLock) { wipeLocked() }
                return@withContext emptyList()
            }
            val credentials = resolveCredentials(s, records)
            val now = System.currentTimeMillis()
            val cameras = records.zip(credentials) { record, creds ->
                CameraInfo(
                    deviceId = record.cloudUid,
                    name = record.name,
                    model = record.model,
                    online = record.online,
                    streamUrl = null,
                    transport = creds.transport,
                    shared = record.shared,
                )
            }
            synchronized(writeLock) {
                // A logout (or start-up purge) during the network calls wins: nothing is written after it.
                if (generation != wipeGeneration || session.currentSession() == null) {
                    Timber.d("[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] result=session-ended written=false")
                    throw CameraError.Unauthorized
                }
                credentialStore.replaceAll(credentials)
                cameraDao.replaceAll(cameras.map { it.toEntity(now) })
            }
            Timber.d(
                "[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] devices=${cameras.size} " +
                    "shared=${cameras.count { it.shared }} tnp=${cameras.count { it.transport == TransportType.TNP }} " +
                    "tutk=${cameras.count { it.transport == TransportType.TUTK }} " +
                    "langtao=${cameras.count { it.transport == TransportType.LANGTAO }} " +
                    "unknown=${cameras.count { it.transport == TransportType.UNKNOWN }}",
            )
            cameras
            // END_BLOCK_FETCH_CAMERA_LIST
        }
    }

    /** Calls OWNED_PATHS in order on the current region; stops at the first path that yields rows. */
    private suspend fun fetchOwned(s: AuthSession): FetchResult {
        // START_BLOCK_FETCH_SOURCE
        val hmac = YiRequestSigner.sign(s.accessToken, s.refreshToken, s.userId)
        var successes = 0
        var serverFailures = 0
        var ioFailures = 0
        for (path in DeviceSourcePlan.OWNED_PATHS) {
            val source = DeviceSourcePlan.label(path)
            val envelope = try {
                api.signedList(path, userId = s.userId, hmac = hmac)
            } catch (e: HttpException) {
                if (e.code() == HTTP_UNAUTHORIZED) throw CameraError.Unauthorized
                ioFailures++
                Timber.d("[Camera][getCameraList][BLOCK_FETCH_SOURCE] source=$source http=${e.code()} result=io-failure")
                continue
            } catch (e: IOException) {
                ioFailures++
                Timber.d("[Camera][getCameraList][BLOCK_FETCH_SOURCE] source=$source result=io-failure")
                continue
            } catch (e: SerializationException) {
                // Non-JSON or unreadable body (gateway error page, captive portal): an unusable answer, not a code.
                ioFailures++
                Timber.d("[Camera][getCameraList][BLOCK_FETCH_SOURCE] source=$source result=malformed")
                continue
            }
            when (envelope.code) {
                in AUTH_ERROR_CODES -> {
                    Timber.d("[Camera][getCameraList][BLOCK_FETCH_SOURCE] source=$source code=${envelope.code} result=auth")
                    throw CameraError.Unauthorized
                }
                in SUCCESS_CODES -> {
                    successes++
                    val records = DeviceRecordParser.parse(envelope.data, shared = false)
                    Timber.d("[Camera][getCameraList][BLOCK_FETCH_SOURCE] source=$source code=${envelope.code} count=${records.size}")
                    if (records.isNotEmpty()) return FetchResult(records, successes, serverFailures, ioFailures)
                }
                else -> {
                    serverFailures++
                    Timber.d("[Camera][getCameraList][BLOCK_FETCH_SOURCE] source=$source code=${envelope.code} result=rejected")
                }
            }
        }
        return FetchResult(emptyList(), successes, serverFailures, ioFailures)
        // END_BLOCK_FETCH_SOURCE
    }

    // START_CONTRACT: resolveCredentials
    //   PURPOSE: Build P2pCredentials for every record (CAM-03) with the vendor transport rule (02-RESEARCH.md
    //     Pattern 5): a TNP-shaped device_info DID makes the camera TNP (dialled with that DID, plus InitString and
    //     license key); otherwise the cloud uid is the P2P id and its case decides (lower -> LANGTAO, else TUTK).
    //     The list `type` / `did` never decide; the type is logged as a hint only.
    //   INPUTS: { s: AuthSession - signing material; records: List<DeviceRecord> - deduped list rows }
    //   OUTPUTS: { List<P2pCredentials> - same order and size as records; transportVerified = backed by an answer }
    //   SIDE_EFFECTS: at most one GET /v4/tnp/device_info per camera whose stored record is not transportVerified
    //     (0 in steady state); no retry, no timer. A network failure never yields a guessed transport: the stored
    //     verified record is kept, otherwise the camera is UNKNOWN and unverified (asked again next refresh).
    //     Never calls the PIN password endpoint. Logs flags only.
    //   LINKS: M-P2P-CREDS, V-M-P2P-CREDS, V-M-CAMERA-LIST, M-STREAM-CORE
    // END_CONTRACT: resolveCredentials
    private suspend fun resolveCredentials(s: AuthSession, records: List<DeviceRecord>): List<P2pCredentials> {
        // START_BLOCK_RESOLVE_P2P_CREDENTIALS
        val all = records.map { record ->
            val password = if (record.hasPincode) null else CameraPasswordCipher.decrypt(record.passwordHex, record.cloudUid)
            val known = credentialStore.get(record.cloudUid)
            val knownVerified = known != null && known.transportVerified
            val decision: Decision = if (
                known != null && known.transportVerified &&
                (known.transport != TransportType.TNP || known.licenseKey != null)
            ) {
                Decision.keep(known, tnpInfo = "reused")
            } else {
                when (val lookup = fetchTnpInfo(s, record.cloudUid)) {
                    is TnpLookup.Answer -> {
                        val info = lookup.info
                        when {
                            info != null && TransportResolver.isTnpDid(info.did) -> {
                                val r = TransportResolver.decide(info.did, record.cloudUid)
                                Decision(r.transport, r.p2pId, info.initString, info.licenseKey, true, "deviceInfo", "ok")
                            }
                            TransportResolver.isTnpDid(lookup.rawDid) ->
                                if (knownVerified) {
                                    Decision.keep(known!!, tnpInfo = "incomplete")
                                } else {
                                    // TNP DID without InitString / License: not dialable; asked again next refresh.
                                    Decision(TransportType.TNP, lookup.rawDid!!, null, null, false, "deviceInfo", "incomplete")
                                }
                            else -> Decision.byUid(record.cloudUid, tnpInfo = "notTnp")
                        }
                    }
                    is TnpLookup.Rejected -> Decision.byUid(record.cloudUid, tnpInfo = "rejected")
                    TnpLookup.Failed ->
                        if (knownVerified) {
                            Decision.keep(known!!, tnpInfo = "failed")
                        } else {
                            // Never keep a guess (e.g. a Phase 1 "TUTK by list type") after a network error.
                            Decision(TransportType.UNKNOWN, record.cloudUid, null, null, false, "none", "failed")
                        }
                }
            }
            Timber.d(
                "[Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] transport=${decision.transport} via=${decision.via} " +
                    "listType=${record.type ?: "-"} tnpInfo=${decision.tnpInfo} hasPassword=${password != null} " +
                    "needsPincode=${record.hasPincode}",
            )
            P2pCredentials(
                deviceId = record.cloudUid,
                p2pId = decision.p2pId,
                transport = decision.transport,
                password = password,
                initString = decision.initString,
                licenseKey = decision.licenseKey,
                p2pEncrypt = record.p2pEncrypt,
                wakeup = record.wakeup,
                needsPincode = record.hasPincode,
                transportVerified = decision.verified,
            )
        }
        Timber.d(
            "[Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] stored=${all.size} " +
                "withLicense=${all.count { it.licenseKey != null }} withPassword=${all.count { it.password != null }} " +
                "verified=${all.count { it.transportVerified }}",
        )
        return all
        // END_BLOCK_RESOLVE_P2P_CREDENTIALS
    }

    /** Transport decision of one camera; values are secrets, toString prints flags only. */
    private class Decision(
        val transport: TransportType,
        val p2pId: String,
        val initString: String?,
        val licenseKey: String?,
        val verified: Boolean,
        val via: String,
        val tnpInfo: String,
    ) {
        override fun toString() = "Decision(transport=$transport, verified=$verified, via=$via, tnpInfo=$tnpInfo)"

        companion object {
            fun keep(known: P2pCredentials, tnpInfo: String) = Decision(
                known.transport, known.p2pId, known.initString, known.licenseKey, known.transportVerified,
                via = if (known.transport == TransportType.TNP) "deviceInfo" else "uid", tnpInfo = tnpInfo,
            )

            fun byUid(cloudUid: String, tnpInfo: String): Decision {
                val r = TransportResolver.decide(null, cloudUid)
                return Decision(r.transport, r.p2pId, null, null, true, "uid", tnpInfo)
            }
        }
    }

    /** Outcome of one /v4/tnp/device_info call. */
    private sealed interface TnpLookup {
        /** Success code; [rawDid] read even when InitString/License are missing; [info] null when incomplete. */
        class Answer(val rawDid: String?, val info: TnpDeviceInfo?, val code: String) : TnpLookup {
            override fun toString() = "Answer(hasDid=${rawDid != null}, complete=${info != null}, code=$code)"
        }

        /** Non-success code: the server answered for this camera. */
        data class Rejected(val code: String) : TnpLookup

        /** IOException / HttpException / SerializationException. */
        object Failed : TnpLookup
    }

    /** One signed GET /v4/tnp/device_info; never throws, no retry. */
    private suspend fun fetchTnpInfo(s: AuthSession, cloudUid: String): TnpLookup {
        val hmac = YiRequestSigner.sign(s.accessToken, s.refreshToken, s.userId, extra = listOf("uid" to cloudUid))
        val envelope = try {
            api.tnpDeviceInfo(userId = s.userId, uid = cloudUid, hmac = hmac)
        } catch (e: IOException) {
            return TnpLookup.Failed
        } catch (e: HttpException) {
            return TnpLookup.Failed
        } catch (e: SerializationException) {
            return TnpLookup.Failed
        }
        if (envelope.code !in SUCCESS_CODES) {
            Timber.d("[Camera][creds][BLOCK_RESOLVE_P2P_CREDENTIALS] tnpCode=${envelope.code} result=rejected")
            return TnpLookup.Rejected(envelope.code)
        }
        return TnpLookup.Answer(
            rawDid = DeviceRecordParser.parseTnpDid(envelope.data),
            info = DeviceRecordParser.parseTnpInfo(envelope.data),
            code = envelope.code,
        )
    }

    // START_CONTRACT: getCredentials
    //   PURPOSE: P2P credentials of one camera from the encrypted store.
    //   INPUTS: { deviceId: String - cloud uid }
    //   OUTPUTS: { P2pCredentials? - null when unknown }
    //   SIDE_EFFECTS: none
    //   LINKS: M-P2P-CREDS
    // END_CONTRACT: getCredentials
    /** Phase 2 entry point: the P2P credentials stored for [deviceId], or null. */
    fun getCredentials(deviceId: String): P2pCredentials? = credentialStore.get(deviceId)

    // START_CONTRACT: credentialsFor
    //   PURPOSE: P2pCredentialsSource port (M-STREAM-CORE): the live screen reads one camera's credentials.
    //   INPUTS: { deviceId: String - cloud uid }
    //   OUTPUTS: { P2pCredentials? - same as getCredentials; null when unknown }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE, M-P2P-CREDS
    // END_CONTRACT: credentialsFor
    override fun credentialsFor(deviceId: String): P2pCredentials? = credentialStore.get(deviceId)

    // START_CONTRACT: clearSignedOutData
    //   PURPOSE: Logout cleanup (SEC-04), called synchronously by AuthRepository.logout.
    //   INPUTS: { none }
    //   OUTPUTS: { Unit - returns only after Room and the credential store are empty }
    //   SIDE_EFFECTS: blocks the caller while the wipe runs on Dispatchers.IO (Room forbids the main thread);
    //     no UI scope can cancel it; a refresh in flight is prevented from writing afterwards.
    //   LINKS: M-AUTH, M-P2P-CREDS, M-LOCAL-DB
    // END_CONTRACT: clearSignedOutData
    override fun clearSignedOutData() {
        runBlocking(Dispatchers.IO) {
            // START_BLOCK_CLEAR_CAMERA_DATA
            synchronized(writeLock) { wipeLocked() }
            Timber.d("[Camera][clearAll][BLOCK_CLEAR_CAMERA_DATA] cleared=true reason=logout")
            // END_BLOCK_CLEAR_CAMERA_DATA
        }
    }

    // START_CONTRACT: purgeIfSignedOut
    //   PURPOSE: Start-up safety net: with no session, no camera row or P2P credential may remain (a logout
    //     whose cleanup failed, or a process killed mid-logout).
    //   INPUTS: { none }
    //   OUTPUTS: { Boolean - true when there was no session and the wipe ran }
    //   SIDE_EFFECTS: blocking Room + store access; call off the main thread. With a session: no change.
    //   LINKS: M-AUTH, M-P2P-CREDS, M-LOCAL-DB
    // END_CONTRACT: purgeIfSignedOut
    fun purgeIfSignedOut(): Boolean {
        val rows = synchronized(writeLock) {
            if (session.currentSession() != null) return false
            val count = runCatching { cameraDao.count() }.getOrDefault(-1)
            wipeLocked()
            count
        }
        Timber.d("[Camera][clearAll][BLOCK_CLEAR_CAMERA_DATA] cleared=true reason=no-session rows=$rows")
        return true
    }

    /** Caller holds [writeLock]. Store first: the secrets matter more than the cache. */
    private fun wipeLocked() {
        wipeGeneration++
        credentialStore.clear()
        cameraDao.deleteAll()
    }

    /** One record per cloud uid; the first occurrence (an owned row precedes a shared duplicate) wins. */
    internal fun dedupeRecords(records: List<DeviceRecord>): List<DeviceRecord> {
        val owned = records.filter { !it.shared }
        val shared = records.filter { it.shared }
        return (owned + shared).distinctBy { it.cloudUid }
    }

    override suspend fun cachedCameras(): List<CameraInfo> = withContext(Dispatchers.IO) {
        runCatching { cameraDao.getAll().map { it.toInfo() } }.getOrDefault(emptyList())
    }

    private companion object {
        val AUTH_ERROR_CODES = setOf("20201", "20202", "20203", "20205", "20253", "40110")
        val SUCCESS_CODES = setOf("20000", "20200")
        const val HTTP_UNAUTHORIZED = 401
    }

    /** Look up a single camera from the cache. */
    suspend fun getCameraById(deviceId: String): CameraInfo? = withContext(Dispatchers.IO) {
        cameraDao.getById(deviceId)?.toInfo()
    }

    /** Overwrite the Room cache with the given list in one transaction; an empty list clears it. */
    suspend fun cacheCameraList(cameras: List<CameraInfo>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cameraDao.replaceAll(cameras.map { it.toEntity(now) })
    }

    /** Refresh the persisted online/offline flag (e.g. from an FCM status push). */
    suspend fun updateCameraStatus(deviceId: String, online: Boolean): CameraInfo? = withContext(Dispatchers.IO) {
        val row = cameraDao.getById(deviceId) ?: return@withContext null
        val updated = row.copy(online = online, last_seen = System.currentTimeMillis())
        cameraDao.insertCamera(updated)
        updated.toInfo()
    }

    /** Resolve the live stream URL for a camera. Uses the server-provided url when present. */
    suspend fun getLiveStreamUrl(deviceId: String): String = withContext(Dispatchers.IO) {
        // START_BLOCK_RESOLVE_STREAM_URL
        val cam = cameraDao.getById(deviceId)?.toInfo() ?: throw CameraError.NotFound
        val url = cam.streamUrl?.takeIf { it.isNotBlank() }
            ?: throw CameraError.NoStreamUrl
        Timber.d("[Camera][resolveUrl][BLOCK_RESOLVE_STREAM_URL] resolved=true url=***")
        url
        // END_BLOCK_RESOLVE_STREAM_URL
    }

    private fun CameraEntity.toInfo(): CameraInfo =
        CameraInfo(device_id, name, model, online, stream_url, TransportType.fromStorage(transport), shared)

    private fun CameraInfo.toEntity(now: Long): CameraEntity =
        CameraEntity(deviceId, name, model, online, streamUrl, now, now, transport.name, shared)
}
