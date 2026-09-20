package com.juwp.schedule

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juwp.schedule.ui.AboutScreen
import com.juwp.schedule.ui.LoginScreen
import com.juwp.schedule.ui.ScheduleScreen
import com.juwp.schedule.ui.ScheduleUiState
import com.juwp.schedule.ui.ScheduleViewModel
import com.juwp.schedule.ui.scheduleViewModelFactory
import com.juwp.schedule.ui.SettingsScreen
import com.juwp.schedule.ui.TimetablePillInfo
import com.juwp.schedule.ui.TodayScreen
import com.juwp.schedule.ui.theme.PureScheduleTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ SplashScreen 兼容库：接管系统闪屏（背景色已按深浅模式配置），消除白屏
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 冷启动同步读一次配置（单次小文件磁盘读，毫秒级）：
        // 首帧之前拿到最终外观（主题色/动态色/深浅模式/背景图），杜绝图3→图4 的颜色与布局闪变
        val app = application as ScheduleApp
        val splashPrefs = runBlocking { app.container.settings.readSplashPrefs() }

        setContent {
            // Android 13+ 启动时自动申请一次通知权限：
            // 没有它，上课提醒的闹钟即使被系统准时唤醒，通知也会被静默丢弃
            //（这正是「必须前台才提醒 / 后台不提醒」最常见的原因）
            RequestNotificationPermissionOnce()

            val viewModel: ScheduleViewModel = viewModel(
                factory = scheduleViewModelFactory(application, splashPrefs)
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            // 主题三态 + 自定义主题色 + 动态颜色都从 state 来，设置页一改全 App 立刻换肤
            PureScheduleTheme(
                mode = state.themeMode,
                seed = Color(state.themeColorArgb),
                dynamic = state.dynamicColor,
            ) {
                AppRoot(viewModel = viewModel, state = state)
            }
        }
    }
}

/** 底部导航的三个主页面 */
private enum class MainTab(val label: String, val icon: ImageVector) {
    Today("今日", Icons.Filled.Today),
    Timetable("周课表", Icons.Filled.CalendarMonth),
    Settings("设置", Icons.Filled.Settings),
}

/**
 * 启动时申请一次通知权限（仅 Android 13+）。
 * 拒绝也不打扰：设置页「开启上课提醒」处仍有手动授权入口，
 * 且提醒接收器在无权限时会安全跳过而不会崩溃。
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* 结果不重要：无权限时接收器会静默跳过 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * 应用根布局。
 *
 * 依然是手写的状态切换（不用 Navigation 组件）：
 *  - booting → 启动页：此时绝不出登录页，避免「记住密码却闪一下登录窗」的观感
 *    （启动期间可能在后台静默重登，见 ScheduleViewModel.bootstrap 的注释）
 *  - 未登录 → 登录页
 *  - 已登录 → 底部导航三页（今日 / 周课表 / 设置）
 */
@Composable
private fun AppRoot(viewModel: ScheduleViewModel, state: ScheduleUiState) {
    // 每次回到前台：跨天检测（重算日期/星期/周次）+ 按更新间隔决定是否静默同步
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onAppResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 「回到本周」悬浮条信息由周课表页上报，与轻提示统一在底部容器里排布
    var pillInfo by remember { mutableStateOf<TimetablePillInfo?>(null) }

    // 全局壁纸层提到 AppRoot：**启动页也要铺在壁纸上**。
    // 早前它在 MainScaffold 里面，于是启动时先是「启动页 + 黑底」、等壁纸解码完
    // 才变成「主界面 + 壁纸」—— 用户看到的就是「图二→图三」那一闪。
    // 提到这里之后，从第一帧起背景就一致，只会有一次「启动页 → 内容」的过渡。
    WithBackgroundImage(
        path = state.backgroundImagePath,
        version = state.backgroundImageVersion,
        highRes = state.backgroundHd,
    ) {
    Box(Modifier.fillMaxSize()) {
        when {
            state.booting -> BootSplash()

            !state.loggedIn -> LoginScreen(
                studentId = state.studentId,
                savedPassword = state.savedPassword,
                rememberPassword = state.rememberPassword,
                loggingIn = state.loggingIn,
                error = state.error,
                onLogin = { id, pwd, remember -> viewModel.login(id, pwd, remember) },
            )

            else -> MainScaffold(
                viewModel = viewModel,
                state = state,
                onTimetablePill = { pillInfo = it },
            )
        }

        // 登录过期：先友好提示，用户确认后才跳登录页（启动时不再突兀打断）
        if (state.sessionExpired) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissSessionExpired() },
                title = { Text("登录已过期") },
                text = { Text("为了继续同步最新课表，需要重新登录一次。现在仍可查看已缓存的课表。") },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmSessionExpired() }) { Text("重新登录") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissSessionExpired() }) { Text("稍后") }
                },
            )
        }

        // 出错弹窗（登录页 / 主界面通用）
        state.error?.let { message ->
            AlertDialog(
                onDismissRequest = { viewModel.consumeError() },
                title = { Text("出错了") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.retryAfterError() }) { Text("重试") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.consumeError() }) { Text("知道了") }
                },
            )
        }

        // 轻提示（全局仅此一处，避免重复渲染出两个 Toast）：
        // 位置随「回到本周」是否显示动态调整——
        //  · 情况 A（正在查看非本周、胶囊显示）：Toast 抬到胶囊正上方，绝不重叠
        //  · 情况 B（本周/无胶囊）：保持默认的屏幕中下方位置
        state.message?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // 126dp：与下方向上 84dp + 34dp 高的胶囊只留 ~8dp 缝隙，视觉上成组
                    .padding(bottom = if (pillInfo != null) 126.dp else 96.dp),
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(2000)
                viewModel.consumeMessage()
            }
        }

        // 「回到本周」胶囊：固定在【底部导航栏正上方】（系统导航条 inset + 84dp），
        // 完全可见不被遮挡；轻阴影形成清晰的视觉边界
        pillInfo?.let { info ->
            val maxPillWidth = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 84.dp)
                    .widthIn(max = maxPillWidth)
                    .clickable { info.onBackToCurrentWeek() },
            ) {
                Text(
                    text = "本周为第 ${info.currentWeek} 周 · 回到本周",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
    }
}

