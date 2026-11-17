package com.zehn.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zehn.assistant.data.model.OrbState
import com.zehn.assistant.ui.theme.ZehnColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val LocalZehnHapticsEnabled = compositionLocalOf { true }

@Composable
fun rememberZehnHaptic(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    val enabled = LocalZehnHapticsEnabled.current
    return remember(haptics, enabled) {
        {
            if (enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }
}

/**
 * Soft 3D Claymorphism + Neumorphism Glass Card with bottom-right dark drop shadow
 * and subtle top-left rim highlight.
 */
@Composable
fun ClayGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    accentGlow: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "clay_press"
    )
    val triggerHaptic = rememberZehnHaptic()

    val baseModifier = modifier
        .scale(scale)
        .drawBehind {
            val rPx = cornerRadius.toPx()
            // Outer dark neumorphic shadow (bottom-right)
            drawRoundRect(
                color = ZehnColors.ShadowDark.copy(alpha = 0.72f),
                topLeft = Offset(5.dp.toPx(), 7.dp.toPx()),
                size = size,
                cornerRadius = CornerRadius(rPx, rPx)
            )
            // Top-left rim highlight
            drawRoundRect(
                color = if (accentGlow) ZehnColors.Cyan.copy(alpha = 0.25f) else ZehnColors.HighlightLight.copy(alpha = 0.55f),
                topLeft = Offset((-2).dp.toPx(), (-2).dp.toPx()),
                size = size,
                cornerRadius = CornerRadius(rPx, rPx)
            )
        }
        .clip(shape)
        .background(ZehnColors.GlassCardBrush)
        .border(
            width = 1.dp,
            brush = if (accentGlow) ZehnColors.CyanVioletBrush else Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))
            ),
            shape = shape
        )
        .let { m ->
            if (onClick != null) {
                m.clickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.material3.ripple(color = ZehnColors.Cyan)
                ) {
                    triggerHaptic()
                    onClick()
                }
            } else m
        }

    Column(
        modifier = baseModifier.padding(16.dp),
        content = content
    )
}

@Composable
fun ClayActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isDanger: Boolean = false,
    enabled: Boolean = true
) {
    val triggerHaptic = rememberZehnHaptic()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btn_scale"
    )

    val bgBrush = when {
        !enabled -> Brush.linearGradient(listOf(ZehnColors.SurfaceElevated, ZehnColors.SurfaceElevated))
        isDanger -> Brush.linearGradient(listOf(Color(0xFFFB7185), Color(0xFFE11D48)))
        else -> ZehnColors.CyanVioletBrush
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .scale(scale)
            .drawBehind {
                drawRoundRect(
                    color = ZehnColors.ShadowDark.copy(alpha = 0.65f),
                    topLeft = Offset(3.dp.toPx(), 5.dp.toPx()),
                    size = size,
                    cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx())
                )
            }
            .clip(RoundedCornerShape(18.dp))
            .background(bgBrush)
            .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(color = Color.White)
            ) {
                triggerHaptic()
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isDanger || !enabled) ZehnColors.FrostWhite else ZehnColors.Background,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isDanger || !enabled) ZehnColors.FrostWhite else ZehnColors.Background
            )
        }
    }
}

@Composable
fun ClayToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Toggle"
) {
    val triggerHaptic = rememberZehnHaptic()
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 2.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "clay_switch_thumb"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) ZehnColors.Cyan.copy(alpha = 0.28f) else ZehnColors.ShadowDark.copy(alpha = 0.7f),
        label = "clay_switch_track"
    )

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable {
                triggerHaptic()
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(30.dp)
                .clip(CircleShape)
                .background(trackColor)
                .border(
                    1.dp,
                    if (checked) ZehnColors.Cyan.copy(alpha = 0.7f) else ZehnColors.HighlightLight,
                    CircleShape
                )
                .padding(2.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (checked) ZehnColors.CyanVioletBrush
                        else Brush.linearGradient(listOf(ZehnColors.SlateMuted, Color(0xFF64748B)))
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            )
        }
    }
}

/**
 * Centerpiece animated glowing Orb with 4 real states:
 * IDLE (slow pulse), LISTENING (reacts to micLevel), THINKING (swirl), SPEAKING (waveform ripples).
 */
