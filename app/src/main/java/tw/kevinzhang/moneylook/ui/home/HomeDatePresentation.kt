package tw.kevinzhang.moneylook.ui.home

import java.time.DayOfWeek
import java.time.LocalDate

/** The overview card intentionally follows the device's local calendar date. */
fun homeOverviewTitle(date: LocalDate): String =
    "${date.monthValue}月${date.dayOfMonth}日(${date.dayOfWeek.shortTraditionalChinese()})"

private fun DayOfWeek.shortTraditionalChinese(): String = when (this) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}
