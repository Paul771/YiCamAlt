// FILE: AlertProbeShapeTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin the value-free alert list shape analyzer and line formatter used by the debug alert probe.
//   SCOPE: list counters (code, count, keys, digit counts, expire minutes, uid match, ext and file_type classes,
//     password lengths, per-camera counts, oldest age), error codes, unsafe file_type values, media line format,
//     RedactionScanner sweep, MediaCandidate redacted toString.
//   DEPENDS: M-DEBUG-PROBE (AlertProbeShape), RedactionScanner
//   LINKS: V-M-DEBUG-PROBE, SEC-01, EVT-01
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.debug

import com.yicamalt.event.MediaCrypto
import com.yicamalt.test.RedactionScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   AlertProbeShapeTest - analyzeList / formatList / formatMedia scenarios.
//   item / body - synthetic alert list fixtures with fake ids, fake https URLs and fake pwds.
//   SECRETS - every fixture value that must never appear in a formatted line.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-00: initial AlertProbeShape scenarios (test-first).
// END_CHANGE_SUMMARY

class AlertProbeShapeTest {

    private val scanner = RedactionScanner()
    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    private val uidA = "SYNTHUIDAAAA01"
    private val uidB = "SYNTHUIDBBBB02"
    private val uidC = "SYNTHUIDCCCC03"
    private val host = "media-synth.example-cdn.test"
    private val pwd16 = "pwdSynth16chars!"
    private val vpwd16 = "vidSynth16chars#"
    private val idA = "evtSynthId001"

    private val cameras = listOf(uidA to false, uidB to true)

    private fun item(
        id: String,
        uid: String,
        time: Long,
        picPath: String,
        fileType: String?,
        picPwd: String = pwd16,
        videoUrl: String = "https://$host/v/clip.mp4?sig=SYNTHSIG",
        videoPwd: String = vpwd16,
    ): String {
        val ft = if (fileType == null) "" else ""","file_type":"$fileType""""
        return """{"id":"$id","uid":"$uid","time":$time,"type":0,"sub_type":1,""" +
            """"pic_urls":"https://$host/$picPath?sig=SYNTHSIG","pic_pwd":"$picPwd",""" +
            """"video_urls":"$videoUrl","video_pwd":"$videoPwd","expire_time":${now + 10 * 60_000}$ft}"""
    }

    private fun body(vararg items: String) = """{"code":"20000","data":[${items.joinToString(",")}]}"""

    private val threeItems = body(
        item(idA, uidA, now - 1 * day, "p/a.jpg", "mp4"),
        item("", uidA, now - 2 * day, "p/b.tar", "ts"),
        item("evtSynthId003", uidC, now - 3 * day, "p/c.yitar", null),
    )

    private val secrets = listOf(
        uidA, uidB, uidC, host, pwd16, vpwd16, idA, "evtSynthId003", "SYNTHSIG", "https://",
        (now - day).toString(), (now + 10 * 60_000).toString(),
    )

    @Test
    fun `list shape reports code count keys digits and classes`() {
        val shape = AlertProbeShape.analyzeList(threeItems, now, cameras)
        val line = AlertProbeShape.formatList(shape)
        assertTrue(line.startsWith("list code=20000 count=3 "), line)
        assertTrue(
            line.contains(
                "keys=[expire_time,file_type,id,pic_pwd,pic_urls,sub_type,time,type,uid,video_pwd,video_urls]",
            ),
            line,
        )
        assertTrue(line.contains("timeDigits=13 "), line)
        assertTrue(line.contains("expireDigits=13 "), line)
        assertTrue(line.contains("expireInMin=10..10 "), line)
        assertTrue(line.contains("uidMatch=2/3 "), line)
        assertTrue(line.contains("idBlank=1 "), line)
        assertTrue(line.contains("oldestAgeDays=3 "), line)
        assertTrue(line.contains("picExt=jpg:1,tar:1,yitar:1,other:0 "), line)
        assertTrue(line.contains("fileType=mp4:1,ts:1,empty:1,other:0 "), line)
        assertTrue(line.contains("picPwdLen=16 "), line)
        assertTrue(line.contains("videoPwdLen=16 "), line)
    }

    @Test
    fun `per camera counts carry index and shared flag only`() {
        val shape = AlertProbeShape.analyzeList(
            body(
                item("e1", uidA, now - day, "p/a.jpg", "mp4"),
                item("e2", uidA, now - day, "p/a.jpg", "mp4"),
                item("e3", uidB, now - day, "p/a.jpg", "mp4"),
            ),
            now, cameras,
        )
        val line = AlertProbeShape.formatList(shape)
        assertTrue(line.contains("cameras=c0(shared=false):2,c1(shared=true):1,unknown:0"), line)
    }

