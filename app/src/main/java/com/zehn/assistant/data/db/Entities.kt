package com.zehn.assistant.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val projectId: Long? = null,
    val providerName: String = "GEMINI",
    val modelName: String = "gemini-2.5-flash",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val citationsJson: String = "[]",
    val imageUri: String? = null,
    val modelUsed: String = "",
    val detectedLanguage: String = "en",
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tier: String, // WORKING, SESSION, LONG_TERM, NOTIFICATION_INTEL
    val title: String,
    val content: String,
    val priority: Int = 5, // 1..10
    val sourceApp: String = "Zehn",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val documentCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class DocumentChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val documentName: String,
    val sourceUri: String,
    val pageOrSection: Int,
    val chunkText: String,
    val embeddingCsv: String, // 64-dim normalized float vector stored in SQLite
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slug: String,
    val name: String,
    val description: String,
    val markdownBody: String,
    val parametersSchemaJson: String = """{"type":"object","properties":{}}""",
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val kind: String, // "WORKFLOW_JSON" (AI-driven multi-app) or "FLOW_YAML" (Fixed instant replay)
    val definitionText: String,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val runCount: Int = 0
)

@Serializable
@Entity(tableName = "triggers")
data class TriggerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerType: String, // TIME, NOTIFICATION, APP_OPEN, CHARGING, BATTERY_LOW, CONNECTIVITY
    val conditionValue: String, // e.g. "08:30", "com.whatsapp", "charging_on"
    val actionPrompt: String,
    val linkedFlowId: Long? = null,
    val enabled: Boolean = true,
    val lastTriggeredAt: Long? = null
)

@Serializable
@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // VOICE, AGENT_FAST, AGENT_GUI, SAFETY, TOOL, MODEL
    val title: String,
    val detail: String,
    val status: String, // SUCCESS, BLOCKED, FAILED, INFO
    val timestamp: Long = System.currentTimeMillis()
) {
    @androidx.room.Ignore
    constructor(category: String, title: String, detail: String, status: String) :
        this(0L, category, title, detail, status, System.currentTimeMillis())
}

@Serializable
@Entity(
    tableName = "discovered_tools",
    indices = [Index(value = ["identifier"], unique = true)]
)
data class DiscoveredToolEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String, // GITHUB or HUGGINGFACE
    val identifier: String, // owner/repo or modelId
    val name: String,
    val description: String,
    val starsOrDownloads: Int = 0,
    val lastUpdated: String = "",
    val license: String = "Unknown",
    val languageOrPipeline: String = "",
    val dependenciesSummary: String = "",
    val supportedAndroidMinSdk: Int? = null,
    val estimatedRamMb: Long = 0L,
    val estimatedStorageMb: Long = 0L,
    val batteryImpact: String = "Low",
    val compatibilityScore: Int = 0,
    val compatibilityReport: String = "",
    val securityFlags: String = "CLEAR",
    val isDuplicate: Boolean = false,
    val duplicateReason: String = "",
    val htmlUrl: String = "",
    val evaluatedAt: Long = System.currentTimeMillis(),
    // V4.2 structured scoring, strict pre-filter status, and implementation reports
    val fitScore: Double = 0.0,
    val statusTag: String = "Suggested", // Suggested, Pending Approval, Approved, Integrated, Rejected
    val implementationPlan: String = "",
    val hasModelCard: Boolean = true,
    val activelyMaintained: Boolean = true,
    val rejectionReason: String = ""
)

@Serializable
@Entity(tableName = "upgrade_proposals")
data class UpgradeProposalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val toolIdentifier: String,
    val title: String,
    val summary: String,
    val integrationPlan: String,
    val proposedFilesJson: String = "[]",
    val permissionsRequested: String = "",
    val riskLevel: String = "SENSITIVE", // LOW, SENSITIVE, BLOCKED
    val requiresApproval: Boolean = true,
    val status: String = "PENDING", // PENDING, APPROVED, REJECTED, STAGED, MERGED, ROLLED_BACK, FAILED
    val branchName: String = "",
    val prNumber: Int? = null,
    val prUrl: String = "",
    val commitSha: String = "",
    val lastBuildConclusion: String = "",
    val lastErrorReport: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // V4.2 additions
    val fitScore: Double = 0.0,
    val protectedPathsFlagged: String = "",
    val retryAttempts: Int = 0
)

@Serializable
@Entity(tableName = "dev_run_history")
data class AutoDevRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionType: String, // RESEARCH, BRANCH, COMMIT, PR, BUILD_POLL, MERGE, ROLLBACK, RELEASE_CHECK, ISSUE, SELF_REPAIR
    val branchOrRef: String = "",
    val httpStatus: Int = 0,
    val summary: String,
    val detail: String = "",
    val success: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "release_history",
    indices = [Index(value = ["tagName"], unique = true)]
)
data class ReleaseRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tagName: String,
    val versionName: String,
    val parsedVersionCode: Int? = null,
    val releaseName: String = "",
    val changelog: String = "",
    val apkAssetName: String = "",
    val apkDownloadUrl: String = "",
    val releaseHtmlUrl: String = "",
    val apkSizeBytes: Long = 0L,
    val sha256: String = "",
    val compatibleWithDevice: Boolean = true,
    val compatibilityNote: String = "",
    val publishedAt: String = "",
    val notified: Boolean = false,
    val detectedAt: Long = System.currentTimeMillis(),
    // V4.2 updater verification & installation history
    val isPrerelease: Boolean = false,
    val verifiedSha256: Boolean = false,
    val verifiedSigningCert: Boolean = false,
    val installedFromApp: Boolean = false,
    val installedAt: Long = 0L
)

@Serializable
@Entity(tableName = "upgrade_error_logs")
data class UpgradeErrorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String, // DISCOVERY, AUTO_DEV, UPDATER, SAFE_MODE, INSTALLER
    val message: String,
    val detail: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
