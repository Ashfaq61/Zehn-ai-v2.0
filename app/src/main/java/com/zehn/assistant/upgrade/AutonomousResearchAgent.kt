package com.zehn.assistant.upgrade

import android.util.Base64
import com.zehn.assistant.ai.OnDeviceLiteRtManager
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.ZehnSettings
import com.zehn.assistant.data.db.AutoDevRunEntity
import com.zehn.assistant.data.db.DiscoveredToolEntity
import com.zehn.assistant.data.db.UpgradeCenterDao
import com.zehn.assistant.data.db.UpgradeErrorLogEntity
import com.zehn.assistant.data.db.UpgradeProposalEntity
import com.zehn.assistant.security.KeystoreCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class ResearchCycleSummary(
    val running: Boolean = false,
    val currentStage: String = "Idle",
    val newlyDiscoveredCount: Int = 0,
    val skippedDuplicatesCount: Int = 0,
    val rejectedCount: Int = 0,
    val proposalsCreatedCount: Int = 0,
    val autoApprovedProposalIds: List<Long> = emptyList(),
    val lastError: String = ""
)

/**
 * Autonomous GitHub & Hugging Face discovery agent:
 * - Reads user's editable topic list
 * - Enforces strict pre-filters BEFORE generating reports/proposals
 * - Items < 7.0 fit score stay "Suggested" (never auto-integrated)
 * - Items >= 8.0 are eligible for auto-approval ONLY when autoApproveEnabled == true and no protected paths are touched
 * - Backs off cleanly on HTTP 403/429 rate limits (Retry-After & X-RateLimit-Reset)
 */
