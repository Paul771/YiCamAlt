// FILE: LogRedactorTest.kt
// VERSION: 0.1.2
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
//   maskQuery tests - hmac/userid/uid masked, seq kept, password/account still masked, alert from/to masked.
//   maskJsonBody tests - envelope, device_info, device list, unknown keys, id keys, arrays, non-json.
//   redactHttpLine tests - HTTP FAILED lines keep only the exception simpleName (+ elapsed ms); no host/IP/port.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.2 - Device diag log leaked server and phone IPv4 via the BASIC logger's
//     `<-- HTTP FAILED: <exception toString>` line; redactHttpLine pins simpleName-only output.
//   PREV_CHANGE: v0.1.1 - GSD plan 05-00: from / to epoch seconds masked in the alert list query; limit, fromDB,
//     expires stay readable; exact keys only (total / photo stay readable).
//   PREV_CHANGE: v0.1.0 - SEC-01: initial LogRedactor coverage.
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

    @Test
    fun `maskQuery masks device and user identifiers of event and tnp urls`() {
        val out = LogRedactor.maskQuery("?device_id=SYNTH123456&user_id=77&p2pid=SYNTHAAAA&access_token=abc&sn=SYNTH9&seq=1&event_id=5")
        assertEquals("?device_id=██&user_id=██&p2pid=██&access_token=██&sn=██&seq=1&event_id=5", out)
    }

    @Test
    fun `maskQuery masks every id and token key of an event-list URL`() {
        val line = "--> GET https://h/v5/alert/events?seq=1&user_id=${F.USER_ID}&device_id=${F.SERIAL}" +
            "&deviceid=${F.DID}&access_token=${F.TOKEN}&sn=${F.SERIAL}&limit=20&type=motion"
        assertEquals(
            "--> GET https://h/v5/alert/events?seq=1&user_id=██&device_id=██&deviceid=██&access_token=██" +
                "&sn=██&limit=20&type=motion",
            LogRedactor.maskQuery(line),
        )
    }

    @Test
    fun `QUERY_KEYS is derived from the secret-key classifier`() {
        listOf("device_id", "deviceid", "user_id", "userid", "uid", "did", "token", "hmac", "password", "account")
            .forEach { assertTrue(it in LogRedactor.QUERY_KEYS || LogRedactor.isSecretKey(it), it) }
        LogRedactor.QUERY_KEYS.forEach { assertTrue(LogRedactor.isSecretKey(it), "QUERY_KEYS entry not secret: $it") }
        assertTrue(LogRedactor.isSecretKey("access_token"))
    }

    @Test
    fun `maskQuery masks compound keys with an exact secret segment`() {
        val out = LogRedactor.maskQuery("?x-uid=1&user.account=2&cam-did=3&seq=1")
        assertEquals("?x-uid=██&user.account=██&cam-did=██&seq=1", out)
    }

    @Test
    fun `maskQuery masks pairs embedded in a non-secret value`() {
        val out = LogRedactor.maskQuery("?next=https://h/p?token=abc&seq=1&ref=a=b")
        assertEquals("?next=https://h/p?token=██&seq=1&ref=a=b", out)
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
    fun `maskQuery masks from and to epoch seconds`() {
        val line = "--> GET https://h/v2/alert/list?seq=0&userid=${F.USER_ID}&type=&sub_type=" +
            "&from=1700000000&to=1700086400&limit=20&fromDB=true&expires=10&hmac=abc"
        val out = LogRedactor.maskQuery(line)
        assertEquals(
            "--> GET https://h/v2/alert/list?seq=0&userid=██&type=&sub_type=&from=██&to=██" +
                "&limit=20&fromDB=true&expires=10&hmac=██",
            out,
        )
        RedactionScanner().assertClean(out)
    }

    @Test
    fun `maskQuery keeps limit fromDB and expires readable`() {
        assertEquals("?limit=20&fromDB=true&expires=10", LogRedactor.maskQuery("?limit=20&fromDB=true&expires=10"))
        assertFalse(LogRedactor.isSecretKey("fromDB"))
        assertFalse(LogRedactor.isSecretKey("expires"))
        assertFalse(LogRedactor.isSecretKey("limit"))
    }

    @Test
    fun `isSecretKey keeps total and photo readable`() {
        assertTrue(LogRedactor.isSecretKey("from"))
        assertTrue(LogRedactor.isSecretKey("to"))
        assertFalse(LogRedactor.isSecretKey("total"))
        assertFalse(LogRedactor.isSecretKey("photo"))
        assertFalse(LogRedactor.isSecretKey("toggle"))
        assertFalse(LogRedactor.isSecretKey("fromDB"))
        assertEquals("?total=3&photo=1", LogRedactor.maskQuery("?total=3&photo=1"))
    }

    @Test
    fun `isSecretKey classifies names`() {
        assertTrue(LogRedactor.isSecretKey("access_token"))
        assertTrue(LogRedactor.isSecretKey("DID"))
        assertTrue(LogRedactor.isSecretKey("tnpLicenseKey"))
        assertFalse(LogRedactor.isSecretKey("online"))
        assertFalse(LogRedactor.isSecretKey("code"))
    }

    // START_BLOCK_HTTP_FAILED_TESTS
    private val timeoutLine = "<-- HTTP FAILED: java.net.SocketTimeoutException: failed to connect to " +
        "gw-us.xiaoyi.com/203.0.113.5 (port 443) from /192.0.2.7 (port 41234) after 15000ms"

    @Test
    fun `redactHttpLine keeps only simpleName and elapsed of a connect timeout`() {
        val out = LogRedactor.redactHttpLine(timeoutLine)
        assertEquals("<-- HTTP FAILED: SocketTimeoutException after 15000ms", out)
        assertNoSecrets(out, "203.0.113.5", "192.0.2.7", "xiaoyi", "443", "41234", "java.net")
        RedactionScanner().assertClean(out)
    }

    @Test
    fun `redactHttpLine drops the host of an unknown host failure`() {
        val line = "<-- HTTP FAILED: java.net.UnknownHostException: Unable to resolve host " +
            "\"gw-us.xiaoyi.com\": No address associated with hostname"
        val out = LogRedactor.redactHttpLine(line)
        assertEquals("<-- HTTP FAILED: UnknownHostException", out)
        assertNoSecrets(out, "gw-us", "xiaoyi", "resolve")
        RedactionScanner().assertClean(out)
    }

    @Test
    fun `redactHttpLine drops host and port of a connect exception`() {
        val line = "<-- HTTP FAILED: java.net.ConnectException: Failed to connect to gw-us.xiaoyi.com/203.0.113.5:443"
        val out = LogRedactor.redactHttpLine(line)
        assertEquals("<-- HTTP FAILED: ConnectException", out)
        RedactionScanner().assertClean(out)
    }

    @Test
    fun `redactHttpLine handles a message-less exception`() {
        assertEquals(
            "<-- HTTP FAILED: SSLHandshakeException",
            LogRedactor.redactHttpLine("<-- HTTP FAILED: javax.net.ssl.SSLHandshakeException"),
        )
    }

    @Test
    fun `redactHttpLine still masks request and response lines`() {
        val req = "--> GET https://h/v2/alert/list?userid=${F.USER_ID}&hmac=${F.HMAC}&limit=20"
        assertEquals("--> GET https://h/v2/alert/list?userid=██&hmac=██&limit=20", LogRedactor.redactHttpLine(req))
        val resp = "<-- 200 OK https://h/v5/devices/list?seq=1&uid=${F.CLOUD_UID} (120ms, unknown-length body)"
        assertEquals(
            "<-- 200 OK https://h/v5/devices/list?seq=1&uid=██ (120ms, unknown-length body)",
            LogRedactor.redactHttpLine(resp),
        )
    }
    // END_BLOCK_HTTP_FAILED_TESTS
}
