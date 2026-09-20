package com.juwp.schedule.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.juwp.schedule.data.model.CourseArrangement
import com.juwp.schedule.data.model.SemesterSchedule
import com.juwp.schedule.domain.WeekCalculator
import com.juwp.schedule.ui.theme.colorForCourse
import com.juwp.schedule.ui.theme.lighten
import java.time.LocalDate

// ------------------------------------------------------------------ 数据准备

/** 今日页的一节课展示块 */
private data class TodayBlock(
    val course: CourseArrangement,
    val periodRowIndex: Int,
    val startTime: String,
    val endTime: String,
    val periodLabel: String,
)

/**
 * 从整学期课表里取出「本周今天」的课。
 *
 * 注意铁律 3：一个格子里的多门课只是周次不同，按「当前周」过滤后每格最多 1 门，
 * 所以这里每个节次行只取一条安排，天然不会出现并课。
 */
private fun buildTodayBlocks(
    schedule: SemesterSchedule?,
    week: Int,
    weekday: Int,
): List<TodayBlock> {
    if (schedule == null) return emptyList()
    return schedule.cells
        .filter { it.weekday == weekday && week in it.course.weeks }
        .groupBy { it.periodRowIndex }
        .map { (rowIndex, cellsInSlot) ->
            val cell = cellsInSlot.first()
            val period = schedule.periods.getOrNull(rowIndex)
            val times = period?.timeRange?.split('~', '-', '—')?.map { it.trim() }.orEmpty()
            TodayBlock(
                course = cell.course,
                periodRowIndex = rowIndex,
                startTime = times.getOrNull(0) ?: "",
                endTime = times.getOrNull(1) ?: "",
                periodLabel = period?.label ?: "",
            )
        }
        .sortedBy { it.periodRowIndex }
}

// ------------------------------------------------------------------ 页面

