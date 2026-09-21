package com.juwp.schedule.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.juwp.schedule.ScheduleApp
import com.juwp.schedule.data.model.SemesterSchedule
import com.juwp.schedule.data.model.TermOption
import com.juwp.schedule.data.net.LoginResult
import com.juwp.schedule.data.prefs.SplashPrefs
import com.juwp.schedule.data.repo.SyncResult
import com.juwp.schedule.domain.WeekCalculator
import com.juwp.schedule.sync.ScheduleSyncWorker
import com.juwp.schedule.ui.theme.AppThemeMode
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** 全应用 UI 状态 */
data class ScheduleUiState(
    val booting: Boolean = true,
    val loggedIn: Boolean = false,
    /** 登录凭证过期：界面弹友好提示，用户确认后才跳登录页（不突兀打断） */
    val sessionExpired: Boolean = false,
    val studentId: String = "",
    val savedPassword: String = "",
    val rememberPassword: Boolean = true,

    val schedule: SemesterSchedule? = null,
    val selectedTermId: String = "",
    /**
     * 可选学期列表（设置页下拉的数据源）。
     *
     * ⚠️ 为什么单独放在 UiState、而不是从 `schedule.availableTerms` 取：
     * 它是**跨学期**的元数据 —— 切学期时 `schedule` 会被置空（见 [sync] 的 switching 分支），
     * 而学期列表**不该跟着消失**。早期实现直接从 `schedule` 里读，于是切学期过程中
     * 设置页会瞬间弹出「还没有获取到学期列表，请先在课表页下拉刷新一次」，
     * 同步完成后才恢复 —— 用户看到的「图三闪现」就是这个。
     * 提到这里之后，切学期全程列表都在。
     */
    val availableTerms: List<TermOption> = emptyList(),
    /** 今天：与 todayWeekday/currentWeek 在 recomputeWeek 里用同一个 LocalDate.now() 一起算 */
    val today: LocalDate = LocalDate.now(),
    val currentWeek: Int = 1,
    /**
     * ⚠️ 默认值必须是**当天的真实星期**，不能写 1（周一）。
     * 写 1 会让所有「还没算过周次」的中间态都显示成周一，
     * 启动时表现就是「今天明明是周日，标题却先写周一、随后才跳回来」。
     */
    val todayWeekday: Int = WeekCalculator.weekdayOf(LocalDate.now()),
    val termStartMillis: Long = 0L,
    val fromCache: Boolean = false,
    val lastSyncAt: Long = 0L,

    val loggingIn: Boolean = false,
    val syncing: Boolean = false,
    /**
     * 首次拉取中：已登录但还没有任何课表数据，此刻正在后台抓取。
     * 用来避免启动时先闪一下「还没有课表数据」空态卡片（用户反馈的「图二闪烁」）。
     * 只有拿到明确结果（成功/失败/需重登）才会置回 false。
     */
    val firstLoad: Boolean = false,
    val error: String? = null,
    val message: String? = null,

    val reminderEnabled: Boolean = false,
    val reminderLeadMinutes: Int = 15,

    // ---- 外观（深浅模式 / 主题色 / 背景图 / 动态颜色） ----
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val themeColorArgb: Long = 0xFF2B5CE6,
    val dynamicColor: Boolean = false,
    val backgroundImagePath: String = "",
    /** 背景图版本号：每次更换/清除/切换高清时 +1，驱动重新解码（同路径替换不会触发路径 key） */
    val backgroundImageVersion: Int = 0,
    /** 高清背景图：按原图分辨率解码 */
    val backgroundHd: Boolean = false,
    /** 课程卡片透明化：周课表卡片半透明，露出自定义背景图 */
    val cardTransparent: Boolean = false,

    // ---- 课表自动更新间隔（小时；0 = 仅手动） ----
    val syncIntervalHours: Int = 6,

    /**
     * 自动选择学期（默认开启）：按今天日期自动切到对应学期，
     * 免去每学期手动设置。关掉后始终使用用户手选的学期。
     */
    val autoSelectTerm: Boolean = true,
)

/**
 * 主 ViewModel：负责登录、同步、周次计算、外观设置与更新策略。
 *
 * @param initial 冷启动时由 MainActivity 同步读出的用户配置——
 *        初始状态直接带「最终外观」，用户看到的第一个界面就是正确配色，无闪变
 */
