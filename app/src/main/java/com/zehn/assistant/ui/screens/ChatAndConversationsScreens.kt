package com.zehn.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.zehn.assistant.ai.ProviderCatalog
import com.zehn.assistant.data.model.AiExecutionMode
import com.zehn.assistant.data.model.AiProvider
import com.zehn.assistant.data.model.OrbState
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.theme.ZehnColors

@Composable
fun ChatScreen(
    vm: ZehnViewModel,
    isBottomSheet: Boolean = false,
    onOpenConversations: () -> Unit,
    onOpenCamera: () -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val draft by vm.streamingDraft.collectAsStateWithLifecycle()
    val busy by vm.chatBusy.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val selectedProjectId by vm.selectedProjectId.collectAsStateWithLifecycle()
    val voiceState by vm.voiceUiState.collectAsStateWithLifecycle()
    val liveInputDraft by vm.liveInputDraft.collectAsStateWithLifecycle()
    val liveOutputDraft by vm.liveOutputDraft.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var attachedImage by remember { mutableStateOf<Uri?>(null) }
    var showModelMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, draft.length, liveInputDraft.length, liveOutputDraft.length) {
        val total = messages.size +
            (if (draft.isNotBlank()) 1 else 0) +
            (if (liveInputDraft.isNotBlank()) 1 else 0) +
            (if (liveOutputDraft.isNotBlank()) 1 else 0)
        if (total > 0) {
            listState.animateScrollToItem((total - 1).coerceAtLeast(0))
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachedImage = uri
    }

    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.voiceCoordinator.startVoiceTurn()
        } else {
            vm.showBanner("Microphone permission is required for voice input.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
            .padding(bottom = if (isBottomSheet) 16.dp else 108.dp)
    ) {
        // Top live model selector + mode bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(
                    onClick = { showModelMenu = true },
                    shape = RoundedCornerShape(14.dp),
                    color = ZehnColors.SurfaceElevated,
                    modifier = Modifier.testTag("chat_live_model_picker")
                ) {
                    Text(
                        text = "${settings.activeProvider.displayName}: ${settings.activeModel} ▾",
                        style = MaterialTheme.typography.labelMedium,
                        color = ZehnColors.Cyan,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                DropdownMenu(
                    expanded = showModelMenu,
                    onDismissRequest = { showModelMenu = false },
                    modifier = Modifier.background(ZehnColors.SurfaceClay)
                ) {
                    AiProvider.entries.forEach { provider ->
                        ProviderCatalog.modelsFor(provider).forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Text("${provider.displayName} • ${m.label}", color = ZehnColors.FrostWhite)
                                },
                                onClick = {
                                    vm.setProviderAndModel(provider, m.id)
                                    showModelMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Row {
                IconButton(onClick = { vm.createNewConversation() }) {
                    Icon(Icons.Filled.Add, contentDescription = "New Chat", tint = ZehnColors.FrostWhite)
                }
                IconButton(onClick = onOpenConversations) {
                    Icon(Icons.Filled.History, contentDescription = "Conversations", tint = ZehnColors.Cyan)
                }
            }
        }

        // Mode & RAG Project chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiExecutionMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.executionMode == mode,
                    onClick = { vm.setExecutionMode(mode) },
                    label = { Text(mode.label) }
                )
            }
            projects.forEach { proj ->
                FilterChip(
                    selected = selectedProjectId == proj.id,
                    onClick = { vm.selectProject(if (selectedProjectId == proj.id) null else proj.id) },
                    label = { Text("RAG: ${proj.name}") }
                )
            }
        }

        if (voiceState.liveModeActive) {
            Text(
                text = voiceState.statusBanner,
                style = MaterialTheme.typography.labelMedium,
                color = ZehnColors.Emerald,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(6.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                val isUser = msg.role == "user"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    ClayGlassCard(
                        accentGlow = !isUser,
                        modifier = Modifier.fillMaxWidth(0.88f)
                    ) {
                        if (!isUser && msg.modelUsed.isNotBlank()) {
                            Text(msg.modelUsed, style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                            Spacer(Modifier.height(4.dp))
                        }
                        if (!msg.imageUri.isNullOrBlank()) {
                            AsyncImage(
                                model = msg.imageUri,
                                contentDescription = "Attached or generated image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(msg.content, style = MaterialTheme.typography.bodyLarge, color = ZehnColors.FrostWhite)
                    }
                }
            }
            if (draft.isNotBlank()) {
                item {
                    ClayGlassCard(accentGlow = true, modifier = Modifier.fillMaxWidth(0.88f)) {
                        Text("Streaming...", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                        Spacer(Modifier.height(4.dp))
                        Text(draft, style = MaterialTheme.typography.bodyLarge, color = ZehnColors.FrostWhite)
                    }
                }
            }
            if (liveInputDraft.isNotBlank()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ClayGlassCard(modifier = Modifier.fillMaxWidth(0.88f).testTag("live_input_transcript_card")) {
                            Text("You (Live Voice)...", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                            Spacer(Modifier.height(4.dp))
                            Text(liveInputDraft, style = MaterialTheme.typography.bodyLarge, color = ZehnColors.FrostWhite)
                        }
                    }
                }
            }
            if (liveOutputDraft.isNotBlank()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        ClayGlassCard(accentGlow = true, modifier = Modifier.fillMaxWidth(0.88f).testTag("live_output_transcript_card")) {
                            Text("${voiceState.activeLiveModel.ifBlank { "Gemini Live" }} (Live Audio)...", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Emerald)
                            Spacer(Modifier.height(4.dp))
                            Text(liveOutputDraft, style = MaterialTheme.typography.bodyLarge, color = ZehnColors.FrostWhite)
                        }
                    }
                }
            }
        }

        if (attachedImage != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = attachedImage,
                    contentDescription = "Pending image",
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text("Image attached for Vision analysis", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan, modifier = Modifier.weight(1f))
                IconButton(onClick = { attachedImage = null }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove image", tint = ZehnColors.RoseStop)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenCamera) {
                Icon(Icons.Filled.CameraAlt, contentDescription = "Camera Vision & OCR", tint = ZehnColors.Cyan)
            }
            IconButton(onClick = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Icon(Icons.Filled.Image, contentDescription = "Pick Photo", tint = ZehnColors.Violet)
            }
            IconButton(onClick = {
                if (input.isNotBlank()) {
                    vm.generateImageInChat(input)
                    input = ""
                } else {
                    vm.showBanner("Type an image prompt first, then tap Imagine.")
                }
            }) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = "Generate Image", tint = ZehnColors.Cyan)
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask Zehn or run phone task...", color = ZehnColors.SlateMuted) },
                modifier = Modifier.weight(1f).testTag("chat_input_field"),
                maxLines = 3,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ZehnColors.Cyan,
                    unfocusedBorderColor = ZehnColors.HighlightLight,
                    focusedTextColor = ZehnColors.FrostWhite,
                    unfocusedTextColor = ZehnColors.FrostWhite
                )
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (input.isBlank() && attachedImage == null) {
                        if (voiceState.orbState != OrbState.IDLE) {
                            vm.voiceCoordinator.bargeIn()
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            vm.voiceCoordinator.startVoiceTurn()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        vm.sendChatMessage(input, attachedImage)
                        input = ""
                        attachedImage = null
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag("chat_send_button")
            ) {
                Icon(
                    imageVector = when {
                        input.isNotBlank() || attachedImage != null -> Icons.AutoMirrored.Filled.Send
                        voiceState.orbState != OrbState.IDLE -> Icons.Filled.Stop
                        else -> Icons.Filled.Mic
                    },
                    contentDescription = if (input.isBlank() && attachedImage == null) "Voice" else "Send",
                    tint = if (voiceState.orbState != OrbState.IDLE && input.isBlank() && attachedImage == null) ZehnColors.RoseStop else ZehnColors.Cyan
                )
            }
        }
    }
}

@Composable
fun ConversationsScreen(
    vm: ZehnViewModel,
    onSelectAndOpenChat: (Long) -> Unit
) {
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(bottom = 112.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Conversations", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
            ClayActionButton(text = "New Chat", icon = Icons.Filled.Add, onClick = {
                vm.createNewConversation()
                onSelectAndOpenChat(vm.activeConversationId.value)
            })
        }

        OutlinedTextField(
            value = query,
            onValueChange = { vm.setConversationSearch(it) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = ZehnColors.Cyan) },
            placeholder = { Text("Search messages & titles in Room...", color = ZehnColors.SlateMuted) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(conversations, key = { it.id }) { conv ->
                ClayGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        vm.selectConversation(conv.id)
                        onSelectAndOpenChat(conv.id)
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(conv.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = ZehnColors.FrostWhite)
                            Text("${conv.providerName} • ${conv.modelName}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                        }
                        IconButton(onClick = { vm.deleteConversation(conv.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete conversation", tint = ZehnColors.RoseStop)
                        }
                    }
                }
            }
        }
    }
}
