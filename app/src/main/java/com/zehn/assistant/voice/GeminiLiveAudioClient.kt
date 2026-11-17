package com.zehn.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import androidx.core.content.ContextCompat
import com.zehn.assistant.agent.AgentLoop
import com.zehn.assistant.agent.PhoneControlExecutor
import com.zehn.assistant.agent.ScreenVisionManager
import com.zehn.assistant.agent.ScrollDirection
import com.zehn.assistant.agent.ZehnAccessibilityService
import com.zehn.assistant.agent.ZehnNotificationListenerService
import com.zehn.assistant.ai.ModelOption
import com.zehn.assistant.ai.ProviderCatalog
import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.data.model.VoiceProfile
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.security.SafetyPolicyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

data class LiveModelsDiscoveryState(
    val isLoading: Boolean = false,
    val hasVerifiedServerList: Boolean = false,
    val availableModels: List<ModelOption> = emptyList(),
    val statusMessage: String = "Checking Gemini Live models..."
)

sealed class GeminiLiveEvent {
    data class TryingModel(val modelId: String, val isFallback: Boolean) : GeminiLiveEvent()
    data class Connected(val modelId: String, val resumed: Boolean) : GeminiLiveEvent()
    data class AudioLevel(val micLevel: Float) : GeminiLiveEvent()
    data class PlaybackState(val isPlaying: Boolean) : GeminiLiveEvent()
    data class InputTranscript(val text: String, val isTurnFinal: Boolean) : GeminiLiveEvent()
    data class OutputTranscript(val text: String, val isTurnFinal: Boolean) : GeminiLiveEvent()
    data class ToolAction(val name: String, val summary: String) : GeminiLiveEvent()
    data class StatusNotice(val message: String) : GeminiLiveEvent()
    data class Failed(val reason: String, val shouldFallbackToClassic: Boolean) : GeminiLiveEvent()
    data object SessionEnded : GeminiLiveEvent()
}

