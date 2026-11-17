package com.zehn.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zehn.assistant.agent.ZehnAccessibilityService
import com.zehn.assistant.data.model.VoiceLanguage
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.components.ClayToggleSwitch
import com.zehn.assistant.ui.theme.ZehnColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CallAssistantScreen(vm: ZehnViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val incomingCaller by vm.callAssistant.incomingCaller.collectAsStateWithLifecycle()
    val recentCalls by vm.recentCallRecords.collectAsStateWithLifecycle()
    val cap = remember(settings) { vm.callCapabilityReport() }

    var enabled by remember(settings.callAssistantEnabled) { mutableStateOf(settings.callAssistantEnabled) }
    var autoAnswer by remember(settings.callAutoAnswerEnabled) { mutableStateOf(settings.callAutoAnswerEnabled) }
    var delaySec by remember(settings.callAutoAnswerDelaySec) { mutableIntStateOf(settings.callAutoAnswerDelaySec) }
    var vipCsv by remember(settings.vipContactsCsv) { mutableStateOf(settings.vipContactsCsv) }
    var callLang by remember(settings.callLanguage) { mutableStateOf(settings.callLanguage) }
    var prompt by remember(settings.callReceptionistPrompt) { mutableStateOf(settings.callReceptionistPrompt) }
    var voipEnabled by remember(settings.voipSipModeEnabled) { mutableStateOf(settings.voipSipModeEnabled) }
    var voipUri by remember(settings.voipSipServerUri) { mutableStateOf(settings.voipSipServerUri) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Autonomous Call Assistant", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
            Text("Caller ID screening, VIP bypass, auto-answer countdown & VoIP bridge config", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        }

        incomingCaller?.let { caller ->
            item {
                ClayGlassCard(accentGlow = true) {
                    Text("Incoming Call Active: $caller", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClayActionButton(text = "Answer", icon = Icons.Filled.Call, modifier = Modifier.weight(1f), onClick = {
                            vm.showBanner(vm.callAssistant.answerIncomingCall())
                        })
                        ClayActionButton(text = "Reject + SMS", isDanger = true, modifier = Modifier.weight(1f), onClick = {
                            vm.showBanner(vm.callAssistant.rejectIncomingCall(settings.autoReplySmsEnabled, caller))
                        })
                    }
                }
            }
        }

        item {
            ClayGlassCard {
                Text("Android Telecom Capability Truth", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Amber)
                Spacer(Modifier.height(4.dp))
                Text(cap.restrictionSummary, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Phone State: ${if (cap.readPhoneStateGranted) "GRANTED" else "PERMISSION REQUIRED"} • Answer/End: ${if (cap.answerCallsGranted) "GRANTED" else "PERMISSION REQUIRED"} • Contacts Lookup: ${if (cap.readContactsGranted) "GRANTED" else "OPTIONAL"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.Cyan
                )
            }
        }

        item {
            ClayGlassCard(accentGlow = true) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Call Assistant", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.titleMedium)
                        Text("Announce callers locally and log interactions", color = ZehnColors.SlateMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    ClayToggleSwitch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Answer Non-VIP Calls", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.titleMedium)
                        Text("Answers after countdown unless caller matches VIP list", color = ZehnColors.SlateMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    ClayToggleSwitch(checked = autoAnswer, onCheckedChange = { autoAnswer = it })
                }
                Text("Auto-Answer Delay: ${delaySec}s", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                Slider(value = delaySec.toFloat(), onValueChange = { delaySec = it.toInt().coerceIn(2, 15) }, valueRange = 2f..15f)

                Spacer(Modifier.height(6.dp))
                Text("Receptionist Language", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceLanguage.entries.forEach { lang ->
                        FilterChip(selected = callLang == lang, onClick = { callLang = lang }, label = { Text(lang.label) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = vipCsv,
                    onValueChange = { vipCsv = it },
                    label = { Text("VIP / Trusted Contacts (names or last digits, comma-separated)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("AI Receptionist Greeting Script") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VoIP / SIP Bridge Adapter", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.titleMedium)
                        Text("Use custom SIP/WebRTC server for two-way audio", color = ZehnColors.SlateMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    ClayToggleSwitch(checked = voipEnabled, onCheckedChange = { voipEnabled = it })
                }
                if (voipEnabled) {
                    OutlinedTextField(
                        value = voipUri,
                        onValueChange = { voipUri = it },
                        label = { Text("SIP / VoIP Endpoint URI (e.g. sip:user@pbx.example.com)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(10.dp))
                ClayActionButton(
                    text = "Save Call Assistant Config",
                    icon = Icons.Filled.Check,
                    modifier = Modifier.fillMaxWidth().testTag("save_call_config_button"),
                    onClick = {
                        vm.updateCallAssistantConfig(enabled, autoAnswer, delaySec, vipCsv, callLang, prompt, voipEnabled, voipUri)
                    }
                )
            }
        }

        if (recentCalls.isNotEmpty()) {
            item { Text("Recent Call Screening Log", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite) }
            items(recentCalls) { rec ->
                ClayGlassCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(Icons.Filled.PhoneInTalk, contentDescription = null, tint = if (rec.isVip) ZehnColors.Emerald else ZehnColors.Cyan)
                            Spacer(Modifier.padding(4.dp))
                            Text(rec.callerDisplay, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                        }
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(rec.timestampMs)), color = ZehnColors.SlateMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("${rec.outcome}${if (rec.isVip) " • VIP" else ""}", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                }
            }
        }
    }
}

@Composable
fun OpenSourceDiscoveryScreen(vm: ZehnViewModel) {
    val context = LocalContext.current
    val loading by vm.discoveryLoading.collectAsStateWithLifecycle()
    val repos by vm.githubResults.collectAsStateWithLifecycle()
    val models by vm.hfResults.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("gemma") }
    var tab by remember { mutableIntStateOf(0) } // 0 = GitHub, 1 = Hugging Face

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Open-Source AI Discovery", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
            Text("Live metadata & RAM/license inspection from GitHub & Hugging Face (read-only safe)", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        }

        item {
            ClayGlassCard(accentGlow = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("GitHub Android/AI") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Hugging Face Models") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(if (tab == 0) "Search GitHub repositories" else "Search Hugging Face models") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("discovery_search_input")
                )
                Spacer(Modifier.height(8.dp))
                ClayActionButton(
                    text = if (tab == 0) "Search GitHub" else "Search Hugging Face",
                    icon = Icons.Filled.Search,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (tab == 0) vm.searchOpenSourceTools(query)
                        else vm.searchHuggingFaceModels(query)
                    }
                )
                if (loading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ZehnColors.Cyan)
                }
            }
        }

        if (tab == 0) {
            items(repos, key = { it.fullName }) { repo ->
                ClayGlassCard {
                    Text(repo.fullName, style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                    Text("★ ${repo.stars} • ${repo.language} • License: ${repo.license} • Updated ${repo.updatedAt}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(repo.description, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.FrostWhite)
                    Spacer(Modifier.height(4.dp))
                    Text(repo.fitAssessment, style = MaterialTheme.typography.labelSmall, color = ZehnColors.Emerald)
                    Spacer(Modifier.height(6.dp))
                    ClayActionButton(text = "Inspect on GitHub", icon = Icons.Filled.OpenInBrowser, onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.htmlUrl))) }
                    })
                }
            }
        } else {
            items(models, key = { it.modelId }) { m ->
                ClayGlassCard {
                    Text(m.modelId, style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                    Text("${m.pipelineTag} • ${m.downloads} downloads • License: ${m.license}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(m.hardwareFit, style = MaterialTheme.typography.labelMedium, color = ZehnColors.Emerald)
                    Spacer(Modifier.height(6.dp))
                    ClayActionButton(text = "Open Model Card", icon = Icons.Filled.OpenInBrowser, onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.htmlUrl))) }
                    })
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    vm: ZehnViewModel,
    onOpenAccessibilityDebug: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val accSvc by vm.accessibilityConnected.collectAsStateWithLifecycle()
    val notifConnected by vm.notificationListenerConnected.collectAsStateWithLifecycle()
    val onDevice by vm.onDeviceState.collectAsStateWithLifecycle()
    val liveDiscovery by vm.liveModelsDiscovery.collectAsStateWithLifecycle()
    val a11yPermEnabled = remember(accSvc) { ZehnAccessibilityService.isAccessibilityPermissionEnabled(context) }

    fun has(p: String): Boolean = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val accLabel = when {
        accSvc != null -> "CONNECTED & ACTIVE"
        a11yPermEnabled -> "PERMISSION ENABLED, SERVICE DISCONNECTED (Reconnect)"
        else -> "DISABLED (Enable in Accessibility Settings)"
    }

    val rows = listOf(
        Triple("Microphone / Voice Control", if (has(Manifest.permission.RECORD_AUDIO)) "ACTIVE" else "PERMISSION REQUIRED", ZehnColors.Cyan),
        Triple("Gemini Live Audio API", if (vm.crypto.getGeminiKeys().isNotEmpty() && settings.geminiLiveVoiceEnabled) "CONFIGURED (${liveDiscovery.availableModels.size} models)" else "NEEDS GEMINI KEY", ZehnColors.Cyan),
        Triple("Accessibility GUI Agent", accLabel, if (accSvc != null) ZehnColors.Emerald else ZehnColors.Amber),
        Triple("Notification Listener", if (notifConnected) "ACTIVE" else "INACTIVE", ZehnColors.Amber),
        Triple("CameraX + ML Kit OCR/QR", if (has(Manifest.permission.CAMERA)) "READY" else "PERMISSION REQUIRED", ZehnColors.Cyan),
        Triple("LiteRT-LM On-Device Engine", if (vm.liteRtManager.isNativeRuntimeAvailable()) "NATIVE AAR READY" else "INTERFACE MODE (Cloud Fallback)", ZehnColors.SlateMuted),
        Triple("RAM & Concurrency Guard", if (vm.isLowRamSingleRequestMode()) "LOW-RAM SERIALIZED (${onDevice.totalRamMb} MB)" else "STANDARD (${onDevice.totalRamMb} MB)", ZehnColors.Emerald),
        Triple("Cellular Call Uplink Injection", "UNSUPPORTED BY ANDROID OS (No root)", ZehnColors.RoseStop)
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Honest System Diagnostics", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
            Text("Real device & permission capability audit — zero fabricated states", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        }

        items(rows) { (name, stateLabel, tint) ->
            ClayGlassCard {
                Text(name, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Spacer(Modifier.height(4.dp))
                Text(stateLabel, style = MaterialTheme.typography.labelMedium, color = tint)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ClayActionButton(text = "Test Active Provider", modifier = Modifier.weight(1f), onClick = {
                    vm.testProviderConnection(settings.activeProvider)
                })
                ClayActionButton(text = "Accessibility", modifier = Modifier.weight(1f), onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                })
            }
            Spacer(Modifier.height(8.dp))
            ClayActionButton(
                text = "Open Accessibility Debug Dashboard",
                modifier = Modifier.fillMaxWidth().testTag("diagnostics_open_a11y_debug_button"),
                onClick = onOpenAccessibilityDebug
            )
        }
    }
}
