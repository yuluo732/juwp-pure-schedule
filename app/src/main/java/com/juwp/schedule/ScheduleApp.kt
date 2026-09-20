package com.juwp.schedule

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log

class ScheduleApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.i(TAG, "ScheduleApp 启动")
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.reminder_channel_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val TAG = "ScheduleApp"
        const val REMINDER_CHANNEL_ID = "class_reminder"

        lateinit var instance: ScheduleApp
            private set

        fun container(): AppContainer = instance.container
    }
}
