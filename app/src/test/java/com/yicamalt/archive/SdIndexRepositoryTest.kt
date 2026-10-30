// FILE: SdIndexRepositoryTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for SD status and index fetch over ArchiveLinkPort.
//   SCOPE: 816 / 817 status incl. timeout and refused send; TNP 9031 / 9032 index (ok, empty, malformed, timeout,
//          not ready); TUTK 792 / 793 paging (endflag, total, rejected page, total 0, per-page timeout); foreign
//          reply types ignored; log markers and redaction.
//   DEPENDS: M-SD-ARCHIVE (SdIndexRepository, SdTimeline, SdTime, SdArchiveCodec), FakeArchivePort, SdFixtures,
//            TraceRecorder
//   LINKS: V-M-SD-ARCHIVE
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.archive

import com.yicamalt.archive.SdFixtures.utc
import com.yicamalt.camera.TransportType
import com.yicamalt.test.TraceRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

// START_MODULE_MAP
//   now / range - fixed wall time and the 30-day request range.
//   t(iso) - STimeDay of an ISO instant in the UTC wire zone.
//   tnpPort() / tutkPort() - fake ports per transport.
//   repo(port) - repository with UTC wire zone and virtual clock.
//   SdIndexRepositoryTest - status, TNP index, TUTK paging, filtering, log redaction.
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-03: research Pattern 3 / 6 fetch rules and D-07 error results.
// END_CHANGE_SUMMARY

@OptIn(ExperimentalCoroutinesApi::class)
class SdIndexRepositoryTest {

    private val recorder = TraceRecorder()
    private val utcZone = ZoneOffset.UTC
    private val now = utc("2026-10-30T12:00:00Z")

    @BeforeEach fun setUp() { recorder.plant() }
    @AfterEach fun tearDown() { recorder.unplant() }

    private fun t(iso: String) = SdTime.toWire(utc(iso), utcZone)
    private fun tnpPort() = FakeArchivePort(TransportType.TNP, encrypted = true, bigEndian = true)
    private fun tutkPort() = FakeArchivePort(TransportType.TUTK, encrypted = true, bigEndian = false)
    private fun TestScope.repo(port: ArchiveLinkPort) =
        SdIndexRepository(port, wireZone = utcZone, timeoutMs = 10_000, clock = { currentTime })

    private val pageA = listOf(t("2026-10-30T10:00:00Z") to 60, t("2026-10-30T10:01:00Z") to 60) // adjacent: 1 run
    private val pageB = listOf(t("2026-10-30T11:00:00Z") to 60, t("2026-10-30T11:30:00Z") to 60)
    private val pageC = listOf(t("2026-10-30T11:45:00Z") to 60)
    private val allEntries = (pageA + pageB + pageC).map { IndexEntry(it.first, it.second) }

    // START_BLOCK_STATUS_TESTS
    @Test
    fun `status 817 tfstat 5 is NoCard`() = runTest {
        val port = tnpPort()
        port.onSend = { type, _ -> if (type == 816) port.reply(817, SdFixtures.deviceInfo(tfstat = 5)) }
        assertEquals(SdStatus.NoCard, repo(port).fetchStatus())
        assertEquals(816, port.sent[0].type)
        assertArrayEquals(ByteArray(4), port.sent[0].payload)
    }

    @Test
    fun `status TUTK reads capacity little-endian`() = runTest {
        val port = tutkPort()
        port.onSend = { type, _ ->
            if (type == 816) port.reply(817, SdFixtures.deviceInfo(tfstat = 0, total = 100, bigEndian = false))
        }
        assertEquals(SdStatus.Ok, repo(port).fetchStatus())
    }

    @Test
    fun `status timeout is Unknown after 10 s`() = runTest {
        val port = tnpPort()
        assertEquals(SdStatus.Unknown, repo(port).fetchStatus())
        assertTrue(currentTime >= 10_000, "virtual time $currentTime")
    }

    @Test
    fun `status send refused is Unknown without waiting`() = runTest {
        val port = tnpPort().apply { sendResult = false }
        assertEquals(SdStatus.Unknown, repo(port).fetchStatus())
        assertEquals(0, currentTime)
    }
    // END_BLOCK_STATUS_TESTS

    // START_BLOCK_TNP_INDEX_TESTS
    @Test
    fun `TNP index ok`() = runTest {
        val port = tnpPort()
        port.onSend = { type, _ ->
            if (type == 9031) {
                port.reply(9032, SdFixtures.tnpIndex(t("2026-10-30T09:00:00Z") to 60, t("2026-10-30T10:00:00Z") to 120))
            }
        }
        val result = repo(port).fetchIndex(now)
        assertTrue(result is IndexResult.Ok, "$result")
        assertEquals(2, (result as IndexResult.Ok).segments.size)
        assertEquals(9031, port.sent[0].type)
        val expected = SdArchiveCodec.listEventsRequest(
            SdTime.toWire(now - SdTimeline.INDEX_RANGE_MS, utcZone),
            SdTime.toWire(now, utcZone),
            bigEndian = true,
        )
        assertArrayEquals(expected, port.sent[0].payload)
    }

    @Test
    fun `TNP index count 0 is Ok empty`() = runTest {
        val port = tnpPort()
        port.onSend = { type, _ -> if (type == 9031) port.reply(9032, SdFixtures.tnpIndex()) }
        assertEquals(IndexResult.Ok(emptyList()), repo(port).fetchIndex(now))
    }

    @Test
    fun `TNP reply under 12 bytes is Malformed`() = runTest {
        val port = tnpPort()
        port.onSend = { type, _ -> if (type == 9031) port.reply(9032, ByteArray(8)) }
        assertEquals(IndexResult.Malformed, repo(port).fetchIndex(now))
    }