@Singleton
class GeminiLiveAudioClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager,
    private val safetyPolicy: SafetyPolicyManager,
    private val agentLoop: AgentLoop,
    private val phoneControl: PhoneControlExecutor,
    private val actionLogDao: ActionLogDao,
    private val screenVisionManager: ScreenVisionManager? = null
) {
    // Secondary constructor preserved for unit tests
    constructor(
        context: Context,
        okHttpClient: OkHttpClient,
        crypto: KeystoreCryptoManager,
        safetyPolicy: SafetyPolicyManager,
        agentLoop: AgentLoop,
        phoneControl: PhoneControlExecutor,
        actionLogDao: ActionLogDao
    ) : this(context, okHttpClient, crypto, safetyPolicy, agentLoop, phoneControl, actionLogDao, null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _discoveryState = MutableStateFlow(LiveModelsDiscoveryState())
    val discoveryState: StateFlow<LiveModelsDiscoveryState> = _discoveryState.asStateFlow()

    private val _events = MutableSharedFlow<GeminiLiveEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GeminiLiveEvent> = _events.asSharedFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var activeModelId: String = ""

    @Volatile
    private var latestResumptionHandle: String = ""

    @Volatile
    private var activePersonaPrompt: String = ""

    @Volatile
    private var activeVoiceProfile: VoiceProfile = VoiceProfile.ARIA

    private val sessionRequested = AtomicBoolean(false)
    private val isPlaybackActive = AtomicBoolean(false)
    private val playbackGeneration = AtomicLong(0L)
    private val resumptionAttempts = AtomicInteger(0)
    private val lastAudioEnqueueTimeMs = AtomicLong(0L)

    @Volatile
    private var setupDeferred: CompletableDeferred<SetupOutcome>? = null

    private var connectJob: Job? = null
    private var micJob: Job? = null
    private var playbackJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    private var pcmPlaybackChannel = Channel<Pair<Long, ByteArray>>(capacity = Channel.UNLIMITED)

    private val inputTurnBuffer = StringBuilder()
    private val outputTurnBuffer = StringBuilder()
    private var inputCommittedForTurn = false
    private val cancelledToolCallIds = Collections.synchronizedSet(mutableSetOf<String>())

    private sealed class SetupOutcome {
        data object Ready : SetupOutcome()
        data class AuthOrQuotaError(val reason: String) : SetupOutcome()
        data class Failed(val reason: String) : SetupOutcome()
    }

    suspend fun discoverAvailableLiveModels(): List<ModelOption> = withContext(Dispatchers.IO) {
        val keys = crypto.getGeminiKeys()
        if (keys.isEmpty()) {
            _discoveryState.value = LiveModelsDiscoveryState(
                isLoading = false,
                hasVerifiedServerList = false,
                availableModels = emptyList(),
                statusMessage = "No Gemini API key configured. Add a key in API Keys to load available Live models."
            )
            return@withContext emptyList()
        }

        _discoveryState.value = _discoveryState.value.copy(
            isLoading = true,
            statusMessage = "Querying Gemini models endpoint..."
        )

        var lastError = "Unable to query Gemini models"
        for (attempt in keys.indices) {
            val (keyIdx, rawKey) = crypto.nextGeminiKey() ?: break
            val apiKey = rawKey.trim()
            if (apiKey.isEmpty()) continue

            val req = Request.Builder()
                .url("$MODELS_REST_URL?pageSize=200")
                .addHeader("x-goog-api-key", apiKey)
                .get()
                .build()

            try {
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 429 || resp.code == 403 || resp.code == 401) {
                        lastError = "HTTP ${resp.code} on key #${keyIdx + 1}"
                        crypto.markGeminiKeyQuotaLimited(keyIdx)
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        lastError = "Models endpoint HTTP ${resp.code}"
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    val liveModels = parseLiveModelsResponse(body)
                    val msg = if (liveModels.isEmpty()) {
                        "Connected, but no bidiGenerateContent Live models returned for this key."
                    } else {
                        "Available Live models (${liveModels.size}): ${liveModels.joinToString { it.id }}"
                    }
                    _discoveryState.value = LiveModelsDiscoveryState(
                        isLoading = false,
                        hasVerifiedServerList = true,
                        availableModels = liveModels,
                        statusMessage = msg
                    )
                    return@withContext liveModels
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = sanitizeError(e.message ?: "Network error querying models")
            }
        }

        _discoveryState.value = _discoveryState.value.copy(
            isLoading = false,
            statusMessage = "Live model check failed ($lastError)"
        )
        return@withContext emptyList()
    }

    internal fun parseLiveModelsResponse(jsonBody: String): List<ModelOption> {
        return runCatching {
            val root = JSONObject(jsonBody)
            val arr = root.optJSONArray("models") ?: return@runCatching emptyList()
            val found = mutableListOf<ModelOption>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val methods = obj.optJSONArray("supportedGenerationMethods") ?: continue
                var supportsLive = false
                for (m in 0 until methods.length()) {
                    if (methods.optString(m) == "bidiGenerateContent") {
                        supportsLive = true
                        break
                    }
                }
                if (!supportsLive) continue
                val rawName = obj.optString("name", "").trim()
                val modelId = rawName.removePrefix("models/")
                if (modelId.isBlank()) continue
                val label = obj.optString("displayName", modelId).ifBlank { modelId }
                found.add(ModelOption(id = modelId, label = label, contextWindow = "Live Audio", supportsVision = true))
            }
            val priorityOrder = ProviderCatalog.defaultGeminiLiveModels.map { it.id }
            found.sortedWith(
                compareBy<ModelOption>(
                    { opt ->
                        val idx = priorityOrder.indexOf(opt.id)
                        if (idx >= 0) idx else Int.MAX_VALUE
                    },
                    { it.id }
                )
            )
        }.getOrDefault(emptyList())
    }

    fun startLiveSession(
        personaPrompt: String,
        voiceProfile: VoiceProfile,
        preferredModel: String,
        fallbackModel: String,
        customModel: String
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _events.tryEmit(
                GeminiLiveEvent.Failed(
                    "Microphone permission (RECORD_AUDIO) is required for Gemini Live voice.",
                    shouldFallbackToClassic = false
                )
            )
            return
        }

        val keys = crypto.getGeminiKeys()
        if (keys.isEmpty()) {
            _events.tryEmit(
                GeminiLiveEvent.Failed(
                    "No Gemini API key configured. Add a Gemini key in API Keys — falling back to classic pipeline.",
                    shouldFallbackToClassic = true
                )
            )
            return
        }

        stopSessionInternal(emitEnded = false)
        sessionRequested.set(true)
        _isSessionActive.value = true
        activePersonaPrompt = personaPrompt
        activeVoiceProfile = voiceProfile
        latestResumptionHandle = ""
        resumptionAttempts.set(0)
        clearTurnBuffers()

        connectJob = scope.launch {
            try {
                // Ensure we have checked available Live models on this key
                val discovered = if (_discoveryState.value.hasVerifiedServerList) {
                    _discoveryState.value.availableModels
                } else {
                    discoverAvailableLiveModels()
                }

                val candidates = buildCandidateModelList(
                    customModel = customModel,
                    preferredModel = preferredModel,
                    fallbackModel = fallbackModel,
                    discoveredModels = discovered,
                    hasVerifiedServerList = _discoveryState.value.hasVerifiedServerList
                )

                if (candidates.isEmpty()) {
                    stopSessionInternal(emitEnded = false)
                    _events.tryEmit(
                        GeminiLiveEvent.Failed(
                            "No Gemini Live models are available for the saved API key. Using classic voice pipeline.",
                            shouldFallbackToClassic = true
                        )
                    )
                    return@launch
                }

                var lastErr = "Connection failed"
                var connected = false

                for ((index, modelId) in candidates.withIndex()) {
                    if (!sessionRequested.get() || !isActive) return@launch
                    val isFallback = index > 0 || modelId != preferredModel
                    _events.tryEmit(GeminiLiveEvent.TryingModel(modelId, isFallback))

                    val outcome = connectAndVerifySetup(
                        modelId = modelId,
                        resumptionHandle = null
                    )
                    when (outcome) {
                        is SetupOutcome.Ready -> {
                            activeModelId = modelId
                            connected = true
                            _events.tryEmit(GeminiLiveEvent.Connected(modelId, resumed = false))
                            startAudioCaptureAndPlayback()
                            break
                        }
                        is SetupOutcome.AuthOrQuotaError -> {
                            lastErr = outcome.reason
                            _events.tryEmit(GeminiLiveEvent.StatusNotice("Model $modelId unavailable ($lastErr), trying next..."))
                        }
                        is SetupOutcome.Failed -> {
                            lastErr = outcome.reason
                            _events.tryEmit(GeminiLiveEvent.StatusNotice("Model $modelId failed ($lastErr), trying next..."))
                        }
                    }
                }

                if (!connected && sessionRequested.get()) {
                    stopSessionInternal(emitEnded = false)
                    _events.tryEmit(
                        GeminiLiveEvent.Failed(
                            "Gemini Live unavailable ($lastErr). Switching to classic STT/LLM/TTS fallback.",
                            shouldFallbackToClassic = true
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMsg = sanitizeError(e.message ?: "Gemini Live connection error")
                stopSessionInternal(emitEnded = false)
                _events.tryEmit(
                    GeminiLiveEvent.Failed(
                        "Gemini Live error: $safeMsg. Switching to classic fallback.",
                        shouldFallbackToClassic = true
                    )
                )
            }
        }
    }

    internal fun buildCandidateModelList(
        customModel: String,
        preferredModel: String,
        fallbackModel: String,
        discoveredModels: List<ModelOption>,
        hasVerifiedServerList: Boolean
    ): List<String> {
        val result = linkedSetOf<String>()
        val cleanCustom = customModel.trim().removePrefix("models/")
        if (cleanCustom.isNotEmpty()) {
            result.add(cleanCustom)
        }
        val cleanPreferred = preferredModel.trim().removePrefix("models/")
        val cleanFallback = fallbackModel.trim().removePrefix("models/")

        val availableIds = discoveredModels.map { it.id }.toSet()
        val priorityOrder = ProviderCatalog.defaultGeminiLiveModels.map { it.id }

        if (hasVerifiedServerList) {
            if (cleanPreferred.isNotBlank() && cleanPreferred in availableIds) result.add(cleanPreferred)
            if (cleanFallback.isNotBlank() && cleanFallback in availableIds) result.add(cleanFallback)
            priorityOrder.forEach { id ->
                if (id in availableIds) result.add(id)
            }
            discoveredModels.sortedWith(
                compareBy<ModelOption>(
                    { opt ->
                        val idx = priorityOrder.indexOf(opt.id)
                        if (idx >= 0) idx else Int.MAX_VALUE
                    },
                    { it.id }
                )
            ).forEach { result.add(it.id) }
        } else {
            if (cleanPreferred.isNotEmpty()) result.add(cleanPreferred)
            if (cleanFallback.isNotEmpty()) result.add(cleanFallback)
            priorityOrder.forEach { result.add(it) }
        }
        return result.toList()
    }

    private suspend fun connectAndVerifySetup(
        modelId: String,
        resumptionHandle: String?
    ): SetupOutcome {
        val keys = crypto.getGeminiKeys()
        if (keys.isEmpty()) return SetupOutcome.AuthOrQuotaError("No Gemini API key configured")

        var lastFailure: SetupOutcome = SetupOutcome.Failed("Setup timed out")

        for (attempt in keys.indices) {
            if (!sessionRequested.get()) return SetupOutcome.Failed("Session cancelled")
            val pair = crypto.nextGeminiKey() ?: break
            val (keyIdx, rawKey) = pair
            val apiKey = rawKey.trim()
            if (apiKey.isEmpty()) continue

            val deferred = CompletableDeferred<SetupOutcome>()
            setupDeferred = deferred

            runCatching { webSocket?.close(1000, "Switching attempt") }
            webSocket = null

            val req = Request.Builder()
                .url(LIVE_WS_URL)
                .addHeader("x-goog-api-key", apiKey)
                .build()

            val socket = okHttpClient.newWebSocket(req, createWebSocketListener(modelId, keyIdx, resumptionHandle))
            webSocket = socket

            val outcome = withTimeoutOrNull(SETUP_TIMEOUT_MS) {
                deferred.await()
            } ?: SetupOutcome.Failed("Timed out waiting for setupComplete")

            when (outcome) {
                is SetupOutcome.Ready -> return outcome
                is SetupOutcome.AuthOrQuotaError -> {
                    lastFailure = outcome
                    val rotated = crypto.markGeminiKeyQuotaLimited(keyIdx)
                    if (!rotated) return outcome
                }
                is SetupOutcome.Failed -> {
                    lastFailure = outcome
                    // Model error doesn't need key rotation
                    return outcome
                }
            }
        }
        return lastFailure
    }

    private fun createWebSocketListener(
        modelId: String,
        keyIdx: Int,
        resumptionHandle: String?
    ): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val effectiveHandle = resumptionHandle?.takeIf { it.isNotBlank() }
                    ?: latestResumptionHandle.takeIf { it.isNotBlank() }
                val setupPayload = buildSetupJson(
                    modelId = modelId,
                    personaPrompt = activePersonaPrompt,
                    voiceProfile = activeVoiceProfile,
                    resumptionHandle = effectiveHandle
                )
                webSocket.send(setupPayload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingJson(text, keyIdx)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingJson(bytes.utf8(), keyIdx)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val safeReason = sanitizeError(reason)
                setupDeferred?.let {
                    if (!it.isCompleted) {
                        val isAuth = code == 1008 || safeReason.contains("quota", ignoreCase = true) ||
                            safeReason.contains("key", ignoreCase = true) ||
                            safeReason.contains("permission", ignoreCase = true)
                        it.complete(
                            if (isAuth) SetupOutcome.AuthOrQuotaError(safeReason.ifBlank { "Closed ($code)" })
                            else SetupOutcome.Failed(safeReason.ifBlank { "Closed ($code)" })
                        )
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val safeReason = sanitizeError(reason)
                setupDeferred?.let {
                    if (!it.isCompleted) {
                        it.complete(SetupOutcome.Failed(safeReason.ifBlank { "Connection closed ($code)" }))
                        return
                    }
                }
                if (sessionRequested.get() && code != 1000) {
                    attemptSessionResumption("Closed ($code): $safeReason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val safeReason = sanitizeError(t.message ?: "WebSocket failure (HTTP $code)")
                setupDeferred?.let {
                    if (!it.isCompleted) {
                        if (code == 401 || code == 403 || code == 429) {
                            it.complete(SetupOutcome.AuthOrQuotaError("HTTP $code: $safeReason"))
                        } else {
                            it.complete(SetupOutcome.Failed(safeReason))
                        }
                        return
                    }
                }
                if (sessionRequested.get()) {
                    if (code == 429 || code == 403) {
                        crypto.markGeminiKeyQuotaLimited(keyIdx)
                    }
                    attemptSessionResumption(safeReason)
                }
            }
        }
    }

    private fun attemptSessionResumption(reason: String) {
        if (!sessionRequested.get()) return
        val handle = latestResumptionHandle
        val attempt = resumptionAttempts.incrementAndGet()
        if (handle.isNotBlank() && attempt <= MAX_RESUMPTION_ATTEMPTS && activeModelId.isNotBlank()) {
            _events.tryEmit(
                GeminiLiveEvent.StatusNotice("Connection dropped ($reason) — resuming Live session ($attempt/$MAX_RESUMPTION_ATTEMPTS)...")
            )
            scope.launch {
                delay(450L * attempt)
                if (!sessionRequested.get()) return@launch
                val outcome = connectAndVerifySetup(activeModelId, handle)
                if (outcome is SetupOutcome.Ready) {
                    resumptionAttempts.set(0)
                    _events.tryEmit(GeminiLiveEvent.Connected(activeModelId, resumed = true))
                } else {
                    val failMsg = when (outcome) {
                        is SetupOutcome.AuthOrQuotaError -> outcome.reason
                        is SetupOutcome.Failed -> outcome.reason
                        else -> "Unable to resume"
                    }
                    stopSessionInternal(emitEnded = false)
                    _events.tryEmit(
                        GeminiLiveEvent.Failed(
                            "Live session interrupted ($failMsg). Falling back to classic pipeline.",
                            shouldFallbackToClassic = true
                        )
                    )
                }
            }
        } else {
            stopSessionInternal(emitEnded = false)
            _events.tryEmit(
                GeminiLiveEvent.Failed(
                    "Live connection lost ($reason). Falling back to classic voice pipeline.",
                    shouldFallbackToClassic = true
                )
            )
        }
    }

    private fun handleIncomingJson(rawText: String, keyIdx: Int) {
        runCatching {
            val root = JSONObject(rawText)

            if (root.has("setupComplete")) {
                setupDeferred?.let { if (!it.isCompleted) it.complete(SetupOutcome.Ready) }
                return
            }

            root.optJSONObject("error")?.let { errObj ->
                val code = errObj.optInt("code", 0)
                val msg = sanitizeError(errObj.optString("message", "Live API error"))
                setupDeferred?.let {
                    if (!it.isCompleted) {
                        if (code == 429 || code == 403 || code == 401 || msg.contains("quota", ignoreCase = true)) {
                            it.complete(SetupOutcome.AuthOrQuotaError(msg))
                        } else {
                            it.complete(SetupOutcome.Failed(msg))
                        }
                        return
                    }
                }
                if (code == 429 || code == 403) {
                    crypto.markGeminiKeyQuotaLimited(keyIdx)
                }
                _events.tryEmit(GeminiLiveEvent.StatusNotice("Live API notice: $msg"))
                return
            }

            root.optJSONObject("sessionResumptionUpdate")?.let { upd ->
                val newHandle = upd.optString("newHandle", "")
                if (newHandle.isNotBlank()) {
                    latestResumptionHandle = newHandle
                }
            }

            if (root.has("goAway") && latestResumptionHandle.isNotBlank() && sessionRequested.get()) {
                attemptSessionResumption("Server requested reconnect (goAway)")
                return
            }

            root.optJSONObject("toolCallCancellation")?.let { cancelObj ->
                val ids = cancelObj.optJSONArray("ids")
                if (ids != null) {
                    for (i in 0 until ids.length()) {
                        cancelledToolCallIds.add(ids.optString(i))
                    }
                }
            }

            root.optJSONObject("toolCall")?.let { tcObj ->
                val calls = tcObj.optJSONArray("functionCalls")
                if (calls != null && calls.length() > 0) {
                    scope.launch {
                        executeToolCallsAndReply(calls)
                    }
                }
            }

            root.optJSONObject("serverContent")?.let { sc ->
                if (sc.optBoolean("interrupted", false)) {
                    interruptPlaybackImmediate()
                    flushPendingOutputTurn()
                }

                sc.optJSONObject("inputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let { chunk ->
                    // New user speech arrived; flush any lingering prior assistant output first
                    if (outputTurnBuffer.isNotEmpty()) {
                        flushPendingOutputTurn()
                    }
                    inputTurnBuffer.append(chunk)
                    inputCommittedForTurn = false
                    _events.tryEmit(GeminiLiveEvent.InputTranscript(inputTurnBuffer.toString(), isTurnFinal = false))
                }

                sc.optJSONObject("outputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }?.let { chunk ->
                    commitPendingInputTurnIfNeeded()
                    outputTurnBuffer.append(chunk)
                    _events.tryEmit(GeminiLiveEvent.OutputTranscript(outputTurnBuffer.toString(), isTurnFinal = false))
                }

                val modelTurnParts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
                if (modelTurnParts != null) {
                    for (i in 0 until modelTurnParts.length()) {
                        val part = modelTurnParts.optJSONObject(i) ?: continue
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val mime = inlineData.optString("mimeType", "")
                            val b64 = inlineData.optString("data", "")
                            if (mime.startsWith("audio/") && b64.isNotEmpty()) {
                                val pcm = Base64.decode(b64, Base64.DEFAULT)
                                if (pcm.isNotEmpty()) {
                                    commitPendingInputTurnIfNeeded()
                                    enqueuePcmAudio(pcm)
                                }
                            }
                        } else if (!part.optBoolean("thought", false)) {
                            val text = part.optString("text", "")
                            if (text.isNotEmpty() && outputTurnBuffer.isEmpty()) {
                                commitPendingInputTurnIfNeeded()
                                outputTurnBuffer.append(text)
                                _events.tryEmit(GeminiLiveEvent.OutputTranscript(outputTurnBuffer.toString(), isTurnFinal = false))
                            }
                        }
                    }
                }

                val turnComplete = sc.optBoolean("turnComplete", false) || sc.optBoolean("generationComplete", false)
                if (turnComplete) {
                    commitPendingInputTurnIfNeeded()
                    flushPendingOutputTurn()
                }
            }
        }
    }

    private fun commitPendingInputTurnIfNeeded() {
        val text = inputTurnBuffer.toString().trim()
        if (text.isNotEmpty() && !inputCommittedForTurn) {
            inputCommittedForTurn = true
            _events.tryEmit(GeminiLiveEvent.InputTranscript(text, isTurnFinal = true))
            inputTurnBuffer.clear()
        }
    }

    private fun flushPendingOutputTurn() {
        val text = outputTurnBuffer.toString().trim()
        if (text.isNotEmpty()) {
            _events.tryEmit(GeminiLiveEvent.OutputTranscript(text, isTurnFinal = true))
            outputTurnBuffer.clear()
        }
    }

    private fun clearTurnBuffers() {
        inputTurnBuffer.clear()
        outputTurnBuffer.clear()
        inputCommittedForTurn = false
        cancelledToolCallIds.clear()
    }

    private suspend fun executeToolCallsAndReply(calls: JSONArray) {
        val responses = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val callId = call.optString("id", "")
            val fnName = call.optString("name", "")
            val args = call.optJSONObject("args") ?: JSONObject()

            if (callId.isNotBlank() && cancelledToolCallIds.remove(callId)) {
                continue
            }

            val result = if (safetyPolicy.policy.value.emergencyStopped) {
                "Blocked: Emergency STOP is active."
            } else {
                executeSingleAgentTool(fnName, args)
            }

            runCatching {
                actionLogDao.insert(
                    ActionLogEntity(
                        category = "LIVE_TOOL",
                        title = fnName,
                        detail = sanitizeError(result).take(200),
                        status = if (result.startsWith("Blocked")) "BLOCKED" else "SUCCESS"
                    )
                )
            }
            _events.tryEmit(GeminiLiveEvent.ToolAction(fnName, sanitizeError(result)))

            if (callId.isNotBlank() && cancelledToolCallIds.remove(callId)) {
                continue
            }

            responses.put(
                JSONObject()
                    .put("id", callId)
                    .put("name", fnName)
                    .put("response", JSONObject().put("result", result))
            )
        }

        if (responses.length() > 0 && sessionRequested.get()) {
            val payload = JSONObject().put(
                "toolResponse",
                JSONObject().put("functionResponses", responses)
            )
            runCatching { webSocket?.send(payload.toString()) }
        }
    }

    private suspend fun executeSingleAgentTool(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "open_app" -> phoneControl.openApp(args.optString("app_name"))
                "toggle_flashlight" -> {
                    val state = when (args.optString("state", "toggle").lowercase()) {
                        "on" -> true
                        "off" -> false
                        else -> null
                    }
                    phoneControl.toggleFlashlight(state)
                }
                "get_system_status" -> phoneControl.getSystemStatusSummary()
                "get_weather" -> phoneControl.fetchWeather(args.optString("city", "Karachi"))
                "web_search" -> phoneControl.webSearch(args.optString("query"))
                "initiate_call" -> phoneControl.initiateCall(args.optString("phone_number"))
                "send_sms" -> phoneControl.sendSms(args.optString("phone_number"), args.optString("message"))
                "send_whatsapp" -> phoneControl.sendWhatsApp(args.optString("recipient"), args.optString("message"))
                "control_media" -> phoneControl.controlMedia(args.optString("action", "toggle"))
                "play_youtube" -> phoneControl.playYouTube(args.optString("query"))
                "play_spotify" -> phoneControl.playSpotify(args.optString("query"))
                "set_volume" -> phoneControl.setVolumePercent(args.optInt("percent", 50))
                "set_brightness" -> phoneControl.setBrightnessPercent(args.optInt("percent", 50))
                "set_alarm" -> phoneControl.setAlarm(args.optInt("hour", 7), args.optInt("minute", 0), args.optString("label", "Zehn Alarm"))
                "set_timer" -> phoneControl.setTimer(args.optInt("seconds", 60), args.optString("label", "Zehn Timer"))
                "read_notifications" -> {
                    val items = ZehnNotificationListenerService.recentNotifications.value.take(5)
                    if (items.isEmpty()) "No recent notifications captured."
                    else items.joinToString("\n") { "• [${it.title}] ${it.body}" }
                }
                "get_clipboard" -> phoneControl.getClipboardText().ifBlank { "Clipboard is empty." }
                "observe_screen" -> {
                    val svc = ZehnAccessibilityService.instance.value
                        ?: return "Accessibility Service is disconnected. Enable Zehn AI Accessibility in Android Settings."
                    val obs = svc.observeScreenStructured()
                    if (!obs.error.isNullOrBlank()) {
                        obs.error
                    } else {
                        buildString {
                            append("App: ${obs.packageName} | Nodes: ${obs.totalNodeCount} | Clickable: ${obs.clickableElements.size} | Scrollable: ${obs.scrollableContainers.size}\n")
                            append("Visible text: ${obs.screenText.take(20).joinToString(" | ")}")
                        }
                    }
                }
                "click_screen_element" -> {
                    val target = args.optString("target")
                    val svc = ZehnAccessibilityService.instance.value
                        ?: return "Accessibility Service is disconnected. Enable Zehn AI Accessibility first."
                    when (val res = svc.tapNodeByTextVerified(target)) {
                        is ZehnAccessibilityService.TapOutcome.Success -> res.message
                        is ZehnAccessibilityService.TapOutcome.Blocked -> res.reason
                        is ZehnAccessibilityService.TapOutcome.Failed -> res.reason
                    }
                }
                "scroll_screen" -> {
                    val dirRaw = args.optString("direction", "down").lowercase()
                    val svc = ZehnAccessibilityService.instance.value
                        ?: return "Accessibility Service is disconnected. Enable Zehn AI Accessibility first."
                    val dir = if (dirRaw.contains("up")) ScrollDirection.UP else ScrollDirection.DOWN
                    svc.scrollDirection(dir, args.optString("container_hint").ifBlank { null }).message
                }
                "swipe_screen" -> {
                    val dir = args.optString("direction", "left").lowercase()
                    val svc = ZehnAccessibilityService.instance.value
                        ?: return "Accessibility Service is disconnected. Enable Zehn AI Accessibility first."
                    svc.performVerifiedSwipe(dir).message
                }
                "long_press_screen" -> {
                    val target = args.optString("target").ifBlank { null }
                    val svc = ZehnAccessibilityService.instance.value
                        ?: return "Accessibility Service is disconnected. Enable Zehn AI Accessibility first."
                    svc.longPressNodeOrPoint(query = target).message
                }
                "navigate_back" -> {
                    val svc = ZehnAccessibilityService.instance.value
                        ?: return "Accessibility Service is disconnected."
                    if (svc.performBack()) "Navigated Back" else "Back action failed"
                }
                "capture_screen_ocr" -> {
                    val mgr = screenVisionManager
                        ?: return "Screen Vision Manager unavailable."
                    val res = mgr.captureAndAnalyzeScreen(useVisionModel = false)
                    if (res.error != null) res.error
                    else "Method: ${res.method}\nOCR Text:\n${res.ocrText.take(1200)}"
                }
                "run_agent_goal" -> {
                    val goal = args.optString("goal")
                    agentLoop.tryExecuteCommand(goal) ?: agentLoop.runGuiLoop(goal)
                }
                else -> "Unknown tool '$name'."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Tool '$name' failed: ${sanitizeError(e.message ?: "Unexpected error")}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureAndPlayback() {
        micJob?.cancel()
        playbackJob?.cancel()
        watchdogJob?.cancel()

        pcmPlaybackChannel.close()
        pcmPlaybackChannel = Channel(capacity = Channel.UNLIMITED)

        // 24 kHz PCM AudioTrack playback with small low-latency buffer
        playbackJob = scope.launch {
            val outRate = 24000
            val minOutBuf = AudioTrack.getMinBufferSize(
                outRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4800) // ~100ms small buffer at 24kHz mono 16-bit

            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(outRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minOutBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: run {
                stopSessionInternal(emitEnded = false)
                _events.tryEmit(
                    GeminiLiveEvent.Failed(
                        "Unable to initialize 24 kHz AudioTrack playback.",
                        shouldFallbackToClassic = true
                    )
                )
                return@launch
            }

            audioTrack = track
            runCatching { track.play() }

            try {
                for ((gen, bytes) in pcmPlaybackChannel) {
                    if (!sessionRequested.get() || !isActive) break
                    if (gen != playbackGeneration.get()) continue
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching { track.play() }
                    }
                    if (!isPlaybackActive.getAndSet(true)) {
                        _events.tryEmit(GeminiLiveEvent.PlaybackState(true))
                    }
                    var offset = 0
                    while (offset < bytes.size && sessionRequested.get() && gen == playbackGeneration.get()) {
                        val written = track.write(bytes, offset, bytes.size - offset)
                        if (written <= 0) break
                        offset += written
                    }
                }
            } catch (_: CancellationException) {
            } finally {
                isPlaybackActive.set(false)
                _events.tryEmit(GeminiLiveEvent.PlaybackState(false))
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.stop() }
                runCatching { track.release() }
                audioTrack = null
            }
        }

        // Watchdog to transition orb state back to LISTENING smoothly after audio tail drains
        @OptIn(ExperimentalCoroutinesApi::class)
        watchdogJob = scope.launch {
            while (sessionRequested.get() && isActive) {
                delay(220)
                if (isPlaybackActive.get()) {
                    val idleMs = System.currentTimeMillis() - lastAudioEnqueueTimeMs.get()
                    if (idleMs > 650L && pcmPlaybackChannel.isEmpty) {
                        if (isPlaybackActive.getAndSet(false)) {
                            _events.tryEmit(GeminiLiveEvent.PlaybackState(false))
                        }
                    }
                }
            }
        }

        // 16 kHz PCM Mic capture -> Base64 realtimeInput.audio
        micJob = scope.launch {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _events.tryEmit(GeminiLiveEvent.Failed("Microphone permission required.", shouldFallbackToClassic = false))
                return@launch
            }

            val inRate = 16000
            val frameSamples = 512 // 32ms frame @ 16kHz
            val frameBytes = frameSamples * 2
            val minInBuf = AudioRecord.getMinBufferSize(
                inRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(frameBytes * 4)

            val recorder = createBestAudioRecord(inRate, minInBuf) ?: run {
                _events.tryEmit(
                    GeminiLiveEvent.Failed(
                        "Unable to initialize 16 kHz AudioRecord microphone.",
                        shouldFallbackToClassic = true
                    )
                )
                return@launch
            }

            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
                }
            }

            val pcmBuffer = ByteArray(frameBytes)
            var highEnergySpeechFrames = 0

            try {
                recorder.startRecording()
                while (sessionRequested.get() && isActive) {
                    val read = recorder.read(pcmBuffer, 0, frameBytes)
                    if (read <= 0) continue

                    val micLevel = computeNormalizedRms(pcmBuffer, read)
                    _events.tryEmit(GeminiLiveEvent.AudioLevel(micLevel))

                    // Instant local barge-in when user speaks clearly over playback
                    if (isPlaybackActive.get()) {
                        if (micLevel > 0.62f) {
                            highEnergySpeechFrames++
                            if (highEnergySpeechFrames >= 3) {
                                highEnergySpeechFrames = 0
                                interruptPlaybackImmediate()
                            }
                        } else {
                            highEnergySpeechFrames = 0
                        }
                    } else {
                        highEnergySpeechFrames = 0
                    }

                    val b64 = Base64.encodeToString(pcmBuffer, 0, read, Base64.NO_WRAP)
                    val frameJson = JSONObject().put(
                        "realtimeInput",
                        JSONObject().put(
                            "audio",
                            JSONObject()
                                .put("mimeType", "audio/pcm;rate=16000")
                                .put("data", b64)
                        )
                    )
                    webSocket?.send(frameJson.toString())
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                if (sessionRequested.get()) {
                    _events.tryEmit(GeminiLiveEvent.StatusNotice("Mic stream error: ${sanitizeError(e.message ?: "failed")}"))
                }
            } finally {
                _events.tryEmit(GeminiLiveEvent.AudioLevel(0f))
                runCatching { aec?.release() }
                runCatching { ns?.release() }
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createBestAudioRecord(sampleRate: Int, minBuf: Int): AudioRecord? {
        val sources = listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        for (src in sources) {
            val rec = runCatching {
                AudioRecord(
                    src,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            runCatching { rec?.release() }
        }
        return null
    }

    private fun computeNormalizedRms(pcmBytes: ByteArray, length: Int): Float {
        val sampleCount = length / 2
        if (sampleCount <= 0) return 0f
        var sumSq = 0.0
        var i = 0
        while (i + 1 < length) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sumSq += sample * sample
            i += 2
        }
        val rms = sqrt(sumSq / sampleCount).toFloat()
        return ((rms - 120f) / 3800f).coerceIn(0f, 1f)
    }

    private fun enqueuePcmAudio(pcm: ByteArray) {
        lastAudioEnqueueTimeMs.set(System.currentTimeMillis())
        pcmPlaybackChannel.trySend(playbackGeneration.get() to pcm)
    }

    fun interruptPlaybackImmediate() {
        playbackGeneration.incrementAndGet()
        while (pcmPlaybackChannel.tryReceive().isSuccess) {
            // drain queued stale audio frames
        }
        runCatching {
            audioTrack?.let { tr ->
                tr.pause()
                tr.flush()
                tr.play()
            }
        }
        if (isPlaybackActive.getAndSet(false)) {
            _events.tryEmit(GeminiLiveEvent.PlaybackState(false))
        }
    }

    fun stopSession() {
        stopSessionInternal(emitEnded = true)
    }

    private fun stopSessionInternal(emitEnded: Boolean) {
        sessionRequested.set(false)
        _isSessionActive.value = false
        connectJob?.cancel()
        connectJob = null
        micJob?.cancel()
        micJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        interruptPlaybackImmediate()
        playbackJob?.cancel()
        playbackJob = null
        setupDeferred?.let { if (!it.isCompleted) it.complete(SetupOutcome.Failed("Stopped")) }
        setupDeferred = null
        runCatching { webSocket?.close(1000, "Client stopped") }
        webSocket = null
        commitPendingInputTurnIfNeeded()
        flushPendingOutputTurn()
        clearTurnBuffers()
        latestResumptionHandle = ""
        resumptionAttempts.set(0)
        if (emitEnded) {
            _events.tryEmit(GeminiLiveEvent.SessionEnded)
        }
    }

    private fun buildSetupJson(
        modelId: String,
        personaPrompt: String,
        voiceProfile: VoiceProfile,
        resumptionHandle: String?
    ): JSONObject {
        val fullModelName = if (modelId.startsWith("models/")) modelId else "models/$modelId"
        val prebuiltVoiceName = mapProfileToGeminiVoice(voiceProfile)

        val genConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "speechConfig",
                JSONObject().put(
                    "voiceConfig",
                    JSONObject().put(
                        "prebuiltVoiceConfig",
                        JSONObject().put("voiceName", prebuiltVoiceName)
                    )
                )
            )

        val setupObj = JSONObject().apply {
            put("model", fullModelName)
            put("generationConfig", genConfig)
            if (personaPrompt.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", personaPrompt)))
                )
            }
            put("tools", JSONArray().put(JSONObject().put("functionDeclarations", buildToolDeclarations())))
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            put(
                "sessionResumption",
                if (!resumptionHandle.isNullOrBlank()) JSONObject().put("handle", resumptionHandle) else JSONObject()
            )
        }
        return JSONObject().put("setup", setupObj)
    }

    private fun mapProfileToGeminiVoice(profile: VoiceProfile): String = when (profile) {
        VoiceProfile.AOEDE -> "Aoede"
        VoiceProfile.KORE -> "Kore"
        VoiceProfile.ZEPHYR -> "Zephyr"
        VoiceProfile.LEDA -> "Leda"
        VoiceProfile.CALLIRRHOE -> "Callirrhoe"
        VoiceProfile.AUTONOE -> "Autonoe"
        VoiceProfile.CHARON -> "Charon"
        VoiceProfile.PUCK -> "Puck"
        VoiceProfile.FENRIR -> "Fenrir"
        VoiceProfile.ORUS -> "Orus"
        else -> "Aoede"
    }

    private fun buildToolDeclarations(): JSONArray {
        fun tool(name: String, desc: String, props: Map<String, Pair<String, String>> = emptyMap(), required: List<String> = emptyList()): JSONObject {
            val propertiesObj = JSONObject()
            props.forEach { (k, typeAndDesc) ->
                propertiesObj.put(
                    k,
                    JSONObject().put("type", typeAndDesc.first).put("description", typeAndDesc.second)
                )
            }
            val params = JSONObject()
                .put("type", "OBJECT")
                .put("properties", propertiesObj)
            if (required.isNotEmpty()) {
                params.put("required", JSONArray(required))
            }
            return JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", params)
        }

        return JSONArray()
            .put(tool("open_app", "Open an installed Android app by name or package.", mapOf("app_name" to ("STRING" to "App name such as WhatsApp, YouTube, Spotify, Settings")), listOf("app_name")))
            .put(tool("toggle_flashlight", "Turn device flashlight on, off, or toggle.", mapOf("state" to ("STRING" to "on, off, or toggle"))))
            .put(tool("get_system_status", "Get battery percentage, free RAM, CPU cores, network state, and local time."))
            .put(tool("get_weather", "Fetch current weather for a city.", mapOf("city" to ("STRING" to "City name")), listOf("city")))
            .put(tool("web_search", "Search the web for quick facts.", mapOf("query" to ("STRING" to "Search query")), listOf("query")))
            .put(tool("initiate_call", "Start a phone call to a phone number (requires user confirmation if enabled).", mapOf("phone_number" to ("STRING" to "Phone number to dial")), listOf("phone_number")))
            .put(tool("send_sms", "Send an SMS message (requires user confirmation if enabled).", mapOf("phone_number" to ("STRING" to "Recipient number"), "message" to ("STRING" to "SMS body")), listOf("phone_number", "message")))
            .put(tool("send_whatsapp", "Send a WhatsApp message (requires user confirmation if enabled).", mapOf("recipient" to ("STRING" to "Contact name or phone number"), "message" to ("STRING" to "Message text")), listOf("recipient", "message")))
            .put(tool("control_media", "Control media playback: play, pause, toggle, next, or previous.", mapOf("action" to ("STRING" to "Media control command")), listOf("action")))
            .put(tool("play_youtube", "Search and open a video on YouTube.", mapOf("query" to ("STRING" to "Video search query")), listOf("query")))
            .put(tool("play_spotify", "Search and play music on Spotify.", mapOf("query" to ("STRING" to "Song or artist query")), listOf("query")))
            .put(tool("set_volume", "Set media volume percentage (0-100).", mapOf("percent" to ("INTEGER" to "Volume percentage 0..100")), listOf("percent")))
            .put(tool("set_brightness", "Set screen brightness percentage (5-100).", mapOf("percent" to ("INTEGER" to "Brightness percentage 5..100")), listOf("percent")))
            .put(tool("set_alarm", "Set an alarm on the clock app.", mapOf("hour" to ("INTEGER" to "Hour 0-23"), "minute" to ("INTEGER" to "Minute 0-59"), "label" to ("STRING" to "Alarm label")), listOf("hour", "minute")))
            .put(tool("set_timer", "Start a countdown timer.", mapOf("seconds" to ("INTEGER" to "Duration in seconds"), "label" to ("STRING" to "Timer label")), listOf("seconds")))
            .put(tool("read_notifications", "Read recent captured notifications on the phone."))
            .put(tool("get_clipboard", "Read current text from the Android clipboard."))
            .put(tool("observe_screen", "Read the structured UI tree, visible text, clickable buttons, and scrollable containers of the current screen."))
            .put(tool("click_screen_element", "Tap a visible UI button or item by its text or description.", mapOf("target" to ("STRING" to "Exact or partial label of the button or element to tap")), listOf("target")))
            .put(tool("scroll_screen", "Scroll the current screen or list up or down.", mapOf("direction" to ("STRING" to "up or down"), "container_hint" to ("STRING" to "Optional scrollable list name or hint")), listOf("direction")))
            .put(tool("swipe_screen", "Perform a directional touch swipe gesture across the screen.", mapOf("direction" to ("STRING" to "left, right, up, or down")), listOf("direction")))
            .put(tool("long_press_screen", "Long press a screen element by text label, or center of screen if omitted.", mapOf("target" to ("STRING" to "Optional label to long-press"))))
            .put(tool("navigate_back", "Press the Android system Back action via Accessibility."))
            .put(tool("capture_screen_ocr", "Capture visible screen pixels and extract text with on-device ML Kit OCR."))
            .put(tool("run_agent_goal", "Run a multi-step or compound Android accessibility GUI automation goal.", mapOf("goal" to ("STRING" to "Full natural-language goal such as 'Open WhatsApp and scroll down'")), listOf("goal")))
    }

    internal fun sanitizeError(raw: String): String {
        return raw
            .replace(Regex("""AIza[0-9A-Za-z\-_]{10,}"""), "[REDACTED_KEY]")
            .replace(Regex("""([?&]key=)[^&\s]+"""), "$1[REDACTED]")
            .replace(Regex("""Bearer\s+[0-9A-Za-z\-._~+/]+=*""", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
            .take(240)
    }

    companion object {
        private const val MODELS_REST_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val LIVE_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val SETUP_TIMEOUT_MS = 7_500L
        private const val MAX_RESUMPTION_ATTEMPTS = 3
    }
}
