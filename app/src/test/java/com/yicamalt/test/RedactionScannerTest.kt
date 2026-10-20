// FILE: RedactionScannerTest.kt
// VERSION: 0.2.0
// START_MODULE_CONTRACT
//   PURPOSE: Prove RedactionScanner flags every SEC-01 secret shape and stays clean on real redacted log shapes.
//   SCOPE: one test per secret shape; known-safe project log lines; assertCleanQuiet message hygiene.
//   DEPENDS: RedactionScanner, RedactionFixtures
//   LINKS: V-M-HTTP, SEC-01
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.test

import com.yicamalt.test.RedactionFixtures as F
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// START_MODULE_MAP
//   assertFlagged - helper: scan() must report at least one hit for the line.
//   flags <shape> - one test per secret shape embedded in a realistic log line.
//   safe line <n> is clean - real, already-redacted project log shapes produce no hit.
//   assertCleanQuiet tests - failure names pattern + index, never the secret.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.2.0 - GSD plan 02-00: IPv4 / authInfo hits and value-free P2P / decoder marker lines (scanner v0.3.0).
//   PREVIOUS: v0.1.0 - SEC-01: initial shape coverage for scanner v0.2.0.
// END_CHANGE_SUMMARY

class RedactionScannerTest {

    private val scanner = RedactionScanner()

    private fun assertFlagged(line: String) {
        assertTrue(scanner.scan(line).isNotEmpty(), "expected a hit for a synthetic secret line")
    }

    // START_BLOCK_FLAG_SHAPES
    @Test fun `flags hmac query`() = assertFlagged("--> GET https://h/v5/devices/list?seq=1&hmac=${F.HMAC}")
    @Test fun `flags json token`() = assertFlagged("""body={"code":"20000","token":"${F.TOKEN}"}""")
    @Test fun `flags json License`() = assertFlagged("""body={"License":"${F.LICENSE}"}""")
    @Test fun `flags json InitString`() = assertFlagged("""body={"InitString":"${F.INIT_STRING}"}""")
    @Test fun `flags did assignment`() = assertFlagged("[P2p][connect][BLOCK_CONNECT] did=${F.DID}")
    @Test fun `flags p2pid colon`() = assertFlagged("[P2p][connect][BLOCK_CONNECT] p2pid: ${F.TUTK_UID}")
    @Test fun `flags nonce assignment`() = assertFlagged("[Auth][sign][BLOCK_SIGN] nonce=${F.NONCE}")
    @Test fun `flags json password`() = assertFlagged("""body={"password":"${F.PASSWORD_HEX}"}""")
    @Test fun `flags serial`() = assertFlagged("[Camera][list][BLOCK_FETCH] device=${F.SERIAL}")
    @Test fun `flags bare DID`() = assertFlagged("connecting to ${F.DID} via relay")
    @Test fun `flags bare base64 hmac`() = assertFlagged("sig ${F.HMAC} ok")
    @Test fun `flags bare token_secret`() = assertFlagged("value ${F.TOKEN_SECRET} end")
    @Test fun `flags email`() = assertFlagged("user ${F.EMAIL} logged in")
    @Test fun `flags private ip`() = assertFlagged("[P2P][tnp][BLOCK_CHECK_SESSION] peer ${F.PRIVATE_IP} lan")
    @Test fun `flags wan ip`() = assertFlagged("[P2P][tnp][BLOCK_CHECK_SESSION] peer ${F.WAN_IP}:32108")
    @Test fun `flags auth info`() = assertFlagged("[P2P][tnp][BLOCK_AUTH_REPLY] sent ${F.AUTH_INFO} ok")
    @Test fun `flags bare TNP DID in P2P line`() = assertFlagged("[P2P][tnp][BLOCK_CONNECT] ${F.DID}")
    // END_BLOCK_FLAG_SHAPES

    // START_BLOCK_SAFE_LINES
    @Test fun `safe line 1 is clean`() =
        scanner.assertClean("[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] devices=3 code=20000")

    @Test fun `safe line 2 is clean`() =
        scanner.assertClean("--> GET https://gw-eu.xiaoyi.com/v4/devices/list?seq=1&userid=██&hmac=██")

    @Test fun `safe line 3 is clean`() = scanner.assertClean("Authorization: ██")

    @Test fun `safe line 4 is clean`() = scanner.assertClean(
        """[Network][body][BLOCK_REDACTED_LOG] path=/v4/tnp/device_info status=200 body={"code":"20000","data":{"DID":"██","InitString":"██","License":"██"}}""",
    )

    @Test fun `safe line 5 is clean`() = scanner.assertClean(
        "[Probe][run][BLOCK_PROBE_MATRIX] region=EU path=/v4/devices/list http=200 code=20000 len=1834 count=3 " +
            "fields=did,ipcParam,name,password,type,uid ipcParam=object(p2p_encrypt,wakeup)",
    )

    @Test fun `safe line 6 is clean`() = scanner.assertClean("[Shell][render][BLOCK_RENDER_SHELL] hasUser=true tab=Камеры")

    @Test fun `safe line 7 is clean`() = scanner.assertClean("x-xiaoyi-appVersion: android;454;6.9.8_20260826105838")

    @Test fun `safe P2P init line is clean`() =
        scanner.assertClean("[P2P][runtime][BLOCK_INIT_PPPP] init=0 api=0xA2050401 debug=off")

    @Test fun `safe decoder line is clean`() =
        scanner.assertClean("[Media][decoder][BLOCK_CREATE_DECODER] mime=video/hevc name=c2.qti.hevc.decoder hw=true")

    @Test fun `safe check session line is clean`() =
        scanner.assertClean("[P2P][tnp][BLOCK_CHECK_SESSION] mode=P2P lan=true connectMs=812")

    @Test fun `safe version line is clean`() = scanner.assertClean("version 1.13.9")

    @Test fun `safe loopback mock server url is clean`() =
        scanner.assertClean("--> GET http://127.0.0.1:41234/v4/devices/list?seq=1&hmac=██")
    // END_BLOCK_SAFE_LINES

    // START_BLOCK_QUIET_ASSERT
    @Test
    fun `assertCleanQuiet reports pattern and index but not the secret`() {
        val line = "[Auth][login][BLOCK_STORE_TOKEN] token=${F.TOKEN} did=${F.DID}"
        val ex = assertThrows<IllegalStateException> { scanner.assertCleanQuiet(line) }
        val msg = ex.message.orEmpty()
        assertTrue(msg.contains("regex:"), "message names the pattern")
        assertTrue(Regex("@\\d+").containsMatchIn(msg), "message carries the index")
        assertFalse(msg.contains(F.TOKEN), "message must not echo the token")
        assertFalse(msg.contains(F.DID), "message must not echo the DID")
    }

    @Test
    fun `assertCleanQuiet passes clean buffer`() =
        scanner.assertCleanQuiet("[Camera][getCameraList][BLOCK_FETCH_CAMERA_LIST] devices=3 code=20000")
    // END_BLOCK_QUIET_ASSERT
}
