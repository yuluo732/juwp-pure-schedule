package com.juwp.schedule.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs

// ------------------------------------------------------------------ 主题模式

/** 外观模式：跟随系统 / 浅色 / 深色。名字与 DataStore 里的字符串一一对应 */
enum class AppThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun fromRaw(raw: String?): AppThemeMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

// ------------------------------------------------------------------ 主题色预设

/** 可选主题色预设（ARGB）。默认经典蓝，与旧版视觉一致 */
data class ThemeColorPreset(val label: String, val argb: Long)

val ThemeColorPresets: List<ThemeColorPreset> = listOf(
    ThemeColorPreset("经典蓝", 0xFF2B5CE6),
    ThemeColorPreset("湖光青", 0xFF0CA678),
    ThemeColorPreset("天空青", 0xFF1098AD),
    ThemeColorPreset("葡萄紫", 0xFF7048E8),
    ThemeColorPreset("落日橙", 0xFFE8590C),
    ThemeColorPreset("樱花粉", 0xFFD6336C),
)

// ------------------------------------------------------------------ 颜色工具

/** 把 [this] 与 [other] 按 [fraction] 比例混合（0 = 自身，1 = other） */
fun Color.mix(other: Color, fraction: Float): Color = Color(
    red = red + (other.red - red) * fraction,
    green = green + (other.green - green) * fraction,
    blue = blue + (other.blue - blue) * fraction,
    alpha = 1f,
)

fun Color.lighten(fraction: Float) = mix(Color.White, fraction)
fun Color.darken(fraction: Float) = mix(Color.Black, fraction)

/**
 * 课程块的粉彩底色（参考 WakeUp 风格）：把饱和的课程色往白里提，
 * 文字用原色压深，深浅模式都用同一套粉彩，观感与图 5 一致。
 *
 * 卡片配色**只有一套**，两种显示模式共用：
 * 粉彩底（[pastelOf]）+ 同色系深色文字（[onPastelOf]），文字与底色同源。
 *
 * 开启「卡片透明化」时只调底色 alpha，**绝不更换配色方案**。
 *
 * ⚠️ 这里曾两度被改坏，别再犯：
 *  ① 按 WCAG 对比度把文字"加深防发虚" → 文字变灰、失去与卡片的同色系关系，用户：「还不如原来」；
 *  ② 改成深色底 + 白字 → 底色与未开启模式不一致，用户：「非常难看」。
 * 结论：透明化是"透明度"功能，不是"换主题"功能。
 */
fun pastelOf(base: Color): Color = base.lighten(0.62f)
fun onPastelOf(base: Color): Color = base.darken(0.42f)

/**
 * 为一周的课表挑选颜色，确保**相邻（上下/左右）课程卡片颜色不接近**。
 *
 * 原实现是「课程名哈希 → 固定色板」，哈希碰撞会让相邻格子拿到几乎一样的颜色，
 * 视觉上分不清边界。这里改为按网格位置贪心分配：
 *
 *  1. 课程一旦分到颜色就固定下来（跨周保持稳定，避免滑动切周时颜色跳变）；
 *  2. 分色时**优先避开四邻已占用的颜色**（色相距离 < 阈值的都算冲突）；
 *  3. 若该课的「主色」可用就优先复用（同一门课尽量同色）；
 *  4. 仍然冲突时，从整周未被使用的颜色里挑，最后才退化为"避开四邻即可"。
 *
 * 兜底保证：无论怎么挑，都从「与四邻都不冲突」的候选里选；
 * 只有当色板里一个可用色都没有时才放宽（此时至少保证不是完全同色）。
 *
 * @param layout 每个格子：`(week, weekday, periodRowIndex) to 课程名` 的列表
 */
fun assignDistinctCourseColors(
    layout: List<Triple<Triple<Int, Int, Int>, Int, String>>,
    palette: List<Color> = CoursePalette,
    hueThreshold: Float = 34f,
): Map<String, Color> {
    if (palette.isEmpty()) return emptyMap()

    // 先用课程名哈希定一个"主色"，同一门课尽量用它，保证观感稳定
    val preferred = mutableMapOf<String, Color>()
    layout.forEach { (_, _, name) -> preferred.getOrPut(name) { colorForCourse(name) } }

    val assigned = mutableMapOf<String, Color>()
    val byWeek = layout.groupBy { it.first.first }

    byWeek.toSortedMap().forEach { (_, entries) ->
        // 已占用记录：按「星期 to 节次行」索引，查四邻时直接用真实坐标
        val takenAt = mutableMapOf<Pair<Int, Int>, MutableList<Color>>()

        fun neighborsOf(weekday: Int, row: Int): List<Color> = buildList {
            addAll(takenAt[weekday to (row - 1)].orEmpty())
            addAll(takenAt[weekday to (row + 1)].orEmpty())
            addAll(takenAt[(weekday - 1) to row].orEmpty())
            addAll(takenAt[(weekday + 1) to row].orEmpty())
        }

        // 按「先上后下、先左后右」顺序填色，先填的格子选色自由度更大
        entries.sortedBy { it.first.second * 100 + it.first.third }.forEach { (key, _, name) ->
            val weekday = key.second
            val periodRow = key.third
            val existing = assigned[name]
            if (existing != null) {
                takenAt.getOrPut(weekday to periodRow) { mutableListOf() }.add(existing)
                return@forEach
            }
            val neighbors = neighborsOf(weekday, periodRow)
            // 「可用」= 与所有已占位的四邻色相距离都不小于阈值
            val ok: (Color) -> Boolean = { cand ->
                neighbors.none { hueDistance(it, cand) < hueThreshold }
            }
            val usedColors = assigned.values.toSet()
            // 优先级：该课主色 → 整周未用过的色 → 任意可用色 → 放宽（取与四邻最远的）
            val pick = listOf(preferred.getValue(name)).firstOrNull(ok)
                ?: palette.firstOrNull { it !in usedColors && ok(it) }
                ?: palette.firstOrNull(ok)
                ?: palette.minByOrNull { cand ->
                    neighbors.minOfOrNull { hueDistance(it, cand) } ?: 360f
                }
                ?: palette.first()
            assigned[name] = pick
            takenAt.getOrPut(weekday to periodRow) { mutableListOf() }.add(pick)
        }
    }
    return assigned
}

