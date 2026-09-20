package com.juwp.schedule.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.juwp.schedule.MainActivity
import com.juwp.schedule.R
import com.juwp.schedule.ScheduleApp

/**
 * 上课提醒通知。
 *
 * 由 [ReminderScheduler] 用 AlarmManager.setAlarmClock 注册的精确闹钟触发
 * （系统级闹钟，App 进程被杀也会准时唤醒；不用 WorkManager 是因为它不保证分钟级准时）。
 * 缺少通知权限时静默跳过，不崩溃。
 */
class ClassReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMINDER) return

        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE).orEmpty()
        val room = intent.getStringExtra(ReminderScheduler.EXTRA_ROOM).orEmpty()
        val teachers = intent.getStringExtra(ReminderScheduler.EXTRA_TEACHERS).orEmpty()
        val time = intent.getStringExtra(ReminderScheduler.EXTRA_TIME).orEmpty()
        val lead = intent.getIntExtra(ReminderScheduler.EXTRA_LEAD, 15)

        if (title.isBlank()) return

        // ⚠️ 过时提醒不再补发：部分 ROM（如 vivo/小米的省电策略）会把闹钟一直扣到
        // App 回到前台才投递，甚至改系统时间后一次性补发——那样会「一股脑弹出一堆」。
        // 这里判断：实际触发比原定时间晚超过 30 分钟就静默丢弃（课早就开始了，提醒没意义）
        val triggerAt = intent.getLongExtra(ReminderScheduler.EXTRA_TRIGGER_AT, 0L)
        if (triggerAt > 0 && System.currentTimeMillis() - triggerAt > 30 * 60_000L) {
            Log.i(TAG, "提醒已过期 ${(System.currentTimeMillis() - triggerAt) / 60_000} 分钟，跳过补发：$title")
            return
        }

        // Android 13+ 需要运行时通知权限，没有就静默跳过（不崩溃）
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "缺少通知权限，跳过提醒：$title")
            return
        }

        val content = buildString {
            append(time)
            if (room.isNotBlank()) append(" · ").append(room)
            if (teachers.isNotBlank()) append(" · ").append(teachers)
        }

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ScheduleApp.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_title_format, title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(title, time), notification)
        }.onFailure { Log.w(TAG, "发送通知失败", it) }
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationId(title: String, time: String): Int =
        (title + time).hashCode() and 0x7FFFFFFF

    private companion object {
        const val TAG = "ClassReminder"
    }
}
