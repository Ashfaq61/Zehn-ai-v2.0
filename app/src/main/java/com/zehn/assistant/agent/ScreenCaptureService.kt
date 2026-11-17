package com.zehn.assistant.agent

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zehn.assistant.R
import com.zehn.assistant.ZehnApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class ScreenshotPermissionStatus(val label: String) {
    MEDIA_PROJECTION_GRANTED("MEDIA_PROJECTION GRANTED"),
    ACCESSIBILITY_ONLY_API30_PLUS("ACCESSIBILITY API 30+ ONLY (MediaProjection not granted)"),
    CONSENT_REQUIRED("CONSENT REQUIRED (Grant MediaProjection or enable Accessibility API 30+)")
}

class ScreenCaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            clearActiveProjection("MediaProjection stopped by user or system lock")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode == Activity.RESULT_OK && dataIntent != null) {
            runCatching {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection?.unregisterCallback(projectionCallback)
                mediaProjection?.stop()
                val projection = mgr.getMediaProjection(resultCode, dataIntent)
                projection?.registerCallback(projectionCallback, handler)
                mediaProjection = projection
                activeProjection = projection
                _projectionActive.value = projection != null
                _lastCaptureError.value = if (projection != null) "None" else "getMediaProjection returned null"
            }.onFailure { err ->
                _projectionActive.value = false
                _lastCaptureError.value = "MediaProjection init failed: ${err.message}"
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notif = NotificationCompat.Builder(this, ZehnApplication.CHANNEL_SCREEN_CAPTURE)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(getString(R.string.screen_capture_notification_title))
            .setContentText(getString(R.string.screen_capture_notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(4109, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(4109, notif)
        }
    }

    override fun onDestroy() {
        clearActiveProjection("ScreenCaptureService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun clearActiveProjection(reason: String) {
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        activeProjection = null
        _projectionActive.value = false
        _lastCaptureError.value = reason
    }

    companion object {
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_DATA_INTENT = "extra_data_intent"

        @Volatile
        private var activeProjection: MediaProjection? = null

        private val _projectionActive = MutableStateFlow(false)
        val projectionActive: StateFlow<Boolean> = _projectionActive.asStateFlow()

        private val _lastCaptureError = MutableStateFlow("None")
        val lastCaptureError: StateFlow<String> = _lastCaptureError.asStateFlow()

        fun startWithConsent(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA_INTENT, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching { activeProjection?.stop() }
            activeProjection = null
            _projectionActive.value = false
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        fun getPermissionStatus(context: Context): ScreenshotPermissionStatus {
            return when {
                _projectionActive.value && activeProjection != null -> ScreenshotPermissionStatus.MEDIA_PROJECTION_GRANTED
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ZehnAccessibilityService.isConnected.value ->
                    ScreenshotPermissionStatus.ACCESSIBILITY_ONLY_API30_PLUS
                else -> ScreenshotPermissionStatus.CONSENT_REQUIRED
            }
        }

        suspend fun captureSingleFrame(context: Context): Bitmap? = withContext(Dispatchers.IO) {
            val projection = activeProjection ?: run {
                _lastCaptureError.value = "MediaProjection not granted"
                return@withContext null
            }

            val (width, height, densityDpi) = run {
                val dm = context.resources.displayMetrics
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val wm = context.getSystemService(WindowManager::class.java)
                    val b = wm?.currentWindowMetrics?.bounds
                    Triple(
                        (b?.width() ?: dm.widthPixels).coerceAtLeast(360),
                        (b?.height() ?: dm.heightPixels).coerceAtLeast(640),
                        dm.densityDpi.coerceAtLeast(160)
                    )
                } else {
                    Triple(dm.widthPixels.coerceAtLeast(360), dm.heightPixels.coerceAtLeast(640), dm.densityDpi.coerceAtLeast(160))
                }
            }

            var reader: ImageReader? = null
            var display: VirtualDisplay? = null
            try {
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                display = projection.createVirtualDisplay(
                    "ZehnScreenCapture",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    Handler(Looper.getMainLooper())
                )

                var image = reader.acquireLatestImage()
                var tries = 0
                while (image == null && tries < 18) {
                    delay(45)
                    image = reader.acquireLatestImage()
                    tries++
                }
                if (image == null) {
                    _lastCaptureError.value = "Timed out waiting for MediaProjection frame"
                    return@withContext null
                }

                image.use { img ->
                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val fullBmp = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    fullBmp.copyPixelsFromBuffer(buffer)
                    val cropped = if (fullBmp.width != width) {
                        val c = Bitmap.createBitmap(fullBmp, 0, 0, width, height)
                        fullBmp.recycle()
                        c
                    } else fullBmp
                    _lastCaptureError.value = "None"
                    return@withContext cropped
                }
            } catch (e: Exception) {
                _lastCaptureError.value = "MediaProjection capture failed: ${e.message}"
                return@withContext null
            } finally {
                runCatching { display?.release() }
                runCatching { reader?.close() }
            }
        }
    }
}
