package com.juwp.schedule.data.parse

import com.juwp.schedule.data.model.CourseArrangement
import com.juwp.schedule.data.model.PeriodInfo
import com.juwp.schedule.data.model.RawCell
import com.juwp.schedule.data.model.ScheduleCell
import com.juwp.schedule.data.model.SemesterSchedule
import com.juwp.schedule.data.model.TermOption
import com.juwp.schedule.data.net.JwRegex
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 课表 HTML 解析器。
 *
 * 页面：GET /jsxsd/xskb/xskb_list.do?viweType=0
 *
 * 结构（实测）：
 *
 * ```html
 * <table class="qz-weeklyTable">
 *   <thead><tr>
 *     <th class="...qz-weeklyTable-label...">周次</th>       <!-- 第一列 -->
 *     <th class="...">星期一</th> ... <th class="...qz-currentWeek">星期四</th> ...
 *   </tr></thead>
 *   <tbody>
 *     <tr>
 *       <td name="timeTd" ...> 第一二节 08:30~09:55 </td>      <!-- 第一列：节次 -->
 *       <td name="kbDataTd" rowspan="1" class="... qz-hasCourse" colSize="1">
 *         <div class="td-cell">
 *           <ul name="kbdataUl" kbdataSize="2">
 *             <li class="courselists-item qz-hasCourse-1">
 *               <div class="qz-hasCourse-title qz-ellipse">国际税收</div>
 *               <p class="qz-hasCourse-detaillists">
 *                 <span class="qz-hasCourse-detailitem qz-hasCourse-abbrinfo">
 *                   老师:黎宁;时间:1,3,5,7,9,11周[3-4节];地点:教学南大楼(南C104)
 *                 </span>
 *                 <span name="kchSpan">;课程编号:020420062</span>
 *                 <span name="dealeSpan">班级:...;总人数:120;考核方式:考查;总学时:12</span>
 *               </p>
 *             </li>
 *             ...
 *           </ul>
 *         </div>
 *       </td> ×7（周一..周日）
 *     </tr>
 *     ... 每行一个节次
 *   </tbody>
 * </table>
 * ```
 *
 * ⚠️ 两个坑（已实测踩到）：
 *  1. `td` 上的 `qz-hasCourse` class **不代表有课**，空格子也带这个类（172 次出现但只有 16 个格子真有课）。
 *      必须以 `ul[name=kbdataUl]` 里的 `li` 数量为准。
 *  2. 一个格子里可能有 **多门课**（实测最多 5 门），同一门课还会因换教室/换周次拆成多条，
 *      所以解析结果是「课程安排列表」而不是「课程列表」。
 */
object ScheduleParser {

    private const val TAG = "ScheduleParser"

    /** 解析整张课表 */
    fun parse(html: String, fetchedAt: Long = System.currentTimeMillis()): SemesterSchedule {
        val doc = Jsoup.parse(html)

        val table = doc.selectFirst("table.qz-weeklyTable")
            ?: throw ScheduleParseException(
                "未找到课表表格（table.qz-weeklyTable）。可能未登录、学期无数据，或站点已改版。"
            )

        val periods = parsePeriods(table)
        val currentWeekday = parseCurrentWeekday(table)
        val terms = parseTerms(doc, html)
        val currentTerm = terms.firstOrNull { it.selected }?.id ?: terms.firstOrNull()?.id.orEmpty()

        // 单双周补齐需要知道「本学期上到第几周」。
        // 由于字面周次（如 "6双周" 只写了 6）不足以定上界，这里先扫一遍所有周次串
        // 取全局最大值，用它作为补齐上界，避免把课排到学期结束之后。
        val maxWeek = maxMentionedWeek(table).coerceAtLeast(MIN_EXPAND_WEEK)

        val cells = parseCells(table, maxWeek)

        return SemesterSchedule(
            termId = currentTerm,
            availableTerms = terms,
            periods = periods,
            cells = cells,
            currentWeekdayFromServer = currentWeekday,
            fetchedAt = fetchedAt,
        )
    }

