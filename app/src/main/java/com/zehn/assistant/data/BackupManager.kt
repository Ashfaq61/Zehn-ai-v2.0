package com.zehn.assistant.data

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.zehn.assistant.BuildConfig
import com.zehn.assistant.data.db.ConversationEntity
import com.zehn.assistant.data.db.MemoryEntity
import com.zehn.assistant.data.db.MessageEntity
import com.zehn.assistant.data.db.SkillEntity
import com.zehn.assistant.data.db.TriggerEntity
import com.zehn.assistant.data.db.WorkflowEntity
import com.zehn.assistant.data.db.ZehnDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ZehnBackupPayload(
    val version: Int = 2,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val targetReleaseTag: String = "",
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: ZehnSettings,
    val memories: List<MemoryEntity>,
    val skills: List<SkillEntity>,
    val workflows: List<WorkflowEntity>,
    val triggers: List<TriggerEntity>,
    val conversations: List<ConversationEntity>,
    val messages: List<MessageEntity>
)

data class StorageBatteryTelemetry(
    val apkCacheBytes: Long = 0L,
    val roomDatabaseBytes: Long = 0L,
    val backupsBytes: Long = 0L,
    val localModelsBytes: Long = 0L,
    val backupCount: Int = 0,
    val latestBackupName: String = "",
    val ignoringBatteryOptimizations: Boolean = false
)

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ZehnDatabase,
    private val prefs: PreferencesRepository,
    private val json: Json
) {
    private val backupsDir: File
        get() = File(context.filesDir, "backups").apply { mkdirs() }

    suspend fun exportAllToJson(targetTag: String = ""): String = withContext(Dispatchers.IO) {
        val payload = ZehnBackupPayload(
            targetReleaseTag = targetTag,
            settings = prefs.settings.first(),
            memories = db.memoryDao().getAllOnce(),
            skills = db.skillDao().getAllOnce(),
            workflows = db.workflowDao().getAllOnce(),
            triggers = db.triggerDao().getAllOnce(),
            conversations = db.conversationDao().getAllOnce(),
            messages = db.messageDao().getAllOnce()
        )
        json.encodeToString(payload)
    }

    suspend fun importFromJson(rawJson: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = json.decodeFromString<ZehnBackupPayload>(rawJson)
            prefs.updateSettings { payload.settings }
            payload.memories.forEach { db.memoryDao().insert(it.copy(id = 0)) }
            payload.skills.forEach { db.skillDao().insert(it.copy(id = 0)) }
            payload.workflows.forEach { db.workflowDao().insert(it.copy(id = 0)) }
            payload.triggers.forEach { db.triggerDao().insert(it.copy(id = 0)) }
            val convMap = mutableMapOf<Long, Long>()
            payload.conversations.forEach { conv ->
                val newId = db.conversationDao().insert(conv.copy(id = 0))
                convMap[conv.id] = newId
            }
            payload.messages.forEach { msg ->
                val targetConv = convMap[msg.conversationId] ?: return@forEach
                db.messageDao().insert(msg.copy(id = 0, conversationId = targetConv))
            }
            payload.conversations.size + payload.memories.size + payload.skills.size
        }
    }

    /**
     * Automatically saves a full settings + memory + workflow snapshot before installing an update.
     */
    suspend fun createPreUpdateBackupSnapshot(targetReleaseTag: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val safeTag = targetReleaseTag.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "update" }
            val payloadJson = exportAllToJson(targetTag = targetReleaseTag)
            val file = File(backupsDir, "pre_update_v${BuildConfig.VERSION_CODE}_${safeTag}_${System.currentTimeMillis()}.json")
            file.writeText(payloadJson)
            // Keep at most 10 newest backup snapshots
            backupsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(10)
                ?.forEach { it.delete() }
            file
        }
    }

    fun listBackupSnapshots(): List<File> = backupsDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".json") }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    suspend fun restoreLatestPreUpdateBackup(): Result<Int> = withContext(Dispatchers.IO) {
        val latest = listBackupSnapshots().firstOrNull()
            ?: return@withContext Result.failure(IllegalStateException("No pre-update backup snapshot found yet."))
        importFromJson(latest.readText())
    }

    fun computeStorageBatteryTelemetry(): StorageBatteryTelemetry {
        val apkCacheDir = File(context.cacheDir, "updates")
        val modelsDir = File(context.filesDir, "litert_models")
        val dbFile = context.getDatabasePath("zehn_assistant.db")
        val backups = listBackupSnapshots()
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringOpt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
            runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
        } else {
            true
        }
        return StorageBatteryTelemetry(
            apkCacheBytes = dirSizeBytes(apkCacheDir),
            roomDatabaseBytes = (dbFile?.length() ?: 0L) +
                (context.getDatabasePath("zehn_assistant.db-wal")?.length() ?: 0L),
            backupsBytes = dirSizeBytes(backupsDir),
            localModelsBytes = dirSizeBytes(modelsDir),
            backupCount = backups.size,
            latestBackupName = backups.firstOrNull()?.name.orEmpty(),
            ignoringBatteryOptimizations = ignoringOpt
        )
    }

    private fun dirSizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
