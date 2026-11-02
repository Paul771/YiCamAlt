// FILE: AlertProbeShapeFormsTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pin the second-round value-free shape lines of the debug alert probe: URL field forms
//     (pic_urls / video_urls / video02_urls), uid vs every known identifier type, field kinds and video02 shape,
//     the https variant of an http media URL.
//   SCOPE: analyzeList with CameraIds + account ids + api host, formatUrlFields, formatUid, formatFields,
//     httpsVariant, schemeOf, RedactionScanner sweep over the new lines.
//   DEPENDS: M-DEBUG-PROBE (AlertProbeShape), RedactionScanner
//   LINKS: V-M-DEBUG-PROBE, SEC-01, EVT-01, EVT-03
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.debug

import com.yicamalt.test.RedactionScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// START_MODULE_MAP
//   AlertProbeShapeFormsTest - URL form, uid identifier match, field kind and https variant scenarios.
//   ev / body - synthetic events built from raw JSON fragments (fake ids, hosts, signatures, pwds).
//   SECRETS - every fixture value that must never appear in a formatted line.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - 05-00 follow-up after the first device run: URL fields are plural (pic_urls,
//     video_urls, video02_urls), media GETs died on cleartext, uid matched no deviceId. Test-first.
// END_CHANGE_SUMMARY

class AlertProbeShapeFormsTest {

    private val scanner = RedactionScanner()
    private val now = 1_760_000_000_000L

    private val dev0 = "SYNTHDEVAAAA0001"
    private val dev1 = "SYNTHDEVBBBB0002"
    private val p2p0 = "SYNP2PZZZZ00001"
    private val user = "77712345"
    private val host = "media-synth.example-cdn.test"
    private val apiHost = "api-synth.example-cdn.test"
    private val pwd = "pwdSynth16chars!"
    private val vpwd = "vidSynth16chars#"
    private val v2pwd = "v02Synth16chars%"

    private val cams = listOf(
        CameraIds(shared = false, ids = mapOf("dev" to dev0, "p2p" to p2p0)),
        CameraIds(shared = true, ids = mapOf("dev" to dev1)),
    )
    private val account = mapOf("user" to user)

    private fun ev(uid: String, extra: String) =
        """{"id":"evtSynth1","uid":"$uid","time":${now - 60_000},"type":1,"pic_pwd":"$pwd","video_pwd":"$vpwd",""" +
            """"file_type":"mp4",$extra}"""

    private fun body(vararg items: String) = """{"code":"20000","data":[${items.joinToString(",")}]}"""

    private val httpItem = ev(
        dev0,
        """"pic_urls":"http://$host/p/a.jpg?sig=SYNTHSIG","video_urls":"http://$host/v/c.mp4",""" +
            """"video02_urls":"","video02_pwd":"$v2pwd"""",
    )

    private val secrets = listOf(
        dev0, dev1, p2p0, user, host, apiHost, pwd, vpwd, v2pwd, "SYNTHSIG", "evtSynth1",
        "http://", "https://", "example-cdn", (now - 60_000).toString(),
    )

    @Test
    fun `string url field reports type elems scheme host labels query and ext`() {
        val shape = AlertProbeShape.analyzeList(body(httpItem), now, cams, account, apiHost)
        val lines = AlertProbeShape.formatUrlFields(shape)
        assertEquals(3, lines.size, lines.toString())
        val pic = lines[0]
        assertEquals(
            "urlshape field=pic_urls types=string:1 elems=1 urls=1 schemes=http:1,https:0,other:0 " +
                "hostLabels=3 ipHost=0 query=1 apiBase=1 ext=jpg:1",
            pic,
        )
        val clip = lines[1]
        assertTrue(clip.startsWith("urlshape field=video_urls types=string:1 elems=1 urls=1 schemes=http:1,"), clip)
        assertTrue(clip.contains(" query=0 "), clip)
        assertTrue(clip.endsWith(" ext=mp4:1"), clip)
        val v02 = lines[2]
        assertTrue(v02.startsWith("urlshape field=video02_urls types=string:1 elems=0 urls=0 "), v02)
    }

    @Test
    fun `array url field is detected and the candidate comes from the first array element`() {
        val item = ev(
            dev0,
            """"pic_urls":["","https://$host/p/a.jpg?sig=SYNTHSIG","https://$host/p/b.jpg"],""" +
                """"pic_pwd":["","$pwd","$pwd"],"video_urls":["https://$host/v/c.m3u8"]""",
        ).replace(""""pic_pwd":"$pwd",""", "")
        val shape = AlertProbeShape.analyzeList(body(item), now, cams, account, apiHost)
        val lines = AlertProbeShape.formatUrlFields(shape)
        assertTrue(lines[0].contains("types=array:1 elems=3 urls=2 schemes=http:0,https:2,other:0"), lines[0])
        assertTrue(lines[1].contains("types=array:1 elems=1 urls=1"), lines[1])
        assertTrue(lines[1].endsWith("ext=m3u8:1"), lines[1])
        assertTrue(lines[2].contains("types=missing:1 elems=0 urls=0"), lines[2])
        val c = shape.candidate!!
        assertTrue(c.picUrl.endsWith("/p/a.jpg?sig=SYNTHSIG"))
        assertEquals(pwd, c.picPwd)
        assertTrue(c.videoUrl.endsWith("c.m3u8"))
    }

