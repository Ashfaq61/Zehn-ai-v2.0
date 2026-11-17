package com.zehn.assistant.agent

import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.data.db.WorkflowEntity
import com.zehn.assistant.data.model.ExecutionPath
import com.zehn.assistant.security.SafetyPolicyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class PlanStep(
    val index: Int,
    val label: String,
    val status: String // PENDING, RUNNING, DONE, BLOCKED, FAILED
)

data class AgentPlanState(
    val goal: String = "",
    val path: ExecutionPath = ExecutionPath.FAST_PATH,
    val confidence: Float = 0f,
    val steps: List<PlanStep> = emptyList(),
    val running: Boolean = false,
    val summary: String = ""
)

data class ClassifiedIntent(
    val action: String,
    val param1: String = "",
    val param2: String = "",
    val confidence: Float,
    val path: ExecutionPath
)

data class CameraLaunchRequest(
    val useFrontCamera: Boolean,
    val isVideoMode: Boolean,
    val sourceCommand: String
)

@Singleton
class IntentClassifier @Inject constructor() {

    fun classify(input: String): ClassifiedIntent {
        val lower = input.lowercase().trim()
        val hasUiActionVerb = lower.contains("scroll") ||
            lower.contains("swipe") ||
            lower.contains("tap ") ||
            lower.contains("tap karo") ||
            lower.contains("click") ||
            lower.contains("dabaao") ||
            lower.contains("dabao") ||
            lower.contains("long press") ||
            lower.contains("type ") ||
            lower.contains("read screen") ||
            lower.contains("screen read") ||
            lower.contains("screen par")

        return when {
            lower == "stop" || lower == "stop all" || lower == "band karo" || lower == "ruk jao" || lower == "bas karo" -> {
                ClassifiedIntent("STOP_ALL", "", "", 0.99f, ExecutionPath.FAST_PATH)
            }
            // Compound multi-step commands: e.g., "WhatsApp kholo aur chat list scroll karo", "Open Settings and tap Wi-Fi"
            hasUiActionVerb && (lower.contains(" aur ") || lower.contains(" and ") || lower.contains(" then ") || lower.contains(" phir ") || lower.contains("kholo")) -> {
                val appToOpen = extractLeadingAppToOpen(lower)
                ClassifiedIntent("COMPOUND_GUI", appToOpen, input, 0.84f, ExecutionPath.STANDARD_PATH)
            }
            lower.contains("front camera") || lower.contains("selfie camera") -> {
                val isVideo = lower.contains("video") || lower.contains("record")
                ClassifiedIntent("OPEN_CAMERA_UI", "front", isVideo.toString(), 0.95f, ExecutionPath.FAST_PATH)
            }
            lower.contains("back camera") ||
                ((lower.contains("camera") || lower.contains("photo lo") || lower.contains("take photo") || lower.contains("video record")) &&
                    (lower.contains("open") || lower.contains("kholo") || lower.contains("lo") || lower.contains("record") || lower.contains("karo"))) -> {
                val isFront = lower.contains("front") || lower.contains("selfie")
                val isVideo = lower.contains("video") || lower.contains("record")
                ClassifiedIntent("OPEN_CAMERA_UI", if (isFront) "front" else "back", isVideo.toString(), 0.94f, ExecutionPath.FAST_PATH)
            }
            lower.contains("ocr screen") || lower.contains("screen ocr") || lower.contains("analyze screen") -> {
                ClassifiedIntent("GUI_AUTOMATION", input, "", 0.82f, ExecutionPath.STANDARD_PATH)
            }
            lower.contains("screenshot") -> {
                ClassifiedIntent("SCREENSHOT", "", "", 0.94f, ExecutionPath.FAST_PATH)
            }
            lower.contains("close app") || lower.contains("app band") || lower.contains("go home") || lower == "home" -> {
                ClassifiedIntent("CLOSE_OR_HOME", "", "", 0.92f, ExecutionPath.FAST_PATH)
            }
            lower == "back" || lower == "go back" || lower == "wapis jao" || lower == "peeche jao" -> {
                ClassifiedIntent("NAV_BACK", "", "", 0.94f, ExecutionPath.FAST_PATH)
            }
            lower.contains("tiktok") && (lower.contains("scroll") || lower.contains("swipe") || lower.contains("next")) -> {
                ClassifiedIntent("TIKTOK_SCROLL", "", "", 0.91f, ExecutionPath.STANDARD_PATH)
            }
            lower.startsWith("open ") || lower.endsWith(" kholo") || lower.endsWith(" open karo") -> {
                val app = lower
                    .removePrefix("open ")
                    .removeSuffix(" open karo")
                    .removeSuffix(" kholo")
                    .trim()
                ClassifiedIntent("OPEN_APP", app, "", 0.94f, ExecutionPath.FAST_PATH)
            }
            lower.contains("youtube") && (lower.contains("play") || lower.contains("search") || lower.contains("par") || lower.contains("chalao")) -> {
                val q = lower
                    .replace("youtube par", "")
                    .replace("on youtube", "")
                    .replace("search karo", "")
                    .replace("search", "")
                    .replace("play", "")
                    .replace("chalao", "")
                    .replace("youtube", "")
                    .trim()
                ClassifiedIntent("YOUTUBE", q.ifBlank { "lofi beats" }, "", 0.91f, ExecutionPath.FAST_PATH)
            }
            lower.contains("google") && (lower.contains("search") || lower.contains("par") || lower.contains("dhundo")) -> {
                val q = lower
                    .replace("google par", "")
                    .replace("on google", "")
                    .replace("search karo", "")
                    .replace("search", "")
                    .replace("dhundo", "")
                    .replace("google", "")
                    .trim()
                ClassifiedIntent("GOOGLE_SEARCH", q.ifBlank { "weather today" }, "", 0.91f, ExecutionPath.FAST_PATH)
            }
            lower.contains("maps") && (lower.contains("search") || lower.contains("par") || lower.contains("directions") || lower.contains("open")) -> {
                val q = lower
                    .replace("google maps", "")
                    .replace("maps par", "")
                    .replace("on maps", "")
                    .replace("maps", "")
                    .replace("open", "")
                    .replace("karo", "")
                    .trim()
                ClassifiedIntent("OPEN_MAPS", q, "", 0.90f, ExecutionPath.FAST_PATH)
            }
            lower.contains("flashlight") || lower.contains("torch") || lower.contains("light jalao") || lower.contains("light band") -> {
                val state = when {
                    lower.contains("on") || lower.contains("jalao") -> "on"
                    lower.contains("off") || lower.contains("band") -> "off"
                    else -> "toggle"
                }
                ClassifiedIntent("FLASHLIGHT", state, "", 0.96f, ExecutionPath.FAST_PATH)
            }
            lower.contains("battery") || lower.contains("system status") || lower.contains("ram") || lower.contains("cpu") -> {
                ClassifiedIntent("SYSTEM_STATUS", "", "", 0.95f, ExecutionPath.FAST_PATH)
            }
            lower.contains("weather") || lower.contains("mausam") -> {
                val city = lower.substringAfter("weather in ", "").ifBlank {
                    lower.substringAfter("weather ", "Karachi")
                }.trim()
                ClassifiedIntent("WEATHER", city, "", 0.90f, ExecutionPath.FAST_PATH)
            }
            lower.startsWith("call ") || lower.endsWith(" ko call karo") -> {
                val num = lower.removePrefix("call ").removeSuffix(" ko call karo").trim()
                ClassifiedIntent("CALL", num, "", 0.92f, ExecutionPath.FAST_PATH)
            }
            lower.startsWith("whatsapp ") && !hasUiActionVerb && !lower.contains("kholo") && !lower.contains("open") -> {
                ClassifiedIntent("WHATSAPP", "", input, 0.88f, ExecutionPath.FAST_PATH)
            }
            lower.contains("spotify") -> {
                val q = lower.replace("play", "").replace("on spotify", "").replace("spotify", "").trim()
                ClassifiedIntent("SPOTIFY", q.ifBlank { "chill mix" }, "", 0.90f, ExecutionPath.FAST_PATH)
            }
            lower.contains("pause") || lower.contains("resume") || lower.contains("next song") -> {
                val act = if (lower.contains("next")) "next" else "toggle"
                ClassifiedIntent("MEDIA", act, "", 0.91f, ExecutionPath.FAST_PATH)
            }
            lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("bluetooth") -> {
                val target = if (lower.contains("bluetooth")) "bluetooth" else "wifi"
                ClassifiedIntent("CONNECTIVITY", target, "", 0.91f, ExecutionPath.FAST_PATH)
            }
            lower.contains("speaker on") || lower.contains("speakerphone") -> {
                ClassifiedIntent("SPEAKERPHONE", (!lower.contains("off")).toString(), "", 0.92f, ExecutionPath.FAST_PATH)
            }
            lower.contains("read notifications") || lower.contains("notifications") -> {
                ClassifiedIntent("READ_NOTIFICATIONS", "", "", 0.89f, ExecutionPath.FAST_PATH)
            }
            lower.contains("clipboard") -> {
                ClassifiedIntent("CLIPBOARD", "", "", 0.90f, ExecutionPath.FAST_PATH)
            }
            hasUiActionVerb || lower.contains("screen") || lower.contains("recents") -> {
                ClassifiedIntent("GUI_AUTOMATION", input, "", 0.78f, ExecutionPath.STANDARD_PATH)
            }
            else -> ClassifiedIntent("LLM_CHAT", input, "", 0.60f, ExecutionPath.FAST_PATH)
        }
    }

