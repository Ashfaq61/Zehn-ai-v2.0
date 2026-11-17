package com.zehn.assistant.ai

import android.net.Uri
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.data.db.MemoryDao
import com.zehn.assistant.data.db.SkillDao
import com.zehn.assistant.data.model.AiExecutionMode
import com.zehn.assistant.data.model.AiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

data class RoutedStreamResult(
    val delta: String = "",
    val toolCall: LlmToolCall? = null,
    val citations: List<RagCitation> = emptyList(),
    val modelUsed: String = "",
    val error: String? = null,
    val completed: Boolean = false
)

@Singleton
class AiEngineRouter @Inject constructor(
    private val prefs: PreferencesRepository,
    private val cloudClient: CloudLlmClient,
    private val liteRtManager: OnDeviceLiteRtManager,
    private val ragEngine: RagEngine,
    private val memoryDao: MemoryDao,
    private val skillDao: SkillDao,
    private val actionLogDao: ActionLogDao
) {
    private val lowRamMutex = Mutex()

    val providerUsage: StateFlow<Map<AiProvider, ApiProviderUsage>> = cloudClient.providerUsage

    suspend fun testProviderConnection(provider: AiProvider): ProviderConnectionTestResult =
        cloudClient.testProviderConnection(provider)

    fun isLowRamSingleRequestMode(): Boolean {
        val ramMb = liteRtManager.state.value.totalRamMb
        return ramMb in 1L until LOW_RAM_THRESHOLD_MB
    }

    fun routeAndStream(
        history: List<LlmMessage>,
        userInput: String,
        projectId: Long? = null,
        imageUri: Uri? = null,
        tools: List<LlmToolDefinition> = emptyList()
    ): Flow<RoutedStreamResult> = flow {
        val lowRam = isLowRamSingleRequestMode()
        if (lowRam) {
            lowRamMutex.withLock {
                executeRoutedStream(history, userInput, projectId, imageUri, tools).collect { emit(it) }
            }
        } else {
            executeRoutedStream(history, userInput, projectId, imageUri, tools).collect { emit(it) }
        }
    }

    private fun executeRoutedStream(
        history: List<LlmMessage>,
        userInput: String,
        projectId: Long?,
        imageUri: Uri?,
        tools: List<LlmToolDefinition>
    ): Flow<RoutedStreamResult> = flow {
        val settings = prefs.settings.first()
        val citations = ragEngine.queryCitations(projectId, userInput)
        val systemPrompt = buildRichSystemPrompt(settings.personaPrompt, citations)

        val useOffline = when (settings.executionMode) {
            AiExecutionMode.OFFLINE -> true
            AiExecutionMode.CLOUD -> false
            AiExecutionMode.HYBRID -> {
                val simpleQuery = userInput.length < 140 && imageUri == null && tools.isEmpty()
                simpleQuery && liteRtManager.state.value.engineReady
            }
        }

        if (settings.webhookModeEnabled) {
            var completed = false
            cloudClient.streamChat(AiProvider.WEBHOOK, "webhook-default", systemPrompt, history, tools, imageUri)
                .collect { ev ->
                    when (ev) {
                        is LlmStreamEvent.TextDelta -> emit(RoutedStreamResult(delta = ev.delta, citations = citations, modelUsed = "Webhook"))
                        is LlmStreamEvent.Completed -> {
                            completed = true
                            emit(RoutedStreamResult(citations = citations, modelUsed = "Webhook", completed = true))
                        }
                        is LlmStreamEvent.ErrorEvent -> emit(RoutedStreamResult(error = ev.message, modelUsed = "Webhook"))
                        is LlmStreamEvent.ToolCallEvent -> emit(RoutedStreamResult(toolCall = ev.call, modelUsed = "Webhook"))
                    }
                }
            if (completed) return@flow
        }

        if (useOffline || settings.activeProvider == AiProvider.ON_DEVICE_LITERT) {
            val spec = ProviderCatalog.liteRtModels.firstOrNull { it.id == settings.selectedLiteRtModelId }
                ?: ProviderCatalog.liteRtModels.first()
            if (!liteRtManager.state.value.engineReady && liteRtManager.modelFileFor(spec).exists()) {
                liteRtManager.loadModel(spec, settings.liteRtBackend, systemPrompt)
            }
            if (liteRtManager.state.value.engineReady) {
                val promptWithContext = if (citations.isNotEmpty()) {
                    "Context:\n" + citations.joinToString("\n") { "[${it.documentName} p.${it.pageOrSection}] ${it.snippet}" } +
                        "\n\nUser: $userInput"
                } else userInput
                liteRtManager.streamPrompt(promptWithContext).collect { ev ->
                    when (ev) {
                        is LlmStreamEvent.TextDelta -> emit(RoutedStreamResult(delta = ev.delta, citations = citations, modelUsed = spec.name))
                        is LlmStreamEvent.Completed -> emit(RoutedStreamResult(citations = citations, modelUsed = spec.name, completed = true))
                        is LlmStreamEvent.ErrorEvent -> emit(RoutedStreamResult(error = ev.message, modelUsed = spec.name))
                        is LlmStreamEvent.ToolCallEvent -> emit(RoutedStreamResult(toolCall = ev.call, modelUsed = spec.name))
                    }
                }
                return@flow
            } else if (settings.executionMode == AiExecutionMode.OFFLINE) {
                emit(
                    RoutedStreamResult(
                        error = "Offline mode active, but ${spec.name} is not downloaded yet. Open Voice & AI Models to download it.",
                        modelUsed = spec.name
                    )
                )
                return@flow
            }
        }

        val providersToTry = buildList {
            add(settings.activeProvider)
            settings.fallbackChain.forEach { if (it != settings.activeProvider) add(it) }
            if (AiProvider.HUGGINGFACE !in this) add(AiProvider.HUGGINGFACE)
        }

        var delivered = false
        var lastError = "No AI provider succeeded. Add an API key in API Keys or download Gemma 4 for Offline mode."

        for (provider in providersToTry) {
            if (provider == AiProvider.ON_DEVICE_LITERT) {
                if (liteRtManager.state.value.engineReady) {
                    liteRtManager.streamPrompt(userInput).collect { ev ->
                        if (ev is LlmStreamEvent.TextDelta) {
                            delivered = true
                            emit(RoutedStreamResult(delta = ev.delta, citations = citations, modelUsed = "LiteRT Fallback"))
                        } else if (ev is LlmStreamEvent.Completed) {
                            emit(RoutedStreamResult(citations = citations, modelUsed = "LiteRT Fallback", completed = true))
                        }
                    }
                    if (delivered) break
                }
                continue
            }

            val model = if (provider == settings.activeProvider) {
                settings.activeModel
            } else {
                ProviderCatalog.modelsFor(provider).firstOrNull()?.id ?: continue
            }

            var failedThisProvider = false
            cloudClient.streamChat(provider, model, systemPrompt, history, tools, imageUri).collect { ev ->
                when (ev) {
                    is LlmStreamEvent.TextDelta -> {
                        delivered = true
                        emit(RoutedStreamResult(delta = ev.delta, citations = citations, modelUsed = "$model (${provider.displayName})"))
                    }
                    is LlmStreamEvent.ToolCallEvent -> {
                        val validJson = validateToolArgsJson(ev.call.argumentsJson)
                        if (validJson != null) {
                            delivered = true
                            emit(
                                RoutedStreamResult(
                                    toolCall = ev.call.copy(argumentsJson = validJson),
                                    citations = citations,
                                    modelUsed = "$model (${provider.displayName})"
                                )
                            )
                        } else {
                            actionLogDao.insert(
                                ActionLogEntity("MODEL", "Malformed Tool JSON Repair", "Retrying ${ev.call.name} with schema repair prompt", "INFO")
                            )
                            val repairHistory = history + LlmMessage(
                                "user",
                                "Your previous tool call '${ev.call.name}' had malformed JSON arguments: ${ev.call.argumentsJson}. Return ONLY valid JSON matching the schema."
                            )
                            cloudClient.streamChat(provider, model, systemPrompt, repairHistory, tools, null).collect { retryEv ->
                                if (retryEv is LlmStreamEvent.ToolCallEvent) {
                                    delivered = true
                                    emit(RoutedStreamResult(toolCall = retryEv.call, citations = citations, modelUsed = model))
                                } else if (retryEv is LlmStreamEvent.TextDelta) {
                                    delivered = true
                                    emit(RoutedStreamResult(delta = retryEv.delta, citations = citations, modelUsed = model))
                                }
                            }
                        }
                    }
                    is LlmStreamEvent.ErrorEvent -> {
                        failedThisProvider = true
                        lastError = ev.message
                    }
                    is LlmStreamEvent.Completed -> {
                        if (!failedThisProvider) {
                            delivered = true
                            emit(RoutedStreamResult(citations = citations, modelUsed = "$model (${provider.displayName})", completed = true))
                        }
                    }
                }
            }
            if (delivered) break
        }

        if (!delivered) {
            emit(RoutedStreamResult(error = lastError))
        }
    }

    private fun validateToolArgsJson(raw: String): String? {
        return runCatching {
            JSONObject(raw).toString()
        }.getOrNull()
    }

    private suspend fun buildRichSystemPrompt(persona: String, citations: List<RagCitation>): String {
        val tz = TimeZone.getDefault()
        val localNow = SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.getDefault()).apply {
            timeZone = tz
        }.format(Date()) + " (${tz.id})"
        val memories = memoryDao.getAllOnce().take(12)
        val skills = skillDao.getEnabledOnce()

        return buildString {
            appendLine(persona)
            appendLine("Multilingual Policy: Understand and reply naturally in the user's language (English, Urdu script, Roman Urdu, or Hindi/Hinglish) matching their phrasing concisely.")
            appendLine("Device Local Time & Timezone: $localNow")
            if (memories.isNotEmpty()) {
                appendLine("\nActive 4-Tier Memory:")
                memories.forEach { m ->
                    appendLine("- [${m.tier}] ${m.title}: ${m.content}")
                }
            }
            if (skills.isNotEmpty()) {
                appendLine("\nAvailable SKILL.md Playbooks:")
                skills.forEach { s ->
                    appendLine("- ${s.slug}: ${s.description}")
                }
            }
            if (citations.isNotEmpty()) {
                appendLine("\nRetrieved RAG Project Sources (cite as [Document p.X]):")
                citations.forEach { c ->
                    appendLine("- [${c.documentName}, p.${c.pageOrSection}]: ${c.snippet}")
                }
            }
        }
    }

    companion object {
        const val LOW_RAM_THRESHOLD_MB = 4096L
    }
}
