// FILE: P2pCredentials.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: P2P transport type and per-camera credentials (in-memory / encrypted store only — SEC-04).
//   SCOPE: TransportType enum, P2pCredentials value, TransportResolver: vendor rule — device_info DID shape, else cloud-uid case.
//   DEPENDS: none
//   LINKS: M-P2P-CREDS, V-M-P2P-CREDS, M-CAMERA-LIST
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

// START_MODULE_MAP
//   TransportType - TNP | TUTK | LANGTAO | UNKNOWN; fromStorage(name) for the Room column and the encrypted store.
//   P2pCredentials - per-camera secrets (p2p id, password, InitString, License) with a flag-only toString().
//   TransportResolver - decide(deviceInfoDid, cloudUid) -> Resolution(transport, p2pId, source); isTnpDid(id).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 02-01: vendor transport rule (02-RESEARCH.md Pattern 5). The device-list type/did no
//     longer decide transport; mismatch removed (it compared the display did with a TNP regex and meant nothing).
//     transportVerified marks a decision backed by a device_info answer.
//   PREVIOUS: v0.1.0 - GSD plan 01-12 (CAM-03, SEC-04): created.
//     INVARIANT: toString() never prints a secret. P2pCredentials must never be written to Room or to a log line;
//     its only durable home is P2pCredentialStore.
// END_CHANGE_SUMMARY

enum class TransportType {
    TNP, TUTK, LANGTAO, UNKNOWN;

    companion object {
        /** Tolerant inverse of [name]: null or an unknown name maps to [UNKNOWN]. */
        fun fromStorage(value: String?): TransportType =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

// START_CONTRACT: P2pCredentials
//   PURPOSE: Everything one camera needs to open a P2P session.
//   INPUTS: { deviceId: String - cloud uid (store key); p2pId: String - TNP DID or TUTK uid;
//     password: String? - recovered camera password, null when recovery failed;
//     initString / licenseKey: String? - TNP device info (InitString, License), null for non-TNP;
//     p2pEncrypt / wakeup: Boolean - ipcParam flags; needsPincode: Boolean - hasPincode, PIN flow not implemented;
//     transportVerified: Boolean - true only when transport/p2pId come from a device_info answer (default false) }
//   OUTPUTS: { immutable value }
//   SIDE_EFFECTS: none
//   LINKS: M-P2P-CREDS
// END_CONTRACT: P2pCredentials
data class P2pCredentials(
    val deviceId: String,
    val p2pId: String,
    val transport: TransportType,
    val password: String?,
    val initString: String?,
    val licenseKey: String?,
    val p2pEncrypt: Boolean,
    val wakeup: Boolean,
    val needsPincode: Boolean,
    val transportVerified: Boolean = false,
) {
    override fun toString(): String =
        "P2pCredentials(transport=$transport, hasP2pId=${p2pId.isNotBlank()}, hasPassword=${password != null}, " +
            "hasInitString=${!initString.isNullOrBlank()}, hasLicense=${!licenseKey.isNullOrBlank()}, " +
            "encrypt=$p2pEncrypt, wakeup=$wakeup, needsPincode=$needsPincode, verified=$transportVerified)"
}

object TransportResolver {

    enum class Source { DEVICE_INFO, UID_SHAPE }

    data class Resolution(val transport: TransportType, val p2pId: String, val source: Source) {
        override fun toString() = "Resolution(transport=$transport, source=$source)"
    }

    // TNP DID shape as the vendor app checks it: `TNP` prefix (case-sensitive) and exactly three dash-separated parts.
    fun isTnpDid(id: String?): Boolean = id != null && id.startsWith("TNP") && id.split("-").size == 3

    // START_CONTRACT: decide
    //   PURPOSE: Vendor rule (02-RESEARCH.md Pattern 5): when the device_info DID is TNP-shaped the camera is TNP and is
    //     dialled with that DID; otherwise the cloud uid is the P2P id and its case decides (all lower-case -> LANGTAO,
    //     else TUTK). The device-list `type` / `did` never take part.
    //   INPUTS: { deviceInfoDid: String? - DID from a device_info answer, null when the answer had none;
    //     cloudUid: String - the camera's cloud uid }
    //   OUTPUTS: { Resolution - transport, the id to dial, and which input decided }
    //   SIDE_EFFECTS: none
    //   LINKS: M-P2P-CREDS, V-M-P2P-CREDS
    // END_CONTRACT: decide
    fun decide(deviceInfoDid: String?, cloudUid: String): Resolution {
        // START_BLOCK_RESOLVE_TRANSPORT
        TODO()
        // END_BLOCK_RESOLVE_TRANSPORT
    }

    @Deprecated("GSD 02-01 Task 1 shim, removed in Task 2")
    data class LegacyResolution(val transport: TransportType, val mismatch: Boolean)

    @Deprecated("GSD 02-01 Task 1 shim, removed in Task 2")
    fun resolve(type: Int?, p2pId: String?): LegacyResolution {
        val id = p2pId?.trim().orEmpty()
        val looksTnp = id.isNotEmpty() && isTnpDid(id)
        @Suppress("DEPRECATION")
        return when (type) {
            2 -> LegacyResolution(TransportType.TNP, mismatch = id.isNotEmpty() && !looksTnp)
            0 -> LegacyResolution(TransportType.TUTK, mismatch = looksTnp)
            1 -> LegacyResolution(TransportType.LANGTAO, mismatch = looksTnp)
            null -> LegacyResolution(if (looksTnp) TransportType.TNP else TransportType.UNKNOWN, mismatch = false)
            else -> LegacyResolution(TransportType.UNKNOWN, mismatch = false)
        }
    }
}
