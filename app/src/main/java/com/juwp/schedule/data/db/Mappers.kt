package com.juwp.schedule.data.db

import com.juwp.schedule.data.model.CourseArrangement
import com.juwp.schedule.data.model.PeriodInfo
import com.juwp.schedule.data.model.ScheduleCell
import com.juwp.schedule.data.model.SemesterSchedule
import com.juwp.schedule.data.model.TermOption

/** 领域模型 → 数据库实体 */
fun ScheduleCell.toEntity(termId: String): CourseEntity = CourseEntity(
    termId = termId,
    uniqueKey = course.uniqueKey,
    name = course.name,
    teachers = course.teachers,
    room = course.room,
    courseCode = course.courseCode,
    classes = course.classes,
    studentCount = course.studentCount,
    assessment = course.assessment,
    totalHours = course.totalHours,
    weeksRaw = course.weeksRaw,
    weeksCsv = course.weeks.sorted().joinToString(","),
    periodRaw = course.periodRaw,
    periodsCsv = course.periods.sorted().joinToString(","),
    weekday = weekday,
    periodRowIndex = periodRowIndex,
    rowSpan = rowSpan,
)

/** 数据库实体 → 领域模型 */
fun CourseEntity.toModel(): CourseArrangement = CourseArrangement(
    name = name,
    teachers = teachers,
    room = room,
    courseCode = courseCode,
    classes = classes,
    studentCount = studentCount,
    assessment = assessment,
    totalHours = totalHours,
    weeksRaw = weeksRaw,
    weeks = weeks,
    periodRaw = periodRaw,
    periods = periods,
)

fun CourseEntity.toCell(): ScheduleCell = ScheduleCell(
    weekday = weekday,
    periodRowIndex = periodRowIndex,
    rowSpan = rowSpan,
    course = toModel(),
)

fun PeriodInfo.toEntity(termId: String, rowIndex: Int): PeriodEntity =
    PeriodEntity(termId = termId, rowIndex = rowIndex, label = label, timeRange = timeRange)

fun PeriodEntity.toModel(): PeriodInfo = PeriodInfo(label = label, timeRange = timeRange)

fun TermOption.toEntity(fetchedAt: Long): TermMetaEntity =
    TermMetaEntity(termId = id, termName = name, isSelected = selected, fetchedAt = fetchedAt)

/**
 * 把数据库里的本学期数据还原成 [SemesterSchedule]（离线阅读用）。
 * 注意 cells 里存的 weeks 已是解析后的集合，这里直接复用。
 *
 * ⚠️ 必须过滤掉空学期的占位行（[ScheduleDatabase.EMPTY_TERM_MARKER_ROW]），
 * 否则界面会把它当成一个真实节次，渲染出一行空白。
 */
fun buildScheduleFromCache(
    termId: String,
    courses: List<CourseEntity>,
    periods: List<PeriodEntity>,
    terms: List<TermMetaEntity>,
    currentWeekday: Int?,
    fetchedAt: Long,
): SemesterSchedule = SemesterSchedule(
    termId = termId,
    availableTerms = terms.map { TermOption(it.termId, it.termName, it.isSelected) },
    periods = periods
        .filter { it.rowIndex >= 0 }
        .sortedBy { it.rowIndex }
        .map { it.toModel() },
    cells = courses.map { it.toCell() },
    currentWeekdayFromServer = currentWeekday,
    fetchedAt = fetchedAt,
)
