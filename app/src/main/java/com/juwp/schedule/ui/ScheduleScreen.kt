package com.juwp.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juwp.schedule.data.model.CourseArrangement
import com.juwp.schedule.data.model.SemesterSchedule
import com.juwp.schedule.domain.WeekCalculator
import kotlinx.coroutines.launch

/**
 * 「回到本周」悬浮条信息：由周课表页上报给根布局，
 * 与「同步完成」提示统一放在根布局的底部浮层容器里纵向排列——
 * 从结构上保证两者绝不互相遮挡（Toast 永远在胶囊正上方）。
 */
data class TimetablePillInfo(
    val displayedWeek: Int,
    val currentWeek: Int,
    val onBackToCurrentWeek: () -> Unit,
)

/**
 * 周课表页。
 *
 * 交互：
 *  - 左右滑动（HorizontalPager）或点 ‹ › 切换教学周，两者共用同一个 pager 状态
 *  - 点「回到本周」跳回当前周
 *  - 下拉刷新重新抓取课表
 *
 * ⚠️ 周次状态只放在 pager 里（单一数据源），不回写 ViewModel：
 *  之前把 pager 页码回写 state.currentWeek，又用 LaunchedEffect 把 state 同步回 pager；
 *  滑动/动画过程中的中间页码会触发反向修正，两个动画互相打架，
 *  表现就是「‹ › 点了像没反应」。现在 ‹ › 和滑动都只操作 pager，问题根除。
 */
@Composable
fun ScheduleScreen(
    state: ScheduleUiState,
    onRefresh: () -> Unit,
    onTimetablePill: (TimetablePillInfo?) -> Unit = {},
) {
    val schedule = state.schedule

    Box(Modifier.fillMaxSize()) {
        when {
            schedule == null -> EmptySchedule(onRefresh = onRefresh, syncing = state.syncing)
            // 该学期没有任何节次数据（空课表学期）：整页居中提示
            schedule.periods.isEmpty() -> EmptyTermNotice()
            else -> ScheduleContent(
                schedule = schedule,
                state = state,
                onRefresh = onRefresh,
                onTimetablePill = onTimetablePill,
            )
        }
    }
}

/**
 * 选到的学期没有课表数据时的整页提示。
 * 在整页居中渲染（而不是塞进网格里），保证一眼看到。
 *
 * 视觉层级（定稿）：
 *   颜文字  18sp / alpha 0.4   —— 点缀
 *   标题    22sp / alpha 1.0   —— 主角（加粗）
 *   提示语  14sp / alpha 0.6   —— 辅助
 *   间距    12dp / 6dp
 *
 * 迭代记录：曾试过 32/36/26sp（按设计稿 px÷2 换算），实测标题高 148px、
 * 提示语折成 3 行、整块视觉过重，用户反馈「字体太大」故回滚。
 * 另：正文尺寸受系统「显示大小」影响，本机密度下 1sp ≈ 2.05px。
 *
 * ⚠️ 层级一律用「同一个基色 + alpha」表达，**不写死灰色值**：
 * 基色取 onSurface，浅色模式是深色字、深色模式是浅色字，
 * 三种 alpha 在两种主题下都能得到正确对比度。
 */
