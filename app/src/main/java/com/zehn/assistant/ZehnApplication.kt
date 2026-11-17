package com.zehn.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.zehn.assistant.security.CrashRecoveryManager
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class ZehnApplication : Application() {

    @Inject
    lateinit var crashRecoveryManager: CrashRecoveryManager

    override fun onCreate() {
        super.onCreate()
        crashRecoveryManager.recordStartupVersion(BuildConfig.VERSION_CODE)
        installGlobalCrashLogger()
        createNotificationChannels()
    }

    private fun installGlobalCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashFile = File(filesDir, CRASH_LOG_FILE_NAME)
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    throwable.printStackTrace(pw)
                }
                val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
                val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
                val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

                val sanitizedTrace = crashRecoveryManager.sanitize(sw.toString(), maxLength = 24_000)
                val logContent = buildString {
                    appendLine("=== Zehn AI Crash Log ===")
                    appendLine("Version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
                    appendLine("Time: $formattedTime")
                    appendLine("Device Model: $deviceModel")
                    appendLine("Android Version: $androidVersion")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    appendLine("Stack Trace:")
                    append(sanitizedTrace)
                }
                crashFile.writeText(logContent)
            } catch (_: Throwable) {
                // Never swallow the crash if writing crash.txt itself fails
            }

            try {
                crashRecoveryManager.recordUncaughtCrash(throwable)
            } catch (_: Throwable) {
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val wakeChannel = NotificationChannel(
                CHANNEL_WAKE_WORD,
                getString(R.string.wake_word_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps offline 'Hey Zehn' wake-word detection active"
                setShowBadge(false)
            }
            val overlayChannel = NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.overlay_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating voice orb & emergency stop controller"
                setShowBadge(false)
            }
            val triggerChannel = NotificationChannel(
                CHANNEL_TRIGGERS,
                "Zehn Automations",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from scheduled and event-driven triggers"
            }
            val updatesChannel = NotificationChannel(
                CHANNEL_UPDATES,
                "APK Releases & Auto-Dev Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a verified GitHub Release APK is ready or when Auto-Dev reports status"
            }
            val screenCaptureChannel = NotificationChannel(
                CHANNEL_SCREEN_CAPTURE,
                getString(R.string.screen_capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while Zehn captures screen frames via MediaProjection with your consent"
                setShowBadge(false)
            }
            manager.createNotificationChannels(
                listOf(wakeChannel, overlayChannel, triggerChannel, updatesChannel, screenCaptureChannel)
            )
        }
    }

    companion object {
        const val CRASH_LOG_FILE_NAME = "crash.txt"
        const val CHANNEL_WAKE_WORD = "zehn_wake_word"
        const val CHANNEL_OVERLAY = "zehn_overlay"
        const val CHANNEL_TRIGGERS = "zehn_triggers"
        const val CHANNEL_UPDATES = "zehn_updates"
        const val CHANNEL_SCREEN_CAPTURE = "zehn_screen_capture"
    }
}
