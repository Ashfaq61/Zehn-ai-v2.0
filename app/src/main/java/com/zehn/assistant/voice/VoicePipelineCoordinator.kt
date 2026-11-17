package com.zehn.assistant.voice

import com.zehn.assistant.agent.AgentLoop
import com.zehn.assistant.agent.CameraLaunchRequest
import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.LlmMessage
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.ZehnSettings
import com.zehn.assistant.data.model.AiExecutionMode
import com.zehn.assistant.data.model.OrbState
import com.zehn.assistant.data.model.VoiceLanguage
import com.zehn.assistant.data.model.VoiceProfile
import com.zehn.assistant.security.SafetyPolicyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class VoicePipelineUiState(
    val orbState: OrbState = OrbState.IDLE,
    val micLevel: Float = 0f,
    val liveTranscript: String = "",
    val lastReply: String = "",
    val detectedLanguage: VoiceLanguage = VoiceLanguage.ENGLISH,
    val statusBanner: String = "Tap the glowing orb or say \"Hey Zehn\"",
    val activeLiveModel: String = "",
    val liveModeActive: Boolean = false,
    val continuousModeActive: Boolean = false
)

sealed class LiveChatTranscriptEvent {
    data class InputDraft(val text: String) : LiveChatTranscriptEvent()
    data class OutputDraft(val text: String) : LiveChatTranscriptEvent()
    data class CommittedTurn(val role: String, val content: String, val modelUsed: String) : LiveChatTranscriptEvent()
    data object ClearDrafts : LiveChatTranscriptEvent()
}

