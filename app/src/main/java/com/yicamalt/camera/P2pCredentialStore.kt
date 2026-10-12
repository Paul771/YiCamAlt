// FILE: P2pCredentialStore.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Only persistence path for P2P secrets: EncryptedSharedPreferences (SEC-04).
//   SCOPE: P2pCredentialStore contract (get / replaceAll / clear), the Keystore-backed production store,
//     an in-memory store for tests, and the JSON codec of one record. Nothing here touches Room.
//   DEPENDS: M-P2P-CREDS types (P2pCredentials, TransportType)
//   LINKS: M-P2P-CREDS, V-M-P2P-CREDS
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// START_MODULE_MAP
//   P2pCredentialStore - get(deviceId) / replaceAll(list) / clear().
//   InMemoryP2pCredentialStore - process-memory implementation (tests, previews).
//   P2pCredentialsCodec - encode / decode one record as JSON; decode returns null on anything malformed.
//   EncryptedP2pCredentialStore - production implementation over the encrypted prefs file yicamalt_p2p.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 01-12 (SEC-04): created. Log lines carry a count only, never a key or a value.
//     The encrypted store needs the Android Keystore, so it is verified on the phone (plan 01-11), not on the JVM.
// END_CHANGE_SUMMARY

interface P2pCredentialStore {
    /** Credentials of one camera by cloud uid, or null when absent or unreadable. */
    fun get(deviceId: String): P2pCredentials?

    /** Replace the whole set: records missing from [credentials] are dropped. */
    fun replaceAll(credentials: List<P2pCredentials>)

    fun clear()
}

/** Process-memory store. The map is swapped as a whole, so readers never see a half-replaced set. */
class InMemoryP2pCredentialStore : P2pCredentialStore {

    @Volatile
    private var byDeviceId: Map<String, P2pCredentials> = emptyMap()

    override fun get(deviceId: String): P2pCredentials? = byDeviceId[deviceId]

    override fun replaceAll(credentials: List<P2pCredentials>) {
        byDeviceId = credentials.associateBy { it.deviceId }
    }

    override fun clear() {
        byDeviceId = emptyMap()
    }
}

internal object P2pCredentialsCodec {

    @Serializable
    private data class Stored(
        val deviceId: String,
        val p2pId: String,
        val transport: String,
        val password: String? = null,
        val initString: String? = null,
        val licenseKey: String? = null,
        val p2pEncrypt: Boolean = false,
        val wakeup: Boolean = false,
        val needsPincode: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(c: P2pCredentials): String = json.encodeToString(
        Stored.serializer(),
        Stored(
            deviceId = c.deviceId,
            p2pId = c.p2pId,
            transport = c.transport.name,
            password = c.password,
            initString = c.initString,
            licenseKey = c.licenseKey,
            p2pEncrypt = c.p2pEncrypt,
            wakeup = c.wakeup,
            needsPincode = c.needsPincode,
        ),
    )

    // START_CONTRACT: decode
    //   PURPOSE: Read one stored record back.
    //   INPUTS: { raw: String - value written by encode }
    //   OUTPUTS: { P2pCredentials? - null for non-JSON, a non-object, a record without deviceId / p2pId / transport
    //     or with a blank deviceId; never throws }
    //   SIDE_EFFECTS: none (the raw value is a secret and is never logged)
    //   LINKS: M-P2P-CREDS
    // END_CONTRACT: decode
    fun decode(raw: String): P2pCredentials? {
        val stored = try {
            json.decodeFromString(Stored.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (stored.deviceId.isBlank()) return null
        return P2pCredentials(
            deviceId = stored.deviceId,
            p2pId = stored.p2pId,
            transport = TransportType.fromStorage(stored.transport),
            password = stored.password,
            initString = stored.initString,
            licenseKey = stored.licenseKey,
            p2pEncrypt = stored.p2pEncrypt,
            wakeup = stored.wakeup,
            needsPincode = stored.needsPincode,
        )
    }
}

@Singleton
class EncryptedP2pCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : P2pCredentialStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "yicamalt_p2p", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(deviceId: String): P2pCredentials? =
        prefs.getString(KEY_PREFIX + deviceId, null)?.let(P2pCredentialsCodec::decode)

    @Synchronized
    override fun replaceAll(credentials: List<P2pCredentials>) {
        // START_BLOCK_STORE_P2P_CREDENTIALS
        // clear() + puts in ONE editor: SharedPreferences applies the clear first, then the puts, in one commit.
        val editor = prefs.edit().clear()
        credentials.forEach { editor.putString(KEY_PREFIX + it.deviceId, P2pCredentialsCodec.encode(it)) }
        val stored = editor.commit()
        if (stored) {
            Timber.d("[P2pCreds][replaceAll][BLOCK_STORE_P2P_CREDENTIALS] count=${credentials.size}")
        } else {
            Timber.w("[P2pCreds][replaceAll][BLOCK_STORE_P2P_CREDENTIALS] code=P2P_STORE_FAILED count=${credentials.size}")
        }
        // END_BLOCK_STORE_P2P_CREDENTIALS
    }

    @Synchronized
    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_PREFIX = "cred:"
    }
}
