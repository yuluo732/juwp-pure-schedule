package com.juwp.schedule.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.juwp.schedule.ScheduleApp
import com.juwp.schedule.data.net.LoginResult
import com.juwp.schedule.data.repo.SyncResult
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 后台定时同步课表。
 *
 * 为什么用 WorkManager 而不是 AlarmManager：
 *  - 课表同步不要求分钟级准时，WorkManager 的周期任务对省电更友好；
 *  - 自带网络约束 + 系统重启后自动恢复，不用自己写开机广播；
 *  - 依赖（androidx.work）本来就在项目里，不违反轻依赖原则。
 *
 * 失败策略：全程静默，不打扰用户。会话失效时尝试用记住的密码静默重登一次；
 * 仍失败就等下次周期或用户打开 App 时的前台同步兜底（前台同步失败会回落本地缓存）。
 */
class ScheduleSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? ScheduleApp)?.container ?: return Result.failure()
        val settings = container.settings
        val repo = container.repository

        val loggedIn = runCatching { settings.loggedIn.first() }.getOrDefault(false)
        if (!loggedIn) return Result.success()

        val result = when (runCatching { repo.syncSchedule() }.getOrNull()) {
            is SyncResult.Success -> Result.success()

            SyncResult.NeedLogin -> {
                // 会话过期：有记住密码就静默重登一次，再补同步一次
                val id = settings.currentStudentId()
                val pwd = settings.currentPassword()
                if (id.isNotBlank() && pwd.isNotBlank() &&
                    runCatching { repo.login(id, pwd) }.getOrNull() is LoginResult.Success
                ) {
                    settings.setLoggedIn(true)
                    if (runCatching { repo.syncSchedule() }.getOrNull() is SyncResult.Success) {
                        Result.success()
                    } else {
                        Result.retry()
                    }
                } else {
                    // 没有凭据或重登失败：保持登录标记不动，等用户下次打开 App 处理
                    Result.success()
                }
            }

            else -> Result.retry()
        }

        // 后台每次同步后顺带把上课提醒「滚动续排」一次（AlarmManager 只排未来 7 天）：
        // 这样即使 App 长期不打开，闹钟窗口也不会用完；setAlarmClock 注册后
        // 即使进程被杀，时间一到系统照样唤醒触发
        runCatching { container.reminderScheduler.reschedule() }
            .onFailure { Log.w("ScheduleSyncWorker", "后台续排提醒失败", it) }

        return result
    }

    companion object {
        private const val WORK_NAME = "jw_schedule_periodic_sync"

        /**
         * 按用户设置的间隔（重）排周期任务；hours <= 0 表示关闭后台同步。
         * 用 UPDATE 策略保证「改间隔」时原地生效，不会叠出多个任务。
         */
        fun reschedule(context: Context, intervalHours: Int) {
            val wm = WorkManager.getInstance(context)
            if (intervalHours <= 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<ScheduleSyncWorker>(
                intervalHours.coerceAtLeast(1).toLong(), TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
