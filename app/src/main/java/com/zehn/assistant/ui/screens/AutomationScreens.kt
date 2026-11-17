package com.zehn.assistant.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zehn.assistant.data.db.SkillEntity
import com.zehn.assistant.data.db.TriggerEntity
import com.zehn.assistant.data.db.WorkflowEntity
import com.zehn.assistant.data.model.TriggerType
import com.zehn.assistant.ui.ZehnViewModel
import com.zehn.assistant.ui.components.ClayActionButton
import com.zehn.assistant.ui.components.ClayGlassCard
import com.zehn.assistant.ui.components.ClayToggleSwitch
import com.zehn.assistant.ui.theme.JetBrainsMonoFamily
import com.zehn.assistant.ui.theme.ZehnColors

@Composable
fun TriggersScreen(vm: ZehnViewModel) {
    val triggers by vm.triggers.collectAsStateWithLifecycle()

    var showCreator by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TriggerType.TIME) }
    var condition by remember { mutableStateOf("08:30") }
    var prompt by remember { mutableStateOf("Run morning briefing and summarize priority alerts") }

    val suggestions = listOf(
        Triple("08:30 Morning Briefing", TriggerType.TIME, "08:30"),
        Triple("Charger Connected Voice", TriggerType.CHARGING, "POWER_CONNECTED"),
        Triple("WhatsApp VIP Alert", TriggerType.NOTIFICATION, "com.whatsapp"),
        Triple("Spotify Driving Mix", TriggerType.APP_OPEN, "com.spotify.music")
    )

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
                Column {
                    Text("Triggers", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
                    Text("Time, Notification, App Open & Charging automations", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                }
                ClayActionButton(
                    text = if (showCreator) "Close" else "Create",
                    icon = Icons.Filled.Add,
                    onClick = { showCreator = !showCreator },
                    modifier = Modifier.testTag("triggers_add_button")
                )
            }
        }

        if (showCreator) {
            item {
                ClayGlassCard(accentGlow = true) {
                    Text("Smart Suggestions", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { (title, tType, cond) ->
                            AssistChip(
                                onClick = {
                                    name = title
                                    type = tType
                                    condition = cond
                                    prompt = "Auto-run: $title"
                                },
                                label = { Text(title) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TriggerType.entries.forEach { t ->
                            FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Trigger Name") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = condition, onValueChange = { condition = it }, label = { Text("Condition (e.g. 08:30 or package)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Action / Prompt") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    ClayActionButton(
                        text = "Save Trigger",
                        icon = Icons.Filled.Bolt,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (name.isNotBlank()) {
                                vm.addTrigger(
                                    TriggerEntity(
                                        name = name,
                                        triggerType = type.name,
                                        conditionValue = condition,
                                        actionPrompt = prompt,
                                        enabled = true
                                    )
                                )
                                showCreator = false
                                name = ""
                            }
                        }
                    )
                }
            }
        }

        items(triggers, key = { it.id }) { item ->
            ClayGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                        Text("${item.triggerType} • ${item.conditionValue}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                        Spacer(Modifier.height(4.dp))
                        Text(item.actionPrompt, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ClayToggleSwitch(
                            checked = item.enabled,
                            onCheckedChange = { vm.toggleTrigger(item.id, it) }
                        )
                        IconButton(onClick = { vm.deleteTrigger(item.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete trigger", tint = ZehnColors.RoseStop)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SkillsScreen(vm: ZehnViewModel) {
    val skills by vm.skills.collectAsStateWithLifecycle()
    var editingSkill by remember { mutableStateOf<SkillEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var markdown by remember { mutableStateOf("") }

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
                Column {
                    Text("SKILL.md Manager", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
                    Text("Editable agent tool playbooks invoked via function calling", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                }
                ClayActionButton(text = "New Skill", icon = Icons.Filled.Add, onClick = {
                    editingSkill = SkillEntity(slug = "custom_skill", name = "", description = "", markdownBody = "---\nname: custom_skill\n---\n1. Describe steps...")
                    name = ""
                    desc = ""
                    markdown = "---\nname: custom_skill\n---\n1. Describe steps..."
                })
            }
        }

        editingSkill?.let { current ->
            item {
                ClayGlassCard(accentGlow = true) {
                    Text("Edit SKILL.md", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Skill Name") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = markdown,
                        onValueChange = { markdown = it },
                        label = { Text("SKILL.md Markdown Body") },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClayActionButton(text = "Save Skill", modifier = Modifier.weight(1f), onClick = {
                            if (name.isNotBlank()) {
                                vm.saveSkill(
                                    current.copy(
                                        slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_"),
                                        name = name,
                                        description = desc,
                                        markdownBody = markdown,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                                editingSkill = null
                            }
                        })
                        ClayActionButton(text = "Cancel", isDanger = true, onClick = { editingSkill = null })
                    }
                }
            }
        }

        items(skills, key = { it.id }) { skill ->
            ClayGlassCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    editingSkill = skill
                    name = skill.name
                    desc = skill.description
                    markdown = skill.markdownBody
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(skill.name, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                        Text(skill.slug, style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                        Spacer(Modifier.height(4.dp))
                        Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ClayToggleSwitch(checked = skill.enabled, onCheckedChange = { vm.toggleSkill(skill, it) })
                        if (!skill.builtIn) {
                            IconButton(onClick = { vm.deleteSkill(skill.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete skill", tint = ZehnColors.RoseStop)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MissionModeScreen(vm: ZehnViewModel) {
    val planState by vm.agentPlanState.collectAsStateWithLifecycle()
    val workflows by vm.workflows.collectAsStateWithLifecycle()
    val recording by vm.recordingActive.collectAsStateWithLifecycle()

    var missionInput by remember { mutableStateOf("") }
    var flowName by remember { mutableStateOf("Recorded Routine") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Mission Mode & Flows", style = MaterialTheme.typography.headlineMedium, color = ZehnColors.FrostWhite)
            Text("Fast-Path Intent vs Standard GUI Agent Loop • Workflows (JSON) & Instant Flows (YAML)", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
        }

        item {
            ClayGlassCard(accentGlow = true) {
                Text("Run Autonomous Goal", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = missionInput,
                    onValueChange = { missionInput = it },
                    placeholder = { Text("e.g. Open Spotify, Scroll down, or Battery status", color = ZehnColors.SlateMuted) },
                    modifier = Modifier.fillMaxWidth().testTag("mission_goal_input"),
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClayActionButton(
                        text = "Execute Mission",
                        icon = Icons.Filled.PlayArrow,
                        modifier = Modifier.weight(1f).testTag("mission_execute_button"),
                        onClick = {
                            if (missionInput.isNotBlank()) {
                                vm.runAgentMission(missionInput)
                            }
                        }
                    )
                    ClayActionButton(
                        text = if (recording) "Save Flow" else "Record Task",
                        icon = if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        isDanger = recording,
                        onClick = {
                            if (recording) vm.stopRecordingAndSaveFlow(flowName)
                            else vm.agentLoop.startRecordingTask()
                        }
                    )
                }
                if (recording) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = flowName,
                        onValueChange = { flowName = it },
                        label = { Text("Recorded Flow Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            ClayGlassCard {
                Text("11-App Control & Bilingual Shortcuts", style = MaterialTheme.typography.titleMedium, color = ZehnColors.Cyan)
                Spacer(Modifier.height(6.dp))
                val quickGoals = listOf(
                    "WhatsApp kholo",
                    "Facebook open karo",
                    "Open TikTok",
                    "YouTube par lofi beats search karo",
                    "Google par weather news search karo",
                    "Google Maps open karo",
                    "Open Gallery",
                    "Open Phone",
                    "Open Messages",
                    "Open Settings",
                    "Front camera open karo",
                    "Take screenshot",
                    "Swipe up",
                    "Close app"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickGoals.forEach { preset ->
                        AssistChip(
                            onClick = {
                                missionInput = preset
                                vm.runAgentMission(preset)
                            },
                            label = { Text(preset) }
                        )
                    }
                }
            }
        }

        if (planState.goal.isNotBlank()) {
            item {
                ClayGlassCard {
                    Text("Live 6-Stage PlanManager • ${planState.path.label}", style = MaterialTheme.typography.labelMedium, color = ZehnColors.Cyan)
                    Spacer(Modifier.height(4.dp))
                    Text(planState.goal, style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
                    planState.steps.forEach { s ->
                        Text("${s.index}. [${s.status}] ${s.label}", style = MaterialTheme.typography.bodyMedium, color = when (s.status) {
                            "DONE" -> ZehnColors.Emerald
                            "BLOCKED", "FAILED" -> ZehnColors.RoseStop
                            else -> ZehnColors.FrostWhite
                        })
                    }
                    if (planState.summary.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Outcome: ${planState.summary}", style = MaterialTheme.typography.bodyMedium, color = ZehnColors.Emerald)
                    }
                }
            }
        }

        item {
            Text("Workflows (JSON) & Flows (YAML Instant Replay)", style = MaterialTheme.typography.titleMedium, color = ZehnColors.FrostWhite)
        }

        items(workflows, key = { it.id }) { wf ->
            ClayGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(wf.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = ZehnColors.FrostWhite)
                        Text("${wf.kind} • Runs: ${wf.runCount}", style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                        Text(wf.description, style = MaterialTheme.typography.bodyMedium, color = ZehnColors.SlateMuted)
                    }
                    Row {
                        ClayActionButton(text = "Replay", icon = Icons.Filled.PlayArrow, onClick = { vm.runWorkflow(wf) })
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { vm.deleteWorkflow(wf.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete workflow", tint = ZehnColors.RoseStop)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    wf.definitionText,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = ZehnColors.SlateMuted
                )
            }
        }
    }
}