@Singleton
class AutonomousResearchAgent @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager,
    private val prefs: PreferencesRepository,
    private val upgradeDao: UpgradeCenterDao,
    private val liteRtManager: OnDeviceLiteRtManager,
    private val safetyAnalyzer: RepoSafetyAnalyzer
) {
    private val _state = MutableStateFlow(ResearchCycleSummary())
    val state: StateFlow<ResearchCycleSummary> = _state.asStateFlow()

    @Volatile
    private var stopRequested = false

    fun stopResearch() {
        stopRequested = true
        _state.value = _state.value.copy(running = false, currentStage = "Idle")
    }

    suspend fun runFullResearchCycle(
        includeGitHub: Boolean = true,
        includeHuggingFace: Boolean = true,
        forceRescanExisting: Boolean = false,
        focusModeOverride: String? = null,
        customQuery: String? = null
    ): Result<ResearchCycleSummary> = withContext(Dispatchers.IO) {
        stopRequested = false
        val settings = prefs.settings.first()
        if (settings.allAutoUpgradesPaused && customQuery.isNullOrBlank()) {
            val paused = ResearchCycleSummary(running = false, currentStage = "Paused", lastError = "Auto-upgrades are paused.")
            _state.value = paused
            return@withContext Result.success(paused)
        }

        val focusMode = focusModeOverride ?: settings.researchFocusMode
        val deviceRamMb = liteRtManager.state.value.totalRamMb

        var newCount = 0
        var skippedCount = 0
        var rejectedCount = 0
        var proposalCount = 0
        val autoApprovedIds = mutableListOf<Long>()
        val errors = mutableListOf<String>()

        _state.value = ResearchCycleSummary(running = true, currentStage = "Running")

        try {
            if (includeGitHub && !stopRequested) {
                val configuredTopics = settings.parsedGitHubTopics()
                val queries = when {
                    !customQuery.isNullOrBlank() -> listOf(customQuery.trim())
                    focusMode == "SECURITY_ONLY" -> configuredTopics.filter {
                        it.contains("security", true) || it.contains("privacy", true) || it.contains("keystore", true)
                    }.ifEmpty {
                        listOf("android security keystore privacy harden", "android accessibilityservice security")
                    }
                    else -> configuredTopics.take(6)
                }

                for (q in queries) {
                    if (stopRequested) break
                    val reposRes = fetchGitHubRepositories(q, perPage = 4)
                    reposRes.onFailure { err ->
                        val msg = "GitHub ($q): ${err.message}"
                        errors.add(msg)
                        recordErrorLog("DISCOVERY", msg)
                    }.onSuccess { rawRepos ->
                        for (repo in rawRepos) {
                            if (stopRequested) break
                            val existing = upgradeDao.findDiscoveredTool(repo.fullName)
                            if (existing != null && !forceRescanExisting) {
                                skippedCount++
                                continue
                            }

                            _state.value = _state.value.copy(currentStage = "Running (${repo.fullName})")
                            val readme = fetchGitHubReadme(repo.fullName)
                            val gradle = fetchGitHubBuildGradle(repo.fullName)

                            val evaluated = safetyAnalyzer.evaluateTool(
                                source = "GITHUB",
                                identifier = repo.fullName,
                                name = repo.fullName.substringAfter('/'),
                                description = repo.description,
                                starsOrDownloads = repo.stars,
                                lastUpdated = repo.updatedAt,
                                rawLicense = repo.license,
                                languageOrPipeline = repo.language,
                                readmeText = readme,
                                gradleText = gradle,
                                htmlUrl = repo.htmlUrl,
                                deviceRamMb = deviceRamMb
                            )
                            upgradeDao.upsertDiscoveredTool(evaluated)
                            newCount++

                            if (evaluated.statusTag == "Rejected") {
                                rejectedCount++
                            } else {
                                val createdPropId = maybeCreateProposal(evaluated, settings)
                                if (createdPropId != null) {
                                    proposalCount++
                                    if (createdPropId.second) {
                                        autoApprovedIds.add(createdPropId.first)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (includeHuggingFace && !stopRequested) {
                val configuredHf = settings.parsedHuggingFaceTopics()
                val hfQueries = when {
                    !customQuery.isNullOrBlank() -> listOf(customQuery.trim())
                    else -> configuredHf.take(5)
                }

                for (q in hfQueries) {
                    if (stopRequested) break
                    _state.value = _state.value.copy(currentStage = "Running (HF: $q)")
                    val hfRes = fetchHuggingFaceModels(q, limit = 4)
                    hfRes.onFailure { err ->
                        val msg = "HF ($q): ${err.message}"
                        errors.add(msg)
                        recordErrorLog("DISCOVERY", msg)
                    }.onSuccess { models ->
                        for (m in models) {
                            if (stopRequested) break
                            val existing = upgradeDao.findDiscoveredTool(m.modelId)
                            if (existing != null && !forceRescanExisting) {
                                skippedCount++
                                continue
                            }
                            val evaluated = safetyAnalyzer.evaluateTool(
                                source = "HUGGINGFACE",
                                identifier = m.modelId,
                                name = m.modelId.substringAfter('/'),
                                description = "Pipeline: ${m.pipelineTag}, Languages: ${m.languages}",
                                starsOrDownloads = m.downloads,
                                lastUpdated = m.lastModified,
                                rawLicense = m.license,
                                languageOrPipeline = m.pipelineTag,
                                readmeText = m.cardSummary.ifBlank { "Model ${m.modelId} ${m.pipelineTag} ${m.languages}" },
                                gradleText = "",
                                htmlUrl = m.htmlUrl,
                                deviceRamMb = deviceRamMb,
                                hasModelCard = m.hasModelCard
                            )
                            upgradeDao.upsertDiscoveredTool(evaluated)
                            newCount++

                            if (evaluated.statusTag == "Rejected") {
                                rejectedCount++
                            } else {
                                val createdPropId = maybeCreateProposal(evaluated, settings)
                                if (createdPropId != null) {
                                    proposalCount++
                                    if (createdPropId.second) {
                                        autoApprovedIds.add(createdPropId.first)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val now = System.currentTimeMillis()
            val intervalMs = when (settings.researchScheduleMode) {
                "DAILY" -> 24L * 60 * 60 * 1000
                "WEEKLY" -> 7L * 24L * 60 * 60 * 1000
                else -> 0L
            }
            prefs.updateSettings {
                it.copy(
                    lastResearchTimestamp = now,
                    nextScheduledResearchTimestamp = if (intervalMs > 0L && !it.researchPaused && !it.allAutoUpgradesPaused) now + intervalMs else 0L
                )
            }

            upgradeDao.insertDevRun(
                AutoDevRunEntity(
                    actionType = "RESEARCH",
                    branchOrRef = settings.githubBaseBranch,
                    httpStatus = if (errors.isEmpty()) 200 else 207,
                    summary = "Discovered $newCount tools ($rejectedCount rejected), skipped $skippedCount cached, $proposalCount proposals",
                    detail = errors.joinToString("\n").ifBlank { "Completed cleanly." },
                    success = errors.isEmpty() || newCount > 0
                )
            )

            val done = ResearchCycleSummary(
                running = false,
                currentStage = if (errors.isNotEmpty() && newCount == 0) "Failed" else "Idle",
                newlyDiscoveredCount = newCount,
                skippedDuplicatesCount = skippedCount,
                rejectedCount = rejectedCount,
                proposalsCreatedCount = proposalCount,
                autoApprovedProposalIds = autoApprovedIds,
                lastError = errors.joinToString("; ")
            )
            _state.value = done
            Result.success(done)
        } catch (t: Throwable) {
            val errMsg = t.message ?: "Research failed"
            recordErrorLog("DISCOVERY", errMsg, t.stackTraceToString().take(800))
            _state.value = ResearchCycleSummary(
                running = false,
                currentStage = "Failed",
                newlyDiscoveredCount = newCount,
                skippedDuplicatesCount = skippedCount,
                rejectedCount = rejectedCount,
                proposalsCreatedCount = proposalCount,
                lastError = errMsg
            )
            upgradeDao.insertDevRun(
                AutoDevRunEntity(
                    actionType = "RESEARCH",
                    branchOrRef = settings.githubBaseBranch,
                    httpStatus = 0,
                    summary = "Research cycle failed",
                    detail = errMsg,
                    success = false
                )
            )
            Result.failure(t)
        }
    }

    /**
     * Returns Pair(proposalId, isAutoApprovedForAutoDev) or null if item stays Suggested (< 7.0) or rejected.
     */
    internal suspend fun maybeCreateProposal(tool: DiscoveredToolEntity, settings: ZehnSettings): Pair<Long, Boolean>? {
        if (tool.statusTag == "Rejected" || tool.rejectionReason.isNotBlank() || tool.securityFlags.startsWith("BLOCKED")) {
            return null
        }
        // Items under a fit score of 7.0 stay "Suggested" and are NEVER integrated automatically
        if (tool.fitScore < 7.0) {
            return null
        }
        val existingProp = upgradeDao.findProposalByTool(tool.identifier)
        if (existingProp != null) return null

        val sanitizedSlug = tool.name.replace(Regex("[^A-Za-z0-9]"), "").take(24).ifBlank { "ToolAdapter" }
        val adapterFilePath = "app/src/main/java/com/zehn/assistant/upgrade/adapters/${sanitizedSlug}AdapterMetadata.kt"
        val risk = safetyAnalyzer.assessIntegrationRisk(tool, listOf(adapterFilePath))
        if (risk.riskLevel == "BLOCKED") return null

        val protectedFlags = risk.protectedPaths.joinToString(", ")
        val eligibleAutoApprove = tool.fitScore >= 8.0 &&
            settings.autoApproveEnabled &&
            settings.autoDevEnabled &&
            !settings.allAutoUpgradesPaused &&
            protectedFlags.isBlank() &&
            !risk.requiresApproval

        val status = if (eligibleAutoApprove) "APPROVED" else "PENDING"
        val requiresApproval = !eligibleAutoApprove

        val plan = buildString {
            appendLine("Integration Report for ${tool.identifier} (${tool.source})")
            appendLine("- Fit Score: ${tool.fitScore}/10 (${tool.compatibilityScore}/100)")
            appendLine("- License: ${tool.license}")
            appendLine("- RAM / Storage: ~${tool.estimatedRamMb}MB RAM / ~${tool.estimatedStorageMb}MB")
            appendLine("- Dependencies: ${tool.dependenciesSummary}")
            appendLine("- Security: ${tool.securityFlags}")
            if (protectedFlags.isNotBlank()) appendLine("- PROTECTED PATHS: $protectedFlags (Manual Approval Required)")
            appendLine("1. Create isolated adapter descriptor under upgrade/adapters without breaking core APIs.")
            appendLine("2. Target branch: zehn-auto/${tool.name.lowercase().replace(Regex("[^a-z0-9]"), "-").take(24)}")
            appendLine("3. Run unit/Robolectric tests & CI before PR review.")
        }

        val generatedAdapterCode = """
            package com.zehn.assistant.upgrade.adapters

            /**
             * Auto-generated modular capability descriptor for ${tool.identifier}
             * License: ${tool.license} | Source: ${tool.htmlUrl}
             * Risk Assessment: ${risk.riskLevel} | Fit Score: ${tool.fitScore}/10
             */
            object ${sanitizedSlug}AdapterMetadata {
                const val SOURCE_ID: String = "${tool.identifier}"
                const val SOURCE_TYPE: String = "${tool.source}"
                const val LICENSE: String = "${tool.license}"
                const val FIT_SCORE: Double = ${tool.fitScore}
                const val REQUIRES_APPROVAL: Boolean = $requiresApproval
            }
        """.trimIndent()

        val filesJson = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("path", adapterFilePath)
                    put("content", generatedAdapterCode)
                }
            )
        }.toString()

        val branchSlug = tool.name.lowercase().replace(Regex("[^a-z0-9]"), "-").trim('-').take(24).ifBlank { "tool" }
        val newId = upgradeDao.upsertProposal(
            UpgradeProposalEntity(
                toolIdentifier = tool.identifier,
                title = "Integrate ${tool.name} adapter",
                summary = "${tool.description} (Fit ${tool.fitScore}/10, RAM ~${tool.estimatedRamMb}MB)",
                integrationPlan = plan,
                proposedFilesJson = filesJson,
                permissionsRequested = risk.permissionsRequested.joinToString(", "),
                riskLevel = risk.riskLevel,
                requiresApproval = requiresApproval,
                status = status,
                branchName = "zehn-auto/$branchSlug",
                fitScore = tool.fitScore,
                protectedPathsFlagged = protectedFlags
            )
        )
        if (eligibleAutoApprove) {
            upgradeDao.upsertDiscoveredTool(tool.copy(statusTag = "Approved"))
        }
        return newId to eligibleAutoApprove
    }

    internal data class RawGitHubRepo(
        val fullName: String,
        val description: String,
        val stars: Int,
        val license: String,
        val language: String,
        val updatedAt: String,
        val htmlUrl: String
    )

    internal data class RawHfModel(
        val modelId: String,
        val pipelineTag: String,
        val downloads: Int,
        val license: String,
        val languages: String,
        val lastModified: String,
        val htmlUrl: String,
        val hasModelCard: Boolean,
        val cardSummary: String = ""
    )

    private suspend fun fetchGitHubRepositories(query: String, perPage: Int): Result<List<RawGitHubRepo>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.github.com/search/repositories?q=$encoded&sort=updated&order=desc&per_page=$perPage"
        return executeGetWithBackoff(url, isGitHub = true).mapCatching { body ->
            parseGitHubSearchPayload(body)
        }
    }

    internal fun parseGitHubSearchPayload(json: String): List<RawGitHubRepo> {
        val root = JSONObject(json)
        val items = root.optJSONArray("items") ?: return emptyList()
        val list = mutableListOf<RawGitHubRepo>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val fullName = obj.optString("full_name", "").trim()
            if (fullName.isBlank()) continue
            list.add(
                RawGitHubRepo(
                    fullName = fullName,
                    description = obj.optString("description", "No description").ifBlank { "No description" },
                    stars = obj.optInt("stargazers_count", 0),
                    license = obj.optJSONObject("license")?.optString("spdx_id", "Unknown").orEmpty().ifBlank { "Unknown" },
                    language = obj.optString("language", "Mixed").ifBlank { "Mixed" },
                    updatedAt = obj.optString("pushed_at", "").take(10),
                    htmlUrl = obj.optString("html_url", "https://github.com/$fullName")
                )
            )
        }
        return list
    }

    private suspend fun fetchGitHubReadme(fullName: String): String {
        val url = "https://api.github.com/repos/$fullName/readme"
        val res = executeGetWithBackoff(url, isGitHub = true).getOrNull() ?: return ""
        return runCatching {
            val obj = JSONObject(res)
            val b64 = obj.optString("content", "").replace("\n", "")
            if (b64.isBlank()) "" else String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).take(4000)
        }.getOrDefault("")
    }

    private suspend fun fetchGitHubBuildGradle(fullName: String): String {
        val paths = listOf("app/build.gradle.kts", "app/build.gradle", "build.gradle.kts", "package.json", "requirements.txt")
        for (path in paths) {
            val res = executeGetWithBackoff("https://api.github.com/repos/$fullName/contents/$path", isGitHub = true).getOrNull() ?: continue
            val decoded = runCatching {
                val obj = JSONObject(res)
                val b64 = obj.optString("content", "").replace("\n", "")
                if (b64.isBlank()) "" else String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).take(4000)
            }.getOrDefault("")
            if (decoded.isNotBlank()) return decoded
        }
        return ""
    }

    private suspend fun fetchHuggingFaceModels(query: String, limit: Int): Result<List<RawHfModel>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://huggingface.co/api/models?search=$encoded&sort=downloads&direction=-1&limit=$limit&full=true"
        return executeGetWithBackoff(url, isGitHub = false).mapCatching { body ->
            parseHuggingFacePayload(body)
        }
    }

    internal fun parseHuggingFacePayload(json: String): List<RawHfModel> {
        val arr = JSONArray(json)
        val out = mutableListOf<RawHfModel>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", obj.optString("modelId", "")).trim()
            if (id.isBlank()) continue
            val tags = obj.optJSONArray("tags")
            val cardData = obj.optJSONObject("cardData")
            var license = cardData?.optString("license", "").orEmpty().ifBlank { "Unknown" }
            val langs = mutableListOf<String>()
            var hasReadmeSibling = false
            val siblings = obj.optJSONArray("siblings")
            if (siblings != null) {
                for (s in 0 until siblings.length()) {
                    val rfn = siblings.optJSONObject(s)?.optString("rfilename", "").orEmpty()
                    if (rfn.equals("README.md", ignoreCase = true)) {
                        hasReadmeSibling = true
                        break
                    }
                }
            }
            if (tags != null) {
                for (t in 0 until tags.length()) {
                    val tag = tags.optString(t)
                    if (tag.startsWith("license:") && license == "Unknown") license = tag.removePrefix("license:")
                    else if (tag in setOf("en", "ur", "hi", "multilingual")) langs.add(tag.uppercase())
                }
            }
            val downloads = obj.optInt("downloads", 0)
            val pipelineTag = obj.optString("pipeline_tag", "").trim()
            // Verify model card presence: cardData present, OR README.md listed in siblings,
            // OR documented license + pipeline + substantial downloads (>= 500)
            val hasModelCard = cardData != null || hasReadmeSibling ||
                (!license.equals("Unknown", ignoreCase = true) && pipelineTag.isNotBlank() && downloads >= 500)

            out.add(
                RawHfModel(
                    modelId = id,
                    pipelineTag = pipelineTag.ifBlank { "text-generation" },
                    downloads = downloads,
                    license = license,
                    languages = langs.ifEmpty { listOf("EN/Multi") }.joinToString(","),
                    lastModified = obj.optString("lastModified", "").take(10),
                    htmlUrl = "https://huggingface.co/$id",
                    hasModelCard = hasModelCard,
                    cardSummary = cardData?.optString("model-index", "").orEmpty()
                )
            )
        }
        return out
    }

    private suspend fun executeGetWithBackoff(url: String, isGitHub: Boolean): Result<String> {
        val token = if (isGitHub) {
            crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        } else {
            crypto.getSecret(KeystoreCryptoManager.KEY_HF_TOKEN).trim()
        }
        var delayMs = 1_000L
        repeat(3) { attempt ->
            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", if (isGitHub) "application/vnd.github+json" else "application/json")
                .apply {
                    if (isGitHub) {
                        addHeader("X-GitHub-Api-Version", "2022-11-28")
                        addHeader("User-Agent", "ZehnAI-AutonomousResearch/4.2")
                    }
                    if (token.isNotBlank()) addHeader("Authorization", "Bearer $token")
                }
                .get()
                .build()

            val callRes = runCatching {
                okHttpClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val retryAfterSec = resp.header("Retry-After")?.toLongOrNull()
                    val resetEpochSec = resp.header("X-RateLimit-Reset")?.toLongOrNull()
                    val body = resp.body?.string().orEmpty()
                    Triple(code, computeWaitMs(retryAfterSec, resetEpochSec, delayMs), body)
                }
            }

            callRes.onFailure { err ->
                if (attempt == 2) return Result.failure(err)
                delay(delayMs)
                delayMs *= 2
            }.onSuccess { (code, waitMs, body) ->
                if (code in 200..299) return Result.success(body)
                if ((code == 403 || code == 429) && attempt < 2) {
                    delay(waitMs)
                    delayMs *= 2
                } else {
                    val apiLabel = if (isGitHub) "GitHub" else "HuggingFace"
                    return Result.failure(IllegalStateException("$apiLabel API HTTP $code"))
                }
            }
        }
        return Result.failure(IllegalStateException("API retries exhausted"))
    }

    internal fun computeWaitMs(retryAfterSec: Long?, rateResetEpochSec: Long?, defaultMs: Long): Long {
        if (retryAfterSec != null && retryAfterSec > 0) {
            return (retryAfterSec * 1000L).coerceIn(500L, 15_000L)
        }
        if (rateResetEpochSec != null) {
            val deltaMs = (rateResetEpochSec * 1000L) - System.currentTimeMillis()
            if (deltaMs in 1..15_000L) return deltaMs
        }
        return defaultMs.coerceIn(500L, 15_000L)
    }

    private suspend fun recordErrorLog(source: String, msg: String, detail: String = "") {
        runCatching {
            upgradeDao.insertErrorLog(
                UpgradeErrorLogEntity(
                    source = source,
                    message = msg.take(240),
                    detail = detail.take(1200)
                )
            )
        }
    }
}