@Composable
fun ZehnAnimatedOrb(
    orbState: OrbState,
    micLevel: Float,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 200.dp,
    onClick: () -> Unit = {}
) {
    val triggerHaptic = rememberZehnHaptic()
    val infiniteTransition = rememberInfiniteTransition(label = "orb_transition")

    val slowPulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_pulse"
    )

    val swirlAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thinking_swirl"
    )

    val ripplePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "speaking_ripple"
    )

    val smoothMic by animateFloatAsState(
        targetValue = micLevel.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "mic_smooth"
    )

    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .clickable {
                triggerHaptic()
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.30f

            val dynamicRadius = when (orbState) {
                OrbState.IDLE -> baseRadius * slowPulse
                OrbState.LISTENING -> baseRadius * (1.0f + smoothMic * 0.42f)
                OrbState.THINKING -> baseRadius * (0.96f + 0.06f * sin(swirlAngle * PI / 180f).toFloat())
                OrbState.SPEAKING -> baseRadius * (1.03f + 0.12f * sin(ripplePhase * 2 * PI).toFloat())
            }

            // Ambient outer aura
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ZehnColors.Cyan.copy(alpha = if (orbState == OrbState.IDLE) 0.25f else 0.48f),
                        ZehnColors.Violet.copy(alpha = 0.18f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = dynamicRadius * 1.95f
                ),
                radius = dynamicRadius * 1.95f,
                center = center
            )

            // State-specific outer kinetic rings
            when (orbState) {
                OrbState.SPEAKING -> {
                    for (i in 0..2) {
                        val p = (ripplePhase + i * 0.33f) % 1f
                        val r = dynamicRadius * (1.1f + p * 0.75f)
                        drawCircle(
                            color = ZehnColors.Cyan.copy(alpha = (1f - p) * 0.55f),
                            radius = r,
                            center = center,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }
                OrbState.THINKING -> {
                    rotate(swirlAngle, pivot = center) {
                        drawArc(
                            brush = Brush.sweepGradient(listOf(ZehnColors.Cyan, ZehnColors.Violet, Color.Transparent)),
                            startAngle = 0f,
                            sweepAngle = 240f,
                            useCenter = false,
                            topLeft = Offset(center.x - dynamicRadius * 1.32f, center.y - dynamicRadius * 1.32f),
                            size = Size(dynamicRadius * 2.64f, dynamicRadius * 2.64f),
                            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    rotate(-swirlAngle * 1.4f, pivot = center) {
                        drawArc(
                            brush = Brush.sweepGradient(listOf(ZehnColors.Violet, ZehnColors.Cyan, Color.Transparent)),
                            startAngle = 90f,
                            sweepAngle = 190f,
                            useCenter = false,
                            topLeft = Offset(center.x - dynamicRadius * 1.50f, center.y - dynamicRadius * 1.50f),
                            size = Size(dynamicRadius * 3.0f, dynamicRadius * 3.0f),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                OrbState.LISTENING -> {
                    val bars = 24
                    for (i in 0 until bars) {
                        val angle = (i * (360f / bars)) * (PI / 180f)
                        val wave = (0.3f + 0.7f * smoothMic) * (0.6f + 0.4f * sin(angle * 3 + swirlAngle * 0.08))
                        val r1 = dynamicRadius * 1.15f
                        val r2 = r1 + (18.dp.toPx() * wave.toFloat())
                        drawLine(
                            color = if (i % 2 == 0) ZehnColors.Cyan else ZehnColors.Violet,
                            start = Offset(center.x + r1 * cos(angle).toFloat(), center.y + r1 * sin(angle).toFloat()),
                            end = Offset(center.x + r2 * cos(angle).toFloat(), center.y + r2 * sin(angle).toFloat()),
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
                OrbState.IDLE -> {
                    drawCircle(
                        color = ZehnColors.Cyan.copy(alpha = 0.25f),
                        radius = dynamicRadius * 1.24f,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }

            // 3D Claymorphic Core Sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFA5F3FC),
                        ZehnColors.Cyan,
                        ZehnColors.Violet,
                        Color(0xFF3B1D8E)
                    ),
                    center = Offset(center.x - dynamicRadius * 0.28f, center.y - dynamicRadius * 0.28f),
                    radius = dynamicRadius * 1.35f
                ),
                radius = dynamicRadius,
                center = center
            )

            // Specular glass highlight
            drawCircle(
                color = Color.White.copy(alpha = 0.35f),
                radius = dynamicRadius * 0.24f,
                center = Offset(center.x - dynamicRadius * 0.32f, center.y - dynamicRadius * 0.32f)
            )
        }
    }
}

/**
 * Custom edge-to-edge bottom navigation bar with a sculpted round center notch
 * cradling the glowing Mic / Orb trigger. Content draws behind it with zero black strip.
 */
@Composable
fun NotchedBottomNavBar(
    selectedTabIndex: Int,
    orbState: OrbState,
    onSelectTab: (Int) -> Unit,
    onMicClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val triggerHaptic = rememberZehnHaptic()
    val notchShape = remember {
        GenericShape { size, _ ->
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val notchRadius = 38f * (w / 400f).coerceIn(0.85f, 1.35f)
            val cornerR = 26f
            moveTo(0f, cornerR)
            quadraticTo(0f, 0f, cornerR, 0f)
            lineTo(cx - notchRadius * 1.45f, 0f)
            cubicTo(
                cx - notchRadius * 0.95f, 0f,
                cx - notchRadius * 0.9f, notchRadius * 0.95f,
                cx, notchRadius * 0.95f
            )
            cubicTo(
                cx + notchRadius * 0.9f, notchRadius * 0.95f,
                cx + notchRadius * 0.95f, 0f,
                cx + notchRadius * 1.45f, 0f
            )
            lineTo(w - cornerR, 0f)
            quadraticTo(w, 0f, w, cornerR)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Sculpted glassmorphic clay bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp)
                .drawBehind {
                    drawRoundRect(
                        color = ZehnColors.ShadowDark.copy(alpha = 0.78f),
                        topLeft = Offset(0f, 8.dp.toPx()),
                        size = size,
                        cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx())
                    )
                }
                .clip(notchShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            ZehnColors.SurfaceElevated.copy(alpha = 0.94f),
                            ZehnColors.SurfaceClay.copy(alpha = 0.97f)
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.14f), notchShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NavTabItem(
                label = "Home",
                selected = selectedTabIndex == 0,
                selectedIcon = Icons.Filled.Home,
                unselectedIcon = Icons.Outlined.Home,
                testTag = "nav_home",
                onClick = { triggerHaptic(); onSelectTab(0) },
                modifier = Modifier.weight(1f)
            )
            NavTabItem(
                label = "Chat",
                selected = selectedTabIndex == 1,
                selectedIcon = Icons.Filled.ChatBubble,
                unselectedIcon = Icons.Outlined.ChatBubbleOutline,
                testTag = "nav_chat",
                onClick = { triggerHaptic(); onSelectTab(1) },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(76.dp)) // Space under the sculpted notch
            NavTabItem(
                label = "Triggers",
                selected = selectedTabIndex == 2,
                selectedIcon = Icons.Filled.Bolt,
                unselectedIcon = Icons.Outlined.Bolt,
                testTag = "nav_triggers",
                onClick = { triggerHaptic(); onSelectTab(2) },
                modifier = Modifier.weight(1f)
            )
            NavTabItem(
                label = "Hub",
                selected = selectedTabIndex == 3,
                selectedIcon = Icons.Filled.DashboardCustomize,
                unselectedIcon = Icons.Outlined.DashboardCustomize,
                testTag = "nav_hub",
                onClick = { triggerHaptic(); onSelectTab(3) },
                modifier = Modifier.weight(1f)
            )
        }

        // Center Floating Mic/Orb Button cradled in the notch
        val isActive = orbState != OrbState.IDLE
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-18).dp)
                .size(62.dp)
                .drawBehind {
                    drawCircle(
                        color = if (isActive) ZehnColors.Cyan.copy(alpha = 0.45f) else ZehnColors.ShadowDark.copy(alpha = 0.8f),
                        radius = size.minDimension * 0.56f
                    )
                }
                .clip(CircleShape)
                .background(
                    if (isActive) Brush.linearGradient(listOf(ZehnColors.RoseStop, ZehnColors.Violet))
                    else ZehnColors.CyanVioletBrush
                )
                .border(2.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                .testTag("nav_mic_orb")
                .clickable {
                    triggerHaptic()
                    onMicClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isActive) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = "Voice Orb",
                tint = ZehnColors.Background,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun NavTabItem(
    label: String,
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(14.dp))
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = label,
            tint = if (selected) ZehnColors.Cyan else ZehnColors.SlateMuted,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) ZehnColors.Cyan else ZehnColors.SlateMuted,
            maxLines = 1
        )
    }
}

@Composable
fun EmergencyStopPill(
    emergencyStopped: Boolean,
    onToggleStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onToggleStop,
        shape = RoundedCornerShape(16.dp),
        color = if (emergencyStopped) ZehnColors.Amber else ZehnColors.RoseStop.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, if (emergencyStopped) ZehnColors.Amber else ZehnColors.RoseStop),
        modifier = modifier.minimumInteractiveComponentSize().testTag("emergency_stop_button")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = "Emergency Stop",
                tint = if (emergencyStopped) ZehnColors.Background else ZehnColors.RoseStop,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (emergencyStopped) "RESUME" else "STOP",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = if (emergencyStopped) ZehnColors.Background else ZehnColors.RoseStop
            )
        }
    }
}
