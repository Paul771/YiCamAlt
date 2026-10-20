// FILE: RedactionFixtures.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Synthetic but realistically shaped Yi / P2P secrets shared by every redaction test (SEC-01).
//   SCOPE: constants only. Every secret contains SYNTH so the repo secret hook accepts them; values are invented.
//   DEPENDS: none
//   LINKS: V-M-HTTP, V-M-LOCAL-DB, SEC-01
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

// START_MODULE_MAP
//   RedactionFixtures - object with one synthetic value per secret class (serial, uid, DID, token, hmac, ...).
//   RedactionFixtures.AUTH_INFO / PRIVATE_IP / WAN_IP - Phase 2 P2P secret shapes (TNP authInfo, peer addresses).
//   RedactionFixtures.ALL_SECRETS - every secret constant except USER_ID, for extraLiterals scans.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 02-00: AUTH_INFO (31-char TNP authInfo: 15-char nonce, comma, 15-char HMAC
//     prefix), PRIVATE_IP and WAN_IP (TEST-NET-3) added to ALL_SECRETS for Phase 2 P2P logs.
//   PREVIOUS: v0.1.0 - SEC-01: initial synthetic fixtures (replaces cam-1 style values that hid serial leaks).
// END_CHANGE_SUMMARY

object RedactionFixtures {
    const val SERIAL = "9876543210123" // SYNTH realistic 13-digit serial
    const val USER_ID = "100042" // not the real user id
    const val CLOUD_UID = "SYNTHUID00000001" // 16 chars: AES key material shape
    const val DID = "TNPXSYN-123456-SYNTH"
    const val TUTK_UID = "SYNTHTUTKUID0000001A"
    const val TOKEN = "SYNTHtok3n0a1b2c3d4e5f6a7b8c9d0e1f2"
    const val TOKEN_SECRET = "SYNTHs3cr3t0a1b2c3d4e5f6a7b8c9d0e1f"
    const val HMAC = "SYNTHhm4c0a1b2c3d4e5f6a7b8c=" // 28 chars, base64 HMAC-SHA1 length
    const val PASSWORD_HEX = "0a1b2c3d4e5f60718293a4b5c6d7e8f9" // SYNTH
    const val PASSWORD_PLAIN = "synthpw1"
    const val INIT_STRING = "SYNTHINITEBGBEMBMKGJMGAJPEIGIFKEGHBMCHMJHCKBMBHGFBJNOLCOLCIEB"
    const val LICENSE = "SYNTHLIC01:SYNTHEXTRAPART"
    const val NONCE = "SYNTHn0nce12345"
    const val EMAIL = "synth.user@example.com"
    const val AUTH_INFO = "SYNTHn0nce12345,SYNTHhmacpw1234" // 31 chars: TNP authInfo shape
    const val PRIVATE_IP = "192.168.1.23" // SYNTH LAN peer address
    const val WAN_IP = "203.0.113.7" // SYNTH TEST-NET-3 (documentation range)

    val ALL_SECRETS: List<String> = listOf(
        SERIAL, CLOUD_UID, DID, TUTK_UID, TOKEN, TOKEN_SECRET, HMAC,
        PASSWORD_HEX, PASSWORD_PLAIN, INIT_STRING, LICENSE, NONCE, EMAIL,
        AUTH_INFO, PRIVATE_IP, WAN_IP,
    )
}
