package com.juwp.schedule.domain

import com.juwp.schedule.data.model.SemesterSchedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 教学周计算。
 *
 * 教务系统本身不告诉你「今天是第几周」，只告诉你课表覆盖 1..16 周。
 * 这里用「第 1 周周一」作为锚点推算。
 *
 * 锚点来源优先级：
 *  1. 用户手动设置（最准，开学第一周自己填一次）
 *  2. 用户手动指定当前周
 *  3. 用服务端标记的「今天」+ 粗略反推（不精确，仅作初值）
 */
object WeekCalculator {

    /** 中国习惯：周一为一周第一天 */
    private val WEEK_FIELDS: WeekFields = WeekFields.of(DayOfWeek.MONDAY, 4)

    /** 本地日期对应的星期几（1=周一 .. 7=周日），与课表列一致 */
    fun weekdayOf(date: LocalDate = LocalDate.now()): Int = date.dayOfWeek.value

    /**
     * 计算 [date] 是第几教学周。
     *
     * @param termStart 第 1 周的周一；为 null 时返回 null（调用方需引导用户设置）
     * @return 从 1 开始的周次；若 date 早于开学日则返回 1（不返回 0/负数）
     */
    fun weekOf(date: LocalDate, termStart: LocalDate?): Int? {
        if (termStart == null) return null
        val startMonday = termStart.with(DayOfWeek.MONDAY)
        val days = ChronoUnit.DAYS.between(startMonday, date)
        if (days < 0) return 1
        return (days / 7).toInt() + 1
    }

    /**
     * 第 [week] 周的周一日期。
     */
    fun mondayOfWeek(termStart: LocalDate, week: Int): LocalDate =
        termStart.with(DayOfWeek.MONDAY).plusWeeks((week - 1).toLong())

    /**
     * 第 [week] 周某一天（weekday: 1=周一..7=周日）。
     */
    fun dateOf(termStart: LocalDate, week: Int, weekday: Int): LocalDate =
        mondayOfWeek(termStart, week).plusDays((weekday - 1).toLong())

    /** 一周 7 天的日期列表 */
    fun datesOfWeek(termStart: LocalDate, week: Int): List<LocalDate> =
        (0..6).map { mondayOfWeek(termStart, week).plusDays(it.toLong()) }

    /**
     * 依据服务端标记的「今天」反推第 1 周周一（粗略）。
     * 服务端只给「今天是星期几」和课表覆盖的周次范围，无法唯一定位，
     * 所以这里退化为「假设今天是第 1 周」，仅用于首次进入时给用户一个可编辑的初值。
     */
    fun guessTermStart(serverWeekday: Int?, today: LocalDate): LocalDate =
        today.with(DayOfWeek.MONDAY)

    /** 是否周末 */
    fun isWeekend(weekday: Int): Boolean = weekday >= 6

    /** 星期中文名 */
    fun weekdayName(weekday: Int): String = when (weekday) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> "?"
    }

    fun weekdayNameFull(weekday: Int): String = when (weekday) {
        1 -> "星期一"
        2 -> "星期二"
        3 -> "星期三"
        4 -> "星期四"
        5 -> "星期五"
        6 -> "星期六"
        7 -> "星期日"
        else -> "?"
    }

    /** 让 [week] 落在课表有效范围内 */
    fun clampWeek(week: Int, schedule: SemesterSchedule): Int {
        val max = schedule.maxWeek.coerceAtLeast(1)
        return week.coerceIn(1, max)
    }

    /** 本地化日期格式：M/d */
    fun shortDate(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}"

    private fun locale(): Locale = Locale.CHINA
}
