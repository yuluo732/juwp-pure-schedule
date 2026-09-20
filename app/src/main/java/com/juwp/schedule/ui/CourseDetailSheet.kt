package com.juwp.schedule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juwp.schedule.data.model.CourseArrangement
import com.juwp.schedule.ui.theme.colorForCourse

/**
 * 课程详情弹层。点课程卡片弹出，展示教务系统里的全部字段。
 *
 * @param courses 该卡片代表的课程安排。**通常只有 1 门**；
 *   但当同一格子里同周并存多门课时（实测：重修课与主课时间冲突，例如
 *   `2025-2026-2` 周一第一二节的「高等数学B(下)」与「马克思主义基本原理」）
 *   课表上只画第一门、右上角标「+N」，点开就把这几门**逐门列全**，
 *   学生不会因为行高被压掉而漏看一门课。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    courses: List<CourseArrangement>,
    onDismiss: () -> Unit,
) {
    val first = courses.firstOrNull() ?: return
    val multiple = courses.size > 1
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val baseColor = colorForCourse(first.name)
    // 详情弹层最大高度 = 屏幕的 60%，保持「半屏卡片」观感而不是全屏页
    val configuration = LocalConfiguration.current
    val maxSheetHeight = (configuration.screenHeightDp * 0.6f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            // 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = baseColor),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.School,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (multiple) "本时段 ${courses.size} 门课" else first.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            if (multiple) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "这几门课在同一时段并存（常见于重修课与主课冲突），逐门列出：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            courses.forEachIndexed { index, course ->
                if (index > 0) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                }
                Spacer(Modifier.height(12.dp))
                if (multiple) {
                    Text(
                        text = "${index + 1}. ${course.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorForCourse(course.name),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                CourseDetailFields(course)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (multiple) {
                    "同一时段的多门课会按周次各自生效；上面的「周次」是各自独立的。"
                } else {
                    "同一天同一节次若在不同周次上课，会按周次分别显示；" +
                        "本卡片只代表其中一段安排。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单门课的字段列表（多课弹层里会被逐门复用） */
@Composable
private fun CourseDetailFields(course: CourseArrangement) {
    DetailRow("教师", course.teachers)
    DetailRow("地点", course.room)
    DetailRow("时间", formatPeriods(course.periodRaw))
    DetailRow("周次", formatWeeks(course.weeksRaw, course.weeks))
    DetailRow("课程编号", course.courseCode)
    DetailRow("班级", course.classes)
    DetailRow("考核方式", course.assessment)
    DetailRow("总学时", course.totalHours)
    DetailRow("总人数", course.studentCount)
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "1-2"（或旧缓存里的 "1-2节"）→ "第 1-2 节"；防御性去掉原文的「节」再拼量词 */
private fun formatPeriods(raw: String): String {
    val clean = raw.replace("节", "").trim()
    if (clean.isBlank()) return ""
    return "第 $clean 节"
}

/**
 * 周次友好化：把 "1-2,4-9,11"（或旧缓存里的 "1-2,4-9,11周"）展开成
 * "第 1-2,4-9,11 周（共 9 周）"；防御性去掉原文的「周」再拼量词。
 */
private fun formatWeeks(raw: String, weeks: Set<Int>): String {
    val base = raw.replace("周", "").trim().ifBlank { weeks.sorted().joinToString(",") }
    if (base.isBlank()) return ""
    return "第 $base 周（共 ${weeks.size} 周）"
}
