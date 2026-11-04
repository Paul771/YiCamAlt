// FILE: EventsFeedTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Verify the pure events-feed logic: day grouping and headers, camera / day filters, calendar marks,
//     time labels, subtitle and empty texts (EVT-01, EVT-02, EVT-05, EVT-06; D-02..D-04).
//   SCOPE: JVM-only, synthetic rows (no real ids, keys or names); fixed zones Europe/Moscow and Europe/Berlin (DST).
//   DEPENDS: M-UI-EVENTS (EventsFeed), M-EVENT (SyncResult), M-DATABASE (EventEntity)
//   LINKS: V-M-UI-EVENTS, M-UI-EVENTS
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT

// START_MODULE_MAP
//   row - builds a synthetic EventEntity.
//   at - epoch ms of a local date-time in a zone.
//   EventsFeedTest - one test per behaviour of plan 05-04 Task 1 (EventsFeed part).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 05-04: initial grouping / filter / label / text tests.
// END_CHANGE_SUMMARY
package com.yicamalt.ui.events

import com.yicamalt.database.EventEntity
import com.yicamalt.event.SyncResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val MSK: ZoneId = ZoneId.of("Europe/Moscow")
private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
    LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

private fun row(key: String, device: String, timeMs: Long, thumb: String? = null, clip: Boolean = true) =
    EventEntity(key, device, timeMs, 1, 0, "mp4", clip, 6, thumb, 0L)

class EventsFeedTest {

    private val now = at(MSK, 2026, 10, 28, 15)
    private val cams = mapOf("dev-a" to "Прихожая", "dev-b" to "Двор")

    @Test
    fun `groups by local day newest first with headers Сегодня Вчера and d MMMM`() {
        val rows = listOf(
            row("k1", "dev-a", at(MSK, 2026, 10, 26, 9)),
            row("k2", "dev-a", at(MSK, 2026, 10, 28, 10)),
            row("k3", "dev-b", at(MSK, 2026, 10, 27, 23, 59)),
            row("k4", "dev-b", at(MSK, 2026, 10, 28, 14)),
        )
        val sections = EventsFeed.build(rows, cams, emptySet(), null, MSK, now)
        assertEquals(listOf("Сегодня", "Вчера", "26 октября"), sections.map { it.header })
        assertEquals(listOf("k4", "k2"), sections[0].cards.map { it.key })
        assertEquals(LocalDate.of(2026, 10, 27), sections[1].day)
        assertEquals(listOf("k1"), sections[2].cards.map { it.key })
    }

    @Test
    fun `camera filter empty means all cameras`() {
        val rows = listOf(row("k1", "dev-a", now - 1000), row("k2", "dev-b", now - 2000), row("k3", "dev-x", now - 3000))
        val cards = EventsFeed.build(rows, cams, emptySet(), null, MSK, now).flatMap { it.cards }
        assertEquals(listOf("k1", "k2", "k3"), cards.map { it.key })
    }

    @Test
    fun `camera filter keeps only selected device ids`() {
        val rows = listOf(row("k1", "dev-a", now - 1000), row("k2", "dev-b", now - 2000), row("k3", "dev-a", now - 3000))
        val cards = EventsFeed.build(rows, cams, setOf("dev-a"), null, MSK, now).flatMap { it.cards }
        assertEquals(listOf("k1", "k3"), cards.map { it.key })
        assertTrue(cards.all { it.cameraName == "Прихожая" && it.knownCamera })
    }

    @Test
    fun `unknown camera events show Камера and only appear under all`() {
        val rows = listOf(row("k1", "dev-x", now - 1000), row("k2", "dev-a", now - 2000))
        val all = EventsFeed.build(rows, cams, emptySet(), null, MSK, now).flatMap { it.cards }
        val unknown = all.first { it.key == "k1" }
        assertEquals("Камера", unknown.cameraName)
        assertFalse(unknown.knownCamera)
        val filtered = EventsFeed.build(rows, cams, setOf("dev-a", "dev-b"), null, MSK, now).flatMap { it.cards }
        assertEquals(listOf("k2"), filtered.map { it.key })
        // A known camera with a blank name also falls back to «Камера».
        val blank = EventsFeed.build(listOf(row("k3", "dev-c", now)), mapOf("dev-c" to " "), emptySet(), null, MSK, now)
        assertEquals("Камера", blank.single().cards.single().cameraName)
        assertTrue(blank.single().cards.single().knownCamera)
    }

    @Test
    fun `day filter null shows the whole window`() {
        val rows = (0 until 5).map { d -> row("k$d", "dev-a", now - d * 86_400_000L) }
        val sections = EventsFeed.build(rows, cams, emptySet(), null, MSK, now)
        assertEquals(5, sections.size)
        assertEquals(5, sections.sumOf { it.cards.size })
    }

