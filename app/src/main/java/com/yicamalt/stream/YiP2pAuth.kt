// FILE: YiP2pAuth.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Build the per-command TNP authInfo field exactly like the vendor client (nonce + HMAC-SHA1 password).
//   SCOPE: nonce alphabet, per-connection nonce prefix, random nonce tail, HmacSHA1 password digest, 32-byte authInfo.
//   DEPENDS: none (JDK javax.crypto, java.util.Base64)
//   LINKS: M-STREAM-CORE, V-M-STREAM-CORE
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.stream

import java.util.Base64
import java.util.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// START_MODULE_MAP
//   YiP2pAuth - object: ALPHABET, noncePrefix(rnd), nonce(len, rnd), hmacSha1(key, msg) (internal),
//     hmacPassword(cameraPassword, nonce), authInfo(encrypted, password, noncePrefix7, rnd).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 02-04 from 02-RESEARCH.md Pattern 4 (VERIFIED-LOCAL layouts).
//     INVARIANT: nothing here logs; the password and authInfo never leave the returned values.
//     HAZARD: production callers must pass java.security.SecureRandom; seeded Random is for tests only.
// END_CHANGE_SUMMARY

object YiP2pAuth {
    const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    const val AUTH_INFO_SIZE = 32
    const val NONCE_PREFIX_LEN = 7
    const val NONCE_TAIL_LEN = 8
    const val PASS_LEN = 15
    private const val HMAC_ALG = "HmacSHA1"

    // START_CONTRACT: noncePrefix
    //   PURPOSE: Per-connection 7-char nonce prefix (vendor keeps it for the life of one session).
    //   INPUTS: { rnd: Random - SecureRandom in production }
    //   OUTPUTS: { String - 7 chars from ALPHABET }
    //   SIDE_EFFECTS: consumes rnd
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: noncePrefix
    fun noncePrefix(rnd: Random): String = nonce(NONCE_PREFIX_LEN, rnd)

    // START_CONTRACT: nonce
    //   PURPOSE: Random string of len chars from ALPHABET.
    //   INPUTS: { len: Int - length >= 0, rnd: Random }
    //   OUTPUTS: { String }
    //   SIDE_EFFECTS: consumes rnd
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: nonce
    fun nonce(len: Int, rnd: Random): String {
        require(len >= 0) { "len < 0" }
        return buildString(len) { repeat(len) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }
    }

    // START_CONTRACT: hmacSha1
    //   PURPOSE: Raw HmacSHA1 primitive, the single Mac code path used by hmacPassword (RFC 2202 pinned).
    //   INPUTS: { key: ByteArray, msg: ByteArray }
    //   OUTPUTS: { ByteArray - 20-byte digest }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: hmacSha1
    internal fun hmacSha1(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(key, HMAC_ALG))
        return mac.doFinal(msg)
    }

    // START_CONTRACT: hmacPassword
    //   PURPOSE: Vendor password digest: Base64(HmacSHA1(key = UTF8(pwd), msg = UTF8(fixed user prefix + nonce))).take(15).
    //   INPUTS: { cameraPassword: String, nonce: String - 15-char nonce }
    //   OUTPUTS: { String - 15 Base64 chars }
    //   SIDE_EFFECTS: none
    //   LINKS: M-STREAM-CORE
    // END_CONTRACT: hmacPassword
    fun hmacPassword(cameraPassword: String, nonce: String): String {
        val digest = hmacSha1(
            cameraPassword.toByteArray(Charsets.UTF_8),
            "user=xiaoyiuser&nonce=$nonce".toByteArray(Charsets.UTF_8),
        )
        return Base64.getEncoder().encodeToString(digest).take(PASS_LEN)
    }

    // START_CONTRACT: authInfo
    //   PURPOSE: 32-byte authInfo field written at IOCtrl head offset 8 of every command.
    //   INPUTS: { encrypted: Boolean - p2p_encrypt on, password: String, noncePrefix7: String - per-connection prefix,
    //             rnd: Random - SecureRandom in production }
    //   OUTPUTS: { ByteArray - 32 bytes: "nonce15,pass15" (31 ASCII) + 0, or "admin,<password>" zero-padded/truncated }
    //   SIDE_EFFECTS: consumes rnd when encrypted
    //   LINKS: M-STREAM-CORE, TnpCodec.commandUnit
    // END_CONTRACT: authInfo
    fun authInfo(encrypted: Boolean, password: String, noncePrefix7: String, rnd: Random): ByteArray {
        // START_BLOCK_BUILD_AUTH_INFO
        val text = if (encrypted) {
            require(noncePrefix7.length == NONCE_PREFIX_LEN) { "nonce prefix must be 7 chars" }
            val n = noncePrefix7 + nonce(NONCE_TAIL_LEN, rnd)
            n + "," + hmacPassword(password, n)
        } else {
            "admin,$password"
        }
        return text.toByteArray(Charsets.US_ASCII).copyOf(AUTH_INFO_SIZE)
        // END_BLOCK_BUILD_AUTH_INFO
    }
}
