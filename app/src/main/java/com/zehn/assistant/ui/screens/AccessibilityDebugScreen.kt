package com.zehn.assistant.ui.screens

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.theme.ZehnColors

@Composable
fun AccessibilityDebugScreen(vm: ZehnViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val diagnostics by vm.accessibilityDiagnostics.collectAsStateWithLifecycle()
    val serviceConnected by vm.isAccessibilityServiceConnected.collectAsStateWithLifecycle()
    val projectionActive by vm.mediaProjectionActive.collectAsStateWithLifecycle()
    val screenshotStatus by vm.screenshotPermissionStatus.collectAsStateWithLifecycle()
    val lastObservation by vm.lastScreenObservation.collectAsStateWithLifecycle()
    val lastVisualAnalysis by vm.lastVisualAnalysis.collectAsStateWithLifecycle()
    val clickCount by vm.playgroundClickCount.collectAsStateWithLifecycle()
    val playgroundLog by vm.playgroundActionLog.collectAsStateWithLifecycle()

    var customClickTarget by remember { mutableStateOf("Test Tap Target") }
    val verticalTestListState = rememberLazyListState()
    val horizontalTestListState = rememberLazyListState()

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.onMediaProjectionConsentResult(result.resultCode, result.data)
    }

    LaunchedEffect(Unit) {
        vm.refreshAccessibilityDiagnostics()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshAccessibilityDiagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val playgroundItems = remember {
        (1..20).map { "Playground Scroll Row #$it — Real Accessibility Target" }
    }
    val swipeCards = remember {
        (1..6).map { "Card #$it" to "Horizontal Swipe Item $it" }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Accessibility Debug Dashboard",
                        style = MaterialTheme.typography.headlineMedium,
                        color = ZehnColors.FrostWhite
                    )
                    Text(
                        text = "Live Screen Vision, Click, Scroll, Swipe & OCR Diagnostics",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZehnColors.SlateMuted
                    )
                }
                IconButton(
                    onClick = { vm.refreshAccessibilityDiagnostics() },
                    modifier = Modifier.testTag("a11y_refresh_status_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh Diagnostics", tint = ZehnColors.Cyan)
                }
            }
        }

        // Required 11-Metric Status Panel
        item {
            ClayGlassCard(accentGlow = serviceConnected) {
                Text("Live Accessibility & Vision Telemetry", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Spacer(Modifier.height(8.dp))

                DiagnosticMetricRow(
                    label = "Accessibility permission",
                    value = if (diagnostics.permissionEnabled) "ENABLED" else "DISABLED",
                    valueColor = if (diagnostics.permissionEnabled) ZehnColors.Emerald else ZehnColors.RoseStop
                )
                DiagnosticMetricRow(
                    label = "Accessibility service",
                    value = if (diagnostics.serviceConnected && serviceConnected) "CONNECTED" else "DISCONNECTED",
                    valueColor = if (diagnostics.serviceConnected && serviceConnected) ZehnColors.Emerald else ZehnColors.Amber
                )
                DiagnosticMetricRow(
                    label = "Current foreground app",
                    value = "${diagnostics.foregroundAppLabel.ifBlank { "Unknown" }} (${diagnostics.foregroundPackage.ifBlank { "none" }})"
                )
                DiagnosticMetricRow(
                    label = "Screen node count",
                    value = "${diagnostics.screenNodeCount} (Windows: ${diagnostics.windowCount})"
                )
                DiagnosticMetricRow(
                    label = "Clickable node count",
                    value = "${diagnostics.clickableNodeCount}"
                )
                DiagnosticMetricRow(
                    label = "Scrollable node count",
                    value = "${diagnostics.scrollableNodeCount}"
                )
                DiagnosticMetricRow(
                    label = "Last action",
                    value = diagnostics.lastAction
                )
                DiagnosticMetricRow(
                    label = "Last action result",
                    value = diagnostics.lastActionResult
                )
                DiagnosticMetricRow(
                    label = "Gesture capability",
                    value = if (diagnostics.gestureCapable) "SUPPORTED (canPerformGestures)" else "UNAVAILABLE (Connect service on API 24+)",
                    valueColor = if (diagnostics.gestureCapable) ZehnColors.Emerald else ZehnColors.Amber
                )
                DiagnosticMetricRow(
                    label = "Screenshot permission status",
                    value = screenshotStatus,
                    valueColor = if (projectionActive) ZehnColors.Emerald else ZehnColors.Cyan
                )
                DiagnosticMetricRow(
                    label = "Last error",
                    value = diagnostics.lastError,
                    valueColor = if (diagnostics.lastError == "None") ZehnColors.Emerald else ZehnColors.RoseStop
                )
            }
        }

        // All 7 Required Real Test Actions
        item {
            ClayGlassCard {
                Text("Real Accessibility Action Tests", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = customClickTarget,
                    onValueChange = { customClickTarget = it },
                    label = { Text("Click Test Target Label") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("a11y_click_target_input")
                )
                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton(
                        text = "Test Screen Reading",
                        icon = Icons.Filled.Visibility,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("a11y_test_read_button"),
                        onClick = { vm.runTestScreenReading() }
                    )
                    ClayActionButton(
                        text = "Test Click",
                        icon = Icons.Filled.TouchApp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("a11y_test_click_button"),
                        onClick = { vm.runTestClick(customClickTarget.ifBlank { "Test Tap Target" }) }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton(
                        text = "Test Scroll Down",
                        icon = Icons.Filled.ArrowDownward,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("a11y_test_scroll_down_button"),
                        onClick = { vm.runTestScrollDown() }
                    )
                    ClayActionButton(
                        text = "Test Scroll Up",
                        icon = Icons.Filled.ArrowUpward,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("a11y_test_scroll_up_button"),
                        onClick = { vm.runTestScrollUp() }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton(
                        text = "Test Swipe Left",
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("a11y_test_swipe_left_button"),
                        onClick = { vm.runTestSwipeLeft() }
                    )
                    ClayActionButton(
                        text = "Test Swipe Right",
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("a11y_test_swipe_right_button"),
                        onClick = { vm.runTestSwipeRight() }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ClayActionButton(
                        text = "Reconnect Service",
                        icon = Icons.Filled.AccessibilityNew,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("a11y_reconnect_button"),
                        onClick = {
                            if (!vm.reconnectAccessibilityService()) {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            }
                        }
                    )
                    ClayActionButton(
                        text = "Android Settings",
                        modifier = Modifier
                            .weight(1f)
                            .testTag("a11y_open_settings_button"),
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        }
                    )
                }
            }
        }

        // Screenshot & Vision OCR Controls (MediaProjection + Accessibility takeScreenshot fallback)
        item {
            ClayGlassCard {
                Text("Screenshot & ML Kit OCR Engine", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Text(
                    text = "Method A: Accessibility node tree | Method B: MediaProjection (user consent) or Android 11+ Accessibility takeScreenshot + ML Kit OCR",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZehnColors.SlateMuted
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!projectionActive) {
                        ClayActionButton(
                            text = "Grant MediaProjection",
                            icon = Icons.Filled.CameraAlt,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("a11y_grant_projection_button"),
                            onClick = {
                                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                                if (mgr != null) {
                                    mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
                                } else {
                                    vm.showBanner("MediaProjectionManager unavailable on this device")
                                }
                            }
                        )
                    } else {
                        ClayActionButton(
                            text = "Stop Projection",
                            isDanger = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("a11y_stop_projection_button"),
                            onClick = { vm.stopMediaProjection() }
                        )
                    }
                    ClayActionButton(
                        text = "Capture & OCR Screen",
                        modifier = Modifier
                            .weight(1f)
                            .testTag("a11y_capture_ocr_button"),
                        onClick = { vm.runCaptureAndOcrScreen() }
                    )
                }
            }
        }

        // Built-in Interactive Playground Targets (Clickable button + 20-item Scroll List + Horizontal Swipe Strip)
        item {
            ClayGlassCard(accentGlow = true) {
                Text("Interactive Automation Test Playground", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Text(
                    text = playgroundLog,
                    style = MaterialTheme.typography.labelMedium,
                    color = ZehnColors.Emerald
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Click Count: $clickCount",
                        style = MaterialTheme.typography.titleMedium,
                        color = ZehnColors.FrostWhite
                    )
                    ClayActionButton(
                        text = "Test Tap Target",
                        icon = Icons.Filled.TouchApp,
                        modifier = Modifier.testTag("a11y_playground_tap_target"),
                        onClick = { vm.incrementPlaygroundClick() }
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Vertical Scroll List (First visible: #${verticalTestListState.firstVisibleItemIndex + 1})",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZehnColors.SlateMuted
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    state = verticalTestListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(168.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ZehnColors.Background.copy(alpha = 0.65f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                        .testTag("a11y_playground_scroll_list"),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(playgroundItems) { rowLabel ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ZehnColors.SurfaceClay)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(rowLabel, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.FrostWhite)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Horizontal Swipe Carousel (Active index: #${horizontalTestListState.firstVisibleItemIndex + 1})",
                    style = MaterialTheme.typography.labelMedium,
                    color = ZehnColors.SlateMuted
                )
                Spacer(Modifier.height(4.dp))
                LazyRow(
                    state = horizontalTestListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("a11y_playground_swipe_strip"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(swipeCards) { (title, subtitle) ->
                        Box(
                            modifier = Modifier
                                .width(190.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ZehnColors.SurfaceClay)
                                .border(1.dp, ZehnColors.Cyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Column {
                                Text(title, style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                                Spacer(Modifier.height(4.dp))
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ZehnColors.FrostWhite)
                            }
                        }
                    }
                }
            }
        }

        // Structured Accessibility Screen Reading Inspector Output
        lastObservation?.let { obs ->
            item {
                ClayGlassCard {
                    Text(
                        text = "Last Structured Screen Observation (${obs.packageName.ifBlank { "unknown" }})",
                        style = MaterialTheme.typography.titleMedium,
                        color = ZehnColors.Cyan
                    )
                    Spacer(Modifier.height(4.dp))
                    if (!obs.error.isNullOrBlank()) {
                        Text(obs.error, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.Amber)
                    } else {
                        Text(
                            text = "Total visited: ${obs.totalNodeCount} • Clickable: ${obs.clickableElements.size} • Inputs: ${obs.inputFields.size} • Scrollable: ${obs.scrollableContainers.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ZehnColors.Emerald
                        )
                        if (obs.clickableElements.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Clickable Elements:", style = MaterialTheme.typography.labelSmall, color = ZehnColors.SlateMuted)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                obs.clickableElements.take(12).forEach { node ->
                                    AssistChip(
                                        onClick = { customClickTarget = node.primaryLabel() },
                                        label = { Text(node.primaryLabel().take(24)) }
                                    )
                                }
                            }
                        }
                        if (obs.screenText.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Extracted Text (${obs.screenText.size}):\n" +
                                    obs.screenText.take(14).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = ZehnColors.FrostWhite
                            )
                        }
                    }
                }
            }
        }

        // Visual Capture + OCR Inspector Output
        lastVisualAnalysis?.let { vis ->
            item {
                ClayGlassCard {
                    Text("Last Visual Screenshot & OCR (${vis.method})", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                    Spacer(Modifier.height(4.dp))
                    if (!vis.error.isNullOrBlank()) {
                        Text(vis.error, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.Amber)
                    } else {
                        Text("OCR Lines Extracted: ${vis.ocrElements.size}", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Emerald)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = vis.ocrText.ifBlank { "No readable text detected in screenshot frame." }.take(1000),
                            style = MaterialTheme.typography.bodySmall,
                            color = ZehnColors.FrostWhite
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticMetricRow(
    label: String,
    value: String,
    valueColor: Color = ZehnColors.FrostWhite
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ZehnColors.SlateMuted,
            modifier = Modifier.weight(0.44f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = valueColor,
            modifier = Modifier.weight(0.56f)
        )
    }
}
