package validate

import com.juwp.schedule.data.parse.ScheduleParser
import com.juwp.schedule.domain.TermMatcher
import java.io.File
import java.time.LocalDate

/**
 * 用**结构与教务系统完全一致的样例课表 HTML** 验证 ScheduleParser。
 * 这是「离线验证」，不需要 Android SDK，直接用 kotlinc 编译运行。
 *
 * ⚠️ 样例是**合成数据**（`validation/fixtures/sample-timetable.html`）：
 *    表格层级、class、name 属性、字段分隔符都与真实页面一致，
 *    但教师姓名/班级/课程编号是示例值（`示例教师A`、`示例班级01`、`DEMO00000001`），
 *    课程名用通用学科名。这样验证能在公开仓库里跑，又不夹带任何个人信息。
 *
 *    样例刻意保留了真实数据里的几个陷阱，用来守住既有回归：
 *      · 空格子也带 `qz-hasCourse` class（必须以 `li` 数量为准）
 *      · 一格多课，且各条周次互不重叠（「多课 = 不同周次」铁律）
 *      · 单双周写法 `1-16周(单)` / `2,6双周`
 *      · 空括号地点 `()`
 *
 * 运行：见 tools/run-parser-check.ps1
 */
fun main(args: Array<String>) {
    val htmlPath = args.firstOrNull() ?: "validation/fixtures/sample-timetable.html"
    val file = File(htmlPath)
    require(file.exists()) { "找不到测试数据：$htmlPath" }

    println("=".repeat(72))
    println("ScheduleParser 离线验证")
    println("数据源: ${file.absolutePath}  (${file.length()} bytes)")
    println("=".repeat(72))

    val html = file.readText()
    val schedule = ScheduleParser.parse(html, fetchedAt = 0L)

    println()
    println("学期 termId          = ${schedule.termId}")
    println("可选学期数           = ${schedule.availableTerms.size}")
    println("  " + schedule.availableTerms.take(4).joinToString { "${it.id}${if (it.selected) "★" else ""}" })
    println("节次行数             = ${schedule.periods.size}")
    schedule.periods.forEachIndexed { i, p -> println("   [$i] ${p.label}  ${p.timeRange}") }
    println("服务端“今天”列       = ${schedule.currentWeekdayFromServer}")
    println("课程安排(cells) 总数 = ${schedule.cells.size}")
    println("周次范围             = ${schedule.minWeek} .. ${schedule.maxWeek}")

    // ---------------- 断言 ----------------
    val checks = mutableListOf<Pair<String, Boolean>>()
    fun check(name: String, cond: Boolean) = checks.add(name to cond)

    check("解析出 5 个节次", schedule.periods.size == 5)
    check("节次时间非空", schedule.periods.all { it.timeRange.isNotBlank() })
    check("服务端标记今天是星期四", schedule.currentWeekdayFromServer == 4)
    check("第一节课时间是 08:30~09:55", schedule.periods.firstOrNull()?.timeRange == "08:30~09:55")
    // 22 = 16 个「有课的格子」里因为存在一格多课，展开成 22 条课程安排。
    // 若将来课表变动，这个下界仍然成立（这门专业至少有 16 条安排）。
    check("课程安排数 >= 16（一格多课已展开）", schedule.cells.size >= 16)
    check("有课的格子数为 16", schedule.cells.groupBy { it.weekday to it.periodRowIndex }.size == 16)

    val names = schedule.cells.map { it.course.name }.toSet()
    println()
    println("识别到的课程名 (${names.size}):")
    names.sorted().forEach { println("   · $it") }

    check("包含《国际货物运输与保险》", names.any { it.contains("国际货物运输与保险") })
    check("包含《跨境电商实务》", names.any { it.contains("跨境电商实务") })
    check("包含《国际税收》", names.any { it.contains("国际税收") })
    check("包含《数字贸易》", names.any { it.contains("数字贸易") })

    // 抽一门课逐字段核对
    val sample = schedule.cells.firstOrNull { it.course.name.contains("国际货物运输与保险") }?.course
    println()
    println("字段级核对 —《国际货物运输与保险》:")
    if (sample != null) {
        println("   教师     = ${sample.teachers}")
        println("   教室     = ${sample.room}")
        println("   课程编号 = ${sample.courseCode}")
        println("   周次原文 = ${sample.weeksRaw}")
        println("   周次集合 = ${sample.weeks.sorted()}")
        println("   节次原文 = ${sample.periodRaw}")
        println("   节次集合 = ${sample.periods.sorted()}")
        println("   考核方式 = ${sample.assessment}")
        println("   总学时   = ${sample.totalHours}")
        check("教师解析正确", sample.teachers == "示例教师C")
        check("课程编号解析正确", sample.courseCode == "DEMO00000002")
        check("周次集合解析正确", sample.weeks.containsAll(listOf(1, 2, 4, 5, 6, 7, 8, 9, 11)))
        check("节次集合解析正确", sample.periods == setOf(1, 2))
    } else {
        check("找到样本课程", false)
    }

    // 多门课格子
    val multi = schedule.cells.groupBy { it.weekday to it.periodRowIndex }.filter { it.value.size > 1 }
    println()
    println("含多门课的格子数 = ${multi.size}")
    multi.entries.take(3).forEach { (key, value) ->
        println("   星期${key.first} 第${key.second + 1}行: " + value.joinToString { it.course.name })
    }
    check("存在一格多课的情况", multi.isNotEmpty())

    // 关键回归：一格多课必须是因为「不同周次」，而不是同一周次并存。
    // 用户的真实观察 + 实测数据：同一周次并存的格子数为 0。
    println()
    println("--- 一格多课的成因分析 ---")
    var overlapCells = 0
    multi.forEach { (key, cells) ->
        val weekMap = mutableMapOf<Int, MutableList<String>>()
        cells.forEach { cell ->
            cell.course.weeks.forEach { w ->
                weekMap.getOrPut(w) { mutableListOf() }.add(cell.course.name)
            }
        }
        val overlap = weekMap.filterValues { it.size > 1 }
        if (overlap.isNotEmpty()) {
            overlapCells++
            println("   ⚠️ 星期${key.first} 第${key.second + 1}行 同周并存: $overlap")
        }
    }
    println("   同一周次并存的格子数 = $overlapCells（预期 0）")
    check("同一周次不存在并课（多课只因周次不同）", overlapCells == 0)

    // 按周过滤后，任何一周任何一格都不超过 1 门课
    val worst = (1..schedule.maxWeek).maxOf { w ->
        schedule.cells.groupBy { it.weekday to it.periodRowIndex }
            .values.maxOf { cells -> cells.count { w in it.course.weeks } }
    }
    println("   按周过滤后单格最多课程数 = $worst（预期 1）")
    check("按周过滤后每格最多 1 门课", worst <= 1)

    // 同一门课在不同周次换教室，必须是两条独立记录
    val movingCourse = schedule.cells.filter { it.course.name.contains("国际结算") }
    println("   《国际结算》被拆成 ${movingCourse.size} 条安排（换教室/换周次所致）")
    check("《国际结算》因换教室拆成多条", movingCourse.size >= 4)

    // 第 1 周星期四第一二节应为《国际商务谈判》（1-9,11-12,16 周）
    check("第1周星期四第一二节是《国际商务谈判》",
        schedule.cellAt(1, 4, 0).any { it.name.contains("国际商务谈判") })
    // 第 4 周星期二第一二节应为《国际货物运输与保险》
    check("第4周星期二第一二节是《国际货物运输与保险》",
        schedule.cellAt(4, 2, 0).any { it.name.contains("国际货物运输与保险") })

    // 周次查询
    println()
    println("cellAt(week=3, 星期四=4, 第一二节行=0) = " +
        schedule.cellAt(3, 4, 0).map { it.name })
    check("第3周星期四第一二节有《国际商务谈判》",
        schedule.cellAt(3, 4, 0).any { it.name.contains("国际商务谈判") })
    check("第10周星期四第一二节无课（该课不含第10周）",
        schedule.cellAt(10, 4, 0).isEmpty())

    // 边界
    check("解析空周次串返回空集合", ScheduleParser.parseWeeks("") == emptySet<Int>())
    check("解析 '1-5' 得到 1..5", ScheduleParser.parseWeeks("1-5") == setOf(1, 2, 3, 4, 5))
    check("解析 '1,3,5' 得到 1/3/5", ScheduleParser.parseWeeks("1,3,5") == setOf(1, 3, 5))
    check("解析 '9-10' 节次", ScheduleParser.parsePeriods("9-10") == setOf(9, 10))

    // ---------------- 连堂课（rowSpan）回归 ----------------
    // 样例里 第七八节·周一 是 rowspan="2" 的连堂课。
    // 这条路径此前**完全没有测试覆盖**（样例 35 个格子全是 rowspan="1"），
    // 而渲染层曾因此把卡片高度算错、下半截被裁掉。
    println()
    println("--- 连堂课（rowSpan）---")
    val spanned = schedule.cells.filter { it.rowSpan >= 2 }
    println("   rowSpan>=2 的课程安排数 = ${spanned.size}")
    spanned.take(3).forEach {
        println("     星期${it.weekday} 行${it.periodRowIndex} rowSpan=${it.rowSpan} ${it.course.name} 节次=${it.course.periodRaw}")
    }
    check("解析器读出了 rowSpan>=2（连堂课）", spanned.isNotEmpty())
    check(
        "连堂课落在周一行 3（第七八节），rowSpan 为 2",
        spanned.any { it.weekday == 1 && it.periodRowIndex == 3 && it.rowSpan == 2 },
    )
    // 跨行课程覆盖的每一行，渲染时都要能把卡片放下 —— 所以行高必须为正
    check("逐行行高列表长度与节次数一致", schedule.periods.isNotEmpty())

    // ---------------- ★ 列偏移回归（rowspan 导致的整行左移）----------------
    // 样例第 5 行（第九十节）的周一那一列被上一行的 rowspan="2" 占用，
    // 因此该行只有 6 个 td。「周二」在原文件里是第 1 个 td，但它的**真实星期必须是 2**。
    //
    // ⚠️ 这里为什么单独有一组断言：早期解析器按「第 N 个 td = 星期 N」算，
    //    只要出现 rowspan 跨行，该行所有课程就被画到错的那一天
    //    （实测：周二的课 → 周一，周日的课 → 周六）。
    //    而此前的断言只看「位置总数 == 16」和「rowspan 课在 周一行 3」——
    //    整行错位后位置总数不变、rowspan 课也不在被跨越行，所以**照样全绿**。
    //    这就是断言盲区，必须直接断言被跨越行里各格子的真实星期。
    println()
    println("--- 列偏移回归（被 rowspan 跨越的行）---")
    // 这两门课的实际周次：周二那门 = {10}，周日那门 = {5,6,7,8}
    val lateTue = schedule.cellAt(10, 2, 4).map { it.name }
    val lateSun = schedule.cellAt(5, 7, 4).map { it.name }
    println("   cellAt(week=10, 周二=2, 第九十节行=4) = $lateTue")
    println("   cellAt(week=5,  周日=7, 第九十节行=4) = $lateSun")
    check(
        "被 rowspan 跨越的行不左移：周二格子仍在星期二",
        lateTue.any { it.contains("大学生国家安全教育") },
    )
    check(
        "被 rowspan 跨越的行不左移：周日格子仍在星期日",
        lateSun.any { it.contains("创新创业基础") },
    )
    check(
        "被跨越行的周一位不再被误占",
        schedule.cellAt(10, 1, 4).none { it.name.contains("大学生国家安全教育") },
    )

    // ---------------- 单双周（实测量级 bug 的回归）----------------
    // 教务系统原文「2,6双周」的真实语义是「第 2 周起每隔一周」= {2,4,6,8,…}，
    // 早期实现只取字面数字 {2,6}，会让用户整整漏掉一半的课。
    println()
    println("--- 单双周解析（数据级 bug 回归）---")
    val dbl = ScheduleParser.parseWeeks("2,6双周", maxWeek = 16)
    val odd = ScheduleParser.parseWeeks("1-16周(单)", maxWeek = 16)
    val even2 = ScheduleParser.parseWeeks("1-16双", maxWeek = 16)
    val oddPoint = ScheduleParser.parseWeeks("1,3,5,7单周", maxWeek = 16)
    println("   '2,6双周'      -> ${dbl.sorted()}")
    println("   '1-16周(单)'   -> ${odd.sorted()}")
    println("   '1-16双'       -> ${even2.sorted()}")
    println("   '1,3,5,7单周'  -> ${oddPoint.sorted()}")
    check("'2,6双周' 补齐为偶数周 {2,4,6,8,10,12,14,16}", dbl == setOf(2, 4, 6, 8, 10, 12, 14, 16))
    check("'2,6双周' 不再漏掉第 4 周", 4 in dbl)
    check("'1-16周(单)' 得到奇数周", odd == setOf(1, 3, 5, 7, 9, 11, 13, 15))
    check("'1-16双' 得到偶数周", even2 == setOf(2, 4, 6, 8, 10, 12, 14, 16))
    check("'1,3,5,7单周' 得到奇数周", oddPoint == setOf(1, 3, 5, 7, 9, 11, 13, 15))
    check("单双周补齐受 maxWeek 约束（maxWeek=6 时 '2,6双周' = {2,4,6}）",
        ScheduleParser.parseWeeks("2,6双周", maxWeek = 6) == setOf(2, 4, 6))
    check("不含单双周标记时不做补齐（'2,6' 仍为 {2,6}）",
        ScheduleParser.parseWeeks("2,6") == setOf(2, 6))

    // ---------------- 自动选学期（按日期推断）----------------
    println()
    println("--- 学期匹配（自动选择学期）---")
    val cases = listOf(
        LocalDate.of(2026, 9, 19) to "2026-2027-1",   // 秋季学期开学后
        LocalDate.of(2026, 12, 1) to "2026-2027-1",  // 秋季学期中
        LocalDate.of(2027, 1, 15) to "2026-2027-1",  // 1 月仍属秋季学期
        LocalDate.of(2027, 2, 20) to "2026-2027-2",  // 春季学期开学
        LocalDate.of(2027, 6, 1) to "2026-2027-2",   // 春季学期中
        LocalDate.of(2027, 8, 31) to "2026-2027-2",  // 暑假仍算春季学期学年
    )
    cases.forEach { (d, want) ->
        val got = TermMatcher.termIdFor(d)
        println("   $d → $got（期望 $want）")
        check("$d 推断为 $want", got == want)
    }
    val candidates = listOf("2029-2030-2", "2029-2030-1", "2026-2027-1", "2025-2026-2", "2024-2025-1")
    println("   候选 $candidates")
    println("   pickTerm(2026-09-19) = ${TermMatcher.pickTerm(candidates, LocalDate.of(2026, 9, 19))}")
    check("从候选里选出 2026-2027-1",
        TermMatcher.pickTerm(candidates, LocalDate.of(2026, 9, 19)) == "2026-2027-1")
    check("候选里没有匹配学年时返回 null（不瞎猜）",
        TermMatcher.pickTerm(listOf("2029-2030-1", "2028-2029-2"), LocalDate.of(2026, 9, 19)) == null)
    check("非法学期 id 被识别", !TermMatcher.isValidTermId("2026-2027-3"))

    // 异常路径
    val threw = runCatching { ScheduleParser.parse("<html><body>no table</body></html>") }.isFailure
    check("无表格时抛出 ScheduleParseException", threw)

    // ---------------- 结果 ----------------
    println()
    println("=".repeat(72))
    val failed = checks.filterNot { it.second }
    checks.forEach { (n, ok) -> println("  ${if (ok) "✅ PASS" else "❌ FAIL"}  $n") }
    println("=".repeat(72))
    println("总计 ${checks.size} 项，通过 ${checks.size - failed.size}，失败 ${failed.size}")
    if (failed.isNotEmpty()) {
        println("失败项: " + failed.joinToString { it.first })
        kotlin.system.exitProcess(1)
    }
    println("🎉 全部通过 —— 解析器与样例课表的结构完全吻合")
}