class ScheduleViewModel(app: Application, initial: SplashPrefs? = null) : AndroidViewModel(app) {

    private val container = (app as ScheduleApp).container
    private val repo = container.repository
    private val settings = container.settings

    private val _state = MutableStateFlow(
        if (initial != null) {
            // ⚠️ 首帧之前就把「今天 / 星期 / 第几周」算准。
            //    这几个字段的默认值是 1（周一、第 1 教学周），若留给 bootstrap 之后的
            //    recomputeWeek 去纠正，主界面会先用错值渲染一帧再跳变 ——
            //    实测就是启动时「9月20日 周一 / 第 1 教学周」一闪而过。
            val today = LocalDate.now()
            val weekday = WeekCalculator.weekdayOf(today)
            val week = if (initial.termStartMillis > 0) {
                WeekCalculator.weekOf(
                    today,
                    LocalDate.ofEpochDay(initial.termStartMillis / 86_400_000L),
                )
            } else {
                1
            }
            ScheduleUiState(
                // ⚠️ 必须是 true：课表还没从缓存读出来之前**不能**撤掉启动页，
                //    否则会先闪一帧「还没有课表数据」空态卡片（实测截图里的图二/图三）。
                //    bootstrap() 会在拿到缓存（或明确判定没数据）时才把它置回 false。
                booting = true,
                loggedIn = initial.loggedIn,
                themeMode = AppThemeMode.fromRaw(initial.themeMode),
                themeColorArgb = initial.themeColorArgb,
                dynamicColor = initial.dynamicColor,
                backgroundImagePath = initial.backgroundImagePath,
                backgroundHd = initial.backgroundHd,
                termStartMillis = initial.termStartMillis,
                selectedTermId = initial.currentTermId,
                lastSyncAt = initial.lastSyncAt,
                today = today,
                todayWeekday = weekday,
                // weekOf 返回可空（算不出周次时给 null），这里兜底成第 1 周
                currentWeek = (week ?: 1).coerceAtLeast(1),
            )
        } else {
            val today = LocalDate.now()
            ScheduleUiState(
                today = today,
                todayWeekday = WeekCalculator.weekdayOf(today),
            )
        }
    )
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    private var autoSyncJob: Job? = null

    init {
        viewModelScope.launch {
            settings.studentId.collect { id -> _state.update { it.copy(studentId = id) } }
        }
        viewModelScope.launch {
            settings.savedPassword.collect { pwd -> _state.update { it.copy(savedPassword = pwd) } }
        }
        viewModelScope.launch {
            settings.rememberPassword.collect { r -> _state.update { it.copy(rememberPassword = r) } }
        }
        viewModelScope.launch {
            settings.termStartMillis.collect { m -> _state.update { it.copy(termStartMillis = m) } }
        }
        viewModelScope.launch {
            settings.reminderEnabled.collect { e -> _state.update { it.copy(reminderEnabled = e) } }
        }
        viewModelScope.launch {
            settings.reminderLeadMinutes.collect { m -> _state.update { it.copy(reminderLeadMinutes = m) } }
        }
        viewModelScope.launch {
            settings.lastSyncAt.collect { t -> _state.update { it.copy(lastSyncAt = t) } }
        }
        viewModelScope.launch {
            // DataStore 在「写入相同值」时也会重发 → 每次同步落库后都会触发一次回读缓存，
            // 把刚同步完的 fromCache=false 又盖回 true（表现为同步成功仍显示「离线缓存」）。
            // 这里用 lastTermSeen 去重：只有学期号真的变化才回读缓存。
            var lastTermSeen: String? = null
            settings.currentTermId.collect { id ->
                _state.update { it.copy(selectedTermId = id) }
                if (id.isNotBlank() && id != lastTermSeen) {
                    lastTermSeen = id
                    loadCached(id)
                }
            }
        }
        viewModelScope.launch {
            settings.themeMode.collect { raw ->
                _state.update { it.copy(themeMode = AppThemeMode.fromRaw(raw)) }
            }
        }
        viewModelScope.launch {
            settings.themeColorSeed.collect { argb ->
                _state.update { it.copy(themeColorArgb = argb) }
            }
        }
        viewModelScope.launch {
            settings.dynamicColor.collect { on ->
                _state.update { it.copy(dynamicColor = on) }
            }
        }
        viewModelScope.launch {
            settings.backgroundImage.collect { path ->
                _state.update { it.copy(backgroundImagePath = path) }
            }
        }
        viewModelScope.launch {
            settings.backgroundHd.collect { hd ->
                _state.update { it.copy(backgroundHd = hd) }
            }
        }
        viewModelScope.launch {
            settings.cardTransparent.collect { t ->
                _state.update { it.copy(cardTransparent = t) }
            }
        }
        viewModelScope.launch {
            settings.syncIntervalHours.collect { h ->
                _state.update { it.copy(syncIntervalHours = h) }
            }
        }
        viewModelScope.launch {
            settings.autoSelectTerm.collect { v ->
                _state.update { it.copy(autoSelectTerm = v) }
            }
        }

        viewModelScope.launch { bootstrap() }

        // 跨零点定时器：进程存活时，每天 00:00 过后自动重算日期/星期/周次并重排提醒，
        // 用户不用手动点刷新。App 被杀掉的场景由 onAppResumed 的跨天检测兜底。
        viewModelScope.launch {
            while (isActive) {
                val now = LocalDateTime.now()
                val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
                val delayMs = java.time.Duration.between(now, nextMidnight).toMillis() + 1_500
                delay(delayMs)
                recomputeWeek()
                container.reminderScheduler.reschedule()
            }
        }
    }

