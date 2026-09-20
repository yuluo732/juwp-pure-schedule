package com.juwp.schedule.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.juwp.schedule.MainActivity
import com.juwp.schedule.data.prefs.SettingsStore
import com.juwp.schedule.data.repo.ScheduleRepository
import com.juwp.schedule.domain.WeekCalculator
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 上课提醒排程。
 *
 * 策略：不用 WorkManager 做「精确到分钟」的提醒（WorkManager 最小周期 15 分钟且不保证准时），
 * 而是每天用 [AlarmManager] 排一次「明天 + 今天剩余课程」的提醒，逐个课程设置精确闹钟。
 * 每次 App 启动 / 数据刷新 / 开机都会重排，保证与最新课表一致。
 *
 * 精确闹钟权限：Android 12+ 若用户未授予 SCHEDULE_EXACT_ALARM，自动降级为 setAndAllowWhileIdle
 * （可能偏差几分钟，但不会崩）。
 */
class ReminderScheduler(
    private val context: Context,
    private val repository: ScheduleRepository,
    private val settings: SettingsStore,
) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * 重排未来 [daysAhead] 天内所有课程的提醒。
     */
    suspend fun reschedule(daysAhead: Int = 7) {
        if (alarmManager == null) return
        cancelAll()

        val enabled = settings.reminderEnabled.first()
        if (!enabled) {
            Log.i(TAG, "提醒未开启，跳过排程")
            return
        }

        val leadMinutes = settings.reminderLeadMinutes.first().coerceIn(0, 120)
        val termStartMillis = settings.termStartMillis.first()
        val schedule = repository.loadFromCache() ?: run {
            Log.i(TAG, "无课表缓存，跳过排程")
            return
        }

        val termStart = if (termStartMillis > 0) {
            LocalDate.ofEpochDay(termStartMillis / 86_400_000L)
        } else {
            null
        }
        // 没有锚点日期时，用「今天=第1周」退化处理，仍然能提醒当天课程
        val anchor = termStart ?: LocalDate.now()

        val now = LocalDateTime.now()
        var scheduled = 0

        for (offset in 0 until daysAhead) {
            val date = LocalDate.now().plusDays(offset.toLong())
            val week = WeekCalculator.weekOf(date, anchor) ?: continue
            if (week < 1) continue
            val weekday = WeekCalculator.weekdayOf(date)

            schedule.periods.forEachIndexed { rowIndex, period ->
                val courses = schedule.cellAt(week, weekday, rowIndex)
                if (courses.isEmpty()) return@forEachIndexed

                val startTime = parseStartTime(period.timeRange) ?: return@forEachIndexed
                val trigger = date.atTime(startTime).minusMinutes(leadMinutes.toLong())
                if (trigger.isBefore(now)) return@forEachIndexed

                val title = courses.joinToString(" / ") { it.name }
                val room = courses.mapNotNull { it.room.takeIf { r -> r.isNotBlank() } }
                    .distinct().joinToString(" ")
                courses.forEachIndexed { idx, course ->
                    scheduleAlarm(
                        requestCode = requestCodeFor(date, rowIndex, idx),
                        triggerAt = trigger,
                        title = title,
                        room = room.ifBlank { course.room },
                        teachers = course.teachers,
                        timeText = period.timeRange,
                        leadMinutes = leadMinutes,
                    )
                    scheduled++
                }
            }
        }
        Log.i(TAG, "已排程 $scheduled 个上课提醒（提前 $leadMinutes 分钟，覆盖 $daysAhead 天）")
    }

    private fun scheduleAlarm(
        requestCode: Int,
        triggerAt: LocalDateTime,
        title: String,
        room: String,
        teachers: String,
        timeText: String,
        leadMinutes: Int,
    ) {
        val intent = Intent(context, ClassReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_ROOM, room)
            putExtra(EXTRA_TEACHERS, teachers)
            putExtra(EXTRA_TIME, timeText)
            putExtra(EXTRA_LEAD, leadMinutes)
            // 记录「本该触发的时间」：系统在某些 ROM 上会把闹钟扣住直到 App 回到前台，
            // 接收器据此判断是否已经过时太久（超过 30 分钟就不再补发，避免一次弹一堆）
            putExtra(EXTRA_TRIGGER_AT, triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerMillis = triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // ⚠️ 必须用 setAlarmClock 而不是 setExactAndAllowWhileIdle：
        //  1. 系统把它当「用户闹钟」对待——不被 Doze 延迟，也不会被
        //     小米/华为/OPPO 等厂商省电策略批量合并到维护窗口（此前
        //     「App 关掉就不响」的主要原因）
        //  2. Android 14+ 默认拒绝 SCHEDULE_EXACT_ALARM，setAlarmClock 不需要该权限
        //  3. showIntent：点状态栏的闹钟图标时打开 App
        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarmManager?.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerMillis, showIntent),
                pending,
            )
        }.onFailure { Log.w(TAG, "排程闹钟失败 requestCode=$requestCode", it) }
    }

    /**
     * 排一条 [afterSeconds] 秒后的「提醒自检」通知。
     *
     * 用来让用户当场验证后台提醒是否真的能响：注册一个 60 秒后的精确闹钟，
     * 用户锁屏等一分钟，响了说明系统放行；不响就去按引导开自启动/后台权限。
     * 走的是和真实提醒完全相同的通道（setAlarmClock + 同一个接收器）。
     */
    fun scheduleTestReminder(afterSeconds: Long = 60) {
        val am = alarmManager ?: return
        val triggerAt = System.currentTimeMillis() + afterSeconds * 1000
        val intent = Intent(context, ClassReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_TITLE, "提醒自检")
            putExtra(EXTRA_ROOM, "这条通知能出现，说明后台提醒工作正常")
            putExtra(EXTRA_TEACHERS, "")
            putExtra(EXTRA_TIME, "测试")
            putExtra(EXTRA_LEAD, 0)
            putExtra(EXTRA_TRIGGER_AT, triggerAt)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            TEST_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pending)
        }.onFailure { Log.w(TAG, "自检闹钟排程失败", it) }
    }

    /** 取消所有已排程提醒 */
    fun cancelAll() {        // PendingIntent 无法枚举，这里按 requestCode 空间逐个取消（覆盖 7 天 × 5 行 × 8 门课）
        for (day in 0 until 9) {
            for (row in 0 until 8) {
                for (idx in 0 until 8) {
                    val intent = Intent(context, ClassReminderReceiver::class.java).apply {
                        action = ACTION_REMINDER
                    }
                    val pending = PendingIntent.getBroadcast(
                        context,
                        requestCodeFor(LocalDate.now().plusDays(day.toLong()), row, idx),
                        intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                    )
                    if (pending != null) {
                        alarmManager?.cancel(pending)
                        pending.cancel()
                    }
                }
            }
        }
    }

    /** 从 "08:30~09:55" 里取出开始时间 */
    private fun parseStartTime(timeRange: String): LocalTime? {
        val match = TIME_REGEX.find(timeRange) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun requestCodeFor(date: LocalDate, rowIndex: Int, courseIndex: Int): Int =
        (date.toEpochDay().toInt() and 0xFFFF) * 1000 + rowIndex * 10 + courseIndex

    companion object {
        const val TAG = "ReminderScheduler"
        const val ACTION_REMINDER = "com.juwp.schedule.ACTION_CLASS_REMINDER"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ROOM = "extra_room"
        const val EXTRA_TEACHERS = "extra_teachers"
        const val EXTRA_TIME = "extra_time"
        const val EXTRA_LEAD = "extra_lead"
        const val EXTRA_TRIGGER_AT = "extra_trigger_at"

        private val TIME_REGEX = Regex("""(\d{1,2}):(\d{2})""")

        /** 自检闹钟用固定 requestCode，不占用课程提醒的编号空间 */
        private const val TEST_REQUEST_CODE = 990_001
    }
}
