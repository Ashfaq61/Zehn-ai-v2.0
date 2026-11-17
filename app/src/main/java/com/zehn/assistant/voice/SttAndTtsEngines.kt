package com.zehn.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.zehn.assistant.data.model.SttEngineType
import com.zehn.assistant.data.model.VoiceLanguage
import com.zehn.assistant.data.model.VoiceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface SttEngine {
    val partialTranscript: StateFlow<String>
    val isListening: StateFlow<Boolean>
    fun startListening(
        language: VoiceLanguage,
        engineType: SttEngineType,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    )
    fun stopListening()
}

@Singleton
class AndroidSttEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : SttEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _partialTranscript = MutableStateFlow("")
    override val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    override fun startListening(
        language: VoiceLanguage,
        engineType: SttEngineType,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    _isListening.value = false
                    onError("Microphone permission (RECORD_AUDIO) is required.")
                    return@post
                }
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    onError("No system SpeechRecognizer service installed on device.")
                    return@post
                }
                runCatching { recognizer?.destroy() }
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _isListening.value = true
                            _partialTranscript.value = ""
                        }

                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            _isListening.value = false
                        }

                        override fun onError(error: Int) {
                            _isListening.value = false
                            val msg = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Try again."
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out."
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                                else -> "Speech recognition error ($error)"
                            }
                            onError(msg)
                        }

                        override fun onResults(results: Bundle?) {
                            _isListening.value = false
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull().orEmpty().trim()
                            if (text.isNotEmpty()) {
                                _partialTranscript.value = text
                                onFinalResult(text)
                            } else {
                                onError("Empty speech result")
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull().orEmpty()
                            if (partial.isNotBlank()) _partialTranscript.value = partial
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.bcp47)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    if (engineType == SttEngineType.VOSK_OFFLINE) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                }
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                _isListening.value = false
                onError(e.message ?: "Failed to start microphone recognition")
            }
        }
    }

    override fun stopListening() {
        mainHandler.post {
            _isListening.value = false
            runCatching { recognizer?.stopListening() }
        }
    }
}

interface TtsEngine {
    val isSpeaking: StateFlow<Boolean>
    fun configure(profile: VoiceProfile, rateMultiplier: Float, pitchMultiplier: Float, detectedLang: VoiceLanguage)
    fun streamTokenChunk(delta: String)
    fun flushRemaining()
    fun speakImmediate(text: String, profile: VoiceProfile)
    fun stop()
}

/**
 * Buffers streaming LLM tokens and dispatches sentence chunks immediately to Android TextToSpeech
 * for low-latency conversational replies, with instant barge-in cancellation.
 */
@Singleton
class SentenceStreamingTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : TtsEngine {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val sentenceBuffer = StringBuilder()

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var activeProfile: VoiceProfile = VoiceProfile.ARIA
    private var rateMult = 1.0f
    private var pitchMult = 1.0f

    init {
        runCatching {
            tts = TextToSpeech(context) { status ->
                ready = (status == TextToSpeech.SUCCESS)
                if (ready) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                        }
                    })
                    applyVoiceConfig(activeProfile, VoiceLanguage.AUTO)
                }
            }
        }
    }

    override fun configure(
        profile: VoiceProfile,
        rateMultiplier: Float,
        pitchMultiplier: Float,
        detectedLang: VoiceLanguage
    ) {
        activeProfile = profile
        rateMult = rateMultiplier
        pitchMult = pitchMultiplier
        applyVoiceConfig(profile, detectedLang)
    }

    private fun applyVoiceConfig(profile: VoiceProfile, detectedLang: VoiceLanguage) {
        if (!ready) return
        runCatching {
            val localeTag = when (detectedLang) {
                VoiceLanguage.URDU -> "ur-PK"
                VoiceLanguage.HINDI -> "hi-IN"
                VoiceLanguage.HINGLISH -> "en-IN"
                VoiceLanguage.ENGLISH -> "en-US"
                VoiceLanguage.AUTO -> profile.localeTag
            }
            val locale = Locale.forLanguageTag(localeTag)
            val res = tts?.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            tts?.setPitch((profile.pitch * pitchMult).coerceIn(0.6f, 1.6f))
            tts?.setSpeechRate((profile.rate * rateMult).coerceIn(0.6f, 1.8f))
        }
    }

    @Synchronized
    override fun streamTokenChunk(delta: String) {
        sentenceBuffer.append(delta)
        val text = sentenceBuffer.toString()
        val boundaryIdx = text.lastIndexOfAny(charArrayOf('.', '!', '?', '۔', '।', '\n'))
        if (boundaryIdx >= 8) {
            val complete = text.substring(0, boundaryIdx + 1).trim()
            val remainder = text.substring(boundaryIdx + 1)
            sentenceBuffer.clear()
            sentenceBuffer.append(remainder)
            enqueueSpeak(complete)
        }
    }

    @Synchronized
    override fun flushRemaining() {
        val rem = sentenceBuffer.toString().trim()
        sentenceBuffer.clear()
        if (rem.isNotEmpty()) enqueueSpeak(rem)
    }

    override fun speakImmediate(text: String, profile: VoiceProfile) {
        stop()
        configure(profile, rateMult, pitchMult, VoiceLanguage.AUTO)
        enqueueSpeak(text)
    }

    private fun enqueueSpeak(text: String) {
        if (!ready || text.isBlank()) return
        val clean = text.replace(Regex("[*#`]+"), "").trim()
        if (clean.isEmpty()) return
        _isSpeaking.value = true
        runCatching {
            tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }.onFailure {
            _isSpeaking.value = false
        }
    }

    @Synchronized
    override fun stop() {
        sentenceBuffer.clear()
        _isSpeaking.value = false
        runCatching { tts?.stop() }
    }
}
