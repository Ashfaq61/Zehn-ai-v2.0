package com.zehn.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zehn.assistant.R
import com.zehn.assistant.security.SafetyPolicyManager
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.theme.ZehnColors

@Composable
fun OnboardingScreen(
    safetyPolicy: SafetyPolicyManager,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val permsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val runtimePerms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZehnColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(28.dp))
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_zehn_onboarding_hero_1794764579194),
                contentDescription = "Zehn AI Neural Voice Assistant",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Text(
            text = "Meet Zehn AI",
            style = MaterialTheme.typography.displaySmall,
            color = ZehnColors.FrostWhite
        )
        Text(
            text = "Your multilingual voice AI & phone-automation agent. Zero analytics, hardware-encrypted API keys, and strict safety gates.",
            style = MaterialTheme.typography.bodyLarge,
            color = ZehnColors.SlateMuted
        )

        PermissionExplainCard(
            icon = Icons.Filled.Mic,
            title = "Microphone & Wake Word",
            explanation = "Required for Silero VAD voice input, barge-in interruption, and optional offline 'Hey Zehn' listening."
        )
        PermissionExplainCard(
            icon = Icons.Filled.CameraAlt,
            title = "Camera & ML Kit Vision",
            explanation = "Optional for live OCR, QR scanning, and photo Q&A. Gallery photos use the zero-permission Android Photo Picker."
        )
        PermissionExplainCard(
            icon = Icons.Filled.AccessibilityNew,
            title = "Accessibility Agent (Optional)",
            explanation = "Enables screen reading & UI taps for GUI automation. Gaming Check & Payment Confirmation remain strictly gated."
        )
        PermissionExplainCard(
            icon = Icons.Filled.NotificationsActive,
            title = "Notification Intel & Default Assistant",
            explanation = "Reads & replies to notifications on request, and lets Zehn launch on Home long-press."
        )

        if (safetyPolicy.isSamsungDevice()) {
            ClayGlassCard(accentGlow = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Security, contentDescription = null, tint = ZehnColors.Cyan)
                    Spacer(Modifier.width(10.dp))
                    Text("Samsung OneUI Setup Tip", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "On Samsung devices, disable 'Restricted Settings' in App Info (top-right menu) before enabling Accessibility, and set Battery to 'Unrestricted' so wake-word stays responsive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZehnColors.FrostWhite
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ClayActionButton(
                text = "Grant Core Permissions",
                icon = Icons.Filled.Mic,
                modifier = Modifier.weight(1f).testTag("onboarding_grant_perms"),
                onClick = { permsLauncher.launch(runtimePerms) }
            )
            ClayActionButton(
                text = "System Assist",
                icon = Icons.Filled.Layers,
                modifier = Modifier.weight(1f),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                }
            )
        }

        ClayActionButton(
            text = "Enter Zehn AI",
            icon = Icons.Filled.CheckCircle,
            modifier = Modifier.fillMaxWidth().testTag("onboarding_continue"),
            onClick = onFinish
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionExplainCard(
    icon: ImageVector,
    title: String,
    explanation: String
) {
    ClayGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = title, tint = ZehnColors.Cyan, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                Spacer(Modifier.height(4.dp))
                Text(explanation, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
            }
        }
    }
}
