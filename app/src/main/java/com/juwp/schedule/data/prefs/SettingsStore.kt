package com.juwp.schedule.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jw_settings")

/**
 * 冷启动一次性读出的关键配置。
 * MainActivity 在首帧之前用它构造 ViewModel 的初始状态，
 * 让用户看到的第一个界面就是「最终外观」（主题色/动态色/深浅模式/背景图），
 * 杜绝「先默认蓝再跳成用户配色」的闪变。
 */
data class SplashPrefs(
    val loggedIn: Boolean = false,
    val themeMode: String = "system",
    val themeColorArgb: Long = 0xFF2B5CE6L,
    val dynamicColor: Boolean = false,
    val backgroundImagePath: String = "",
    val backgroundHd: Boolean = false,
    val termStartMillis: Long = 0L,
    val currentTermId: String = "",
    val lastSyncAt: Long = 0L,
)

/**
 * 用户设置与凭据存储。
 *
 * ⚠️ 安全说明：学号/密码以明文存在 app 私有 DataStore 中（`/data/data/<包名>/files/datastore/`）。
 *   - 未 root 的设备上其它应用无法读取；
 *   - 已排除云备份（见 res/xml/backup_rules.xml）；
 *   - 如果要把 APK 分发给别人或上架，建议改为 EncryptedSharedPreferences；
 *     当前实现保持零额外依赖，便于你先跑通。
 *   - 用户也可以在设置里选择「不保存密码」，则每次冷启动需重新输入。
 */
class SettingsStore(private val context: Context) {

    val studentId: Flow<String> = context.dataStore.data.map { it[KEY_STUDENT_ID] ?: "" }
    val savedPassword: Flow<String> = context.dataStore.data.map { it[KEY_PASSWORD] ?: "" }
    val rememberPassword: Flow<Boolean> = context.dataStore.data.map { it[KEY_REMEMBER] ?: true }
    val loggedIn: Flow<Boolean> = context.dataStore.data.map { it[KEY_LOGGED_IN] ?: false }

    /** 第 1 周的周一日期（毫秒时间戳，0 表示未设置） */
    val termStartMillis: Flow<Long> = context.dataStore.data.map { it[KEY_TERM_START] ?: 0L }
    /** 教务系统里当前学期 id */
    val currentTermId: Flow<String> = context.dataStore.data.map { it[KEY_TERM_ID] ?: "" }

    /** 上课提醒提前分钟数 */
    val reminderLeadMinutes: Flow<Int> = context.dataStore.data.map { it[KEY_REMINDER_LEAD] ?: 15 }