/** 两个颜色的色相环形距离（0..180） */
private fun hueDistance(a: Color, b: Color): Float {
    val ha = hueOf(a)
    val hb = hueOf(b)
    val d = abs(ha - hb) % 360f
    return if (d > 180f) 360f - d else d
}

/** 提取色相（取颜色在色环上的角度，0..360） */
private fun hueOf(c: Color): Float {
    val maxC = maxOf(c.red, c.green, c.blue)
    val minC = minOf(c.red, c.green, c.blue)
    val d = maxC - minC
    if (d == 0f) return 0f
    val h = when (maxC) {
        c.red -> 60f * (((c.green - c.blue) / d) % 6f)
        c.green -> 60f * (((c.blue - c.red) / d) + 2f)
        else -> 60f * (((c.red - c.green) / d) + 4f)
    }
    return if (h < 0f) h + 360f else h
}

/**
 * 由主题色种子生成整套 Material3 配色。
 *
 * 为什么不用动态取色（Material You）：那是 Android 12+ API 且依赖壁纸，
 * 我们要的是「用户自己选一个颜色、全 App 立刻统一变化」，直接从种子色推导更可控。
 * 推导规则：亮色模式用原色做 primary，往白里混出 container；深色模式把 primary
 * 往白里提亮（保证在深色表面上的对比度），container 往黑里压深。
 */
private fun buildLightColors(seed: Color) = lightColorScheme(
    primary = seed,
    onPrimary = Color.White,
    primaryContainer = seed.lighten(0.86f),
    onPrimaryContainer = seed.darken(0.35f),
    secondary = seed.darken(0.2f),
    onSecondary = Color.White,
    // 浅色模式全局背景用柔和浅灰（#F5F5F5）：避免纯白长时间刺眼，
    // 与纯白卡片形成柔和层级对比
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF44474E),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    outline = Color(0xFFC5C7CE),
)

private fun buildDarkColors(seed: Color) = darkColorScheme(
    primary = seed.lighten(0.55f),
    onPrimary = seed.darken(0.75f),
    primaryContainer = seed.darken(0.15f),
    onPrimaryContainer = seed.lighten(0.85f),
    secondary = seed.lighten(0.7f),
    onSecondary = seed.darken(0.75f),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1B1C1F),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF8E9099),
)

// ------------------------------------------------------------------ 课程配色

// 课程卡片配色：按课程名哈希取色，保证同一门课颜色稳定
val CoursePalette: List<Color> = listOf(
    Color(0xFF4C6EF5), // 蓝
    Color(0xFF12B886), // 青绿
    Color(0xFFE8590C), // 橙
    Color(0xFF9C36B5), // 紫
    Color(0xFFF03E3E), // 红
    Color(0xFF0CA678), // 绿
    Color(0xFF1C7ED6), // 亮蓝
    Color(0xFFF59F00), // 琥珀
    Color(0xFF7048E8), // 靛
    Color(0xFFE64980), // 玫红
    Color(0xFF2F9E44), // 深绿
    Color(0xFF1098AD), // 蓝绿
)

/** 同一门课恒定取到同一个颜色 */
fun colorForCourse(name: String): Color {
    if (name.isEmpty()) return CoursePalette.first()
    var hash = 0
    for (ch in name) hash = hash * 31 + ch.code
    val idx = ((hash % CoursePalette.size) + CoursePalette.size) % CoursePalette.size
    return CoursePalette[idx]
}

// ------------------------------------------------------------------ 字体

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp),
)

// ------------------------------------------------------------------ 主题入口

/**
 * 全局主题。
 *
 * @param mode 外观模式（跟随系统时实时响应系统深浅色切换）
 * @param seed  用户选择的主题色；深浅两套配色都由它推导，改一处全 App 联动
 * @param dynamic 动态颜色主题（Material You）：开启后【彻底接管配色】——
 *                整个 ColorScheme（背景/卡片/文字/分割线）全部来自系统动态色盘，
 *                且明暗强制跟随系统（isSystemInDarkTheme），忽略手动外观 mode，
 *                因为动态色盘本身就是按系统当前明暗给出的
 */
@Composable
fun PureScheduleTheme(
    mode: AppThemeMode = AppThemeMode.SYSTEM,
    seed: Color = Color(ThemeColorPresets.first().argb),
    dynamic: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 动态颜色开启时强制跟随系统明暗，手动外观设置不参与
    val darkTheme = if (dynamic) {
        isSystemInDarkTheme()
    } else {
        when (mode) {
            AppThemeMode.SYSTEM -> isSystemInDarkTheme()
            AppThemeMode.LIGHT -> false
            AppThemeMode.DARK -> true
        }
    }
    val context = LocalContext.current
    val colorScheme = when {
        // 动态取色：完全交给系统色盘（不做任何色阶覆盖，避免"过度修改"导致视觉怪异）
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> buildDarkColors(seed)
        else -> buildLightColors(seed)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