    // ------------------------------------------------------------ 节次（行）

    /**
     * 解析节次行。每行第一个 td 带 name="timeTd"，文本形如「第一二节 08:30~09:55」。
     * 兼容没有 name 属性的改版：只要第一个 td 里能匹配到「第X节」就认为是节次列。
     */
    private fun parsePeriods(table: Element): List<PeriodInfo> {
        val result = mutableListOf<PeriodInfo>()
        val rows = table.select("tbody > tr")
        for (row in rows) {
            val tds = row.select("> td")
            if (tds.isEmpty()) continue
            val first = tds.first()!!
            val text = first.text().trim()
            val label = JwRegex.PERIOD_LABEL.find(text)?.value
            val isTimeCell = first.attr("name") == "timeTd" || label != null
            if (!isTimeCell) continue
            val time = JwRegex.PERIOD_TIME.find(text)?.value?.replace(" ", "") ?: ""
            val (labelOut, timeOut) = normalizePeriodLabel(label, time)
            result.add(
                PeriodInfo(
                    label = labelOut,
                    timeRange = timeOut,
                )
            )
        }
        return result
    }

    /**
     * 教务系统把晚间课的节次行写成「第九十十一节 19:00~21:10」，有两处坑：
     *  1. 标签极易误读成「第九十一节」；实际拆开是第九、十节 + 第十一节（合并行）
     *  2. 行时间是整行的起止（19:00~21:10），而实际课程都落在 9-10 节，
     *     本校真实的第九、十节时间是 19:00~20:25（本校学生确认）
     * 展示统一规范为「第九十节 19:00~20:25」，写法与第一二节/第三四节等行保持一致。
     * ⚠️ 若将来教务系统出现真正落在第十一节的课程，需要把这一行拆成两行分别计时。
     */
    private fun normalizePeriodLabel(label: String?, time: String): Pair<String, String> {
        val l = label ?: ""
        return if (l.contains("九十十一")) {
            "第九十节" to "19:00~20:25"
        } else {
            l to time
        }
    }

    /** 表头里哪一列带 qz-currentWeek（服务端认为的「今天」），返回 1..7，取不到返回 null */
    private fun parseCurrentWeekday(table: Element): Int? {
        val headers = table.select("thead th")
        if (headers.isEmpty()) return null
        // headers[0] 是「周次」列，星期从下标 1 开始
        for (i in 1 until headers.size) {
            if (headers[i].hasClass("qz-currentWeek")) return i
        }
        return null
    }

    // ------------------------------------------------------------ 课程格子

    /**
     * 扫描全表，取所有周次串里提到的最大周次。
     * 用于给单双周补齐定上界（`6双周` 这种只写一个数字的写法需要它）。
     */
    private fun maxMentionedWeek(table: Element): Int {
        var max = 0
        table.select("li.courselists-item").forEach { li ->
            val detail = li.text().replace('\u00A0', ' ')
            val raw = JwRegex.WEEKS.find(detail)?.groupValues?.get(1) ?: return@forEach
            JwRegex.WEEK_RANGE.findAll(raw).forEach { m ->
                m.groupValues[2].toIntOrNull()?.let { if (it > max) max = it }
            }
            JwRegex.WEEK_SINGLE.findAll(raw).forEach { m ->
                m.value.toIntOrNull()?.let { if (it > max) max = it }
            }
        }
        return max
    }