// ------------------------------------------------------------------ 主界面

@Composable
private fun MainScaffold(
    viewModel: ScheduleViewModel,
    state: ScheduleUiState,
    onTimetablePill: (TimetablePillInfo?) -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf(MainTab.Today.name) }
    val tab = MainTab.entries.firstOrNull { it.name == tabName } ?: MainTab.Today
    // 「关于」二级页面。用手写的布尔量而不是导航库：本项目刻意不引入 Navigation（见 AGENTS.md）。
    // rememberSaveable：进程被回收再回来时不会莫名丢掉这一页
    var showAbout by rememberSaveable { mutableStateOf(false) }

    // 全局壁纸层已提到 AppRoot（见那里的注释）。
    //
    // ⚠️ 关于页与主界面之间必须用「**推入 / 推出**」，不能用「叠一层滑入」。
    //    本项目页面容器都是透明的（要露出全局壁纸，与其他页面一致），
    //    如果两页在过渡期间**重叠**，下层设置页的文字会从上层半透明卡片后面透出来
    //    （实测截图里能读到「清空所有数据」「退出登录」，像渲染坏了）。
    //    「推入」时两页 x 方向始终**相邻、不重叠**（一页从 0 滑到 -w，另一页从 +w 滑到 0），
    //    所以既能拿到「设置被向左推出、关于从右推入」的联动动效，又不会互相透。
    //    返回时方向取反，自然就有滑出动画。
    //    刻意不加 fade：淡入淡出会让两页短暂半透明，反而破坏「不重叠」这个前提。
    // 关于页的「推入 / 推出」用手写两层实现，**不用 AnimatedContent**：
    //  1) AnimatedContent 会在过渡结束后销毁离场页 —— 设置页的滚动位置随之丢失
    //     （用户反馈：从关于返回后设置页跳回顶部）；
    //  2) 两层始终相邻、不重叠，所以既能看到「设置被向左推出、关于从右推入」
    //     的联动动效，又不会出现「下层文字从上层半透明卡片后面透出来」（实测踩过）。
    val aboutSlide = remember { Animatable(if (showAbout) 1f else 0f) }
    LaunchedEffect(showAbout) {
        aboutSlide.animateTo(
            targetValue = if (showAbout) 1f else 0f,
            animationSpec = tween(SLIDE_DURATION_MS),
        )
    }

    Box(Modifier.fillMaxSize()) {
        // ---- 图层 1：主界面（设置页在其中）。常驻不销毁，滚动位置原样保留。
        //     关于页打开时它平移到屏幕左侧之外。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = -aboutSlide.value * size.width },
        ) {
        Scaffold(
            containerColor = Color.Transparent,
            // ⚠️ 透明容器会让 contentColorFor 匹配失败、回退到 Compose 默认的黑色文字，
            // 必须显式指定 contentColor，深色模式下未显式着色的文字才是白色
            contentColor = MaterialTheme.colorScheme.onSurface,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    tonalElevation = 3.dp,
                ) {
                    MainTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == tab,
                            onClick = {
                                tabName = item.name
                                // 「回到本周」气泡是周课表页**上报**给根布局的。
                                // 切页时周课表要等 AnimatedContent 的退场动画跑完才 dispose，
                                // 它的 onDispose 才把气泡清掉 —— 表现就是「页面已经切完了，
                                // 气泡还挂一会儿」（用户反馈）。所以在点击的这一刻就清掉。
                                if (item != MainTab.Timetable) onTimetablePill(null)
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                // 切换底部导航时页面横向滑入滑出。
                // 方向按两个 tab 的序号大小决定：往右的标签从右边进、往左的从左边进，
                // 这样「手指往哪边点，页面就往哪边推」符合直觉。
                // 用 AnimatedContent 而不是自己写动画：它会按 targetState 保存/恢复各页的
                // rememberSaveable 状态（周课表看到第几周、设置页滚动位置），切回来不丢。
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val dir = if (forward) 1 else -1
                        (
                            slideInHorizontally(
                                animationSpec = tween(SLIDE_DURATION_MS),
                            ) { width -> dir * width } + fadeIn(tween(SLIDE_DURATION_MS))
                            togetherWith
                            slideOutHorizontally(
                                animationSpec = tween(SLIDE_DURATION_MS),
                            ) { width -> -dir * width } + fadeOut(tween(SLIDE_DURATION_MS))
                            )
                    },
                    label = "mainTab",
                ) { current ->
                    when (current) {
                        MainTab.Today -> TodayScreen(
                            state = state,
                            onRefresh = { viewModel.sync() },
                            onOpenTimetable = { tabName = MainTab.Timetable.name },
                        )

                        MainTab.Timetable -> ScheduleScreen(
                            state = state,
                            onRefresh = { viewModel.sync() },
                            onTimetablePill = onTimetablePill,
                        )

                        MainTab.Settings -> SettingsScreen(
                            state = state,
                            onSwitchTerm = { viewModel.switchTerm(it) },
                            onSetAutoSelectTerm = { viewModel.setAutoSelectTerm(it) },
                            onSetTermStart = { viewModel.setTermStart(it) },
                            onSetReminderEnabled = { viewModel.setReminderEnabled(it) },
                            onSetReminderLead = { viewModel.setReminderLeadMinutes(it) },
                            onSetSyncInterval = { viewModel.setSyncIntervalHours(it) },
                            onSetThemeMode = { viewModel.setThemeMode(it) },
                            onSetThemeColor = { viewModel.setThemeColor(it) },
                            onSetDynamicColor = { viewModel.setDynamicColor(it) },
                            onSetBackgroundHd = { viewModel.setBackgroundHd(it) },
                            onSetCardTransparent = { viewModel.setCardTransparent(it) },
                            onTestReminder = { viewModel.testReminder() },
                            onPickBackground = { uri ->
                                if (uri != null) viewModel.setBackgroundImage(uri)
                            },
                            onClearBackground = { viewModel.clearBackgroundImage() },
                            onLogout = { viewModel.logout() },
                            onClearAll = { viewModel.clearAll() },
                            onOpenAbout = { showAbout = true },
                            onSwipeToTimetable = { tabName = MainTab.Timetable.name },
                        )
                    }
                }
            }
        }
        }   // ← 结束图层 1（主界面 / 设置页）

        // ---- 图层 2：关于页。完全滑出（slide == 0）后就不再组合它，
        //      它本身没有需要保留的状态，销毁无害。
        if (aboutSlide.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = (1f - aboutSlide.value) * size.width },
            ) {
                AboutScreen(onBack = { showAbout = false })
            }
        }
    }   // ← 结束 Box（两个图层）
}

