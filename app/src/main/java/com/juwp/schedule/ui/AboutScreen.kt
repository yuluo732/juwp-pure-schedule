package com.juwp.schedule.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * 拆出来之后设置页只留一行入口，说明与链接都能铺开写。
 *
 * 背景沿用全局壁纸（由 MainScaffold 的 WithBackgroundImage 提供），
 * 所以这里容器保持透明，卡片用与设置页相同的 80% 不透明表面色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME

    // 系统返回键 / 手势返回：先关本页，而不是直接把 App 退出到桌面
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = Color.Transparent,
        // ⚠️ 透明容器会让 contentColorFor 匹配失败、回退成黑色文字，必须显式给 contentColor
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "关于",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            AppIdentityCard(version = version)

            Spacer(Modifier.height(14.dp))

            SectionCard(
                title = "说明",
                subtitle = "数据从哪来、存在哪、什么情况下连不上",
            ) {
                BodyText(
                    "数据来自学校教务系统，登录走学校统一身份认证（CAS + SSO）。" +
                        "本 App 没有服务器，账号与课表只保存在你自己手机里，不会上传到任何其他地方。"
                )
                Spacer(Modifier.height(8.dp))
                BodyText(
                    "教务系统公网可直接访问，校外、假期都能正常同步；" +
                        "开着代理软件（如 Clash 的 TUN 模式）时可能连不上，关掉即可。"
                )
                Spacer(Modifier.height(8.dp))
                BodyText(
                    "本 App 只适配江西水利电力大学的教务系统，其他学校的账号无法登录。"
                )
            }

            Spacer(Modifier.height(14.dp))

            SectionCard(
                title = "链接",
                subtitle = "源码、使用说明与问题反馈都在仓库里",
            ) {
                LinkRow(
                    iconRes = R.drawable.ic_github_invertocat,
                    // GitHub 官方 Invertocat 是白色填充，必须配深色圆底才可见；
                    // #24292F 是 GitHub 自己的深色，浅色/深色模式下都能读出白色标志
                    iconTileColor = GITHUB_TILE,
                    title = "GitHub 仓库",
                    subtitle = REPO_LABEL,
                    onClick = { openUrl(context, REPO_URL) },
                )
            }

            Spacer(Modifier.height(14.dp))

            SectionCard(title = "开源许可") {
                BodyText("MIT License © 2026 Pure Schedule contributors")
                Spacer(Modifier.height(8.dp))
                BodyText(
                    "本项目为非官方工具，与江西水利电力大学无隶属关系。" +
                        "使用时请遵守学校相关规定。"
                )
            }

            Spacer(Modifier.height(24.dp))
        }
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
 * 带图标的一行链接：左侧圆形图标底 + 标题/副标题 + 右侧「在外部打开」箭头。
 * 样式对齐系统中「去 XX 下载」那类跳转项。
 */
@Composable
private fun LinkRow(
    iconRes: Int,
    iconTileColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconTileColor),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
private const val REPO_LABEL = "yuluo732/juwp-pure-schedule"

/** GitHub 深色：#24292F，与白色 Invertocat 搭配在明暗两种模式下都清晰 */
private val GITHUB_TILE = Color(0xFF24292F)

/** 应用图标白底方块的边长 */
private val APP_ICON_TILE = 56.dp