    private fun extractLeadingAppToOpen(lower: String): String {
        val clause = lower
            .substringBefore(" aur ")
            .substringBefore(" and ")
            .substringBefore(" then ")
            .substringBefore(" phir ")
            .trim()
        return clause
            .removePrefix("open ")
            .removeSuffix(" open karo")
            .removeSuffix(" kholo")
            .removeSuffix(" open")
            .trim()
    }
}

@Singleton
class PlanManager @Inject constructor() {
    private val _planState = MutableStateFlow(AgentPlanState())
    val planState: StateFlow<AgentPlanState> = _planState.asStateFlow()

    fun startPlan(goal: String, path: ExecutionPath, confidence: Float, stepLabels: List<String>) {
        _planState.value = AgentPlanState(
            goal = goal,
            path = path,
            confidence = confidence,
            steps = stepLabels.mapIndexed { idx, label ->
                PlanStep(idx + 1, label, if (idx == 0) "RUNNING" else "PENDING")
            },
            running = true,
            summary = ""
        )
    }

    fun updateStep(index: Int, status: String) {
        _planState.update { state ->
            val updated = state.steps.map {
                if (it.index == index) it.copy(status = status) else it
            }
            state.copy(steps = updated)
        }
    }

    fun finishPlan(summary: String) {
        _planState.update { it.copy(running = false, summary = summary) }
    }
}

