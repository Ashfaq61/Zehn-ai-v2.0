package com.zehn.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.core.content.ContextCompat
import com.zehn.assistant.BuildConfig
import com.zehn.assistant.agent.ZehnAccessibilityService
import com.zehn.assistant.ai.OnDeviceModelState
import com.zehn.assistant.ai.ProviderConnectionTestResult
import com.zehn.assistant.data.DEFAULT_DISCOVERY_TOPICS
import com.zehn.assistant.data.StorageBatteryTelemetry
import com.zehn.assistant.data.ZehnSettings
import com.zehn.assistant.data.db.AutoDevRunEntity
import com.zehn.assistant.data.db.DiscoveredToolEntity
import com.zehn.assistant.data.db.ReleaseRecordEntity
import com.zehn.assistant.data.db.UpgradeErrorLogEntity
import com.zehn.assistant.data.model.AiProvider
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.components.ClayToggleSwitch
import com.zehn.assistant.ui.theme.ZehnColors
import com.zehn.assistant.upgrade.ApkDownloadProgress
import com.zehn.assistant.upgrade.ApkInstallStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun LazyListScope.discoveryTabItems(
    vm: ZehnViewModel,
    settings: ZehnSettings,
    tools: List<DiscoveredToolEntity>
) {
    item {
        DiscoveryControlsCard(vm = vm, settings = settings)
    }
    item {
        Text(
            text = "Discovered Reports (${tools.size}) — Fit < 7.0 stays Suggested; Rejected tools excluded",
            style = MaterialTheme.typography.labelSmall,
            color = ZehnColors.SlateMuted
        )
    }
    if (tools.isEmpty()) {
        item {
            ClayGlassCard {
                Text(
                    text = "No tools cached yet. Tap 'Start Research' to run Wi-Fi-aware discovery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZehnColors.SlateMuted
                )
            }
        }
    } else {
        items(tools, key = { it.id }) { t ->
            val tagColor = when (t.statusTag) {
                "Rejected" -> ZehnColors.RoseStop
                "Approved", "Integrated" -> ZehnColors.Emerald
                "Pending Approval" -> ZehnColors.Cyan
                else -> ZehnColors.Amber
            }
            ClayGlassCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${t.source}: ${t.identifier}",
                        style = MaterialTheme.typography.titleMedium,
                        color = ZehnColors.Cyan,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${t.statusTag} • ${String.format(Locale.US, "%.1f", t.fitScore)}/10",
                        style = MaterialTheme.typography.labelSmall,
                        color = tagColor
                    )
                }
                Text(
                    "Compat ${t.compatibilityScore}/100 • License: ${t.license} • ${if (t.source == "GITHUB") "★ ${t.starsOrDownloads}" else "${t.starsOrDownloads} dl"} • Maintained: ${if (t.activelyMaintained) "Active" else "Stale"} • Updated ${t.lastUpdated}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.SlateMuted
                )
                Spacer(Modifier.height(4.dp))
                Text(t.description, style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
                if (t.rejectionReason.isNotBlank()) {
                    Text("Filtered: ${t.rejectionReason}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.RoseStop)
                } else if (t.implementationPlan.isNotBlank()) {
                    Text(t.implementationPlan, style = MaterialTheme.typography.labelSmall, color = ZehnColors.Emerald)
                }
                Text(
                    "Deps: ${t.dependenciesSummary} • RAM ~${t.estimatedRamMb}MB • Security: ${t.securityFlags}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.SlateMuted
                )
            }
        }
    }
}

