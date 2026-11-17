package com.zehn.assistant.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.zehn.assistant.security.SafetyPolicyManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class ScreenNodeSnapshot(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val isPassword: Boolean,
    val bounds: Rect,
    val scrollable: Boolean = false,
    val longClickable: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val hintText: String = "",
    val stateDescription: String = "",
    val availableActions: List<String> = emptyList()
) {
    fun primaryLabel(): String = when {
        text.isNotBlank() -> text
        contentDescription.isNotBlank() -> contentDescription
        hintText.isNotBlank() -> hintText
        viewId.isNotBlank() -> viewId.substringAfterLast('/')
        else -> className.substringAfterLast('.')
    }
}

data class ScreenObservation(
    val timestampMs: Long = System.currentTimeMillis(),
    val packageName: String = "",
    val windowTitle: String = "",
    val windowCount: Int = 0,
    val totalNodeCount: Int = 0,
    val screenText: List<String> = emptyList(),
    val visibleButtons: List<ScreenNodeSnapshot> = emptyList(),
    val clickableElements: List<ScreenNodeSnapshot> = emptyList(),
    val inputFields: List<ScreenNodeSnapshot> = emptyList(),
    val scrollableContainers: List<ScreenNodeSnapshot> = emptyList(),
    val nodes: List<ScreenNodeSnapshot> = emptyList(),
    val blockedBySafety: Boolean = false,
    val error: String? = null
) {
    fun signatureHash(): String = buildString {
        append(packageName)
        append('|')
        append(totalNodeCount)
        append('|')
        screenText.take(25).forEach {
            append(it)
            append(';')
        }
    }
}

enum class ScrollDirection { UP, DOWN }

data class ActionExecutionResult(
    val success: Boolean,
    val action: String,
    val message: String,
    val methodUsed: String = "",
    val blocked: Boolean = false
)

data class AccessibilityDiagnostics(
    val permissionEnabled: Boolean = false,
    val serviceConnected: Boolean = false,
    val foregroundPackage: String = "",
    val foregroundAppLabel: String = "",
    val windowCount: Int = 0,
    val screenNodeCount: Int = 0,
    val clickableNodeCount: Int = 0,
    val scrollableNodeCount: Int = 0,
    val lastAction: String = "None",
    val lastActionResult: String = "No actions executed yet",
    val gestureCapable: Boolean = false,
    val accessibilityScreenshotCapable: Boolean = false,
    val lastError: String = "None",
    val lastUpdatedAtMs: Long = 0L
)

class ZehnAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceDeps {
        fun safetyPolicy(): SafetyPolicyManager
    }

    private val deps by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ServiceDeps::class.java)
    }

    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 80L
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
        }
        _instance.value = this
        _isConnected.value = true
        updateCapabilitiesDiagnostics(applicationContext)
        _diagnostics.update {
            it.copy(
                permissionEnabled = true,
                serviceConnected = true,
                lastAction = "SERVICE_CONNECTED",
                lastActionResult = "ZehnAccessibilityService connected cleanly",
                lastError = "None",
                lastUpdatedAtMs = System.currentTimeMillis()
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val pkg = safeEvent.packageName?.toString()?.takeIf { it.isNotBlank() }
        if (pkg != null) {
            _foregroundPackage.value = pkg
            val appLabel = resolveAppLabel(applicationContext, pkg)
            _diagnostics.update {
                it.copy(
                    permissionEnabled = true,
                    serviceConnected = true,
                    foregroundPackage = pkg,
                    foregroundAppLabel = appLabel,
                    lastUpdatedAtMs = System.currentTimeMillis()
                )
            }
            if (safeEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                onAppOpenedCallback?.invoke(pkg)
            }
        }
    }

    override fun onInterrupt() {
        _diagnostics.update {
            it.copy(
                lastError = "Accessibility service interrupted by system",
                lastUpdatedAtMs = System.currentTimeMillis()
            )
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (_instance.value === this) {
            _instance.value = null
        }
        _isConnected.value = false
        _diagnostics.update {
            it.copy(
                serviceConnected = false,
                permissionEnabled = isAccessibilityPermissionEnabled(applicationContext),
                lastError = "Accessibility service unbound by Android OS",
                lastUpdatedAtMs = System.currentTimeMillis()
            )
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (_instance.value === this) {
            _instance.value = null
        }
        _isConnected.value = false
        _diagnostics.update {
            it.copy(
                serviceConnected = false,
                permissionEnabled = isAccessibilityPermissionEnabled(applicationContext),
                lastUpdatedAtMs = System.currentTimeMillis()
            )
        }
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    fun refreshServiceConfiguration(): ActionExecutionResult {
        return runCatching {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
            updateCapabilitiesDiagnostics(applicationContext)
            val obs = observeScreenStructured()
            recordAction("RECONNECT_SERVICE", "Refreshed service config (${obs.totalNodeCount} nodes)", null)
            ActionExecutionResult(
                success = true,
                action = "RECONNECT_SERVICE",
                message = "Service connected & refreshed (${obs.totalNodeCount} nodes visible).",
                methodUsed = "AccessibilityService.setServiceInfo"
            )
        }.getOrElse { err ->
            val msg = "Service refresh failed: ${err.message ?: "unknown"}"
            recordAction("RECONNECT_SERVICE", msg, msg)
            ActionExecutionResult(false, "RECONNECT_SERVICE", msg)
        }
    }

    fun hasGestureCapability(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val info = runCatching { serviceInfo }.getOrNull() ?: return false
        return (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
    }

    fun hasAccessibilityScreenshotCapability(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = runCatching { serviceInfo }.getOrNull() ?: return true
            return (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0 ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        }
        return true
    }

    fun getVisibleScreenBounds(): Rect {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = getSystemService(WindowManager::class.java)
                val b = wm?.currentWindowMetrics?.bounds
                if (b != null && b.width() > 0 && b.height() > 0) return b
            }
            val dm = resources.displayMetrics
            Rect(0, 0, dm.widthPixels.coerceAtLeast(320), dm.heightPixels.coerceAtLeast(480))
        }.getOrElse {
            Rect(0, 0, 1080, 1920)
        }
    }

    /**
     * Multi-window safe active root retrieval. Checks rootInActiveWindow first, then
     * falls back to the top application window from `windows`.
     */
    private fun acquireFreshRootNode(): Pair<AccessibilityNodeInfo?, String> {
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (activeRoot != null) {
            runCatching { activeRoot.refresh() }
            val activePkg = activeRoot.packageName?.toString().orEmpty()
            return activeRoot to activePkg
        }

        val windowList = runCatching { windows }.getOrNull().orEmpty()
        val prioritized = windowList.sortedByDescending { it.layer }
        for (win in prioritized) {
            if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
            ) {
                val winRoot = runCatching { win.root }.getOrNull()
                if (winRoot != null) {
                    runCatching { winRoot.refresh() }
                    val pkg = winRoot.packageName?.toString().orEmpty()
                    return winRoot to pkg
                }
            }
        }
        return null to (_foregroundPackage.value ?: "")
    }

    fun readScreenSnapshots(): List<ScreenNodeSnapshot> = observeScreenStructured().nodes

    fun observeScreenStructured(): ScreenObservation {
        return runCatching {
            val policy = deps.safetyPolicy()
            val (root, rootPkg) = acquireFreshRootNode()
            val currentPkg = rootPkg.ifBlank { _foregroundPackage.value.orEmpty() }
            if (currentPkg.isNotBlank()) {
                _foregroundPackage.value = currentPkg
            }
            if (!policy.canReadCurrentScreen(currentPkg.ifBlank { null })) {
                val reason = "Screen reading blocked by Safety/Gaming Guard for ${currentPkg.ifBlank { "current app" }}"
                recordAction("OBSERVE_SCREEN", reason, reason)
                return ScreenObservation(
                    packageName = currentPkg,
                    blockedBySafety = true,
                    error = reason
                )
            }

            if (root == null) {
                val err = "No active window root available right now."
                recordAction("OBSERVE_SCREEN", err, err)
                return ScreenObservation(
                    packageName = currentPkg,
                    windowCount = runCatching { windows.size }.getOrDefault(0),
                    error = err
                )
            }

            val allNodes = mutableListOf<ScreenNodeSnapshot>()
            val visibleBounds = getVisibleScreenBounds()
            var rawVisited = 0

            fun traverseSafe(node: AccessibilityNodeInfo?, depth: Int, shouldRecycle: Boolean) {
                if (node == null || depth > MAX_TREE_DEPTH || rawVisited > MAX_RAW_NODES) {
                    if (shouldRecycle) safeRecycle(node)
                    return
                }
                rawVisited++
                try {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val isPwd = isNodePasswordProtected(node)
                    val rawText = node.text?.toString().orEmpty()
                    val text = if (isPwd && rawText.isNotEmpty()) "[PROTECTED_PASSWORD]" else rawText
                    val desc = if (isPwd) "[PROTECTED_FIELD]" else node.contentDescription?.toString().orEmpty()
                    val hint = if (isPwd) {
                        "[PROTECTED_HINT]"
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        node.hintText?.toString().orEmpty()
                    } else ""
                    val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        node.stateDescription?.toString().orEmpty()
                    } else ""
                    val id = node.viewIdResourceName.orEmpty()
                    val cls = node.className?.toString().orEmpty()
                    val actionLabels = buildActionLabels(node)
                    val inBounds = rect.width() > 0 && rect.height() > 0 && Rect.intersects(rect, visibleBounds)

                    if (text.isNotBlank() || desc.isNotBlank() || hint.isNotBlank() ||
                        node.isClickable || node.isEditable || node.isScrollable || node.isLongClickable
                    ) {
                        if (allNodes.size < MAX_CAPTURED_NODES && (inBounds || node.isScrollable || text.isNotBlank())) {
                            allNodes.add(
                                ScreenNodeSnapshot(
                                    text = text,
                                    contentDescription = desc,
                                    viewId = id,
                                    className = cls,
                                    clickable = node.isClickable,
                                    editable = node.isEditable,
                                    isPassword = isPwd,
                                    bounds = rect,
                                    scrollable = node.isScrollable,
                                    longClickable = node.isLongClickable,
                                    focusable = node.isFocusable,
                                    focused = node.isFocused,
                                    enabled = node.isEnabled,
                                    checkable = node.isCheckable,
                                    checked = node.isChecked,
                                    hintText = hint,
                                    stateDescription = stateDesc,
                                    availableActions = actionLabels
                                )
                            )
                        }
                    }

                    val count = node.childCount.coerceAtMost(80)
                    for (i in 0 until count) {
                        val child = runCatching { node.getChild(i) }.getOrNull()
                        traverseSafe(child, depth + 1, shouldRecycle = true)
                    }
                } finally {
                    if (shouldRecycle) safeRecycle(node)
                }
            }

            traverseSafe(root, 0, shouldRecycle = false)

            val windowList = runCatching { windows }.getOrDefault(emptyList())
            val windowTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                windowList.firstOrNull { it.isActive }?.title?.toString().orEmpty()
            } else ""

            val screenTexts = allNodes.asSequence()
                .filter { !it.isPassword }
                .map { it.text.ifBlank { it.contentDescription }.ifBlank { it.hintText } }
                .filter { it.isNotBlank() && it != "[PROTECTED_FIELD]" && it != "[PROTECTED_PASSWORD]" }
                .distinct()
                .toList()

            val clickable = allNodes.filter { it.clickable && it.enabled }
            val buttons = clickable.filter {
                it.className.contains("Button", ignoreCase = true) ||
                    it.text.isNotBlank() ||
                    it.contentDescription.isNotBlank()
            }
            val inputs = allNodes.filter { it.editable }
            val scrollables = allNodes.filter { it.scrollable }

            _diagnostics.update {
                it.copy(
                    permissionEnabled = true,
                    serviceConnected = true,
                    foregroundPackage = currentPkg,
                    foregroundAppLabel = resolveAppLabel(applicationContext, currentPkg),
                    windowCount = windowList.size.coerceAtLeast(1),
                    screenNodeCount = rawVisited,
                    clickableNodeCount = clickable.size,
                    scrollableNodeCount = scrollables.size,
                    gestureCapable = hasGestureCapability(),
                    accessibilityScreenshotCapable = hasAccessibilityScreenshotCapability(),
                    lastAction = "OBSERVE_SCREEN",
                    lastActionResult = "Read $rawVisited nodes (${clickable.size} clickable, ${scrollables.size} scrollable)",
                    lastError = "None",
                    lastUpdatedAtMs = System.currentTimeMillis()
                )
            }

            ScreenObservation(
                packageName = currentPkg,
                windowTitle = windowTitle,
                windowCount = windowList.size.coerceAtLeast(1),
                totalNodeCount = rawVisited,
                screenText = screenTexts,
                visibleButtons = buttons,
                clickableElements = clickable,
                inputFields = inputs,
                scrollableContainers = scrollables,
                nodes = allNodes
            )
        }.getOrElse { err ->
            val msg = "Screen read failed: ${err.message ?: "unexpected error"}"
            recordAction("OBSERVE_SCREEN", msg, msg)
            ScreenObservation(error = msg)
        }
    }

    /**
     * Synchronous tap kept for backwards compatibility; delegates node lookup + clickable parent discovery.
     * Prefer `tapNodeByTextVerified` inside coroutines for verified gesture callbacks.
     */
    fun tapNodeByText(query: String): TapOutcome {
        return tapNodeInternal(query, allowGestureFallback = true)
    }

    suspend fun tapNodeByTextVerified(query: String): TapOutcome = withContext(Dispatchers.Default) {
        val policy = deps.safetyPolicy()
        if (policy.policy.value.emergencyStopped) {
            return@withContext TapOutcome.Blocked("Emergency STOP active")
        }
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@withContext TapOutcome.Failed("Empty click target specified")
        }
        val (root, rootPkg) = acquireFreshRootNode()
        val currentPkg = rootPkg.ifBlank { _foregroundPackage.value.orEmpty() }
        if (!policy.canReadCurrentScreen(currentPkg.ifBlank { null })) {
            val msg = "Click blocked by Safety/Gaming Guard for $currentPkg"
            recordAction("CLICK($cleanQuery)", msg, msg)
            return@withContext TapOutcome.Blocked(msg)
        }
        if (root == null) {
            val msg = "No active window available to locate '$cleanQuery'"
            recordAction("CLICK($cleanQuery)", msg, msg)
            return@withContext TapOutcome.Failed(msg)
        }

        val target = findBestNodeMatching(root, cleanQuery)
        if (target == null) {
            val msg = "Visible element '$cleanQuery' not found on current screen ($currentPkg)"
            recordAction("CLICK($cleanQuery)", msg, msg)
            return@withContext TapOutcome.Failed(msg)
        }

        runCatching { target.refresh() }
        val safeLabel = safeLabelFor(target, cleanQuery)
        if (!policy.policy.value.automatedPaymentConfirmEnabled &&
            policy.isSensitivePaymentNode(safeLabel, target.contentDescription?.toString(), target.viewIdResourceName)
        ) {
            val msg = "Automated Payment Confirmation is OFF — blocked tapping '$safeLabel'"
            recordAction("CLICK($safeLabel)", msg, msg)
            return@withContext TapOutcome.Blocked(msg)
        }

        // 1. Discover target or nearest clickable ancestor and validate freshness + enabled
        val clickableAncestor = findClickableAncestor(target)
        if (clickableAncestor != null) {
            val ancestorLabel = safeLabelFor(clickableAncestor, safeLabel)
            if (!policy.policy.value.automatedPaymentConfirmEnabled &&
                policy.isSensitivePaymentNode(ancestorLabel, clickableAncestor.contentDescription?.toString(), clickableAncestor.viewIdResourceName)
            ) {
                return@withContext TapOutcome.Blocked("Automated Payment Confirmation is OFF — blocked tapping '$ancestorLabel'")
            }
            val refreshed = runCatching { clickableAncestor.refresh() }.getOrDefault(false)
            if (refreshed || clickableAncestor.isEnabled) {
                val ok = runCatching { clickableAncestor.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                if (ok) {
                    val msg = "Executed ACTION_CLICK on '$safeLabel'"
                    recordAction("CLICK($safeLabel)", msg, null)
                    return@withContext TapOutcome.Success(msg)
                }
            }
        }

        // 2. Fallback: bounds-based verified gesture tap clamped to visible screen bounds
        val rect = Rect()
        target.getBoundsInScreen(rect)
        val screen = getVisibleScreenBounds()
        if (rect.isEmpty || !Rect.intersects(rect, screen)) {
            val msg = "Found '$safeLabel' but bounds $rect are outside visible screen $screen"
            recordAction("CLICK($safeLabel)", msg, msg)
            return@withContext TapOutcome.Failed(msg)
        }

        val cx = rect.centerX().toFloat().coerceIn((screen.left + 4).toFloat(), (screen.right - 4).toFloat())
        val cy = rect.centerY().toFloat().coerceIn((screen.top + 4).toFloat(), (screen.bottom - 4).toFloat())
        val gestureResult = tapCoordinatesVerified(cx, cy)
        return@withContext if (gestureResult.success) {
            val msg = "Executed verified gesture tap on '$safeLabel' at (${cx.toInt()}, ${cy.toInt()})"
            recordAction("CLICK($safeLabel)", msg, null)
            TapOutcome.Success(msg)
        } else {
            recordAction("CLICK($safeLabel)", gestureResult.message, gestureResult.message)
            TapOutcome.Failed(gestureResult.message)
        }
    }

    private fun tapNodeInternal(query: String, allowGestureFallback: Boolean): TapOutcome {
        val policy = deps.safetyPolicy()
        val currentPkg = _foregroundPackage.value
        if (!policy.canReadCurrentScreen(currentPkg)) {
            return TapOutcome.Blocked("Screen reading blocked by Safety/Gaming Guard for $currentPkg")
        }
        val (root, _) = acquireFreshRootNode()
        if (root == null) return TapOutcome.Failed("No active window")
        val target = findBestNodeMatching(root, query) ?: return TapOutcome.Failed("Element '$query' not found")
        runCatching { target.refresh() }

        val safeLabel = safeLabelFor(target, query)
        if (!policy.policy.value.automatedPaymentConfirmEnabled &&
            policy.isSensitivePaymentNode(safeLabel, target.contentDescription?.toString(), target.viewIdResourceName)
        ) {
            return TapOutcome.Blocked("Automated Payment Confirmation is OFF — blocked tapping '$safeLabel'")
        }

        val clickableNode = findClickableAncestor(target)
        if (clickableNode != null) {
            val clicked = runCatching { clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            if (clicked) {
                recordAction("CLICK($safeLabel)", "ACTION_CLICK succeeded", null)
                return TapOutcome.Success("Tapped '$safeLabel'")
            }
        }

        if (!allowGestureFallback) return TapOutcome.Failed("Click failed on '$query'")

        val rect = Rect()
        target.getBoundsInScreen(rect)
        val screen = getVisibleScreenBounds()
        if (rect.isEmpty || !Rect.intersects(rect, screen)) {
            return TapOutcome.Failed("Element '$query' bounds outside visible screen")
        }
        val cx = rect.centerX().toFloat().coerceIn((screen.left + 4).toFloat(), (screen.right - 4).toFloat())
        val cy = rect.centerY().toFloat().coerceIn((screen.top + 4).toFloat(), (screen.bottom - 4).toFloat())
        return if (tapCoordinates(cx, cy)) {
            recordAction("CLICK($safeLabel)", "Gesture tap dispatched", null)
            TapOutcome.Success("Gesture-tapped '$safeLabel'")
        } else {
            val err = "Click failed on '$query'"
            recordAction("CLICK($safeLabel)", err, err)
            TapOutcome.Failed(err)
        }
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 7) {
            if (current.isClickable && current.isEnabled) {
                return current
            }
            val parent = runCatching { current.parent }.getOrNull()
            if (current !== node) safeRecycle(current)
            current = parent
            hops++
        }
        return null
    }

    private fun findLongClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 7) {
            if (current.isLongClickable && current.isEnabled) {
                return current
            }
            val parent = runCatching { current.parent }.getOrNull()
            if (current !== node) safeRecycle(current)
            current = parent
            hops++
        }
        return null
    }

    private fun findBestNodeMatching(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val clean = query.trim()
        if (clean.isBlank()) return null

        var exactMatch: AccessibilityNodeInfo? = null
        var containsMatch: AccessibilityNodeInfo? = null

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TREE_DEPTH || exactMatch != null) return
            val isPwd = isNodePasswordProtected(node)
            if (!isPwd) {
                val t = node.text?.toString()?.trim().orEmpty()
                val d = node.contentDescription?.toString()?.trim().orEmpty()
                val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""
                val idTail = node.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ').orEmpty()

                val isExact = t.equals(clean, ignoreCase = true) ||
                    d.equals(clean, ignoreCase = true) ||
                    h.equals(clean, ignoreCase = true)
                val isContains = t.contains(clean, ignoreCase = true) ||
                    d.contains(clean, ignoreCase = true) ||
                    h.contains(clean, ignoreCase = true) ||
                    (idTail.isNotBlank() && idTail.equals(clean, ignoreCase = true))

                if (isExact) {
                    exactMatch = node
                    return
                }
                if (isContains && containsMatch == null) {
                    containsMatch = node
                }
            }
            val count = node.childCount.coerceAtMost(80)
            for (i in 0 until count) {
                walk(runCatching { node.getChild(i) }.getOrNull(), depth + 1)
                if (exactMatch != null) return
            }
        }

        walk(root, 0)
        return exactMatch ?: containsMatch
    }

    fun tapCoordinates(x: Float, y: Float): Boolean {
        if (deps.safetyPolicy().policy.value.emergencyStopped) return false
        if (!hasGestureCapability()) return false
        val screen = getVisibleScreenBounds()
        val safeX = x.coerceIn((screen.left + 2).toFloat(), (screen.right - 2).toFloat())
        val safeY = y.coerceIn((screen.top + 2).toFloat(), (screen.bottom - 2).toFloat())
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
        return runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }.getOrDefault(false)
    }

    suspend fun tapCoordinatesVerified(x: Float, y: Float): ActionExecutionResult {
        if (deps.safetyPolicy().policy.value.emergencyStopped) {
            return ActionExecutionResult(false, "TAP($x,$y)", "Blocked by Emergency STOP", blocked = true)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !hasGestureCapability()) {
            val err = "Gesture dispatch unsupported (requires API 24+ and canPerformGestures)"
            recordAction("TAP", err, err)
            return ActionExecutionResult(false, "TAP", err)
        }
        val screen = getVisibleScreenBounds()
        if (x < screen.left || x > screen.right || y < screen.top || y > screen.bottom) {
            val err = "Coordinates (${x.toInt()}, ${y.toInt()}) outside visible screen $screen"
            recordAction("TAP", err, err)
            return ActionExecutionResult(false, "TAP", err)
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
        return dispatchGestureVerified(
            actionName = "TAP(${x.toInt()},${y.toInt()})",
            gesture = GestureDescription.Builder().addStroke(stroke).build(),
            timeoutMs = 1500L
        )
    }

    suspend fun longPressNodeOrPoint(query: String? = null, x: Float? = null, y: Float? = null): ActionExecutionResult {
        val policy = deps.safetyPolicy()
        if (policy.policy.value.emergencyStopped) {
            return ActionExecutionResult(false, "LONG_PRESS", "Blocked by Emergency STOP", blocked = true)
        }
        if (!query.isNullOrBlank()) {
            val (root, pkg) = acquireFreshRootNode()
            if (!policy.canReadCurrentScreen(pkg.ifBlank { null })) {
                return ActionExecutionResult(false, "LONG_PRESS($query)", "Blocked by Safety/Gaming Guard", blocked = true)
            }
            if (root != null) {
                val node = findBestNodeMatching(root, query)
                if (node != null) {
                    runCatching { node.refresh() }
                    val lcNode = findLongClickableAncestor(node)
                    if (lcNode != null && runCatching { lcNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)) {
                        val msg = "Executed ACTION_LONG_CLICK on '$query'"
                        recordAction("LONG_PRESS($query)", msg, null)
                        return ActionExecutionResult(true, "LONG_PRESS($query)", msg, "AccessibilityNodeInfo.ACTION_LONG_CLICK")
                    }
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val screen = getVisibleScreenBounds()
                    if (!rect.isEmpty && Rect.intersects(rect, screen)) {
                        val cx = rect.centerX().toFloat().coerceIn((screen.left + 4).toFloat(), (screen.right - 4).toFloat())
                        val cy = rect.centerY().toFloat().coerceIn((screen.top + 4).toFloat(), (screen.bottom - 4).toFloat())
                        val path = Path().apply { moveTo(cx, cy) }
                        val stroke = GestureDescription.StrokeDescription(path, 0L, 680L)
                        return dispatchGestureVerified("LONG_PRESS($query)", GestureDescription.Builder().addStroke(stroke).build(), 2000L)
                    }
                }
            }
            val msg = "Target '$query' not found for long press"
            recordAction("LONG_PRESS($query)", msg, msg)
            return ActionExecutionResult(false, "LONG_PRESS($query)", msg)
        }

        val screen = getVisibleScreenBounds()
        val px = (x ?: screen.exactCenterX()).coerceIn((screen.left + 8).toFloat(), (screen.right - 8).toFloat())
        val py = (y ?: screen.exactCenterY()).coerceIn((screen.top + 8).toFloat(), (screen.bottom - 8).toFloat())
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 680L)
        return dispatchGestureVerified("LONG_PRESS(${px.toInt()},${py.toInt()})", GestureDescription.Builder().addStroke(stroke).build(), 2000L)
    }

    /**
     * Synchronous scroll wrapper for legacy callers.
     */
    fun scroll(forward: Boolean): Boolean {
        val (root, pkg) = acquireFreshRootNode()
        if (!deps.safetyPolicy().canReadCurrentScreen(pkg.ifBlank { null })) return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (root != null && scrollBestContainer(root, action)) {
            recordAction(if (forward) "SCROLL_DOWN" else "SCROLL_UP", "Accessibility scroll action succeeded", null)
            return true
        }
        return performSwipe(if (forward) "up" else "down")
    }

    /**
     * Real scroll with node-action priority (`ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD`)
     * targeted at the largest visible scrollable container, followed by a verified `dispatchGesture`
     * strictly inside that container or visible display bounds.
     */
    suspend fun scrollDirection(direction: ScrollDirection, containerHint: String? = null): ActionExecutionResult =
        withContext(Dispatchers.Default) {
            val policy = deps.safetyPolicy()
            if (policy.policy.value.emergencyStopped) {
                return@withContext ActionExecutionResult(false, "SCROLL_${direction.name}", "Blocked by Emergency STOP", blocked = true)
            }
            val (root, pkg) = acquireFreshRootNode()
            val activePkg = pkg.ifBlank { _foregroundPackage.value.orEmpty() }
            if (!policy.canReadCurrentScreen(activePkg.ifBlank { null })) {
                val msg = "Scroll blocked by Safety/Gaming Guard ($activePkg)"
                recordAction("SCROLL_${direction.name}", msg, msg)
                return@withContext ActionExecutionResult(false, "SCROLL_${direction.name}", msg, blocked = true)
            }

            val a11yAction = if (direction == ScrollDirection.DOWN) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }

            var targetContainerBounds: Rect? = null
            if (root != null) {
                val candidate = findBestScrollableNode(root, containerHint)
                if (candidate != null) {
                    runCatching { candidate.refresh() }
                    val rect = Rect()
                    candidate.getBoundsInScreen(rect)
                    targetContainerBounds = rect
                    val ok = runCatching { candidate.performAction(a11yAction) }.getOrDefault(false)
                    if (ok) {
                        val msg = "Scrolled ${direction.name.lowercase()} via AccessibilityNodeInfo (${candidate.className?.toString()?.substringAfterLast('.').orEmpty()})"
                        recordAction("SCROLL_${direction.name}", msg, null)
                        return@withContext ActionExecutionResult(
                            success = true,
                            action = "SCROLL_${direction.name}",
                            message = msg,
                            methodUsed = if (direction == ScrollDirection.DOWN) "ACTION_SCROLL_FORWARD" else "ACTION_SCROLL_BACKWARD"
                        )
                    }
                }
            }

            // Gesture fallback confined inside scrollable container or visible display bounds
            val swipeDir = if (direction == ScrollDirection.DOWN) "up" else "down"
            val gestureRes = performVerifiedSwipe(swipeDir, targetContainerBounds)
            return@withContext gestureRes.copy(action = "SCROLL_${direction.name}")
        }

    private fun findBestScrollableNode(root: AccessibilityNodeInfo, hint: String?): AccessibilityNodeInfo? {
        val scrollables = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        val visible = getVisibleScreenBounds()

        fun collect(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TREE_DEPTH) return
            if (node.isScrollable && node.isEnabled) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val inter = Rect(r)
                if (inter.intersect(visible)) {
                    var score = inter.width() * inter.height()
                    val id = node.viewIdResourceName?.lowercase().orEmpty()
                    val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                    if (!hint.isNullOrBlank() && (id.contains(hint.lowercase()) || desc.contains(hint.lowercase()))) {
                        score += 5_000_000
                    }
                    // Prefer vertical lists/recyclers over tiny horizontal strips unless hinted
                    if (inter.height() >= inter.width() * 0.7f) {
                        score += 500_000
                    }
                    scrollables.add(node to score)
                }
            }
            val count = node.childCount.coerceAtMost(80)
            for (i in 0 until count) {
                collect(runCatching { node.getChild(i) }.getOrNull(), depth + 1)
            }
        }

        collect(root, 0)
        return scrollables.maxByOrNull { it.second }?.first
    }

    private fun scrollBestContainer(root: AccessibilityNodeInfo, action: Int): Boolean {
        val best = findBestScrollableNode(root, null)
        if (best != null) {
            runCatching { best.refresh() }
            if (runCatching { best.performAction(action) }.getOrDefault(false)) return true
        }
        return false
    }

    fun performSwipe(direction: String): Boolean {
        if (deps.safetyPolicy().policy.value.emergencyStopped) return false
        if (!hasGestureCapability()) return false
        val stroke = buildSwipeStroke(direction, null) ?: return false
        return runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }.getOrDefault(false)
    }

    suspend fun performVerifiedSwipe(direction: String, containerBounds: Rect? = null): ActionExecutionResult {
        val cleanDir = direction.lowercase().trim()
        if (deps.safetyPolicy().policy.value.emergencyStopped) {
            return ActionExecutionResult(false, "SWIPE_$cleanDir", "Blocked by Emergency STOP", blocked = true)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            val msg = "Gestures require Android 7.0 (API 24+)"
            recordAction("SWIPE_$cleanDir", msg, msg)
            return ActionExecutionResult(false, "SWIPE_$cleanDir", msg)
        }
        if (!hasGestureCapability()) {
            val msg = "Service lacks CAPABILITY_CAN_PERFORM_GESTURES — enable Zehn Accessibility Service"
            recordAction("SWIPE_$cleanDir", msg, msg)
            return ActionExecutionResult(false, "SWIPE_$cleanDir", msg)
        }
        val stroke = buildSwipeStroke(cleanDir, containerBounds) ?: run {
            val msg = "No valid on-screen coordinates for swipe '$cleanDir'"
            recordAction("SWIPE_$cleanDir", msg, msg)
            return ActionExecutionResult(false, "SWIPE_$cleanDir", msg)
        }
        return dispatchGestureVerified(
            actionName = "SWIPE_${cleanDir.uppercase()}",
            gesture = GestureDescription.Builder().addStroke(stroke).build(),
            timeoutMs = 2000L
        )
    }

    private fun buildSwipeStroke(direction: String, containerBounds: Rect?): GestureDescription.StrokeDescription? {
        val screen = getVisibleScreenBounds()
        // Intersect containerBounds with visible screen bounds, inset safely to avoid OS edge nav zones
        val safeRegion = Rect(
            screen.left + (screen.width() * 0.08f).toInt().coerceAtLeast(16),
            screen.top + (screen.height() * 0.14f).toInt().coerceAtLeast(32),
            screen.right - (screen.width() * 0.08f).toInt().coerceAtLeast(16),
            screen.bottom - (screen.height() * 0.14f).toInt().coerceAtLeast(32)
        )
        if (containerBounds != null && !containerBounds.isEmpty) {
            val intersected = Rect(containerBounds)
            if (intersected.intersect(safeRegion) && intersected.width() > 60 && intersected.height() > 60) {
                safeRegion.set(
                    intersected.left + 12,
                    intersected.top + 12,
                    intersected.right - 12,
                    intersected.bottom - 12
                )
            }
        }
        if (safeRegion.width() < 40 || safeRegion.height() < 40) return null

        val midX = safeRegion.exactCenterX()
        val midY = safeRegion.exactCenterY()
        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        when (direction.lowercase()) {
            "up", "down_feed", "next" -> {
                startX = midX
                startY = safeRegion.top + safeRegion.height() * 0.80f
                endX = midX
                endY = safeRegion.top + safeRegion.height() * 0.22f
            }
            "down", "prev" -> {
                startX = midX
                startY = safeRegion.top + safeRegion.height() * 0.22f
                endX = midX
                endY = safeRegion.top + safeRegion.height() * 0.80f
            }
            "left" -> {
                startX = safeRegion.left + safeRegion.width() * 0.85f
                startY = midY
                endX = safeRegion.left + safeRegion.width() * 0.15f
                endY = midY
            }
            "right" -> {
                startX = safeRegion.left + safeRegion.width() * 0.15f
                startY = midY
                endX = safeRegion.left + safeRegion.width() * 0.85f
                endY = midY
            }
            else -> return null
        }

        val cx1 = startX.coerceIn(screen.left + 4f, screen.right - 4f)
        val cy1 = startY.coerceIn(screen.top + 4f, screen.bottom - 4f)
        val cx2 = endX.coerceIn(screen.left + 4f, screen.right - 4f)
        val cy2 = endY.coerceIn(screen.top + 4f, screen.bottom - 4f)

        val path = Path().apply {
            moveTo(cx1, cy1)
            lineTo(cx2, cy2)
        }
        return GestureDescription.StrokeDescription(path, 0L, 280L)
    }

    private suspend fun dispatchGestureVerified(
        actionName: String,
        gesture: GestureDescription,
        timeoutMs: Long
    ): ActionExecutionResult {
        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val accepted = runCatching {
                    dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) {
                                    cont.resume(
                                        ActionExecutionResult(
                                            success = true,
                                            action = actionName,
                                            message = "$actionName completed cleanly via dispatchGesture",
                                            methodUsed = "AccessibilityService.dispatchGesture"
                                        )
                                    )
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) {
                                    cont.resume(
                                        ActionExecutionResult(
                                            success = false,
                                            action = actionName,
                                            message = "$actionName was cancelled by Android gesture dispatcher",
                                            methodUsed = "AccessibilityService.dispatchGesture"
                                        )
                                    )
                                }
                            }
                        },
                        mainHandler
                    )
                }.getOrElse { err ->
                    if (cont.isActive) {
                        cont.resume(
                            ActionExecutionResult(
                                success = false,
                                action = actionName,
                                message = "$actionName exception: ${err.message ?: "unknown"}",
                                methodUsed = "AccessibilityService.dispatchGesture"
                            )
                        )
                    }
                    true
                }

                if (!accepted && cont.isActive) {
                    cont.resume(
                        ActionExecutionResult(
                            success = false,
                            action = actionName,
                            message = "$actionName rejected by Android OS before start (check permissions or overlapping window)",
                            methodUsed = "AccessibilityService.dispatchGesture"
                        )
                    )
                }
            }
        } ?: ActionExecutionResult(
            success = false,
            action = actionName,
            message = "$actionName timed out waiting for GestureResultCallback",
            methodUsed = "AccessibilityService.dispatchGesture"
        )

        recordAction(actionName, outcome.message, if (outcome.success) null else outcome.message)
        return outcome
    }

    fun typeTextIntoFocused(text: String): Boolean {
        if (deps.safetyPolicy().policy.value.emergencyStopped) return false
        val (root, pkg) = acquireFreshRootNode()
        if (!deps.safetyPolicy().canReadCurrentScreen(pkg.ifBlank { null })) return false
        if (root == null) return false
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: findFirstEditable(root)
            ?: return false
        runCatching { focused.refresh() }
        if (isNodePasswordProtected(focused)) {
            recordAction("TYPE_TEXT", "Blocked typing into protected password field", "Password protection active")
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
        recordAction("TYPE_TEXT", if (ok) "Injected text into input field" else "ACTION_SET_TEXT failed", if (ok) null else "Input rejected ACTION_SET_TEXT")
        return ok
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_TREE_DEPTH) return null
        if (node.isEditable && node.isEnabled && !isNodePasswordProtected(node)) return node
        val count = node.childCount.coerceAtMost(80)
        for (i in 0 until count) {
            findFirstEditable(runCatching { node.getChild(i) }.getOrNull(), depth + 1)?.let { return it }
        }
        return null
    }

    fun performBack(): Boolean {
        val ok = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
        recordAction("GLOBAL_BACK", if (ok) "Navigated Back" else "GLOBAL_ACTION_BACK failed", if (ok) null else "Back action rejected")
        return ok
    }

    fun performHome(): Boolean {
        val ok = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)
        recordAction("GLOBAL_HOME", if (ok) "Navigated Home" else "GLOBAL_ACTION_HOME failed", if (ok) null else "Home action rejected")
        return ok
    }

    fun performRecents(): Boolean {
        val ok = runCatching { performGlobalAction(GLOBAL_ACTION_RECENTS) }.getOrDefault(false)
        recordAction("GLOBAL_RECENTS", if (ok) "Opened Recents" else "GLOBAL_ACTION_RECENTS failed", if (ok) null else "Recents rejected")
        return ok
    }

    suspend fun captureScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            recordAction("A11Y_SCREENSHOT", "API < 30 triggered system screenshot only (no Bitmap access)", "Requires Android 11+ or MediaProjection")
            return null
        }
        val pkg = _foregroundPackage.value
        if (!deps.safetyPolicy().canReadCurrentScreen(pkg)) {
            recordAction("A11Y_SCREENSHOT", "Screenshot blocked by Safety/Gaming Guard", "Safety policy blocked")
            return null
        }
        return withTimeoutOrNull(4500L) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        screenshotExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val softCopy = runCatching {
                                    val hwBmp = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                    screenshot.hardwareBuffer.close()
                                    val copy = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBmp?.recycle()
                                    copy
                                }.getOrNull()
                                recordAction(
                                    "A11Y_SCREENSHOT",
                                    if (softCopy != null) "Captured ${softCopy.width}x${softCopy.height}" else "Failed to convert HardwareBuffer",
                                    if (softCopy == null) "Bitmap conversion failed" else null
                                )
                                if (cont.isActive) cont.resume(softCopy)
                            }

                            override fun onFailure(errorCode: Int) {
                                val err = "takeScreenshot failed with errorCode=$errorCode"
                                recordAction("A11Y_SCREENSHOT", err, err)
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                }.onFailure { err ->
                    recordAction("A11Y_SCREENSHOT", "Exception: ${err.message}", err.message)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    suspend fun saveScreenshotToGallery(): Result<String> {
        if (!deps.safetyPolicy().canReadCurrentScreen(_foregroundPackage.value)) {
            return Result.failure(IllegalStateException("Screenshot blocked on protected/banking screen."))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val ok = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            return if (ok) Result.success("Triggered system screenshot.")
            else Result.failure(IllegalStateException("System screenshot action unavailable."))
        }
        val bmp = captureScreenshotBitmap()
            ?: return Result.failure(IllegalStateException("Failed to capture display frame via AccessibilityService."))
        return withContext(Dispatchers.IO) {
            runCatching {
                val fileName = "Zehn_Screen_${System.currentTimeMillis()}.png"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MahiZehnAI")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert returned null")
                contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                } ?: error("Unable to open screenshot output stream")
                bmp.recycle()
                "Saved screenshot: Pictures/MahiZehnAI/$fileName"
            }
        }
    }

    private fun safeLabelFor(node: AccessibilityNodeInfo, fallback: String): String {
        if (isNodePasswordProtected(node)) return "[PROTECTED_FIELD]"
        return node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: fallback
    }

    private fun buildActionLabels(node: AccessibilityNodeInfo): List<String> {
        return runCatching {
            node.actionList.mapNotNull { action ->
                when (action.id) {
                    AccessibilityNodeInfo.ACTION_CLICK -> "CLICK"
                    AccessibilityNodeInfo.ACTION_LONG_CLICK -> "LONG_CLICK"
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "SCROLL_FORWARD"
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "SCROLL_BACKWARD"
                    AccessibilityNodeInfo.ACTION_SET_TEXT -> "SET_TEXT"
                    AccessibilityNodeInfo.ACTION_FOCUS -> "FOCUS"
                    else -> null
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    private fun isNodePasswordProtected(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        if (isPasswordViewId(node.viewIdResourceName)) return true
        return isPasswordInputType(node.inputType)
    }

    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }

    private fun recordAction(action: String, result: String, error: String?) {
        _diagnostics.update {
            it.copy(
                permissionEnabled = isAccessibilityPermissionEnabled(applicationContext),
                serviceConnected = _instance.value != null,
                lastAction = action,
                lastActionResult = result,
                lastError = error ?: it.lastError.takeIf { prev -> prev != "None" && error == null }?.let { "None" } ?: "None",
                lastUpdatedAtMs = System.currentTimeMillis()
            )
        }
    }

    private fun updateCapabilitiesDiagnostics(context: Context) {
        _diagnostics.update {
            it.copy(
                permissionEnabled = isAccessibilityPermissionEnabled(context),
                serviceConnected = _instance.value != null,
                gestureCapable = hasGestureCapability(),
                accessibilityScreenshotCapable = hasAccessibilityScreenshotCapability(),
                lastUpdatedAtMs = System.currentTimeMillis()
            )
        }
    }

    sealed class TapOutcome {
        data class Success(val message: String) : TapOutcome()
        data class Blocked(val reason: String) : TapOutcome()
        data class Failed(val reason: String) : TapOutcome()
    }

    companion object {
        private const val MAX_TREE_DEPTH = 28
        private const val MAX_RAW_NODES = 400
        private const val MAX_CAPTURED_NODES = 160

        private val _instance = MutableStateFlow<ZehnAccessibilityService?>(null)
        val instance: StateFlow<ZehnAccessibilityService?> = _instance.asStateFlow()

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _foregroundPackage = MutableStateFlow<String?>(null)
        val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

        private val _diagnostics = MutableStateFlow(AccessibilityDiagnostics())
        val diagnostics: StateFlow<AccessibilityDiagnostics> = _diagnostics.asStateFlow()

        var onAppOpenedCallback: ((String) -> Unit)? = null

        fun refreshDiagnosticsSnapshot(context: Context): AccessibilityDiagnostics {
            val enabled = isAccessibilityPermissionEnabled(context)
            val svc = _instance.value
            val updated = _diagnostics.value.copy(
                permissionEnabled = enabled,
                serviceConnected = svc != null,
                gestureCapable = svc?.hasGestureCapability() ?: false,
                accessibilityScreenshotCapable = svc?.hasAccessibilityScreenshotCapability() ?: (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R),
                lastUpdatedAtMs = System.currentTimeMillis()
            )
            _diagnostics.value = updated
            return updated
        }

        fun recordExternalAction(action: String, result: String, error: String? = null) {
            _diagnostics.update {
                it.copy(
                    lastAction = action,
                    lastActionResult = result,
                    lastError = error ?: "None",
                    lastUpdatedAtMs = System.currentTimeMillis()
                )
            }
        }

        fun isAccessibilityPermissionEnabled(context: Context): Boolean {
            val expectedComponent = ComponentName(context, ZehnAccessibilityService::class.java)
            val expectedFlat = expectedComponent.flattenToString()
            val expectedShort = expectedComponent.flattenToShortString()

            val fromManager = runCatching {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    ?.any { info ->
                        val si = info.resolveInfo?.serviceInfo
                        si != null && si.packageName == context.packageName &&
                            si.name == ZehnAccessibilityService::class.java.name
                    } ?: false
            }.getOrDefault(false)
            if (fromManager) return true

            val enabledSetting = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull().orEmpty()
            if (enabledSetting.isBlank()) return false

            return enabledSetting.split(':').any { entry ->
                val clean = entry.trim()
                clean.equals(expectedFlat, ignoreCase = true) ||
                    clean.equals(expectedShort, ignoreCase = true) ||
                    clean.endsWith("/${ZehnAccessibilityService::class.java.name}", ignoreCase = true) &&
                    clean.startsWith("${context.packageName}/", ignoreCase = true)
            }
        }

        private fun resolveAppLabel(context: Context, packageName: String): String {
            if (packageName.isBlank()) return ""
            return runCatching {
                val pm = context.packageManager
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(packageName, 0)
                }
                pm.getApplicationLabel(appInfo).toString()
            }.getOrDefault(packageName)
        }

        internal fun isPasswordViewId(viewId: String?): Boolean {
            val v = viewId?.lowercase() ?: return false
            return v.contains("password") ||
                v.contains("passcode") ||
                v.contains("pin_input") ||
                v.contains("pin_entry") ||
                v.contains("otp")
        }

        internal fun isPasswordInputType(inputType: Int): Boolean {
            if (inputType == InputType.TYPE_NULL) return false
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val clazz = inputType and InputType.TYPE_MASK_CLASS
            return when (clazz) {
                InputType.TYPE_CLASS_TEXT ->
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                InputType.TYPE_CLASS_NUMBER ->
                    variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }
    }
}
