// FILE: CameraRegistry.kt
// VERSION: 0.4.0
// START_MODULE_CONTRACT
//   PURPOSE: Fetch the user's Yi cameras from the probe-proven device-list source, keep an atomic Room cache of
//     the secret-free fields, and report distinct outcomes (no cameras / rejected / unreachable / unauthorized).
//   SCOPE: getCameraList (signed list fetch on the session region, dedupe, Room replaceAll), cachedCameras
//     (Room only), getCameraById, cacheCameraList, updateCameraStatus, getLiveStreamUrl; trace markers
//     BLOCK_FETCH_SOURCE / BLOCK_FETCH_CAMERA_LIST / BLOCK_RESOLVE_STREAM_URL.
//   DEPENDS: M-HTTP (YiCloudDeviceApi, YiRequestSigner), M-LOCAL-DB (CameraDao), M-AUTH (SessionSource),
//     M-P2P-CREDS (P2pCredentialStore, TransportResolver), M-CONFIG (region is the one kept by login)
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
import kotlinx.coroutines.Dispatchers
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
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.4.0 - probe-driven sources (DeviceSourcePlan.OWNED_PATHS = /v5/devices/list), owned rows
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
) : CameraListPort {

    private val refreshMutex = Mutex()

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
            val fetch = fetchOwned(s)
            val records = dedupeRecords(fetch.records)
            if (records.isEmpty()) {
                if (fetch.successes == 0) {
                    if (fetch.serverFailures > 0) throw CameraError.ServerRejected
                    throw CameraError.FetchFailed
                }
                Timber.d("[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] devices=0 result=no-devices")
                cameraDao.deleteAll()
                credentialStore.clear()
                return@withContext emptyList()
            }
            val now = System.currentTimeMillis()
            val cameras = records.map { record ->
                val transport = TransportResolver.resolve(record.type, record.p2pId).transport
                CameraInfo(
                    deviceId = record.cloudUid,
                    name = record.name,
                    model = record.model,
                    online = record.online,
                    streamUrl = null,
                    transport = transport,
                    shared = record.shared,
                )
            }
            cameraDao.replaceAll(cameras.map { it.toEntity(now) })
            Timber.d(
                "[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] devices=${cameras.size} " +
                    "shared=${cameras.count { it.shared }} tnp=${cameras.count { it.transport == TransportType.TNP }} " +
                    "tutk=${cameras.count { it.transport == TransportType.TUTK }}",
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