/**
 * 页面切换滑动时长。
 * 220ms 是「能看清方向、又不拖沓」的取值：再长（300ms+）连点两次导航会排队等待，
 * 手感发黏；再短（150ms-）就只剩闪一下，看不出滑动方向。
 */
private const val SLIDE_DURATION_MS = 220

// ------------------------------------------------------------------ 背景图层

/**
 * 全局背景图层：有自定义背景图时铺在最底层，上面盖一层半透明遮罩，
 * 壁纸隐约可见但不喧宾夺主；没有背景图时就是普通的主题底色。
 * 遮罩用黑色：深色模式 45%、浅色模式 30%，卡片本身再带半透明表面色。
 */
@Composable
private fun WithBackgroundImage(
    path: String,
    version: Int,
    highRes: Boolean,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val bitmap = rememberDecodedBackground(path = path, version = version, highRes = highRes)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (dark) 0.45f else 0.30f))
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }
        content()
    }
}

/**
 * 从内部存储解码背景图。
 * - 普通模式：按最长边 ~1600px 采样，省内存
 * - 高清模式（用户开关）：不采样，按原图分辨率解码
 * 以 (path, version, highRes) 为 key：换图/切高清都会重新解码（修复「换图不生效」）。
 */
@Composable
private fun rememberDecodedBackground(path: String, version: Int, highRes: Boolean): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, path, version, highRes) {
        if (path.isBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                if (!highRes) {
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) {
                        sample *= 2
                    }
                }
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }.value

// ------------------------------------------------------------------ 启动页

@Composable
private fun BootSplash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.School,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
}