    @Test
    fun `day filter shows only that local day`() {
        // Berlin 2026-03-29 is a 23 h day (spring forward), 2026-10-25 a 25 h day (fall back).
        val berlinNow = at(BERLIN, 2026, 10, 26, 12)
        val rows = listOf(
            row("before", "dev-a", at(BERLIN, 2026, 10, 24, 23, 59)),
            row("start", "dev-a", at(BERLIN, 2026, 10, 25, 0, 0)),
            row("late", "dev-a", at(BERLIN, 2026, 10, 25, 23, 30)),
            row("after", "dev-a", at(BERLIN, 2026, 10, 26, 0, 0)),
            row("spring", "dev-a", at(BERLIN, 2026, 3, 29, 23, 30)),
        )
        val fall = EventsFeed.build(rows, cams, emptySet(), LocalDate.of(2026, 10, 25), BERLIN, berlinNow)
        assertEquals(listOf("late", "start"), fall.flatMap { it.cards }.map { it.key })
        assertEquals(1, fall.size)
        val spring = EventsFeed.build(rows, cams, emptySet(), LocalDate.of(2026, 3, 29), BERLIN, berlinNow)
        assertEquals(listOf("spring"), spring.flatMap { it.cards }.map { it.key })
        val empty = EventsFeed.build(rows, cams, emptySet(), LocalDate.of(2026, 10, 20), BERLIN, berlinNow)
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `days with events feed the calendar marks`() {
        val times = listOf(at(MSK, 2026, 10, 28, 0, 0), at(MSK, 2026, 10, 27, 23, 59), at(MSK, 2026, 10, 27, 1), at(MSK, 2026, 10, 1, 12))
        assertEquals(
            setOf(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 1)),
            EventsFeed.daysWithEvents(times, MSK),
        )
        assertTrue(EventsFeed.daysWithEvents(emptyList(), MSK).isEmpty())
    }

    @Test
    fun `time label is HH mm in the zone`() {
        val t = at(MSK, 2026, 10, 28, 7, 5)
        val card = EventsFeed.build(listOf(row("k1", "dev-a", t, thumb = "20261028/x.jpg")), cams, emptySet(), null, MSK, now)
            .single().cards.single()
        assertEquals("07:05", card.timeLabel)
        assertEquals(t, card.timeMs)
        assertEquals("20261028/x.jpg", card.thumbPath)
        assertTrue(card.hasClip)
        // The same instant in Berlin (UTC+1 in winter, MSK is UTC+3) is two hours earlier.
        val berlin = EventsFeed.build(listOf(row("k1", "dev-a", t)), cams, emptySet(), null, BERLIN, now).single().cards.single()
        assertEquals("05:05", berlin.timeLabel)
    }

    @Test
    fun `card toString hides key device and name`() {
        val card = EventsFeed.build(listOf(row("secret-key", "dev-a", now)), cams, emptySet(), null, MSK, now).single().cards.single()
        val text = card.toString()
        assertFalse(text.contains("secret-key"))
        assertFalse(text.contains("dev-a"))
        assertFalse(text.contains("Прихожая"))
    }

    @Test
    fun `subtitle texts`() {
        assertEquals("Обновлено только что", EventsFeed.subtitle(SyncResult.Fetched(3, 1, false), null))
        assertEquals("Обновлено 3 мин назад", EventsFeed.subtitle(SyncResult.TooEarly(3), null))
        assertEquals("Обновлено только что", EventsFeed.subtitle(SyncResult.TooEarly(0), null))
        assertEquals("Нет сети, показан кэш", EventsFeed.subtitle(SyncResult.Offline, null))
        assertEquals("Сессия истекла", EventsFeed.subtitle(SyncResult.Unauthorized, null))
        assertEquals("Не удалось обновить, показан кэш", EventsFeed.subtitle(SyncResult.Failed("x"), null))
        assertEquals("Обновлено 2 мин назад", EventsFeed.subtitle(SyncResult.InProgress, "Обновлено 2 мин назад"))
        assertNull(EventsFeed.subtitle(SyncResult.InProgress, null))
        assertNull(EventsFeed.subtitle(null, null))
    }

    @Test
    fun `empty texts`() {
        assertEquals("Событий пока нет", EventsFeed.emptyText(totalRows = 0, shownRows = 0))
        assertEquals("Нет событий для выбранного фильтра", EventsFeed.emptyText(totalRows = 4, shownRows = 0))
        assertNull(EventsFeed.emptyText(totalRows = 4, shownRows = 2))
    }
}
