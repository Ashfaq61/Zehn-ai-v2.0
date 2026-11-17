package com.zehn.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.text.InputType
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zehn.assistant.agent.AgentLoop
import com.zehn.assistant.agent.IntentClassifier
import com.zehn.assistant.agent.PhoneControlExecutor
import com.zehn.assistant.agent.PlanManager
import com.zehn.assistant.agent.ScreenCaptureService
import com.zehn.assistant.agent.ScreenVisionManager
import com.zehn.assistant.agent.ScreenshotPermissionStatus
import com.zehn.assistant.agent.VisionEngine
import com.zehn.assistant.agent.ZehnAccessibilityService
import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.CloudLlmClient
import com.zehn.assistant.ai.OnDeviceLiteRtManager
import com.zehn.assistant.ai.RagEngine
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.ZehnDatabase
import com.zehn.assistant.data.model.ExecutionPath
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.security.SafetyPolicyManager
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
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class ZehnV41AccessibilityRepairTest {

    private lateinit var context: Context
    private lateinit var db: ZehnDatabase
    private lateinit var safetyPolicy: SafetyPolicyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ZehnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        safetyPolicy = SafetyPolicyManager(context, Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `manifest declares exported accessibility service and mediaProjection capture service`() {
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS
        )

        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(perms.contains(Manifest.permission.FOREGROUND_SERVICE))
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertTrue(perms.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"))
        }

        val services = pkgInfo.services?.toList().orEmpty()
        val a11ySvc = services.firstOrNull { it.name == ZehnAccessibilityService::class.java.name }
        assertTrue("ZehnAccessibilityService must be registered in manifest", a11ySvc != null)
        assertTrue("ZehnAccessibilityService must have android:exported=\"true\"", a11ySvc!!.exported)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, a11ySvc.permission)

        val capSvc = services.firstOrNull { it.name == ScreenCaptureService::class.java.name }
        assertTrue("ScreenCaptureService must be registered in manifest", capSvc != null)
        assertFalse("ScreenCaptureService should not be exported", capSvc!!.exported)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            assertTrue(
                (capSvc.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0
            )
        }
    }

    @Test
    fun `accessibility service config xml sets gestures, window retrieval, and screenshot capabilities cleanly`() {
        val parser = context.resources.getXml(R.xml.accessibility_service_config)
        val attrs = mutableMapOf<String, String>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "accessibility-service") {
                for (i in 0 until parser.attributeCount) {
                    attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                }
            }
            eventType = parser.next()
        }
        assertEquals("true", attrs["canPerformGestures"])
        assertEquals("true", attrs["canRetrieveWindowContent"])
        assertEquals("true", attrs["canTakeScreenshot"])
        assertFalse(attrs["accessibilityFlags"]?.contains("flagRequestEnhancedWebAccessibility") == true)
    }

    @Test
    fun `accessibility permission enabled detection separates OS setting from bound connection`() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        assertFalse(ZehnAccessibilityService.isAccessibilityPermissionEnabled(context))

        val componentStr = ComponentName(context, ZehnAccessibilityService::class.java).flattenToString()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, componentStr)
        assertTrue(ZehnAccessibilityService.isAccessibilityPermissionEnabled(context))
        assertFalse(ZehnAccessibilityService.isConnected.value)

        val diag = ZehnAccessibilityService.refreshDiagnosticsSnapshot(context)
        assertTrue(diag.permissionEnabled)
        assertFalse(diag.serviceConnected)

        val shotStatus = ScreenCaptureService.getPermissionStatus(context)
        assertEquals(ScreenshotPermissionStatus.CONSENT_REQUIRED, shotStatus)
    }

    @Test
    fun `password input type and view id masking protect sensitive credential inputs`() {
        assertTrue(
            ZehnAccessibilityService.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
        )
        assertTrue(
            ZehnAccessibilityService.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
        )
        assertTrue(
            ZehnAccessibilityService.isPasswordInputType(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            )
        )
        assertFalse(
            ZehnAccessibilityService.isPasswordInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            )
        )
        assertTrue(ZehnAccessibilityService.isPasswordViewId("com.zehn.app:id/pin_input_box"))
        assertFalse(ZehnAccessibilityService.isPasswordViewId("com.zehn.app:id/search_bar"))
    }

    @Test
    fun `compound multilingual and screen GUI intents parse and extract targets accurately`() {
        val classifier = IntentClassifier()
        val phoneControl = PhoneControlExecutor(context, OkHttpClient(), safetyPolicy, db.actionLogDao())
        val agentLoop = AgentLoop(
            classifier, PlanManager(), VisionEngine(safetyPolicy), phoneControl, safetyPolicy, db.actionLogDao()
        )

        val compoundUrdu = classifier.classify("WhatsApp kholo aur chat list scroll karo")
        assertEquals("COMPOUND_GUI", compoundUrdu.action)
        assertEquals("whatsapp", compoundUrdu.param1)
        assertEquals(ExecutionPath.STANDARD_PATH, compoundUrdu.path)

        val compoundEn = classifier.classify("Open Settings and tap Wi-Fi")
        assertEquals("COMPOUND_GUI", compoundEn.action)
        assertEquals("settings", compoundEn.param1)
        assertEquals(ExecutionPath.STANDARD_PATH, compoundEn.path)

        val parsedSettingsClick = agentLoop.parseGuiIntent("Screen par Settings button click karo")
        assertEquals("CLICK_TARGET", parsedSettingsClick.actionKind)
        assertEquals("Settings", parsedSettingsClick.targetHint)

        val parsedLongPress = agentLoop.parseGuiIntent("Long press Wi-Fi")
        assertEquals("LONG_PRESS", parsedLongPress.actionKind)
        assertEquals("Wi-Fi", parsedLongPress.targetHint)

        assertEquals("SCROLL_DOWN", agentLoop.parseGuiIntent("chat list scroll karo").actionKind)
        assertEquals("SCROLL_UP", agentLoop.parseGuiIntent("scroll up").actionKind)
        assertEquals("SWIPE_LEFT", agentLoop.parseGuiIntent("swipe left").actionKind)
        assertEquals("SWIPE_RIGHT", agentLoop.parseGuiIntent("swipe right").actionKind)
        assertEquals("INSPECT_OCR", agentLoop.parseGuiIntent("capture screen OCR").actionKind)
    }

    @Test
    fun `agent loop and screen vision manager report honest failure when service disconnected`() = runBlocking {
        val okHttp = OkHttpClient()
        val crypto = KeystoreCryptoManager(context)
        val cloud = CloudLlmClient(context, okHttp, crypto, safetyPolicy, db.actionLogDao())
        val liteRt = OnDeviceLiteRtManager(context, okHttp, crypto, db.actionLogDao())
        val rag = RagEngine(context, db.projectRagDao())
        val prefs = PreferencesRepository(context)
        val router = AiEngineRouter(prefs, cloud, liteRt, rag, db.memoryDao(), db.skillDao(), db.actionLogDao())
        val visionMgr = ScreenVisionManager(context, safetyPolicy, router)

        val phoneControl = PhoneControlExecutor(context, okHttp, safetyPolicy, db.actionLogDao())
        val agentLoop = AgentLoop(
            IntentClassifier(), PlanManager(), VisionEngine(safetyPolicy), phoneControl, safetyPolicy, db.actionLogDao(), visionMgr
        )

        val obs = visionMgr.observeViaAccessibility()
        assertTrue(obs.error?.contains("not connected", ignoreCase = true) == true)

        val ocrRes = visionMgr.captureAndAnalyzeScreen(useVisionModel = false)
        assertEquals("UNAVAILABLE", ocrRes.method)
        assertTrue(ocrRes.error != null)

        val compoundRes = agentLoop.tryExecuteCommand("WhatsApp kholo aur chat list scroll karo")
        assertTrue(compoundRes?.contains("Accessibility Service is disabled", ignoreCase = true) == true)

        val logs = db.actionLogDao().observeRecent().first()
        assertTrue(logs.any { it.category == "AGENT_GUI" && it.status == "FAILED" })
    }
}
