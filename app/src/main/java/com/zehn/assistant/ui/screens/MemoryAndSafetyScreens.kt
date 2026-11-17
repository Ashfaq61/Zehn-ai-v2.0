package com.zehn.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zehn.assistant.agent.ZehnAccessibilityService
import com.zehn.assistant.data.model.MemoryTier
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.components.ClayToggleSwitch
import com.zehn.assistant.ui.components.EmergencyStopPill
import com.zehn.assistant.ui.theme.ZehnColors

@Composable
fun MemoryScreen(vm: ZehnViewModel) {
    val memories by vm.memories.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var selectedTier by remember { mutableStateOf(MemoryTier.LONG_TERM) }
    var memTitle by remember { mutableStateOf("") }
    var memContent by remember { mutableStateOf("") }
    var personaDraft by remember(settings.personaPrompt) { mutableStateOf(settings.personaPrompt) }
    var newProjectName by remember { mutableStateOf("") }
    var activeUploadProjectId by remember { mutableStateOf<Long?>(null) }

    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val projId = activeUploadProjectId ?: projects.firstOrNull()?.id
        if (uri != null && projId != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
            vm.ingestRagDocument(projId, uri, name)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("4-Tier Memory & RAG Vault", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
            Text("Working, Session, Long-Term & Notification Intel + On-device Document Projects", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        }

        // Persona Editor
        item {
            ClayGlassCard(accentGlow = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Psychology, contentDescription = null, tint = ZehnColors.Cyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Persona / System Prompt Editor", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = personaDraft,
                    onValueChange = { personaDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Spacer(Modifier.height(8.dp))
                ClayActionButton(text = "Save Persona", onClick = { vm.updatePersona(personaDraft) })
            }
        }

        // RAG Projects
        item {
            ClayGlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = ZehnColors.Cyan)
                    Spacer(Modifier.width(8.dp))
                    Text("On-Device RAG Projects (PDF / TXT / OCR)", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text("New Project Name") },
                        modifier = Modifier.weight(1f)
                    )
                    ClayActionButton(text = "Add", icon = Icons.Filled.Add, onClick = {
                        if (newProjectName.isNotBlank()) {
                            vm.createRagProject(newProjectName, "SQLite RAG document collection")
                            newProjectName = ""
                        }
                    })
                }
                Spacer(Modifier.height(8.dp))
                projects.forEach { proj ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(proj.name, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                            Text("${proj.documentCount} documents indexed", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                        }
                        ClayActionButton(text = "Attach Doc", icon = Icons.Filled.UploadFile, onClick = {
                            activeUploadProjectId = proj.id
                            docPicker.launch(arrayOf("application/pdf", "text/*", "image/*"))
                        })
                    }
                }
            }
        }

        // 4-Tier Memory Tabs + Creator
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoryTier.entries.forEach { tier ->
                    FilterChip(selected = selectedTier == tier, onClick = { selectedTier = tier }, label = { Text(tier.label) })
                }
            }
            Spacer(Modifier.height(6.dp))
            ClayGlassCard {
                Text("${selectedTier.label} Tier: ${selectedTier.description}", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = memTitle, onValueChange = { memTitle = it }, label = { Text("Title / Key") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = memContent, onValueChange = { memContent = it }, label = { Text("Remembered Fact / Note") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClayActionButton(text = "Save Memory", modifier = Modifier.weight(1f), onClick = {
                        if (memTitle.isNotBlank() && memContent.isNotBlank()) {
                            vm.addMemory(selectedTier, memTitle, memContent)
                            memTitle = ""
                            memContent = ""
                        }
                    })
                    ClayActionButton(text = "Clear Tier", isDanger = true, onClick = { vm.clearMemoryTier(selectedTier) })
                }
            }
        }

        val tierItems = memories.filter { it.tier == selectedTier.name }
        items(tierItems, key = { it.id }) { mem ->
            ClayGlassCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${mem.title} (P${mem.priority})", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                        Text("${mem.sourceApp} • ${mem.content}", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                    }
                    ClayActionButton(text = "Forget", isDanger = true, onClick = { vm.forgetMemory(mem.id) })
                }
            }
        }
    }
}

