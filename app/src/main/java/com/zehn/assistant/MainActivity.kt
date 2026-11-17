package com.zehn.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.zehn.assistant.data.model.OrbState
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.CameraVisionDialog
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.components.LocalZehnHapticsEnabled
import com.zehn.assistant.ui.components.NotchedBottomNavBar
import com.zehn.assistant.ui.screens.AccessibilityDebugScreen
import com.zehn.assistant.ui.screens.AccountScreen
import com.zehn.assistant.ui.screens.ApiKeysScreen
import com.zehn.assistant.ui.screens.AutonomousUpgradeCenterScreen
import com.zehn.assistant.ui.screens.CallAssistantScreen
import com.zehn.assistant.ui.screens.ChatScreen
import com.zehn.assistant.ui.screens.ConversationsScreen
import com.zehn.assistant.ui.screens.DiagnosticsScreen
import com.zehn.assistant.ui.screens.HomeScreen
import com.zehn.assistant.ui.screens.HubScreen
import com.zehn.assistant.ui.screens.MemoryScreen
import com.zehn.assistant.ui.screens.MissionModeScreen
import com.zehn.assistant.ui.screens.OnboardingScreen
import com.zehn.assistant.ui.screens.OpenSourceDiscoveryScreen
import com.zehn.assistant.ui.screens.OrbScreen
import com.zehn.assistant.ui.screens.PermissionsScreen
import com.zehn.assistant.ui.screens.SecurityScreen
import com.zehn.assistant.ui.screens.SettingsScreen
import com.zehn.assistant.ui.screens.SkillsScreen
import com.zehn.assistant.ui.screens.TouchGuardScreen
import com.zehn.assistant.ui.screens.TriggersScreen
import com.zehn.assistant.ui.screens.UpdateScreen
import com.zehn.assistant.ui.screens.VoiceAndAiModelsScreen
import com.zehn.assistant.ui.theme.ZehnColors
import com.zehn.assistant.ui.theme.ZehnTheme
import com.zehn.assistant.voice.WakeWordService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var launchIntoOrb by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAssistIntent(intent)

        setContent {
            ZehnTheme {
                ZehnRootApp(
                    initialOpenOrb = launchIntoOrb,
                    onConsumeOrbTrigger = { launchIntoOrb = false }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkAssistIntent(intent)
    }

    private fun checkAssistIntent(intent: Intent?) {
        val action = intent?.action
        if (action == WakeWordService.ACTION_TRIGGER_ORB ||
            action == Intent.ACTION_ASSIST ||
            action == Intent.ACTION_VOICE_COMMAND
        ) {
            launchIntoOrb = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZehnRootApp(
    initialOpenOrb: Boolean,
    onConsumeOrbTrigger: () -> Unit,
    vm: ZehnViewModel = hiltViewModel()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val voiceState by vm.voiceUiState.collectAsStateWithLifecycle()
    val pendingConfirm by vm.pendingConfirmation.collectAsStateWithLifecycle()
    val banner by vm.toastBanner.collectAsStateWithLifecycle()
    val crashFileText by vm.crashFileText.collectAsStateWithLifecycle()
    val safeModeActive by vm.safeModeActive.collectAsStateWithLifecycle()
    val crashReport by vm.lastCrashReport.collectAsStateWithLifecycle()
    val safeModeReply by vm.safeModeChatReply.collectAsStateWithLifecycle()
    val safeModeBusy by vm.safeModeChatBusy.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(banner) {
        if (!banner.isNullOrBlank()) {
            snackbarHostState.showSnackbar(banner!!)
            vm.clearBanner()
        }
    }

    val context = LocalContext.current
    val navMicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.voiceCoordinator.startVoiceTurn()
        } else {
            vm.showBanner("Microphone permission is required for voice input.")
        }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var activeSubRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var showChatBottomSheet by remember { mutableStateOf(false) }
    var showCameraDialog by remember { mutableStateOf(false) }
    var cameraStartFront by remember { mutableStateOf(false) }
    var cameraStartVideo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.cameraLaunchRequests.collect { req ->
            cameraStartFront = req.useFrontCamera
            cameraStartVideo = req.isVideoMode
            showCameraDialog = true
        }
    }

    LaunchedEffect(initialOpenOrb) {
        if (initialOpenOrb) {
            activeSubRoute = "orb"
            onConsumeOrbTrigger()
        }
    }

    if (activeSubRoute != null) {
        BackHandler { activeSubRoute = null }
    }

    CompositionLocalProvider(LocalZehnHapticsEnabled provides settings.hapticFeedbackEnabled) {
        if (safeModeActive) {
            val combinedCrashText = listOfNotNull(
                crashFileText?.takeIf { it.isNotBlank() },
                crashReport?.sanitizedStackTrace?.takeIf { it.isNotBlank() },
                crashReport?.sanitizedMessage
            ).firstOrNull() ?: "Repeated crashes detected within 5 minutes. Services paused."

            SafeModeScreen(
                crashText = combinedCrashText,
                crashCount = crashReport?.crashCountInWindow ?: 3,
                basicChatReply = safeModeReply,
                basicChatBusy = safeModeBusy,
                snackbarHostState = snackbarHostState,
                onSendBasicChat = { vm.sendSafeModeBasicChat(it) },
                onCreateGitHubIssue = { vm.createSafeModeGitHubIssue() },
                onTriggerAutoDevFix = { vm.queueSafeModeAutoDevFix() },
                onRestoreBackup = { vm.restoreLatestPreUpdateBackup() },
                onClearLogs = { vm.clearCrashLogFile() },
                onExitSafeMode = { vm.exitSafeMode() }
            )
            return@CompositionLocalProvider
        }

        if (!crashFileText.isNullOrBlank()) {
            CrashLogScreen(
                crashText = crashFileText!!,
                onClear = { vm.clearCrashLogFile() }
            )
            return@CompositionLocalProvider
        }

        if (!settings.onboardingCompleted) {
            OnboardingScreen(
                safetyPolicy = vm.safetyPolicy,
                onFinish = { vm.completeOnboarding() }
            )
            return@CompositionLocalProvider
        }

        val tabStateHolder = rememberSaveableStateHolder()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ZehnColors.Background)
        ) {
            // Content area draws edge-to-edge behind the floating notched navbar
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                if (activeSubRoute != null && activeSubRoute != "orb") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { activeSubRoute = null }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = ZehnColors.Cyan
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = routeTitle(activeSubRoute!!),
                            style = MaterialTheme.typography.titleMedium,
                            color = ZehnColors.SlateMuted
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = activeSubRoute ?: "tab_$selectedTab",
                        transitionSpec = {
                            (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                                scaleIn(initialScale = 0.97f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)))
                                .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
                        },
                        label = "zehn_screen_transition"
                    ) { routeKey ->
                        when (routeKey) {
                            "tab_0" -> tabStateHolder.SaveableStateProvider("tab_0") {
                                HomeScreen(
                                    vm = vm,
                                    onOpenOrb = { activeSubRoute = "orb" },
                                    onOpenChatSheet = { showChatBottomSheet = true },
                                    onOpenMission = { activeSubRoute = "mission" },
                                    onOpenModels = { activeSubRoute = "voice_models" },
                                    onOpenSecurity = { activeSubRoute = "security" },
                                    onOpenCamera = { showCameraDialog = true }
                                )
                            }
                            "tab_1" -> tabStateHolder.SaveableStateProvider("tab_1") {
                                ChatScreen(
                                    vm = vm,
                                    isBottomSheet = false,
                                    onOpenConversations = { activeSubRoute = "conversations" },
                                    onOpenCamera = { showCameraDialog = true }
                                )
                            }
                            "tab_2" -> tabStateHolder.SaveableStateProvider("tab_2") {
                                TriggersScreen(vm = vm)
                            }
                            "tab_3" -> tabStateHolder.SaveableStateProvider("tab_3") {
                                HubScreen(onNavigate = { dest -> activeSubRoute = dest })
                            }
                            "orb" -> OrbScreen(vm = vm, onBack = { activeSubRoute = null })
                            "voice_models" -> VoiceAndAiModelsScreen(vm = vm)
                            "skills" -> SkillsScreen(vm = vm)
                            "memory" -> MemoryScreen(vm = vm)
                            "security" -> SecurityScreen(vm = vm)
                            "touch_guard" -> TouchGuardScreen(
                                vm = vm,
                                onOpenAccessibilityDebug = { activeSubRoute = "accessibility_debug" }
                            )
                            "mission" -> MissionModeScreen(vm = vm)
                            "permissions" -> PermissionsScreen(vm = vm)
                            "api_keys" -> ApiKeysScreen(vm = vm)
                            "conversations" -> ConversationsScreen(vm = vm) {
                                activeSubRoute = null
                                selectedTab = 1
                            }
                            "settings" -> SettingsScreen(vm = vm)
                            "call_assistant" -> CallAssistantScreen(vm = vm)
                            "open_source_discovery" -> OpenSourceDiscoveryScreen(vm = vm)
                            "upgrade_center" -> AutonomousUpgradeCenterScreen(vm = vm)
                            "diagnostics" -> DiagnosticsScreen(
                                vm = vm,
                                onOpenAccessibilityDebug = { activeSubRoute = "accessibility_debug" }
                            )
                            "accessibility_debug" -> AccessibilityDebugScreen(vm = vm)
                            "update" -> UpdateScreen(vm = vm)
                            "account" -> AccountScreen(vm = vm)
                        }
                    }
                }
            }

            // Snackbar feedback
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 112.dp, start = 16.dp, end = 16.dp)
            )

            // Floating Notched Bottom Navbar (edge-to-edge transparent behind it)
            NotchedBottomNavBar(
                selectedTabIndex = if (activeSubRoute == null) selectedTab else -1,
                orbState = voiceState.orbState,
                onSelectTab = { idx ->
                    activeSubRoute = null
                    selectedTab = idx
                },
                onMicClick = {
                    activeSubRoute = "orb"
                    if (voiceState.orbState == OrbState.IDLE) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            vm.voiceCoordinator.startVoiceTurn()
                        } else {
                            navMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        vm.voiceCoordinator.bargeIn()
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // Draggable Chat Bottom Sheet
            if (showChatBottomSheet) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                ModalBottomSheet(
                    onDismissRequest = { showChatBottomSheet = false },
                    sheetState = sheetState,
                    containerColor = ZehnColors.SurfaceClay,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.84f)) {
                        ChatScreen(
                            vm = vm,
                            isBottomSheet = true,
                            onOpenConversations = {
                                showChatBottomSheet = false
                                activeSubRoute = "conversations"
                            },
                            onOpenCamera = { showCameraDialog = true }
                        )
                    }
                }
            }

            // CameraX + ML Kit OCR/QR/Photo/Video Dialog
            if (showCameraDialog) {
                CameraVisionDialog(
                    initialUseFrontCamera = cameraStartFront,
                    initialVideoMode = cameraStartVideo,
                    onDismiss = {
                        showCameraDialog = false
                        cameraStartFront = false
                        cameraStartVideo = false
                    },
                    onCapturedPhoto = { uri ->
                        selectedTab = 1
                        activeSubRoute = null
                        vm.sendChatMessage("Analyze this camera image carefully.", attachedImageUri = uri)
                    },
                    onScannedOcrOrQr = { scanned ->
                        selectedTab = 1
                        activeSubRoute = null
                        vm.sendChatMessage("Process this scanned text/QR:\n$scanned")
                    },
                    onSavedMediaNotice = { notice -> vm.showBanner(notice) }
                )
            }

            // Mandatory Safety Confirmation Modal (Payments, Calls, Messages, Deletes, Social ToS)
            pendingConfirm?.let { req ->
                AlertDialog(
                    onDismissRequest = { vm.safetyPolicy.resolveConfirmation(false) },
                    containerColor = ZehnColors.SurfaceClay,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = ZehnColors.Amber)
                            Spacer(Modifier.width(8.dp))
                            Text(req.title, color = ZehnColors.FrostWhite)
                        }
                    },
                    text = {
                        Column {
                            Text(req.detail, color = ZehnColors.FrostWhite)
                            if (!req.warningBanner.isNullOrBlank()) {
                                Spacer(Modifier.height(10.dp))
                                ClayGlassCard {
                                    Text(req.warningBanner, color = ZehnColors.Amber, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        ClayActionButton(text = "Confirm", onClick = { vm.safetyPolicy.resolveConfirmation(true) })
                    },
                    dismissButton = {
                        TextButton(onClick = { vm.safetyPolicy.resolveConfirmation(false) }) {
                            Text("Cancel", color = Color(0xFFFB7185))
                        }
                    }
                )
            }
        }
    }
}

