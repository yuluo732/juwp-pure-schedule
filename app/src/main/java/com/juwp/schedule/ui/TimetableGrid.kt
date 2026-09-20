package com.juwp.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.juwp.schedule.data.model.CourseArrangement
import com.juwp.schedule.data.model.SemesterSchedule
import com.juwp.schedule.domain.WeekCalculator
import com.juwp.schedule.ui.theme.assignDistinctCourseColors
import com.juwp.schedule.ui.theme.colorForCourse
import com.juwp.schedule.ui.theme.onPastelOf
import com.juwp.schedule.ui.theme.pastelOf

/**
 * 单周课表网格。
 *
 * 布局：第一列固定为节次/时间，后面 7 列是星期一到星期日。
 * 每行的行高会根据该行「最多几门课」动态调整，避免一门课被挤扁；
 * [minRowHeight] 由外层按「网格可视高度 ÷ 节次数」传入，让行铺满屏幕不留大片空白。
 */
/**
 * 表头（星期 + 日期），独立成 Public 组件：
 * 周课表页把它放在滑动容器外面实现「吸顶」，上下滚动查看课程时表头固定不动。
 * week 变化（滑动切周）时日期列随之刷新。
 */
@Composable
fun TimetableHeaderRow(
    schedule: SemesterSchedule,
    week: Int,
    todayWeekday: Int?,
    highlightToday: Boolean,
    termStartMillis: Long,
    modifier: Modifier = Modifier,
) {
    // 表头日期（有开学日期时显示 M/d）
    val dates = if (termStartMillis > 0) {
        val start = java.time.LocalDate.ofEpochDay(termStartMillis / 86_400_000L)
        WeekCalculator.datesOfWeek(start, week)
    } else {
        null
    }
    Row(
        modifier
            .fillMaxWidth()
            // 78% 半透明：自定义壁纸能隐约透出（与今日页头部一致）；
            // 内部文字都显式着色，不受 contentColor 回退影响
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .padding(vertical = 6.dp)
    ) {
        Box(
            Modifier.width(TIME_COLUMN_WIDTH),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "节次",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (weekday in 1..7) {
            val isToday = highlightToday && weekday == todayWeekday
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = WeekCalculator.weekdayName(weekday),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (dates != null) {
                    Text(
                        text = WeekCalculator.shortDate(dates[weekday - 1]),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isToday) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } else if (isToday) {
                    Text(
                        text = "今天",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 逐行计算行高 = 「该行信息最多的那一格」所需的最小高度（不再按可视高度拉伸）。
 *
 * 关键点：
 *  - 一格多课时按【紧凑模式】算（课程名最多 3 行、不显示地点与教师），
 *    不能把每门课的完整信息行数累加；
 *  - 单课格子按「课程名 5 行 + 地点 4 行 + 教师 3 行」算；
 *  - **连堂课（rowSpan >= 2）的总需求要分摊到它覆盖的每一行**。
 *    否则渲染时那张卡片拿到的是 `rowHeight × span` 的高度，
 *    而它所在的行只有单行那么高，超出的部分会被父级裁掉（卡片下半截消失）。
 *  - 返回的是**逐行**的列表，不是一个统一值 —— 连堂课需要相邻行共同凑够高度。
 *
 * 用 [rememberTextMeasurer] 按真实列宽测量换行数，因此不会出现
 * 「行高够但文字被省略号截掉」或「卡片上下大片空白」。
 *
 * @param textWidth 卡片内部可用文字宽度（列宽 - 卡片内边距），由外层按屏幕宽度算好传入
 */
@Composable
fun rememberRowHeights(schedule: SemesterSchedule, textWidth: Dp): List<Dp> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val chipStyle = MaterialTheme.typography.labelSmall.copy(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    // ⚠️ 这里不能用 `return remember(...) { ... }` 的写法：
    // 那样 lambda 的返回值就是整个 lambda 的结果，内部再写 `return` 属于
    // 「非局部返回」，Kotlin 会报 `'return' is prohibited here`。
    // 正确做法是让表达式自然落在 lambda 末尾作为返回值。
    return remember(schedule, textWidth, chipStyle, density) {
        val maxWidthPx = with(density) { textWidth.roundToPx() }.coerceAtLeast(1)
        fun lines(text: String, cap: Int): Int {
            if (text.isBlank()) return 0
            return measurer.measure(
                text = AnnotatedString(text),
                style = chipStyle,
                constraints = Constraints(maxWidth = maxWidthPx),
            ).lineCount.coerceAtMost(cap)
        }
        val lineHeight = 13.5.dp    // labelSmall 行高 13sp（已关字体留白）+ 余量
        val chipExtra = 7.dp        // 卡片自身上下内边距
        val rowExtra = 1.dp         // 行间距

        // 逐行算高度，而不是整表同一个高度。
        //
        // ⚠️ 为什么必须「逐行」：连堂课（rowSpan >= 2）的卡片在渲染时会拿到
        //    height = rowHeights[row] * span（见下方 CourseChip 的 spannedHeight）。
        //    如果所有行同高、且这个高度只按「单行内容」算出来，那么这张卡片的
        //    实际高度会超出它所在行的可用高度，**超出的部分被父级裁掉** ——
        //    表现就是连堂课卡片下半截不见了（不是注释里原以为的"溢出到后续行"）。
        //
        // 解法：把「跨行课程的总需求」**分摊**到它覆盖的每一行。
        //    这样 sum(覆盖行的行高) + 行间距 恰好 >= 该卡片需要的总高度，
        //    卡片在自己的行范围内就放得下，不会被裁。
        val perRow = MutableList(schedule.periods.size) { 0.dp }
        schedule.periods.forEachIndexed { rowIndex, _ ->
            for (weekday in 1..7) {
                // 本行本列「起始」的课程（跨行课程只在它起始的那一行参与计算）
                val starting = schedule.cells.filter {
                    it.weekday == weekday && it.periodRowIndex == rowIndex
                }
                if (starting.isEmpty()) continue

                val maxWeek = starting.maxOfOrNull { c -> c.course.weeks.maxOrNull() ?: 0 } ?: 0
                for (weekIndex in 1..maxWeek) {
                    val cells = starting.filter { weekIndex in it.course.weeks }
                    if (cells.isEmpty()) continue
                    val compact = cells.size > 1
                    cells.forEach { cell ->
                        val c = cell.course
                        val lineCount = if (compact) {
                            // 紧凑模式：只有课程名（上限与 CourseChip 的 maxLines=3 对齐）
                            lines(c.name, 3)
                        } else {
                            // 完整模式：课程名 5 行 + 地点 4 行 + 教师 3 行。
                            // ⚠️ 这三个上限必须与 CourseChip 里的 maxLines 完全一致，
                            //    否则「算出的行高」与「实际渲染行数」不符，文字仍会被省略号截断。
                            lines(c.name, 5) +
                                lines(formatRoomForChip(c.room), 4) +
                                lines(formatTeachersForChip(c.teachers), 3)
                        }
                        // 跨行课程的总需求**分摊**到它覆盖的每一行：
                        // sum(覆盖行的行高) 才够放下 height = rowHeight × span 的卡片
                        val span = cell.rowSpan.coerceAtLeast(1)
                        val demand = (lineHeight * lineCount + chipExtra) / span
                        if (demand > perRow[rowIndex]) perRow[rowIndex] = demand
                    }
                }
            }
        }
        // 别低于 84dp：否则单行信息的格子看着太扁
        perRow.map { (it + rowExtra).coerceAtLeast(84.dp) }
    }
}

/**
 * 固定节次列（吸顶/冻结）：左右滑动切周时不动，垂直方向与课表网格
 * 共享同一个 ScrollState（由外层传入同一个 state 实现双向同步滚动）。
 */
@Composable
fun TimetablePeriodColumn(
    schedule: SemesterSchedule,
    rowHeights: List<Dp>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(TIME_COLUMN_WIDTH)) {
        schedule.periods.forEachIndexed { rowIndex, period ->
            Column(
                modifier = Modifier
                    .height(rowHeights[rowIndex])
                    .padding(horizontal = 2.dp, vertical = 1.dp),
                // 居中：与右侧拉伸填满整行的卡片垂直居中呼应，左右严格等高对齐
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = period.label,
                    style = MaterialTheme.typography.labelSmall,
                    // 加粗 + onSurface：浅色模式（尤其叠加亮色壁纸时）也有绝对对比度
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (period.timeRange.isNotBlank()) {
                    // 拆成两行显示（08:30 / 09:55），窄列也能看清；
                    // 用 onSurface 保证浅色模式壁纸上有足够对比度（onSurfaceVariant 太浅）
                    val parts = period.timeRange.split('~', '-')
                    Text(
                        text = if (parts.size == 2) "${parts[0].trim()}\n${parts[1].trim()}" else period.timeRange,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
        // 与网格页尾部的 Spacer 等高（底部预留 60dp 悬浮区），
        // 两列滚动范围一致才能保持同步
        Spacer(Modifier.height(60.dp))
    }
}

/**
 * 单周 7 列课程网格（不含节次列与表头——它们由外层固定/吸顶）。
 * [rowHeights] 必须来自 [rememberRowHeights]，与固定节次列逐行等高。
 */
@Composable
fun TimetableWeekGrid(
    schedule: SemesterSchedule,
    week: Int,
    todayWeekday: Int?,
    onCourseClick: (CourseArrangement) -> Unit,
    rowHeights: List<Dp>,
    /** 课程卡片透明化：卡片底色降到 50% 不透明，让自定义背景图透出来 */
    translucentCards: Boolean = false,
    /**
     * 课程名 → 卡片主色。由外层用 [assignDistinctCourseColors] 按网格位置算好，
     * 保证相邻卡片颜色不接近；缺省时退回按课程名哈希取色。
     */
    courseColors: Map<String, Color> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val periods = schedule.periods
    if (periods.isEmpty() || rowHeights.size != periods.size) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("课表为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // 未由外层传入配色时，在这里按整个学期的网格自行算一套（相邻不撞色）。
    // remember 挂在 schedule 上：切周不重算，换学期才重算。
    val colors = remember(schedule, courseColors) {
        if (courseColors.isNotEmpty()) {
            courseColors
        } else {
            assignDistinctCourseColors(
                buildList {
                    schedule.cells.forEach { cell ->
                        cell.course.weeks.forEach { w ->
                            add(Triple(Triple(w, cell.weekday, cell.periodRowIndex), 0, cell.course.name))
                        }
                    }
                }
            )
        }
    }

    Column(modifier.fillMaxSize()) {
        periods.forEachIndexed { rowIndex, _ ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeights[rowIndex])
                    // 行间距压到 0.5dp：整体更紧凑（原 1dp）
                    .padding(vertical = 0.5.dp)
            ) {
                // 7 天（当天列不加背景色块，只保留表头高亮）
                for (weekday in 1..7) {
                    // ⚠️ 必须按【当前这一周】过滤后再决定是否紧凑。
                    // 历史 bug：这里曾用「该格全部记录数」判断紧凑，而一个格子
                    // 往往因为「同一门课不同周次换教室」堆了 3~6 条记录
                    // （如 周2行1 有 6 条国际贸易函电），于是被判成多课 →
                    // 教室与教师被整块隐藏、课程名只给 2 行 → 用户看到「信息显示不全」。
                    // 实测：按周过滤后 81% 的格子只有 1 门课，应当完整显示。
                    val courses = schedule.cells
                        .filter { it.weekday == weekday && it.periodRowIndex == rowIndex && week in it.course.weeks }
                    val compact = courses.size > 1

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(1.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            courses.forEach { cell ->
                                // 连堂课（rowSpan >= 2）纵向占满它覆盖的若干行。
                                //
                                // ⚠️ 这里的高度必须与 rememberRowHeights 里「把总需求分摊到
                                //    每一行」的算法配套：行高之和 + 行间距 >= 本卡片所需总高，
                                //    否则卡片会超出所在行的可用高度、被父级裁掉下半截。
                                val span = cell.rowSpan.coerceAtLeast(1)
                                val spannedHeight = if (span > 1) {
                                    val start = rowIndex
                                    val covered = (start until (start + span).coerceAtMost(rowHeights.size))
                                        .mapNotNull { rowHeights.getOrNull(it) }
                                    if (covered.isEmpty()) {
                                        null
                                    } else {
                                        covered.reduce { a, b -> a + b } + 1.dp * (covered.size - 1)
                                    }
                                } else null
                                CourseChip(
                                    course = cell.course,
                                    compact = compact,
                                    translucent = translucentCards,
                                    // 优先用「相邻不撞色」算出来的颜色；没算到就退回按名字哈希
                                    baseColor = colors[cell.course.name],
                                    onClick = { onCourseClick(cell.course) },
                                    // 单课卡片拉伸填满整行高度；一格多课时各自按内容高度堆叠；
                                    // 连堂课按跨度占满多行
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            when {
                                                spannedHeight != null -> Modifier.height(spannedHeight)
                                                courses.size == 1 -> Modifier.weight(1f)
                                                else -> Modifier.heightIn(min = 28.dp)
                                            }
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 底部预留 60dp：放置「回到本周」悬浮条等底部浮层，课程可完整滑出不被遮挡
        Spacer(Modifier.height(60.dp))
    }
}

/**
 * 课表卡片里的【教师】排版：多名教师各占一行。
 * 「李文彪， 王键」挤在一行时，末尾的逗号+空格会把「王键」挤到第三行被省略号截掉；
 * 改成每人一行后，谁都能完整显示（也顺带省去逗号字符的宽度）。
 */
private fun formatTeachersForChip(teachers: String): String =
    teachers.split(',', '，', '、', ';', '；', '/')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

/**
 * 课表卡片里的地点排版（窄列防截断）：
 *   教学北大楼(北B315) → 教学北大楼⏎(北⏎B315)
 * 三行显示：楼栋名、括号+楼号前缀、房间号，保证「北B315」这种房间号完整可见。
 */
private fun formatRoomForChip(room: String): String {
    if (room.isBlank()) return ""
    // 0) 教务系统里体育课/形势与政策等的教室是空括号 "()"，
    //    直接格式化会在卡片里渲染出孤立的 "(" ")" 两行 → 先判定为无地点
    val stripped = room.replace("(", "").replace(")", "")
        .replace("（", "").replace("）", "").trim()
    if (stripped.isEmpty()) return ""
    // 1) 括号前换行：教学北大楼(北B315) → 教学北大楼⏎(北B315)（中英文括号都处理）
    var s = room.replace("(", "\n(").replace("（", "\n（").trimStart('\n', ' ')
    // 2) 括号内再拆一行：让「楼号前缀」和「房间号」各占一行——
    //    北B315 → (北⏎B315)、S413 → (S⏎413)，窄列里房间号完整可见不截断
    s = Regex("""([（(])([^\d）)]{0,1})([A-Za-z]*\d[^\n）)]*[）)])""").replace(s) {
        "${it.groupValues[1]}${it.groupValues[2]}\n${it.groupValues[3]}"
    }
    return s
}

/** 一格里的课程卡片：粉彩底色 + 同色系深文字，无边框（图 5 风格） */
@Composable
private fun CourseChip(
    course: CourseArrangement,
    compact: Boolean,
    translucent: Boolean,
    /** 卡片主色；null 时按课程名哈希取色 */
    baseColor: Color? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = baseColor ?: colorForCourse(course.name)
    val shape = RoundedCornerShape(8.dp)

    // ⚠️ 配色只有一套，两种模式必须一致 —— 这是用户明确定下的设计语言：
    //   卡片底 = 粉彩（课程色往白里提）
    //   文字   = 同色系深色（课程色压深），与卡片底色是同源色
    // 开启「卡片透明化」时**只降低底色的不透明度**，绝不更换配色。
    //
    // 迭代教训（两轮都做错了，别再犯）：
    //   ① 先把文字越改越灰 → 失去与卡片的同色系关系，用户：「还不如原来」
    //   ② 改成深色底 + 白字 → 底色变深、与未开启模式不一致，用户：「非常难看」
    // 结论：透明化只是"透"，不是"换皮"。可读性只能靠微调 alpha 解决，
    // 不能动色相/明度关系。
    val cardBg = pastelOf(base)
    val textColor = onPastelOf(base)
    val subTextColor = textColor
    // 透明化时卡片底色的不透明度。
    // 0.5 会让同色系深字在叠加亮壁纸后发虚，取 0.68：仍能透出壁纸，
    // 又保住了"文字与卡片同色系"的观感（配色一个字都没改）。
    val cardAlpha = if (translucent) 0.68f else 1f

    // 关掉字体上下留白（includeFontPadding）：每行省 2~3dp，
    // 8 行内容（长课名 + 楼栋房间 + 两位老师）因此能完整显示，不再被省略号截掉教师名
    val chipTextStyle = MaterialTheme.typography.labelSmall.copy(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    Box(
        modifier = modifier
            .clip(shape)
            // 透明化开关：只调背景色的 alpha（0.5），文字仍完全不透明
            .background(cardBg.copy(alpha = cardAlpha))
            .clickable(onClick = onClick)
            // 横向 4dp / 纵向 3dp：文字既不贴边，又能让每行多容纳字符
            // （大字体设置下横向留白过多会造成右侧大片空白的观感）
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Column(
            // fillMaxSize：文本容器严格撑满整张卡片（宽度 100%，不给右侧留死空白）
            Modifier.fillMaxSize(),
            // 内容垂直居中：整体看起来居中不贴顶
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = course.name,
                style = chipTextStyle,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                // 课程名上限 5 行：窄列每行只放得下 2~3 个汉字，
                // 实测最长课名 22 字（如「大学生职业生涯规划与创新创业基础（下）」），
                // 原来只给 3 行必然截断成「…」。行高由 rememberRowHeights 按真实换行数算，
                // 这里放宽不会破版，只是该行整体变高一点。
                maxLines = if (compact) 3 else 5,
                overflow = TextOverflow.Ellipsis,
                // 宽度铺满卡片内部可用区域
                modifier = Modifier.fillMaxWidth(),
            )
            if (!compact) {
                val roomText = formatRoomForChip(course.room)
                if (roomText.isNotBlank()) {
                    Text(
                        text = roomText,
                        style = chipTextStyle,
                        color = subTextColor,
                        // 4 行：楼栋（窄列可能折 2 行）+ 括号楼号 + 房间号，保证房间号完整
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (course.teachers.isNotBlank()) {
                    Text(
                        // 多名教师一人一行（见 formatTeachersForChip）
                        text = formatTeachersForChip(course.teachers),
                        style = chipTextStyle,
                        color = subTextColor,
                        // 3 行：窄列每行只放得下约 3 个字，最多两位老师各一行
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// 节次列收窄：把宽度让给课程卡片（用户反馈 58dp 太宽）；公开给外层做固定列
val TIME_COLUMN_WIDTH = 46.dp
