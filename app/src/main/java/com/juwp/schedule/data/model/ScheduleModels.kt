package com.juwp.schedule.data.model

/**
 * 一门课的「单次安排」。
 *
 * 注意：教务系统同一门课在不同周次可能换老师、换教室（例如同一门课第 1-9 周在北 C201、
 * 第 11-12 周在北 C102），系统会把它们拆成多条记录放在同一个格子里。
 * 因此这里不以「课程名」为主键，而是以「课程名 + 周次集合 + 节次 + 教室」为一条安排。
 */
data class CourseArrangement(
    /** 课程名称，例如「国际货物运输与保险」 */
    val name: String,
    /** 授课教师，可能多人，例如 "刘义杰" 或 "李文彪,王键" */
    val teachers: String,
    /** 教室，例如「教学北大楼(北C201)」 */
    val room: String,
    /** 课程编号，例如 "020420125"，可能为空 */
    val courseCode: String,
    /** 上课班级描述，可能为空 */
    val classes: String,
    /** 总人数，可能为空 */
    val studentCount: String,
    /** 考核方式，例如「考查」「考试」 */
    val assessment: String,
    /** 总学时，可能为空 */
    val totalHours: String,
    /** 原始周次描述，例如 "1-2,4-9,11" */
    val weeksRaw: String,
    /** 解析后的周次集合（去重升序），例如 [1,2,4,5,6,7,8,9,11] */
    val weeks: Set<Int>,
    /** 节次区间原文，例如 "1-2" */
    val periodRaw: String,
    /** 解析后的节次集合（从 1 开始），例如 [1,2] */
    val periods: Set<Int>,
) {
    /** 唯一键：用于 Room 主键与去重 */
    val uniqueKey: String
        get() = listOf(name, weeksRaw, periodRaw, room, teachers).joinToString("|")
}

/** 某个格子（星期 + 节次）里的一门课；一格里可能有多门课 */
data class ScheduleCell(
    /** 星期：1=周一 ... 7=周日 */
    val weekday: Int,
    /** 该格所属的节次行序号（从 0 开始，对应 [DaySchedule.periods] 的下标） */
    val periodRowIndex: Int,
    /** 该格覆盖的节次行数（rowspan），1 表示只占一行 */
    val rowSpan: Int,
    val course: CourseArrangement,
)

/** 一天里某个节次的定义（第几节 + 起止时间） */
data class PeriodInfo(
    /** 展示用标题，例如「第一二节」 */
    val label: String,
    /** 起止时间，例如 "08:30~09:55" */
    val timeRange: String,
)

/** 一个学期的完整课表 */
data class SemesterSchedule(
    /** 学期标识，例如 "2026-2027-1" */
    val termId: String,
    /** 该学期所有可选学期（用于下拉切换） */
    val availableTerms: List<TermOption>,
    /** 节次表（行定义） */
    val periods: List<PeriodInfo>,
    /** 全部课程安排（未按周展开） */
    val cells: List<ScheduleCell>,
    /** 服务端标记的「今天」是星期几（1..7），拿不到则为 null */
    val currentWeekdayFromServer: Int?,
    /** 抓取时间（毫秒时间戳） */
    val fetchedAt: Long,
) {
    /** 课表覆盖的最大周次，用于生成周切换范围 */
    val maxWeek: Int get() = cells.maxOfOrNull { c -> c.course.weeks.maxOrNull() ?: 0 } ?: 0

    /** 课表覆盖的最小周次 */
    val minWeek: Int get() = cells.minOfOrNull { c -> c.course.weeks.minOrNull() ?: Int.MAX_VALUE } ?: 1

    /**
     * 指定周次里，某个「星期 + 节次行」上的所有课程。
     * 这是周课表渲染的核心查询。
     */
    fun cellAt(week: Int, weekday: Int, periodRowIndex: Int): List<CourseArrangement> =
        cells.filter { cell ->
            cell.weekday == weekday &&
                cell.periodRowIndex == periodRowIndex &&
                week in cell.course.weeks
        }.map { it.course }
}

/** 学期下拉项 */
data class TermOption(
    val id: String,
    val name: String,
    val selected: Boolean,
)

/** 课表单元格在网格中的位置（解析中间产物） */
internal data class RawCell(
    val weekday: Int,
    val periodRowIndex: Int,
    val rowSpan: Int,
    val courses: List<CourseArrangement>,
)