@Composable
fun TodayScreen(
    state: ScheduleUiState,
    onRefresh: () -> Unit,
    onOpenTimetable: () -> Unit,
) {
    // 日期与星期都来自 state.today / state.todayWeekday（recomputeWeek 用同一个
    // LocalDate.now() 一起算），跨零点由 ViewModel 的定时器与前台跨天检测统一刷新，
    // 不再各自取时间，杜绝「日期变了星期没变」
    val today = state.today
    val blocks = remember(state.schedule, state.currentWeek, state.todayWeekday, state.today) {
        buildTodayBlocks(state.schedule, state.currentWeek, state.todayWeekday)
    }
    var selectedCourse by remember { mutableStateOf<CourseArrangement?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---------------- 日期 + 教学周 头部卡片 ----------------
        TodayHeader(
            dateText = "${today.monthValue}月${today.dayOfMonth}日",
            weekdayText = WeekCalculator.weekdayName(state.todayWeekday),
            weekText = if (state.termStartMillis > 0) {
                "第 ${state.currentWeek} 教学周"
            } else {
                "开学日期未设置，去设置里选一下"
            },
            syncing = state.syncing,
            onRefresh = onRefresh,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "今日课程 · ${blocks.size} 节",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )

        Spacer(Modifier.height(10.dp))

        when {
            // 首次拉取中：显示转圈而不是空态卡片。
            // 否则「登录态还在、缓存已清」的启动瞬间会先闪一下「还没有课表数据」（用户反馈的图二）
            state.schedule == null && state.firstLoad -> {
                LoadingCard()
            }

            state.schedule == null -> {
                NoDataCard(onRefresh = onRefresh, syncing = state.syncing)
            }

            blocks.isEmpty() -> {
                EmptyTodayCard(onOpenTimetable = onOpenTimetable)
            }

            else -> {
                blocks.forEach { block ->
                    TodayCourseCard(block = block, onClick = { selectedCourse = block.course })
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    selectedCourse?.let { course ->
        CourseDetailSheet(course = course, onDismiss = { selectedCourse = null })
    }
}

// ------------------------------------------------------------------ 头部卡片

@Composable
private fun TodayHeader(
    dateText: String,
    weekdayText: String,
    weekText: String,
    syncing: Boolean,
    onRefresh: () -> Unit,
) {
    // 顶部卡片 78% 透明度的主题色：自定义背景图能隐约透出来（用户指定 70%-80%）；
    // 带 alpha 的颜色必须显式给 contentColor，内部文字才不会回退成黑色
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "$dateText  $weekdayText",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = weekText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                )
            }
            // 固定 48dp 槽位：加载圈与按钮同尺寸，同步时卡片高度不再缩窄抖动
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新课表",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 课程卡片

@Composable
private fun TodayCourseCard(block: TodayBlock, onClick: () -> Unit) {
    val courseColor = colorForCourse(block.course.name)
    // 深色模式下 surfaceVariant 偏灰偏亮，与背景拉不开层次：
    // 深色时改用更深的近黑底色（#1C1C1E），浅色保持 surfaceVariant；
    // ⚠️ 带 alpha 的颜色会让 contentColorFor 匹配失败，必须显式给 contentColor
    // 卡片不加描边（用户反馈边框突兀），通透感由半透明底提供
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) {
        Color(0xFF1C1C1E).copy(alpha = 0.82f)
    } else {
        // 浅色：纯白卡片（surface = #FFFFFF），与柔和的 #F5F5F5 全局背景形成层级对比
        MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
    }
    Surface(
        color = cardColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧时间块
            Surface(
                color = courseColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = block.startTime.ifBlank { "--:--" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = courseColor,
                    )
                    Text(
                        text = block.endTime.ifBlank { "--:--" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            // 右侧课程信息
            Column(Modifier.weight(1f)) {
                Text(
                    text = block.course.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                InfoLine(
                    icon = { Icon(Icons.Filled.Person, null, Modifier.size(13.dp), MaterialTheme.colorScheme.onSurfaceVariant) },
                    text = block.course.teachers.ifBlank { "老师未标注" },
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                InfoLine(
                    icon = { Icon(Icons.Filled.Place, null, Modifier.size(13.dp), MaterialTheme.colorScheme.onSurfaceVariant) },
                    text = block.course.room.ifBlank { "地点未标注" },
                    maxLines = 2,
                )
            }

            // 节次标签：用课程主题色的浅色变体做底 + 同色文字，
            // 在白色卡片上立刻凸显（之前用 surfaceVariant 与卡片底色融为一体）
            if (block.periodLabel.isNotBlank()) {
                Surface(
                    color = courseColor.copy(alpha = if (isDark) 0.26f else 0.18f),
                    contentColor = if (isDark) courseColor.lighten(0.3f) else courseColor,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = block.periodLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoLine(icon: @Composable () -> Unit, text: String, maxLines: Int = 1) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ 空状态

/**
 * 首次拉取课表时的占位卡片：只显示转圈，**不显示任何"空"文案**。
 * 目的是消除启动瞬间「还没有课表数据」的闪烁。
 */
@Composable
private fun LoadingCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "正在获取课表…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoDataCard(onRefresh: () -> Unit, syncing: Boolean) {
    Surface(
        // 浅色模式下 0.74 几乎看不出卡片边界，统一收到 0.5
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.NightsStay,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text("还没有课表数据", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "连网同步一次就能看到今天的课",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRefresh, enabled = !syncing) {
                Text(if (syncing) "同步中…" else "立即同步")
            }
        }
    }
}

@Composable
private fun EmptyTodayCard(onOpenTimetable: () -> Unit) {
    // 透明度统一 0.5：深色模式透出壁纸效果好，浅色模式也不再"看不到卡片"
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.5f else 0.62f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    // 云月图标（Material Rounded 变体，自带一朵云）
                    Icons.Rounded.NightsStay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("今天没有课", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "好好休息，或者看看这周其它天的安排",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpenTimetable,
                // 按钮也做半透明（0.8）：和卡片一起透出壁纸
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("去看周课表")
            }
        }
    }
}