    /**
     * 冷启动流程（消除「登录页闪一下」的关键就在这里）：
     *  1. 有课表缓存 → 直接进主界面（离线优先），登录态照常展示，随后按需静默同步
     *  2. 有登录标记但无缓存 → 直接进主界面，后台同步抓课表
     *  3. 无缓存也无登录标记 → 若「记住密码」里有凭据，先静默重登一次（此期间
     *     booting 保持 true，界面停在启动页，绝不闪登录页）；重登失败才落登录页
     *  4. 什么都没有 → 登录页
     */
    private suspend fun bootstrap() {
        val loggedInFlag = runCatching { settings.loggedIn.first() }.getOrDefault(false)
        val savedTermId = runCatching { settings.currentTermId.first() }.getOrDefault("")

        // ⚠️ 开着「自动选择学期」时，**打开 App 就要切到当前学期** ——
        //    这正是那个开关的文案承诺（「已开启：打开 App 自动切到当前学期」）。
        //    原实现只在同步时用推断结果，而同步被「自动更新间隔」节流（默认 6 小时），
        //    于是用户手动切过学期之后、间隔到期之前，App 会一直停在手动选的那个学期 ——
        //    实测复现：12:30 打开 App，仍显示 09:44 手选的 2024-2025-2，
        //    而当天应匹配 2026-2027-1。用户反馈的「自动选择学期失效」就是这个。
        val autoTermId = runCatching {
            if (settings.autoSelectTerm.first()) repo.resolveAutoTerm() else null
        }.getOrNull()
        val termId = autoTermId ?: savedTermId
        if (autoTermId != null && autoTermId != savedTermId) {
            // 写回 DataStore 而不是只改 UiState：下面 collect(currentTermId) 就是靠它驱动
            // 「当前学期」的显示与缓存加载，两个地方必须一致（否则又是一处两个真相源）
            runCatching { settings.setCurrentTermId(autoTermId) }
        }

        val cached = runCatching { repo.loadFromCache(termId.ifBlank { null }) }.getOrNull()

        if (cached != null) {
            _state.update {
                it.copy(
                    booting = false,
                    loggedIn = loggedInFlag,
                    schedule = cached,
                    fromCache = true,
                    // 冷启动就把学期列表填上，避免进设置页瞬间显示「还没获取到学期列表」
                    availableTerms = cached.availableTerms.takeIf { t -> t.isNotEmpty() }
                        ?: it.availableTerms,
                )
            }
            recomputeWeek()
            if (loggedInFlag) maybeAutoSync()
        } else if (loggedInFlag) {
            // 有登录标记但还没缓存：置 firstLoad，界面显示转圈而不是空态卡片，
            // 否则会先闪一下「还没有课表数据」（用户反馈的图二）
            _state.update { it.copy(booting = false, loggedIn = true, firstLoad = true) }
            autoSync(showError = true)
        } else {
            val id = runCatching { settings.studentId.first() }.getOrDefault("")
            val pwd = runCatching { settings.savedPassword.first() }.getOrDefault("")
            val willTryRelogin = id.isNotBlank() && pwd.isNotBlank()
            if (willTryRelogin) {
                _state.update { it.copy(firstLoad = true) }
            }
            val ok = willTryRelogin && runCatching { silentRelogin() }.getOrDefault(false)
            _state.update { it.copy(booting = false, loggedIn = ok) }
            if (ok) autoSync() else _state.update { it.copy(firstLoad = false) }
        }
    }