    @Test
    fun `TNP index timeout`() = runTest {
        val port = tnpPort()
        assertEquals(IndexResult.Timeout, repo(port).fetchIndex(now))
        assertTrue(currentTime >= 10_000, "virtual time $currentTime")
    }

    @Test
    fun `TNP send refused is NotReady`() = runTest {
        val port = tnpPort().apply { sendResult = false }
        assertEquals(IndexResult.NotReady, repo(port).fetchIndex(now))
        assertEquals(0, currentTime)
    }
    // END_BLOCK_TNP_INDEX_TESTS

    // START_BLOCK_TUTK_INDEX_TESTS
    @Test
    fun `TUTK three pages`() = runTest {
        val port = tutkPort()
        port.onSend = { type, _ ->
            if (type == 792) {
                port.reply(793, SdFixtures.tutkPage(5, 0, false, pageA))
                port.reply(793, SdFixtures.tutkPage(5, 1, false, pageB))
                port.reply(793, SdFixtures.tutkPage(5, 2, true, pageC))
            }
        }
        val result = repo(port).fetchIndex(now)
        assertEquals(IndexResult.Ok(SdTimeline.fromEntries(allEntries, utcZone)), result)
        assertEquals(4, (result as IndexResult.Ok).segments.size) // pageA is one adjacent run
        assertEquals(792, port.sent[0].type)
        val expected = SdArchiveCodec.listEventsRequest(
            SdTime.toWire(now - SdTimeline.INDEX_RANGE_MS, utcZone),
            SdTime.toWire(now, utcZone),
            bigEndian = false,
        )
        assertArrayEquals(expected, port.sent[0].payload)
    }

    @Test
    fun `TUTK stops when total is reached without endflag`() = runTest {
        val port = tutkPort()
        port.onSend = { type, _ ->
            if (type == 792) {
                port.reply(793, SdFixtures.tutkPage(4, 0, false, pageA))
                port.reply(793, SdFixtures.tutkPage(4, 1, false, pageB))
            }
        }
        val result = repo(port).fetchIndex(now)
        assertTrue(result is IndexResult.Ok, "$result")
        assertEquals(3, (result as IndexResult.Ok).segments.size)
        assertEquals(0, currentTime)
    }

    @Test
    fun `TUTK rejected page ignored`() = runTest {
        val port = tutkPort()
        port.onSend = { type, _ ->
            if (type == 792) {
                port.reply(793, SdFixtures.tutkPage(5, 0, false, pageA))
                port.reply(793, SdFixtures.tutkPage(5, 1, false, pageB, countOverride = 3)) // count mismatch
                port.reply(793, SdFixtures.tutkPage(5, 1, false, pageB))
                port.reply(793, SdFixtures.tutkPage(5, 2, true, pageC))
            }
        }
        assertEquals(IndexResult.Ok(SdTimeline.fromEntries(allEntries, utcZone)), repo(port).fetchIndex(now))
    }

    @Test
    fun `TUTK total 0 is Ok empty`() = runTest {
        val port = tutkPort()
        port.onSend = { type, _ -> if (type == 792) port.reply(793, SdFixtures.tutkPage(0, 0, false, emptyList())) }
        assertEquals(IndexResult.Ok(emptyList()), repo(port).fetchIndex(now))
        assertEquals(0, currentTime)
    }

    @Test
    fun `TUTK per-page timeout`() = runTest {
        val port = tutkPort()
        port.onSend = { type, _ -> if (type == 792) port.reply(793, SdFixtures.tutkPage(5, 0, false, pageA)) }
        assertEquals(IndexResult.Timeout, repo(port).fetchIndex(now))
        assertTrue(currentTime >= 10_000, "virtual time $currentTime")
    }

    @Test
    fun `TUTK send refused is NotReady`() = runTest {
        val port = tutkPort().apply { sendResult = false }
        assertEquals(IndexResult.NotReady, repo(port).fetchIndex(now))
    }
    // END_BLOCK_TUTK_INDEX_TESTS

    @Test
    fun `replies of other types are ignored`() = runTest {
        val port = tnpPort()
        port.onSend = { type, _ -> if (type == 9031) port.reply(817, SdFixtures.deviceInfo(tfstat = 0, total = 100)) }
        assertEquals(IndexResult.Timeout, repo(port).fetchIndex(now))

        val port2 = tnpPort()
        port2.onSend = { type, _ ->
            if (type == 9031) {
                port2.reply(817, SdFixtures.deviceInfo(tfstat = 0, total = 100))
                port2.reply(9032, SdFixtures.tnpIndex(t("2026-10-30T09:00:00Z") to 60))
            }
        }
        val result = repo(port2).fetchIndex(now)
        assertTrue(result is IndexResult.Ok && result.segments.size == 1, "$result")
    }

    @Test
    fun `logs carry block markers and no dates`() = runTest {
        val port = tnpPort()
        port.onSend = { type, _ ->
            when (type) {
                816 -> port.reply(817, SdFixtures.deviceInfo(tfstat = 0, total = 30_000))
                9031 -> port.reply(9032, SdFixtures.tnpIndex(t("2026-10-30T09:00:00Z") to 60))
            }
        }
        val r = repo(port)
        r.fetchStatus()
        r.fetchIndex(now)
        recorder.assertMarkerAppeared("BLOCK_SD_STATUS")
        recorder.assertMarkerAppeared("BLOCK_FETCH_INDEX")
        val text = recorder.bufferText()
        assertFalse(Regex("""20\d\d-""").containsMatchIn(text), text)
    }
}
