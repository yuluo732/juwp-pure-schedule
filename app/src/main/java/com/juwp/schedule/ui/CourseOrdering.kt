package com.juwp.schedule.ui

import com.juwp.schedule.data.model.CourseArrangement
import com.juwp.schedule.data.model.ScheduleCell

/**
 * 从学号推出「本级次」前缀。
 *
 * 学号前 4 位是入学年份（`<学号>` 形如 `2024xxxxxx` → 入学年 `2024`），
 * 而课表里班级名的前两位就是级次（`24国际经济与贸易03班` → `24`），
 * 两者对得上，所以取学号第 3~4 位即可。
 *
 * 取不到（未登录 / 学号异常）时返回 null，调用方退化为「保持原顺序」。
 */
fun ownGradeOf(studentId: String): String? {
    val head = studentId.trim().take(4)
    if (head.length != 4 || !head.all { it.isDigit() }) return null
    return head.takeLast(2)
}

/**
 * 一格（或一个时段）里有多个课程安排时，把**本级次的课排到最前面**。
 *
 * ⚠️ 为什么需要这一步：重修课的班级挂在**别的级次**下。
 * 实测：24 级学生重修「高等数学B(下)」，该课在教务系统里属于 `25市场营销1-2`；
 * 而教务系统返回的顺序里它恰好排在前面，于是卡片上显示的是重修班那门，
 * 学生会以为自己上的是那门课。本级次的课才是「正课」，应当优先展示。
 *
 * 排序是**稳定**的：同一优先级内保持解析出来的原始顺序，不引入随机性。
 */
fun preferOwnGrade(courses: List<CourseArrangement>, ownGrade: String?): List<CourseArrangement> =
    preferBy(courses, ownGrade) { it.classes }

/**
 * 同上，但直接作用于 [ScheduleCell]（课表格子里的「起始课程」记录）。
 * 渲染与行高计算都要用同一个顺序，两处都调这个，保证取到的是同一门。
 *
 * ⚠️ 不能与上面那个写成同名重载：两者擦除后签名都是 `(List, String)`，
 *    会撞 JVM 签名而编译失败，所以名字分开。
 */
fun preferOwnGradeCells(cells: List<ScheduleCell>, ownGrade: String?): List<ScheduleCell> =
    preferBy(cells, ownGrade) { it.course.classes }

private fun <T> preferBy(items: List<T>, ownGrade: String?, classesOf: (T) -> String): List<T> {
    if (ownGrade.isNullOrBlank() || items.size < 2) return items
    return items.sortedByDescending { if (belongsToGrade(classesOf(it), ownGrade)) 1 else 0 }
}

/** 班级串形如 `24国际经济与贸易[02-03]班,24市场营销03班`，逐段看是否以该级次开头 */
private fun belongsToGrade(classes: String, ownGrade: String): Boolean =
    classes.split(',', '，', '、', ';', '；', '/')
        .any { it.trim().startsWith(ownGrade) }
