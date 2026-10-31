// FILE: CalendarGrid.kt
// VERSION: 0.1.0
// START_MODULE_CONTRACT
//   PURPOSE: Pure month grid for the archive day dialog (SD-02, D-02): recorded days marked and tappable, other days
//     disabled, today always tappable so the user can come back to the default day.
//   SCOPE: 42-cell Monday-first month, contiguous navigable month range (earliest recorded month .. latest of
//     recorded / today), Russian month title and weekday headers.
//   DEPENDS: java.time
//   LINKS: M-UI-LIVEVIEW, V-M-UI-LIVEVIEW
//   ROLE: RUNTIME
//   MAP_MODE: EXPORTS
// END_MODULE_CONTRACT
package com.yicamalt.ui.live

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// START_MODULE_MAP
//   DayCell - one grid cell (date null = padding), enabled / marked / isToday / selected flags
//   CalendarGrid - month, monthsWithRecordings, title, weekdayHeaders
// END_MODULE_MAP

// START_CHANGE_SUMMARY
//   LAST_CHANGE: v0.1.0 - GSD plan 04-07 GREEN: month grid. enabled = recorded || today.
//   PREVIOUS: v0.0.1 - GSD plan 04-07 RED: signatures only.
// END_CHANGE_SUMMARY

data class DayCell(val date: LocalDate?, val enabled: Boolean, val marked: Boolean, val isToday: Boolean, val selected: Boolean)

object CalendarGrid {
    private const val CELLS = 42
    private val RU: Locale = Locale.forLanguageTag("ru")
    private val HEADERS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    private val EMPTY = DayCell(null, enabled = false, marked = false, isToday = false, selected = false)

    // START_CONTRACT: month
    //   PURPOSE: Cells of one month, Monday first, padded to 6 weeks.
    //   INPUTS: { month: YearMonth; today: LocalDate; recorded: Set<LocalDate> - days with recordings;
    //     selected: LocalDate - currently selected day }
    //   OUTPUTS: { List<DayCell> - exactly 42 cells }
    //   SIDE_EFFECTS: none
    //   LINKS: D-02
    // END_CONTRACT: month
    fun month(month: YearMonth, today: LocalDate, recorded: Set<LocalDate>, selected: LocalDate): List<DayCell> {
        val leading = month.atDay(1).dayOfWeek.value - 1
        val out = ArrayList<DayCell>(CELLS)
        repeat(leading) { out += EMPTY }
        for (d in 1..month.lengthOfMonth()) {
            val date = month.atDay(d)
            val marked = date in recorded
            out += DayCell(date, enabled = marked || date == today, marked = marked, isToday = date == today, selected = date == selected)
        }
        while (out.size < CELLS) out += EMPTY
        return out
    }

    // START_CONTRACT: monthsWithRecordings
    //   PURPOSE: Months the dialog may navigate to.
    //   INPUTS: { recorded: Set<LocalDate>; today: LocalDate }
    //   OUTPUTS: { List<YearMonth> - ascending contiguous range covering every recorded month and today's month }
    //   SIDE_EFFECTS: none
    //   LINKS: D-02
    // END_CONTRACT: monthsWithRecordings
    fun monthsWithRecordings(recorded: Set<LocalDate>, today: LocalDate): List<YearMonth> {
        val months = recorded.map { YearMonth.from(it) } + YearMonth.from(today)
        val first = months.min()
        val last = months.max()
        val out = ArrayList<YearMonth>()
        var m = first
        while (m <= last) {
            out += m
            m = m.plusMonths(1)
        }
        return out
    }

    // START_CONTRACT: title
    //   PURPOSE: Month header text.
    //   INPUTS: { month: YearMonth }
    //   OUTPUTS: { String - «Октябрь 2026» (standalone Russian month, capitalised) }
    //   SIDE_EFFECTS: none
    //   LINKS: D-02
    // END_CONTRACT: title
    fun title(month: YearMonth): String {
        val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)
        return name.replaceFirstChar { it.titlecase(RU) } + " " + month.year
    }

    // START_CONTRACT: weekdayHeaders
    //   PURPOSE: Column headers, Monday first.
    //   INPUTS: none
    //   OUTPUTS: { List<String> - «Пн» .. «Вс» }
    //   SIDE_EFFECTS: none
    //   LINKS: D-02
    // END_CONTRACT: weekdayHeaders
    fun weekdayHeaders(): List<String> = HEADERS
}
