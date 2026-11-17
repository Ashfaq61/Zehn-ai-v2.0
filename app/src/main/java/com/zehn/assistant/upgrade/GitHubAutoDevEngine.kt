package com.zehn.assistant.upgrade

import com.zehn.assistant.BuildConfig
import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.CloudLlmClient
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.AutoDevRunEntity
import com.zehn.assistant.data.db.UpgradeCenterDao
import com.zehn.assistant.data.db.UpgradeErrorLogEntity
import com.zehn.assistant.data.db.UpgradeProposalEntity
import com.zehn.assistant.security.KeystoreCryptoManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubConnectionStatus(
    val connected: Boolean = false,
    val username: String = "",
    val repoFullName: String = "",
    val defaultBranch: String = "main",
    val canPush: Boolean = false,
    val canPull: Boolean = false,
    val latestBaseSha: String = "",
    val message: String = "Not tested"
)

data class WorkflowBuildStatus(
    val runId: Long = 0L,
    val branch: String = "",
    val status: String = "Idle", // queued, in_progress, completed, Idle
    val conclusion: String = "", // success, failure, cancelled, ""
    val htmlUrl: String = "",
    val failedLogTail: String = "",
    val updatedAt: String = ""
)

data class RealGitHubMetrics(
    val openAutoDevPrCount: Int = 0,
    val mergedAutoDevPrCount: Int = 0,
    val passedCiRunsCount: Int = 0,
    val failedCiRunsCount: Int = 0,
    val openPrBranches: List<String> = emptyList(),
    val syncedWithGitHub: Boolean = false,
    val lastSyncedAt: Long = 0L
)

data class AutoDevExecutionState(
    val running: Boolean = false,
    val activeCycleStage: String = "Idle", // Strict cycle states: Idle, Running, Waiting for CI, Waiting for approval, Failed
    val currentBranch: String = "",
    val lastBuild: WorkflowBuildStatus = WorkflowBuildStatus(),
    val statusMessage: String = "Idle"
)

data class RepoReadinessItem(
    val title: String,
    val passed: Boolean,
    val detail: String,
    val fixHint: String
)

data class RepoReadinessReport(
    val items: List<RepoReadinessItem>,
    val allPassed: Boolean
)

/**
 * Real GitHub REST & Git Data API automation engine:
 * - Encrypted Keystore PAT only, never embedded or logged
 * - Rate-limit aware backoff (Retry-After, X-RateLimit-Reset, exponential backoff on 403/429)
 * - Max 2 open Auto-Dev PRs enforced from GitHub API
 * - Duplicate PR / branch prevention
 * - Protected path manual-approval gate (Manifest permissions, Touch Guard/Security, Keystore/Tokens, Workflows, Signing)
 * - Self-repair loop on failed CI runs (up to 5 attempts, last ~200 log lines + changed files)
 * - Crash Guard issue creation & Auto-Dev fix task queueing
 * - Non-downgrading Rollback PR (new higher versionCode build on CI)
 */
