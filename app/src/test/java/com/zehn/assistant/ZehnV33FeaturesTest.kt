package com.zehn.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zehn.assistant.agent.CallAssistantManager
import com.zehn.assistant.agent.IntentClassifier
import com.zehn.assistant.agent.PhoneControlExecutor
import com.zehn.assistant.agent.ZehnAccessibilityService
import com.zehn.assistant.ai.CloudLlmClient
import com.zehn.assistant.ai.OnDeviceLiteRtManager
import com.zehn.assistant.ai.OpenSourceDiscoveryClient
import com.zehn.assistant.ai.ProviderCatalog
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.ZehnDatabase
import com.zehn.assistant.data.model.AiProvider
import com.zehn.assistant.data.model.ExecutionPath
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.security.SafetyPolicyManager
import com.zehn.assistant.voice.SentenceStreamingTtsEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZehnV33FeaturesTest {

    private lateinit var context: Context
    private lateinit var db: ZehnDatabase
    private lateinit var crypto: KeystoreCryptoManager
    private lateinit var safetyPolicy: SafetyPolicyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ZehnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        crypto = KeystoreCryptoManager(context)
        crypto.clearAllSecrets()
        safetyPolicy = SafetyPolicyManager(context, Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `bilingual Roman Urdu and English intent classification routes accurately`() {
        val classifier = IntentClassifier()

        val wa = classifier.classify("WhatsApp kholo")
        assertEquals(ExecutionPath.FAST_PATH, wa.path)
        assertEquals("OPEN_APP", wa.action)
        assertEquals("whatsapp", wa.param1)

        val yt = classifier.classify("YouTube par lofi search karo")
        assertEquals(ExecutionPath.FAST_PATH, yt.path)
        assertEquals("YOUTUBE", yt.action)
        assertEquals("lofi", yt.param1)

        val google = classifier.classify("Google par weather today search karo")
        assertEquals(ExecutionPath.FAST_PATH, google.path)
        assertEquals("GOOGLE_SEARCH", google.action)
        assertEquals("weather today", google.param1)

        val cam = classifier.classify("Front camera open karo")
        assertEquals(ExecutionPath.FAST_PATH, cam.path)
        assertEquals("OPEN_CAMERA_UI", cam.action)
        assertEquals("front", cam.param1)
        assertEquals("false", cam.param2)

        val tiktok = classifier.classify("TikTok scroll karo")
        assertEquals(ExecutionPath.STANDARD_PATH, tiktok.path)
        assertEquals("TIKTOK_SCROLL", tiktok.action)

        assertEquals("STOP_ALL", classifier.classify("stop").action)
        assertEquals("STOP_ALL", classifier.classify("band karo").action)
        assertEquals("STOP_ALL", classifier.classify("ruk jao").action)
    }

    @Test
    fun `accessibility password node heuristic protects credential fields`() {
        assertTrue(ZehnAccessibilityService.isPasswordViewId("com.example:id/password_input"))
        assertTrue(ZehnAccessibilityService.isPasswordViewId("com.bank:id/pin_entry"))
        assertTrue(ZehnAccessibilityService.isPasswordViewId("com.auth:id/otp_code_field"))
        assertFalse(ZehnAccessibilityService.isPasswordViewId("com.example:id/search_query"))
        assertFalse(ZehnAccessibilityService.isPasswordViewId(null))
    }

    @Test
    fun `open source discovery parses GitHub, HuggingFace, and honors device RAM limits`() {
        val okHttp = OkHttpClient()
        val liteRt = OnDeviceLiteRtManager(context, okHttp, crypto, db.actionLogDao())
        val discovery = OpenSourceDiscoveryClient(okHttp, crypto, liteRt)

        val ghJson = """
            {
              "items": [
                {
                  "full_name": "alphacep/vosk-android-demo",
                  "description": "Offline speech recognition on Android",
                  "stargazers_count": 2150,
                  "language": "Kotlin",
                  "pushed_at": "2025-11-10T12:00:00Z",
                  "html_url": "https://github.com/alphacep/vosk-android-demo",
                  "license": { "spdx_id": "Apache-2.0" }
                },
                {
                  "full_name": "example/gpl-tool",
                  "description": "GPL utility",
                  "stargazers_count": 42,
                  "language": "Python",
                  "pushed_at": "2025-01-01T00:00:00Z",
                  "html_url": "https://github.com/example/gpl-tool",
                  "license": { "spdx_id": "GPL-3.0" }
                }
              ]
            }
        """.trimIndent()
        val repos = discovery.parseGitHubReposJson(ghJson)
        assertEquals(2, repos.size)
        assertEquals("Apache-2.0", repos[0].license)
        assertTrue(repos[0].fitAssessment.contains("High Fit"))
        assertTrue(repos[1].fitAssessment.contains("License Caution"))

        val hfJson = """
            [
              {
                "id": "google/gemma-3-2b-it",
                "pipeline_tag": "text-generation",
                "downloads": 120000,
                "likes": 950,
                "tags": ["license:gemma", "en", "multilingual"]
              }
            ]
        """.trimIndent()
        val modelsLowRam = discovery.parseHuggingFaceModelsJson(hfJson, deviceRamMb = 4096L)
        assertEquals(1, modelsLowRam.size)
        assertEquals("google/gemma-3-2b-it", modelsLowRam[0].modelId)
        assertEquals("gemma", modelsLowRam[0].license)
        assertTrue(modelsLowRam[0].hardwareFit.contains("Device Compatible"))

        val tooLarge = discovery.assessModelRamFit("meta-llama/Llama-3.3-70B-Instruct", deviceRamMb = 6144L)
        assertTrue(tooLarge.contains("Cloud Inference Only"))
    }

    @Test
    fun `call assistant detects VIP callers and honestly reports uplink restriction`() {
        val prefs = PreferencesRepository(context)
        val tts = SentenceStreamingTtsEngine(context)
        val callManager = CallAssistantManager(context, prefs, tts, db.actionLogDao())

        assertTrue(callManager.isVipCaller("Ammi", "+923001234567", "Ammi, Boss, 7890"))
        assertTrue(callManager.isVipCaller("Unknown", "+923210007890", "Ammi, 7890"))
        assertFalse(callManager.isVipCaller("Telemarketer", "+923009998877", "Ammi, 7890"))
        assertFalse(callManager.isVipCaller("Ammi", "+923001234567", ""))

        val caps = callManager.capabilityReport()
        assertFalse(caps.canInjectAudioIntoCellularUplink)
        assertTrue(caps.restrictionSummary.contains("non-root", ignoreCase = true))
    }

    @Test
    fun `cloud error sanitization redacts all provider tokens and keystore manages slots`() {
        val raw = "HTTP 401 ?key=AIzaSySecret12345 Bearer sk-proj-99999 gsk_abcd1234 hf_xyzToken999"
        val clean = CloudLlmClient.sanitizeError(raw)
        assertFalse(clean.contains("AIzaSySecret"))
        assertFalse(clean.contains("sk-proj-99999"))
        assertFalse(clean.contains("gsk_abcd1234"))
        assertFalse(clean.contains("hf_xyzToken999"))

        crypto.addGeminiKey("key-111")
        crypto.addGeminiKey("key-222")
        assertEquals(listOf("key-111", "key-222"), crypto.getGeminiKeys())

        crypto.updateGeminiKeyAt(0, "key-111-updated")
        assertEquals(listOf("key-111-updated", "key-222"), crypto.getGeminiKeys())

        crypto.removeGeminiKeyAt(0)
        assertEquals(listOf("key-222"), crypto.getGeminiKeys())

        crypto.saveSecret(KeystoreCryptoManager.KEY_HF_TOKEN, "hf_test_secret")
        assertTrue(crypto.hasSecret(KeystoreCryptoManager.KEY_HF_TOKEN))
        crypto.deleteSecret(KeystoreCryptoManager.KEY_HF_TOKEN)
        assertFalse(crypto.hasSecret(KeystoreCryptoManager.KEY_HF_TOKEN))
    }

    @Test
    fun `huggingface catalog and v3_3 preferences persist safely`() = runBlocking {
        val hfModels = ProviderCatalog.modelsFor(AiProvider.HUGGINGFACE)
        assertTrue(hfModels.isNotEmpty())

        val prefs = PreferencesRepository(context)
        prefs.updateSettings {
            it.copy(
                continuousConversationEnabled = true,
                callAutoAnswerEnabled = true,
                callAutoAnswerDelaySec = 6,
                vipContactsCsv = "Ammi, Doctor",
                voipSipModeEnabled = true,
                voipSipServerUri = "sip:pbx.example.com"
            )
        }
        val saved = prefs.settings.first()
        assertTrue(saved.continuousConversationEnabled)
        assertTrue(saved.callAutoAnswerEnabled)
        assertEquals(6, saved.callAutoAnswerDelaySec)
        assertEquals("Ammi, Doctor", saved.vipContactsCsv)
        assertTrue(saved.voipSipModeEnabled)

        val phoneControl = PhoneControlExecutor(context, OkHttpClient(), safetyPolicy, db.actionLogDao())
        val tikTokMissing = phoneControl.openApp("TikTok")
        assertTrue(tikTokMissing.contains("not installed", ignoreCase = true))
    }
}
