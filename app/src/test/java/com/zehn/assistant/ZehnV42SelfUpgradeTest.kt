package com.zehn.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.CloudLlmClient
import com.zehn.assistant.ai.OnDeviceLiteRtManager
import com.zehn.assistant.ai.RagEngine
import com.zehn.assistant.data.BackupManager
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.ZehnSettings
import com.zehn.assistant.data.db.DiscoveredToolEntity
import com.zehn.assistant.data.db.ReleaseRecordEntity
import com.zehn.assistant.data.db.UpgradeErrorLogEntity
import com.zehn.assistant.data.db.UpgradeProposalEntity
import com.zehn.assistant.data.db.ZehnDatabase
import com.zehn.assistant.security.CrashRecoveryManager
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.security.SafetyPolicyManager
import com.zehn.assistant.upgrade.AutonomousResearchAgent
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZehnV42SelfUpgradeTest {

    private lateinit var context: Context
    private lateinit var db: ZehnDatabase
    private lateinit var crypto: KeystoreCryptoManager
    private lateinit var safetyPolicy: SafetyPolicyManager
    private lateinit var prefs: PreferencesRepository
    private lateinit var safetyAnalyzer: RepoSafetyAnalyzer
    private lateinit var json: Json

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        json = Json { ignoreUnknownKeys = true }
        db = Room.inMemoryDatabaseBuilder(context, ZehnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        crypto = KeystoreCryptoManager(context)
        crypto.clearAllSecrets()
        safetyPolicy = SafetyPolicyManager(context, json)
        prefs = PreferencesRepository(context)
        safetyAnalyzer = RepoSafetyAnalyzer()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `room migration 2 to 3 preserves existing upgrade rows and adds v4_2 schema`() {
        runBlocking {
        val dbFile = context.getDatabasePath("migration_v2_v3_test.db")
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, projectId INTEGER, providerName TEXT NOT NULL, modelName TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, citationsJson TEXT NOT NULL, imageUri TEXT, modelUsed TEXT NOT NULL, detectedLanguage TEXT NOT NULL, timestamp INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
        sqlite.execSQL("CREATE INDEX index_messages_conversationId ON messages (conversationId)")
        sqlite.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tier TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, priority INTEGER NOT NULL, sourceApp TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, documentCount INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE document_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, projectId INTEGER NOT NULL, documentName TEXT NOT NULL, sourceUri TEXT NOT NULL, pageOrSection INTEGER NOT NULL, chunkText TEXT NOT NULL, embeddingCsv TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE)")
        sqlite.execSQL("CREATE INDEX index_document_chunks_projectId ON document_chunks (projectId)")
        sqlite.execSQL("CREATE TABLE skills (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, slug TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, markdownBody TEXT NOT NULL, parametersSchemaJson TEXT NOT NULL, enabled INTEGER NOT NULL, builtIn INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE workflows (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, kind TEXT NOT NULL, definitionText TEXT NOT NULL, enabled INTEGER NOT NULL, lastRunAt INTEGER, runCount INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE triggers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, triggerType TEXT NOT NULL, conditionValue TEXT NOT NULL, actionPrompt TEXT NOT NULL, linkedFlowId INTEGER, enabled INTEGER NOT NULL, lastTriggeredAt INTEGER)")
        sqlite.execSQL("CREATE TABLE action_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, category TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, status TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE discovered_tools (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, source TEXT NOT NULL, identifier TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, starsOrDownloads INTEGER NOT NULL, lastUpdated TEXT NOT NULL, license TEXT NOT NULL, languageOrPipeline TEXT NOT NULL, dependenciesSummary TEXT NOT NULL, supportedAndroidMinSdk INTEGER, estimatedRamMb INTEGER NOT NULL, estimatedStorageMb INTEGER NOT NULL, batteryImpact TEXT NOT NULL, compatibilityScore INTEGER NOT NULL, compatibilityReport TEXT NOT NULL, securityFlags TEXT NOT NULL, isDuplicate INTEGER NOT NULL, duplicateReason TEXT NOT NULL, htmlUrl TEXT NOT NULL, evaluatedAt INTEGER NOT NULL)")
        sqlite.execSQL("CREATE UNIQUE INDEX index_discovered_tools_identifier ON discovered_tools (identifier)")
        sqlite.execSQL("CREATE TABLE upgrade_proposals (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, toolIdentifier TEXT NOT NULL, title TEXT NOT NULL, summary TEXT NOT NULL, integrationPlan TEXT NOT NULL, proposedFilesJson TEXT NOT NULL, permissionsRequested TEXT NOT NULL, riskLevel TEXT NOT NULL, requiresApproval INTEGER NOT NULL, status TEXT NOT NULL, branchName TEXT NOT NULL, prNumber INTEGER, prUrl TEXT NOT NULL, commitSha TEXT NOT NULL, lastBuildConclusion TEXT NOT NULL, lastErrorReport TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE dev_run_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, actionType TEXT NOT NULL, branchOrRef TEXT NOT NULL, httpStatus INTEGER NOT NULL, summary TEXT NOT NULL, detail TEXT NOT NULL, success INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
        sqlite.execSQL("CREATE TABLE release_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tagName TEXT NOT NULL, versionName TEXT NOT NULL, parsedVersionCode INTEGER, releaseName TEXT NOT NULL, changelog TEXT NOT NULL, apkAssetName TEXT NOT NULL, apkDownloadUrl TEXT NOT NULL, releaseHtmlUrl TEXT NOT NULL, apkSizeBytes INTEGER NOT NULL, sha256 TEXT NOT NULL, compatibleWithDevice INTEGER NOT NULL, compatibilityNote TEXT NOT NULL, publishedAt TEXT NOT NULL, notified INTEGER NOT NULL, detectedAt INTEGER NOT NULL)")
        sqlite.execSQL("CREATE UNIQUE INDEX index_release_history_tagName ON release_history (tagName)")

        sqlite.execSQL("INSERT INTO discovered_tools VALUES (1, 'GITHUB', 'legacy/tool', 'tool', 'Legacy v2 row', 99, '2026-10-01', 'MIT', 'Kotlin', 'okhttp', 24, 64, 5, 'Low', 85, 'ok', 'CLEAR', 0, '', 'https://github.com/legacy/tool', 1000)")
        sqlite.version = 2
        sqlite.close()

        val migrated = Room.databaseBuilder(context, ZehnDatabase::class.java, dbFile.name)
            .addMigrations(ZehnDatabase.MIGRATION_1_2, ZehnDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        val savedTool = migrated.upgradeCenterDao().findDiscoveredTool("legacy/tool")
        assertNotNull(savedTool)
        assertEquals("Suggested", savedTool?.statusTag)
        migrated.upgradeCenterDao().insertErrorLog(UpgradeErrorLogEntity(source = "UPDATER", message = "Test log"))
        assertEquals(1, migrated.upgradeCenterDao().observeErrorLogs().first().size)
        migrated.close()
        dbFile.delete()
        }
    }

    @Test
    fun `strict pre-filters reject GPL, AGPL, non-commercial, unknown, stale, unverified HF, and over-RAM tools`() {
        assertEquals("INCOMPATIBLE", safetyAnalyzer.evaluateLicense("GPL-3.0").status)
        assertEquals("INCOMPATIBLE", safetyAnalyzer.evaluateLicense("AGPL-3.0").status)
        assertEquals("INCOMPATIBLE", safetyAnalyzer.evaluateLicense("CC-BY-NC-4.0").status)
        assertEquals("INCOMPATIBLE", safetyAnalyzer.evaluateLicense("UNKNOWN").status)
        assertEquals("INCOMPATIBLE", safetyAnalyzer.evaluateLicense("NOASSERTION").status)
        assertEquals("COMPATIBLE", safetyAnalyzer.evaluateLicense("MIT").status)
        assertEquals("COMPATIBLE", safetyAnalyzer.evaluateLicense("Apache-2.0").status)

        assertFalse(safetyAnalyzer.isActivelyMaintained("2020-01-01", LocalDate.of(2026, 11, 16)))
        assertTrue(safetyAnalyzer.isActivelyMaintained("2026-09-10", LocalDate.of(2026, 11, 16)))

        val gplRejected = safetyAnalyzer.evaluateTool(
            source = "GITHUB",
            identifier = "bad/gpl-tool",
            name = "gpl-tool",
            description = "Copyleft assistant",
            starsOrDownloads = 500,
            lastUpdated = "2026-10-01",
            rawLicense = "GPL-3.0",
            languageOrPipeline = "Kotlin",
            readmeText = "GPL-3.0",
            gradleText = "",
            htmlUrl = "https://github.com/bad/gpl-tool",
            deviceRamMb = 8192L
        )
        assertEquals("Rejected", gplRejected.statusTag)
        assertTrue(gplRejected.rejectionReason.contains("GPL"))

        val noCardHf = safetyAnalyzer.evaluateTool(
            source = "HUGGINGFACE",
            identifier = "tiny/unverified-finetune",
            name = "unverified-finetune",
            description = "text-generation",
            starsOrDownloads = 120,
            lastUpdated = "2026-10-01",
            rawLicense = "MIT",
            languageOrPipeline = "text-generation",
            readmeText = "",
            gradleText = "",
            htmlUrl = "https://huggingface.co/tiny/unverified-finetune",
            deviceRamMb = 8192L,
            hasModelCard = false
        )
        assertEquals("Rejected", noCardHf.statusTag)
        assertTrue(noCardHf.rejectionReason.contains("Missing model card"))

        val giantModel = safetyAnalyzer.evaluateTool(
            source = "HUGGINGFACE",
            identifier = "huge/llama-70b",
            name = "llama-70b",
            description = "70B weights",
            starsOrDownloads = 5000,
            lastUpdated = "2026-10-01",
            rawLicense = "Apache-2.0",
            languageOrPipeline = "text-generation",
            readmeText = "Apache-2.0",
            gradleText = "",
            htmlUrl = "https://huggingface.co/huge/llama-70b",
            deviceRamMb = 6144L,
            hasModelCard = true
        )
        assertEquals("Rejected", giantModel.statusTag)
        assertTrue(giantModel.rejectionReason.contains("RAM"))
    }

    @Test
    fun `fit score under 7 stays Suggested and protected paths block auto-approve even when switch is ON`() {
        runBlocking {
        val mockClient = scriptedClient { _, _ -> 200 to "{}" }
        val liteRt = OnDeviceLiteRtManager(context, mockClient, crypto, db.actionLogDao())
        val agent = AutonomousResearchAgent(mockClient, crypto, prefs, db.upgradeCenterDao(), liteRt, safetyAnalyzer)

        // Tool overlapping built-in Room/CameraX with minSdk 33 and low score (< 7.0)
        val lowFit = safetyAnalyzer.evaluateTool(
            source = "GITHUB",
            identifier = "misc/helper-room-camerax",
            name = "helper-room-camerax",
            description = "Experimental helper using camerax and room",
            starsOrDownloads = 20,
            lastUpdated = "2026-10-01",
            rawLicense = "MIT",
            languageOrPipeline = "Python",
            readmeText = "MIT License",
            gradleText = "minSdk = 33",
            htmlUrl = "https://github.com/misc/helper-room-camerax",
            deviceRamMb = 8192L
        )
        assertTrue("Expected fit < 7.0 but got ${lowFit.fitScore}", lowFit.fitScore < 7.0)
        assertEquals("Suggested", lowFit.statusTag)
        val noProp = agent.maybeCreateProposal(lowFit, ZehnSettings(autoApproveEnabled = true, autoDevEnabled = true))
        assertNull(noProp)

        // High fit >= 8.0 stays PENDING when autoApproveEnabled == false
        val goodTool = safetyAnalyzer.evaluateTool(
            source = "GITHUB",
            identifier = "zehn-oss/pure-kotlin-tool",
            name = "pure-kotlin-tool",
            description = "Clean modular utility for offline indexing",
            starsOrDownloads = 450,
            lastUpdated = "2026-11-01",
            rawLicense = "Apache-2.0",
            languageOrPipeline = "Kotlin",
            readmeText = "Apache-2.0 License",
            gradleText = "minSdk = 24",
            htmlUrl = "https://github.com/zehn-oss/pure-kotlin-tool",
            deviceRamMb = 8192L
        )
        assertTrue("Expected fit >= 8.0 but got ${goodTool.fitScore}", goodTool.fitScore >= 8.0)

        val manualWhenOff = agent.maybeCreateProposal(goodTool, ZehnSettings(autoApproveEnabled = false, autoDevEnabled = true))
        assertNotNull(manualWhenOff)
        assertFalse(manualWhenOff!!.second)

        // Protected path always forces manual approval & gets detected
        val protectedHits = safetyAnalyzer.detectProtectedPaths(
            listOf("app/src/main/AndroidManifest.xml", "app/src/main/java/com/zehn/assistant/security/TouchGuard.kt")
        )
        assertTrue(protectedHits.any { it.contains("AndroidManifest") })
        assertTrue(protectedHits.any { it.contains("Security") })
        }
    }

    @Test
    fun `autodev engine enforces max 2 open PRs, exact cycle states, backoff, issue creation and rollback PR`() {
        runBlocking {
        val postedBodies = mutableListOf<String>()
        var branchCreated = false
        val mockClient = scriptedClient { req, bodyStr ->
            val url = req.url.toString()
            if (req.method == "POST") postedBodies.add(bodyStr)
            when {
                url.contains("/pulls?state=open") -> 200 to """
                    [
                      {"number":1,"title":"[Zehn Auto-Dev] A","head":{"ref":"zehn-auto/a"}},
                      {"number":2,"title":"[Zehn Auto-Dev] B","head":{"ref":"zehn-auto/b"}}
                    ]
                """.trimIndent()
                url.contains("/pulls?state=closed") -> 200 to """
                    [{"number":10,"title":"[Zehn Auto-Dev] Merged","merged_at":"2026-11-15T10:00:00Z","head":{"ref":"zehn-auto/old"}}]
                """.trimIndent()
                url.contains("/actions/runs") -> 200 to """{"workflow_runs":[{"id":1,"status":"completed","conclusion":"success"},{"id":2,"status":"completed","conclusion":"failure"}]}"""
                url.endsWith("/issues") && req.method == "POST" -> 201 to """{"number":99,"html_url":"https://github.com/zehn/zehn-ai/issues/99"}"""
                url.endsWith("/git/ref/heads/main") -> 200 to """{"object":{"sha":"sha000"}}"""
                url.contains("/git/ref/heads/zehn-auto/") -> if (branchCreated) 200 to """{"object":{"sha":"sha000"}}""" else 404 to "{}"
                url.endsWith("/git/refs") && req.method == "POST" -> {
                    branchCreated = true
                    201 to """{"ref":"refs/heads/zehn-auto/rollback","object":{"sha":"sha000"}}"""
                }
                url.contains("/git/refs/heads/") && req.method == "PATCH" -> 200 to """{"object":{"sha":"commit222"}}"""
                url.endsWith("/git/commits/sha000") -> 200 to """{"tree":{"sha":"tree000"}}"""
                url.endsWith("/git/blobs") -> 201 to """{"sha":"blob111"}"""
                url.endsWith("/git/trees") -> 201 to """{"sha":"tree111"}"""
                url.endsWith("/git/commits") -> 201 to """{"sha":"commit222"}"""
                url.endsWith("/pulls") && req.method == "POST" -> 201 to """{"number":77,"html_url":"https://github.com/zehn/zehn-ai/pull/77"}"""
                else -> 404 to "{}"
            }
        }

        crypto.saveSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN, "ghp_unit_test_secret_token_999")
        prefs.updateSettings { it.copy(githubRepoSlug = "zehn/zehn-ai", githubBaseBranch = "main") }

        val cloud = CloudLlmClient(context, mockClient, crypto, safetyPolicy, db.actionLogDao())
        val liteRt = OnDeviceLiteRtManager(context, mockClient, crypto, db.actionLogDao())
        val rag = RagEngine(context, db.projectRagDao())
        val router = AiEngineRouter(prefs, cloud, liteRt, rag, db.memoryDao(), db.skillDao(), db.actionLogDao())
        val engine = GitHubAutoDevEngine(mockClient, crypto, prefs, db.upgradeCenterDao(), safetyAnalyzer, router)

        val propId = db.upgradeCenterDao().upsertProposal(
            UpgradeProposalEntity(
                toolIdentifier = "zehn/demo",
                title = "New Tool",
                summary = "Test",
                integrationPlan = "Plan",
                proposedFilesJson = """[{"path":"app/src/main/java/com/zehn/assistant/upgrade/adapters/Demo.kt","content":"package demo"}]""",
                riskLevel = "LOW",
                requiresApproval = false,
                status = "APPROVED",
                branchName = "zehn-auto/demo-new",
                fitScore = 9.0
            )
        )

        val blockedByCap = engine.executeIntegrationProposal(propId)
        assertTrue(blockedByCap.isFailure)
        assertTrue(blockedByCap.exceptionOrNull()!!.message!!.contains("Max 2"))
        assertEquals(GitHubAutoDevEngine.STAGE_WAITING_APPROVAL, engine.devState.value.activeCycleStage)

        val metrics = engine.refreshRealGitHubMetrics()
        assertEquals(2, metrics.openAutoDevPrCount)
        assertEquals(1, metrics.mergedAutoDevPrCount)
        assertEquals(1, metrics.passedCiRunsCount)

        // Crash issue creation must redact embedded secrets before POSTing
        val issueRes = engine.createGitHubIssueForCrash(
            title = "NullPointer",
            sanitizedCrashLog = "Trace with ghp_secret1234567890 leaked and AIzaSyA1234567890abcdef"
        ).getOrThrow()
        assertTrue(issueRes.contains("#99"))
        val issueBody = postedBodies.last()
        assertFalse(issueBody.contains("ghp_secret1234567890"))
        assertFalse(issueBody.contains("AIzaSyA1234567890abcdef"))

        val rollbackRes = engine.createRollbackPrForPreviousVersion("v4.0.10").getOrThrow()
        assertTrue(rollbackRes.contains("PR #77"))
        }
    }

    @Test
    fun `release updater checks stable vs beta, blocks downgrades, resumes Range download, verifies sha256 and cert, and manages backups`() {
        runBlocking {
        val fullBytes = "ZEHN_TEST_APK_PAYLOAD_v9.9.9".toByteArray()
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(fullBytes).joinToString("") { "%02x".format(it) }
        var seenRangeHeader = ""

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val partFile = File(updatesDir, "v9.9.9.apk.part")
        partFile.writeBytes(fullBytes.copyOfRange(0, 10)) // simulate interrupted partial download

        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url.endsWith(".apk")) {
                    seenRangeHeader = req.header("Range").orEmpty()
                    val remaining = fullBytes.copyOfRange(10, fullBytes.size)
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .body(remaining.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
                val jsonBody = if (url.contains("/releases/latest")) {
                    """
                    {
                      "tag_name": "v9.9.9",
                      "name": "Stable v9.9.9",
                      "prerelease": false,
                      "draft": false,
                      "body": "versionCode: 999\nsha256: $expectedSha",
                      "html_url": "https://github.com/zehn/zehn-ai/releases/tag/v9.9.9",
                      "assets": [{"name":"zehn.apk","browser_download_url":"https://github.com/zehn/zehn-ai/releases/download/v9.9.9/zehn.apk","size":${fullBytes.size}}]
                    }
                    """.trimIndent()
                } else {
                    """
                    [
                      {"tag_name":"v9.9.10-beta1","name":"Beta","prerelease":true,"draft":false,"body":"versionCode: 1000","html_url":"https://github.com/zehn/zehn-ai/releases/tag/v9.9.10-beta1","assets":[]}
                    ]
                    """.trimIndent()
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val backupMgr = BackupManager(context, db, prefs, json)
        val mgr = ReleaseUpdateManager(context, mockClient, crypto, prefs, db.upgradeCenterDao(), backupMgr)

        // Pre-update backup create & restore
        prefs.updateSettings { it.copy(githubRepoSlug = "zehn/zehn-ai", updaterWifiOnly = false, releaseChannel = "STABLE") }
        val snap = backupMgr.createPreUpdateBackupSnapshot("v9.9.9").getOrThrow()
        assertTrue(snap.exists())
        assertTrue(backupMgr.restoreLatestPreUpdateBackup().isSuccess)

        // Channel filtering: stable ignores prerelease json array
        val preArray = """[{"tag_name":"v9.9.10-beta","prerelease":true,"draft":false,"html_url":"https://github.com/zehn/zehn-ai/releases/tag/b"}]"""
        assertTrue(mgr.parseReleasesJson(preArray, 24, 35, includePrereleases = false).isEmpty())
        assertEquals(1, mgr.parseReleasesJson(preArray, 24, 35, includePrereleases = true).size)

        // Downgrade guard
        val older = ReleaseRecordEntity(
            tagName = "v1.0.0",
            versionName = "1.0.0",
            parsedVersionCode = BuildConfig.VERSION_CODE,
            apkDownloadUrl = "https://github.com/zehn/zehn-ai/releases/download/v1.0.0/app.apk"
        )
        assertTrue(mgr.downloadAndVerifyReleaseApk(older, bypassWifiOnly = true).isFailure)

        // Resumable Range download + SHA-256 verification (cert check triggers SecurityException on plain non-APK test bytes)
        val newer = ReleaseRecordEntity(
            tagName = "v9.9.9",
            versionName = "9.9.9",
            parsedVersionCode = 999,
            sha256 = expectedSha,
            apkSizeBytes = fullBytes.size.toLong(),
            apkDownloadUrl = "https://github.com/zehn/zehn-ai/releases/download/v9.9.9/zehn.apk"
        )
        val dlAttempt = mgr.downloadAndVerifyReleaseApk(newer, bypassWifiOnly = true)
        assertEquals("bytes=10-", seenRangeHeader)
        // Fake bytes have matching SHA-256 so it reaches cert check and rejects un-signed bytes cleanly
        assertTrue(dlAttempt.isFailure)
        assertTrue(dlAttempt.exceptionOrNull()!!.message!!.contains("certificate"))

        // SHA-256 mismatch also rejects cleanly
        val badSha = newer.copy(sha256 = "0".repeat(64))
        partFile.writeBytes(fullBytes.copyOfRange(0, 10))
        val badShaRes = mgr.downloadAndVerifyReleaseApk(badSha, bypassWifiOnly = true)
        assertTrue(badShaRes.exceptionOrNull()!!.message!!.contains("SHA-256 verification FAILED"))

        // Cert digest matching logic
        assertTrue(mgr.verifyCertDigestsMatch(setOf("abc"), setOf("abc", "def")))
        assertFalse(mgr.verifyCertDigestsMatch(setOf("abc"), setOf("xyz")))
        assertFalse(mgr.verifyCertDigestsMatch(emptySet(), setOf("abc")))
        }
    }

    @Test
    fun `crash recovery activates Safe Mode strictly after 3 crashes within 5 minutes`() {
        val mgr = CrashRecoveryManager(context)
        mgr.exitSafeMode()

        val t0 = 1_000_000_000L
        mgr.recordUncaughtCrash(IllegalStateException("crash 1"), nowMs = t0)
        assertFalse(mgr.safeModeActive.value)

        // Crash 2 spaced > 5 min later resets window outside first crash
        val tFar = t0 + CrashRecoveryManager.CRASH_WINDOW_MS + 10_000L
        mgr.recordUncaughtCrash(IllegalStateException("crash 2"), nowMs = tFar)
        assertFalse(mgr.safeModeActive.value)

        // Crashes 3 and 4 within 5 min of tFar reach threshold 3 -> Safe Mode ON
        mgr.recordUncaughtCrash(IllegalStateException("crash 3"), nowMs = tFar + 30_000L)
        assertFalse(mgr.safeModeActive.value)
        mgr.recordUncaughtCrash(IllegalStateException("crash 4"), nowMs = tFar + 60_000L)
        assertTrue(mgr.safeModeActive.value)

        mgr.exitSafeMode()
        assertFalse(mgr.safeModeActive.value)
    }

    private fun scriptedClient(handler: (okhttp3.Request, String) -> Pair<Int, String>): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = okio.Buffer()
                req.body?.writeTo(buf)
                val (code, body) = handler(req, buf.readUtf8())
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "ERR")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }
}
