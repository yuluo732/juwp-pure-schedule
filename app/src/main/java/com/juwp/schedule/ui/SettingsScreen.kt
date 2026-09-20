package com.juwp.schedule.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.juwp.schedule.BuildConfig
import com.juwp.schedule.ui.theme.AppThemeMode
import com.juwp.schedule.ui.theme.ThemeColorPresets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 弹窗为什么改不大也缩不小 —— 一次实测记录，避免后人重走。
 *
 * 需求是「弹窗太大、留白太多，等比缩小」。看起来最自然的做法是局部调大 `LocalDensity`
 * （dp 和 sp 一起缩放，不会出现"格子小了字还很大"）。**实测这条路走不通**：
 * `Dialog` 的内容在独立子组合里渲染，`CompositionLocalProvider(LocalDensity)` 传不进去。
 *
 * 证据（真机 uiautomator 量节点尺寸）：
 *   把系数设成 0.55（极端值，正常一眼就能看出差别），弹窗内
 *     - 日期格子仍为 168x168 px（= 48dp x 3.5，即原始密度）
 *     - 选项文字仍为 57 px 高（= 14sp bodyMedium x 3.5，即原始密度）
 *   数值完全不动，说明覆盖被丢弃。
 *
 * 三种放置位置都试过，全部无效：
 *   ① 包在 `DatePickerDialog` 外层；② 包在 `BasicAlertDialog` 外层；
 *   ③ 自己照抄 M3 的 `DatePickerDialog` 结构、直接调用 `Dialog`（即现在的 [AppDatePickerDialog]）。
 *   根因：`Dialog` 用 `rememberCompositionContext()` 取调用点的组合上下文，
 *   内容在各自窗口的**新组合**里渲染，局部值不参与继承。
 *
 * 结论：弹窗尺寸只能靠「换更小的组件 / 更小的内边距」来压，不能靠密度。
 * 因此 [DIALOG_SCALE] 已被删除 —— 留一个不生效的常量只会误导。
 *
 * 各弹窗的最终处理：
 *   - 自动更新间隔 / 提前提醒时间 → 自绘 [OptionListDialog]，圆点 48dp→18dp、行距 6dp→14dp 内边距，
 *     高度实打实降下来，7 个选项一屏可见（原先第 7 项「60 分钟」被挤出屏幕）
 *   - 开学日期 → M3 把宽度写死 360dp、日期格子写死 48dp，本机 3.5 密度下 360dp = 整屏宽，
 *     **无参数可调、密度也改不动，属于无解**；仅去掉右上角「铅笔」并收紧按钮区
 */