    @Test
    fun `object and ip host forms are classified without values`() {
        val item = ev(
            dev0,
            """"pic_urls":{"a":"https://$host/p/a.jpg"},"video_urls":"https://10.1.2.3:8080/v/c.ts,ftp://$host/x"""",
        )
        val shape = AlertProbeShape.analyzeList(body(item), now, cams, account, null)
        val lines = AlertProbeShape.formatUrlFields(shape)
        assertTrue(lines[0].contains("types=object:1 elems=0 urls=0"), lines[0])
        assertTrue(lines[1].contains("elems=2 urls=2 schemes=http:0,https:1,other:1"), lines[1])
        assertTrue(lines[1].contains("ipHost=1"), lines[1])
        assertTrue(lines[1].contains("apiBase=-"), lines[1])
    }

    @Test
    fun `uid line reports per identifier type matches lengths and char class`() {
        val shape = AlertProbeShape.analyzeList(
            body(
                ev(p2p0, """"pic_urls":"""""),
                ev(dev0.substring(0, 10), """"pic_urls":"""""),
                ev(user, """"pic_urls":"""""),
                ev(dev1.lowercase(), """"pic_urls":"""""),
            ),
            now, cams, account, apiHost,
        )
        val line = AlertProbeShape.formatUid(shape)
        assertTrue(line.startsWith("uid n=4 lens=8,10,15,16 class=digits:1,lower-alnum:1,upper-alnum:2 "), line)
        assertTrue(line.contains("dev[have=2/2 lens=16 eq=0 ci=1 pre=1 suf=0 sub=1]"), line)
        assertTrue(line.contains("p2p[have=1/2 lens=15 eq=1 ci=1 pre=0 suf=0 sub=0]"), line)
        assertTrue(line.contains("user[have=1/1 lens=8 eq=1 ci=1 pre=0 suf=0 sub=0]"), line)
        assertTrue(line.endsWith(" any=3/4"), line)
    }

    @Test
    fun `fields line reports value kinds video02 keys and key equality counts`() {
        val item = ev(
            dev0,
            """"pic_urls":"https://$host/p/a.jpg","video_urls":"https://$host/v/c.mp4",""" +
                """"video02_urls":"https://$host/v/d.mp4","video02_pwd":"$vpwd","no_risk":false,"verification_tags":[]""",
        )
        val shape = AlertProbeShape.analyzeList(body(item, httpItem), now, cams, account, apiHost)
        val line = AlertProbeShape.formatFields(shape)
        assertTrue(line.startsWith("fields n=2 "), line)
        assertTrue(line.contains("kinds=["), line)
        assertTrue(line.contains("no_risk:bool"), line)
        assertTrue(line.contains("time:num"), line)
        assertTrue(line.contains("verification_tags:arr"), line)
        assertTrue(line.contains("video02_urls:str"), line)
        assertTrue(line.contains(" video02Keys=[video02_pwd,video02_urls] "), line)
        assertTrue(line.contains(" video02Present=1 "), line)
        assertTrue(line.contains(" video02PwdLen=16 "), line)
        assertTrue(line.contains(" sameKey=picVideo:0,video02Video:1 "), line)
        assertTrue(line.endsWith(" hosts=1"), line)
    }

    @Test
    fun `https variant keeps path and query and drops the default port`() {
        assertEquals("https://h.test/p/a.jpg?x=1", AlertProbeShape.httpsVariant("http://h.test/p/a.jpg?x=1"))
        assertEquals("https://h.test/p", AlertProbeShape.httpsVariant("http://h.test:80/p"))
        assertEquals("https://h.test:8080/p", AlertProbeShape.httpsVariant("http://h.test:8080/p"))
        assertNull(AlertProbeShape.httpsVariant("https://h.test/p"))
        assertNull(AlertProbeShape.httpsVariant("not a url"))
        assertEquals("http", AlertProbeShape.schemeOf("HTTP://h.test/p"))
        assertEquals("https", AlertProbeShape.schemeOf("https://h.test/p"))
        assertEquals("other", AlertProbeShape.schemeOf("ftp://h.test/p"))
        assertEquals("other", AlertProbeShape.schemeOf(""))
    }

    @Test
    fun `every new line passes RedactionScanner and carries no fixture value`() {
        val shape = AlertProbeShape.analyzeList(
            body(httpItem, ev(p2p0, """"pic_urls":["https://$host/p/a.jpg"]""")),
            now, cams, account, apiHost,
        )
        val lines = AlertProbeShape.formatUrlFields(shape) +
            AlertProbeShape.formatUid(shape) + AlertProbeShape.formatFields(shape) + AlertProbeShape.formatList(shape)
        lines.forEach { scanner.assertClean(it, extraLiterals = secrets) }
    }
}
