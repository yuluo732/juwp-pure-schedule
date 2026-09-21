package com.juwp.schedule.data.repo

import android.util.Log
import com.juwp.schedule.data.db.ScheduleDao
import com.juwp.schedule.data.db.buildScheduleFromCache
import com.juwp.schedule.data.db.toEntity
import com.juwp.schedule.data.model.SemesterSchedule
import com.juwp.schedule.data.net.CasClient
import com.juwp.schedule.data.net.Http
import com.juwp.schedule.data.net.JwClient
import com.juwp.schedule.data.net.JwUrls
import com.juwp.schedule.data.net.LoginResult
import com.juwp.schedule.data.net.PersistentCookieJar
import com.juwp.schedule.data.net.SessionExpiredException
import com.juwp.schedule.data.parse.ScheduleParser
import com.juwp.schedule.data.prefs.SettingsStore
import com.juwp.schedule.domain.TermMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import okhttp3.OkHttpClient

/** 同步结果 */
sealed interface SyncResult {
    data class Success(val schedule: SemesterSchedule, val fromCache: Boolean) : SyncResult
    data object NeedLogin : SyncResult
    data class Failure(val message: String) : SyncResult
}

/**
 * 课表仓库：统一编排「登录 → 抓取 → 解析 → 落库」，并把缓存读出来给 UI。
 */