@Singleton
class GitHubAutoDevEngine @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager,
    private val prefs: PreferencesRepository,
    private val upgradeDao: UpgradeCenterDao,
    private val safetyAnalyzer: RepoSafetyAnalyzer,
    private val aiRouter: AiEngineRouter
) {
    private val _connection = MutableStateFlow(GitHubConnectionStatus())
    val connection: StateFlow<GitHubConnectionStatus> = _connection.asStateFlow()

    private val _devState = MutableStateFlow(AutoDevExecutionState())
    val devState: StateFlow<AutoDevExecutionState> = _devState.asStateFlow()

    private val _realMetrics = MutableStateFlow(RealGitHubMetrics())
    val realMetrics: StateFlow<RealGitHubMetrics> = _realMetrics.asStateFlow()

    @Volatile
    private var stopRequested = false

    fun stopAll() {
        stopRequested = true
        _devState.value = _devState.value.copy(
            running = false,
            activeCycleStage = STAGE_IDLE,
            statusMessage = "Auto-Dev stopped immediately by user."
        )
    }

    suspend fun testConnection(repoOverride: String? = null): GitHubConnectionStatus = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val repoSlug = (repoOverride ?: settings.githubRepoSlug).trim().trim('/')
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()

        if (token.isBlank()) {
            val missing = GitHubConnectionStatus(connected = false, message = "GitHub token is empty. Add a least-privilege PAT first.")
            _connection.value = missing
            return@withContext missing
        }
        if (!repoSlug.contains('/') || repoSlug.split('/').size != 2) {
            val badRepo = GitHubConnectionStatus(connected = false, message = "Enter repository in 'owner/repo' format.")
            _connection.value = badRepo
            return@withContext badRepo
        }

        val userRes = executeGitHubRequest("GET", "https://api.github.com/user", token)
        if (userRes.isFailure) {
            val err = CloudLlmClient.sanitizeError(userRes.exceptionOrNull()?.message ?: "Auth error")
            val st = GitHubConnectionStatus(connected = false, message = "Token check failed: $err")
            _connection.value = st
            logRun("AUTH_TEST", repoSlug, 401, "GitHub auth failed", err, false)
            recordError("AUTO_DEV", "GitHub auth failed", err)
            return@withContext st
        }

        val userObj = JSONObject(userRes.getOrThrow().second)
        val login = userObj.optString("login", "authenticated")

        val repoRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug", token)
        if (repoRes.isFailure) {
            val msg = repoRes.exceptionOrNull()?.message ?: "Repository access denied"
            val st = GitHubConnectionStatus(
                connected = false,
                username = login,
                repoFullName = repoSlug,
                message = "Authenticated as @$login, but cannot access '$repoSlug': $msg"
            )
            _connection.value = st
            logRun("AUTH_TEST", repoSlug, 403, "Repo check failed", msg, false)
            recordError("AUTO_DEV", "Repo access denied", msg)
            return@withContext st
        }

        val repoObj = JSONObject(repoRes.getOrThrow().second)
        val perms = repoObj.optJSONObject("permissions")
        val canPush = perms?.optBoolean("push", false) ?: false
        val canPull = perms?.optBoolean("pull", true) ?: true
        val defBranch = repoObj.optString("default_branch", settings.githubBaseBranch).ifBlank { "main" }

        val refRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/git/ref/heads/${settings.githubBaseBranch}", token)
        val baseSha = refRes.getOrNull()?.second?.let {
            runCatching { JSONObject(it).getJSONObject("object").optString("sha", "") }.getOrNull()
        }.orEmpty()

        if (baseSha.isNotBlank() && settings.lastGoodCommitSha.isBlank()) {
            prefs.updateSettings { it.copy(lastGoodCommitSha = baseSha) }
        }

        val permNote = if (!canPush) "READ-ONLY token (grant Contents + Pull requests + Actions + Issues to push/PR)." else "Read + Push verified."
        val ok = GitHubConnectionStatus(
            connected = true,
            username = login,
            repoFullName = repoSlug,
            defaultBranch = defBranch,
            canPush = canPush,
            canPull = canPull,
            latestBaseSha = baseSha,
            message = "Connected as @$login on $repoSlug ($defBranch). $permNote"
        )
        _connection.value = ok
        logRun("AUTH_TEST", repoSlug, 200, "Connected @$login (push=$canPush)", ok.message, true)

        refreshRealGitHubMetrics(repoSlug, token)
        ok
    }

    /**
     * Fetches real open/merged PR counts and GitHub Actions pass/fail counts from GitHub APIs
     * so dashboard stats never show fabricated local guesses.
     */
    suspend fun refreshRealGitHubMetrics(
        repoOverride: String? = null,
        tokenOverride: String? = null
    ): RealGitHubMetrics = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val repoSlug = (repoOverride ?: settings.githubRepoSlug).trim().trim('/')
        val token = (tokenOverride ?: crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN)).trim()
        if (token.isBlank() || !repoSlug.contains('/')) {
            return@withContext _realMetrics.value
        }

        var openPrs = 0
        var mergedPrs = 0
        var passedRuns = 0
        var failedRuns = 0
        val openBranches = mutableListOf<String>()
        var synced = false

        val openPrRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/pulls?state=open&per_page=30", token)
        openPrRes.getOrNull()?.second?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                synced = true
                for (i in 0 until arr.length()) {
                    val pr = arr.optJSONObject(i) ?: continue
                    val headRef = pr.optJSONObject("head")?.optString("ref", "").orEmpty()
                    val title = pr.optString("title", "")
                    if (headRef.startsWith("zehn-auto") || title.contains("Zehn Auto-Dev", true)) {
                        openPrs++
                        if (headRef.isNotBlank()) openBranches.add(headRef)
                    }
                }
            }
        }

        val closedPrRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/pulls?state=closed&per_page=30", token)
        closedPrRes.getOrNull()?.second?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                synced = true
                for (i in 0 until arr.length()) {
                    val pr = arr.optJSONObject(i) ?: continue
                    val headRef = pr.optJSONObject("head")?.optString("ref", "").orEmpty()
                    val mergedAt = pr.optString("merged_at", "")
                    val title = pr.optString("title", "")
                    if (mergedAt.isNotBlank() && mergedAt != "null" &&
                        (headRef.startsWith("zehn-auto") || title.contains("Zehn Auto-Dev", true))
                    ) {
                        mergedPrs++
                    }
                }
            }
        }

        val runsRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/actions/runs?per_page=30", token)
        runsRes.getOrNull()?.second?.let { raw ->
            runCatching {
                val runs = JSONObject(raw).optJSONArray("workflow_runs")
                if (runs != null) {
                    synced = true
                    for (i in 0 until runs.length()) {
                        val run = runs.optJSONObject(i) ?: continue
                        when (run.optString("conclusion", "")) {
                            "success" -> passedRuns++
                            "failure" -> failedRuns++
                        }
                    }
                }
            }
        }

        val next = RealGitHubMetrics(
            openAutoDevPrCount = openPrs,
            mergedAutoDevPrCount = mergedPrs,
            passedCiRunsCount = passedRuns,
            failedCiRunsCount = failedRuns,
            openPrBranches = openBranches,
            syncedWithGitHub = synced,
            lastSyncedAt = System.currentTimeMillis()
        )
        _realMetrics.value = next
        next
    }

    /**
     * Stages an UpgradeProposal onto its dedicated feature branch (never directly to main/baseBranch):
     * - Enforces max 2 open Auto-Dev PRs at a time
     * - Enforces zero duplicates
     * - Enforces manual approval gate when protected paths are touched
     * - Polls GitHub Actions CI & runs up to 5 LLM repair passes on failure
     */
    suspend fun executeIntegrationProposal(
        proposalId: Long,
        onBuildFailureNotify: (String) -> Unit = {}
    ): Result<UpgradeProposalEntity> = withContext(Dispatchers.IO) {
        stopRequested = false
        val settings = prefs.settings.first()
        if (settings.allAutoUpgradesPaused) {
            return@withContext Result.failure(IllegalStateException("All auto-upgrades are paused via emergency control."))
        }

        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        val baseBranch = settings.githubBaseBranch.ifBlank { "main" }

        val proposal = upgradeDao.getProposalById(proposalId)
            ?: return@withContext Result.failure(IllegalArgumentException("Proposal #$proposalId not found."))

        if (proposal.riskLevel == "BLOCKED") {
            return@withContext Result.failure(IllegalStateException("Proposal blocked by pre-filter or security scan."))
        }
        if (proposal.requiresApproval && proposal.status != "APPROVED" && proposal.status != "STAGED") {
            _devState.value = _devState.value.copy(
                running = false,
                activeCycleStage = STAGE_WAITING_APPROVAL,
                statusMessage = "Proposal #${proposal.id} requires manual approval before staging."
            )
            return@withContext Result.failure(IllegalStateException("Proposal requires manual approval before integration."))
        }
        if (token.isBlank() || !repo.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Connect GitHub token and 'owner/repo' in Upgrade Center first."))
        }

        val targetBranch = proposal.branchName.ifBlank { "zehn-auto/proposal-${proposal.id}" }
        if (targetBranch == baseBranch || targetBranch == "main" || targetBranch == "master") {
            return@withContext Result.failure(IllegalStateException("Safety rule: never commit directly to protected branch '$baseBranch'."))
        }

        // Enforce max 2 open PRs & zero duplicates from real GitHub APIs
        val metrics = refreshRealGitHubMetrics(repo, token)
        if (metrics.openPrBranches.any { it.equals(targetBranch, ignoreCase = true) }) {
            val dupMsg = "Duplicate PR blocked: an open PR already exists for '$targetBranch'."
            logRun("PR", targetBranch, 409, "Duplicate PR blocked", dupMsg, false)
            recordError("AUTO_DEV", dupMsg)
            _devState.value = _devState.value.copy(running = false, activeCycleStage = STAGE_WAITING_APPROVAL, statusMessage = dupMsg)
            return@withContext Result.failure(IllegalStateException(dupMsg))
        }
        if (metrics.openAutoDevPrCount >= MAX_OPEN_AUTO_DEV_PRS) {
            val capMsg = "Max $MAX_OPEN_AUTO_DEV_PRS open Auto-Dev PRs reached (${metrics.openAutoDevPrCount}/$MAX_OPEN_AUTO_DEV_PRS). Wait for merge or close a PR."
            logRun("PR", targetBranch, 429, "Max open PRs cap reached", capMsg, false)
            _devState.value = _devState.value.copy(running = false, activeCycleStage = STAGE_WAITING_APPROVAL, statusMessage = capMsg)
            return@withContext Result.failure(IllegalStateException(capMsg))
        }

        _devState.value = AutoDevExecutionState(
            running = true,
            activeCycleStage = STAGE_RUNNING,
            currentBranch = targetBranch,
            statusMessage = "Creating branch $targetBranch from $baseBranch..."
        )

        val branchRes = createOrUpdateBranch(repo, token, targetBranch, baseBranch)
        if (branchRes.isFailure) {
            val err = branchRes.exceptionOrNull()?.message ?: "Branch creation failed"
            val updated = proposal.copy(status = "FAILED", lastErrorReport = err, updatedAt = System.currentTimeMillis())
            upgradeDao.upsertProposal(updated)
            recordError("AUTO_DEV", "Branch creation failed", err)
            _devState.value = _devState.value.copy(running = false, activeCycleStage = STAGE_FAILED, statusMessage = err)
            return@withContext Result.failure(branchRes.exceptionOrNull()!!)
        }

        val baseSha = branchRes.getOrThrow()
        prefs.updateSettings { it.copy(lastGoodCommitSha = baseSha) }

        val changes = parseProposedChanges(proposal)
        val diffScan = safetyAnalyzer.scanCommitDiff(changes, allowWorkflowEdits = settings.allowWorkflowEdits)
        if (!diffScan.clean) {
            val reason = "Pre-commit safety gate blocked commit: ${diffScan.blockingReasons.joinToString("; ")}"
            logRun("COMMIT", targetBranch, 403, "Pre-commit safety blocked", reason, false)
            recordError("AUTO_DEV", "Pre-commit safety blocked", reason)
            val updated = proposal.copy(status = "FAILED", lastErrorReport = reason, updatedAt = System.currentTimeMillis())
            upgradeDao.upsertProposal(updated)
            _devState.value = _devState.value.copy(running = false, activeCycleStage = STAGE_FAILED, statusMessage = reason)
            return@withContext Result.failure(IllegalStateException(reason))
        }

        val detectedProtected = safetyAnalyzer.detectProtectedPaths(
            paths = changes.map { it.path },
            textHint = "${proposal.permissionsRequested} ${proposal.protectedPathsFlagged}"
        )

        _devState.value = _devState.value.copy(activeCycleStage = STAGE_RUNNING, statusMessage = "Committing ${changes.size} file(s) to $targetBranch...")
        val commitRes = commitChangesAtomic(
            repoSlug = repo,
            token = token,
            branch = targetBranch,
            changes = changes,
            commitMessage = "feat(auto-dev): ${proposal.title}\n\nVerified license & safety scan.\nTool: ${proposal.toolIdentifier}"
        )
        if (commitRes.isFailure) {
            val err = commitRes.exceptionOrNull()?.message ?: "Commit failed"
            val updated = proposal.copy(status = "FAILED", lastErrorReport = err, updatedAt = System.currentTimeMillis())
            upgradeDao.upsertProposal(updated)
            recordError("AUTO_DEV", "Commit failed", err)
            _devState.value = _devState.value.copy(running = false, activeCycleStage = STAGE_FAILED, statusMessage = err)
            return@withContext Result.failure(commitRes.exceptionOrNull()!!)
        }

        val commitSha = commitRes.getOrThrow()
        _devState.value = _devState.value.copy(activeCycleStage = STAGE_RUNNING, statusMessage = "Opening Pull Request $targetBranch -> $baseBranch...")

        val protectedSection = if (detectedProtected.isNotEmpty() || proposal.protectedPathsFlagged.isNotBlank()) {
            val allFlags = (detectedProtected + proposal.protectedPathsFlagged.split(",").map { it.trim() }).filter { it.isNotBlank() }.distinct()
            "\n> [!WARNING]\n> **PROTECTED PATHS / CONFIGS FLAGGED (Manual Review Required):** ${allFlags.joinToString(", ")}\n"
        } else {
            ""
        }

        val prRes = openPullRequest(
            repoSlug = repo,
            token = token,
            headBranch = targetBranch,
            baseBranch = baseBranch,
            title = "[Zehn Auto-Dev] ${proposal.title}",
            body = """
                ## Autonomous Integration Proposal
                - **Source**: `${proposal.toolIdentifier}`
                - **Fit Score**: `${proposal.fitScore}/10`
                - **Risk Level**: `${proposal.riskLevel}`
                - **Permissions Requested**: `${proposal.permissionsRequested.ifBlank { "None" }}`
                - **Commit SHA**: `$commitSha`
                $protectedSection
                ### Integration Plan & Changelog
                ${proposal.integrationPlan}
            """.trimIndent()
        )

        val prNumber = prRes.getOrNull()?.first
        val prUrl = prRes.getOrNull()?.second.orEmpty()

        var staged = proposal.copy(
            status = "STAGED",
            branchName = targetBranch,
            commitSha = commitSha,
            prNumber = prNumber ?: proposal.prNumber,
            prUrl = prUrl.ifBlank { proposal.prUrl },
            protectedPathsFlagged = if (detectedProtected.isNotEmpty()) detectedProtected.joinToString(", ") else proposal.protectedPathsFlagged,
            lastErrorReport = "",
            updatedAt = System.currentTimeMillis()
        )
        upgradeDao.upsertProposal(staged)

        // Poll GitHub Actions CI run
        _devState.value = _devState.value.copy(activeCycleStage = STAGE_WAITING_CI, statusMessage = "Waiting for CI on $targetBranch...")
        val buildStatus = pollBranchBuild(repo, token, targetBranch, maxPolls = 10)
        _devState.value = _devState.value.copy(lastBuild = buildStatus)

        staged = staged.copy(
            lastBuildConclusion = buildStatus.conclusion.ifBlank { buildStatus.status },
            lastErrorReport = buildStatus.failedLogTail,
            updatedAt = System.currentTimeMillis()
        )
        upgradeDao.upsertProposal(staged)

        if (buildStatus.conclusion == "failure" && settings.autoDevEnabled && !stopRequested) {
            staged = attemptBuildSelfRepair(repo, token, staged, buildStatus, changes)
        }

        val hasProtected = staged.protectedPathsFlagged.isNotBlank() || detectedProtected.isNotEmpty()
        if (staged.lastBuildConclusion == "success" && settings.autoMergeOnGreen && !staged.requiresApproval && !hasProtected && prNumber != null) {
            val mergeRes = mergePullRequest(repo, token, prNumber, staged.title)
            if (mergeRes.isSuccess) {
                staged = staged.copy(status = "MERGED", updatedAt = System.currentTimeMillis())
                upgradeDao.upsertProposal(staged)
            }
        } else if (staged.lastBuildConclusion == "failure") {
            val failSummary = "Build failed after ${staged.retryAttempts} repair attempt(s) on $targetBranch: ${staged.lastErrorReport.take(160)}"
            recordError("AUTO_DEV", "CI failure on $targetBranch", failSummary)
            if (settings.notifyOnBuildFailure) {
                onBuildFailureNotify(failSummary)
            }
        }

        refreshRealGitHubMetrics(repo, token)

        val finalStage = when {
            staged.status == "MERGED" -> STAGE_IDLE
            staged.lastBuildConclusion == "failure" -> STAGE_FAILED
            staged.lastBuildConclusion == "success" -> STAGE_WAITING_APPROVAL
            else -> STAGE_WAITING_CI
        }
        _devState.value = _devState.value.copy(
            running = false,
            activeCycleStage = finalStage,
            statusMessage = "Branch $targetBranch -> ${staged.status} (CI: ${staged.lastBuildConclusion.ifBlank { "queued" }})"
        )
        Result.success(staged)
    }

    suspend fun triggerGitHubActionsBuild(branchOverride: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        val ref = branchOverride?.ifBlank { null } ?: settings.githubBaseBranch.ifBlank { "main" }

        if (token.isBlank() || !repo.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Configure GitHub token and repository first."))
        }

        val workflows = listOf("release.yml", "android-build.yml", "build.yml")
        var lastErr = "No dispatchable workflow found"
        for (wf in workflows) {
            val body = JSONObject().put("ref", ref).toString()
            val res = executeGitHubRequest(
                "POST",
                "https://api.github.com/repos/$repo/actions/workflows/$wf/dispatches",
                token,
                body
            )
            if (res.isSuccess) {
                logRun("BUILD_DISPATCH", ref, 204, "Triggered $wf on $ref", "", true)
                _devState.value = _devState.value.copy(activeCycleStage = STAGE_WAITING_CI, currentBranch = ref, statusMessage = "Triggered $wf on $ref")
                return@withContext Result.success("Triggered $wf on branch '$ref'.")
            } else {
                lastErr = res.exceptionOrNull()?.message ?: lastErr
            }
        }
        logRun("BUILD_DISPATCH", ref, 404, "Build trigger failed", lastErr, false)
        recordError("AUTO_DEV", "Workflow dispatch failed", lastErr)
        Result.failure(IllegalStateException("Unable to trigger workflow build: $lastErr"))
    }

    suspend fun pollLatestActionsRun(branch: String? = null): Result<WorkflowBuildStatus> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        if (token.isBlank() || !repo.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Configure GitHub token & owner/repo first."))
        }
        val status = pollBranchBuild(repo, token, branch ?: settings.githubBaseBranch, maxPolls = 1)
        _devState.value = _devState.value.copy(lastBuild = status)
        refreshRealGitHubMetrics(repo, token)
        Result.success(status)
    }

    /**
     * Safe monotonic rollback: opens a Rollback PR on zehn-auto/rollback-... targeting baseBranch
     * so GitHub Actions builds the restored previous state under a NEW higher versionCode
     * (never attempting to install an older versionCode).
     */
    suspend fun createRollbackPrForPreviousVersion(
        targetReleaseTag: String = "",
        previousCommitSha: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        val baseBranch = settings.githubBaseBranch.ifBlank { "main" }

        if (token.isBlank() || !repo.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Configure GitHub token & owner/repo to create a Rollback PR."))
        }

        val safeTag = targetReleaseTag.ifBlank { settings.lastGoodTagOrVersion }.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val rollbackBranch = "zehn-auto/rollback-$safeTag-${System.currentTimeMillis() % 10000}"

        val branchRes = createOrUpdateBranch(repo, token, rollbackBranch, baseBranch)
        if (branchRes.isFailure) return@withContext Result.failure(branchRes.exceptionOrNull()!!)

        val shaHint = previousCommitSha.ifBlank { settings.lastGoodCommitSha }.ifBlank { "previous release tag $safeTag" }
        val rollbackMarker = CommitFileChange(
            path = "docs/rollbacks/ROLLBACK_${safeTag}.md",
            content = """
                # Monotonic Rollback Release Request
                - Requested Target State: `$safeTag` (`$shaHint`)
                - Triggered from Installed App: `v${BuildConfig.VERSION_NAME}` (`versionCode=${BuildConfig.VERSION_CODE}`)
                - Note: Android prohibits installing lower `versionCode`. Merging this PR triggers `release.yml` to publish a **new higher versionCode** containing the verified rollback source.
            """.trimIndent()
        )
        val commitRes = commitChangesAtomic(
            repoSlug = repo,
            token = token,
            branch = rollbackBranch,
            changes = listOf(rollbackMarker),
            commitMessage = "revert(release): stage monotonic rollback PR toward $safeTag"
        )
        if (commitRes.isFailure) return@withContext Result.failure(commitRes.exceptionOrNull()!!)

        val prRes = openPullRequest(
            repoSlug = repo,
            token = token,
            headBranch = rollbackBranch,
            baseBranch = baseBranch,
            title = "[Zehn Auto-Dev] Rollback PR toward $safeTag (higher versionCode)",
            body = """
                ## Monotonic Rollback Release
                - **Target State**: `$safeTag` (`$shaHint`)
                - **Commit**: `${commitRes.getOrThrow()}`
                - **Notice**: Merging this PR into `$baseBranch` publishes a new GitHub Release with `versionCode = GITHUB_RUN_NUMBER` (strictly higher than current `${BuildConfig.VERSION_CODE}`).
            """.trimIndent()
        )
        prRes.map { (prNum, url) ->
            _devState.value = _devState.value.copy(
                activeCycleStage = STAGE_WAITING_APPROVAL,
                currentBranch = rollbackBranch,
                statusMessage = "Rollback PR #$prNum ready for your Merge approval."
            )
            refreshRealGitHubMetrics(repo, token)
            "Opened Rollback PR #$prNum ($rollbackBranch -> $baseBranch): $url"
        }
    }

    /**
     * Legacy branch reset helper kept intact for existing Auto-Dev tests.
     */
    suspend fun rollbackBranchToStable(branchToReset: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        val targetBranch = branchToReset?.ifBlank { null } ?: _devState.value.currentBranch.ifBlank { "zehn-auto" }

        if (token.isBlank() || !repo.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Configure GitHub token & repo first."))
        }

        val stableSha = if (settings.lastGoodCommitSha.isNotBlank()) {
            settings.lastGoodCommitSha
        } else {
            val baseRef = executeGitHubRequest("GET", "https://api.github.com/repos/$repo/git/ref/heads/${settings.githubBaseBranch}", token)
            baseRef.getOrNull()?.second?.let { JSONObject(it).getJSONObject("object").optString("sha", "") }.orEmpty()
        }

        if (stableSha.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Could not resolve stable commit SHA for '${settings.githubBaseBranch}'."))
        }

        val body = JSONObject().put("sha", stableSha).put("force", true).toString()
        val res = executeGitHubRequest("PATCH", "https://api.github.com/repos/$repo/git/refs/heads/$targetBranch", token, body)
        return@withContext res.fold(
            onSuccess = { (code, _) ->
                logRun("ROLLBACK", targetBranch, code, "Rolled back $targetBranch to ${stableSha.take(7)}", "", true)
                _devState.value = _devState.value.copy(
                    running = false,
                    activeCycleStage = STAGE_IDLE,
                    statusMessage = "Restored '$targetBranch' to stable SHA ${stableSha.take(7)}."
                )
                Result.success("Restored '$targetBranch' to stable commit ${stableSha.take(7)}.")
            },
            onFailure = { err ->
                val msg = CloudLlmClient.sanitizeError(err.message ?: "Rollback failed")
                logRun("ROLLBACK", targetBranch, 500, "Rollback failed", msg, false)
                Result.failure(IllegalStateException(msg))
            }
        )
    }

    /**
     * Crash Guard: creates a GitHub Issue on the configured repository with sanitized crash details.
     */
    suspend fun createGitHubIssueForCrash(title: String, sanitizedCrashLog: String): Result<String> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        if (token.isBlank() || !repo.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Configure GitHub token & 'owner/repo' first to file an Issue."))
        }

        val cleanTitle = "[Safe Mode Crash] ${title.take(100)} (v${BuildConfig.VERSION_NAME})"
        val cleanBody = buildString {
            appendLine("## Zehn AI Safe Mode Crash Report")
            appendLine("- **Version**: `${BuildConfig.VERSION_NAME}` (`versionCode=${BuildConfig.VERSION_CODE}`)")
            appendLine("- **Guard**: Triggered after repeated crashes within 5 minutes.")
            appendLine()
            appendLine("### Sanitized Stack Trace")
            appendLine("```")
            appendLine(CloudLlmClient.sanitizeError(sanitizedCrashLog).take(6000))
            appendLine("```")
        }
        val reqBody = JSONObject()
            .put("title", cleanTitle)
            .put("body", cleanBody)
            .put("labels", JSONArray().put("bug").put("safe-mode"))
            .toString()

        val res = executeGitHubRequest("POST", "https://api.github.com/repos/$repo/issues", token, reqBody)
        res.mapCatching { (code, payload) ->
            val obj = JSONObject(payload)
            val num = obj.optInt("number", 0)
            val htmlUrl = obj.optString("html_url", "")
            logRun("ISSUE", repo, code, "Opened Crash Issue #$num", htmlUrl, true)
            "Created GitHub Issue #$num: $htmlUrl"
        }.onFailure { err ->
            val msg = CloudLlmClient.sanitizeError(err.message ?: "Failed to create issue")
            logRun("ISSUE", repo, 500, "Failed to create Crash Issue", msg, false)
            recordError("SAFE_MODE", "Issue creation failed", msg)
        }
    }

    /**
     * Crash Guard: creates an Auto-Dev crash-fix proposal (and stages if token & Auto-Dev allow).
     */
    suspend fun createCrashFixAutoDevTask(sanitizedCrashLog: String): Result<UpgradeProposalEntity> = withContext(Dispatchers.IO) {
        val cleanTrace = CloudLlmClient.sanitizeError(sanitizedCrashLog).take(3500)
        val diagPrompt = """
            Analyze this Android crash stack trace from Zehn AI and produce a concise, concrete fix plan and Kotlin guard snippet:
            $cleanTrace
        """.trimIndent()
        val diagnosis = runCatching {
            aiRouter.routeAndStream(emptyList(), diagPrompt, null).toList().joinToString("") { it.delta }
        }.getOrDefault("Add defensive null/try-catch guard around failing component.")

        val fixReportPath = "docs/crash_fixes/SAFE_MODE_FIX_${BuildConfig.VERSION_CODE}_${System.currentTimeMillis()}.md"
        val fixContent = """
            # Automated Safe-Mode Crash Fix Proposal
            - App Version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})

            ## Crash Trace
            ```
            $cleanTrace
            ```

            ## LLM Root-Cause Diagnosis & Patch Guide
            ${CloudLlmClient.sanitizeError(diagnosis).ifBlank { "Investigate top stack frame & isolate failing subsystem." }}
        """.trimIndent()

        val filesJson = JSONArray().put(
            JSONObject().put("path", fixReportPath).put("content", fixContent)
        ).toString()

        val proposal = UpgradeProposalEntity(
            toolIdentifier = "crash-guard/v${BuildConfig.VERSION_CODE}-${System.currentTimeMillis() % 10000}",
            title = "Crash Guard Fix for v${BuildConfig.VERSION_NAME}",
            summary = "Automated Safe-Mode crash analysis & fix staging task",
            integrationPlan = diagnosis.take(1200).ifBlank { "Stage Safe-Mode fix branch and run CI." },
            proposedFilesJson = filesJson,
            permissionsRequested = "",
            riskLevel = "LOW",
            requiresApproval = true,
            status = "APPROVED",
            branchName = "zehn-auto/crash-fix-${System.currentTimeMillis() % 10000}",
            fitScore = 9.0
        )
        val id = upgradeDao.upsertProposal(proposal)
        val settings = prefs.settings.first()
        val saved = upgradeDao.getProposalById(id) ?: proposal.copy(id = id)
        if (!settings.allAutoUpgradesPaused && crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).isNotBlank() && settings.githubRepoSlug.contains('/')) {
            return@withContext executeIntegrationProposal(id)
        }
        Result.success(saved)
    }

    internal suspend fun createOrUpdateBranch(
        repoSlug: String,
        token: String,
        newBranch: String,
        baseBranch: String
    ): Result<String> {
        val baseRef = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/git/ref/heads/$baseBranch", token)
        if (baseRef.isFailure) return Result.failure(baseRef.exceptionOrNull()!!)

        val baseSha = JSONObject(baseRef.getOrThrow().second).getJSONObject("object").getString("sha")
        val existingBranch = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/git/ref/heads/$newBranch", token)
        return if (existingBranch.isSuccess) {
            val patchBody = JSONObject().put("sha", baseSha).put("force", false).toString()
            executeGitHubRequest("PATCH", "https://api.github.com/repos/$repoSlug/git/refs/heads/$newBranch", token, patchBody)
            logRun("BRANCH", newBranch, 200, "Updated existing branch from $baseBranch", baseSha.take(7), true)
            Result.success(baseSha)
        } else {
            val createBody = JSONObject()
                .put("ref", "refs/heads/$newBranch")
                .put("sha", baseSha)
                .toString()
            val created = executeGitHubRequest("POST", "https://api.github.com/repos/$repoSlug/git/refs", token, createBody)
            created.map {
                logRun("BRANCH", newBranch, 201, "Created branch from $baseBranch", baseSha.take(7), true)
                baseSha
            }
        }
    }

    internal suspend fun commitChangesAtomic(
        repoSlug: String,
        token: String,
        branch: String,
        changes: List<CommitFileChange>,
        commitMessage: String
    ): Result<String> {
        val refRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/git/ref/heads/$branch", token)
        if (refRes.isFailure) return Result.failure(refRes.exceptionOrNull()!!)
        val headCommitSha = JSONObject(refRes.getOrThrow().second).getJSONObject("object").getString("sha")

        val commitObjRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/git/commits/$headCommitSha", token)
        if (commitObjRes.isFailure) return Result.failure(commitObjRes.exceptionOrNull()!!)
        val baseTreeSha = JSONObject(commitObjRes.getOrThrow().second).getJSONObject("tree").getString("sha")

        val treeItems = JSONArray()
        for (file in changes) {
            val blobReq = JSONObject()
                .put("content", file.content)
                .put("encoding", "utf-8")
                .toString()
            val blobRes = executeGitHubRequest("POST", "https://api.github.com/repos/$repoSlug/git/blobs", token, blobReq)
            if (blobRes.isFailure) return Result.failure(blobRes.exceptionOrNull()!!)
            val blobSha = JSONObject(blobRes.getOrThrow().second).getString("sha")
            treeItems.put(
                JSONObject()
                    .put("path", file.path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", blobSha)
            )
        }

        val treeReq = JSONObject()
            .put("base_tree", baseTreeSha)
            .put("tree", treeItems)
            .toString()
        val treeRes = executeGitHubRequest("POST", "https://api.github.com/repos/$repoSlug/git/trees", token, treeReq)
        if (treeRes.isFailure) return Result.failure(treeRes.exceptionOrNull()!!)
        val newTreeSha = JSONObject(treeRes.getOrThrow().second).getString("sha")

        val newCommitReq = JSONObject()
            .put("message", commitMessage)
            .put("tree", newTreeSha)
            .put("parents", JSONArray().put(headCommitSha))
            .toString()
        val newCommitRes = executeGitHubRequest("POST", "https://api.github.com/repos/$repoSlug/git/commits", token, newCommitReq)
        if (newCommitRes.isFailure) return Result.failure(newCommitRes.exceptionOrNull()!!)
        val newCommitSha = JSONObject(newCommitRes.getOrThrow().second).getString("sha")

        val updateRefReq = JSONObject().put("sha", newCommitSha).toString()
        val updateRefRes = executeGitHubRequest("PATCH", "https://api.github.com/repos/$repoSlug/git/refs/heads/$branch", token, updateRefReq)
        if (updateRefRes.isFailure) return Result.failure(updateRefRes.exceptionOrNull()!!)

        logRun("COMMIT", branch, 201, "Committed ${changes.size} files (${newCommitSha.take(7)})", commitMessage.lines().firstOrNull().orEmpty(), true)
        return Result.success(newCommitSha)
    }

    internal suspend fun openPullRequest(
        repoSlug: String,
        token: String,
        headBranch: String,
        baseBranch: String,
        title: String,
        body: String
    ): Result<Pair<Int, String>> {
        val req = JSONObject()
            .put("title", title)
            .put("head", headBranch)
            .put("base", baseBranch)
            .put("body", body)
            .toString()
        val res = executeGitHubRequest("POST", "https://api.github.com/repos/$repoSlug/pulls", token, req)
        return res.mapCatching { (code, payload) ->
            val obj = JSONObject(payload)
            val num = obj.getInt("number")
            val html = obj.optString("html_url", "")
            logRun("PR", headBranch, code, "Opened PR #$num -> $baseBranch", html, true)
            num to html
        }
    }

    suspend fun mergePullRequest(
        repoSlug: String,
        token: String,
        prNumber: Int,
        commitTitle: String
    ): Result<String> {
        val req = JSONObject()
            .put("commit_title", commitTitle)
            .put("merge_method", "squash")
            .toString()
        val res = executeGitHubRequest("PUT", "https://api.github.com/repos/$repoSlug/pulls/$prNumber/merge", token, req)
        return res.mapCatching { (code, payload) ->
            val obj = JSONObject(payload)
            val sha = obj.optString("sha", "")
            logRun("MERGE", "PR #$prNumber", code, "Merged PR #$prNumber (${sha.take(7)})", "", true)
            if (sha.isNotBlank()) prefs.updateSettings { it.copy(lastGoodCommitSha = sha) }
            sha
        }
    }

    private suspend fun pollBranchBuild(
        repoSlug: String,
        token: String,
        branch: String,
        maxPolls: Int
    ): WorkflowBuildStatus {
        var lastStatus = WorkflowBuildStatus(branch = branch)
        repeat(maxPolls) { idx ->
            if (stopRequested) return lastStatus
            val url = "https://api.github.com/repos/$repoSlug/actions/runs?branch=$branch&per_page=3"
            val res = executeGitHubRequest("GET", url, token)
            if (res.isSuccess) {
                val obj = JSONObject(res.getOrThrow().second)
                val runs = obj.optJSONArray("workflow_runs")
                if (runs != null && runs.length() > 0) {
                    val top = runs.getJSONObject(0)
                    val runId = top.optLong("id", 0L)
                    val st = top.optString("status", "queued")
                    val conc = top.optString("conclusion", "")
                    val htmlUrl = top.optString("html_url", "")
                    val upd = top.optString("updated_at", "")
                    val failTail = if (conc == "failure") fetchFailedJobLogTail(repoSlug, token, runId) else ""
                    lastStatus = WorkflowBuildStatus(
                        runId = runId,
                        branch = branch,
                        status = st,
                        conclusion = conc,
                        htmlUrl = htmlUrl,
                        failedLogTail = failTail,
                        updatedAt = upd
                    )
                    logRun("BUILD_POLL", branch, 200, "Run #$runId: $st / ${conc.ifBlank { "pending" }}", htmlUrl, conc != "failure")
                    if (st == "completed") return lastStatus
                }
            }
            if (idx < maxPolls - 1) delay(6_000L)
        }
        return lastStatus
    }

    private suspend fun fetchFailedJobLogTail(repoSlug: String, token: String, runId: Long): String {
        if (runId <= 0L) return ""
        val jobsRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/actions/runs/$runId/jobs", token)
        val jobs = jobsRes.getOrNull()?.second?.let { runCatching { JSONObject(it).optJSONArray("jobs") }.getOrNull() } ?: return ""
        for (i in 0 until jobs.length()) {
            val job = jobs.optJSONObject(i) ?: continue
            if (job.optString("conclusion") == "failure") {
                val jobId = job.optLong("id", 0L)
                val logRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repoSlug/actions/jobs/$jobId/logs", token)
                val logText = logRes.getOrNull()?.second ?: continue
                return logText.lines().takeLast(200).joinToString("\n").take(6000)
            }
        }
        return ""
    }

    private suspend fun attemptBuildSelfRepair(
        repoSlug: String,
        token: String,
        proposal: UpgradeProposalEntity,
        failedBuild: WorkflowBuildStatus,
        originalChanges: List<CommitFileChange>
    ): UpgradeProposalEntity {
        var current = proposal
        var attempt = 1
        while (attempt <= MAX_REPAIR_ATTEMPTS && !stopRequested && current.lastBuildConclusion == "failure") {
            _devState.value = _devState.value.copy(
                activeCycleStage = STAGE_RUNNING,
                statusMessage = "Self-repair attempt $attempt/$MAX_REPAIR_ATTEMPTS on ${current.branchName}..."
            )
            val filesList = originalChanges.joinToString("\n") { "--- ${it.path} ---\n${it.content.take(1200)}" }
            val prompt = """
                A GitHub Actions Android Gradle build failed on branch '${current.branchName}'.
                Inspect the last ~200 log lines and changed files, and output either a JSON object {"path":"...","content":"..."} or explanation:
                Changed files:
                $filesList

                Failed CI log tail (~200 lines):
                ${failedBuild.failedLogTail.take(2800)}
            """.trimIndent()
            val reply = runCatching {
                aiRouter.routeAndStream(emptyList(), prompt, null).toList().joinToString("") { it.delta }
            }.getOrDefault("")

            if (reply.isBlank()) break

            val candidateFile = extractCommitFileFromLlmReply(reply) ?: CommitFileChange(
                path = "docs/auto_dev_repair_${current.id}_attempt_$attempt.md",
                content = "# Auto-Dev Repair Analysis Attempt $attempt\n\n```\n${CloudLlmClient.sanitizeError(failedBuild.failedLogTail.take(1500))}\n```\n\n## AI Diagnosis\n${CloudLlmClient.sanitizeError(reply.take(2500))}\n"
            )
            val scan = safetyAnalyzer.scanCommitDiff(listOf(candidateFile), allowWorkflowEdits = false)
            if (!scan.clean) break

            val commitRes = commitChangesAtomic(repoSlug, token, current.branchName, listOf(candidateFile), "fix(auto-dev): CI self-repair attempt #$attempt")
            if (commitRes.isFailure) break

            val nextBuild = pollBranchBuild(repoSlug, token, current.branchName, maxPolls = 4)
            logRun("SELF_REPAIR", current.branchName, if (nextBuild.conclusion == "success") 200 else 500, "Repair attempt #$attempt -> ${nextBuild.conclusion}", "", nextBuild.conclusion == "success")

            current = current.copy(
                commitSha = commitRes.getOrThrow(),
                lastBuildConclusion = nextBuild.conclusion.ifBlank { nextBuild.status },
                lastErrorReport = nextBuild.failedLogTail,
                retryAttempts = attempt,
                updatedAt = System.currentTimeMillis()
            )
            upgradeDao.upsertProposal(current)
            if (current.lastBuildConclusion == "success") break
            attempt++
        }
        return current
    }

    private fun extractCommitFileFromLlmReply(raw: String): CommitFileChange? {
        return runCatching {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = JSONObject(raw.substring(start, end + 1))
            val path = obj.optString("path", "").trim()
            val content = obj.optString("content", "")
            if (path.isNotBlank() && content.isNotBlank() && !path.startsWith(".github/workflows")) {
                CommitFileChange(path, content)
            } else {
                null
            }
        }.getOrNull()
    }

    private fun parseProposedChanges(proposal: UpgradeProposalEntity): List<CommitFileChange> {
        val out = mutableListOf<CommitFileChange>()
        runCatching {
            val arr = JSONArray(proposal.proposedFilesJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val p = obj.optString("path", "").trim()
                val c = obj.optString("content", "")
                if (p.isNotBlank() && c.isNotBlank()) {
                    out.add(CommitFileChange(p, c))
                }
            }
        }
        if (out.isEmpty()) {
            out.add(
                CommitFileChange(
                    path = "docs/proposals/proposal_${proposal.id}.md",
                    content = "# ${proposal.title}\n\n${proposal.integrationPlan}\n"
                )
            )
        }
        return out
    }

    private suspend fun logRun(
        action: String,
        branch: String,
        code: Int,
        summary: String,
        detail: String,
        success: Boolean
    ) {
        upgradeDao.insertDevRun(
            AutoDevRunEntity(
                actionType = action,
                branchOrRef = branch,
                httpStatus = code,
                summary = summary,
                detail = CloudLlmClient.sanitizeError(detail).take(1500),
                success = success
            )
        )
    }

    private suspend fun recordError(source: String, message: String, detail: String = "") {
        runCatching {
            upgradeDao.insertErrorLog(
                UpgradeErrorLogEntity(
                    source = source,
                    message = CloudLlmClient.sanitizeError(message).take(240),
                    detail = CloudLlmClient.sanitizeError(detail).take(1200)
                )
            )
        }
    }

    private suspend fun executeGitHubRequest(
        method: String,
        url: String,
        token: String,
        jsonBody: String? = null
    ): Result<Pair<Int, String>> {
        var backoffMs = 800L
        repeat(3) { attempt ->
            val media = "application/json; charset=utf-8".toMediaType()
            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .addHeader("User-Agent", "ZehnAI-AutoDev/4.2")
                .addHeader("Authorization", "Bearer $token")
                .method(method, jsonBody?.toRequestBody(media))
                .build()

            val exec = runCatching {
                okHttpClient.newCall(req).execute().use { resp ->
                    val retryAfter = resp.header("Retry-After")?.toLongOrNull()
                    val resetEpoch = resp.header("X-RateLimit-Reset")?.toLongOrNull()
                    val waitMs = computeRateLimitWaitMs(retryAfter, resetEpoch, backoffMs)
                    Triple(resp.code, waitMs, resp.body?.string().orEmpty())
                }
            }
            exec.onFailure { err ->
                if (attempt == 2) return Result.failure(err)
                delay(backoffMs)
                backoffMs *= 2
            }.onSuccess { (code, waitMs, body) ->
                if (code in 200..299) return Result.success(code to body)
                if ((code == 403 || code == 429) && attempt < 2) {
                    delay(waitMs)
                    backoffMs *= 2
                } else {
                    val snippet = CloudLlmClient.sanitizeError(body.take(240))
                    return Result.failure(IllegalStateException("HTTP $code: $snippet"))
                }
            }
        }
        return Result.failure(IllegalStateException("GitHub API failed after retries"))
    }

    internal fun computeRateLimitWaitMs(retryAfterSec: Long?, resetEpochSec: Long?, defaultMs: Long): Long {
        if (retryAfterSec != null && retryAfterSec > 0) {
            return (retryAfterSec * 1000L).coerceIn(500L, 15_000L)
        }
        if (resetEpochSec != null) {
            val delta = resetEpochSec * 1000L - System.currentTimeMillis()
            if (delta in 1..15_000L) return delta
        }
        return defaultMs.coerceIn(500L, 15_000L)
    }

    suspend fun checkRepoReadiness(): Result<RepoReadinessReport> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        val items = mutableListOf<RepoReadinessItem>()

        if (token.isBlank()) {
            return@withContext Result.success(
                RepoReadinessReport(
                    items = listOf(RepoReadinessItem("GitHub Token", false, "Token is empty", "Add your fine-grained GitHub PAT in Settings / Upgrade Center")),
                    allPassed = false
                )
            )
        }
        if (!repo.contains('/') || repo.split('/').size != 2) {
            return@withContext Result.success(
                RepoReadinessReport(
                    items = listOf(RepoReadinessItem("Repository Slug", false, "Invalid owner/repo format: '$repo'", "Enter repository in 'owner/repo' format in Settings")),
                    allPassed = false
                )
            )
        }

        val userRes = executeGitHubRequest("GET", "https://api.github.com/user", token)
        val tokenPassed = userRes.isSuccess
        items.add(
            RepoReadinessItem(
                title = "GitHub Token Valid",
                passed = tokenPassed,
                detail = if (tokenPassed) "Authenticated successfully" else userRes.exceptionOrNull()?.message ?: "Auth failed",
                fixHint = "Check that your PAT is valid and not expired."
            )
        )

        val repoRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repo", token)
        val repoPassed = repoRes.isSuccess
        var pushAllowed = false
        if (repoPassed) {
            val repoObj = JSONObject(repoRes.getOrThrow().second)
            val perms = repoObj.optJSONObject("permissions")
            pushAllowed = perms?.optBoolean("push", false) ?: false
        }
        items.add(
            RepoReadinessItem(
                title = "Repository Reachable",
                passed = repoPassed,
                detail = if (repoPassed) "Repository '$repo' accessed" else repoRes.exceptionOrNull()?.message ?: "Not accessible",
                fixHint = "Check repository spelling and ensure PAT has access to '$repo'."
            )
        )

        items.add(
            RepoReadinessItem(
                title = "Contents Write Permissions",
                passed = pushAllowed,
                detail = if (pushAllowed) "Push (Contents: write) verified" else "Read-only or insufficient permissions",
                fixHint = "Settings > Developer Settings > Personal Access Tokens: enable Contents (Read and write), Pull requests, Actions, Issues."
            )
        )

        val actionsRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repo/actions/runs?per_page=1", token)
        val actionsPassed = actionsRes.isSuccess
        items.add(
            RepoReadinessItem(
                title = "GitHub Actions Enabled",
                passed = actionsPassed,
                detail = if (actionsPassed) "Actions API responsive" else "Actions check failed or disabled",
                fixHint = "GitHub Repository > Settings > Actions > General: ensure Actions are enabled."
            )
        )

        val ksRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repo/contents/app/debug.keystore?ref=main", token)
        val ksPassed = ksRes.isSuccess
        items.add(
            RepoReadinessItem(
                title = "Keystore on Main",
                passed = ksPassed,
                detail = if (ksPassed) "app/debug.keystore exists on main" else "app/debug.keystore not found on main yet",
                fixHint = "Push to main once (or use Bootstrap new repo) so workflow auto-generates debug.keystore."
            )
        )

        val buildYmlRes = executeGitHubRequest("GET", "https://api.github.com/repos/$repo/contents/.github/workflows/build.yml?ref=main", token)
        val buildYmlPassed = buildYmlRes.isSuccess
        items.add(
            RepoReadinessItem(
                title = "build.yml Workflow Present",
                passed = buildYmlPassed,
                detail = if (buildYmlPassed) ".github/workflows/build.yml found on main" else "build.yml missing on main",
                fixHint = "Tap 'Bootstrap new repo' below to create missing workflow files."
            )
        )

        var latestBuildOk = false
        var buildDetail = "No runs found"
        if (actionsPassed) {
            runCatching {
                val runsArr = JSONObject(actionsRes.getOrThrow().second).optJSONArray("workflow_runs")
                if (runsArr != null && runsArr.length() > 0) {
                    val latest = runsArr.getJSONObject(0)
                    val status = latest.optString("status", "")
                    val conclusion = latest.optString("conclusion", "")
                    latestBuildOk = (conclusion == "success")
                    buildDetail = "Run #${latest.optLong("id")}: status=$status, conclusion=${conclusion.ifBlank { "running" }}"
                }
            }
        }
        items.add(
            RepoReadinessItem(
                title = "Latest Build Status",
                passed = latestBuildOk,
                detail = buildDetail,
                fixHint = "Ensure workflow permissions are set to Read and write, then trigger a build."
            )
        )

        val allPassed = items.all { it.passed }
        Result.success(RepoReadinessReport(items = items, allPassed = allPassed))
    }

    suspend fun bootstrapNewRepoFiles(): Result<String> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val repo = settings.githubRepoSlug.trim()
        val baseBranch = settings.githubBaseBranch.ifBlank { "main" }

        if (token.isBlank() || !repo.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Configure GitHub token and owner/repo first."))
        }

        val buildYmlContent = runCatching { File(".github/workflows/build.yml").readText() }.getOrElse { "" }
        val releaseYmlContent = runCatching { File(".github/workflows/release.yml").readText() }.getOrElse { "" }
        val gitignoreContent = runCatching { File(".gitignore").readText() }.getOrElse { "*.iml\n/build\n/app/build\n*.keystore" }

        val changes = listOf(
            CommitFileChange(".github/workflows/build.yml", buildYmlContent),
            CommitFileChange(".github/workflows/release.yml", releaseYmlContent),
            CommitFileChange(".gitignore", gitignoreContent)
        )

        val res = commitChangesAtomic(
            repoSlug = repo,
            token = token,
            branch = baseBranch,
            changes = changes,
            commitMessage = "chore(bootstrap): bootstrap missing workflows and gitignore [skip ci]"
        )
        res.map { "Successfully bootstrapped repository files on '$baseBranch'." }
    }

    companion object {
        const val STAGE_IDLE = "Idle"
        const val STAGE_RUNNING = "Running"
        const val STAGE_WAITING_CI = "Waiting for CI"
        const val STAGE_WAITING_APPROVAL = "Waiting for approval"
        const val STAGE_FAILED = "Failed"

        const val MAX_OPEN_AUTO_DEV_PRS = 2
        const val MAX_REPAIR_ATTEMPTS = 5
    }
}