private fun routeTitle(route: String): String = when (route) {
    "orb" -> "Voice Orb"
    "voice_models" -> "Voice & AI Models"
    "skills" -> "Skills"
    "memory" -> "Memory & RAG"
    "security" -> "Security"
    "touch_guard" -> "Touch Guard"
    "mission" -> "Mission Mode"
    "permissions" -> "Permissions"
    "api_keys" -> "API Keys"
    "conversations" -> "Conversations"
    "settings" -> "Settings"
    "call_assistant" -> "Call Assistant"
    "open_source_discovery" -> "Open-Source Discovery"
    "upgrade_center" -> "Autonomous Upgrade Center"
    "diagnostics" -> "System Diagnostics"
    "accessibility_debug" -> "Accessibility Debug"
    "update" -> "Update"
    "account" -> "Account"
    else -> "Zehn AI"
}

@Composable
fun CrashLogScreen(
    crashText: String,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ZehnColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Crash log warning",
                    tint = ZehnColors.Amber
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Crash Report Detected",
                        style = MaterialTheme.typography.headlineSmall,
                        color = ZehnColors.FrostWhite
                    )
                    Text(
                        text = "Saved to filesDir/crash.txt on previous crash",
                        style = MaterialTheme.typography.labelMedium,
                        color = ZehnColors.Amber
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            ClayGlassCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("crash_log_card")
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = crashText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp
                            ),
                            color = ZehnColors.FrostWhite,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ClayActionButton(
                    text = "Copy",
                    icon = Icons.Filled.ContentCopy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("copy_crash_log_button"),
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Zehn AI Crash Log", crashText))
                        scope.launch {
                            snackbarHostState.showSnackbar("Crash log copied to clipboard")
                        }
                    }
                )
                ClayActionButton(
                    text = "Clear",
                    icon = Icons.Filled.Delete,
                    isDanger = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("clear_crash_log_button"),
                    onClick = onClear
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 84.dp, start = 16.dp, end = 16.dp)
        )
    }
}