    private fun parseCells(table: Element, maxWeek: Int = 30): List<ScheduleCell> {
        val result = mutableListOf<ScheduleCell>()
        val rows = table.select("tbody > tr")
        var periodRowIndex = -1

        // 每个列的「已被上方 rowspan 占用的剩余行数」。
        //
        // ⚠️ 为什么必须有它：HTML 里 rowspan 跨行时，**被跨越的那一行不会重复给出那个 td**。
        //    于是「本行第 N 个 td」并不等于「星期 N」—— 整行会左移若干天。
        //    早期实现就是直接 `tds.drop(1).forEachIndexed { dayIdx -> weekday = dayIdx + 1 }`，
        //    只要出现 rowspan 跨行，该行所有课程都会被画到错的那一天
        //    （实测：周二的课被解析成周一、周日的课被解析成周六）。
        //    rowSpan 在真实教务数据里罕见但确实存在，所以这里做真正的列号追踪。
        //
        // 语义：占用计数在**本行处理完后递减**，所以登记时要多记一行（rowSpan 而非 rowSpan-1），
        //       这样到下一行时它正好是「还需跳过几次」。早期写成 rowSpan-1 导致提前归零、
        //       偏移依旧（off-by-one，实测复现过）。
        val occupied = mutableMapOf<Int, Int>()

        for (row in rows) {
            val tds = row.select("> td")
            if (tds.isEmpty()) continue
            val first = tds.first() ?: continue
            val firstText = first.text()
            val isTimeCell = first.attr("name") == "timeTd" || JwRegex.PERIOD_LABEL.containsMatchIn(firstText)
            // 每个节次行计数 +1；非节次行沿用上一行下标
            if (isTimeCell) periodRowIndex++

            // 第一列是节次列，课程从第 2 列开始。
            // col 从 1 起步（0 留作节次列），跳过被上方 rowspan 占用的列。
            var col = 1
            for (td in tds.drop(1)) {
                while ((occupied[col] ?: 0) > 0) col++
                val weekday = col
                col++
                if (weekday > 7) break

                val rowSpan = td.attr("rowspan").toIntOrNull() ?: 1
                // 登记占位：本行用掉一次，剩下的行数留给后续行跳过
                if (rowSpan > 1) occupied[weekday] = rowSpan

                val courses = parseCoursesInCell(td, maxWeek)
                if (courses.isEmpty()) continue
                if (periodRowIndex < 0) continue
                courses.forEach { course ->
                    result.add(
                        ScheduleCell(
                            weekday = weekday,
                            periodRowIndex = periodRowIndex,
                            rowSpan = rowSpan,
                            course = course,
                        )
                    )
                }
            }

            // 本行结束：所有占位计数减 1（本行已消费一次）
            occupied.entries.forEach { it.setValue(it.value - 1) }
            occupied.entries.removeAll { it.value <= 0 }
        }
        return result
    }

    /**
     * 解析一个格子里的所有课程。
     * 先取 `ul[name=kbdataUl] > li`；若站点改版没有 ul，退化为扫描 `li.courselists-item`。
     */
    private fun parseCoursesInCell(td: Element, maxWeek: Int = 30): List<CourseArrangement> {
        val items = td.select("ul[name=kbdataUl] > li")
            .ifEmpty { td.select("li.courselists-item") }
            .ifEmpty { td.select("ul.courselists > li") }
        return items.mapNotNull { parseCourseItem(it, maxWeek) }
    }

