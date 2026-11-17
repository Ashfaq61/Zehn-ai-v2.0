package com.zehn.assistant.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zehn.assistant.ZehnApplication
import com.zehn.assistant.agent.AccessibilityDiagnostics
import com.zehn.assistant.agent.AgentLoop
import com.zehn.assistant.agent.AgentPlanState
import com.zehn.assistant.agent.CallAssistantManager
import com.zehn.assistant.agent.CallCapabilityReport
import com.zehn.assistant.agent.CallInteractionRecord
import com.zehn.assistant.agent.CameraLaunchRequest
import com.zehn.assistant.agent.FloatingBubbleService
import com.zehn.assistant.agent.PhoneControlExecutor
import com.zehn.assistant.agent.PlanManager
import com.zehn.assistant.agent.ScreenCaptureService
import com.zehn.assistant.agent.ScreenObservation
import com.zehn.assistant.agent.ScreenVisionManager
import com.zehn.assistant.agent.ScrollDirection
import com.zehn.assistant.agent.VisualScreenAnalysis
import com.zehn.assistant.agent.ZehnAccessibilityService
import com.zehn.assistant.agent.ZehnNotificationListenerService
import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.ApiProviderUsage
import com.zehn.assistant.ai.CloudLlmClient
import com.zehn.assistant.ai.GitHubRepoSuggestion
import com.zehn.assistant.ai.HuggingFaceModelSuggestion
import com.zehn.assistant.ai.LiteRtModelSpec
import com.zehn.assistant.ai.LlmMessage
import com.zehn.assistant.ai.OnDeviceLiteRtManager
import com.zehn.assistant.ai.OnDeviceModelState
import com.zehn.assistant.ai.OpenSourceDiscoveryClient
import com.zehn.assistant.ai.ProviderConnectionTestResult
import com.zehn.assistant.ai.RagEngine
import com.zehn.assistant.data.BackupManager
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.StorageBatteryTelemetry
import com.zehn.assistant.data.ZehnSettings
import com.zehn.assistant.upgrade.RepoReadinessReport
import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.data.db.AutoDevRunEntity
import com.zehn.assistant.data.db.ConversationDao
import com.zehn.assistant.data.db.ConversationEntity
import com.zehn.assistant.data.db.DiscoveredToolEntity
import com.zehn.assistant.data.db.MemoryDao
import com.zehn.assistant.data.db.MemoryEntity
import com.zehn.assistant.data.db.MessageDao
import com.zehn.assistant.data.db.MessageEntity
import com.zehn.assistant.data.db.ProjectEntity
import com.zehn.assistant.data.db.ProjectRagDao
import com.zehn.assistant.data.db.ReleaseRecordEntity
import com.zehn.assistant.data.db.SkillDao
import com.zehn.assistant.data.db.SkillEntity
import com.zehn.assistant.data.db.TriggerDao
import com.zehn.assistant.data.db.TriggerEntity
import com.zehn.assistant.data.db.UpgradeCenterDao
import com.zehn.assistant.data.db.UpgradeErrorLogEntity
import com.zehn.assistant.data.db.UpgradeProposalEntity
import com.zehn.assistant.data.db.WorkflowDao
import com.zehn.assistant.data.db.WorkflowEntity
import com.zehn.assistant.data.model.AiExecutionMode
import com.zehn.assistant.data.model.AiProvider
import com.zehn.assistant.data.model.LiteRtBackend
import com.zehn.assistant.data.model.MemoryTier
import com.zehn.assistant.data.model.SttEngineType
import com.zehn.assistant.data.model.TtsEngineType
import com.zehn.assistant.data.model.VadEngineType
import com.zehn.assistant.data.model.VoiceLanguage
import com.zehn.assistant.data.model.VoiceProfile
import com.zehn.assistant.security.CrashRecoveryManager
import com.zehn.assistant.security.CrashRecoveryReport
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.security.PendingConfirmationRequest
import com.zehn.assistant.security.SafetyPolicyManager
import com.zehn.assistant.security.SafetyPolicyState
import com.zehn.assistant.upgrade.ApkDownloadProgress
import com.zehn.assistant.upgrade.ApkInstallStatus
import com.zehn.assistant.upgrade.AutoDevExecutionState
import com.zehn.assistant.upgrade.AutonomousResearchAgent
import com.zehn.assistant.upgrade.GitHubAutoDevEngine
import com.zehn.assistant.upgrade.GitHubConnectionStatus
import com.zehn.assistant.upgrade.InstallSessionTracker
import com.zehn.assistant.upgrade.RealGitHubMetrics
import com.zehn.assistant.upgrade.ReleaseUpdateManager
import com.zehn.assistant.upgrade.ResearchCycleSummary
import com.zehn.assistant.upgrade.UpgradeScheduler
import com.zehn.assistant.voice.LanguageDetector
import com.zehn.assistant.voice.LiveChatTranscriptEvent
import com.zehn.assistant.voice.LiveModelsDiscoveryState
import com.zehn.assistant.voice.VoicePipelineCoordinator
import com.zehn.assistant.voice.VoicePipelineUiState
import com.zehn.assistant.voice.WakeWordService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ZehnViewModel @Inject constructor(
    private val app: Application,
    private val prefs: PreferencesRepository,
    val crypto: KeystoreCryptoManager,
    val safetyPolicy: SafetyPolicyManager,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao,
    private val projectRagDao: ProjectRagDao,
    private val skillDao: SkillDao,
    private val workflowDao: WorkflowDao,
    private val triggerDao: TriggerDao,
    private val actionLogDao: ActionLogDao,
    private val aiRouter: AiEngineRouter,
    private val cloudLlmClient: CloudLlmClient,
    private val discoveryClient: OpenSourceDiscoveryClient,
    val liteRtManager: OnDeviceLiteRtManager,
    private val ragEngine: RagEngine,
    val voiceCoordinator: VoicePipelineCoordinator,
    val agentLoop: AgentLoop,
    val planManager: PlanManager,
    val phoneControl: PhoneControlExecutor,
    val callAssistant: CallAssistantManager,
    private val screenVisionManager: ScreenVisionManager,
    private val backupManager: BackupManager,
    private val crashRecoveryManager: CrashRecoveryManager,
    private val upgradeCenterDao: UpgradeCenterDao,
    private val researchAgent: AutonomousResearchAgent,
    private val autoDevEngine: GitHubAutoDevEngine,
    private val releaseUpdateManager: ReleaseUpdateManager,
    private val upgradeScheduler: UpgradeScheduler
) : AndroidViewModel(app) {

    val settings: StateFlow<ZehnSettings> = prefs.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, ZehnSettings()
    )

    val policy: StateFlow<SafetyPolicyState> = safetyPolicy.policy
    val pendingConfirmation: StateFlow<PendingConfirmationRequest?> = safetyPolicy.pendingConfirmation
    val voiceUiState: StateFlow<VoicePipelineUiState> = voiceCoordinator.uiState
    val onDeviceState: StateFlow<OnDeviceModelState> = liteRtManager.state
    val agentPlanState: StateFlow<AgentPlanState> = planManager.planState
    val recordingActive: StateFlow<Boolean> = agentLoop.recordingActive
    val keysVersion: StateFlow<Int> = crypto.keysUpdatedVersion
    val providerUsage: StateFlow<Map<AiProvider, ApiProviderUsage>> = aiRouter.providerUsage
    val recentCallRecords: StateFlow<List<CallInteractionRecord>> = callAssistant.recentCalls
    val cameraLaunchRequests: SharedFlow<CameraLaunchRequest> = voiceCoordinator.cameraLaunchRequests

    val accessibilityConnected = ZehnAccessibilityService.instance
    val isAccessibilityServiceConnected: StateFlow<Boolean> = ZehnAccessibilityService.isConnected
    val accessibilityDiagnostics: StateFlow<AccessibilityDiagnostics> = ZehnAccessibilityService.diagnostics
    val mediaProjectionActive: StateFlow<Boolean> = ScreenCaptureService.projectionActive
    val notificationListenerConnected = ZehnNotificationListenerService.connected
    val lastCrashReport: StateFlow<CrashRecoveryReport?> = crashRecoveryManager.lastCrashReport

    private val _lastScreenObservation = MutableStateFlow<ScreenObservation?>(null)
    val lastScreenObservation: StateFlow<ScreenObservation?> = _lastScreenObservation.asStateFlow()

    private val _lastVisualAnalysis = MutableStateFlow<VisualScreenAnalysis?>(null)
    val lastVisualAnalysis: StateFlow<VisualScreenAnalysis?> = _lastVisualAnalysis.asStateFlow()

    private val _playgroundClickCount = MutableStateFlow(0)
    val playgroundClickCount: StateFlow<Int> = _playgroundClickCount.asStateFlow()

    private val _playgroundActionLog = MutableStateFlow("Tap button, scroll list, or swipe horizontal cards to verify Accessibility automation.")
    val playgroundActionLog: StateFlow<String> = _playgroundActionLog.asStateFlow()

    private val _screenshotPermissionStatus = MutableStateFlow(ScreenCaptureService.getPermissionStatus(app).label)
    val screenshotPermissionStatus: StateFlow<String> = _screenshotPermissionStatus.asStateFlow()

    private val _activeConversationId = MutableStateFlow(1L)
    val activeConversationId: StateFlow<Long> = _activeConversationId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversations: StateFlow<List<ConversationEntity>> = _searchQuery.flatMapLatest { q ->
        if (q.isBlank()) conversationDao.observeAll() else conversationDao.search(q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<MessageEntity>> = _activeConversationId.flatMapLatest { convId ->
        messageDao.observeForConversation(convId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memories: StateFlow<List<MemoryEntity>> = memoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val projects: StateFlow<List<ProjectEntity>> = projectRagDao.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val skills: StateFlow<List<SkillEntity>> = skillDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val workflows: StateFlow<List<WorkflowEntity>> = workflowDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val triggers: StateFlow<List<TriggerEntity>> = triggerDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val actionLogs: StateFlow<List<ActionLogEntity>> = actionLogDao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val discoveredTools: StateFlow<List<DiscoveredToolEntity>> = upgradeCenterDao.observeDiscoveredTools()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val upgradeProposals: StateFlow<List<UpgradeProposalEntity>> = upgradeCenterDao.observeProposals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val devRunHistory: StateFlow<List<AutoDevRunEntity>> = upgradeCenterDao.observeDevRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val releaseHistory: StateFlow<List<ReleaseRecordEntity>> = upgradeCenterDao.observeReleases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val upgradeErrorLogs: StateFlow<List<UpgradeErrorLogEntity>> = upgradeCenterDao.observeErrorLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val researchState: StateFlow<ResearchCycleSummary> = researchAgent.state
    val githubConnection: StateFlow<GitHubConnectionStatus> = autoDevEngine.connection
    val autoDevState: StateFlow<AutoDevExecutionState> = autoDevEngine.devState
    val realGitHubMetrics: StateFlow<RealGitHubMetrics> = autoDevEngine.realMetrics
    val apkDownloadProgress: StateFlow<ApkDownloadProgress> = releaseUpdateManager.downloadProgress
    val installStatus: StateFlow<ApkInstallStatus> = InstallSessionTracker.status
    val safeModeActive: StateFlow<Boolean> = crashRecoveryManager.safeModeActive

    private val _storageTelemetry = MutableStateFlow(backupManager.computeStorageBatteryTelemetry())
    val storageTelemetry: StateFlow<StorageBatteryTelemetry> = _storageTelemetry.asStateFlow()

    private val _safeModeChatReply = MutableStateFlow("")
    val safeModeChatReply: StateFlow<String> = _safeModeChatReply.asStateFlow()

    private val _safeModeChatBusy = MutableStateFlow(false)
    val safeModeChatBusy: StateFlow<Boolean> = _safeModeChatBusy.asStateFlow()

    private val _selectedProjectId = MutableStateFlow<Long?>(null)
    val selectedProjectId: StateFlow<Long?> = _selectedProjectId.asStateFlow()

    private val _chatBusy = MutableStateFlow(false)
    val chatBusy: StateFlow<Boolean> = _chatBusy.asStateFlow()

    private val _streamingDraft = MutableStateFlow("")
    val streamingDraft: StateFlow<String> = _streamingDraft.asStateFlow()

    private val _systemStatusText = MutableStateFlow(phoneControl.getSystemStatusSummary())
    val systemStatusText: StateFlow<String> = _systemStatusText.asStateFlow()

    private val _toastBanner = MutableStateFlow<String?>(null)
    val toastBanner: StateFlow<String?> = _toastBanner.asStateFlow()

    private val _autoTunerOutput = MutableStateFlow("")
    val autoTunerOutput: StateFlow<String> = _autoTunerOutput.asStateFlow()

    private val _crashFileText = MutableStateFlow<String?>(readCrashLogFile())
    val crashFileText: StateFlow<String?> = _crashFileText.asStateFlow()

    val liveModelsDiscovery: StateFlow<LiveModelsDiscoveryState> = voiceCoordinator.liveModelsDiscovery

    private val _liveInputDraft = MutableStateFlow("")
    val liveInputDraft: StateFlow<String> = _liveInputDraft.asStateFlow()

    private val _liveOutputDraft = MutableStateFlow("")
    val liveOutputDraft: StateFlow<String> = _liveOutputDraft.asStateFlow()

    private val _providerTestResults = MutableStateFlow<Map<AiProvider, ProviderConnectionTestResult>>(emptyMap())
    val providerTestResults: StateFlow<Map<AiProvider, ProviderConnectionTestResult>> = _providerTestResults.asStateFlow()

    private val _testingProvider = MutableStateFlow<AiProvider?>(null)
    val testingProvider: StateFlow<AiProvider?> = _testingProvider.asStateFlow()

    private val _githubResults = MutableStateFlow<List<GitHubRepoSuggestion>>(emptyList())
    val githubResults: StateFlow<List<GitHubRepoSuggestion>> = _githubResults.asStateFlow()

    private val _hfResults = MutableStateFlow<List<HuggingFaceModelSuggestion>>(emptyList())
    val hfResults: StateFlow<List<HuggingFaceModelSuggestion>> = _hfResults.asStateFlow()

    private val _discoveryLoading = MutableStateFlow(false)
    val discoveryLoading: StateFlow<Boolean> = _discoveryLoading.asStateFlow()

    init {
        viewModelScope.launch {
            val allConvs = conversationDao.getAllOnce()
            if (allConvs.isNotEmpty()) {
                _activeConversationId.value = allConvs.first().id
            }
        }
        viewModelScope.launch {
            voiceCoordinator.errors.collect { err ->
                if (err.isNotBlank()) {
                    _toastBanner.value = err
                }
            }
        }
        viewModelScope.launch {
            crypto.keysUpdatedVersion.collect {
                runCatching { voiceCoordinator.refreshAvailableLiveModels() }
            }
        }
        viewModelScope.launch {
            voiceCoordinator.liveChatEvents.collect { ev ->
                when (ev) {
                    is LiveChatTranscriptEvent.InputDraft -> _liveInputDraft.value = ev.text
                    is LiveChatTranscriptEvent.OutputDraft -> _liveOutputDraft.value = ev.text
                    is LiveChatTranscriptEvent.ClearDrafts -> {
                        _liveInputDraft.value = ""
                        _liveOutputDraft.value = ""
                    }
                    is LiveChatTranscriptEvent.CommittedTurn -> {
                        persistVoiceTranscriptToChat(ev.role, ev.content, ev.modelUsed)
                    }
                }
            }
        }
        // V4.2 startup release watcher & scheduled discovery initialization (skipped in Safe Mode or when Emergency Paused)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                _storageTelemetry.value = backupManager.computeStorageBatteryTelemetry()
                val s = prefs.settings.first()
                if (!crashRecoveryManager.safeModeActive.value && !s.allAutoUpgradesPaused) {
                    upgradeScheduler.applySchedule(
                        mode = s.researchScheduleMode,
                        focusMode = s.researchFocusMode,
                        paused = s.researchPaused,
                        wifiOnlyDiscovery = s.discoveryWifiOnly
                    )
                    if (s.githubRepoSlug.contains('/')) {
                        autoDevEngine.refreshRealGitHubMetrics()
                        releaseUpdateManager.checkForVerifiedReleaseUpdate(notifyIfNewer = true)
                    }
                }
            }
        }
    }

    fun isLowRamSingleRequestMode(): Boolean = aiRouter.isLowRamSingleRequestMode()
    fun callCapabilityReport(): CallCapabilityReport = callAssistant.capabilityReport()

    fun testProviderConnection(provider: AiProvider) = viewModelScope.launch {
        _testingProvider.value = provider
        val res = aiRouter.testProviderConnection(provider)
        _providerTestResults.value = _providerTestResults.value + (provider to res)
        _testingProvider.value = null
        _toastBanner.value = "${provider.displayName}: ${res.message}"
    }

    fun addGeminiKeySlot(key: String) {
        if (key.isBlank()) return
        crypto.addGeminiKey(key)
        _toastBanner.value = "Added Gemini API key slot"
    }

    fun removeGeminiKeySlot(index: Int) {
        crypto.removeGeminiKeyAt(index)
        _toastBanner.value = "Removed Gemini API key slot #${index + 1}"
    }

    fun deleteProviderSecret(keyName: String, label: String) {
        crypto.deleteSecret(keyName)
        _toastBanner.value = "Deleted $label from encrypted vault"
    }

    fun searchOpenSourceTools(query: String) = viewModelScope.launch {
        _discoveryLoading.value = true
        discoveryClient.searchGitHubTools(query).onSuccess { items ->
            _githubResults.value = items
            if (items.isEmpty()) _toastBanner.value = "No GitHub repositories matched '$query'"
        }.onFailure { err ->
            _toastBanner.value = err.message ?: "GitHub search failed"
        }
        _discoveryLoading.value = false
    }

    fun searchHuggingFaceModels(query: String) = viewModelScope.launch {
        _discoveryLoading.value = true
        discoveryClient.searchHuggingFaceModels(query).onSuccess { items ->
            _hfResults.value = items
            if (items.isEmpty()) _toastBanner.value = "No Hugging Face models matched '$query'"
        }.onFailure { err ->
            _toastBanner.value = err.message ?: "Hugging Face search failed"
        }
        _discoveryLoading.value = false
    }

    private suspend fun persistVoiceTranscriptToChat(role: String, content: String, modelUsed: String) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        runCatching {
            var convId = _activeConversationId.value
            val existing = conversationDao.getAllOnce().firstOrNull { it.id == convId }
            if (existing == null) {
                convId = conversationDao.insert(
                    ConversationEntity(
                        title = clean.take(36),
                        providerName = AiProvider.GEMINI.name,
                        modelName = modelUsed
                    )
                )
                _activeConversationId.value = convId
            } else if (role == "user" && existing.title == "New Chat") {
                conversationDao.update(existing.copy(title = clean.take(36), updatedAt = System.currentTimeMillis()))
            }
            messageDao.insert(
                MessageEntity(
                    conversationId = convId,
                    role = role,
                    content = clean,
                    modelUsed = if (role == "assistant") modelUsed else "",
                    detectedLanguage = LanguageDetector.detect(clean, settings.value.voiceLanguage).code
                )
            )
        }
    }

    private fun readCrashLogFile(): String? {
        return runCatching {
            val file = File(app.filesDir, ZehnApplication.CRASH_LOG_FILE_NAME)
            if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
        }.getOrNull()
    }

    fun clearCrashLogFile() {
        runCatching {
            val file = File(app.filesDir, ZehnApplication.CRASH_LOG_FILE_NAME)
            if (file.exists()) file.delete()
        }
        _crashFileText.value = null
        crashRecoveryManager.dismissCrashReport()
    }

    fun clearBanner() {
        _toastBanner.value = null
    }

    fun showBanner(msg: String) {
        _toastBanner.value = msg
    }

    fun dismissCrashReport() {
        crashRecoveryManager.dismissCrashReport()
    }

    fun refreshTelemetry() {
        _systemStatusText.value = phoneControl.getSystemStatusSummary()
        liteRtManager.refreshDeviceCapabilities()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            prefs.updateSettings { it.copy(onboardingCompleted = true) }
        }
    }

    fun setConversationSearch(q: String) {
        _searchQuery.value = q
    }

    fun selectConversation(id: Long) {
        _activeConversationId.value = id
    }

    fun createNewConversation(projectId: Long? = _selectedProjectId.value) {
        viewModelScope.launch {
            val s = settings.value
            val id = conversationDao.insert(
                ConversationEntity(
                    title = "New Chat",
                    projectId = projectId,
                    providerName = s.activeProvider.name,
                    modelName = s.activeModel
                )
            )
            _activeConversationId.value = id
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            conversationDao.deleteById(id)
            val remaining = conversationDao.getAllOnce()
            if (remaining.isEmpty()) createNewConversation()
            else if (_activeConversationId.value == id) _activeConversationId.value = remaining.first().id
        }
    }

    fun selectProject(projectId: Long?) {
        _selectedProjectId.value = projectId
    }

    fun sendChatMessage(text: String, attachedImageUri: Uri? = null, speakReply: Boolean = false) {
        val clean = text.trim()
        if (clean.isEmpty() && attachedImageUri == null) return
        if (_chatBusy.value) return

        viewModelScope.launch {
            if (safetyPolicy.policy.value.emergencyStopped) {
                _toastBanner.value = "Emergency STOP is active. Tap RESUME first."
                return@launch
            }
            _chatBusy.value = true
            _streamingDraft.value = ""

            try {
                var convId = _activeConversationId.value
                val existing = conversationDao.getAllOnce().firstOrNull { it.id == convId }
                if (existing == null) {
                    convId = conversationDao.insert(
                        ConversationEntity(
                            title = clean.take(36).ifBlank { "Vision Query" },
                            providerName = settings.value.activeProvider.name,
                            modelName = settings.value.activeModel
                        )
                    )
                    _activeConversationId.value = convId
                } else if (existing.title == "New Chat" && clean.isNotBlank()) {
                    conversationDao.update(existing.copy(title = clean.take(36), updatedAt = System.currentTimeMillis()))
                }

                val detectedLang = LanguageDetector.detect(clean, settings.value.voiceLanguage)
                messageDao.insert(
                    MessageEntity(
                        conversationId = convId,
                        role = "user",
                        content = clean.ifBlank { "Analyze attached image" },
                        imageUri = attachedImageUri?.toString(),
                        detectedLanguage = detectedLang.code
                    )
                )

                memoryDao.insert(
                    MemoryEntity(
                        tier = MemoryTier.WORKING.name,
                        title = "Last User Query",
                        content = clean.take(180),
                        priority = 6
                    )
                )

                if (attachedImageUri == null) {
                    val agentReply = agentLoop.tryExecuteCommand(clean)
                    if (agentReply != null) {
                        messageDao.insert(
                            MessageEntity(
                                conversationId = convId,
                                role = "assistant",
                                content = agentReply,
                                modelUsed = "Zehn Agent",
                                detectedLanguage = detectedLang.code
                            )
                        )
                        if (speakReply) voiceCoordinator.previewVoice(settings.value.activeVoice)
                        return@launch
                    }
                }

                val history = messageDao.getForConversationOnce(convId)
                    .takeLast(12)
                    .map { LlmMessage(it.role, it.content) }

                val replyAcc = StringBuilder()
                var citationsText = ""
                var usedModel = settings.value.activeModel
                var errMsg: String? = null

                aiRouter.routeAndStream(
                    history = history,
                    userInput = clean.ifBlank { "Describe and analyze this image carefully." },
                    projectId = _selectedProjectId.value,
                    imageUri = attachedImageUri
                ).collect { ev ->
                    if (ev.modelUsed.isNotBlank()) usedModel = ev.modelUsed
                    if (ev.citations.isNotEmpty()) {
                        citationsText = ev.citations.joinToString("\n") {
                            "• ${it.documentName} (p.${it.pageOrSection})"
                        }
                    }
                    if (ev.delta.isNotEmpty()) {
                        replyAcc.append(ev.delta)
                        _streamingDraft.value = replyAcc.toString()
                    }
                    if (ev.error != null) errMsg = ev.error
                }

                if (errMsg != null) {
                    _toastBanner.value = errMsg
                }

                val finalContent = buildString {
                    val main = replyAcc.toString().ifBlank { errMsg ?: "No response." }
                    append(main)
                    if (citationsText.isNotBlank() && errMsg == null) {
                        append("\n\nSources:\n$citationsText")
                    }
                }

                messageDao.insert(
                    MessageEntity(
                        conversationId = convId,
                        role = "assistant",
                        content = finalContent,
                        modelUsed = usedModel,
                        detectedLanguage = detectedLang.code
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toastBanner.value = "Chat failed: ${e.message ?: "Unexpected error"}"
            } finally {
                _streamingDraft.value = ""
                _chatBusy.value = false
            }
        }
    }

    fun generateImageInChat(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            _chatBusy.value = true
            try {
                val convId = _activeConversationId.value
                messageDao.insert(MessageEntity(conversationId = convId, role = "user", content = "/imagine $prompt"))
                val res = cloudLlmClient.generateImage(prompt)
                res.onSuccess { path ->
                    messageDao.insert(
                        MessageEntity(
                            conversationId = convId,
                            role = "assistant",
                            content = "Generated image for \"$prompt\":",
                            imageUri = Uri.fromFile(File(path)).toString(),
                            modelUsed = "Image Generator"
                        )
                    )
                }.onFailure { e ->
                    val errText = e.message ?: "Image generation failed"
                    _toastBanner.value = errText
                    messageDao.insert(
                        MessageEntity(
                            conversationId = convId,
                            role = "assistant",
                            content = errText,
                            modelUsed = "Safety / Image"
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toastBanner.value = "Image generation failed: ${e.message ?: "Unexpected error"}"
            } finally {
                _chatBusy.value = false
            }
        }
    }

    fun setExecutionMode(mode: AiExecutionMode) = viewModelScope.launch {
        prefs.updateSettings { it.copy(executionMode = mode) }
    }

    fun setProviderAndModel(provider: AiProvider, model: String) = viewModelScope.launch {
        prefs.updateSettings { it.copy(activeProvider = provider, activeModel = model) }
    }

    fun setVoiceProfile(profile: VoiceProfile) = viewModelScope.launch {
        prefs.updateSettings { it.copy(activeVoice = profile) }
    }

    fun setVoiceLanguage(lang: VoiceLanguage) = viewModelScope.launch {
        prefs.updateSettings { it.copy(voiceLanguage = lang) }
    }

    fun setPipelineEngines(vad: VadEngineType, stt: SttEngineType, tts: TtsEngineType) = viewModelScope.launch {
        prefs.updateSettings { it.copy(vadEngine = vad, sttEngine = stt, ttsEngine = tts) }
    }

    fun setVoiceTuning(rate: Float, pitch: Float) = viewModelScope.launch {
        prefs.updateSettings { it.copy(voiceRate = rate, voicePitch = pitch) }
    }

    fun setClonedVoiceUri(uri: String) = viewModelScope.launch {
        prefs.updateSettings {
            it.copy(
                clonedVoiceReferenceUri = uri,
                ttsEngine = if (uri.isNotBlank()) TtsEngineType.CLONED_PROFILE else TtsEngineType.ANDROID_STREAMING
            )
        }
    }

    fun setLiteRtBackend(backend: LiteRtBackend) = viewModelScope.launch {
        prefs.updateSettings { it.copy(liteRtBackend = backend) }
    }

    fun setSpeculativeDecoding(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(speculativeDecodingEnabled = enabled) }
    }

    fun setAutoTuner(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(autoTunerEnabled = enabled) }
    }

    fun selectAndLoadLiteRtModel(spec: LiteRtModelSpec) = viewModelScope.launch {
        prefs.updateSettings { it.copy(selectedLiteRtModelId = spec.id) }
        val ok = liteRtManager.loadModel(spec, settings.value.liteRtBackend, settings.value.personaPrompt)
        _toastBanner.value = if (ok) "${spec.name} loaded into memory" else liteRtManager.state.value.lastError
    }

    fun runLiteRtAutoTuner() = viewModelScope.launch {
        _autoTunerOutput.value = liteRtManager.runAutoTuner()
    }

    fun toggleWakeWord(enabled: Boolean) = viewModelScope.launch {
        if (enabled) {
            val started = WakeWordService.start(app)
            prefs.updateSettings { it.copy(wakeWordEnabled = started) }
            if (!started) {
                _toastBanner.value = "Grant Microphone permission in Permissions before enabling Wake Word."
            }
        } else {
            prefs.updateSettings { it.copy(wakeWordEnabled = false) }
            WakeWordService.stop(app)
        }
    }

    fun toggleContinuousConversation(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(continuousConversationEnabled = enabled) }
    }

    fun toggleFloatingBubble(enabled: Boolean) = viewModelScope.launch {
        if (enabled && !Settings.canDrawOverlays(app)) {
            prefs.updateSettings { it.copy(floatingBubbleEnabled = false) }
            _toastBanner.value = "Grant Display Over Other Apps permission first."
            return@launch
        }
        prefs.updateSettings { it.copy(floatingBubbleEnabled = enabled) }
        if (enabled) FloatingBubbleService.start(app) else FloatingBubbleService.stop(app)
    }

    fun toggleHaptics(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(hapticFeedbackEnabled = enabled) }
    }

    fun toggleRealtimeWs(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(realtimeWebSocketEnabled = enabled) }
    }

    fun toggleGeminiLiveVoice(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(geminiLiveVoiceEnabled = enabled) }
    }

    fun setGeminiLiveModel(modelId: String) = viewModelScope.launch {
        val clean = modelId.trim().removePrefix("models/")
        if (clean.isNotEmpty()) {
            prefs.updateSettings { it.copy(geminiLiveModel = clean) }
        }
    }

    fun setGeminiLiveFallbackModel(modelId: String) = viewModelScope.launch {
        val clean = modelId.trim().removePrefix("models/")
        if (clean.isNotEmpty()) {
            prefs.updateSettings { it.copy(geminiLiveFallbackModel = clean) }
        }
    }

    fun setGeminiLiveCustomModel(modelId: String) = viewModelScope.launch {
        val clean = modelId.trim().removePrefix("models/")
        prefs.updateSettings { it.copy(geminiLiveCustomModel = clean) }
        _toastBanner.value = if (clean.isEmpty()) "Custom Live model cleared" else "Custom Live model saved: $clean"
    }

    fun refreshAvailableLiveModels() = viewModelScope.launch {
        voiceCoordinator.refreshAvailableLiveModels()
    }

    fun toggleWebhookMode(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(webhookModeEnabled = enabled) }
    }

    fun toggleCallAssistant(enabled: Boolean) = viewModelScope.launch {
        prefs.updateSettings { it.copy(callAssistantEnabled = enabled) }
        if (enabled) callAssistant.startMonitoringIfPermitted()
    }

    fun updateCallAssistantConfig(
        enabled: Boolean,
        autoAnswer: Boolean,
        delaySec: Int,
        vipCsv: String,
        callLanguage: VoiceLanguage,
        receptionistPrompt: String,
        voipEnabled: Boolean,
        voipUri: String
    ) = viewModelScope.launch {
        prefs.updateSettings {
            it.copy(
                callAssistantEnabled = enabled,
                callAutoAnswerEnabled = autoAnswer,
                callAutoAnswerDelaySec = delaySec.coerceIn(2, 15),
                vipContactsCsv = vipCsv,
                callLanguage = callLanguage,
                callReceptionistPrompt = receptionistPrompt,
                voipSipModeEnabled = voipEnabled,
                voipSipServerUri = voipUri.trim()
            )
        }
        if (enabled) callAssistant.startMonitoringIfPermitted()
        _toastBanner.value = "Call Assistant configuration saved"
    }

    fun toggleAutoReplySms(enabled: Boolean, text: String) = viewModelScope.launch {
        prefs.updateSettings { it.copy(autoReplySmsEnabled = enabled, autoReplySmsText = text) }
    }

    fun updatePersona(persona: String) = viewModelScope.launch {
        prefs.updateSettings { it.copy(personaPrompt = persona) }
        _toastBanner.value = "Persona updated"
    }

    fun updateDisplayName(name: String) = viewModelScope.launch {
        prefs.updateSettings { it.copy(userDisplayName = name) }
    }

    fun addMemory(tier: MemoryTier, title: String, content: String, priority: Int = 6) = viewModelScope.launch {
        memoryDao.insert(MemoryEntity(tier = tier.name, title = title, content = content, priority = priority))
    }

    fun forgetMemory(id: Long) = viewModelScope.launch { memoryDao.forgetById(id) }
    fun clearMemoryTier(tier: MemoryTier) = viewModelScope.launch { memoryDao.clearTier(tier.name) }

    fun createRagProject(name: String, desc: String) = viewModelScope.launch {
        val id = projectRagDao.insertProject(ProjectEntity(name = name, description = desc))
        _selectedProjectId.value = id
    }

    fun ingestRagDocument(projectId: Long, uri: Uri, name: String) = viewModelScope.launch {
        val res = ragEngine.ingestDocument(projectId, uri, name)
        _toastBanner.value = res.fold(
            onSuccess = { chunks -> "Indexed $chunks chunks from $name" },
            onFailure = { err -> err.message ?: "Failed to index document" }
        )
    }

    fun saveSkill(skill: SkillEntity) = viewModelScope.launch { skillDao.insert(skill) }
    fun toggleSkill(skill: SkillEntity, enabled: Boolean) = viewModelScope.launch {
        skillDao.update(skill.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
    }
    fun deleteSkill(id: Long) = viewModelScope.launch { skillDao.deleteById(id) }

    fun addTrigger(trigger: TriggerEntity) = viewModelScope.launch { triggerDao.insert(trigger) }
    fun toggleTrigger(id: Long, enabled: Boolean) = viewModelScope.launch { triggerDao.setEnabled(id, enabled) }
    fun deleteTrigger(id: Long) = viewModelScope.launch { triggerDao.deleteById(id) }

    fun saveWorkflow(wf: WorkflowEntity) = viewModelScope.launch { workflowDao.insert(wf) }
    fun deleteWorkflow(id: Long) = viewModelScope.launch { workflowDao.deleteById(id) }
    fun runWorkflow(wf: WorkflowEntity) = viewModelScope.launch {
        val out = agentLoop.executeWorkflowOrFlow(wf)
        workflowDao.update(wf.copy(lastRunAt = System.currentTimeMillis(), runCount = wf.runCount + 1))
        _toastBanner.value = out
    }

    fun runAgentMission(goal: String) = viewModelScope.launch {
        val out = agentLoop.tryExecuteCommand(goal) ?: agentLoop.runGuiLoop(goal)
        _toastBanner.value = out
    }

    fun stopRecordingAndSaveFlow(name: String) = viewModelScope.launch {
        val yaml = agentLoop.stopRecordingAsYaml(name)
        workflowDao.insert(
            WorkflowEntity(
                name = name,
                description = "Recorded instant-replay Flow (no LLM required)",
                kind = "FLOW_YAML",
                definitionText = yaml
            )
        )
        _toastBanner.value = "Saved Flow '$name'"
    }

    fun clearActionLogs() = viewModelScope.launch { actionLogDao.clearAll() }

    fun toggleEmergencyStop() {
        if (safetyPolicy.policy.value.emergencyStopped) {
            safetyPolicy.clearEmergencyStop()
            _toastBanner.value = "Safety STOP cleared"
        } else {
            safetyPolicy.triggerEmergencyStop()
            voiceCoordinator.stopAll()
            liteRtManager.cancelActiveGeneration()
            _toastBanner.value = "EMERGENCY STOP TRIGGERED"
        }
    }

    suspend fun exportBackupJson(): String = backupManager.exportAllToJson()
    fun importBackupJson(raw: String) = viewModelScope.launch {
        val res = backupManager.importFromJson(raw)
        _toastBanner.value = res.fold(
            onSuccess = { count -> "Imported $count items successfully" },
            onFailure = { "Invalid backup JSON: ${it.message}" }
        )
    }

    // ---- V4.0 Autonomous GitHub Research, Self-Upgrade & APK Build Actions ----

    fun saveGitHubConfig(repoSlug: String, baseBranch: String, tokenDraft: String) = viewModelScope.launch {
        if (tokenDraft.isNotBlank()) {
            crypto.saveSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN, tokenDraft.trim())
        }
        prefs.updateSettings {
            it.copy(
                githubRepoSlug = repoSlug.trim(),
                githubBaseBranch = baseBranch.trim().ifBlank { "main" }
            )
        }
        _toastBanner.value = "Saved encrypted GitHub settings"
    }

    fun testGitHubConnection(repoSlug: String, baseBranch: String, tokenDraft: String) = viewModelScope.launch {
        if (tokenDraft.isNotBlank()) {
            crypto.saveSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN, tokenDraft.trim())
        }
        prefs.updateSettings {
            it.copy(
                githubRepoSlug = repoSlug.trim(),
                githubBaseBranch = baseBranch.trim().ifBlank { "main" }
            )
        }
        val status = autoDevEngine.testConnection(repoSlug)
        _toastBanner.value = status.message
    }

    fun deleteGitHubToken() {
        crypto.deleteSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN)
        _toastBanner.value = "GitHub PAT deleted from Keystore"
    }

    fun updateAutoDevSafetyFlags(
        autoDevEnabled: Boolean,
        autoMergeOnGreen: Boolean,
        allowWorkflowEdits: Boolean,
        notifyUpdateReady: Boolean,
        notifyBuildFailure: Boolean
    ) = viewModelScope.launch {
        prefs.updateSettings {
            it.copy(
                autoDevEnabled = autoDevEnabled,
                autoMergeOnGreen = autoMergeOnGreen,
                allowWorkflowEdits = allowWorkflowEdits,
                notifyOnUpdateReady = notifyUpdateReady,
                notifyOnBuildFailure = notifyBuildFailure
            )
        }
        _toastBanner.value = "Updated Auto-Dev safety rules"
    }

    fun updateResearchSchedule(mode: String, focusMode: String, paused: Boolean) = viewModelScope.launch {
        upgradeScheduler.applySchedule(mode, focusMode, paused)
        _toastBanner.value = if (paused) "Scheduled research paused" else "Research schedule: $mode ($focusMode)"
    }

    fun startResearch(
        includeGitHub: Boolean = true,
        includeHuggingFace: Boolean = true,
        customQuery: String? = null
    ) = viewModelScope.launch {
        val res = researchAgent.runFullResearchCycle(
            includeGitHub = includeGitHub,
            includeHuggingFace = includeHuggingFace,
            customQuery = customQuery
        )
        _toastBanner.value = res.fold(
            onSuccess = { s -> "Found ${s.newlyDiscoveredCount} new tools, ${s.proposalsCreatedCount} integration plans" },
            onFailure = { err -> err.message ?: "Research failed" }
        )
    }

    fun stopResearchAndAutoDev() {
        researchAgent.stopResearch()
        autoDevEngine.stopAll()
        _toastBanner.value = "Autonomous Research & Auto-Dev stopped immediately"
    }

    fun approveProposalAndStage(proposalId: Long, stageToGitHub: Boolean = true) = viewModelScope.launch {
        upgradeCenterDao.updateProposalStatus(proposalId, "APPROVED")
        if (!stageToGitHub) {
            _toastBanner.value = "Proposal #$proposalId marked APPROVED"
            return@launch
        }
        val res = autoDevEngine.executeIntegrationProposal(
            proposalId = proposalId,
            onBuildFailureNotify = { err -> releaseUpdateManager.showBuildFailureNotification(err) }
        )
        _toastBanner.value = res.fold(
            onSuccess = { p -> "Staged branch ${p.branchName} (${p.status})" },
            onFailure = { err -> err.message ?: "Staging failed" }
        )
    }

    fun rejectProposal(proposalId: Long) = viewModelScope.launch {
        upgradeCenterDao.updateProposalStatus(proposalId, "REJECTED")
        _toastBanner.value = "Proposal #$proposalId rejected"
    }

    fun triggerBuildApk(branchOverride: String? = null) = viewModelScope.launch {
        val res = autoDevEngine.triggerGitHubActionsBuild(branchOverride)
        _toastBanner.value = res.fold(
            onSuccess = { it },
            onFailure = { err -> err.message ?: "Unable to start GitHub Actions build" }
        )
    }

    fun pollBuildStatus() = viewModelScope.launch {
        val res = autoDevEngine.pollLatestActionsRun()
        _toastBanner.value = res.fold(
            onSuccess = { b -> "Build #${b.runId}: ${b.status} / ${b.conclusion.ifBlank { "running" }}" },
            onFailure = { err -> err.message ?: "Poll failed" }
        )
    }

    fun checkForApkUpdates() = viewModelScope.launch {
        val res = releaseUpdateManager.checkForVerifiedReleaseUpdate(notifyIfNewer = true)
        _toastBanner.value = res.fold(
            onSuccess = { it.statusMessage },
            onFailure = { err -> err.message ?: "Release check failed" }
        )
    }

    fun openReleaseDownload(release: ReleaseRecordEntity) {
        if (!releaseUpdateManager.openVerifiedUpdateInBrowser(release)) {
            _toastBanner.value = "Release URL is missing or failed trusted GitHub origin check"
        }
    }

    fun restorePreviousVersion(branchToReset: String? = null) = viewModelScope.launch {
        val res = autoDevEngine.rollbackBranchToStable(branchToReset)
        _toastBanner.value = res.fold(
            onSuccess = { it },
            onFailure = { err -> err.message ?: "Rollback failed" }
        )
    }

    fun clearDevRunHistory() = viewModelScope.launch {
        upgradeCenterDao.clearDevRuns()
    }

    private val _repoReadiness = MutableStateFlow<RepoReadinessReport?>(null)
    val repoReadiness: StateFlow<RepoReadinessReport?> = _repoReadiness.asStateFlow()

    fun checkRepoReadiness() = viewModelScope.launch {
        val res = autoDevEngine.checkRepoReadiness()
        _repoReadiness.value = res.getOrNull()
        _toastBanner.value = res.fold(
            onSuccess = { if (it.allPassed) "Repo readiness: All checks passed!" else "Repo readiness: Some items need attention" },
            onFailure = { err -> err.message ?: "Repo readiness check failed" }
        )
    }

    fun bootstrapNewRepo() = viewModelScope.launch {
        val res = autoDevEngine.bootstrapNewRepoFiles()
        _toastBanner.value = res.fold(
            onSuccess = { msg ->
                checkRepoReadiness()
                msg
            },
            onFailure = { err -> err.message ?: "Bootstrap failed" }
        )
    }

    // ---- V4.2 Self-Upgrade, In-App Updater, Safe Mode & Health Actions ----

    fun togglePauseAllAutoUpgrades() = viewModelScope.launch {
        val currentPaused = settings.value.allAutoUpgradesPaused
        val nextPaused = !currentPaused
        prefs.updateSettings { it.copy(allAutoUpgradesPaused = nextPaused) }
        if (nextPaused) {
            researchAgent.stopResearch()
            autoDevEngine.stopAll()
            upgradeScheduler.pauseAllWorkersImmediately()
            _toastBanner.value = "All auto-upgrades PAUSED immediately."
        } else {
            val s = settings.value
            upgradeScheduler.applySchedule(
                mode = s.researchScheduleMode,
                focusMode = s.researchFocusMode,
                paused = s.researchPaused,
                wifiOnlyDiscovery = s.discoveryWifiOnly
            )
            _toastBanner.value = "Auto-upgrades RESUMED."
        }
    }

    fun updateDiscoveryConfig(
        topicsCsv: String,
        wifiOnly: Boolean,
        autoApprove: Boolean,
        scheduleMode: String,
        focusMode: String
    ) = viewModelScope.launch {
        prefs.updateSettings {
            it.copy(
                discoveryTopicsCsv = topicsCsv.trim().ifBlank { com.zehn.assistant.data.DEFAULT_DISCOVERY_TOPICS },
                discoveryWifiOnly = wifiOnly,
                autoApproveEnabled = autoApprove
            )
        }
        upgradeScheduler.applySchedule(
            mode = scheduleMode,
            focusMode = focusMode,
            paused = settings.value.researchPaused || settings.value.allAutoUpgradesPaused,
            wifiOnlyDiscovery = wifiOnly
        )
        _toastBanner.value = "Saved discovery topics & Wi-Fi/battery policy"
    }

    fun updateUpdaterConfig(
        channel: String,
        updaterWifiOnly: Boolean,
        autoMergeOnGreen: Boolean,
        allowWorkflowEdits: Boolean,
        notifyUpdateReady: Boolean,
        notifyBuildFailure: Boolean
    ) = viewModelScope.launch {
        prefs.updateSettings {
            it.copy(
                releaseChannel = channel,
                updaterWifiOnly = updaterWifiOnly,
                autoMergeOnGreen = autoMergeOnGreen,
                allowWorkflowEdits = allowWorkflowEdits,
                notifyOnUpdateReady = notifyUpdateReady,
                notifyOnBuildFailure = notifyBuildFailure
            )
        }
        _toastBanner.value = "Saved Updater ($channel channel, Wi-Fi-only=$updaterWifiOnly)"
    }

    fun downloadAndInstallRelease(release: ReleaseRecordEntity, bypassWifiOnly: Boolean = false) = viewModelScope.launch {
        val dlRes = releaseUpdateManager.downloadAndVerifyReleaseApk(release, bypassWifiOnly = bypassWifiOnly)
        dlRes.onFailure { err ->
            _toastBanner.value = err.message ?: "APK verification/download failed"
            refreshStorageTelemetry()
        }.onSuccess { apkFile ->
            refreshStorageTelemetry()
            val installRes = releaseUpdateManager.installVerifiedApk(release, apkFile)
            _toastBanner.value = installRes.message
            if (installRes.needsUnknownSourcesPermission) {
                runCatching { app.startActivity(releaseUpdateManager.buildManageUnknownSourcesIntent()) }
            }
            refreshStorageTelemetry()
        }
    }

    fun openUnknownSourcesSettings() {
        runCatching { app.startActivity(releaseUpdateManager.buildManageUnknownSourcesIntent()) }
            .onFailure { _toastBanner.value = "Open Android Settings -> Install unknown apps -> Allow Zehn AI" }
    }

    fun refreshRealMetricsAndTelemetry() = viewModelScope.launch {
        refreshStorageTelemetry()
        autoDevEngine.refreshRealGitHubMetrics()
    }

    fun refreshStorageTelemetry() {
        _storageTelemetry.value = backupManager.computeStorageBatteryTelemetry()
    }

    fun restorePreviousVersionRollbackPr(targetTag: String = "") = viewModelScope.launch {
        val res = autoDevEngine.createRollbackPrForPreviousVersion(targetReleaseTag = targetTag)
        _toastBanner.value = res.fold(
            onSuccess = { it },
            onFailure = { err -> err.message ?: "Rollback PR failed" }
        )
    }

    fun createPreUpdateBackup() = viewModelScope.launch {
        val res = backupManager.createPreUpdateBackupSnapshot("manual")
        refreshStorageTelemetry()
        _toastBanner.value = res.fold(
            onSuccess = { f -> "Saved backup snapshot: ${f.name}" },
            onFailure = { err -> err.message ?: "Backup snapshot failed" }
        )
    }

    fun restoreLatestPreUpdateBackup() = viewModelScope.launch {
        val res = backupManager.restoreLatestPreUpdateBackup()
        refreshStorageTelemetry()
        _toastBanner.value = res.fold(
            onSuccess = { count -> "Restored $count items from pre-update backup snapshot" },
            onFailure = { err -> err.message ?: "No backup snapshot to restore" }
        )
    }

    fun clearUpgradeErrorLogs() = viewModelScope.launch {
        upgradeCenterDao.clearErrorLogs()
        _toastBanner.value = "Cleared local upgrade error log"
    }

    fun createSafeModeGitHubIssue() = viewModelScope.launch {
        val crashReport = crashRecoveryManager.lastCrashReport.value
        val trace = listOfNotNull(crashReport?.sanitizedStackTrace, _crashFileText.value)
            .firstOrNull { it.isNotBlank() }
            ?: crashReport?.sanitizedMessage
            ?: "Repeated startup crash"
        val res = autoDevEngine.createGitHubIssueForCrash(
            title = crashReport?.exceptionType ?: "RepeatedCrash",
            sanitizedCrashLog = trace
        )
        _toastBanner.value = res.fold(
            onSuccess = { it },
            onFailure = { err -> err.message ?: "Could not create GitHub issue" }
        )
    }

    fun queueSafeModeAutoDevFix() = viewModelScope.launch {
        val crashReport = crashRecoveryManager.lastCrashReport.value
        val trace = listOfNotNull(crashReport?.sanitizedStackTrace, _crashFileText.value)
            .firstOrNull { it.isNotBlank() }
            ?: crashReport?.sanitizedMessage
            ?: "Repeated startup crash"
        val res = autoDevEngine.createCrashFixAutoDevTask(trace)
        _toastBanner.value = res.fold(
            onSuccess = { p -> "Crash fix task '${p.branchName}' (${p.status})" },
            onFailure = { err -> err.message ?: "Could not stage Auto-Dev crash fix" }
        )
    }

    fun exitSafeMode() {
        crashRecoveryManager.exitSafeMode()
        clearCrashLogFile()
        _toastBanner.value = "Exited Safe Mode"
    }

    fun sendSafeModeBasicChat(prompt: String) = viewModelScope.launch {
        val clean = prompt.trim()
        if (clean.isEmpty() || _safeModeChatBusy.value) return@launch
        _safeModeChatBusy.value = true
        _safeModeChatReply.value = ""
        try {
            val acc = StringBuilder()
            aiRouter.routeAndStream(emptyList(), clean).collect { ev ->
                if (ev.delta.isNotEmpty()) {
                    acc.append(ev.delta)
                    _safeModeChatReply.value = acc.toString()
                }
                if (ev.error != null && acc.isEmpty()) {
                    _safeModeChatReply.value = ev.error
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _safeModeChatReply.value = "Safe Mode chat error: ${e.message}"
        } finally {
            _safeModeChatBusy.value = false
        }
    }

    fun runVoiceHealthTest() = viewModelScope.launch {
        val msg = runCatching { voiceCoordinator.runVoiceHealthSelfTest() }
            .getOrElse { "Voice check failed: ${it.message}" }
        _toastBanner.value = msg
    }

    // ---- V4.1 Accessibility Debug Dashboard & Screen Vision Actions ----

    fun incrementPlaygroundClick() {
        val next = _playgroundClickCount.value + 1
        _playgroundClickCount.value = next
        _playgroundActionLog.value = "Test Tap Target clicked! Count = $next"
    }

    fun recordPlaygroundEvent(msg: String) {
        _playgroundActionLog.value = msg
    }

    fun refreshAccessibilityDiagnostics() {
        ZehnAccessibilityService.refreshDiagnosticsSnapshot(app)
        _screenshotPermissionStatus.value = ScreenCaptureService.getPermissionStatus(app).label
    }

    fun runTestScreenReading() = viewModelScope.launch(Dispatchers.Default) {
        refreshAccessibilityDiagnostics()
        val svc = ZehnAccessibilityService.instance.value
        if (svc == null) {
            val enabled = ZehnAccessibilityService.isAccessibilityPermissionEnabled(app)
            val err = if (enabled) {
                "Permission enabled in Settings, but ZehnAccessibilityService is not bound yet. Tap Reconnect Service."
            } else {
                "Accessibility permission DISABLED & service DISCONNECTED. Enable Zehn AI Accessibility first."
            }
            ZehnAccessibilityService.recordExternalAction("TEST_SCREEN_READING", err, err)
            _toastBanner.value = err
            return@launch
        }
        val obs = svc.observeScreenStructured()
        _lastScreenObservation.value = obs
        _screenshotPermissionStatus.value = ScreenCaptureService.getPermissionStatus(app).label
        _toastBanner.value = if (!obs.error.isNullOrBlank()) {
            obs.error
        } else {
            "Read ${obs.totalNodeCount} nodes (${obs.clickableElements.size} clickable, ${obs.scrollableContainers.size} scrollable)"
        }
    }

    fun runTestClick(targetText: String = "Test Tap Target") = viewModelScope.launch(Dispatchers.Default) {
        val svc = ZehnAccessibilityService.instance.value
        if (svc == null) {
            val msg = "Accessibility Service DISCONNECTED — cannot click '$targetText'."
            ZehnAccessibilityService.recordExternalAction("TEST_CLICK", msg, msg)
            _toastBanner.value = msg
            return@launch
        }
        val outcome = svc.tapNodeByTextVerified(targetText)
        val msg = when (outcome) {
            is ZehnAccessibilityService.TapOutcome.Success -> outcome.message
            is ZehnAccessibilityService.TapOutcome.Blocked -> outcome.reason
            is ZehnAccessibilityService.TapOutcome.Failed -> outcome.reason
        }
        delay(180)
        _lastScreenObservation.value = svc.observeScreenStructured()
        _toastBanner.value = msg
    }

    fun runTestScrollDown() = viewModelScope.launch(Dispatchers.Default) {
        val svc = ZehnAccessibilityService.instance.value
        if (svc == null) {
            val msg = "Accessibility Service DISCONNECTED — cannot scroll down."
            ZehnAccessibilityService.recordExternalAction("TEST_SCROLL_DOWN", msg, msg)
            _toastBanner.value = msg
            return@launch
        }
        val res = svc.scrollDirection(ScrollDirection.DOWN)
        delay(200)
        _lastScreenObservation.value = svc.observeScreenStructured()
        _toastBanner.value = res.message
    }

    fun runTestScrollUp() = viewModelScope.launch(Dispatchers.Default) {
        val svc = ZehnAccessibilityService.instance.value
        if (svc == null) {
            val msg = "Accessibility Service DISCONNECTED — cannot scroll up."
            ZehnAccessibilityService.recordExternalAction("TEST_SCROLL_UP", msg, msg)
            _toastBanner.value = msg
            return@launch
        }
        val res = svc.scrollDirection(ScrollDirection.UP)
        delay(200)
        _lastScreenObservation.value = svc.observeScreenStructured()
        _toastBanner.value = res.message
    }

    fun runTestSwipeLeft() = viewModelScope.launch(Dispatchers.Default) {
        val svc = ZehnAccessibilityService.instance.value
        if (svc == null) {
            val msg = "Accessibility Service DISCONNECTED — cannot swipe left."
            ZehnAccessibilityService.recordExternalAction("TEST_SWIPE_LEFT", msg, msg)
            _toastBanner.value = msg
            return@launch
        }
        val res = svc.performVerifiedSwipe("left")
        delay(200)
        _lastScreenObservation.value = svc.observeScreenStructured()
        _toastBanner.value = res.message
    }

    fun runTestSwipeRight() = viewModelScope.launch(Dispatchers.Default) {
        val svc = ZehnAccessibilityService.instance.value
        if (svc == null) {
            val msg = "Accessibility Service DISCONNECTED — cannot swipe right."
            ZehnAccessibilityService.recordExternalAction("TEST_SWIPE_RIGHT", msg, msg)
            _toastBanner.value = msg
            return@launch
        }
        val res = svc.performVerifiedSwipe("right")
        delay(200)
        _lastScreenObservation.value = svc.observeScreenStructured()
        _toastBanner.value = res.message
    }

    /**
     * Refreshes an active service's AccessibilityServiceInfo config or returns false if
     * Android OS hasn't bound the service yet (caller should launch Accessibility Settings).
     */
    fun reconnectAccessibilityService(): Boolean {
        val svc = ZehnAccessibilityService.instance.value
        if (svc != null) {
            val res = svc.refreshServiceConfiguration()
            refreshAccessibilityDiagnostics()
            _toastBanner.value = res.message
            return true
        }
        val enabled = ZehnAccessibilityService.isAccessibilityPermissionEnabled(app)
        val msg = if (enabled) {
            "Permission enabled, but OS detached service — toggle Zehn AI OFF/ON in Accessibility Settings."
        } else {
            "Accessibility permission disabled — opening Android Accessibility Settings."
        }
        ZehnAccessibilityService.recordExternalAction("RECONNECT_SERVICE", msg, msg)
        refreshAccessibilityDiagnostics()
        _toastBanner.value = msg
        return false
    }

    fun onMediaProjectionConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.startWithConsent(app, resultCode, data)
            viewModelScope.launch {
                delay(250)
                refreshAccessibilityDiagnostics()
            }
            _toastBanner.value = "MediaProjection screen capture activated"
        } else {
            _screenshotPermissionStatus.value = ScreenCaptureService.getPermissionStatus(app).label
            _toastBanner.value = "MediaProjection permission was denied"
        }
    }

    fun stopMediaProjection() {
        ScreenCaptureService.stop(app)
        refreshAccessibilityDiagnostics()
        _toastBanner.value = "MediaProjection screen capture stopped"
    }

    fun runCaptureAndOcrScreen(visionPrompt: String? = null) = viewModelScope.launch(Dispatchers.IO) {
        val analysis = screenVisionManager.captureAndAnalyzeScreen(
            visionPrompt = visionPrompt,
            useVisionModel = !visionPrompt.isNullOrBlank()
        )
        _lastVisualAnalysis.value = analysis
        withContext(Dispatchers.Main) {
            refreshAccessibilityDiagnostics()
            _toastBanner.value = analysis.error
                ?: "Captured screen (${analysis.method}): ${analysis.ocrElements.size} OCR lines"
        }
    }
}
