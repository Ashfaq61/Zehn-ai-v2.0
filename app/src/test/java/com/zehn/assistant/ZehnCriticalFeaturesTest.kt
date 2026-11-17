package com.zehn.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zehn.assistant.agent.AgentLoop
import com.zehn.assistant.agent.CallAssistantManager
import com.zehn.assistant.agent.IntentClassifier
import com.zehn.assistant.agent.PhoneControlExecutor
import com.zehn.assistant.agent.PlanManager
import com.zehn.assistant.agent.VisionEngine
import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.CloudLlmClient
import com.zehn.assistant.ai.OnDeviceLiteRtManager
import com.zehn.assistant.ai.ProviderCatalog
import com.zehn.assistant.ai.RagEngine
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.ConversationEntity
import com.zehn.assistant.data.db.DocumentChunkEntity
import com.zehn.assistant.data.db.MessageEntity
import com.zehn.assistant.data.db.ProjectEntity
import com.zehn.assistant.data.db.ZehnDatabase
import com.zehn.assistant.data.model.AiExecutionMode
import com.zehn.assistant.data.model.AiProvider
import com.zehn.assistant.data.model.ExecutionPath
import com.zehn.assistant.data.model.VoiceLanguage
import com.zehn.assistant.security.CrashRecoveryManager
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.security.SafetyPolicyManager
import com.zehn.assistant.voice.AndroidSttEngine
import com.zehn.assistant.voice.GeminiLiveAudioClient
import com.zehn.assistant.voice.LanguageDetector
import com.zehn.assistant.voice.RealtimeWebSocketClient
import com.zehn.assistant.voice.SentenceStreamingTtsEngine
import com.zehn.assistant.voice.SileroStyleVadDetector
import com.zehn.assistant.voice.VoicePipelineCoordinator
import com.zehn.assistant.data.model.VoiceProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZehnCriticalFeaturesTest {

    private lateinit var context: Context
    private lateinit var db: ZehnDatabase
    private lateinit var json: Json
    private lateinit var crypto: KeystoreCryptoManager
    private lateinit var safetyPolicy: SafetyPolicyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        db = Room.inMemoryDatabaseBuilder(context, ZehnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        crypto = KeystoreCryptoManager(context)
        crypto.clearAllSecrets()
        safetyPolicy = SafetyPolicyManager(context, json)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `ai chat persistence, rag citations, and offline missing model reporting`() = runBlocking {
        val convId = db.conversationDao().insert(
            ConversationEntity(title = "Release Check", providerName = "GEMINI", modelName = "gemini-2.5-flash")
        )
        db.messageDao().insert(
            MessageEntity(conversationId = convId, role = "user", content = "Summarize deployment steps")
        )
        val stored = db.messageDao().getForConversationOnce(convId)
        assertEquals(1, stored.size)
        assertEquals("Summarize deployment steps", stored.first().content)

        // Verify RAG SQLite chunk + cosine similarity citation lookup
        val rag = RagEngine(context, db.projectRagDao())
        val projId = db.projectRagDao().insertProject(
            ProjectEntity(name = "CI Docs", description = "Android build notes")
        )
        val vec = rag.computeEmbedding("GitHub Actions runs gradlew clean test assembleDebug")
        db.projectRagDao().insertChunks(
            listOf(
                DocumentChunkEntity(
                    projectId = projId,
                    documentName = "build_guide.txt",
                    sourceUri = "file:///build_guide.txt",
                    pageOrSection = 1,
                    chunkText = "GitHub Actions runs gradlew clean test assembleDebug and validates APK.",
                    embeddingCsv = vec.joinToString(",")
                )
            )
        )
        val citations = rag.queryCitations(projId, "assembleDebug gradlew")
        assertTrue(citations.isNotEmpty())
        assertEquals("build_guide.txt", citations.first().documentName)

        // Verify Offline AI router reports honest error when LiteRT model is not downloaded yet
        val prefs = PreferencesRepository(context)
        prefs.updateSettings { it.copy(executionMode = AiExecutionMode.OFFLINE) }
        val okHttp = OkHttpClient()
        val cloud = CloudLlmClient(context, okHttp, crypto, safetyPolicy, db.actionLogDao())
        val liteRt = OnDeviceLiteRtManager(context, okHttp, crypto, db.actionLogDao())
        val router = AiEngineRouter(
            prefs, cloud, liteRt, rag, db.memoryDao(), db.skillDao(), db.actionLogDao()
        )
        val events = router.routeAndStream(emptyList(), "Hello offline Zehn", projId).toList()
        assertTrue(events.any { it.error?.contains("not downloaded yet") == true })
    }

    @Test
    fun `api settings and gemini multi-key rotation handle empty and cooldown states`() = runBlocking {
        val prefs = PreferencesRepository(context)
        prefs.updateSettings {
            it.copy(activeProvider = AiProvider.ANTHROPIC, activeModel = ProviderCatalog.modelsFor(AiProvider.ANTHROPIC).first().id)
        }
        val current = prefs.settings.first()
        assertEquals(AiProvider.ANTHROPIC, current.activeProvider)

        // Verify Gemini multi-key rotation avoids quota-limited key
        crypto.saveGeminiKeys(listOf("key-alpha-111", "key-beta-222"))
        val first = crypto.nextGeminiKey()
        assertEquals(0 to "key-alpha-111", first)
        val rotated = crypto.markGeminiKeyQuotaLimited(0, cooldownMs = 60_000L)
        assertTrue(rotated)
        val second = crypto.nextGeminiKey()
        assertEquals(1 to "key-beta-222", second)

        crypto.clearAllSecrets()
        assertNull(crypto.nextGeminiKey())
    }

    @Test
    fun `microphone pipeline enforces emergency stop and detects languages`() = runBlocking {
        assertEquals(VoiceLanguage.ENGLISH, LanguageDetector.detect("Open settings", VoiceLanguage.AUTO))
        assertEquals(VoiceLanguage.URDU, LanguageDetector.detect("میرا موبائل چیک کریں", VoiceLanguage.AUTO))
        assertEquals(VoiceLanguage.HINDI, LanguageDetector.detect("मेरा अलार्म सेट करें", VoiceLanguage.AUTO))
        assertEquals(VoiceLanguage.HINGLISH, LanguageDetector.detect("Bhai wifi on kar do", VoiceLanguage.AUTO))

        val prefs = PreferencesRepository(context)
        val okHttp = OkHttpClient()
        val cloud = CloudLlmClient(context, okHttp, crypto, safetyPolicy, db.actionLogDao())
        val liteRt = OnDeviceLiteRtManager(context, okHttp, crypto, db.actionLogDao())
        val rag = RagEngine(context, db.projectRagDao())
        val router = AiEngineRouter(prefs, cloud, liteRt, rag, db.memoryDao(), db.skillDao(), db.actionLogDao())
        val phoneControl = PhoneControlExecutor(context, okHttp, safetyPolicy, db.actionLogDao())
        val agentLoop = AgentLoop(
            IntentClassifier(), PlanManager(), VisionEngine(safetyPolicy), phoneControl, safetyPolicy, db.actionLogDao()
        )
        val geminiLive = GeminiLiveAudioClient(
            context = context,
            okHttpClient = okHttp,
            crypto = crypto,
            safetyPolicy = safetyPolicy,
            agentLoop = agentLoop,
            phoneControl = phoneControl,
            actionLogDao = db.actionLogDao()
        )
        val coordinator = VoicePipelineCoordinator(
            vad = SileroStyleVadDetector(context),
            stt = AndroidSttEngine(context),
            tts = SentenceStreamingTtsEngine(context),
            realtimeWs = RealtimeWebSocketClient(okHttp, crypto),
            geminiLive = geminiLive,
            aiRouter = router,
            agentLoop = agentLoop,
            prefs = prefs,
            safetyPolicy = safetyPolicy
        )

        safetyPolicy.triggerEmergencyStop()
        coordinator.startVoiceTurn()
        assertTrue(coordinator.uiState.value.statusBanner.contains("Emergency STOP"))

        safetyPolicy.clearEmergencyStop()
        coordinator.bargeIn()
        assertTrue(coordinator.uiState.value.statusBanner.contains("Barge-in"))
    }

    @Test
    fun `accessibility setup, safety guards, and honest uninstalled app check`() = runBlocking {
        val vision = VisionEngine(safetyPolicy)
        assertEquals("Accessibility Service is not enabled.", vision.inspectScreenSummary())

        val phoneControl = PhoneControlExecutor(context, OkHttpClient(), safetyPolicy, db.actionLogDao())
        val agentLoop = AgentLoop(
            IntentClassifier(), PlanManager(), vision, phoneControl, safetyPolicy, db.actionLogDao()
        )
        // Standard GUI loop must honestly report Accessibility Service missing
        val guiResult = agentLoop.runGuiLoop("Tap Continue on screen")
        assertTrue(guiResult.contains("Accessibility Service"))

        // Opening an app not installed on Robolectric test device reports honestly
        val openMissing = phoneControl.openApp("Instagram")
        assertTrue(openMissing.contains("not installed"))

        val classifier = IntentClassifier()
        assertEquals(ExecutionPath.FAST_PATH, classifier.classify("Check battery status").path)
        assertEquals(ExecutionPath.STANDARD_PATH, classifier.classify("Tap Pay Now on screen").path)
    }

    @Test
    fun `call handling gracefully enforces runtime permissions and local timezone telemetry`() {
        val prefs = PreferencesRepository(context)
        val tts = SentenceStreamingTtsEngine(context)
        val callManager = CallAssistantManager(context, prefs, tts, db.actionLogDao())

        // Without ANSWER_PHONE_CALLS granted, returns honest guidance instead of crashing
        val answerMsg = callManager.answerIncomingCall()
        assertTrue(answerMsg.contains("permission", ignoreCase = true))
        val rejectMsg = callManager.rejectIncomingCall(sendAutoReply = false)
        assertTrue(rejectMsg.contains("permission", ignoreCase = true))

        val phoneControl = PhoneControlExecutor(context, OkHttpClient(), safetyPolicy, db.actionLogDao())
        val status = phoneControl.getSystemStatusSummary()
        assertTrue(status.contains("Battery:"))
        assertTrue(status.contains("Local Time:"))
    }

    @Test
    fun `crash recovery captures sanitized exceptions without leaking secrets and dismisses cleanly`() {
        val recovery = CrashRecoveryManager(context)
        recovery.dismissCrashReport()
        assertNull(recovery.lastCrashReport.value)

        recovery.recordUncaughtCrash(
            IllegalStateException("Network failed for key AIzaSyD1234567890ABCDEFGHIJ and sk-proj-999999999999")
        )
        val report = recovery.lastCrashReport.value
        assertNotNull(report)
        assertEquals("IllegalStateException", report?.exceptionType)
        assertFalse(report!!.sanitizedMessage.contains("AIzaSyD"))
        assertFalse(report.sanitizedMessage.contains("sk-proj"))
        assertTrue(report.sanitizedMessage.contains("[REDACTED_KEY]"))

        // Persists across re-instantiation until user dismisses
        val reloaded = CrashRecoveryManager(context)
        assertNotNull(reloaded.lastCrashReport.value)
        reloaded.dismissCrashReport()
        assertNull(CrashRecoveryManager(context).lastCrashReport.value)
    }

    @Test
    fun `gemini live defaults, models endpoint filtering, candidate ordering, and key redaction`() = runBlocking {
        val prefs = PreferencesRepository(context)
        val initial = prefs.settings.first()
        assertEquals(ProviderCatalog.DEFAULT_GEMINI_LIVE_MODEL, initial.geminiLiveModel)
        assertEquals(ProviderCatalog.FALLBACK_GEMINI_LIVE_MODEL, initial.geminiLiveFallbackModel)

        val okHttp = OkHttpClient()
        val phoneControl = PhoneControlExecutor(context, okHttp, safetyPolicy, db.actionLogDao())
        val agentLoop = AgentLoop(
            IntentClassifier(), PlanManager(), VisionEngine(safetyPolicy), phoneControl, safetyPolicy, db.actionLogDao()
        )
        val geminiLive = GeminiLiveAudioClient(
            context = context,
            okHttpClient = okHttp,
            crypto = crypto,
            safetyPolicy = safetyPolicy,
            agentLoop = agentLoop,
            phoneControl = phoneControl,
            actionLogDao = db.actionLogDao()
        )

        val modelsJson = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "supportedGenerationMethods": ["generateContent", "countTokens"]
                },
                {
                  "name": "models/gemini-2.5-flash-native-audio-preview-12-2025",
                  "displayName": "Gemini 2.5 Flash Native Audio",
                  "supportedGenerationMethods": ["bidiGenerateContent"]
                },
                {
                  "name": "models/gemini-3.1-flash-live-preview",
                  "displayName": "Gemini 3.1 Flash Live Preview",
                  "supportedGenerationMethods": ["bidiGenerateContent"]
                }
              ]
            }
        """.trimIndent()
        val parsed = geminiLive.parseLiveModelsResponse(modelsJson)
        assertEquals(2, parsed.size)
        assertEquals("gemini-3.1-flash-live-preview", parsed[0].id)
        assertEquals("gemini-2.5-flash-native-audio-preview-12-2025", parsed[1].id)

        val candidates = geminiLive.buildCandidateModelList(
            customModel = "custom-live-model",
            preferredModel = "gemini-3.1-flash-live-preview",
            fallbackModel = "gemini-2.5-flash-native-audio-preview-12-2025",
            discoveredModels = parsed,
            hasVerifiedServerList = true
        )
        assertEquals(
            listOf(
                "custom-live-model",
                "gemini-3.1-flash-live-preview",
                "gemini-2.5-flash-native-audio-preview-12-2025"
            ),
            candidates
        )

        val redacted = geminiLive.sanitizeError("Failed connecting ?key=AIzaSyAbCdEf1234567890 with Bearer secretToken123")
        assertFalse(redacted.contains("AIzaSyAbCdEf"))
        assertFalse(redacted.contains("secretToken123"))

        // Calling with empty keys/missing permission never throws
        val discoveredEmpty = geminiLive.discoverAvailableLiveModels()
        assertTrue(discoveredEmpty.isEmpty())
        geminiLive.startLiveSession(
            personaPrompt = "You are Zehn",
            voiceProfile = VoiceProfile.ARIA,
            preferredModel = ProviderCatalog.DEFAULT_GEMINI_LIVE_MODEL,
            fallbackModel = ProviderCatalog.FALLBACK_GEMINI_LIVE_MODEL,
            customModel = ""
        )
    }
}