/**
 * 日期选择弹窗。
 *
 * 这是 M3 `DatePickerDialog` 的本地副本 —— 唯一的改动是把按钮区从 `AlertDialogFlowRow`
 * 换成普通 Row（内边距更小），让底部空行少一点。
 *
 * **为什么不顺便把它缩小**：M3 把容器宽度写死成 `requiredWidth(360.dp)`、日期格子写死 48dp
 * （`DatePicker.kt` 里 `requiredHeight(RecommendedSizeForAccessibility * MaxCalendarRows)`），
 * 都没有开放参数。本机密度 3.5 → 360dp = 1260px = 整屏宽，所以这个弹窗天生就是满屏宽的。
 * 想缩只能改密度，但 `CompositionLocalProvider(LocalDensity)` 传不进 Dialog 子组合（见 [DIALOG_SCALE] 注释），
 * 已实测三种写法全部无效。结论：**宽度无解，接受现状**。
 *
 * 保留的这点收益是真实可量化的：按钮区比 M3 少一层 `AlertDialogFlowRow` 的额外间距。
 *
 * @param confirmButton 由调用方提供，因为「确定」要读 DatePicker 的选中值
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .wrapContentHeight()
                .requiredWidth(360.dp)
                .heightIn(max = 568.dp),
            shape = DatePickerDefaults.shape,
            color = DatePickerDefaults.colors().containerColor,
            tonalElevation = DatePickerDefaults.TonalElevation,
        ) {
            Column(verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.weight(1f, fill = false)) { content() }
                Row(
                    Modifier
                        .align(Alignment.End)
                        .padding(bottom = 8.dp, end = 6.dp),
                ) {
                    dismissButton()
                    Spacer(Modifier.width(8.dp))
                    confirmButton()
                }
            }
        }
    }
}

/**
 * 设置页。
 *
 * 面向「不会开发的同学」，所以每一项都配了人话解释，不出现术语。
 * 已改为底部导航中的一个标签页，不再需要返回箭头。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ScheduleUiState,
    onSwitchTerm: (String) -> Unit,
    /** 开关「自动选择学期」 */
    onSetAutoSelectTerm: (Boolean) -> Unit,
    onSetTermStart: (LocalDate) -> Unit,
    onSetReminderEnabled: (Boolean) -> Unit,
    onSetReminderLead: (Int) -> Unit,
    onSetSyncInterval: (Int) -> Unit,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onSetThemeColor: (Long) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetBackgroundHd: (Boolean) -> Unit,
    onSetCardTransparent: (Boolean) -> Unit,
    onTestReminder: () -> Unit,
    onPickBackground: (Uri?) -> Unit,
    onClearBackground: () -> Unit,
    onLogout: () -> Unit,
    onClearAll: () -> Unit,
    /** 打开「关于」二级页面（说明、版本号、GitHub 仓库入口都在那一页） */
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showReminderTimeDialog by remember { mutableStateOf(false) }

    // 系统相册（Photo Picker）：不需要存储权限，选完把 Uri 交给 ViewModel 拷贝保存
    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> onPickBackground(uri) }

    Scaffold(
        // ⚠️ 必须透明：默认底色会把全局壁纸盖住，设置页就成了「没有背景」
        containerColor = Color.Transparent,
        // 透明容器会让默认文字色回退成黑色（深色模式下黑字），
        // 显式绑定 onSurface 后所有未着色文字自动跟随深浅模式
        contentColor = MaterialTheme.colorScheme.onSurface,
        // ⚠️「大上巴」根因：外层 MainScaffold 已处理过系统栏 inset，
        // 内层 Scaffold 再处理一次 + 标题再 statusBarsPadding 就是三重下移。
        // 这里清零 contentWindowInsets，inset 只由最外层负责
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 标题：恢复合理字号（titleLarge 22sp，不再靠缩小字体掩盖布局问题）。
            // inset 由外层 Scaffold 统一处理，这里不再叠 statusBarsPadding（否则就是「大上巴」）
            Text(
                "设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    // 标题与第一张卡片之间留 16dp 呼吸空间（之前贴在一起）
                    .padding(top = 10.dp, bottom = 16.dp, start = 20.dp),
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {

            // ------------------------------------------------ 账号
            SectionCard("账号") {
                InfoRow("学号", state.studentId.ifBlank { "未登录" })
                if (state.lastSyncAt > 0) {
                    InfoRow("上次同步", formatTime(state.lastSyncAt))
                }
            }

            // ------------------------------------------------ 学期
            SectionCard("学期") {
                // 自动选择学期：默认开启，按今天日期推断该上哪个学期
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动选择学期", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (state.autoSelectTerm) {
                                "已开启：打开 App 自动切到当前学期（9月~次年1月为第 1 学期，2~8月为第 2 学期）"
                            } else {
                                "已关闭：始终使用你手动选择的学期"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.autoSelectTerm,
                        onCheckedChange = { onSetAutoSelectTerm(it) },
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp))

                // 学期下拉改成「老 → 新」正序。
                // 教务系统返回的是「新 → 老」（2029-2030-2 排在最前），学期一多就得先滚到底
                // 才能找到当前学期，很别扭。termId 形如 2024-2025-1，字典序恰好等于时间序，
                // 直接按 id 升序排即可，不必解析数字。
                //
                // ⚠️ 数据源是 state.availableTerms，**不是 state.schedule?.availableTerms**：
                // 后者会在切学期时随 schedule 一起被置空，导致这里瞬间弹出「还没有获取到学期列表」
                // 再恢复（用户反馈的「图三闪现」）。学期列表是跨学期的，必须独立保存。
                val terms = state.availableTerms.sortedBy { it.id }
                if (terms.isEmpty()) {
                    Text(
                        "还没有获取到学期列表，请先在课表页下拉刷新一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    val current = terms.firstOrNull { it.id == state.selectedTermId }?.name
                        ?: state.selectedTermId
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                    ) {
                        OutlinedTextField(
                            value = current,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("当前学期") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                        )
                        // 限高 300dp：学期列表很长时弹层不再一直铺到底部导航栏
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.heightIn(max = 300.dp),
                        ) {
                            terms.forEach { term ->
                                DropdownMenuItem(
                                    text = { Text(term.name) },
                                    onClick = {
                                        expanded = false
                                        onSwitchTerm(term.id)
                                    },
                                )
                            }
                        }
                    }
                    if (state.autoSelectTerm) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "注意：开启自动选择时，手动切换只对本次生效，" +
                                "下次打开 App 会按日期重新匹配。要固定用某个学期请关掉上面的开关。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ------------------------------------------------ 开学日期
            SectionCard(
                title = "开学日期",
                subtitle = "选择开学第一周的周一，App 自动按日期推算今天是第几教学周，无需手动调整。",
            ) {
                val termStart = state.termStartMillis.takeIf { it > 0 }?.let {
                    LocalDate.ofEpochDay(it / 86_400_000L)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (termStart != null) {
                            "开学日期：${termStart.year} 年 ${termStart.monthValue} 月 ${termStart.dayOfMonth} 日"
                        } else {
                            "还没设置"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showDatePicker = true }) { Text("选日期") }
                }
            }

            // ------------------------------------------------ 课表更新
            SectionCard(
                title = "课表更新",
                subtitle = "超过所选间隔后，打开 App 或回到前台时会自动同步最新课表，" +
                    "后台也会定时刷新；连不上网时自动显示上次缓存，不影响查看。",
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showIntervalDialog = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Update,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("自动更新间隔", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = when (val h = state.syncIntervalHours) {
                                0 -> "仅手动刷新"
                                1 -> "每 1 小时"
                                else -> "每 $h 小时"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 自动更新的自愈前提：学校会话过期后，App 只能用【记住的密码】静默重登。
                // 没存密码 → 自动更新在会话过期后无法继续（不会再逼你登录，只是静默停止更新）。
                if (state.syncIntervalHours > 0 && !state.rememberPassword) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "提示：开启「记住密码」后，学校登录过期时 App 能自动重新登录，" +
                            "自动更新才能长期保持有效（当前未开启，过期后需手动刷新一次）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ------------------------------------------------ 个性化
            SectionCard(
                title = "个性化",
                subtitle = "主题色会联动顶栏、按钮、今日页卡片等所有地方；背景图铺在全 App 底层。",
            ) {
                Text("主题色", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeColorPresets.forEach { preset ->
                        val selected = preset.argb == state.themeColorArgb
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(preset.argb))
                                .border(
                                    width = if (selected) 3.dp else 0.5.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { onSetThemeColor(preset.argb) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已选择 ${preset.label}",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Spacer(Modifier.height(10.dp))

                // ---- 动态颜色主题（Material You）----
                val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("动态颜色主题", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (supportsDynamic) {
                                "使用系统按壁纸提取的主题色，明暗自动跟随系统"
                            } else {
                                "需要 Android 12 及以上"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.dynamicColor && supportsDynamic,
                        enabled = supportsDynamic,
                        onCheckedChange = { onSetDynamicColor(it) },
                    )
                }
                if (state.dynamicColor && supportsDynamic) {
                    Text(
                        "动态颜色已开启：手动主题色暂不生效，外观自动跟随系统明暗。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自定义背景图", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (state.backgroundImagePath.isBlank()) "未设置，使用主题默认背景"
                            else "已设置，换主题色不影响背景",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        backgroundPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) { Text("选择") }
                    if (state.backgroundImagePath.isNotBlank()) {
                        TextButton(onClick = onClearBackground) { Text("清除") }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("高清背景图", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            // 说清"什么时候才有区别"：只有在导入大图（长边 3200px 以上）时才看得出，
                            // 用的图本身比屏幕还小时两种模式是同一个结果
                            "导入的图片很大（长边 3200px 以上）时才有区别：开启按原图分辨率渲染更清晰、更占内存；" +
                                "关掉会压缩。图片本身不大时两者效果相同。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.backgroundHd,
                        onCheckedChange = { onSetBackgroundHd(it) },
                    )
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Spacer(Modifier.height(10.dp))

                // 课程卡片透明化：周课表卡片降到半透明，让自定义背景图透出来
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("课程卡片透明化", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "周课表卡片变半透明，让自定义背景图透出来（关闭则用不透明底色）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.cardTransparent,
                        onCheckedChange = { onSetCardTransparent(it) },
                    )
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Spacer(Modifier.height(10.dp))

                Text("外观", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                // 禁用态不用系统置灰（会把灰色蒙层糊到外围边框上）：
                // 改为整行统一降透明度，点击在内部拦截，边框保持清晰
                // 选中态直接用全局主题色（primary），不用 M3 默认的 secondaryContainer
                //（动态取色下会变成紫灰色，与用户选择的主题色对不上）
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (state.dynamicColor) 0.45f else 1f)
                ) {
                    AppThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.themeMode == mode,
                            onClick = {
                                if (!state.dynamicColor) onSetThemeMode(mode)
                            },
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = AppThemeMode.entries.size,
                            ),
                        ) {
                            Text(mode.label)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.dynamicColor) {
                        "动态颜色主题开启中，外观由系统明暗决定。"
                    } else {
                        "选「跟随系统」时，手机系统切换深色模式后 App 会立刻跟着变。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ------------------------------------------------ 上课提醒
            SectionCard(
                title = "上课提醒",
                subtitle = "在上课前提醒你。需要允许 App 发通知；" +
                    "如果手机管家拦截了通知，提醒不会弹出。",
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("开启上课提醒", modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.reminderEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                (context as? android.app.Activity)?.let { activity ->
                                    activity.requestPermissions(
                                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                        1001,
                                    )
                                }
                            }
                            onSetReminderEnabled(enabled)
                        },
                    )
                }

                if (state.reminderEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showReminderTimeDialog = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("提前提醒时间", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "提前 ${state.reminderLeadMinutes} 分钟",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                )
                            }
                        }) {
                            Text(
                                "如果提醒偶尔不准时，点这里允许「闹钟和提醒」权限",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "提醒不响或换手机后丢失：请在系统设置里关闭本应用的电池优化（设为不限制）并允许自启动；" +
                            "部分手机上划掉应用卡片会连闹钟一起清掉，属于系统限制，重新打开 App 会自动补排。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        // 直接拉起系统的「忽略电池优化」授权弹窗（需 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        }
                    }) {
                        Text(
                            "点这里把电池优化设为「不限制」（推荐，一劳永逸）",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    Spacer(Modifier.height(6.dp))

                    // 60 秒自检：当场验证「App 关掉后提醒还能不能响」
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onTestReminder() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("提醒自检（60 秒）", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "点一下，然后锁屏或退出 App 等 1 分钟——能收到通知就说明后台提醒正常",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 按品牌引导：不同 ROM 的「后台限制」开关位置完全不同，直接给出本机对应的清单
                    if (ReminderHelp.needsManualSetup()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${ReminderHelp.brandName()} 需要额外放行后台（否则提醒会被系统扣住）：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        ReminderHelp.steps().forEach { step ->
                            Text(
                                "· $step",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        TextButton(onClick = { ReminderHelp.openBackgroundSettings(context) }) {
                            Text(
                                "点这里打开本机的后台设置页面",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------ 危险操作
            SectionCard("账号与数据") {
                TextButton(onClick = { showLogoutDialog = true }) {
                    Text("退出登录")
                }
                Text(
                    "退出后课表缓存保留，仍可离线查看；下次刷新时需要重新登录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(onClick = { showClearDialog = true }) {
                    Text("清空所有数据", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "删除账号、登录状态和课表缓存，等于恢复出厂。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ------------------------------------------------ 关于
            // 说明文字、版本号、GitHub 仓库入口都移到二级页面 AboutScreen 了：
            // 这一屏本来就很长（账号/学期/开学日期/课表更新/个性化/上课提醒/账号与数据），
            // 说明挤在末尾既不好读也不好找，所以这里只留一行入口。
            SectionCard("关于") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenAbout)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "关于极简课程表",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            // 版本号取自 BuildConfig，不再手写字符串 —— 之前写死 v1.5.0，
                            // 发 v1.5.1 时忘了改就会显示错版本
                            "v${BuildConfig.VERSION_NAME} · 说明与 GitHub 仓库",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ---------------------------------------------------- 日期选择弹窗
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.termStartMillis.takeIf { it > 0 }
                ?: System.currentTimeMillis(),
        )
        // 为什么日期弹窗要单独缩小：
        //   M3 的 DatePickerDialog 把尺寸写死成 requiredWidth(360.dp) + heightIn(max = 568.dp)
        //   （见 DatePickerModalTokens.ContainerWidth / ContainerHeight），没有开放参数可调；
        //   本机密度 3.5，360dp 换算正好 1260px = 整屏宽，所以它看起来"顶满全屏、留白巨大"。
        //
        //   缩小只能用「局部调大 LocalDensity」，但它对 Dialog 无效（见 DIALOG_SCALE 注释，已实测）。
        //   所以这里保留 M3 的原始尺寸，只额外做两件不依赖密度的事：
        //   ① 按钮区改用普通 Row（比 AlertDialogFlowRow 少一层间距）；
        //   ② 关掉右上角「铅笔」——桌面端才需要的键盘输入入口，手机上白占一块宽度。
        AppDatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    // DatePicker 内部用 UTC 计算，这里也用 UTC 转日期，避免时区差出一天
                    datePickerState.selectedDateMillis?.let { ms ->
                        onSetTermStart(
                            Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        )
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState, showModeToggle = false)
        }
    }

    // ---------------------------------------------------- 更新间隔弹窗
    if (showIntervalDialog) {
        OptionListDialog(
            title = "自动更新间隔",
            options = listOf(
                0 to "仅手动刷新",
                1 to "每 1 小时",
                3 to "每 3 小时",
                6 to "每 6 小时（推荐）",
                12 to "每 12 小时",
                24 to "每 24 小时",
            ),
            isSelected = { it == state.syncIntervalHours },
            onPick = {
                onSetSyncInterval(it)
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false },
        )
    }

    // ---------------------------------------------------- 提前提醒时间弹窗
    if (showReminderTimeDialog) {
        OptionListDialog(
            title = "提前提醒时间",
            options = listOf(5, 10, 15, 20, 30, 45, 60).map { it to "$it 分钟" },
            isSelected = { it == state.reminderLeadMinutes },
            onPick = {
                onSetReminderLead(it)
                showReminderTimeDialog = false
            },
            onDismiss = { showReminderTimeDialog = false },
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("退出后需要重新输入学号密码才能刷新课表，本地课表仍可查看。确定退出？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout()
                }) { Text("确定退出") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空所有数据") },
            text = { Text("将删除账号、登录状态与课表缓存，且无法恢复。确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onClearAll()
                }) { Text("确定清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 单选列表弹窗（自动更新间隔 / 提前提醒时间共用）。
 *
 * 为什么不用 M3 的 [AlertDialog] + `RadioButton`：
 *  ① `RadioButton` 虽有 20dp 的圆点，但为满足无障碍要求带 48dp 的 `minimumInteractiveComponentSize`，
 *     7 个选项就是 336dp，再叠上 `AlertDialog` 的标题与按钮区，第 7 项（60 分钟）直接被挤出可视区
 *     —— 用户在真机上就看不到「60 分钟」这一项；
 *  ② `AlertDialog` 的内容槽上下各留 24dp、按钮区还要 52dp，对"选一个数字"这种轻量操作留白过多。
 *
 * 这里改用 [BasicAlertDialog] 自绘，把高度真正压下来：
 *   - 圆点换成自绘的 18dp 圆环（[CompactRadioDot]），省掉 48dp 的最小交互尺寸；
 *   - 行内边距 14dp、标题上下 18dp/6dp，比 AlertDialog 紧一档；
 *   - 列表区独立限高并允许滚动，「取消」固定在列表外，选项再多也不会被挤出屏幕。
 *
 * 实测（真机 uiautomator）：7 项全部可见，含此前看不到的「60 分钟」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> OptionListDialog(
    title: String,
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    // 限制最大高度而不是写死高度：选项少时弹窗自然收缩，选项多时才封顶
    val maxContentHeight = LocalConfiguration.current.screenHeightDp.dp * 0.58f
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column {
                Column(
                    Modifier
                        .heightIn(max = maxContentHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 22.dp, top = 18.dp, end = 22.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    options.forEach { (value, label) ->
                        val selected = isSelected(value)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onPick(value) },
                                )
                                .padding(horizontal = 22.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CompactRadioDot(selected)
                            Spacer(Modifier.width(14.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

/**
 * 轻量单选圆点。
 *
 * 不用 M3 的 `RadioButton`：它自带 48dp 的最小交互尺寸，会把每行高度顶到 48dp 以上，
 * 正是弹窗过高的主因。选中态实心点用 primary、未选中描边用 onSurfaceVariant，
 * 与 M3 默认观感一致，只是个子小。整行都可点（见上方 `selectable`），所以不必依赖圆点自身的触控面积。
 */
@Composable
private fun CompactRadioDot(selected: Boolean) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(18.dp)
                .border(2.dp, color, CircleShape)
        )
        if (selected) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(color, CircleShape)
            )
        }
    }
}

/**
 * 设置页统一的卡片容器（80% 不透明表面色 + 标题 + 可选副标题）。
 * 设为 internal 是为了让 [AboutScreen] 复用同一套视觉，不必复制一份。
 */
@Composable
internal fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 卡片 80% 不透明：设置自定义背景图后能隐约透出壁纸，同时保证文字可读
    // （⚠️ 带 alpha 的颜色会让 contentColorFor 匹配失败，必须显式给 contentColor）
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            // 卡片标题恢复 titleMedium（v1.2.3 缩到 titleSmall 后用户反映太小看不清）；
            // 布局紧凑靠 padding/spacing 调节，不靠缩字号
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatTime(millis: Long): String = runCatching {
    val dt = java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
    "%d-%02d-%02d %02d:%02d".format(
        dt.year, dt.monthValue, dt.dayOfMonth, dt.hour, dt.minute,
    )
}.getOrDefault("—")