@Singleton
class VoicePipelineCoordinator @Inject constructor(
    private val vad: SileroStyleVadDetector,
    private val stt: AndroidSttEngine,
    private val tts: SentenceStreamingTtsEngine,
    private val realtimeWs: RealtimeWebSocketClient,
    private val geminiLive: GeminiLiveAudioClient,
    private val aiRouter: AiEngineRouter,
    private val agentLoop: AgentLoop,
    private val prefs: PreferencesRepository,
    private val safetyPolicy: SafetyPolicyManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeTurnJob: Job? = null
    private var classicRetryCount = 0

    private val _uiState = MutableStateFlow(VoicePipelineUiState())
    val uiState: StateFlow<VoicePipelineUiState> = _uiState.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 12)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _liveChatEvents = MutableSharedFlow<LiveChatTranscriptEvent>(extraBufferCapacity = 32)
    val liveChatEvents: SharedFlow<LiveChatTranscriptEvent> = _liveChatEvents.asSharedFlow()

    val liveModelsDiscovery: StateFlow<LiveModelsDiscoveryState> = geminiLive.discoveryState
    val cameraLaunchRequests: SharedFlow<CameraLaunchRequest> = agentLoop.cameraLaunchRequests

    init {
        scope.launch {
            prefs.settings.collect { s ->
                _uiState.update { it.copy(continuousModeActive = s.continuousConversationEnabled) }
            }
        }
        scope.launch {
            try {
                vad.micLevel.collect { level ->
                    if (!_uiState.value.liveModeActive) {
                        _uiState.update { it.copy(micLevel = level) }
                        if (_uiState.value.orbState == OrbState.SPEAKING && level > 0.62f) {
                            bargeIn()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.tryEmit(e.message ?: "Voice activity monitor failed")
            }
        }
        scope.launch {
            try {
                stt.partialTranscript.collect { partial ->
                    if (!_uiState.value.liveModeActive && partial.isNotBlank()) {
                        _uiState.update { it.copy(liveTranscript = partial) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.tryEmit(e.message ?: "Speech transcript listener failed")
            }
        }
        scope.launch {
            try {
                geminiLive.events.collect { ev ->
                    handleGeminiLiveEvent(ev)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.tryEmit(e.message ?: "Gemini Live listener error")
            }
        }
    }

    suspend fun refreshAvailableLiveModels() = geminiLive.discoverAvailableLiveModels()

    private fun handleGeminiLiveEvent(ev: GeminiLiveEvent) {
        when (ev) {
            is GeminiLiveEvent.TryingModel -> {
                val msg = if (ev.isFallback) {
                    "Trying fallback Live model: ${ev.modelId}..."
                } else {
                    "Connecting Gemini Live: ${ev.modelId}..."
                }
                _uiState.update {
                    it.copy(
                        orbState = OrbState.THINKING,
                        activeLiveModel = ev.modelId,
                        liveModeActive = true,
                        statusBanner = msg
                    )
                }
                if (ev.isFallback) _errors.tryEmit(msg)
            }

            is GeminiLiveEvent.Connected -> {
                val msg = if (ev.resumed) "Resumed Gemini Live (${ev.modelId})" else "Gemini Live active: ${ev.modelId}"
                _uiState.update {
                    it.copy(
                        orbState = OrbState.LISTENING,
                        activeLiveModel = ev.modelId,
                        liveModeActive = true,
                        statusBanner = msg
                    )
                }
                recordSuccessfulVoiceCheck("Gemini Live connected (${ev.modelId})")
                if (ev.resumed) _errors.tryEmit(msg)
            }

            is GeminiLiveEvent.AudioLevel -> {
                if (_uiState.value.liveModeActive) {
                    _uiState.update { it.copy(micLevel = ev.micLevel) }
                }
            }

            is GeminiLiveEvent.PlaybackState -> {
                if (_uiState.value.liveModeActive) {
                    _uiState.update {
                        it.copy(
                            orbState = if (ev.isPlaying) OrbState.SPEAKING else OrbState.LISTENING,
                            statusBanner = if (ev.isPlaying) {
                                "Speaking (${it.activeLiveModel})..."
                            } else {
                                "Listening (${it.activeLiveModel})..."
                            }
                        )
                    }
                }
            }

            is GeminiLiveEvent.InputTranscript -> {
                _uiState.update { it.copy(liveTranscript = ev.text) }
                val lower = ev.text.trim().lowercase()
                if (lower == "stop" || lower == "band karo" || lower == "ruk jao") {
                    safetyPolicy.triggerEmergencyStop()
                    stopAll()
                    return
                }
                if (ev.isTurnFinal) {
                    val modelTag = _uiState.value.activeLiveModel.ifBlank { "Gemini Live" }
                    _liveChatEvents.tryEmit(LiveChatTranscriptEvent.InputDraft(""))
                    _liveChatEvents.tryEmit(
                        LiveChatTranscriptEvent.CommittedTurn("user", ev.text, "Live Audio ($modelTag)")
                    )
                } else {
                    _liveChatEvents.tryEmit(LiveChatTranscriptEvent.InputDraft(ev.text))
                }
            }

            is GeminiLiveEvent.OutputTranscript -> {
                _uiState.update { it.copy(lastReply = ev.text) }
                if (ev.isTurnFinal) {
                    val modelTag = _uiState.value.activeLiveModel.ifBlank { "Gemini Live" }
                    recordSuccessfulVoiceCheck("Gemini Live turn completed ($modelTag)")
                    _liveChatEvents.tryEmit(LiveChatTranscriptEvent.OutputDraft(""))
                    _liveChatEvents.tryEmit(
                        LiveChatTranscriptEvent.CommittedTurn("assistant", ev.text, modelTag)
                    )
                } else {
                    _liveChatEvents.tryEmit(LiveChatTranscriptEvent.OutputDraft(ev.text))
                }
            }

            is GeminiLiveEvent.ToolAction -> {
                _uiState.update { it.copy(statusBanner = "Live tool: ${ev.name}") }
                _liveChatEvents.tryEmit(
                    LiveChatTranscriptEvent.CommittedTurn(
                        role = "assistant",
                        content = "[Tool: ${ev.name}] ${ev.summary}",
                        modelUsed = "Zehn Live Agent"
                    )
                )
            }

            is GeminiLiveEvent.StatusNotice -> {
                _uiState.update { it.copy(statusBanner = ev.message) }
                _errors.tryEmit(ev.message)
            }

            is GeminiLiveEvent.Failed -> {
                _liveChatEvents.tryEmit(LiveChatTranscriptEvent.ClearDrafts)
                _uiState.update {
                    it.copy(
                        liveModeActive = false,
                        activeLiveModel = "",
                        orbState = OrbState.IDLE,
                        micLevel = 0f,
                        statusBanner = ev.reason
                    )
                }
                _errors.tryEmit(ev.reason)
                if (ev.shouldFallbackToClassic && !safetyPolicy.policy.value.emergencyStopped) {
                    scope.launch {
                        runCatching {
                            val settings = prefs.settings.first()
                            startClassicVoiceTurn(settings)
                        }
                    }
                }
            }

            is GeminiLiveEvent.SessionEnded -> {
                _liveChatEvents.tryEmit(LiveChatTranscriptEvent.ClearDrafts)
                _uiState.update {
                    it.copy(
                        liveModeActive = false,
                        activeLiveModel = "",
                        orbState = OrbState.IDLE,
                        micLevel = 0f,
                        statusBanner = "Live session ended"
                    )
                }
            }
        }
    }

    fun startVoiceTurn() {
        try {
            if (safetyPolicy.policy.value.emergencyStopped) {
                val msg = "Emergency STOP active — clear STOP first"
                _uiState.update { it.copy(statusBanner = msg) }
                _errors.tryEmit(msg)
                return
            }

            if (geminiLive.isSessionActive.value) {
                if (_uiState.value.orbState == OrbState.SPEAKING) {
                    bargeIn()
                } else {
                    stopAll()
                }
                return
            }

            if (_uiState.value.orbState == OrbState.SPEAKING || _uiState.value.orbState == OrbState.THINKING) {
                bargeIn()
            }

            classicRetryCount = 0
            scope.launch {
                try {
                    val settings = prefs.settings.first()
                    if (settings.geminiLiveVoiceEnabled && settings.executionMode != AiExecutionMode.OFFLINE) {
                        _uiState.update {
                            it.copy(
                                orbState = OrbState.THINKING,
                                liveTranscript = "",
                                lastReply = "",
                                liveModeActive = true,
                                statusBanner = "Connecting Gemini Live..."
                            )
                        }
                        geminiLive.startLiveSession(
                            personaPrompt = settings.personaPrompt,
                            voiceProfile = settings.activeVoice,
                            preferredModel = settings.geminiLiveModel,
                            fallbackModel = settings.geminiLiveFallbackModel,
                            customModel = settings.geminiLiveCustomModel
                        )
                    } else {
                        startClassicVoiceTurn(settings)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { vad.stopMonitoring() }
                    val msg = e.message ?: "Voice capture error"
                    _uiState.update { it.copy(orbState = OrbState.IDLE, liveModeActive = false, statusBanner = msg) }
                    _errors.tryEmit(msg)
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unable to start voice pipeline"
            _uiState.update { it.copy(orbState = OrbState.IDLE, liveModeActive = false, statusBanner = msg) }
            _errors.tryEmit(msg)
        }
    }

    private fun startClassicVoiceTurn(settings: ZehnSettings) {
        try {
            if (safetyPolicy.policy.value.emergencyStopped) return
            _uiState.update {
                it.copy(
                    orbState = OrbState.LISTENING,
                    liveModeActive = false,
                    activeLiveModel = "Classic STT/LLM/TTS",
                    liveTranscript = "",
                    statusBanner = "Listening (${settings.voiceLanguage.label})..."
                )
            }
            vad.startMonitoring(settings.vadEngine)
            stt.startListening(
                language = settings.voiceLanguage,
                engineType = settings.sttEngine,
                onFinalResult = { transcript ->
                    classicRetryCount = 0
                    runCatching { vad.stopMonitoring() }
                    processSpokenQuery(transcript)
                },
                onError = { err ->
                    runCatching { vad.stopMonitoring() }
                    val isTransient = err.contains("No speech recognized", true) || err.contains("timed out", true)
                    if (isTransient && classicRetryCount < 1 && !safetyPolicy.policy.value.emergencyStopped && settings.continuousConversationEnabled) {
                        classicRetryCount++
                        _uiState.update { it.copy(statusBanner = "Re-listening automatically...") }
                        scope.launch {
                            delay(350)
                            startClassicVoiceTurn(settings)
                        }
                    } else {
                        _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = err) }
                        _errors.tryEmit(err)
                    }
                }
            )
        } catch (e: Exception) {
            runCatching { vad.stopMonitoring() }
            val msg = e.message ?: "Classic voice capture error"
            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = msg) }
            _errors.tryEmit(msg)
        }
    }

    fun bargeIn() {
        try {
            if (geminiLive.isSessionActive.value) {
                if (_uiState.value.orbState == OrbState.SPEAKING) {
                    geminiLive.interruptPlaybackImmediate()
                    _uiState.update {
                        it.copy(
                            orbState = OrbState.LISTENING,
                            statusBanner = "Interrupted (Barge-in) — listening..."
                        )
                    }
                } else {
                    geminiLive.stopSession()
                    _liveChatEvents.tryEmit(LiveChatTranscriptEvent.ClearDrafts)
                    _uiState.update {
                        it.copy(
                            orbState = OrbState.IDLE,
                            liveModeActive = false,
                            activeLiveModel = "",
                            statusBanner = "Interrupted (Barge-in)"
                        )
                    }
                }
                return
            }

            activeTurnJob?.cancel()
            tts.stop()
            stt.stopListening()
            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = "Interrupted (Barge-in)") }
        } catch (e: Exception) {
            val msg = e.message ?: "Voice interruption error"
            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = msg) }
            _errors.tryEmit(msg)
        }
    }

    fun stopAll() {
        try {
            geminiLive.stopSession()
            _liveChatEvents.tryEmit(LiveChatTranscriptEvent.ClearDrafts)
            activeTurnJob?.cancel()
            vad.stopMonitoring()
            stt.stopListening()
            tts.stop()
            realtimeWs.disconnect()
            _uiState.update {
                it.copy(
                    orbState = OrbState.IDLE,
                    liveModeActive = false,
                    activeLiveModel = "",
                    statusBanner = "Stopped"
                )
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to stop voice pipeline cleanly"
            _uiState.update { it.copy(orbState = OrbState.IDLE, liveModeActive = false, statusBanner = msg) }
            _errors.tryEmit(msg)
        }
    }

    fun previewVoice(profile: VoiceProfile) {
        try {
            val sample = when (profile.localeTag) {
                "ur-PK" -> "Assalam-o-Alaikum, main ${profile.displayName} hoon — aapka Zehn voice assistant."
                "hi-IN" -> "Namaste, main ${profile.displayName} hoon — Zehn AI aapki seva mein hai."
                else -> "Hello, I'm ${profile.displayName}, ready to assist on Zehn AI."
            }
            tts.speakImmediate(sample, profile)
        } catch (e: Exception) {
            _errors.tryEmit(e.message ?: "Voice preview failed")
        }
    }

    fun processSpokenQuery(transcript: String) {
        activeTurnJob?.cancel()
        activeTurnJob = scope.launch {
            try {
                val settings = prefs.settings.first()
                val lower = transcript.trim().lowercase()
                if (lower == "stop" || lower == "band karo" || lower == "ruk jao") {
                    safetyPolicy.triggerEmergencyStop()
                    stopAll()
                    return@launch
                }

                val detected = LanguageDetector.detect(transcript, settings.voiceLanguage)
                _liveChatEvents.tryEmit(
                    LiveChatTranscriptEvent.CommittedTurn("user", transcript, "Voice STT")
                )
                _uiState.update {
                    it.copy(
                        orbState = OrbState.THINKING,
                        liveTranscript = transcript,
                        detectedLanguage = detected,
                        lastReply = "",
                        statusBanner = "Thinking (${detected.label})..."
                    )
                }

                tts.configure(settings.activeVoice, settings.voiceRate, settings.voicePitch, detected)

                val agentResult = agentLoop.tryExecuteCommand(transcript)
                if (agentResult != null) {
                    _uiState.update {
                        it.copy(
                            orbState = OrbState.SPEAKING,
                            lastReply = agentResult,
                            statusBanner = "Speaking agent result..."
                        )
                    }
                    _liveChatEvents.tryEmit(
                        LiveChatTranscriptEvent.CommittedTurn("assistant", agentResult, "Zehn Agent")
                    )
                    tts.speakImmediate(agentResult, settings.activeVoice)
                    scheduleContinuousRelistenIfEnabled(settings)
                    return@launch
                }

                if (settings.realtimeWebSocketEnabled && realtimeWs.connect(settings.personaPrompt)) {
                    realtimeWs.sendUserTurn(transcript)
                    _uiState.update { it.copy(orbState = OrbState.SPEAKING, statusBanner = "Realtime WebSocket active") }
                    val wsReply = StringBuilder()
                    realtimeWs.events.collect { delta ->
                        if (delta == "[DONE]") {
                            tts.flushRemaining()
                            if (wsReply.isNotEmpty()) {
                                _liveChatEvents.tryEmit(
                                    LiveChatTranscriptEvent.CommittedTurn("assistant", wsReply.toString(), "Realtime WS")
                                )
                            }
                            scheduleContinuousRelistenIfEnabled(settings)
                            return@collect
                        } else if (delta.startsWith("[ERROR]")) {
                            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = delta) }
                            _errors.tryEmit(delta)
                            return@collect
                        } else {
                            wsReply.append(delta)
                            _uiState.update { it.copy(lastReply = wsReply.toString()) }
                            tts.streamTokenChunk(delta)
                        }
                    }
                    return@launch
                }

                val replyBuilder = StringBuilder()
                var switchedToSpeaking = false
                aiRouter.routeAndStream(
                    history = listOf(LlmMessage("user", transcript)),
                    userInput = transcript
                ).collect { chunk ->
                    if (chunk.error != null) {
                        _uiState.update {
                            it.copy(
                                orbState = OrbState.IDLE,
                                lastReply = chunk.error,
                                statusBanner = chunk.error
                            )
                        }
                        _errors.tryEmit(chunk.error)
                    }
                    if (chunk.delta.isNotEmpty()) {
                        if (!switchedToSpeaking) {
                            switchedToSpeaking = true
                            _uiState.update { it.copy(orbState = OrbState.SPEAKING, statusBanner = "Speaking...") }
                        }
                        replyBuilder.append(chunk.delta)
                        _uiState.update { it.copy(lastReply = replyBuilder.toString()) }
                        tts.streamTokenChunk(chunk.delta)
                    }
                    if (chunk.completed) {
                        tts.flushRemaining()
                        if (replyBuilder.isNotEmpty()) {
                            recordSuccessfulVoiceCheck("Classic voice reply (${chunk.modelUsed})")
                            _liveChatEvents.tryEmit(
                                LiveChatTranscriptEvent.CommittedTurn("assistant", replyBuilder.toString(), chunk.modelUsed)
                            )
                        }
                        scheduleContinuousRelistenIfEnabled(settings, "Ready (${chunk.modelUsed})")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "Voice assistant error"
                runCatching { vad.stopMonitoring() }
                runCatching { tts.stop() }
                _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = msg) }
                _errors.tryEmit(msg)
            }
        }
    }

    private fun recordSuccessfulVoiceCheck(detail: String) {
        scope.launch {
            runCatching {
                prefs.updateSettings {
                    it.copy(
                        lastSuccessfulVoiceTestAt = System.currentTimeMillis(),
                        lastSuccessfulVoiceTestDetail = detail.take(120)
                    )
                }
            }
        }
    }

    suspend fun runVoiceHealthSelfTest(): String {
        val settings = prefs.settings.first()
        runCatching { geminiLive.discoverAvailableLiveModels() }
        val models = geminiLive.discoveryState.value.availableModels
        val modelLabel = when {
            settings.geminiLiveVoiceEnabled && models.isNotEmpty() ->
                "Gemini Live (${models.first().id})"
            else -> "Classic (${settings.sttEngine.label} -> ${settings.activeModel} -> ${settings.ttsEngine.label})"
        }
        val detail = "Pipeline Ready: $modelLabel"
        prefs.updateSettings {
            it.copy(
                lastSuccessfulVoiceTestAt = System.currentTimeMillis(),
                lastSuccessfulVoiceTestDetail = detail
            )
        }
        return detail
    }

    private suspend fun scheduleContinuousRelistenIfEnabled(settings: ZehnSettings, idleBanner: String = "Ready") {
        if (!settings.continuousConversationEnabled || safetyPolicy.policy.value.emergencyStopped) {
            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = idleBanner) }
            return
        }
        // Wait briefly for TTS speech drain before re-arming mic
        var waitCycles = 0
        while (tts.isSpeaking.value && waitCycles < 35) {
            delay(200)
            waitCycles++
        }
        if (!safetyPolicy.policy.value.emergencyStopped) {
            startClassicVoiceTurn(settings)
        } else {
            _uiState.update { it.copy(orbState = OrbState.IDLE, statusBanner = idleBanner) }
        }
    }
}