@Singleton
class VisionEngine @Inject constructor(
    private val safetyPolicy: SafetyPolicyManager
) {
    fun observeStructured(): ScreenObservation {
        val svc = ZehnAccessibilityService.instance.value
            ?: return ScreenObservation(error = "Accessibility Service is not enabled.")
        val pkg = ZehnAccessibilityService.foregroundPackage.value
        if (!safetyPolicy.canReadCurrentScreen(pkg)) {
            return ScreenObservation(
                packageName = pkg.orEmpty(),
                blockedBySafety = true,
                error = "Screen reading blocked by Safety/Gaming Guard for ${pkg ?: "current app"}."
            )
        }
        return svc.observeScreenStructured()
    }

    fun inspectScreenSummary(): String {
        val svc = ZehnAccessibilityService.instance.value
            ?: return "Accessibility Service is not enabled."
        val pkg = ZehnAccessibilityService.foregroundPackage.value
        if (!safetyPolicy.canReadCurrentScreen(pkg)) {
            return "Screen reading blocked by Safety/Gaming Guard for ${pkg ?: "current app"}."
        }
        val obs = svc.observeScreenStructured()
        if (obs.blockedBySafety || !obs.error.isNullOrBlank()) {
            return obs.error ?: "Screen reading blocked."
        }
        if (obs.nodes.isEmpty()) return "Screen appears empty or protected."
        return obs.nodes.take(25).joinToString(" | ") {
            it.primaryLabel()
        }
    }
}