    /**
     * 解析单门课。字段分散在多个 span 里，用「整格文本 + 正则」比逐个 span 取值更抗改版。
     */
    private fun parseCourseItem(li: Element, maxWeek: Int = 30): CourseArrangement? {
        val name = li.selectFirst(".qz-hasCourse-title")?.text()?.trim().orEmpty()
            .ifEmpty { li.selectFirst("div")?.text()?.trim().orEmpty() }
        if (name.isEmpty()) return null

        // 把所有文案拼起来，统一做正则提取
        val detail = li.text().replace('\u00A0', ' ')

        val teachers = JwRegex.TEACHER.find(detail)?.groupValues?.get(1)?.trim().orEmpty()
        val room = JwRegex.ROOM.find(detail)?.groupValues?.get(1)?.trim().orEmpty()
        // 课程编号会出现在 detail 两次（kchSpan 与页脚），第一次取到的就是对的
        val courseCode = JwRegex.COURSE_CODE.find(detail)?.groupValues?.get(1)?.trim().orEmpty()
        val classes = JwRegex.CLASSES.find(detail)?.groupValues?.get(1)?.trim().orEmpty()
        val studentCount = JwRegex.STUDENT_COUNT.find(detail)?.groupValues?.get(1)?.trim().orEmpty()
        val assessment = JwRegex.ASSESSMENT.find(detail)?.groupValues?.get(1)?.trim().orEmpty()
        val totalHours = JwRegex.TOTAL_HOURS.find(detail)?.groupValues?.get(1)?.trim().orEmpty()

        // 时间:1-9,11周[5-6节] —— 原文自带「周」「节」后缀，去掉，
        // 避免 UI 再拼量词时出现「第 5-6 节 节」「第 1-9,11 周 周」
        // ⚠️ 只去尾部「周」，**不能**动「双周/单周」字样：那是语义标记，
        //    parseWeeks 要靠它把 "2,6双周" 展开成 {2,4,6}
        val weeksRaw = JwRegex.WEEKS.find(detail)?.groupValues?.get(1)?.trim()
            ?.removeSuffix("周").orEmpty()
        val periodRaw = JwRegex.PERIODS.find(detail)?.groupValues?.get(1)?.trim()
            ?.removeSuffix("节").orEmpty()

        return CourseArrangement(
            name = name,
            teachers = teachers,
            room = room,
            courseCode = courseCode,
            classes = classes,
            studentCount = studentCount,
            assessment = assessment,
            totalHours = totalHours,
            weeksRaw = weeksRaw,
            weeks = parseWeeks(weeksRaw, maxWeek),
            periodRaw = periodRaw,
            periods = parsePeriods(periodRaw),
        )
    }

    // ------------------------------------------------------------ 小工具

    /**
     * 解析周次串。
     *
     * 支持写法：
     *  - 区间：`1-2,4-9,11`
     *  - 散点：`1,3,5`
     *  - 带「周」字：`1-16周`
     *  - **单双周**：`2,6双周`、`1-16周(单)`、`1-16单周`
     *
     * ⚠️ 单双周必须特殊处理 —— 这是实测踩到的真 bug：
     * 教务系统里「马克思主义基本原理 周次=2,6双周」的**真实语义是「第 2 周起每隔一周」**，
     * 即 {2,4,6,8,…}。早期实现只取字面数字 {2,6}，会**漏掉一半的课**。
     *
     * 处理策略：整串只要出现「单周/双周」（或 `(单)` `(双)`）标记，
     * 就把该串涉及的所有周次**按区间补齐**再按奇偶过滤 —— 宁可多显示，
     * 也绝不能让用户漏课。
     *
     * @param maxWeek 单双周补齐时的上界（应传本学期课表的最大周次）
     */
    fun parseWeeks(raw: String, maxWeek: Int = 30): Set<Int> {
        if (raw.isBlank()) return emptySet()
        val cleaned = raw.replace("（", "(").replace("）", ")")
        val oddOnly = ODD_MARKER.containsMatchIn(cleaned)
        val evenOnly = EVEN_MARKER.containsMatchIn(cleaned)
        if (!oddOnly && !evenOnly) return parseWeeksPlain(raw)

        // 先收集字面提到的所有周次
        val mentioned = sortedSetOf<Int>()
        cleaned.split(',', '，', '、').forEach { segment ->
            val range = JwRegex.WEEK_RANGE.find(segment)
            if (range != null) {
                val a = range.groupValues[1].toIntOrNull()
                val b = range.groupValues[2].toIntOrNull()
                if (a != null && b != null && a <= b) for (w in a..b) mentioned.add(w)
            } else {
                JwRegex.WEEK_SINGLE.find(segment)?.value?.toIntOrNull()?.let { mentioned.add(it) }
            }
        }
        if (mentioned.isEmpty()) return emptySet()

        // 补齐到上界，再按奇偶过滤
        val upper = maxOf(mentioned.max(), maxWeek).coerceAtMost(MAX_WEEK_BOUND)
        val lower = mentioned.min()
        return (lower..upper)
            .filter { if (oddOnly) it % 2 == 1 else it % 2 == 0 }
            .toSortedSet()
    }