    // ------------------------------------------------------------ 登录

    fun login(studentId: String, password: String, remember: Boolean) {
        if (studentId.isBlank()) {
            _state.update { it.copy(error = "请输入学号") }
            return
        }
        if (password.isBlank()) {
            _state.update { it.copy(error = "请输入密码") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loggingIn = true, error = null) }
            when (val result = repo.login(studentId.trim(), password)) {
                is LoginResult.Success -> {
                    settings.saveCredentials(studentId.trim(), password, remember)
                    settings.setLoggedIn(true)
                    _state.update {
                        it.copy(loggingIn = false, loggedIn = true, studentId = studentId.trim())
                    }
                    // 记住密码后，后台周期同步也排上；并立刻抓一次课表
                    ScheduleSyncWorker.reschedule(getApplication(), _state.value.syncIntervalHours)
                    sync()
                }

                is LoginResult.BadCredentials -> {
                    _state.update {
                        it.copy(
                            loggingIn = false,
                            error = "学号或密码错误。请先在浏览器打开 https://jiaowu.juwp.edu.cn:81/sso.jsp 确认能登录成功。",
                        )
                    }
                }

                is LoginResult.Failure -> {
                    _state.update { it.copy(loggingIn = false, error = result.message) }
                }
            }
        }
    }

    /**
     * 用「记住的密码」静默重登。成功返回 true 并恢复登录标记。
     * 这是「免重复登录」的核心：Cookie 会话过期时用户完全无感。
     */
    private suspend fun silentRelogin(): Boolean {
        val id = runCatching { settings.studentId.first() }.getOrDefault("")
        val pwd = runCatching { settings.savedPassword.first() }.getOrDefault("")
        if (id.isBlank() || pwd.isBlank()) return false
        val ok = runCatching { repo.login(id, pwd) }.getOrNull() is LoginResult.Success
        if (ok) settings.setLoggedIn(true)
        return ok
    }

    // ------------------------------------------------------------ 同步

    /**
     * 更新策略：
     *  - 手动刷新（下拉 / 今日页按钮）：立即同步，失败弹提示，无网回落缓存
     *  - 自动同步（启动 / 回前台 / 后台周期）：只在「距上次同步超过间隔」或无数据时进行，
     *    全程静默；会话失效先用记住的密码静默重登一次，重登也失败才真正要求重新登录
     *  - 课表数据永远以 Room 本地库为唯一展示来源（离线可看），网络只是更新手段
     */
    fun maybeAutoSync() {
        val s = _state.value
        if (!s.loggedIn || s.booting || s.syncing) return
        val stale = s.syncIntervalHours > 0 &&
            System.currentTimeMillis() - s.lastSyncAt > s.syncIntervalHours * 3_600_000L
        if (s.schedule == null || stale) autoSync()
    }

    /**
     * 自动同步（启动 / 回前台 / 后台周期触发）——**全程静默，绝不把用户踢到登录页**。
     *
     * 会话失效时的处理链：
     *  1. 用记住的密码静默重登一次，成功后重试同步（自愈，用户无感）；
     *  2. 重登仍失败 → 本次更新静默放弃：保留登录态与本地缓存，只写一行日志；
     *     不做任何界面跳转，也不弹对话框（自动更新的失败不该打断用户）。
     *     下一次自动更新 / 用户手动刷新时会再次尝试，重登成功即自动恢复。
     *
     * ⚠️ 历史教训：曾在这里直接 `loggedIn = false` 并落盘，导致「开了自动更新后
     * 一打开 App 就弹登录页」——那等于用「逼用户重新登录」来充当自动更新，是错的。
     */
    private fun autoSync(showError: Boolean = false) {
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            var result = repo.syncSchedule()
            // 会话失效：先用记住的密码静默重登一次再重试（这是唯一的自动恢复手段）
            if (result is SyncResult.NeedLogin && silentRelogin()) {
                result = repo.syncSchedule()
            }
            when (result) {
                is SyncResult.Success -> {
                    _state.update {
                        it.copy(
                            schedule = result.schedule,
                            fromCache = result.fromCache,
                            error = null,
                            firstLoad = false,
                            availableTerms = result.schedule.availableTerms.takeIf { t -> t.isNotEmpty() }
                                ?: it.availableTerms,
                        )
                    }
                    recomputeWeek()
                    container.reminderScheduler.reschedule()
                }

                SyncResult.NeedLogin -> {
                    // 拿不到课表且必须重登：结束首次加载态，让界面回到可交互状态
                    _state.update { it.copy(firstLoad = false) }
                    if (showError) {
                        // 只有「用户主动触发且确实无法继续」的场景才提示，
                        // 且提示后要用户点「重新登录」才跳转（见 AppRoot 的对话框）
                        _state.update {
                            it.copy(
                                sessionExpired = true,
                                message = "登录已过期，请重新登录",
                            )
                        }
                    } else {
                        Log.i(TAG, "自动同步遇会话失效且静默重登失败：静默跳过，保留缓存，下次自动重试")
                    }
                }

                is SyncResult.Failure -> {
                    _state.update { it.copy(firstLoad = false) }
                    if (showError) _state.update { it.copy(error = result.message) }
                    else Log.i(TAG, "自动同步失败（静默）：${result.message}")
                }
            }
        }
    }

    /** 手动同步（下拉刷新 / 按钮） */
    fun sync(termId: String? = null) {
        viewModelScope.launch {
            // 切到另一个学期时，先把当前显示清掉：否则同步期间仍显示上一个学期的课表，
            // 用户会以为「切了学期但课表没变」。
            val switching = termId != null && termId != _state.value.schedule?.termId
            _state.update {
                it.copy(
                    syncing = true,
                    error = null,
                    selectedTermId = termId ?: it.selectedTermId,
                    schedule = if (switching) null else it.schedule,
                    firstLoad = if (switching) true else it.firstLoad,
                )
            }
            var result = repo.syncSchedule(termId)
            if (result is SyncResult.NeedLogin && silentRelogin()) {
                result = repo.syncSchedule(termId)
            }
            when (result) {
                is SyncResult.Success -> {
                    _state.update {
                        it.copy(
                            syncing = false,
                            schedule = result.schedule,
                            fromCache = result.fromCache,
                            firstLoad = false,
                            // 服务端每次都带回完整学期列表；万一这次是空的（极少），
                            // 保留上一次的，别把下拉框弄没了
                            availableTerms = result.schedule.availableTerms.takeIf { t -> t.isNotEmpty() }
                                ?: it.availableTerms,
                            message = if (result.fromCache) "网络不可用，显示的是本地缓存" else "同步完成",
                        )
                    }
                    recomputeWeek()
                    container.reminderScheduler.reschedule()
                }

                SyncResult.NeedLogin -> {
                    _state.update {
                        it.copy(
                            syncing = false,
                            firstLoad = false,
                            sessionExpired = true,
                            message = "登录已过期，请重新登录",
                        )
                    }
                }

                is SyncResult.Failure -> {
                    _state.update { it.copy(syncing = false, firstLoad = false, error = result.message) }
                }
            }
        }
    }

    /** 切换学期 */
    fun switchTerm(termId: String) {
        if (termId.isBlank()) return
        _state.update { it.copy(selectedTermId = termId) }
        viewModelScope.launch { settings.setCurrentTermId(termId) }
        sync(termId)
    }

    private suspend fun loadCached(termId: String) {
        val cached = runCatching { repo.loadFromCache(termId) }.getOrNull()
        if (cached == null) {
            // ⚠️ 该学期还没有缓存：**必须清掉当前显示的课表**。
            // 早期实现这里是 `?: return`，直接保留上一个学期的课表，
            // 于是「设置里显示 A 学期、课表页显示 B 学期的课」——严重的显示错乱。
            // 置空后会走 firstLoad/空态分支，由随后的 sync() 填回来。
            _state.update {
                it.copy(schedule = null, fromCache = false, selectedTermId = termId, firstLoad = true)
            }
            return
        }
        _state.update { it.copy(schedule = cached, fromCache = true, selectedTermId = termId, firstLoad = false) }
        // 学期列表随缓存一起刷新；缓存里没有（老数据）就保留现有的，绝不置空
        cached.availableTerms.takeIf { it.isNotEmpty() }?.let { terms ->
            _state.update { it.copy(availableTerms = terms) }
        }
        recomputeWeek()
    }

    // ------------------------------------------------------------ 周次

    /**
     * 重新计算「今天 / 今天星期几 / 当前第几周」。
     * ⚠️ 三者绑定在同一个 LocalDate.now() 上一起算，杜绝「日期变了星期没变」的漂移。
     * 触发时机：冷启动、同步成功、开学日期变更、跨零点定时器、回到前台检测到跨天。
     */
    fun recomputeWeek() {
        val today = LocalDate.now()
        val weekday = WeekCalculator.weekdayOf(today)
        val termStart = if (_state.value.termStartMillis > 0) {
            LocalDate.ofEpochDay(_state.value.termStartMillis / 86_400_000L)
        } else {
            null
        }
        val computed = termStart?.let { WeekCalculator.weekOf(today, it) } ?: 1
        val max = _state.value.schedule?.maxWeek?.coerceAtLeast(1) ?: 1
        _state.update {
            it.copy(
                today = today,
                todayWeekday = weekday,
                currentWeek = computed.coerceIn(1, max),
            )
        }
    }

    /**
     * 回到前台：先看是否跨天（跨天就重算日期/星期/周次并重排提醒），
     * 再按「课表更新间隔」决定要不要静默同步。
     */
    /**
     * 回到前台：跨天就重算日期/星期/周次；然后【无条件重排提醒】——
     * 补偿系统清理掉的闹钟（国产 ROM/重启后恢复，属于「后台被杀补偿机制」），
     * 排程本身是「先取消后注册」，不会产生重复通知。
     */
    fun onAppResumed() {
        if (_state.value.today != LocalDate.now()) {
            recomputeWeek()
        }
        viewModelScope.launch { container.reminderScheduler.reschedule() }
        maybeAutoSync()
    }

    fun setTermStart(date: LocalDate) {
        val monday = date.with(DayOfWeek.MONDAY)
        val millis = monday.toEpochDay() * 86_400_000L
        viewModelScope.launch {
            settings.setTermStart(millis)
            _state.update { it.copy(termStartMillis = millis) }
            recomputeWeek()
            container.reminderScheduler.reschedule()
        }
    }

    // ------------------------------------------------------------ 外观（模式 / 主题色 / 背景图）

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            settings.setThemeMode(mode.name)
            _state.update { it.copy(themeMode = mode) }
        }
    }

    fun setThemeColor(argb: Long) {
        viewModelScope.launch {
            settings.setThemeColorSeed(argb)
            _state.update { it.copy(themeColorArgb = argb) }
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            settings.setDynamicColor(enabled)
            _state.update { it.copy(dynamicColor = enabled) }
        }
    }

    /**
     * 设置背景图：把相册返回的 Uri 拷贝进 app 私有目录再使用。
     * 为什么不直接存 Uri：相册 Uri 的读权限是临时的，重启后就失效；
     * 拷贝一份进 filesDir 才能长期作为背景。
     */
    fun setBackgroundImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val dst = File(getApplication<Application>().filesDir, "background_image")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("无法读取所选图片")
                settings.setBackgroundImagePath(dst.absolutePath)
                dst.absolutePath
            }.onSuccess { path ->
                // 版本号 +1：路径不变也要让 UI 重新解码（修复「换图不生效」）
                _state.update {
                    it.copy(
                        backgroundImagePath = path,
                        backgroundImageVersion = it.backgroundImageVersion + 1,
                        message = "背景已更新",
                    )
                }
            }.onFailure { e ->
                Log.w(TAG, "保存背景图失败", e)
                _state.update { it.copy(error = "设置背景失败：${e.message}") }
            }
        }
    }

    fun clearBackgroundImage() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val dst = File(getApplication<Application>().filesDir, "background_image")
                if (dst.exists()) dst.delete()
                settings.setBackgroundImagePath("")
            }
            _state.update {
                it.copy(
                    backgroundImagePath = "",
                    backgroundImageVersion = it.backgroundImageVersion + 1,
                    message = "已恢复默认背景",
                )
            }
        }
    }

    /** 切换高清解码后同样要重新解码一次 */
    fun setBackgroundHd(enabled: Boolean) {
        viewModelScope.launch {
            settings.setBackgroundHd(enabled)
            _state.update {
                it.copy(
                    backgroundHd = enabled,
                    backgroundImageVersion = it.backgroundImageVersion + 1,
                )
            }
        }
    }

    // ------------------------------------------------------------ 提醒自检

    /**
     * 「提醒自检」：排一条 60 秒后的测试通知，让用户当场验证后台提醒能不能响。
     * 不依赖课表缓存，也不受「是否已排过课表提醒」影响。
     */
    fun testReminder() {
        container.reminderScheduler.scheduleTestReminder(60)
        _state.update { it.copy(message = "60 秒后会弹一条测试通知，请锁屏等待") }
    }

    /** 课程卡片透明化（周课表卡片半透明，露出背景图） */    fun setCardTransparent(enabled: Boolean) {
        viewModelScope.launch {
            settings.setCardTransparent(enabled)
            _state.update { it.copy(cardTransparent = enabled) }
        }
    }

    // ------------------------------------------------------------ 登录过期

    /** 用户确认「重新登录」：此时才真正跳转到登录页 */
    fun confirmSessionExpired() {
        _state.update { it.copy(sessionExpired = false, loggedIn = false, error = null) }
    }

    /** 用户选择「稍后」：留在当前界面继续看缓存，不做任何跳转 */
    fun dismissSessionExpired() {
        _state.update { it.copy(sessionExpired = false) }
    }

    // ------------------------------------------------------------ 更新间隔

    fun setSyncIntervalHours(hours: Int) {
        viewModelScope.launch {
            settings.setSyncIntervalHours(hours)
            _state.update { it.copy(syncIntervalHours = hours) }
            // 后台周期任务同步调整；间隔为 0 表示只在前台按需同步 + 手动刷新
            ScheduleSyncWorker.reschedule(getApplication(), hours)
            if (hours > 0) maybeAutoSync()
        }
    }

    // ------------------------------------------------------------ 自动选学期

    /**
     * 开关「自动选择学期」。
     * 打开时立刻按今天日期重新推断一次并同步，让用户马上看到效果；
     * 关闭时保留当前学期（用户手选的结果）。
     */
    fun setAutoSelectTerm(enabled: Boolean) {
        viewModelScope.launch {
            settings.setAutoSelectTerm(enabled)
            _state.update { it.copy(autoSelectTerm = enabled) }
            if (enabled) {
                // 传 null 让 repository 走自动推断分支
                sync()
                _state.update { it.copy(message = "已开启，正在按当前日期匹配学期") }
            } else {
                _state.update { it.copy(message = "已关闭，将始终使用你选择的学期") }
            }
        }
    }

    // ------------------------------------------------------------ 提醒设置

    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setReminderEnabled(enabled)
            _state.update { it.copy(reminderEnabled = enabled) }
            if (enabled) container.reminderScheduler.reschedule() else container.reminderScheduler.cancelAll()
        }
    }

    fun setReminderLeadMinutes(minutes: Int) {
        viewModelScope.launch {
            settings.setReminderLeadMinutes(minutes)
            _state.update { it.copy(reminderLeadMinutes = minutes) }
            container.reminderScheduler.reschedule()
        }
    }

    // ------------------------------------------------------------ 退出

    fun logout() {
        viewModelScope.launch {
            container.reminderScheduler.cancelAll()
            ScheduleSyncWorker.reschedule(getApplication(), 0)
            repo.logout()
            _state.update {
                it.copy(loggedIn = false, savedPassword = "", error = null, message = "已退出登录")
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            container.reminderScheduler.cancelAll()
            ScheduleSyncWorker.reschedule(getApplication(), 0)
            repo.clearAll()
            _state.update {
                ScheduleUiState(booting = false, loggedIn = false, message = "已清空所有数据")
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun retryAfterError() {
        val s = _state.value
        consumeError()
        if (!s.loggedIn && s.savedPassword.isNotBlank() && s.studentId.isNotBlank()) {
            login(s.studentId, s.savedPassword, s.rememberPassword)
        } else if (s.loggedIn) {
            sync()
        }
    }

    private companion object {
        const val TAG = "ScheduleViewModel"
    }
}

/** 冷启动工厂：把闪屏阶段读好的配置直接注入初始状态 */
fun scheduleViewModelFactory(app: Application, initial: SplashPrefs?): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ScheduleViewModel(app, initial) as T
    }
