package com.zehn.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zehn.assistant.MainActivity
import com.zehn.assistant.R
import com.zehn.assistant.ZehnApplication
import com.zehn.assistant.data.model.VadEngineType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

interface VadDetector {
    val micLevel: StateFlow<Float>
    val speechActive: StateFlow<Boolean>
    val speechEvents: SharedFlow<Boolean>
    fun startMonitoring(engineType: VadEngineType)
    fun stopMonitoring()
}

/**
 * Silero-style 32ms (512-sample @ 16kHz) acoustic VAD computing RMS energy + zero-crossing rate
 * with adaptive noise floor and hangover smoothing. Drives Orb amplitude and barge-in.
 */
@Singleton
class SileroStyleVadDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : VadDetector {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private val _micLevel = MutableStateFlow(0f)
    override val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _speechActive = MutableStateFlow(false)
    override val speechActive: StateFlow<Boolean> = _speechActive.asStateFlow()

    private val _speechEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
    override val speechEvents: SharedFlow<Boolean> = _speechEvents.asSharedFlow()

    @SuppressLint("MissingPermission")
    override fun startMonitoring(engineType: VadEngineType) {
        if (monitorJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        monitorJob = scope.launch {
            val sampleRate = 16000
            val frameSamples = 512 // 32ms Silero standard frame
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(frameSamples * 4)

            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
            }.getOrNull() ?: return@launch

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@launch
            }

            val buffer = ShortArray(frameSamples)
            var noiseFloor = 220f
            var hangover = 0
            var wasSpeaking = false

            try {
                recorder.startRecording()
                while (true) {
                    val read = recorder.read(buffer, 0, frameSamples)
                    if (read <= 0) continue

                    var sumSq = 0.0
                    var zeroCrossings = 0
                    for (i in 0 until read) {
                        val s = buffer[i].toDouble()
                        sumSq += s * s
                        if (i > 0 && ((buffer[i] >= 0) != (buffer[i - 1] >= 0))) {
                            zeroCrossings++
                        }
                    }
                    val rms = sqrt(sumSq / read).toFloat()
                    val zcr = zeroCrossings.toFloat() / read

                    // Adapt noise floor slowly during silence
                    if (!wasSpeaking) {
                        noiseFloor = noiseFloor * 0.96f + rms * 0.04f
                    }
                    val threshold = when (engineType) {
                        VadEngineType.SILERO_NEURAL -> (noiseFloor * 2.4f).coerceIn(320f, 2200f)
                        VadEngineType.ENERGY_ADAPTIVE -> (noiseFloor * 2.8f).coerceIn(380f, 2600f)
                    }
                    val speechFrame = rms > threshold && zcr in 0.02f..0.48f
                    val normalized = ((rms - 120f) / 3800f).coerceIn(0f, 1f)
                    _micLevel.value = _micLevel.value * 0.45f + normalized * 0.55f

                    if (speechFrame) {
                        hangover = 10 // ~320ms hangover
                        if (!wasSpeaking) {
                            wasSpeaking = true
                            _speechActive.value = true
                            _speechEvents.tryEmit(true)
                        }
                    } else if (hangover > 0) {
                        hangover--
                    } else if (wasSpeaking) {
                        wasSpeaking = false
                        _speechActive.value = false
                        _speechEvents.tryEmit(false)
                    }
                }
            } finally {
                _micLevel.value = 0f
                _speechActive.value = false
                runCatching { recorder.stop() }
                recorder.release()
            }
        }
    }

    override fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        _micLevel.value = 0f
        _speechActive.value = false
    }
}

/**
 * Foreground service hosting offline wake-word ("Hey Zehn") readiness and microphone notification.
 */
class WakeWordService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_TRIGGER_ORB
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 101, openAppIntent, piFlags)

        val notification = NotificationCompat.Builder(this, ZehnApplication.CHANNEL_WAKE_WORD)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(getString(R.string.wake_word_notification_title))
            .setContentText(getString(R.string.wake_word_notification_text))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 4101
        const val ACTION_TRIGGER_ORB = "com.zehn.assistant.ACTION_TRIGGER_ORB"

        fun start(context: Context): Boolean {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            val i = Intent(context, WakeWordService::class.java)
            ContextCompat.startForegroundService(context, i)
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
