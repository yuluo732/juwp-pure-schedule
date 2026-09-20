package com.juwp.schedule.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juwp.schedule.BuildConfig
import com.juwp.schedule.R

/**
 * 「关于」二级页面。
 *
 * 为什么单独开一页而不是留在设置页里：设置页那一屏已经很长（账号 / 学期 / 开学日期 /
 * 课表更新 / 个性化 / 上课提醒 / 账号与数据），说明文字挤在末尾既不好读也不好找。
 *
 * ⚠️ 本页与主界面是 `if/else` **二选一**（见 MainActivity.MainScaffold），不是叠加 ——
 * 本项目所有页面容器都是透明的（要露出全局壁纸），叠加会让下层文字透出来。
 * 因此「划入动画」也只能是本页**自己**从右侧滑进来，而不是两页交叉滑动。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME

    // 系统返回键 / 手势返回：先关本页，而不是直接把 App 退出到桌面
    BackHandler(onBack = onBack)

    // 划入动画：1 = 完全在屏幕右侧之外，0 = 就位。
    // 用 graphicsLayer 的 translationX（里面能拿到 size.width，不必自己算屏宽）。
    val progress = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        progress.animateTo(0f, tween(durationMillis = SLIDE_IN_MS, easing = FastOutSlowInEasing))
    }

    Scaffold(
        // ⚠️ 透明容器 + 显式 contentColor：透明会让 contentColorFor 匹配失败、回退成黑字
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // 与设置页一样清零 —— inset 只由最外层 MainScaffold 处理，否则就是「大上巴」
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .graphicsLayer { translationX = progress.value * size.width },
        ) {
            AboutHeader(onBack = onBack)

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                AppIdentityCard(version = version)

                Spacer(Modifier.height(14.dp))

                SectionCard("说明") {
                    BodyText("本项目已在 GitHub 上开源，源码、使用说明与问题反馈都在仓库里。")
                    Spacer(Modifier.height(8.dp))
                    BodyText(
                        "数据来自学校教务系统，登录走学校统一身份认证（CAS + SSO）。" +
                            "本 App 没有服务器，账号与课表只保存在你自己手机里，" +
                            "不会上传到任何其他地方。"
                    )
                    Spacer(Modifier.height(8.dp))
                    BodyText(
                        "教务系统公网可直接访问，校外、假期都能正常同步；" +
                            "开着代理软件（如 Clash 的 TUN 模式）时可能连不上，关掉即可。"
                    )
                    Spacer(Modifier.height(8.dp))
                    BodyText("本 App 只适配江西水利电力大学的教务系统，其他学校的账号无法登录。")
                    Spacer(Modifier.height(8.dp))
                    BodyText(
                        "本项目为非官方工具，与江西水利电力大学无隶属关系。" +
                            "使用时请遵守学校相关规定。"
                    )
                }

                Spacer(Modifier.height(14.dp))

                // 链接只有一行，不再套「链接」标题卡片（用户要求去掉 title/subtitle）
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        LinkRow(
                            iconRes = R.drawable.ic_github_invertocat,
                            title = "GitHub 仓库",
                            onClick = { openUrl(context, REPO_URL) },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                SectionCard("开源协议") {
                    BodyText("MIT License © 2026 Pure Schedule contributors")
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ 标题

/**
 * 标题行：左侧返回箭头 + 「关于」。
 *
 * ⚠️ 纵向位置要和设置页的「设置」标题对齐。设置页那边是
 * `Text(titleLarge).padding(top = 10.dp, bottom = 16.dp)`，而它的状态栏 inset 由
 * **外层 MainScaffold 的 Scaffold** 提供；关于页是 `if/else` 里替换掉那个 Scaffold 的，
 * 拿不到外层 inset —— 实测标题会顶到 y=43（设置页在 y=168），整整高了 125px。
 * 所以这里自己加 [statusBarsPadding]，再把 top 调到 2dp：
 * `状态栏 + 2dp + 行高40dp/2 = 文字中心`，与设置页的 `状态栏 + 10dp + 14dp` 对齐。
 */
@Composable
private fun AboutHeader(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "关于",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ------------------------------------------------------------------ 应用信息

@Composable
private fun AppIdentityCard(version: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 应用图标：自适应图标的前景是 108dp 画布、图形只在中心约 62dp 的安全区内，
            // 所以这里给的图片尺寸要比白底方块大一圈，图形才正好填满
            Box(
                Modifier
                    .size(APP_ICON_TILE)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(APP_ICON_TILE * 1.72f),
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = "极简课程表",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Pure Schedule · v$version",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "极简课程表，仅适配江西水利电力大学，仅安卓。\n" +
                        "Pure Schedule, JUWP only, Android only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 链接行

/**
 * 带图标的一行链接：图标 + 标题 + 右侧「在外部打开」箭头。
 *
 * ⚠️ 图标不再套深色圆底（用户要求去掉那个黑环）。但 Invertocat 矢量图是**白色填充**，
 * 直接放在浅色卡片上会看不见 —— 所以改用 [Icon] 的 tint 染色：
 * Icon 会拿源图的 alpha 当遮罩、用 tint 重新上色，于是它自动跟随明暗模式。
 */
@Composable
private fun LinkRow(
    iconRes: Int,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(26.dp),
        )

        Spacer(Modifier.width(14.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )

        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "在浏览器中打开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ------------------------------------------------------------------ 工具

/**
 * 用系统浏览器打开链接。
 * 外面包 runCatching：设备上没有任何浏览器时 startActivity 会抛
 * ActivityNotFoundException，不能让它把整个页面带崩。
 */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 仓库地址（仓库已从 pure-schedule 改名为 juwp-pure-schedule） */
private const val REPO_URL = "https://github.com/yuluo732/juwp-pure-schedule"

/** 划入动画时长，与底部导航切页的 220ms 保持一致 */
private const val SLIDE_IN_MS = 220

/** 应用图标白底方块的边长 */
private val APP_ICON_TILE = 56.dp