class ScheduleRepository(
    private val client: OkHttpClient,
    private val cas: CasClient,
    private val jw: JwClient,
    private val cookieJar: PersistentCookieJar,
    private val dao: ScheduleDao,
    private val settings: SettingsStore,
) {

    /** 当前学期课程 Flow（离线可用） */
    fun observeCourses(termId: String) = dao.observeCourses(termId)

    fun observePeriods(termId: String) = dao.observePeriods(termId)

    fun observeTerms() = dao.observeTerms()

    fun observeCurrentTermId(): Flow<String> = settings.currentTermId

    fun observeLastSync(): Flow<Long> = settings.lastSyncAt

    // ------------------------------------------------------------ 登录

    /**
     * 完整登录：CAS 认证 + 教务 SSO + 建立 jsxsd 会话。
     *
     * 返回成功时，[PersistentCookieJar] 里已经持有后续抓课表所需的 Cookie。
     */
    suspend fun login(studentId: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        // 只记录长度，不记录学号本身 —— 学号属个人信息，不该进 logcat
            Log.i(TAG, "开始登录流程（学号 ${studentId.length} 位）")

        // 先清掉旧会话，避免残留 Cookie 干扰
        cookieJar.clear()

        // 1) 触发 SSO 入口，让服务端下发 bzb_njw 并给出 CAS 地址
        val ssoEntry = runCatching { fetchSsoEntry() }.getOrElse { e ->
            return@withContext LoginResult.Failure("无法连接教务系统：${e.message}", e)
        }

        // 2) CAS 认证
        val casResult = cas.login(studentId, password, JwUrls.SSO_SERVICE)
        if (casResult !is LoginResult.Success) return@withContext casResult

        // 3) 跟随 SSO 跳转链，建立 jsxsd 会话
        val sso = jw.followSso(ssoEntry.ticketLocation)
        Log.i(TAG, "SSO 跳转链:\n" + sso.hops.joinToString("\n"))
        if (!sso.ok) {
            return@withContext LoginResult.Failure(sso.message ?: "SSO 未到达教务系统首页")
        }

        // 4) 校验会话确实可用（防止跳转"看起来成功"但没拿到 cookie）
        if (!jw.isSessionAlive()) {
            return@withContext LoginResult.Failure("已通过统一认证，但教务系统会话未建立，请重试")
        }

        settings.setLoggedIn(true)
        LoginResult.Success(studentId)
    }

    /**
     * 访问 sso.jsp 拿 CAS 跳转地址。
     * 该响应是一个 JS 跳转页：
     *   <script>window.location.href='https://eapp2.juwp.edu.cn:9443/cas/login?service=...'</script>
     * 所以这里既要处理 302，也要处理 200+JS 的情况。
     */
    private fun fetchSsoEntry(): SsoEntry {
        val request = okhttp3.Request.Builder()
            .url(JwUrls.SSO_ENTRY)
            .header("User-Agent", Http.UA)
            .header("Accept", "text/html,*/*;q=0.8")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val location = response.header("Location")
            if (location != null) {
                return SsoEntry(cas.buildLoginUrl(JwUrls.SSO_SERVICE), location)
            }
            val html = response.body?.string().orEmpty()
            val jsUrl = JS_REDIRECT.find(html)?.groupValues?.get(1)
                ?: throw IllegalStateException("sso.jsp 未返回跳转地址，页面长度=${html.length}")
            return SsoEntry(cas.buildLoginUrl(JwUrls.SSO_SERVICE), jsUrl)
        }
    }

    // ------------------------------------------------------------ 同步课表

    /**
     * 同步课表：优先走网络，失败则回落到本地缓存。
     *
     * @param termId 指定学期；null 表示抓当前默认学期
     */
    suspend fun syncSchedule(termId: String? = null, force: Boolean = false): SyncResult =
        withContext(Dispatchers.IO) {
            try {
                // ⚠️ 关键：termId 为空时**不能**让服务器决定学期。
                // fetchScheduleHtml(null) 不传 xnxq01id，服务端会返回它自己的默认学期；
                // 而下面又会 setCurrentTermId(parsed.termId)，于是用户选的学期被悄悄覆盖。
                // 实测踩到：用户在设置里选了 2029-2030-2，冷启动静默同步后被换成 2026-2027-1，
                // 界面显示的课表与「当前学期」不一致。
                // 因此这里显式带上用户当前选中的学期。
                val requestedTerm = resolveTermToSync(termId)

                val html = jw.fetchScheduleHtml(requestedTerm)
                val parsed = ScheduleParser.parse(html)
                Log.i(
                    TAG,
                    "解析完成：学期=${parsed.termId} 节次=${parsed.periods.size} " +
                        "课程安排=${parsed.cells.size} 可选学期=${parsed.availableTerms.size}"
                )

                // 落库（显式传 termId：空课表时 courses/periods 都为空，无法从中推断学期）
                val courses = parsed.cells.map { it.toEntity(parsed.termId) }
                val periods = parsed.periods.mapIndexed { i, p -> p.toEntity(parsed.termId, i) }
                val terms = parsed.availableTerms.map { it.toEntity(parsed.fetchedAt) }
                dao.replaceTerm(courses, periods, terms, termId = parsed.termId)

                settings.setCurrentTermId(parsed.termId)
                settings.setLastSyncAt(parsed.fetchedAt)
                parsed.currentWeekdayFromServer?.let { settings.setServerWeekday(it) }

                SyncResult.Success(parsed, fromCache = false)
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "会话失效，需要重新登录")
                SyncResult.NeedLogin
            } catch (e: Exception) {
                Log.e(TAG, "同步失败", e)
                // 回落到本地缓存时，明确按「请求的那个学期」取，
                // 不能退化成"随便拿一个学期的缓存"，否则会出现选中学期与显示课表不一致
                val fallbackTerm = termId ?: settings.currentTermId.first().ifBlank { null }
                val cached = loadFromCache(fallbackTerm)
                if (cached != null) {
                    SyncResult.Success(cached, fromCache = true)
                } else {
                    SyncResult.Failure(e.message ?: "同步失败")
                }
            }
        }

    /**
     * 「自动选择学期」按今天日期推断出的学期；未开启或推断不出时返回 null。
     *
     * ⚠️ 之所以抽成**公开**方法：**冷启动也要用它**。
     *    原实现只在「同步时」用推断结果（见 [resolveTermToSync]），
     *    而同步受「自动更新间隔」节流（默认 6 小时）——
     *    于是用户手动切到别的学期之后、间隔到期之前，App 会一直停在手动选的那个学期。
     *    可那个开关的文案承诺的是「打开 App 自动切到当前学期」，
     *    用户看到的就是「自动选择学期失效了」（实测复现：12:30 打开 App 仍显示 09:44 手选的学期）。
     */
    suspend fun resolveAutoTerm(): String? {
        val known = dao.observeTerms().first().map { it.termId }
        return TermMatcher.pickTerm(known)?.also {
            Log.i(TAG, "自动选择学期：今天 ${LocalDate.now()} → $it（候选 ${known.size} 个）")
        }
    }

    /**
     * 决定本次同步要抓哪个学期。
     *
     * 优先级：
     *  1. 调用方显式传入的 [explicit]（用户在下拉里手动选了学期）；
     *  2. 开启「自动选择学期」时，按今天日期从已知学期列表里推断
     *     （9 月~次年 1 月 = 第 1 学期，2~8 月 = 第 2 学期）；
     *  3. 兜底用上次保存的学期；
     *  4. 都没有则返回 null，交给服务端默认学期（首次安装、库里还没学期列表时）。
     */
    private suspend fun resolveTermToSync(explicit: String?): String? {
        if (!explicit.isNullOrBlank()) return explicit

        if (settings.autoSelectTerm.first()) {
            resolveAutoTerm()?.let { return it }
        }
        return settings.currentTermId.first().ifBlank { null }
    }

    /**
     * 只读本地缓存。
     *
     * @param termId 指定学期。传 null 时才回退到「当前学期 / 库里被标记选中的学期」。
     *   **传了具体学期就必须严格按该学期取** —— 曾经这里在取不到时回退到别处的学期，
     *   导致用户选了 A 学期却看到 B 学期的课表（严重的显示错乱）。
     */
    suspend fun loadFromCache(termId: String? = null): SemesterSchedule? = withContext(Dispatchers.IO) {
        val id = if (termId != null) {
            termId.ifBlank { return@withContext null }
        } else {
            settings.currentTermId.first().ifBlank { dao.selectedTerm()?.termId }
                ?: return@withContext null
        }
        val periods = dao.periods(id)
        // ⚠️ 不能用「课程数为 0」判定无缓存 —— 空学期（教务系统还没排课）本来就没有课程，
        // 那样会把「课表为空」误判成「没有数据」，还会让界面继续显示上一个学期的课表。
        // 判据改为：该学期是否成功同步过（period_info 有行，空学期也会写占位行）。
        val everSynced = periods.isNotEmpty() || dao.isTermSynced(id)
        if (!everSynced) {
            Log.i(TAG, "学期 $id 尚未同步过，无缓存可用")
            return@withContext null
        }
        buildScheduleFromCache(
            termId = id,
            courses = dao.courses(id),
            periods = periods,
            terms = dao.observeTerms().first(),
            currentWeekday = settings.serverWeekday.first().takeIf { it in 1..7 },
            fetchedAt = settings.lastSyncAt.first(),
        )
    }

    /** 退出登录 */
    suspend fun logout() = withContext(Dispatchers.IO) {
        cookieJar.clear()
        settings.clearCredentials()
    }

    /** 彻底清理（含课表缓存） */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        cookieJar.clear()
        settings.clearCredentials()
        settings.setCurrentTermId("")
        dao.clearEverything()
    }

    private data class SsoEntry(val casUrl: String, val ticketLocation: String)

    private companion object {
        const val TAG = "ScheduleRepo"
        val JS_REDIRECT = Regex("""location\.href\s*=\s*['"]([^'"]+)['"]""")
    }
}
