// FILE: P2pCredentials.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: P2P transport type and per-camera credentials (in-memory / encrypted store only — SEC-04).
//   SCOPE: TransportType enum, P2pCredentials value, TransportResolver (device-list `type` cross-checked with the p2pid shape).
//   DEPENDS: none
//   LINKS: M-P2P-CREDS, V-M-P2P-CREDS, M-CAMERA-LIST
//   ROLE: TYPES
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.camera

// START_MODULE_MAP
//   TransportType - TNP | TUTK | LANGTAO | UNKNOWN; fromStorage(name) for the Room column and the encrypted store.
//   P2pCredentials - per-camera secrets (p2p id, password, InitString, License) with a flag-only toString().
//   TransportResolver - resolve(type, p2pId) -> Resolution(transport, mismatch).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 01-12 (CAM-03, SEC-04): created.
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
//     p2pEncrypt / wakeup: Boolean - ipcParam flags; needsPincode: Boolean - hasPincode, PIN flow not implemented }
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
) {
    override fun toString(): String =
        "P2pCredentials(transport=$transport, hasP2pId=${p2pId.isNotBlank()}, hasPassword=${password != null}, " +
            "hasInitString=${!initString.isNullOrBlank()}, hasLicense=${!licenseKey.isNullOrBlank()}, " +
            "encrypt=$p2pEncrypt, wakeup=$wakeup, needsPincode=$needsPincode)"
}

object TransportResolver {

    data class Resolution(val transport: TransportType, val mismatch: Boolean)

    /** TNP DID shape: `TNP…` prefix and three dash-separated parts. */
    internal val TNP_DID = Regex("^TNP[A-Z]*-\\d+-[A-Z]+$")

    private const val TYPE_TUTK = 0
    private const val TYPE_LANGTAO = 1
    private const val TYPE_TNP = 2

    // START_CONTRACT: resolve
    //   PURPOSE: Pick the P2P transport for a camera. The device-list `type` wins (the vendor app branches on it);
    //     the p2pid shape is used only when `type` is absent and to flag a disagreement.
    //   INPUTS: { type: Int? - device-list `type` (0 TUTK, 1 Langtao, 2 TNP); p2pId: String? - device-list `did` }
    //   OUTPUTS: { Resolution - transport + mismatch flag; mismatch is true only when a non-blank id contradicts `type` }
    //   SIDE_EFFECTS: none
    //   LINKS: M-P2P-CREDS, V-M-P2P-CREDS
    // END_CONTRACT: resolve
    fun resolve(type: Int?, p2pId: String?): Resolution {
        // START_BLOCK_RESOLVE_TRANSPORT
        val id = p2pId?.trim().orEmpty()
        val hasId = id.isNotEmpty()
        val looksTnp = hasId && TNP_DID.matches(id)
        return when (type) {
            TYPE_TNP -> Resolution(TransportType.TNP, mismatch = hasId && !looksTnp)
            TYPE_TUTK -> Resolution(TransportType.TUTK, mismatch = looksTnp)
            TYPE_LANGTAO -> Resolution(TransportType.LANGTAO, mismatch = looksTnp)
            null -> Resolution(if (looksTnp) TransportType.TNP else TransportType.UNKNOWN, mismatch = false)
            else -> Resolution(TransportType.UNKNOWN, mismatch = false)
        }
        // END_BLOCK_RESOLVE_TRANSPORT
    }
}