    @Test
    fun `non success code and missing data`() {
        val l1 = AlertProbeShape.formatList(AlertProbeShape.analyzeList("""{"code":"20202"}""", now, cameras))
        assertTrue(l1.startsWith("list code=20202 count=0"), l1)
        val l2 = AlertProbeShape.formatList(AlertProbeShape.analyzeList("<html>not json", now, cameras))
        assertTrue(l2.startsWith("list code=parse-error"), l2)
        val l3 = AlertProbeShape.formatList(AlertProbeShape.analyzeList(null, now, cameras))
        assertTrue(l3.startsWith("list code=no-body"), l3)
        assertNull(AlertProbeShape.analyzeList("""{"code":"20202"}""", now, cameras).candidate)
    }

    @Test
    fun `file_type value outside a-z0-9 up to 8 chars is reported as other`() {
        val shape = AlertProbeShape.analyzeList(
            body(
                item("e1", uidA, now - day, "p/a.jpg", "Mp4!x"),
                item("e2", uidA, now - day, "p/a.jpg", "abcdefghij"),
                item("e3", uidA, now - day, "p/a.jpg", "flv"),
            ),
            now, cameras,
        )
        val line = AlertProbeShape.formatList(shape)
        assertTrue(line.contains("fileType=mp4:0,ts:0,flv:1,empty:0,other:2"), line)
        assertFalse(line.contains("Mp4!x"))
        assertFalse(line.contains("abcdefghij"))
    }

    @Test
    fun `media line formats http size bucket decrypt magic codec`() {
        val preview = AlertProbeShape.formatMedia("preview", 200, 20_000L, true, MediaCrypto.Magic.JPEG, null, null)
        assertTrue(preview.startsWith("preview http=200 sizeBucket=16-64KB decrypt=ok magic=jpeg"), preview)
        assertTrue(preview.contains("tarEntry=-"), preview)
        val clip = AlertProbeShape.formatMedia("clip", 200, 2_000_000L, true, MediaCrypto.Magic.MP4, MediaCrypto.Codec.HEVC, null)
        assertTrue(clip.contains("sizeBucket=1-4MB"), clip)
        assertTrue(clip.contains("magic=mp4"), clip)
        assertTrue(clip.contains("codec=hevc"), clip)
        val tar = AlertProbeShape.formatMedia("preview", 200, 100L, false, MediaCrypto.Magic.UNKNOWN, null, true)
        assertTrue(tar.contains("sizeBucket=<16KB decrypt=fail magic=unknown"), tar)
        assertTrue(tar.contains("tarEntry=yes"), tar)
        val raw = AlertProbeShape.formatMedia("clip", 403, 5_000_000L, null, null, null, null)
        assertTrue(raw.contains("http=403 sizeBucket=>4MB decrypt=raw magic=-"), raw)
        val err = AlertProbeShape.formatMedia("clip", null, null, null, null, null, null, error = "SocketTimeoutException")
        assertTrue(err.contains("http=-1"), err)
        assertTrue(err.contains("error=SocketTimeoutException"), err)
    }

    @Test
    fun `every formatted line passes RedactionScanner and contains no fixture uid url host pwd or epoch`() {
        val lines = listOf(
            AlertProbeShape.formatList(AlertProbeShape.analyzeList(threeItems, now, cameras)),
            AlertProbeShape.formatMedia("preview", 200, 20_000L, true, MediaCrypto.Magic.JPEG, null, false),
            AlertProbeShape.formatMedia("clip", 200, 300_000L, true, MediaCrypto.Magic.MP4, MediaCrypto.Codec.AVC, null),
            AlertProbeShape.analyzeList(threeItems, now, cameras).candidate.toString(),
        )
        lines.forEach { scanner.assertClean(it, extraLiterals = secrets) }
    }

    @Test
    fun `first media candidate keeps url and pwd in memory and its toString is redacted`() {
        val shape = AlertProbeShape.analyzeList(threeItems, now, cameras)
        assertNotNull(shape.candidate)
        val c = shape.candidate!!
        assertTrue(c.picUrl.contains("p/a.jpg"))
        assertEquals(pwd16, c.picPwd)
        assertTrue(c.videoUrl.contains("clip.mp4"))
        assertEquals(vpwd16, c.videoPwd)
        assertEquals("mp4", c.fileType)
        assertEquals("jpg", c.picExt)
        val s = c.toString()
        assertEquals("MediaCandidate(picExt=jpg, hasClip=true)", s)
        secrets.forEach { assertFalse(s.contains(it), "toString leaked a fixture value") }
    }

    @Test
    fun `comma lists use the first non empty element aligned by index`() {
        val shape = AlertProbeShape.analyzeList(
            body(
                item(
                    "e1", uidA, now - day, "p/a.jpg", "mp4",
                    picPwd = ",$pwd16",
                ).replace("\"pic_urls\":\"", "\"pic_urls\":\","),
            ),
            now, cameras,
        )
        val c = shape.candidate!!
        assertEquals(pwd16, c.picPwd)
        assertTrue(c.picUrl.startsWith("https://"))
    }
}
