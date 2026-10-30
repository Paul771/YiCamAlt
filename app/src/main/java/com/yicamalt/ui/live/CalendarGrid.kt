// FILE: CalendarGrid.kt
// VERSION: 0.0.1
package com.yicamalt.ui.live

import java.time.LocalDate
import java.time.YearMonth

data class DayCell(val date: LocalDate?, val enabled: Boolean, val marked: Boolean, val isToday: Boolean, val selected: Boolean)

object CalendarGrid {
    fun month(month: YearMonth, today: LocalDate, recorded: Set<LocalDate>, selected: LocalDate): List<DayCell> = error("RED stub")
    fun monthsWithRecordings(recorded: Set<LocalDate>, today: LocalDate): List<YearMonth> = error("RED stub")
    fun title(month: YearMonth): String = error("RED stub")
    fun weekdayHeaders(): List<String> = error("RED stub")
}
