package com.juwp.schedule

import android.content.Context
import com.juwp.schedule.data.db.ScheduleDatabase
import com.juwp.schedule.data.net.CasClient
import com.juwp.schedule.data.net.Http
import com.juwp.schedule.data.net.JwClient
import com.juwp.schedule.data.prefs.SettingsStore
import com.juwp.schedule.data.repo.ScheduleRepository
import com.juwp.schedule.reminder.ReminderScheduler
import okhttp3.OkHttpClient

/**
 * 手写依赖容器。
 *
 * 刻意不使用 Hilt/Koin：
 *  - 依赖图很小（1 个 client、1 个 db、1 个 repo），手写更直观；
 *  - 少两个注解处理器，Android Studio 首次构建更快、更不容易因版本不匹配失败。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val database: ScheduleDatabase by lazy { ScheduleDatabase.build(appContext) }

    private val httpParts by lazy { Http.build(appContext) }

    val cookieJar by lazy { httpParts.second }

    val httpClient: OkHttpClient by lazy { httpParts.first }

    val casClient: CasClient by lazy { CasClient(httpClient) }

    val jwClient: JwClient by lazy { JwClient(httpClient, cookieJar) }

    val repository: ScheduleRepository by lazy {
        ScheduleRepository(
            client = httpClient,
            cas = casClient,
            jw = jwClient,
            cookieJar = cookieJar,
            dao = database.scheduleDao(),
            settings = settings,
        )
    }

    val reminderScheduler: ReminderScheduler by lazy {
        ReminderScheduler(appContext, repository, settings)
    }
}
