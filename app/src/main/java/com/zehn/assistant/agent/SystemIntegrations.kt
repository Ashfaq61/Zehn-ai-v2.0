package com.zehn.assistant.agent

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zehn.assistant.MainActivity
import com.zehn.assistant.R
import com.zehn.assistant.ZehnApplication
import com.zehn.assistant.security.SafetyPolicyManager
import com.zehn.assistant.voice.WakeWordService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class FloatingBubbleService : Service() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BubbleDeps {
        fun safetyPolicy(): SafetyPolicyManager
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (bubbleView != null || !Settings.canDrawOverlays(this)) return START_STICKY

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val deps = EntryPointAccessors.fromApplication(applicationContext, BubbleDeps::class.java)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(18, 12, 18, 12)
            setBackgroundColor(0xEE1A2032.toInt())
        }

        val orbBtn = TextView(this).apply {
            text = "ZEHN"
            setTextColor(0xFF22D3EE.toInt())
            textSize = 13f
            setPadding(16, 10, 16, 10)
            setOnClickListener {
                val i = Intent(this@FloatingBubbleService, MainActivity::class.java).apply {
                    action = WakeWordService.ACTION_TRIGGER_ORB
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(i)
            }
        }
        val stopBtn = TextView(this).apply {
            text = "STOP"
            setTextColor(0xFFFF5A79.toInt())
            textSize = 12f
            setPadding(14, 10, 14, 10)
            setOnClickListener {
                deps.safetyPolicy().triggerEmergencyStop()
            }
        }
        container.addView(orbBtn)
        container.addView(stopBtn)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 260
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(container, params) }
                    true
                }
                else -> false
            }
        }

        bubbleView = container
        runCatching { wm.addView(container, params) }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notif = NotificationCompat.Builder(this, ZehnApplication.CHANNEL_OVERLAY)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Zehn Floating Bubble")
            .setContentText("Quick voice orb & emergency STOP overlay active")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(4102, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(4102, notif)
        }
    }

    override fun onDestroy() {
        bubbleView?.let { runCatching { windowManager?.removeView(it) } }
        bubbleView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FloatingBubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}

class ZehnQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = getString(R.string.quick_tile_label)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = WakeWordService.ACTION_TRIGGER_ORB
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 202, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

class ZehnAppWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            val intent = Intent(context, MainActivity::class.java).apply {
                action = WakeWordService.ACTION_TRIGGER_ORB
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pi = PendingIntent.getActivity(
                context, 303, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val views = RemoteViews(context.packageName, R.layout.zehn_widget).apply {
                setOnClickPendingIntent(R.id.widget_root, pi)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}

class TriggerBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // Trigger actions can log or display status when charger/boot triggers are enabled
        if (action == Intent.ACTION_POWER_CONNECTED || action == Intent.ACTION_BOOT_COMPLETED) {
            // Kept lightweight and policy-compliant; WorkManager / foreground listeners handle heavy tasks
        }
    }
}