    /** 不含单双周标记的普通解析 */
    private fun parseWeeksPlain(raw: String): Set<Int> {
        val weeks = sortedSetOf<Int>()
        raw.split(',', '，', '、').forEach { segment ->
            val seg = segment.trim()
            if (seg.isEmpty()) return@forEach
            val range = JwRegex.WEEK_RANGE.find(seg)
            if (range != null) {
                val start = range.groupValues[1].toIntOrNull() ?: return@forEach
                val end = range.groupValues[2].toIntOrNull() ?: return@forEach
                if (start <= end && end <= MAX_WEEK_BOUND) {
                    for (w in start..end) weeks.add(w)
                }
            } else {
                JwRegex.WEEK_SINGLE.find(seg)?.value?.toIntOrNull()
                    ?.takeIf { it in 1..MAX_WEEK_BOUND }?.let { weeks.add(it) }
            }
        }
        return weeks
    }

    /**
     * 解析节次串，支持 "1-2" / "1-2,3-4" / "5" 等写法。
     */
    fun parsePeriods(raw: String): Set<Int> {
        if (raw.isBlank()) return emptySet()
        val periods = sortedSetOf<Int>()
        raw.split(',', '，', '、').forEach { segment ->
            val seg = segment.trim()
            if (seg.isEmpty()) return@forEach
            val range = JwRegex.WEEK_RANGE.find(seg)
            if (range != null) {
                val start = range.groupValues[1].toIntOrNull() ?: return@forEach
                val end = range.groupValues[2].toIntOrNull() ?: return@forEach
                if (start in 1..20 && end in 1..20) {
                    for (p in start..end) periods.add(p)
                }
            } else {
                JwRegex.WEEK_SINGLE.find(seg)?.value?.toIntOrNull()
                    ?.takeIf { it in 1..20 }?.let { periods.add(it) }
            }
        }
        return periods
    }

    /** 解析学期下拉（name=xnxq01id 的 select） */
    private fun parseTerms(doc: Document, html: String): List<TermOption> {
        val select = doc.selectFirst("select[name=$XNXQ]")
            ?: doc.select("select").firstOrNull { it.select("option").any { o -> TERM_REGEX.matches(o.attr("value")) } }
            ?: return emptyList()

        return select.select("option").mapNotNull { option ->
            val value = option.attr("value").trim()
            if (value.isEmpty()) return@mapNotNull null
            TermOption(
                id = value,
                name = option.text().trim().ifEmpty { value },
                selected = option.hasAttr("selected"),
            )
        }
    }

    private const val XNXQ = "xnxq01id"
    private val TERM_REGEX = Regex("""\d{4}-\d{4}-[12]""")

    /** 周次上界：防止单双周补齐时无限扩张（一个学期不会超过 30 周） */
    private const val MAX_WEEK_BOUND = 30

    /**
     * 单双周补齐时的最小上界。
     * 若全表最大周次比它还小（例如只有一门 6 周结课的短课），
     * 仍按 16 周补齐，避免「2,6双周」被压成 {2,6} 这类过窄结果。
     */
    private const val MIN_EXPAND_WEEK = 16

    /** 单周标记：单周 / (单) / （单） */
    private val ODD_MARKER = Regex("""单""")
    /** 双周标记 */
    private val EVEN_MARKER = Regex("""双""")

    /** url 解码（页面里可能有 &amp; 转义，统一处理） */
    fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
}

/** 课表解析失败 */
class ScheduleParseException(message: String) : Exception(message)