@Composable
fun SecurityScreen(vm: ZehnViewModel) {
    val policy by vm.policy.collectAsStateWithLifecycle()
    val logs by vm.actionLogs.collectAsStateWithLifecycle()
    var newBlockPkg by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Security & Action Logs", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
                    Text("Enforced runtime safety gates & full audit log", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                }
                EmergencyStopPill(emergencyStopped = policy.emergencyStopped, onToggleStop = { vm.toggleEmergencyStop() })
            }
        }

        item {
            ClayGlassCard(accentGlow = true) {
                SafetySwitchRow(
                    title = "Automated Payment Confirmation",
                    subtitle = "Default OFF: never taps Pay / Place Order / Send Money without asking.",
                    checked = policy.automatedPaymentConfirmEnabled,
                    onChange = { val next = it; vm.safetyPolicy.updatePolicy { p -> p.copy(automatedPaymentConfirmEnabled = next) } }
                )
                SafetySwitchRow(
                    title = "Confirm Calls, Messages & Deletes",
                    subtitle = "Always show confirmation dialog before calling, SMS, WhatsApp, or deleting.",
                    checked = policy.confirmBeforeMessagesAndCalls,
                    onChange = { val next = it; vm.safetyPolicy.updatePolicy { p -> p.copy(confirmBeforeMessagesAndCalls = next, confirmBeforeDeletes = next) } }
                )
                SafetySwitchRow(
                    title = "Gaming Assistant Screen Check",
                    subtitle = "OFF = Accessibility/Vision never reads screens while gaming.",
                    checked = policy.gamingScreenCheckEnabled,
                    onChange = { val next = it; vm.safetyPolicy.updatePolicy { p -> p.copy(gamingScreenCheckEnabled = next) } }
                )
                SafetySwitchRow(
                    title = "Image Generation",
                    subtitle = "OFF = refuses all image generation requests.",
                    checked = policy.imageGenerationEnabled,
                    onChange = { val next = it; vm.safetyPolicy.updatePolicy { p -> p.copy(imageGenerationEnabled = next) } }
                )

                Spacer(Modifier.height(6.dp))
                Text("Agent Step Limit: ${policy.maxAgentSteps} steps", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Slider(
                    value = policy.maxAgentSteps.toFloat(),
                    onValueChange = { v -> vm.safetyPolicy.updatePolicy { it.copy(maxAgentSteps = v.toInt()) } },
                    valueRange = 2f..25f
                )
            }
        }

        // App Block-List
        item {
            ClayGlassCard {
                Text("App Block-List (${policy.blockedAppPackages.size})", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newBlockPkg,
                        onValueChange = { newBlockPkg = it },
                        label = { Text("Package ID (e.g. com.bank.app)") },
                        modifier = Modifier.weight(1f)
                    )
                    ClayActionButton(text = "Block", onClick = {
                        if (newBlockPkg.isNotBlank()) {
                            val pkg = newBlockPkg.trim()
                            vm.safetyPolicy.updatePolicy { it.copy(blockedAppPackages = it.blockedAppPackages + pkg) }
                            newBlockPkg = ""
                        }
                    })
                }
                policy.blockedAppPackages.forEach { pkg ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(pkg, style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                        IconButton(onClick = {
                            vm.safetyPolicy.updatePolicy { it.copy(blockedAppPackages = it.blockedAppPackages - pkg) }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Unblock $pkg", tint = ZehnColors.RoseStop)
                        }
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Full Action Log (${logs.size})", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                ClayActionButton(text = "Clear Log", isDanger = true, onClick = { vm.clearActionLogs() })
            }
        }

        items(logs, key = { it.id }) { log ->
            ClayGlassCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${log.category} • ${log.title}", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                    Text(
                        log.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (log.status) {
                            "BLOCKED", "FAILED" -> ZehnColors.RoseStop
                            "SUCCESS" -> ZehnColors.Emerald
                            else -> ZehnColors.Cyan
                        }
                    )
                }
                Text(log.detail, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
            }
        }
    }
}

@Composable
private fun SafetySwitchRow(
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
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        }
        ClayToggleSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun TouchGuardScreen(
    vm: ZehnViewModel,
    onOpenAccessibilityDebug: () -> Unit = {}
) {
    val context = LocalContext.current
    val policy by vm.policy.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val accSvc by vm.accessibilityConnected.collectAsStateWithLifecycle()
    val a11yPermEnabled = remember(accSvc) { ZehnAccessibilityService.isAccessibilityPermissionEnabled(context) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Touch Guard & Overlay", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
            Text("Accessibility GUI guard, Gaming protection & Floating Orb bubble", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        }

        item {
            ClayGlassCard(accentGlow = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AccessibilityNew, contentDescription = null, tint = ZehnColors.Cyan)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Accessibility Service", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                        Text(
                            text = when {
                                accSvc != null -> "CONNECTED & ACTIVE"
                                a11yPermEnabled -> "ENABLED IN SETTINGS (DISCONNECTED — RECONNECT)"
                                else -> "DISABLED"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (accSvc != null) ZehnColors.Emerald else ZehnColors.Amber
                        )
                    }
                    ClayActionButton(text = "Settings", onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    })
                }
                Spacer(Modifier.height(8.dp))
                ClayActionButton(
                    text = "Open Accessibility Debug Dashboard",
                    modifier = Modifier.fillMaxWidth().testTag("touch_guard_open_a11y_debug_button"),
                    onClick = onOpenAccessibilityDebug
                )
            }
        }

        item {
            ClayGlassCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = ZehnColors.Cyan)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Gaming Assistant Screen Check", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                            Text("When OFF, Zehn strictly skips reading game screens.", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                        }
                    }
                    ClayToggleSwitch(
                        checked = policy.gamingScreenCheckEnabled,
                        onCheckedChange = { v -> vm.safetyPolicy.updatePolicy { it.copy(gamingScreenCheckEnabled = v) } }
                    )
                }
            }
        }

        item {
            ClayGlassCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Layers, contentDescription = null, tint = ZehnColors.Violet)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Floating Orb Bubble Overlay", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                            Text("Draggable Zehn Orb + STOP pill over other apps", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                        }
                    }
                    ClayToggleSwitch(
                        checked = settings.floatingBubbleEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !Settings.canDrawOverlays(context)) {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                )
                            } else {
                                vm.toggleFloatingBubble(enabled)
                            }
                        }
                    )
                }
            }
        }

        if (vm.safetyPolicy.isSamsungDevice()) {
            item {
                ClayGlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = ZehnColors.Amber)
                        Spacer(Modifier.width(8.dp))
                        Text("Samsung OneUI Accessibility & Battery Helper", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Amber)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "1. Open App Info -> 3-dots menu -> 'Allow restricted settings'.\n2. Open Battery -> Select 'Unrestricted' so Samsung Sleeping Apps never kills Zehn.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZehnColors.FrostWhite
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClayActionButton(text = "App Info", modifier = Modifier.weight(1f), onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        })
                        ClayActionButton(text = "Battery Settings", modifier = Modifier.weight(1f), onClick = {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        })
                    }
                }
            }
        }
    }
}
