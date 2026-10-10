// FILE: LogRedactorTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Prove LogRedactor masks secret query values and reduces JSON bodies to shape-only form (SEC-01).
//   SCOPE: maskQuery keys; maskJsonBody envelope, device_info, device list, unknown keys, id-like keys,
//     array truncation, non-JSON bodies. All values come from RedactionFixtures.
//   DEPENDS: M-HTTP (LogRedactor), RedactionFixtures, RedactionScanner
//   LINKS: V-M-HTTP, SEC-01
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.network

import com.yicamalt.test.RedactionFixtures as F
import com.yicamalt.test.RedactionScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   assertNoSecrets - helper: none of the given literals appears in the output.
//   maskQuery tests - hmac/userid/uid masked, seq kept, password/account still masked.
//   maskJsonBody tests - envelope, device_info, device list, unknown keys, id keys, arrays, non-json.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - SEC-01: initial LogRedactor coverage.
// END_CHANGE_SUMMARY

class LogRedactorTest {

    private fun assertNoSecrets(out: String, vararg secrets: String) {
        secrets.forEach { assertFalse(out.contains(it), "masked output leaked a synthetic secret") }
    }

    // START_BLOCK_MASK_QUERY_TESTS
    @Test
    fun `maskQuery masks hmac userid uid and keeps seq`() {
        val line = "--> GET https://h/v5/devices/list?seq=1&userid=${F.USER_ID}&hmac=${F.HMAC}&uid=${F.CLOUD_UID}"
        assertEquals("--> GET https://h/v5/devices/list?seq=1&userid=██&hmac=██&uid=██", LogRedactor.maskQuery(line))
    }

    @Test
    fun `maskQuery still masks password and account`() {
        val line = "--> GET https://h/v4/users/login?account=${F.EMAIL}&password=${F.PASSWORD_HEX}&seq=2"
        val out = LogRedactor.maskQuery(line)
        assertEquals("--> GET https://h/v4/users/login?account=██&password=██&seq=2", out)
    }

    @Test
    fun `maskQuery masks token and token_secret but not lookalike keys`() {
        val out = LogRedactor.maskQuery("?token=${F.TOKEN}&token_secret=${F.TOKEN_SECRET}&xuid=7&seq=1")
        assertEquals("?token=██&token_secret=██&xuid=7&seq=1", out)
    }
    // END_BLOCK_MASK_QUERY_TESTS

    // START_BLOCK_MASK_BODY_TESTS
    @Test
    fun `maskJsonBody masks the encoded data envelope`() {
        val body = """{"code":"20000","data":"{\"access_token\":\"${F.TOKEN}\",\"token_secret\":\"${F.TOKEN_SECRET}\"}"}"""
        val out = LogRedactor.maskJsonBody(body)
        assertNoSecrets(out, F.TOKEN, F.TOKEN_SECRET)
        assertTrue(out.contains("access_token"), out)
        assertTrue(out.contains("token_secret"), out)
        assertTrue(out.contains("\"code\":\"20000\""), out)
    }

    @Test
    fun `maskJsonBody masks tnp device_info`() {
        val body = """{"code":"20000","data":{"DID":"${F.DID}","InitString":"${F.INIT_STRING}","License":"${F.LICENSE}"}}"""
        val out = LogRedactor.maskJsonBody(body)
        assertEquals("""{"code":"20000","data":{"DID":"██","InitString":"██","License":"██"}}""", out)
        RedactionScanner().assertClean(out, extraLiterals = F.ALL_SECRETS)
    }

    @Test
    fun `maskJsonBody masks every value of a device list except code booleans and null`() {
        val body = """{"code":"20000","data":[{"uid":"${F.CLOUD_UID}","did":"${F.DID}","password":"${F.PASSWORD_HEX}",""" +
            """"p2pid":"${F.TUTK_UID}","name":"Living Room","model":"h30ga","type":3,"online":true,""" +
            """"ipcParam":{"p2p_encrypt":"${F.NONCE}","wakeup":null,"sn":${F.SERIAL}}}]}"""
        val out = LogRedactor.maskJsonBody(body)
        assertEquals(
            """{"code":"20000","data":[{"uid":"██","did":"██","password":"██","p2pid":"██","name":"██",""" +
                """"model":"██","type":"██","online":true,"ipcParam":{"p2p_encrypt":"██","wakeup":null,"sn":"██"}}]}""",
            out,
        )
        assertFalse(out.contains("Living Room"))
        RedactionScanner().assertClean(out, extraLiterals = F.ALL_SECRETS)
    }

    @Test
    fun `maskJsonBody masks values under unknown key names`() {
        val body = """{"code":"20000","data":[{"zzUnknownSecretField":"${F.TOKEN}","nested":{"alsoUnknown":"${F.INIT_STRING}","n":${F.SERIAL}}}]}"""
        val out = LogRedactor.maskJsonBody(body)
        assertTrue(out.contains("zzUnknownSecretField"), out)
        assertTrue(out.contains("alsoUnknown"), out)
        assertTrue(out.contains("\"code\":\"20000\""), out)
        assertNoSecrets(out, F.TOKEN, F.INIT_STRING, F.SERIAL)
    }

    @Test
    fun `maskJsonBody replaces identifier-like keys`() {
        val out = LogRedactor.maskJsonBody("""{"data":{"${F.SERIAL}":{"a":1}}}""")
        assertEquals("""{"data":{"<key>":{"a":"██"}}}""", out)
    }

    @Test
    fun `maskJsonBody truncates arrays to three items`() {
        val out = LogRedactor.maskJsonBody("""{"code":0,"data":[1,2,3,4,5]}""")
        assertEquals("""{"code":0,"data":["██","██","██","<+2 more>"]}""", out)
    }

    @Test
    fun `maskJsonBody never echoes a non-json body`() {
        val body = "<html>oops</html>"
        assertEquals("<non-json len=17>", LogRedactor.maskJsonBody(body))
        assertEquals("<non-json len=9>", LogRedactor.maskJsonBody("{\"a\":\"b\"" + "x"))
    }
    // END_BLOCK_MASK_BODY_TESTS

    @Test
    fun `isSecretKey classifies names`() {
        assertTrue(LogRedactor.isSecretKey("access_token"))
        assertTrue(LogRedactor.isSecretKey("DID"))
        assertTrue(LogRedactor.isSecretKey("tnpLicenseKey"))
        assertFalse(LogRedactor.isSecretKey("online"))
        assertFalse(LogRedactor.isSecretKey("code"))
    }
}