    /** 是否开启上课提醒 */
    val reminderEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_REMINDER_ENABLED] ?: false }

    /** 服务端标记的「今天」是星期几 */
    val serverWeekday: Flow<Int> = context.dataStore.data.map { it[KEY_SERVER_WEEKDAY] ?: 0 }

    /** 上次同步时间 */
    val lastSyncAt: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_SYNC] ?: 0L }

    /** 深色模式："system"（跟随系统）/ "light" / "dark" */
    val themeMode: Flow<String> = context.dataStore.data.map { it[KEY_THEME_MODE] ?: "system" }

    /** 主题色（ARGB，Long 存储；默认经典蓝，与旧版视觉一致） */
    val themeColorSeed: Flow<Long> = context.dataStore.data.map { it[KEY_THEME_COLOR] ?: DEFAULT_THEME_COLOR }

    /** 动态颜色主题（Material You）：开启后忽略 themeColorSeed 与外观模式 */
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[KEY_DYNAMIC_COLOR] ?: false }

    /** 自定义背景图（已拷贝到内部存储的绝对路径；空 = 未设置） */
    val backgroundImage: Flow<String> = context.dataStore.data.map { it[KEY_BACKGROUND] ?: "" }

    /** 高清背景图：开启后按原图分辨率解码（更清晰但更耗内存） */
    val backgroundHd: Flow<Boolean> = context.dataStore.data.map { it[KEY_BACKGROUND_HD] ?: false }

    /** 课程卡片透明化：周课表卡片半透明，让自定义背景图透出来 */
    val cardTransparent: Flow<Boolean> = context.dataStore.data.map { it[KEY_CARD_TRANSPARENT] ?: false }

    /** 课表自动更新间隔（小时；0 = 仅手动刷新） */
    val syncIntervalHours: Flow<Int> = context.dataStore.data.map { it[KEY_SYNC_INTERVAL] ?: 6 }

    /**
     * 自动选择学期：**默认开启**。
     *
     * 开启后打开 App 会按当前日期自动切到对应学期
     * （9 月~次年 1 月 → 第 1 学期；2~8 月 → 第 2 学期，见 [com.juwp.schedule.domain.TermMatcher]），
     * 不用每学期手动去设置里换。关掉则始终用你手选的学期。
     */
    val autoSelectTerm: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_TERM] ?: true }

    suspend fun setAutoSelectTerm(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_TERM] = enabled }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    suspend fun setThemeColorSeed(argb: Long) {
        context.dataStore.edit { it[KEY_THEME_COLOR] = argb }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setBackgroundImagePath(path: String) {
        context.dataStore.edit { it[KEY_BACKGROUND] = path }
    }

    suspend fun setBackgroundHd(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BACKGROUND_HD] = enabled }
    }

    suspend fun setCardTransparent(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CARD_TRANSPARENT] = enabled }
    }

    suspend fun setSyncIntervalHours(hours: Int) {
        context.dataStore.edit { it[KEY_SYNC_INTERVAL] = hours }
    }

    suspend fun saveCredentials(studentId: String, password: String, remember: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_STUDENT_ID] = studentId
            prefs[KEY_REMEMBER] = remember
            if (remember) prefs[KEY_PASSWORD] = password else prefs.remove(KEY_PASSWORD)
        }
    }

    suspend fun setLoggedIn(value: Boolean) {
        context.dataStore.edit { it[KEY_LOGGED_IN] = value }
    }

    suspend fun setTermStart(millis: Long) {
        context.dataStore.edit { it[KEY_TERM_START] = millis }
    }

    suspend fun setCurrentTermId(termId: String) {
        context.dataStore.edit { it[KEY_TERM_ID] = termId }
    }

    suspend fun setServerWeekday(weekday: Int) {
        context.dataStore.edit { it[KEY_SERVER_WEEKDAY] = weekday }
    }

    suspend fun setReminderLeadMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_REMINDER_LEAD] = minutes }
    }

    suspend fun setReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_REMINDER_ENABLED] = enabled }
    }

    suspend fun setLastSyncAt(millis: Long) {
        context.dataStore.edit { it[KEY_LAST_SYNC] = millis }
    }

    suspend fun currentStudentId(): String = studentId.first()

    suspend fun currentPassword(): String = savedPassword.first()

    /**
     * 冷启动一次性读取全部关键配置（DataStore 单次磁盘读，毫秒级）。
     * 在 MainActivity.onCreate 里用 runBlocking 调用：首帧之前拿到最终外观。
     */
    suspend fun readSplashPrefs(): SplashPrefs {
        val p = context.dataStore.data.first()
        return SplashPrefs(
            loggedIn = p[KEY_LOGGED_IN] ?: false,
            themeMode = p[KEY_THEME_MODE] ?: "system",
            themeColorArgb = p[KEY_THEME_COLOR] ?: DEFAULT_THEME_COLOR,
            dynamicColor = p[KEY_DYNAMIC_COLOR] ?: false,
            backgroundImagePath = p[KEY_BACKGROUND] ?: "",
            backgroundHd = p[KEY_BACKGROUND_HD] ?: false,
            termStartMillis = p[KEY_TERM_START] ?: 0L,
            currentTermId = p[KEY_TERM_ID] ?: "",
            lastSyncAt = p[KEY_LAST_SYNC] ?: 0L,
        )
    }

    /** 退出登录：清掉凭据与会话标记，但保留课表缓存以便离线查看 */
    suspend fun clearCredentials() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_PASSWORD)
            prefs[KEY_LOGGED_IN] = false
        }
    }

    private companion object {
        const val DEFAULT_THEME_COLOR = 0xFF2B5CE6L
        val KEY_STUDENT_ID = stringPreferencesKey("student_id")
        val KEY_PASSWORD = stringPreferencesKey("password")
        val KEY_REMEMBER = booleanPreferencesKey("remember_password")
        val KEY_LOGGED_IN = booleanPreferencesKey("logged_in")
        val KEY_TERM_START = longPreferencesKey("term_start_millis")
        val KEY_TERM_ID = stringPreferencesKey("current_term_id")
        val KEY_REMINDER_LEAD = intPreferencesKey("reminder_lead_minutes")
        val KEY_REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val KEY_SERVER_WEEKDAY = intPreferencesKey("server_weekday")
        val KEY_LAST_SYNC = longPreferencesKey("last_sync_at")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_THEME_COLOR = longPreferencesKey("theme_color_seed")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_BACKGROUND = stringPreferencesKey("background_image")
        val KEY_BACKGROUND_HD = booleanPreferencesKey("background_hd")
        val KEY_CARD_TRANSPARENT = booleanPreferencesKey("card_transparent")
        val KEY_SYNC_INTERVAL = intPreferencesKey("sync_interval_hours")
        val KEY_AUTO_TERM = booleanPreferencesKey("auto_select_term")
    }
}