@Composable
private fun DiscoveryControlsCard(vm: ZehnViewModel, settings: ZehnSettings) {
    var topicsDraft by remember(settings.discoveryTopicsCsv) { mutableStateOf(settings.discoveryTopicsCsv) }
    ClayGlassCard {
        Text("Continuous Discovery & Pre-Filter Policy", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
        Text(
            "One query per line; prefix 'hf:' for Hugging Face. Rejects GPL/AGPL/non-commercial, missing model cards, stale (>18mo), or over-RAM items.",
            style = MaterialTheme.typography.labelSmall,
            color = ZehnColors.SlateMuted
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = topicsDraft,
            onValueChange = { topicsDraft = it },
            label = { Text("Editable Discovery Topics") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("discovery_topics_input")
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Wi-Fi-only background discovery", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.bodyMedium)
            ClayToggleSwitch(
                checked = settings.discoveryWifiOnly,
                onCheckedChange = {
                    vm.updateDiscoveryConfig(
                        topicsCsv = topicsDraft,
                        wifiOnly = it,
                        autoApprove = settings.autoApproveEnabled,
                        scheduleMode = settings.researchScheduleMode,
                        focusMode = settings.researchFocusMode
                    )
                }
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-approve safe items (Fit >= 8.0, default OFF)", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.bodyMedium)
            ClayToggleSwitch(
                checked = settings.autoApproveEnabled,
                onCheckedChange = {
                    vm.updateDiscoveryConfig(
                        topicsCsv = topicsDraft,
                        wifiOnly = settings.discoveryWifiOnly,
                        autoApprove = it,
                        scheduleMode = settings.researchScheduleMode,
                        focusMode = settings.researchFocusMode
                    )
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ClayActionButton(
                text = "Save Topics",
                icon = Icons.Filled.Check,
                modifier = Modifier.weight(1f).testTag("save_discovery_topics_btn"),
                onClick = {
                    vm.updateDiscoveryConfig(
                        topicsCsv = topicsDraft,
                        wifiOnly = settings.discoveryWifiOnly,
                        autoApprove = settings.autoApproveEnabled,
                        scheduleMode = settings.researchScheduleMode,
                        focusMode = settings.researchFocusMode
                    )
                }
            )
            ClayActionButton(
                text = "Reset Defaults",
                modifier = Modifier.weight(0.8f),
                onClick = { topicsDraft = DEFAULT_DISCOVERY_TOPICS }
            )
        }
    }
}

fun LazyListScope.updaterTabItems(
    vm: ZehnViewModel,
    settings: ZehnSettings,
    releases: List<ReleaseRecordEntity>,
    dlProgress: ApkDownloadProgress,
    installStatus: ApkInstallStatus,
    storage: StorageBatteryTelemetry
) {
    item {
        val context = LocalContext.current
        val canInstallUnknown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
        ClayGlassCard(accentGlow = true) {
            Text("In-App Verified Updater & Pre-Update Backup", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
            Text(
                "Installed: v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) • Downgrades blocked • Certificate + SHA-256 verified",
                style = MaterialTheme.typography.labelSmall,
                color = ZehnColors.FrostWhite
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("STABLE", "BETA").forEach { channel ->
                    FilterChip(
                        selected = settings.releaseChannel == channel,
                        onClick = {
                            vm.updateUpdaterConfig(
                                channel = channel,
                                updaterWifiOnly = settings.updaterWifiOnly,
                                autoMergeOnGreen = settings.autoMergeOnGreen,
                                allowWorkflowEdits = settings.allowWorkflowEdits,
                                notifyUpdateReady = settings.notifyOnUpdateReady,
                                notifyBuildFailure = settings.notifyOnBuildFailure
                            )
                        },
                        label = { Text("$channel Channel") }
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Download updates on Wi-Fi only", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.bodyMedium)
                ClayToggleSwitch(
                    checked = settings.updaterWifiOnly,
                    onCheckedChange = {
                        vm.updateUpdaterConfig(
                            channel = settings.releaseChannel,
                            updaterWifiOnly = it,
                            autoMergeOnGreen = settings.autoMergeOnGreen,
                            allowWorkflowEdits = settings.allowWorkflowEdits,
                            notifyUpdateReady = settings.notifyOnUpdateReady,
                            notifyBuildFailure = settings.notifyOnBuildFailure
                        )
                    }
                )
            }
            Text(
                text = if (canInstallUnknown) "Install Unknown Apps: ALLOWED" else "Install Unknown Apps: PERMISSION NEEDED",
                style = MaterialTheme.typography.labelSmall,
                color = if (canInstallUnknown) ZehnColors.Emerald else ZehnColors.Amber
            )
            if (dlProgress.active || dlProgress.statusMessage.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(dlProgress.statusMessage, style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                if (dlProgress.active) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { dlProgress.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = ZehnColors.Emerald
                    )
                }
            }
            if (installStatus.state != "IDLE") {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Installer [${installStatus.state}]: ${installStatus.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (installStatus.state == "FAILED") ZehnColors.RoseStop else ZehnColors.Emerald
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ClayActionButton("Check Updates", icon = Icons.Filled.SystemUpdate, modifier = Modifier.weight(1f), onClick = { vm.checkForApkUpdates() })
                ClayActionButton("Install Permission", modifier = Modifier.weight(1f), onClick = { vm.openUnknownSourcesSettings() })
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ClayActionButton("Backup Now (${storage.backupCount})", icon = Icons.Filled.Backup, modifier = Modifier.weight(1f), onClick = { vm.createPreUpdateBackup() })
                ClayActionButton("Restore Backup", icon = Icons.Filled.Restore, modifier = Modifier.weight(1f), onClick = { vm.restoreLatestPreUpdateBackup() })
            }
        }
    }
    if (releases.isEmpty()) {
        item {
            ClayGlassCard {
                Text("No GitHub Releases cached yet. Connect 'owner/repo' and tap 'Check Updates'.", color = ZehnColors.SlateMuted)
            }
        }
    } else {
        items(releases, key = { it.id }) { rel ->
            ClayGlassCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${rel.tagName} (${rel.releaseName})", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan, modifier = Modifier.weight(1f))
                    Text(
                        if (rel.isPrerelease) "BETA" else "STABLE",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rel.isPrerelease) ZehnColors.Amber else ZehnColors.Emerald
                    )
                }
                Text(
                    "Code ${rel.parsedVersionCode ?: "?"} • ${rel.compatibilityNote} • ${if (rel.verifiedSigningCert) "Cert Match" else "Unverified"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.Emerald
                )
                if (rel.sha256.isNotBlank()) {
                    Text("SHA-256: ${rel.sha256.take(24)}...", style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                }
                Spacer(Modifier.height(4.dp))
                Text(rel.changelog.take(360), style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton("Download & Install", icon = Icons.Filled.SystemUpdate, modifier = Modifier.weight(1f), onClick = { vm.downloadAndInstallRelease(rel) })
                    ClayActionButton("Rollback PR", icon = Icons.Filled.Restore, modifier = Modifier.weight(0.8f), onClick = { vm.restorePreviousVersionRollbackPr(rel.tagName) })
                }
            }
        }
    }
}

fun LazyListScope.healthTabItems(
    vm: ZehnViewModel,
    settings: ZehnSettings,
    onDevice: OnDeviceModelState,
    providerResults: Map<AiProvider, ProviderConnectionTestResult>
) {
    item {
        val context = LocalContext.current
        val fmt = remember { SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()) }
        val voiceTs = if (settings.lastSuccessfulVoiceTestAt > 0L) fmt.format(Date(settings.lastSuccessfulVoiceTestAt)) else "Not tested yet"
        val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val accOk = ZehnAccessibilityService.isConnected.value || ZehnAccessibilityService.isAccessibilityPermissionEnabled(context)
        val installOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true

        ClayGlassCard(accentGlow = true) {
            Text("System Health Dashboard", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
            Spacer(Modifier.height(6.dp))
            Text("Active Cloud Model: ${settings.activeProvider.displayName} (${settings.activeModel})", style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
            Text("Gemini Live Model: ${settings.geminiLiveCustomModel.ifBlank { settings.geminiLiveModel }} (fallback ${settings.geminiLiveFallbackModel})", style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
            Text("LiteRT-LM Model: ${settings.selectedLiteRtModelId} • Engine Ready: ${onDevice.engineReady}", style = MaterialTheme.typography.bodySmall, color = ZehnColors.SlateMuted)
            Spacer(Modifier.height(6.dp))
            Text("Permissions: Mic=$micOk • Accessibility=$accOk • UnknownApps=$installOk", style = MaterialTheme.typography.labelSmall, color = if (micOk && installOk) ZehnColors.Emerald else ZehnColors.Amber)
            Spacer(Modifier.height(6.dp))
            Text("Last Successful Voice Test: $voiceTs", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
            if (settings.lastSuccessfulVoiceTestDetail.isNotBlank()) {
                Text(settings.lastSuccessfulVoiceTestDetail, style = MaterialTheme.typography.bodySmall, color = ZehnColors.Emerald)
            }
            Spacer(Modifier.height(8.dp))
            ClayActionButton("Run Voice Health Check", icon = Icons.Filled.Refresh, onClick = { vm.runVoiceHealthTest() })
        }
    }
    items(listOf(AiProvider.GEMINI, AiProvider.GROQ, AiProvider.OPENROUTER, AiProvider.HUGGINGFACE, AiProvider.OPENAI_COMPATIBLE, AiProvider.OLLAMA)) { p ->
        val res = providerResults[p]
        ClayGlassCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(p.displayName, style = MaterialTheme.typography.titleSmall, color = ZehnColors.FrostWhite)
                    Text(
                        res?.message ?: "Tap Test to check live API reachability",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            res == null -> ZehnColors.SlateMuted
                            res.connected -> ZehnColors.Emerald
                            else -> ZehnColors.RoseStop
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                ClayActionButton("Test", onClick = { vm.testProviderConnection(p) })
            }
        }
    }
}

fun LazyListScope.monitorAndLogsTabItems(
    vm: ZehnViewModel,
    settings: ZehnSettings,
    storage: StorageBatteryTelemetry,
    devRuns: List<AutoDevRunEntity>,
    errorLogs: List<UpgradeErrorLogEntity>
) {
    item {
        ClayGlassCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Storage, Battery & Updater Data", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                ClayActionButton("Refresh", icon = Icons.Filled.Refresh, onClick = { vm.refreshRealMetricsAndTelemetry() })
            }
            Spacer(Modifier.height(6.dp))
            Text("APK Update Cache: ${fmtKbMb(storage.apkCacheBytes)}", style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
            Text("Room DB Footprint: ${fmtKbMb(storage.roomDatabaseBytes)}", style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
            Text("Pre-Update Backups (${storage.backupCount}): ${fmtKbMb(storage.backupsBytes)}", style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
            Text("Local LiteRT Models: ${fmtKbMb(storage.localModelsBytes)}", style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
            Text("Updater Bytes Downloaded: ${fmtKbMb(settings.updaterBytesDownloaded)}", style = MaterialTheme.typography.bodySmall, color = ZehnColors.Cyan)
            Text(
                "Battery Optimization Exempt: ${storage.ignoringBatteryOptimizations}",
                style = MaterialTheme.typography.labelSmall,
                color = if (storage.ignoringBatteryOptimizations) ZehnColors.Emerald else ZehnColors.Amber
            )
        }
    }
    item {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Sanitized Error Logs (${errorLogs.size})", style = MaterialTheme.typography.titleSmall, color = ZehnColors.Amber)
            if (errorLogs.isNotEmpty()) {
                ClayActionButton("Clear Errors", icon = Icons.Filled.Delete, isDanger = true, onClick = { vm.clearUpgradeErrorLogs() })
            }
        }
    }
    items(errorLogs.take(25), key = { it.id }) { err ->
        ClayGlassCard {
            Text("[${err.source}] ${err.message}", style = MaterialTheme.typography.titleSmall, color = ZehnColors.RoseStop)
            if (err.detail.isNotBlank()) {
                Text(err.detail.take(220), style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
            }
        }
    }
    item {
        Text("GitHub & Discovery Audit Trail (${devRuns.size})", style = MaterialTheme.typography.titleSmall, color = ZehnColors.Cyan)
    }
    items(devRuns.take(40), key = { it.id }) { run ->
        ClayGlassCard {
            Text(
                "${run.actionType} (${run.branchOrRef}) • HTTP ${run.httpStatus}",
                style = MaterialTheme.typography.titleSmall,
                color = if (run.success) ZehnColors.Emerald else ZehnColors.RoseStop
            )
            Text(run.summary, style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
            if (run.detail.isNotBlank()) {
                Text(run.detail.take(180), style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
            }
        }
    }
}

private fun fmtKbMb(bytes: Long): String {
    return when {
        bytes <= 0L -> "0 KB"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
