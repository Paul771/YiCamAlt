// FILE: AlertMapperTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin the v2/alert/list mapping on a synthetic fixture shaped like 05-PROBE D-PROBE-1
//     (fake ids, fake URLs, fake keys).
//   SCOPE: full item, key fallback, aligned first non-empty media element, string / number tolerance,
//     dropped items, missing clip, non-success code, per-page duplicates, redacted toString, ms times,
//     absent play_time, http -> https rewrite of media URLs.
//   DEPENDS: M-EVENT (AlertMapper, AlertEnvelope, EventInfo types), M-EVENT-PLAYBACK (MediaHttp.secureUrl)
//   LINKS: V-M-EVENT, EVT-01, EVT-03
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.event

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   AlertMapperTest - mapping scenarios over decoded JSON fixtures.
//   envelope - decodes a fixture with a strict (non-lenient) Json, so code tolerance comes from AlertCodeSerializer.
//   item - builds one alert object JSON with overridable fields.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-01: initial scenarios (test-first). Fixture follows 05-PROBE: plural URL
//     field names holding one string, 13-digit times, no play_time on real data, http media URLs.
// END_CHANGE_SUMMARY

class AlertMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun envelope(text: String): AlertEnvelope = json.decodeFromString(AlertEnvelope.serializer(), text)

    private fun item(
        id: String? = "evt-1",
        uid: String? = "cam-uid-1",
        time: String? = "1730000000123",
        extra: String = "",
    ): String = buildList {
        if (id != null) add("\"id\":\"$id\"")
        if (uid != null) add("\"uid\":\"$uid\"")
        if (time != null) add("\"time\":$time")
        if (extra.isNotEmpty()) add(extra)
    }.joinToString(",", "{", "}")

    private fun page(vararg items: String, code: String = "\"20000\""): AlertPage =
        AlertMapper.page(envelope("{\"code\":$code,\"data\":[${items.joinToString(",")}]}"))

    @Test
    fun `maps a full item`() {
        val p = page(
            item(
                extra = "\"type\":0,\"sub_type\":1,\"pic_urls\":\"a.jpg,b.jpg\",\"pic_pwd\":\"p1,p2\"," +
                    "\"video_urls\":\"v.mp4\",\"video_pwd\":\"vp\",\"expire_time\":1730000600000," +
                    "\"file_type\":\"mp4\",\"play_time\":6",
            ),
        )
        assertTrue(p.success)
        assertEquals("20000", p.code)
        assertEquals(
            listOf(AlertRecord("evt-1", "cam-uid-1", 1730000000123L, 0, 1, "mp4", 6, true)),
            p.records,
        )
        val m = p.media.getValue("evt-1")
        assertEquals("a.jpg", m.picUrl)
        assertEquals("p1", m.picPwd)
        assertEquals("v.mp4", m.videoUrl)
        assertEquals("vp", m.videoPwd)
        assertEquals(1730000600000L, m.expireMs)
        assertEquals(1730000000123L, p.oldestTimeMs)
        assertEquals(1, p.rawCount)
    }

    @Test
    fun `blank id falls back to uid underscore time`() {
        val p = page(item(id = ""), item(id = null, uid = "cam-uid-2", time = "1730000000999"))
        assertEquals(listOf("cam-uid-1_1730000000123", "cam-uid-2_1730000000999"), p.records.map { it.key })
    }

    @Test
    fun `first non empty pic url keeps its aligned pwd`() {
        val p = page(item(extra = "\"pic_urls\":\",b.jpg\",\"pic_pwd\":\",p2\""))
        val m = p.media.getValue("evt-1")
        assertEquals("b.jpg", m.picUrl)
        assertEquals("p2", m.picPwd)
    }

    @Test
    fun `numbers as strings and code as number are accepted`() {
        val p = page(
            item(time = "\"1730000000123\"", extra = "\"type\":\"0\",\"sub_type\":\"3\",\"expire_time\":\"1730000600000\""),
            code = "20000",
        )
        assertTrue(p.success)
        assertEquals("20000", p.code)
        val r = p.records.single()
        assertEquals(1730000000123L, r.timeMs)
        assertEquals(3, r.subType)
        assertEquals(1730000600000L, p.media.getValue("evt-1").expireMs)
    }

    @Test
    fun `item without uid or with time 0 is dropped`() {
        val p = page(item(uid = null), item(id = "evt-2", time = "0"), item(id = "evt-3", uid = ""), item(id = "evt-4"))
        assertEquals(listOf("evt-4"), p.records.map { it.key })
        assertEquals(4, p.rawCount)
        assertEquals(setOf("evt-4"), p.media.keys)
    }

    @Test
    fun `no video url means hasClip false and null video fields`() {
        val p = page(item(extra = "\"pic_urls\":\"a.jpg\",\"pic_pwd\":\"p1\",\"video_urls\":\"\",\"video_pwd\":\"\""))
        assertFalse(p.records.single().hasClip)
        val m = p.media.getValue("evt-1")
        assertNull(m.videoUrl)
        assertNull(m.videoPwd)
        assertEquals("a.jpg", m.picUrl)
    }

    @Test
    fun `non success code yields empty page with the code`() {
        val p = AlertMapper.page(envelope("{\"code\":\"20202\"}"))
        assertEquals("20202", p.code)
        assertFalse(p.success)
        assertTrue(p.records.isEmpty())
        assertTrue(p.media.isEmpty())
        assertNull(p.oldestTimeMs)
        assertEquals(0, p.rawCount)
    }

    @Test
    fun `duplicate keys in one page are kept once`() {
        val p = page(item(), item(), item(id = "evt-2", time = "1730000000001"))
        assertEquals(listOf("evt-1", "evt-2"), p.records.map { it.key })
        assertEquals(1730000000001L, p.oldestTimeMs)
        assertEquals(3, p.rawCount)
    }

    @Test
    fun `MediaRef toString hides urls and pwds`() {
        val ref = MediaRef("https://cdn.example.test/a.jpg?sig=zz", "pic-secret-pwd", "https://cdn.example.test/v.mp4", "vid-secret-pwd", 5L)
        val text = ref.toString()
        listOf("cdn.example", "a.jpg", "sig=", "pic-secret", "vid-secret", "v.mp4").forEach {
            assertFalse(text.contains(it), it)
        }
        val rec = AlertRecord("evt-key-xyz", "cam-uid-xyz", 1730000000123L, 0, 1, "mp4", 0, true).toString()
        assertFalse(rec.contains("evt-key-xyz"))
        assertFalse(rec.contains("cam-uid-xyz"))
        assertFalse(rec.contains("1730000000123"))
    }

    @Test
    fun `13 digit time is kept as is`() {
        val r = page(item(time = "1730000000123")).records.single()
        assertEquals(1730000000123L, r.timeMs)
    }

    @Test
    fun `absent play_time maps to 0`() {
        val p = page(item(extra = "\"video_urls\":\"v.mp4\",\"video_pwd\":\"vp\",\"file_type\":\"mp4\""))
        assertEquals(0, p.records.single().playTimeS)
        assertTrue(p.records.single().hasClip)
    }

    @Test
    fun `http media urls are rewritten to https with path and query kept`() {
        val p = page(
            item(
                extra = "\"pic_urls\":\"http://cdn.example.test/p/a.jpg?x=1&y=2\",\"pic_pwd\":\"p1\"," +
                    "\"video_urls\":\"http://cdn.example.test/v/b.mp4?x=3\",\"video_pwd\":\"vp\"",
            ),
        )
        val m = p.media.getValue("evt-1")
        assertEquals("https://cdn.example.test/p/a.jpg?x=1&y=2", m.picUrl)
        assertEquals("https://cdn.example.test/v/b.mp4?x=3", m.videoUrl)
    }

    @Test
    fun `secure url keeps https and non default port and leaves unparseable text`() {
        assertEquals("https://cdn.example.test/a.jpg", MediaHttp.secureUrl("https://cdn.example.test/a.jpg"))
        assertEquals("https://cdn.example.test:8080/a.jpg", MediaHttp.secureUrl("http://cdn.example.test:8080/a.jpg"))
        assertEquals("https://cdn.example.test/a.jpg", MediaHttp.secureUrl(" http://cdn.example.test:80/a.jpg "))
        assertEquals("a.jpg", MediaHttp.secureUrl("a.jpg"))
    }

    @Test
    fun `data that is not an array yields no records`() {
        val p = AlertMapper.page(envelope("{\"code\":20000,\"data\":{\"x\":1}}"))
        assertTrue(p.success)
        assertTrue(p.records.isEmpty())
        assertEquals(0, p.rawCount)
    }
}
