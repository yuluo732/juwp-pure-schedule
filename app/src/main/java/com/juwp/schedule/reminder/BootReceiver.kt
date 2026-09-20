package com.juwp.schedule.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.juwp.schedule.ScheduleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新 / **系统时间变化** 后重排提醒。
 *
 * ⚠️ 时间跳变防御（重要）：AlarmManager 基于 RTC 绝对时间。若用户把系统时间
 * 往后调（比如调到明天），所有「明天 8:00」的闹钟会被系统判定为早已过期，
 * 立刻全部补发——表现就是「改一次时间，通知一股脑全弹出来」。
 * 因此监听 TIME_SET / DATE_CHANGED / TIMEZONE_CHANGED：
 * 一收到就 cancelAll() 掉旧的，再按新时间重新计算并注册。
 *
 * 用 goAsync + 协程在 10 秒内完成（BroadcastReceiver 不能做长任务）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        Log.i(TAG, "收到 $action，重新排程上课提醒")
        val pending = goAsync()
        val app = context.applicationContext as? ScheduleApp ?: run {
            pending.finish()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            // reschedule() 内部第一步就是 cancelAll()，天然满足
            // 「先取消旧闹钟、再按新时间注册」的时间跳变防护要求
            runCatching { app.container.reminderScheduler.reschedule() }
                .onFailure { Log.w(TAG, "重排提醒失败（$action）", it) }
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            // ---- 系统时间 / 日期 / 时区变化（时间跳变防护） ----
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