@Composable
private fun EmptyTermNotice() {
    val base = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                "¯\\_(ツ)_/¯",
                fontSize = 18.sp,
                lineHeight = 24.sp,
                color = base.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "课表为空",
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                color = base.copy(alpha = 1.0f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                // 文案精简，保证一行放下（长句会折行把排版拉散）
                "可在「设置」里换个学期",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = base.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleContent(
    schedule: SemesterSchedule,
    state: ScheduleUiState,
    onRefresh: () -> Unit,
    onTimetablePill: (TimetablePillInfo?) -> Unit,
) {
    val maxWeek = schedule.maxWeek.coerceAtLeast(1)
    // 页码不进 rememberSaveable：切走再切回会回到「本周」，这是**既有行为**。
    // 原因：底部导航切页时本页被销毁，pagerState 随之重建，initialPage 取 state.currentWeek。
    // 用户已确认这是预期效果（"切走再切回，自动切回本周不用更改"），
    // 不要再自作主张改成「记住上次翻到第几周」。
    val pagerState = rememberPagerState(
        initialPage = (state.currentWeek - 1).coerceIn(0, maxWeek - 1),
        pageCount = { maxWeek },
    )
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()
    // 点开的那个格子里的课程：单课时只有 1 门，同周并存多门课时会有多门
    var selectedCourses by remember { mutableStateOf<List<CourseArrangement>>(emptyList()) }
    var showWeekPicker by remember { mutableStateOf(false) }
    val displayedWeek = pagerState.currentPage + 1

    // 上报「回到本周」悬浮条状态给根布局：与「同步完成」提示统一放在底部浮层容器里
    // 纵向排列（Toast 永远在胶囊正上方），从结构上杜绝互相遮挡
    LaunchedEffect(displayedWeek, state.currentWeek) {
        if (displayedWeek != state.currentWeek) {
            val target = (state.currentWeek - 1).coerceIn(0, maxOf(schedule.maxWeek - 1, 0))
            onTimetablePill(
                TimetablePillInfo(
                    displayedWeek = displayedWeek,
                    currentWeek = state.currentWeek,
                    onBackToCurrentWeek = {
                        scope.launch { pagerState.animateScrollToPage(target) }
                    },
                )
            )
        } else {
            onTimetablePill(null)
        }
    }
    // 离开周课表页时清除，避免悬浮条残留在其它页面
    DisposableEffect(Unit) {
        onDispose { onTimetablePill(null) }
    }

    Column(Modifier.fillMaxSize()) {
        // ---------------- 紧凑周次栏（单行，78% 半透明与表头一致，无阴影） ----------------
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // Box 三层叠加：中间标题按屏幕物理中心居中（不受左右按钮宽度影响），
            // 左右箭头分别贴两端 → 天然对称；不再保留刷新按钮的 48dp 占位
            Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                IconButton(
                    onClick = {
                        val target = (pagerState.currentPage - 1).coerceAtLeast(0)
                        scope.launch { pagerState.animateScrollToPage(target) }
                    },
                    enabled = pagerState.currentPage > 0,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "上一周")
                }

                IconButton(
                    onClick = {
                        val target = (pagerState.currentPage + 1).coerceAtMost(maxWeek - 1)
                        scope.launch { pagerState.animateScrollToPage(target) }
                    },
                    enabled = pagerState.currentPage < maxWeek - 1,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "下一周")
                }

                // 「第 X 周 + 日期范围」块：绝对居中（Box 的 Center 对齐），
                // 宽度收窄到 62% 给两侧箭头留出安全区，避免长日期串到箭头下面
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.62f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showWeekPicker = true }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "第 ${pagerState.currentPage + 1} 周",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val range = weekDateRangeText(state.termStartMillis, pagerState.currentPage + 1)
                    val offline = if (state.fromCache && state.lastSyncAt > 0) " · 离线缓存" else ""
                    if (range != null || offline.isNotEmpty()) {
                        Text(
                            text = "${range.orEmpty()}$offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // ---------------- 吸顶表头（星期 + 日期）：不随网格滚动 ----------------
        TimetableHeaderRow(
            schedule = schedule,
            week = pagerState.currentPage + 1,
            todayWeekday = state.todayWeekday,
            highlightToday = pagerState.currentPage + 1 == state.currentWeek,
            termStartMillis = state.termStartMillis,
        )

        // ---------------- 固定节次列 + 课表网格（HorizontalPager：左右滑动切周） ----------------
        // BoxWithConstraints 首帧就给出可视宽度，行高一步到位（无二段式闪烁）
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 行高改为「按内容实测」：用 TextMeasurer 按真实列宽算换行数，
            // 不再按可视高度均分 —— 否则信息多的格子必被省略号截断。
            // 7 列均分剩余宽度，再减去卡片内边距(4×2)与格子间距(1×2)
            val chipTextWidth = ((maxWidth - TIME_COLUMN_WIDTH) / 7 - 10.dp).coerceAtLeast(24.dp)
            // 本级次前缀（学号前 4 位 → 如 "24"）：一格多课时用它把正课排到重修课前面
            val ownGrade = remember(state.studentId) { ownGradeOf(state.studentId) }
            val rowHeights = rememberRowHeights(
                schedule = schedule,
                textWidth = chipTextWidth,
                ownGrade = ownGrade,
            )
            // 固定节次列与每一页网格共享同一个垂直滚动状态 → 上下滚动完全同步
            val vScroll = rememberScrollState()

            // 下拉刷新包住【整个课表区域】（节次列 + 网格）：
            // 指示器以整个屏幕水平中轴为基准居中，不再偏向右侧课程区
            PullToRefreshBox(
                isRefreshing = state.syncing,
                onRefresh = onRefresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(Modifier.fillMaxSize()) {
                    // 固定节次列：左右滑动切周时纹丝不动（不随 Pager 横向移动）
                    if (schedule.periods.isNotEmpty()) {
                        Column(
                            Modifier
                                .width(TIME_COLUMN_WIDTH)
                                .verticalScroll(vScroll)
                        ) {
                            TimetablePeriodColumn(schedule = schedule, rowHeights = rowHeights)
                        }
                    }

                    HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(vScroll)
                        ) {
                            TimetableWeekGrid(
                                schedule = schedule,
                                week = page + 1,
                                todayWeekday = state.todayWeekday,
                                onCourseClick = { selectedCourses = it },
                                rowHeights = rowHeights,
                                // 设置页「课程卡片透明化」开关
                                translucentCards = state.cardTransparent,
                                ownGrade = ownGrade,
                            )
                        }
                    }
                }
            }
        }
    }

    if (selectedCourses.isNotEmpty()) {
        CourseDetailSheet(courses = selectedCourses, onDismiss = { selectedCourses = emptyList() })
    }

    // ---------------- 周次快速跳转弹窗（点顶部「第 X 周」弹出） ----------------
    if (showWeekPicker) {
        AlertDialog(
            onDismissRequest = { showWeekPicker = false },
            title = { Text("跳转到周次") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    (1..schedule.maxWeek).chunked(4).forEach { rowWeeks ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowWeeks.forEach { w ->
                                val selected = w == pagerState.currentPage + 1
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            RoundedCornerShape(8.dp),
                                        )
                                        .clickable {
                                            scope.launch { pagerState.animateScrollToPage(w - 1) }
                                            showWeekPicker = false
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "$w",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                            repeat(4 - rowWeeks.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWeekPicker = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EmptySchedule(
    onRefresh: () -> Unit,
    syncing: Boolean,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text("还没有课表数据", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "连网同步一次即可；学期、周次等设置在底部导航的「设置」里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onRefresh, enabled = !syncing) {
                Text(if (syncing) "同步中…" else "立即同步")
            }
        }
    }
}

/** 第 N 周的日期范围文本，例如 "9/14 - 9/20"；没有开学日期时返回 null */
private fun weekDateRangeText(termStartMillis: Long, week: Int): String? {
    if (termStartMillis <= 0) return null
    return runCatching {
        val start = java.time.LocalDate.ofEpochDay(termStartMillis / 86_400_000L)
        val dates = WeekCalculator.datesOfWeek(start, week)
        "${WeekCalculator.shortDate(dates.first())} - ${WeekCalculator.shortDate(dates.last())}"
    }.getOrNull()
}