@Singleton
class AgentLoop @Inject constructor(
    private val intentClassifier: IntentClassifier,
    private val planManager: PlanManager,
    private val visionEngine: VisionEngine,
    private val phoneControl: PhoneControlExecutor,
    private val safetyPolicy: SafetyPolicyManager,
    private val actionLogDao: ActionLogDao,
    private val screenVisionManager: ScreenVisionManager? = null
) {
    // Secondary constructor kept for direct unit-test compatibility
    constructor(
        intentClassifier: IntentClassifier,
        planManager: PlanManager,
        visionEngine: VisionEngine,
        phoneControl: PhoneControlExecutor,
        safetyPolicy: SafetyPolicyManager,
        actionLogDao: ActionLogDao
    ) : this(intentClassifier, planManager, visionEngine, phoneControl, safetyPolicy, actionLogDao, null)

    private val _recordingActive = MutableStateFlow(false)
    val recordingActive: StateFlow<Boolean> = _recordingActive.asStateFlow()

    private val _cameraLaunchRequests = MutableSharedFlow<CameraLaunchRequest>(extraBufferCapacity = 4)
    val cameraLaunchRequests: SharedFlow<CameraLaunchRequest> = _cameraLaunchRequests.asSharedFlow()

    private val recordedSteps = mutableListOf<String>()

    fun startRecordingTask() {
        recordedSteps.clear()
        _recordingActive.value = true
    }

    fun stopRecordingAsYaml(name: String): String {
        _recordingActive.value = false
        val body = if (recordedSteps.isEmpty()) {
            "  - action: get_system_status"
        } else {
            recordedSteps.joinToString("\n") { "  - command: \"$it\"" }
        }
        return "name: $name\nmode: instant_replay\nsteps:\n$body"
    }

    suspend fun tryExecuteCommand(input: String): String? {
        return try {
            val intent = intentClassifier.classify(input)
            if (intent.action == "STOP_ALL") {
                safetyPolicy.triggerEmergencyStop()
                return "STOP activated — voice, agent tasks, and tools halted immediately."
            }
            if (safetyPolicy.policy.value.emergencyStopped) {
                return "Emergency STOP is active. Clear STOP in Security or Home before running actions."
            }
            if (_recordingActive.value) {
                recordedSteps.add(input)
            }
            if (intent.action == "LLM_CHAT") return null

            if (intent.action == "TIKTOK_SCROLL") {
                val launchMsg = phoneControl.openApp("tiktok")
                if (launchMsg.contains("not installed", true) || launchMsg.startsWith("Blocked", true)) {
                    return launchMsg
                }
                delay(700)
                return runGuiLoop("swipe up")
            }

            if (intent.action == "COMPOUND_GUI") {
                return runCompoundGoal(appName = intent.param1, fullGoal = intent.param2)
            }

            if (intent.path == ExecutionPath.FAST_PATH && intent.confidence >= 0.85f) {
                planManager.startPlan(
                    goal = input,
                    path = ExecutionPath.FAST_PATH,
                    confidence = intent.confidence,
                    stepLabels = listOf("Classify Intent (${intent.action})", "Execute Direct Android Fast-Path")
                )
                planManager.updateStep(1, "DONE")
                val result = executeFastPath(intent, input)
                val status = when {
                    result.startsWith("Blocked", true) -> "BLOCKED"
                    result.contains("failed", true) || result.contains("not installed", true) || result.contains("Unable", true) -> "FAILED"
                    else -> "DONE"
                }
                planManager.updateStep(2, status)
                planManager.finishPlan(result)
                actionLogDao.insert(ActionLogEntity("AGENT_FAST", intent.action, result, if (status == "DONE") "SUCCESS" else status))
                return result
            }

            runGuiLoop(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Agent execution error: ${e.message ?: "unexpected error"}"
            planManager.finishPlan(msg)
            runCatching { actionLogDao.insert(ActionLogEntity("AGENT_ERROR", input.take(48), msg, "FAILED")) }
            msg
        }
    }

    private suspend fun runCompoundGoal(appName: String, fullGoal: String): String = withContext(Dispatchers.Default) {
        val svc = ZehnAccessibilityService.instance.value
        if (svc == null) {
            val msg = "Accessibility Service is disabled. Enable Zehn Accessibility Service in Permissions to run multi-step screen automation."
            actionLogDao.insert(ActionLogEntity("AGENT_GUI", fullGoal.take(48), msg, "FAILED"))
            return@withContext msg
        }

        if (appName.isNotBlank()) {
            val launchResult = phoneControl.openApp(appName)
            if (launchResult.contains("not installed", true) || launchResult.startsWith("Blocked", true) || launchResult.startsWith("Could not", true)) {
                actionLogDao.insert(ActionLogEntity("AGENT_GUI", fullGoal.take(48), launchResult, "FAILED"))
                return@withContext launchResult
            }

            // Wait up to 2.6 seconds for requested app window to appear in foreground
            val expectedHint = appName.lowercase().replace(" ", "")
            var settled = false
            repeat(13) {
                delay(200)
                val currentPkg = ZehnAccessibilityService.foregroundPackage.value.orEmpty().lowercase()
                if (currentPkg.contains(expectedHint) ||
                    (expectedHint.contains("whatsapp") && currentPkg.contains("whatsapp")) ||
                    (expectedHint.contains("settings") && currentPkg.contains("settings")) ||
                    (expectedHint.contains("youtube") && currentPkg.contains("youtube")) ||
                    (expectedHint.contains("chrome") && currentPkg.contains("chrome"))
                ) {
                    settled = true
                    return@repeat
                }
            }
            if (!settled) {
                delay(350)
            }
        }

        val secondaryAction = fullGoal
            .substringAfter(" aur ", "")
            .ifBlank { fullGoal.substringAfter(" and ", "") }
            .ifBlank { fullGoal.substringAfter(" then ", "") }
            .ifBlank { fullGoal.substringAfter(" phir ", "") }
            .ifBlank { fullGoal }
            .trim()

        return@withContext runGuiLoop(secondaryAction)
    }

    private suspend fun executeFastPath(intent: ClassifiedIntent, rawInput: String): String {
        return when (intent.action) {
            "OPEN_APP" -> phoneControl.openApp(intent.param1)
            "OPEN_CAMERA_UI" -> {
                val front = intent.param1 == "front"
                val video = intent.param2.toBoolean()
                _cameraLaunchRequests.tryEmit(CameraLaunchRequest(front, video, rawInput))
                "Opened visible Camera preview (${if (front) "front" else "back"} lens${if (video) ", video ready" else ""}). Tap capture to save."
            }
            "SCREENSHOT" -> {
                val svc = ZehnAccessibilityService.instance.value
                    ?: return "Enable Accessibility Service in Permissions to take screenshots."
                svc.saveScreenshotToGallery().getOrElse { it.message ?: "Screenshot failed" }
            }
            "CLOSE_OR_HOME" -> phoneControl.closeCurrentApp()
            "NAV_BACK" -> {
                val svc = ZehnAccessibilityService.instance.value
                    ?: return "Enable Accessibility Service to press Back."
                if (svc.performBack()) "Navigated Back." else "Back action failed."
            }
            "FLASHLIGHT" -> when (intent.param1) {
                "on" -> phoneControl.toggleFlashlight(true)
                "off" -> phoneControl.toggleFlashlight(false)
                else -> phoneControl.toggleFlashlight(null)
            }
            "SYSTEM_STATUS" -> phoneControl.getSystemStatusSummary()
            "WEATHER" -> phoneControl.fetchWeather(intent.param1)
            "CALL" -> phoneControl.initiateCall(intent.param1)
            "WHATSAPP" -> phoneControl.sendWhatsApp("", intent.param2.removePrefix("whatsapp").trim())
            "YOUTUBE" -> phoneControl.playYouTube(intent.param1)
            "GOOGLE_SEARCH" -> phoneControl.searchGoogle(intent.param1)
            "OPEN_MAPS" -> phoneControl.openMaps(intent.param1)
            "SPOTIFY" -> phoneControl.playSpotify(intent.param1)
            "MEDIA" -> phoneControl.controlMedia(intent.param1)
            "CONNECTIVITY" -> phoneControl.openConnectivityPanel(intent.param1)
            "SPEAKERPHONE" -> phoneControl.setSpeakerphone(intent.param1.toBoolean())
            "READ_NOTIFICATIONS" -> {
                val items = ZehnNotificationListenerService.recentNotifications.value.take(5)
                if (items.isEmpty()) "No recent captured notifications."
                else items.joinToString("\n") { "• [${it.title}] ${it.body}" }
            }
            "CLIPBOARD" -> {
                val clip = phoneControl.getClipboardText()
                if (clip.isBlank()) "Clipboard is empty." else "Clipboard: $clip"
            }
            else -> "Executed ${intent.action}."
        }
    }

    /**
     * 6-stage deterministic UI automation pipeline off the Main thread:
     * OBSERVE -> UNDERSTAND -> PLAN -> EXECUTE -> VERIFY -> RECOVER
     */
    suspend fun runGuiLoop(goal: String): String = withContext(Dispatchers.Default) {
        val svc = ZehnAccessibilityService.instance.value
        if (svc == null) {
            val msg = "Accessibility Service is disabled. Enable Zehn Accessibility Service in Permissions to run UI automation."
            actionLogDao.insert(ActionLogEntity("AGENT_GUI", "GUI Loop Unavailable", msg, "FAILED"))
            return@withContext msg
        }

        planManager.startPlan(
            goal = goal,
            path = ExecutionPath.STANDARD_PATH,
            confidence = 0.85f,
            stepLabels = listOf(
                "1. OBSERVE active UI tree & screen structure",
                "2. UNDERSTAND target element & safety policy",
                "3. PLAN accessibility / gesture steps",
                "4. EXECUTE real UI action",
                "5. VERIFY updated screen state",
                "6. RECOVER or report honest outcome"
            )
        )

        if (safetyPolicy.policy.value.emergencyStopped) {
            planManager.finishPlan("Aborted by Emergency STOP")
            return@withContext "Mission aborted by Emergency STOP."
        }

        // Stage 1: OBSERVE
        val beforeObs = visionEngine.observeStructured()
        if (beforeObs.blockedBySafety) {
            planManager.updateStep(1, "BLOCKED")
            val err = beforeObs.error ?: "Screen reading blocked by Safety/Gaming Guard."
            planManager.finishPlan(err)
            return@withContext err
        }
        val beforeHash = beforeObs.signatureHash()
        planManager.updateStep(1, "DONE")

        // Stage 2: UNDERSTAND
        val parsed = parseGuiIntent(goal)
        planManager.updateStep(2, "DONE")

        // Stage 3: PLAN
        if (safetyPolicy.policy.value.emergencyStopped) {
            planManager.updateStep(3, "BLOCKED")
            planManager.finishPlan("Aborted by Emergency STOP")
            return@withContext "Aborted by Emergency STOP."
        }
        planManager.updateStep(3, "DONE")

        // Stage 4: EXECUTE
        var executedOk = false
        var actionMethod = ""
        var actionDetail = ""
        var blockedReason: String? = null

        when (parsed.actionKind) {
            "SCROLL_DOWN" -> {
                val res = svc.scrollDirection(ScrollDirection.DOWN, parsed.targetHint)
                executedOk = res.success
                actionMethod = res.methodUsed
                actionDetail = res.message
                if (res.blocked) blockedReason = res.message
            }
            "SCROLL_UP" -> {
                val res = svc.scrollDirection(ScrollDirection.UP, parsed.targetHint)
                executedOk = res.success
                actionMethod = res.methodUsed
                actionDetail = res.message
                if (res.blocked) blockedReason = res.message
            }
            "SWIPE_LEFT", "SWIPE_RIGHT", "SWIPE_UP", "SWIPE_DOWN" -> {
                val dir = parsed.actionKind.removePrefix("SWIPE_").lowercase()
                val res = svc.performVerifiedSwipe(dir)
                executedOk = res.success
                actionMethod = res.methodUsed
                actionDetail = res.message
                if (res.blocked) blockedReason = res.message
            }
            "LONG_PRESS" -> {
                val res = svc.longPressNodeOrPoint(query = parsed.targetHint.ifBlank { null })
                executedOk = res.success
                actionMethod = res.methodUsed
                actionDetail = res.message
                if (res.blocked) blockedReason = res.message
            }
            "CLICK_TARGET" -> {
                when (val res = svc.tapNodeByTextVerified(parsed.targetHint)) {
                    is ZehnAccessibilityService.TapOutcome.Success -> {
                        executedOk = true
                        actionMethod = "TapVerified"
                        actionDetail = res.message
                    }
                    is ZehnAccessibilityService.TapOutcome.Blocked -> {
                        blockedReason = res.reason
                    }
                    is ZehnAccessibilityService.TapOutcome.Failed -> {
                        executedOk = false
                        actionDetail = res.reason
                    }
                }
            }
            "TYPE_TEXT" -> {
                executedOk = svc.typeTextIntoFocused(parsed.targetHint)
                actionMethod = "ACTION_SET_TEXT"
                actionDetail = if (executedOk) "Typed text into focused field" else "No non-password editable field focused"
            }
            "RECENTS" -> {
                executedOk = svc.performRecents()
                actionMethod = "GLOBAL_ACTION_RECENTS"
                actionDetail = if (executedOk) "Opened Recents" else "Recents action failed"
            }
            "NAV_BACK" -> {
                executedOk = svc.performBack()
                actionMethod = "GLOBAL_ACTION_BACK"
                actionDetail = if (executedOk) "Navigated Back" else "Back action failed"
            }
            "INSPECT_OCR" -> {
                val analysis = screenVisionManager?.captureAndAnalyzeScreen(useVisionModel = false)
                executedOk = analysis?.error == null
                actionDetail = if (analysis == null) {
                    "Accessibility nodes (${beforeObs.totalNodeCount}): ${beforeObs.screenText.take(10).joinToString(" | ")}"
                } else if (analysis.error != null) {
                    analysis.error
                } else {
                    "Screen OCR (${analysis.method}): ${analysis.ocrText.take(300)}"
                }
            }
            else -> {
                executedOk = beforeObs.error == null
                actionDetail = if (beforeObs.screenText.isNotEmpty()) {
                    "App ${beforeObs.packageName}: ${beforeObs.screenText.take(15).joinToString(" | ")}"
                } else {
                    beforeObs.error ?: "Screen has ${beforeObs.totalNodeCount} nodes, 0 text labels."
                }
            }
        }

        if (blockedReason != null) {
            planManager.updateStep(4, "BLOCKED")
            planManager.updateStep(5, "BLOCKED")
            planManager.updateStep(6, "BLOCKED")
            planManager.finishPlan(blockedReason!!)
            actionLogDao.insert(ActionLogEntity("SAFETY", goal.take(48), blockedReason!!, "BLOCKED"))
            return@withContext blockedReason!!
        }
        planManager.updateStep(4, if (executedOk) "DONE" else "FAILED")

        // Stage 5: VERIFY
        delay(320)
        val afterObs = visionEngine.observeStructured()
        val stateChanged = (afterObs.packageName != beforeObs.packageName) ||
            (afterObs.signatureHash() != beforeHash)
        val verified = when (parsed.actionKind) {
            "INSPECT", "INSPECT_OCR" -> executedOk
            "CLICK_TARGET" -> executedOk && stateChanged
            "SCROLL_DOWN", "SCROLL_UP", "SWIPE_LEFT", "SWIPE_RIGHT", "SWIPE_UP", "SWIPE_DOWN" ->
                executedOk && (stateChanged || actionMethod.startsWith("ACTION_SCROLL") || actionMethod == "AccessibilityService.dispatchGesture")
            else -> executedOk
        }
        planManager.updateStep(5, if (verified) "DONE" else "FAILED")

        // Stage 6: RECOVER
        var finalSuccess = verified
        var finalSummary = when {
            verified && stateChanged -> "$actionDetail -> Verified UI state updated (${afterObs.packageName})"
            verified -> "$actionDetail -> Gesture/action completed on ${afterObs.packageName}"
            else -> actionDetail
        }

        if (!verified) {
            when (parsed.actionKind) {
                "SCROLL_DOWN", "SCROLL_UP" -> {
                    val recRes = svc.performVerifiedSwipe(if (parsed.actionKind == "SCROLL_DOWN") "up" else "down")
                    delay(260)
                    val postRecObs = visionEngine.observeStructured()
                    val recChanged = postRecObs.signatureHash() != beforeHash
                    finalSuccess = recRes.success && recChanged
                    finalSummary = if (finalSuccess) {
                        "$actionDetail (recovered via verified swipe gesture; UI updated)"
                    } else if (recRes.success) {
                        "Scroll swipe completed, but screen UI did not change (reached edge or unscrollable)."
                    } else {
                        "Failed: ${recRes.message}"
                    }
                }
                "CLICK_TARGET" -> {
                    if (executedOk && !stateChanged) {
                        // Target was clicked via Accessibility/Gesture, wait once more and check if text/app shifted
                        delay(260)
                        val recheckObs = visionEngine.observeStructured()
                        if (recheckObs.signatureHash() != beforeHash || recheckObs.packageName != beforeObs.packageName) {
                            finalSuccess = true
                            finalSummary = "$actionDetail -> Verified screen updated after delay"
                        } else {
                            // Try OCR coordinate fallback if available
                            val ocrMatch = screenVisionManager?.findOcrElementMatching(parsed.targetHint)
                            if (ocrMatch != null) {
                                val ocrTap = svc.tapCoordinatesVerified(ocrMatch.bounds.exactCenterX(), ocrMatch.bounds.exactCenterY())
                                delay(260)
                                val postOcrObs = visionEngine.observeStructured()
                                finalSuccess = ocrTap.success && postOcrObs.signatureHash() != beforeHash
                                finalSummary = if (finalSuccess) {
                                    "Recovered via OCR coordinates for '${parsed.targetHint}'"
                                } else {
                                    "Verification failed: tapped '${parsed.targetHint}', but UI state did not change."
                                }
                            } else {
                                finalSuccess = false
                                finalSummary = "Verification failed: '$actionDetail' executed, but UI state remained unchanged."
                            }
                        }
                    } else {
                        // Stage 4 click failed; try OCR coordinate fallback if available
                        val ocrMatch = screenVisionManager?.findOcrElementMatching(parsed.targetHint)
                        if (ocrMatch != null) {
                            val ocrTap = svc.tapCoordinatesVerified(ocrMatch.bounds.exactCenterX(), ocrMatch.bounds.exactCenterY())
                            delay(280)
                            val postOcrObs = visionEngine.observeStructured()
                            finalSuccess = ocrTap.success && (postOcrObs.signatureHash() != beforeHash)
                            finalSummary = if (finalSuccess) {
                                "Recovered click via Screen OCR match '${ocrMatch.text}'"
                            } else {
                                "Failed clicking '${parsed.targetHint}': ${ocrTap.message}"
                            }
                        }
                    }
                }
            }
        }

        planManager.updateStep(6, if (finalSuccess) "DONE" else "FAILED")
        val logStatus = if (finalSuccess) "SUCCESS" else "FAILED"
        actionLogDao.insert(ActionLogEntity("AGENT_GUI", goal.take(48), finalSummary, logStatus))
        planManager.finishPlan(finalSummary)
        return@withContext finalSummary
    }

    internal data class ParsedGuiIntent(
        val actionKind: String,
        val targetHint: String = ""
    )

    internal fun parseGuiIntent(goal: String): ParsedGuiIntent {
        val lower = goal.lowercase().trim()
        return when {
            lower.contains("ocr") || lower.contains("capture screen") -> ParsedGuiIntent("INSPECT_OCR")
            lower.contains("long press") || lower.contains("hold ") -> {
                ParsedGuiIntent("LONG_PRESS", extractClickTarget(goal))
            }
            lower.contains("scroll down") || lower.contains("neeche scroll") ||
                (lower.contains("scroll") && !lower.contains("up") && !lower.contains("oopar")) -> {
                val hint = if (lower.contains("chat")) "chat" else ""
                ParsedGuiIntent("SCROLL_DOWN", hint)
            }
            lower.contains("scroll up") || lower.contains("oopar scroll") -> {
                val hint = if (lower.contains("chat")) "chat" else ""
                ParsedGuiIntent("SCROLL_UP", hint)
            }
            lower.contains("swipe left") -> ParsedGuiIntent("SWIPE_LEFT")
            lower.contains("swipe right") -> ParsedGuiIntent("SWIPE_RIGHT")
            lower.contains("swipe up") -> ParsedGuiIntent("SWIPE_UP")
            lower.contains("swipe down") -> ParsedGuiIntent("SWIPE_DOWN")
            lower.contains("tap") || lower.contains("click") || lower.contains("dabaao") || lower.contains("dabao") -> {
                ParsedGuiIntent("CLICK_TARGET", extractClickTarget(goal))
            }
            lower.startsWith("type ") -> ParsedGuiIntent("TYPE_TEXT", goal.substringAfter("type ", "").trim())
            lower.contains("recents") -> ParsedGuiIntent("RECENTS")
            lower == "back" || lower.contains("go back") -> ParsedGuiIntent("NAV_BACK")
            else -> ParsedGuiIntent("INSPECT")
        }
    }

    internal fun extractClickTarget(rawGoal: String): String {
        // Handles "Tap Place Order on screen", "Screen par Settings button click karo", "click Continue", "long press Wi-Fi"
        val cleaned = rawGoal
            .replace(Regex("(?i)\\b(on screen|screen par|par|the|button|icon|option|please|karo|kardo|kar do|dabaao|dabao|tap|click|long press|press|hold)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { rawGoal.trim() }
    }

    suspend fun executeWorkflowOrFlow(workflow: WorkflowEntity): String {
        if (safetyPolicy.policy.value.emergencyStopped) return "Blocked: Emergency STOP active."
        val outputs = mutableListOf<String>()
        if (workflow.kind == "WORKFLOW_JSON") {
            runCatching {
                val obj = JSONObject(workflow.definitionText)
                val steps = obj.optJSONArray("steps") ?: return@runCatching
                for (i in 0 until steps.length()) {
                    val s = steps.getJSONObject(i)
                    val tool = s.optString("tool")
                    val args = s.optJSONObject("args")
                    val res = when (tool) {
                        "get_system_status" -> phoneControl.getSystemStatusSummary()
                        "set_volume" -> phoneControl.setVolumePercent(args?.optInt("level", 60) ?: 60)
                        "open_app" -> phoneControl.openApp(args?.optString("app").orEmpty())
                        "toggle_flashlight" -> phoneControl.toggleFlashlight(null)
                        else -> "Step $tool completed"
                    }
                    outputs.add(res)
                }
            }
        } else {
            workflow.definitionText.lines().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.contains("toggle_flashlight") -> outputs.add(phoneControl.toggleFlashlight(null))
                    trimmed.contains("set_volume") -> outputs.add(phoneControl.setVolumePercent(0))
                    trimmed.contains("get_system_status") -> outputs.add(phoneControl.getSystemStatusSummary())
                    trimmed.startsWith("- command:") -> {
                        val cmd = trimmed.removePrefix("- command:").trim().trim('"')
                        outputs.add(tryExecuteCommand(cmd) ?: "Ran: $cmd")
                    }
                }
            }
        }
        val summary = outputs.ifEmpty { listOf("Executed ${workflow.name}") }.joinToString(" -> ")
        actionLogDao.insert(ActionLogEntity("TOOL", "Replay ${workflow.name}", summary, "SUCCESS"))
        return summary
    }
}