@Composable
fun SafeModeScreen(
    crashText: String,
    crashCount: Int,
    basicChatReply: String,
    basicChatBusy: Boolean,
    snackbarHostState: SnackbarHostState,
    onSendBasicChat: (String) -> Unit,
    onCreateGitHubIssue: () -> Unit,
    onTriggerAutoDevFix: () -> Unit,
    onRestoreBackup: () -> Unit,
    onClearLogs: () -> Unit,
    onExitSafeMode: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var promptDraft by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ZehnColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ClayGlassCard(accentGlow = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = "Safe mode warning", tint = ZehnColors.RoseStop)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Safe Mode Active ($crashCount crashes in 5 min)",
                            style = MaterialTheme.typography.titleLarge,
                            color = ZehnColors.RoseStop
                        )
                        Text(
                            text = "Background discovery, Auto-Dev & Accessibility auto-tasks paused to protect your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ZehnColors.SlateMuted
                        )
                    }
                }
            }

            ClayGlassCard {
                Text("Recovery Actions", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClayActionButton(
                        text = "Create GitHub Issue",
                        icon = Icons.Filled.BugReport,
                        modifier = Modifier.weight(1f).testTag("safe_mode_issue_button"),
                        onClick = onCreateGitHubIssue
                    )
                    ClayActionButton(
                        text = "Auto-Dev Fix",
                        icon = Icons.Filled.Build,
                        modifier = Modifier.weight(1f).testTag("safe_mode_autodev_button"),
                        onClick = onTriggerAutoDevFix
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClayActionButton(
                        text = "Restore Backup",
                        icon = Icons.Filled.Restore,
                        modifier = Modifier.weight(1f),
                        onClick = onRestoreBackup
                    )
                    ClayActionButton(
                        text = "Exit Safe Mode",
                        isDanger = true,
                        modifier = Modifier.weight(1f).testTag("safe_mode_exit_button"),
                        onClick = onExitSafeMode
                    )
                }
            }

            ClayGlassCard {
                Text("Sanitized Crash Log", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Amber)
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = crashText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            ),
                            color = ZehnColors.FrostWhite
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton(
                        text = "Copy Log",
                        icon = Icons.Filled.ContentCopy,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Zehn Safe Mode Crash", crashText))
                            scope.launch { snackbarHostState.showSnackbar("Sanitized crash log copied") }
                        }
                    )
                    ClayActionButton(
                        text = "Clear Log",
                        icon = Icons.Filled.Delete,
                        isDanger = true,
                        modifier = Modifier.weight(1f),
                        onClick = onClearLogs
                    )
                }
            }

            ClayGlassCard {
                Text("Basic Safe-Mode Chat", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Text(
                    "Direct minimal LLM chat without background workers or device actions.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.SlateMuted
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        label = { Text("Ask Zehn in Safe Mode") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        enabled = !basicChatBusy && promptDraft.isNotBlank(),
                        onClick = {
                            val q = promptDraft
                            promptDraft = ""
                            onSendBasicChat(q)
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Safe Mode Chat", tint = ZehnColors.Cyan)
                    }
                }
                if (basicChatBusy) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ZehnColors.Cyan)
                }
                if (basicChatReply.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(basicChatReply, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.FrostWhite)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
        )
    }
}

