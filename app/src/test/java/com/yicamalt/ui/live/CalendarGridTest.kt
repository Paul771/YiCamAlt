// FILE: CalendarGridTest.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Executable contract for the archive day dialog month grid (SD-02, D-02).
//   SCOPE: 42-cell Monday-first grid, enabled / marked / today / selected flags, navigable months, Russian texts.
//   DEPENDS: M-UI-LIVEVIEW (CalendarGrid, DayCell)
//   LINKS: V-M-UI-LIVEVIEW
//   ROLE: TEST
//   MAP_MODE: LOCALS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth

// START_MODULE_MAP
//   CalendarGridTest - one test per behaviour bullet of plan 04-07 Task 1 (CalendarGrid).
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-07: calendar grid vectors.
// END_CHANGE_SUMMARY

class CalendarGridTest {

    private val today = LocalDate.of(2026, 10, 30)
    private val october = YearMonth.of(2026, 10)

    private fun cellOf(cells: List<DayCell>, date: LocalDate): DayCell = cells.single { it.date == date }

    @Test
    fun `October 2026 grid has 42 Monday-first cells`() {
        val cells = CalendarGrid.month(october, today, emptySet(), today)
        assertEquals(42, cells.size)
        assertNull(cells[0].date)
        assertNull(cells[2].date)
        assertEquals(LocalDate.of(2026, 10, 1), cells[3].date)
        assertEquals(LocalDate.of(2026, 10, 31), cells[33].date)
        assertTrue(cells.drop(34).all { it.date == null })
        assertTrue(cells.filter { it.date == null }.none { it.enabled || it.marked || it.selected })
    }

    @Test
    fun `recorded days are marked and enabled, others disabled`() {
        val rec = LocalDate.of(2026, 10, 28)
        val cells = CalendarGrid.month(october, today, setOf(rec), today)
        val recorded = cellOf(cells, rec)
        assertTrue(recorded.marked)
        assertTrue(recorded.enabled)
        val empty = cellOf(cells, LocalDate.of(2026, 10, 27))
        assertFalse(empty.marked)
        assertFalse(empty.enabled)
    }

    @Test
    fun `today stays enabled without recordings and selected follows the selection`() {
        val rec = LocalDate.of(2026, 10, 28)
        val cells = CalendarGrid.month(october, today, setOf(rec), rec)
        val t = cellOf(cells, today)
        assertTrue(t.enabled)
        assertTrue(t.isToday)
        assertFalse(t.marked)
        assertFalse(t.selected)
        assertTrue(cellOf(cells, rec).selected)
        assertEquals(1, cells.count { it.selected })
        assertEquals(1, cells.count { it.isToday })
    }

    @Test
    fun `monthsWithRecordings spans the recorded range up to today`() {
        assertEquals(
            listOf(YearMonth.of(2026, 9), october),
            CalendarGrid.monthsWithRecordings(setOf(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2)), today),
        )
        assertEquals(listOf(october), CalendarGrid.monthsWithRecordings(emptySet(), today))
    }

    @Test
    fun `monthsWithRecordings fills gaps between recorded months`() {
        assertEquals(
            listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9), october),
            CalendarGrid.monthsWithRecordings(setOf(LocalDate.of(2026, 8, 5)), today),
        )
    }

    @Test
    fun `title and weekday headers are Russian`() {
        assertEquals("Октябрь 2026", CalendarGrid.title(october))
        assertEquals(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"), CalendarGrid.weekdayHeaders())
    }
}
