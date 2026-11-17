package com.zehn.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zehn.assistant.BuildConfig
import com.zehn.assistant.R
import com.zehn.assistant.agent.ZehnAccessibilityService
import com.zehn.assistant.ai.ProviderCatalog
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.components.ClayToggleSwitch
import com.zehn.assistant.ui.theme.ZehnColors
import kotlinx.coroutines.launch
import java.util.TimeZone

data class HubDestinationItem(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

@Composable
fun HubScreen(onNavigate: (String) -> Unit) {
    val destinations = remember {
        listOf(
            HubDestinationItem("orb", "Orb", "4-state glowing voice sphere", Icons.Filled.AutoFixHigh),
            HubDestinationItem("voice_models", "Voice & AI Models", "8 voices, 8 providers & Gemma 4", Icons.Filled.Memory),
            HubDestinationItem("skills", "Skills", "SKILL.md function playbooks", Icons.Filled.Extension),
            HubDestinationItem("memory", "Memory & RAG", "4-tier memory & PDF projects", Icons.Filled.Psychology),
            HubDestinationItem("security", "Security", "Safety toggles & action logs", Icons.Filled.Security),
            HubDestinationItem("touch_guard", "Touch Guard", "Gaming guard, bubble & Samsung", Icons.Filled.TouchApp),
            HubDestinationItem("mission", "Mission Mode", "AgentLoop, Workflows & Flows", Icons.Filled.RocketLaunch),
            HubDestinationItem("call_assistant", "Call Assistant", "VIP filter, auto-answer & VoIP", Icons.Filled.TouchApp),
            HubDestinationItem("open_source_discovery", "OS Discovery", "Safe GitHub & Hugging Face inspect", Icons.Filled.Extension),
            HubDestinationItem("upgrade_center", "Upgrade Center", "Autonomous Research, Auto-Dev & APK", Icons.Filled.SystemUpdate),
            HubDestinationItem("accessibility_debug", "Accessibility Debug", "Screen reading, click, scroll, swipe & OCR", Icons.Filled.TouchApp),
            HubDestinationItem("diagnostics", "Diagnostics", "Truthful Active / Blocked audit", Icons.Filled.VerifiedUser),
            HubDestinationItem("permissions", "Permissions", "System & default assistant audit", Icons.Filled.VerifiedUser),
            HubDestinationItem("api_keys", "API Keys", "Keystore AES-GCM vault", Icons.Filled.Key),
            HubDestinationItem("conversations", "Conversations", "Search Room chat history", Icons.Filled.History),
            HubDestinationItem("settings", "Settings", "Wake word, haptics, call AI, backup", Icons.Filled.Settings),
            HubDestinationItem("update", "Update", "App & LiteRT runtime info", Icons.Filled.SystemUpdate),
            HubDestinationItem("account", "Account", "Device specs & local profile", Icons.Filled.AccountCircle)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text("Zehn Command Hub", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
        Text("All ${destinations.size} modules, security controls & engines", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(destinations, key = { it.route }) { item ->
                ClayGlassCard(
                    onClick = { onNavigate(item.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(item.icon, contentDescription = item.title, tint = ZehnColors.Cyan, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(item.title, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                    Spacer(Modifier.height(2.dp))
                    Text(item.subtitle, style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                }
            }
        }
    }
}

@Composable
fun PermissionsScreen(vm: ZehnViewModel) {
    val context = LocalContext.current
    val accSvc by vm.accessibilityConnected.collectAsStateWithLifecycle()
    val notifConnected by vm.notificationListenerConnected.collectAsStateWithLifecycle()

    val runtimePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun granted(p: String): Boolean =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val checks = listOf(
        Triple("Microphone (Voice & Wake Word)", granted(Manifest.permission.RECORD_AUDIO)) {
            runtimePermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        Triple("Camera (Vision, OCR & QR)", granted(Manifest.permission.CAMERA)) {
            runtimePermLauncher.launch(Manifest.permission.CAMERA)
        },
        Triple("Phone & Answer Calls", granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.CALL_PHONE)) {
            runtimePermLauncher.launch(Manifest.permission.CALL_PHONE)
        },
        Triple("Send SMS (Confirmed Actions)", granted(Manifest.permission.SEND_SMS)) {
            runtimePermLauncher.launch(Manifest.permission.SEND_SMS)
        },
        Triple("Accessibility Agent Service", accSvc != null || ZehnAccessibilityService.isAccessibilityPermissionEnabled(context)) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        Triple("Notification Intelligence Listener", notifConnected) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        Triple("Floating Bubble Overlay", Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        },
        Triple("Modify System Brightness", Settings.System.canWrite(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
        },
        Triple(
            "Install Unknown Apps (Self-Updater)",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true
        ) {
            vm.openUnknownSourcesSettings()
        }
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Permissions Center", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
            Text("Real-time Android permission & system integration status", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        }
        items(checks.size) { idx ->
            val (label, ok, action) = checks[idx]
            ClayGlassCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                        Text(if (ok) "GRANTED" else "ACTION NEEDED", style = MaterialTheme.typography.labelSmall, color = if (ok) ZehnColors.Emerald else ZehnColors.Amber)
                    }
                    ClayActionButton(text = if (ok) "Review" else "Grant", onClick = action)
                }
            }
        }
        item {
            ClayGlassCard(accentGlow = true) {
                Text("Default Digital Assistant (Home Long-Press)", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Spacer(Modifier.height(4.dp))
                Text("Set Zehn AI as your device's VoiceInteractionService assistant app.", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                Spacer(Modifier.height(8.dp))
                ClayActionButton(text = "Open Assistant Settings", onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                })
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: ZehnViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val liveDiscovery by vm.liveModelsDiscovery.collectAsStateWithLifecycle()
    val voiceState by vm.voiceUiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var autoReplyDraft by remember(settings.autoReplySmsText) { mutableStateOf(settings.autoReplySmsText) }
    var customLiveModelDraft by remember(settings.geminiLiveCustomModel) { mutableStateOf(settings.geminiLiveCustomModel) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val jsonText = vm.exportBackupJson()
                context.contentResolver.openOutputStream(uri)?.use { it.write(jsonText.toByteArray()) }
                vm.showBanner("Backup JSON exported")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (raw.isNotBlank()) vm.importBackupJson(raw)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
        }

        item {
            ClayGlassCard(accentGlow = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Gemini Live API (Native Audio)", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                        Text(
                            "16kHz PCM mic -> 24kHz AudioTrack + tools; auto-fallback to STT/LLM/TTS",
                            style = MaterialTheme.typography.bodySmall,
                            color = ZehnColors.SlateMuted
                        )
                    }
                    IconButton(
                        onClick = { vm.refreshAvailableLiveModels() },
                        modifier = Modifier.testTag("refresh_live_models_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh Live Models", tint = ZehnColors.Cyan)
                    }
                }

                SettingToggle(
                    title = "Enable Gemini Live Voice",
                    subtitle = "Uses Gemini Live WebSocket first when online; falls back automatically if unavailable",
                    checked = settings.geminiLiveVoiceEnabled
                ) {
                    vm.toggleGeminiLiveVoice(it)
                }

                Text(
                    text = liveDiscovery.statusMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (liveDiscovery.availableModels.isNotEmpty()) ZehnColors.Emerald else ZehnColors.Amber
                )
                if (voiceState.activeLiveModel.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "In Use: ${voiceState.activeLiveModel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ZehnColors.Cyan
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Primary: ${settings.geminiLiveModel} • Fallback: ${settings.geminiLiveFallbackModel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.SlateMuted
                )

                if (liveDiscovery.availableModels.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Available Live Models (Select Primary)", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        liveDiscovery.availableModels.forEach { m ->
                            FilterChip(
                                selected = settings.geminiLiveModel == m.id && settings.geminiLiveCustomModel.isBlank(),
                                onClick = { vm.setGeminiLiveModel(m.id) },
                                label = { Text(m.id) }
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("Available Live Models (Select Fallback)", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        liveDiscovery.availableModels.forEach { m ->
                            FilterChip(
                                selected = settings.geminiLiveFallbackModel == m.id,
                                onClick = { vm.setGeminiLiveFallbackModel(m.id) },
                                label = { Text(m.id) }
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Configured fallback chain: ${ProviderCatalog.DEFAULT_GEMINI_LIVE_MODEL} -> ${ProviderCatalog.FALLBACK_GEMINI_LIVE_MODEL}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZehnColors.SlateMuted
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customLiveModelDraft,
                    onValueChange = { customLiveModelDraft = it },
                    label = { Text("Custom Live Model Name (optional)") },
                    placeholder = { Text(ProviderCatalog.DEFAULT_GEMINI_LIVE_MODEL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("custom_live_model_input")
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClayActionButton(
                        text = "Save Custom Model",
                        modifier = Modifier.weight(1f).testTag("save_custom_live_model_button"),
                        onClick = { vm.setGeminiLiveCustomModel(customLiveModelDraft) }
                    )
                    if (settings.geminiLiveCustomModel.isNotBlank()) {
                        ClayActionButton(
                            text = "Clear",
                            isDanger = true,
                            onClick = {
                                customLiveModelDraft = ""
                                vm.setGeminiLiveCustomModel("")
                            }
                        )
                    }
                }
            }
        }

        item {
            ClayGlassCard {
                SettingToggle("Haptic Feedback", "Tactile vibration on orb, buttons & switches", settings.hapticFeedbackEnabled) {
                    vm.toggleHaptics(it)
                }
                SettingToggle("Continuous Voice Conversation", "Auto-relisten after Zehn finishes speaking (Classic pipeline)", settings.continuousConversationEnabled) {
                    vm.toggleContinuousConversation(it)
                }
                SettingToggle("Offline 'Hey Zehn' Wake Word", "Foreground microphone readiness notification", settings.wakeWordEnabled) {
                    vm.toggleWakeWord(it)
                }
                SettingToggle("Realtime WebSocket Mode", "Responses-API duplex WS backend", settings.realtimeWebSocketEnabled) {
                    vm.toggleRealtimeWs(it)
                }
                SettingToggle("Webhook POST Backend", "Send voice text to custom HTTP endpoint", settings.webhookModeEnabled) {
                    vm.toggleWebhookMode(it)
                }
                SettingToggle("Call Assistant Announcements", "Announce caller & voice answer/reject", settings.callAssistantEnabled) {
                    vm.toggleCallAssistant(it)
                }
                SettingToggle("Auto-Reply SMS on Rejected Call", "Send polite SMS if you reject via Zehn", settings.autoReplySmsEnabled) {
                    vm.toggleAutoReplySms(it, autoReplyDraft)
                }
                OutlinedTextField(
                    value = autoReplyDraft,
                    onValueChange = { autoReplyDraft = it },
                    label = { Text("Auto-Reply SMS Template") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            ClayGlassCard {
                Text("TTS Rate (${"%.2f".format(settings.voiceRate)}x) & Pitch (${"%.2f".format(settings.voicePitch)}x)", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Slider(value = settings.voiceRate, onValueChange = { vm.setVoiceTuning(it, settings.voicePitch) }, valueRange = 0.7f..1.5f)
                Slider(value = settings.voicePitch, onValueChange = { vm.setVoiceTuning(settings.voiceRate, it) }, valueRange = 0.7f..1.4f)
            }
        }

        item {
            ClayGlassCard(accentGlow = true) {
                Text("JSON Backup Export / Import", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Text("Export settings, persona, 4-tier memory, skills, workflows, triggers & chats.", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClayActionButton(text = "Export JSON", icon = Icons.Filled.Backup, modifier = Modifier.weight(1f), onClick = {
                        exportLauncher.launch("zehn_backup_${System.currentTimeMillis()}.json")
                    })
                    ClayActionButton(text = "Import JSON", icon = Icons.Filled.Download, modifier = Modifier.weight(1f), onClick = {
                        importLauncher.launch(arrayOf("application/json"))
                    })
                }
            }
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        }
        ClayToggleSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun UpdateScreen(vm: ZehnViewModel) {
    val onDevice by vm.onDeviceState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val releases by vm.releaseHistory.collectAsStateWithLifecycle()
    val dlProgress by vm.apkDownloadProgress.collectAsStateWithLifecycle()
    val installStatus by vm.installStatus.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Updates & Runtime", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
        }
        item {
            ClayGlassCard(accentGlow = true) {
                Text("Zehn AI v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE} • com.zehn.assistant)", style = MaterialTheme.typography.titleLarge, color = ZehnColors.Cyan)
                Spacer(Modifier.height(6.dp))
                Text("Channel: ${settings.releaseChannel} • Target SDK 35 • ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.FrostWhite)
                Spacer(Modifier.height(6.dp))
                val runtimeStatus = if (vm.liteRtManager.isNativeRuntimeAvailable()) "Native AAR Loaded" else "Interface Ready (Cloud/Fallback)"
                Text("LiteRT-LM Runtime: $runtimeStatus • Downloaded offline models: ${onDevice.downloadedModels.size}", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                if (dlProgress.statusMessage.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(dlProgress.statusMessage, style = MaterialTheme.typography.labelSmall, color = ZehnColors.Emerald)
                }
                if (installStatus.state != "IDLE") {
                    Text("Installer (${installStatus.state}): ${installStatus.message}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton(text = "Refresh Hardware", modifier = Modifier.weight(1f), onClick = { vm.refreshTelemetry() })
                    ClayActionButton(text = "Check Releases", modifier = Modifier.weight(1f), onClick = { vm.checkForApkUpdates() })
                }
            }
        }
        items(releases.size) { idx ->
            val rel = releases[idx]
            ClayGlassCard {
                Text("${rel.tagName} (code ${rel.parsedVersionCode ?: "?"}) • ${rel.releaseName}", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Text(rel.compatibilityNote, style = MaterialTheme.typography.labelSmall, color = ZehnColors.Emerald)
                if (rel.sha256.isNotBlank()) {
                    Text("SHA-256: ${rel.sha256.take(18)}...", style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton(text = "Download & Install", modifier = Modifier.weight(1f), onClick = { vm.downloadAndInstallRelease(rel) })
                    ClayActionButton(text = "Browser", modifier = Modifier.weight(0.6f), onClick = { vm.openReleaseDownload(rel) })
                }
            }
        }
    }
}

@Composable
fun AccountScreen(vm: ZehnViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val onDevice by vm.onDeviceState.collectAsStateWithLifecycle()
    var nameDraft by remember(settings.userDisplayName) { mutableStateOf(settings.userDisplayName) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Account & Device", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
        }
        item {
            ClayGlassCard(accentGlow = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.img_zehn_avatar_1794764579421),
                        contentDescription = "Zehn Profile Core",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(settings.userDisplayName, style = MaterialTheme.typography.titleLarge, color = ZehnColors.FrostWhite)
                        Text("Zero Analytics • Local Keystore Identity", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it }, label = { Text("Display Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                ClayActionButton(text = "Update Profile", onClick = { vm.updateDisplayName(nameDraft) })
            }
        }
        item {
            ClayGlassCard {
                Text("Device Hardware Report", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Model: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "RAM: ${onDevice.totalRamMb} MB • Free Storage: ${onDevice.availableStorageMb} MB\n" +
                        "Local Timezone: ${TimeZone.getDefault().id}\n" +
                        "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZehnColors.SlateMuted
                )
                Spacer(Modifier.height(10.dp))
                ClayActionButton(text = "Wipe Encrypted API Vault", isDanger = true, onClick = {
                    vm.crypto.clearAllSecrets()
                    vm.showBanner("Encrypted API vault wiped")
                })
            }
        }
    }
}
