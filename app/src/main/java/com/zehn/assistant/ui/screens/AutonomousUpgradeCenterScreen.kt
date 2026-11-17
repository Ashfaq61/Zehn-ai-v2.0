package com.zehn.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zehn.assistant.BuildConfig
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.components.ClayToggleSwitch
import com.zehn.assistant.ui.theme.ZehnColors
import com.zehn.assistant.upgrade.AutoDevExecutionState
import com.zehn.assistant.upgrade.RepoReadinessReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AutonomousUpgradeCenterScreen(vm: ZehnViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keysVer by vm.keysVersion.collectAsStateWithLifecycle()
    val tools by vm.discoveredTools.collectAsStateWithLifecycle()
    val proposals by vm.upgradeProposals.collectAsStateWithLifecycle()
    val devRuns by vm.devRunHistory.collectAsStateWithLifecycle()
    val releases by vm.releaseHistory.collectAsStateWithLifecycle()
    val errorLogs by vm.upgradeErrorLogs.collectAsStateWithLifecycle()
    val research by vm.researchState.collectAsStateWithLifecycle()
    val ghConn by vm.githubConnection.collectAsStateWithLifecycle()
    val devState by vm.autoDevState.collectAsStateWithLifecycle()
    val realMetrics by vm.realGitHubMetrics.collectAsStateWithLifecycle()
    val repoReadiness by vm.repoReadiness.collectAsStateWithLifecycle()
    val dlProgress by vm.apkDownloadProgress.collectAsStateWithLifecycle()
    val installStatus by vm.installStatus.collectAsStateWithLifecycle()
    val storage by vm.storageTelemetry.collectAsStateWithLifecycle()
    val onDevice by vm.onDeviceState.collectAsStateWithLifecycle()
    val providerResults by vm.providerTestResults.collectAsStateWithLifecycle()

    val hasToken = remember(keysVer) { vm.crypto.hasSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN) }
    var repoDraft by remember(settings.githubRepoSlug) { mutableStateOf(settings.githubRepoSlug) }
    var branchDraft by remember(settings.githubBaseBranch) { mutableStateOf(settings.githubBaseBranch) }
    var tokenDraft by remember { mutableStateOf("") }
    var searchTopicDraft by remember { mutableStateOf("") }
    var activeTab by remember { mutableIntStateOf(0) } // 0=Proposals, 1=Discovery, 2=Updater, 3=Health, 4=Monitor & Logs

    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val fmt = remember { SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()) }
    fun formatTs(ts: Long): String = if (ts <= 0L) "Not yet" else fmt.format(Date(ts))

    val approvedCount = proposals.count { it.status == "APPROVED" || it.status == "STAGED" || it.status == "MERGED" }
    val latestReleaseVer = releases.firstOrNull()?.tagName ?: "v${BuildConfig.VERSION_NAME} (local)"
    val openPrDisplay = if (realMetrics.syncedWithGitHub) "${realMetrics.openAutoDevPrCount}/2" else "${proposals.count { it.status == "STAGED" }}/2"
    val mergedPrDisplay = if (realMetrics.syncedWithGitHub) realMetrics.mergedAutoDevPrCount else proposals.count { it.status == "MERGED" }
    val ciPassDisplay = if (realMetrics.syncedWithGitHub) realMetrics.passedCiRunsCount else devRuns.count { it.actionType == "BUILD_POLL" && it.success }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Autonomous Upgrade Center", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
            Text(
                "DISCOVER -> FILTER (<7 Suggested, >=8 Gated) -> CI PR (Max 2) -> VERIFY SHA/CERT -> INSTALL -> SAFE MODE",
                style = MaterialTheme.typography.labelSmall,
                color = ZehnColors.Cyan
            )
        }

        // Live Telemetry & Real GitHub Metrics Card
        item {
            ClayGlassCard(accentGlow = true) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "GitHub: ${if (ghConn.connected) "@${ghConn.username}" else "Not Verified"}",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (ghConn.connected) ZehnColors.Emerald else ZehnColors.Amber
                        )
                        Text("Research: ${research.currentStage}", style = MaterialTheme.typography.bodySmall, color = ZehnColors.SlateMuted)
                        Text(
                            "Cycle: ${devState.activeCycleStage} • Open PRs: $openPrDisplay • Latest: $latestReleaseVer",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZehnColors.Cyan
                        )
                    }
                    ClayActionButton(
                        text = "STOP",
                        icon = Icons.Filled.Stop,
                        isDanger = true,
                        modifier = Modifier.testTag("upgrade_emergency_stop_btn"),
                        onClick = { vm.stopResearchAndAutoDev() }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Last scan: ${formatTs(settings.lastResearchTimestamp)} • Next: ${if (settings.researchPaused || settings.allAutoUpgradesPaused) "PAUSED" else formatTs(settings.nextScheduledResearchTimestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.SlateMuted
                )
                Text(
                    "Discovered: ${tools.size} • Approved: $approvedCount • Merged PRs: $mergedPrDisplay • CI Checks Passed: $ciPassDisplay",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZehnColors.FrostWhite
                )
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (settings.allAutoUpgradesPaused) "ALL AUTO-UPGRADES PAUSED" else "Pause all auto-upgrades (Emergency)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.allAutoUpgradesPaused) ZehnColors.RoseStop else ZehnColors.FrostWhite
                    )
                    ClayToggleSwitch(
                        checked = settings.allAutoUpgradesPaused,
                        onCheckedChange = { vm.togglePauseAllAutoUpgrades() }
                    )
                }
                if (research.running || devState.running) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ZehnColors.Cyan)
                }
            }
        }

        // Build Status Dashboard Component Card
        item {
            BuildStatusDashboardCard(vm = vm, devState = devState)
        }

        // Repo Readiness & Bootstrap Card
        item {
            RepoReadinessCard(vm = vm, repoReadiness = repoReadiness)
        }

        // Primary Autonomous Controls
        item {
            ClayGlassCard {
                Text("Autonomous Controls", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton("Start Research", icon = Icons.Filled.PlayArrow, modifier = Modifier.weight(1f).testTag("btn_start_research"), onClick = {
                        vm.startResearch(includeGitHub = true, includeHuggingFace = true, customQuery = searchTopicDraft.takeIf { it.isNotBlank() })
                    })
                    ClayActionButton("Stop Research", icon = Icons.Filled.Stop, isDanger = true, modifier = Modifier.weight(1f).testTag("btn_stop_research"), onClick = {
                        vm.stopResearchAndAutoDev()
                    })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton("Scan GitHub", icon = Icons.Filled.Search, modifier = Modifier.weight(1f), onClick = {
                        vm.startResearch(includeGitHub = true, includeHuggingFace = false, customQuery = searchTopicDraft.takeIf { it.isNotBlank() })
                    })
                    ClayActionButton("Scan Hugging Face", icon = Icons.Filled.Search, modifier = Modifier.weight(1f), onClick = {
                        vm.startResearch(includeGitHub = false, includeHuggingFace = true, customQuery = searchTopicDraft.takeIf { it.isNotBlank() })
                    })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton("Build APK (CI)", icon = Icons.Filled.Build, modifier = Modifier.weight(1f), onClick = { vm.triggerBuildApk() })
                    ClayActionButton("Check Updates", icon = Icons.Filled.SystemUpdate, modifier = Modifier.weight(1f), onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        vm.checkForApkUpdates()
                    })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton("Open Rollback PR", icon = Icons.Filled.Restore, isDanger = true, modifier = Modifier.weight(1f), onClick = {
                        vm.restorePreviousVersionRollbackPr()
                    })
                    ClayActionButton("Sync Metrics", icon = Icons.Filled.Refresh, modifier = Modifier.weight(1f), onClick = {
                        vm.refreshRealMetricsAndTelemetry()
                    })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchTopicDraft,
                    onValueChange = { searchTopicDraft = it },
                    label = { Text("Optional focus keyword (blank = configured topics)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // GitHub Connection & Safety Policy
        item {
            ClayGlassCard {
                Text("GitHub Connection & Safety Policy", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Text(
                    if (hasToken) "Encrypted PAT saved in Android Keystore (masked, never logged)." else "No GitHub PAT configured yet.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasToken) ZehnColors.Emerald else ZehnColors.Amber
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = repoDraft, onValueChange = { repoDraft = it }, label = { Text("Repository (owner/repo)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("gh_repo_input"))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = branchDraft, onValueChange = { branchDraft = it }, label = { Text("Base Branch (default main)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    label = { Text(if (hasToken) "Replace GitHub PAT (•••• saved)" else "GitHub Fine-Grained PAT (Contents/PR/Actions/Issues)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("gh_token_input")
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton("Test", icon = Icons.Filled.Refresh, modifier = Modifier.weight(1f), onClick = {
                        vm.testGitHubConnection(repoDraft, branchDraft, tokenDraft)
                        vm.checkRepoReadiness()
                        tokenDraft = ""
                    })
                    ClayActionButton("Save", icon = Icons.Filled.Check, modifier = Modifier.weight(1f), onClick = {
                        vm.saveGitHubConfig(repoDraft, branchDraft, tokenDraft)
                        vm.checkRepoReadiness()
                        tokenDraft = ""
                    })
                    ClayActionButton("Delete", icon = Icons.Filled.Delete, isDanger = true, modifier = Modifier.weight(1f), onClick = {
                        vm.deleteGitHubToken()
                        tokenDraft = ""
                    })
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Master Auto-Dev (low-risk PR staging)", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.bodyMedium)
                    ClayToggleSwitch(checked = settings.autoDevEnabled, onCheckedChange = {
                        vm.updateAutoDevSafetyFlags(it, settings.autoMergeOnGreen, settings.allowWorkflowEdits, settings.notifyOnUpdateReady, settings.notifyOnBuildFailure)
                    })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-merge non-protected PR when green", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.bodyMedium)
                    ClayToggleSwitch(checked = settings.autoMergeOnGreen, onCheckedChange = {
                        vm.updateAutoDevSafetyFlags(settings.autoDevEnabled, it, settings.allowWorkflowEdits, settings.notifyOnUpdateReady, settings.notifyOnBuildFailure)
                    })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Notify on Verified APK Release", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.bodyMedium)
                    ClayToggleSwitch(checked = settings.notifyOnUpdateReady, onCheckedChange = {
                        vm.updateAutoDevSafetyFlags(settings.autoDevEnabled, settings.autoMergeOnGreen, settings.allowWorkflowEdits, it, settings.notifyOnBuildFailure)
                    })
                }
                Spacer(Modifier.height(6.dp))
                Text("Research Schedule & Filter", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("MANUAL", "DAILY", "WEEKLY").forEach { mode ->
                        FilterChip(selected = settings.researchScheduleMode == mode && !settings.researchPaused, onClick = {
                            vm.updateResearchSchedule(mode, settings.researchFocusMode, paused = false)
                        }, label = { Text(mode) })
                    }
                    FilterChip(selected = settings.researchFocusMode == "SECURITY_ONLY", onClick = {
                        val nextFocus = if (settings.researchFocusMode == "SECURITY_ONLY") "ALL_AI_TOOLS" else "SECURITY_ONLY"
                        vm.updateResearchSchedule(settings.researchScheduleMode, nextFocus, settings.researchPaused)
                    }, label = { Text("Security Only") })
                    FilterChip(selected = settings.researchPaused, onClick = {
                        vm.updateResearchSchedule(settings.researchScheduleMode, settings.researchFocusMode, !settings.researchPaused)
                    }, leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.Pause, null) }, label = { Text(if (settings.researchPaused) "Paused" else "Pause") })
                }
            }
        }

        // 5 Modular Tabs
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = activeTab == 0, onClick = { activeTab = 0 }, label = { Text("Proposals (${proposals.size})") })
                FilterChip(selected = activeTab == 1, onClick = { activeTab = 1 }, label = { Text("Discovery (${tools.size})") })
                FilterChip(selected = activeTab == 2, onClick = { activeTab = 2 }, label = { Text("Updater (${releases.size})") })
                FilterChip(selected = activeTab == 3, onClick = { activeTab = 3 }, label = { Text("Health") })
                FilterChip(selected = activeTab == 4, onClick = { activeTab = 4 }, label = { Text("Monitor & Logs (${errorLogs.size})") })
            }
        }

        when (activeTab) {
            0 -> {
                if (proposals.isEmpty()) {
                    item {
                        ClayGlassCard {
                            Text("No integration proposals yet. Items scoring >= 7.0 create gated proposals here.", color = ZehnColors.SlateMuted)
                        }
                    }
                } else {
                    items(proposals, key = { it.id }) { prop ->
                        ClayGlassCard {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(prop.title, style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan, modifier = Modifier.weight(1f))
                                Text(
                                    "${prop.riskLevel} • ${String.format(Locale.US, "%.1f", prop.fitScore)}/10 • ${prop.status}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (prop.riskLevel == "SENSITIVE") ZehnColors.Amber else ZehnColors.Emerald
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(prop.summary, style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
                            if (prop.protectedPathsFlagged.isNotBlank()) {
                                Text("PROTECTED PATHS: ${prop.protectedPathsFlagged}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.RoseStop)
                            }
                            if (prop.permissionsRequested.isNotBlank()) {
                                Text("Permissions: ${prop.permissionsRequested}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Amber)
                            }
                            if (prop.branchName.isNotBlank()) {
                                Text("Branch: ${prop.branchName} • CI: ${prop.lastBuildConclusion.ifBlank { "unbuilt" }} • Retries: ${prop.retryAttempts}/5", style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ClayActionButton("Approve & Stage", modifier = Modifier.weight(1f), onClick = { vm.approveProposalAndStage(prop.id, true) })
                                ClayActionButton("Reject", isDanger = true, modifier = Modifier.weight(1f), onClick = { vm.rejectProposal(prop.id) })
                            }
                        }
                    }
                }
            }
            1 -> discoveryTabItems(vm = vm, settings = settings, tools = tools)
            2 -> updaterTabItems(vm = vm, settings = settings, releases = releases, dlProgress = dlProgress, installStatus = installStatus, storage = storage)
            3 -> healthTabItems(vm = vm, settings = settings, onDevice = onDevice, providerResults = providerResults)
            else -> monitorAndLogsTabItems(vm = vm, settings = settings, storage = storage, devRuns = devRuns, errorLogs = errorLogs)
        }
    }
}

@Composable
fun BuildStatusDashboardCard(vm: ZehnViewModel, devState: AutoDevExecutionState) {
    val latestBuild = devState.lastBuild
    val conclusion = latestBuild.conclusion.lowercase(Locale.US)
    val statusColor = when (conclusion) {
        "success" -> ZehnColors.Emerald
        "failure" -> ZehnColors.RoseStop
        "in_progress", "queued" -> ZehnColors.Amber
        else -> ZehnColors.SlateMuted
    }
    val statusIcon = when (conclusion) {
        "success" -> Icons.Filled.Check
        "failure" -> Icons.Filled.Close
        else -> Icons.Filled.Build
    }

    ClayGlassCard(accentGlow = conclusion == "success") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                androidx.compose.material3.Icon(imageVector = statusIcon, contentDescription = null, tint = statusColor)
                Column {
                    Text("Build Status Dashboard", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                    Text(
                        "Status: ${latestBuild.status.ifBlank { "Idle" }} • Conclusion: ${latestBuild.conclusion.ifBlank { "Pending/None" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
            }
            ClayActionButton("Poll Status", icon = Icons.Filled.Refresh, onClick = { vm.pollBuildStatus() })
        }
        if (latestBuild.htmlUrl.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("Run URL: ${latestBuild.htmlUrl}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
        }
    }
}

@Composable
fun RepoReadinessCard(vm: ZehnViewModel, repoReadiness: RepoReadinessReport?) {
    var showBootstrapConfirm by remember { mutableStateOf(false) }

    ClayGlassCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Repository Readiness & Bootstrap", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Text(
                    if (repoReadiness == null) "Tap 'Check' to verify token, repository, workflows, keystore & actions."
                    else if (repoReadiness.allPassed) "All 7 checks PASSED! Repo is ready for automated builds & releases."
                    else "Some checks failed. Review fix hints below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (repoReadiness?.allPassed == true) ZehnColors.Emerald else ZehnColors.Amber
                )
            }
            ClayActionButton("Check", icon = Icons.Filled.Search, onClick = { vm.checkRepoReadiness() })
        }

        if (repoReadiness != null) {
            Spacer(Modifier.height(8.dp))
            repoReadiness.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        androidx.compose.material3.Icon(
                            imageVector = if (item.passed) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                            tint = if (item.passed) ZehnColors.Emerald else ZehnColors.RoseStop
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(item.title, style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
                            Text(item.detail, style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                            if (!item.passed && item.fixHint.isNotBlank()) {
                                Text("Fix Hint: ${item.fixHint}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Amber)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        ClayActionButton(
            text = "Bootstrap New Repo (Create Workflows & .gitignore)",
            icon = Icons.Filled.Build,
            onClick = { showBootstrapConfirm = true }
        )
    }

    if (showBootstrapConfirm) {
        AlertDialog(
            onDismissRequest = { showBootstrapConfirm = false },
            containerColor = ZehnColors.SurfaceClay,
            title = { Text("Bootstrap New GitHub Repository", color = ZehnColors.FrostWhite) },
            text = { Text("This will commit missing basic files (.github/workflows/build.yml, release.yml, .gitignore) to your repository's main branch via the GitHub API.", color = ZehnColors.FrostWhite) },
            confirmButton = {
                ClayActionButton(text = "Confirm Bootstrap", onClick = {
                    showBootstrapConfirm = false
                    vm.bootstrapNewRepo()
                })
            },
            dismissButton = {
                TextButton(onClick = { showBootstrapConfirm = false }) {
                    Text("Cancel", color = ZehnColors.RoseStop)
                }
            }
        )
    }
}
