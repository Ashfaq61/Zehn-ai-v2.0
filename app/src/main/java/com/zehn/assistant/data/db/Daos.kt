package com.zehn.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT DISTINCT c.* FROM conversations c
        LEFT JOIN messages m ON c.id = m.conversationId
        WHERE c.title LIKE '%' || :query || '%' OR m.content LIKE '%' || :query || '%'
        ORDER BY c.updatedAt DESC
        """
    )
    fun search(query: String): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ConversationEntity): Long

    @Update
    suspend fun update(item: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<ConversationEntity>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getForConversationOnce(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 50")
    suspend fun searchMessages(query: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MessageEntity): Long

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<MessageEntity>
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY priority DESC, updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE tier = :tier ORDER BY priority DESC, updatedAt DESC")
    fun observeByTier(tier: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY priority DESC, updatedAt DESC")
    suspend fun getAllOnce(): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MemoryEntity): Long

    @Update
    suspend fun update(item: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun forgetById(id: Long)

    @Query("DELETE FROM memories WHERE tier = :tier")
    suspend fun clearTier(tier: String)
}

@Dao
interface ProjectRagDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Query("UPDATE projects SET documentCount = documentCount + 1 WHERE id = :projectId")
    suspend fun incrementDocCount(projectId: Long)

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<DocumentChunkEntity>)

    @Query("SELECT * FROM document_chunks WHERE projectId = :projectId")
    suspend fun getChunksForProject(projectId: Long): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks")
    suspend fun getAllChunks(): List<DocumentChunkEntity>
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY builtIn DESC, name ASC")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE enabled = 1")
    suspend fun getEnabledOnce(): List<SkillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: SkillEntity): Long

    @Update
    suspend fun update(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM skills")
    suspend fun getAllOnce(): List<SkillEntity>
}

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY id DESC")
    fun observeAll(): Flow<List<WorkflowEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(workflow: WorkflowEntity): Long

    @Update
    suspend fun update(workflow: WorkflowEntity)

    @Query("DELETE FROM workflows WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM workflows")
    suspend fun getAllOnce(): List<WorkflowEntity>
}

@Dao
interface TriggerDao {
    @Query("SELECT * FROM triggers ORDER BY id DESC")
    fun observeAll(): Flow<List<TriggerEntity>>

    @Query("SELECT * FROM triggers WHERE enabled = 1")
    suspend fun getEnabledOnce(): List<TriggerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trigger: TriggerEntity): Long

    @Update
    suspend fun update(trigger: TriggerEntity)

    @Query("UPDATE triggers SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE triggers SET lastTriggeredAt = :timestamp WHERE id = :id")
    suspend fun markTriggered(id: Long, timestamp: Long)

    @Query("DELETE FROM triggers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM triggers")
    suspend fun getAllOnce(): List<TriggerEntity>
}

@Dao
interface ActionLogDao {
    @Query("SELECT * FROM action_logs ORDER BY timestamp DESC LIMIT 300")
    fun observeRecent(): Flow<List<ActionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ActionLogEntity): Long

    @Query("DELETE FROM action_logs")
    suspend fun clearAll()
}

@Dao
interface UpgradeCenterDao {
    @Query("SELECT * FROM discovered_tools ORDER BY evaluatedAt DESC LIMIT 200")
    fun observeDiscoveredTools(): Flow<List<DiscoveredToolEntity>>

    @Query("SELECT * FROM discovered_tools WHERE identifier = :identifier LIMIT 1")
    suspend fun findDiscoveredTool(identifier: String): DiscoveredToolEntity?

    @Query("SELECT COUNT(*) FROM discovered_tools")
    suspend fun countDiscoveredTools(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiscoveredTool(tool: DiscoveredToolEntity): Long

    @Query("SELECT * FROM upgrade_proposals ORDER BY updatedAt DESC LIMIT 100")
    fun observeProposals(): Flow<List<UpgradeProposalEntity>>

    @Query("SELECT * FROM upgrade_proposals ORDER BY updatedAt DESC LIMIT 100")
    suspend fun getProposalsOnce(): List<UpgradeProposalEntity>

    @Query("SELECT * FROM upgrade_proposals WHERE id = :id LIMIT 1")
    suspend fun getProposalById(id: Long): UpgradeProposalEntity?

    @Query("SELECT * FROM upgrade_proposals WHERE toolIdentifier = :toolId LIMIT 1")
    suspend fun findProposalByTool(toolId: String): UpgradeProposalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProposal(proposal: UpgradeProposalEntity): Long

    @Query("UPDATE upgrade_proposals SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProposalStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM dev_run_history ORDER BY timestamp DESC LIMIT 200")
    fun observeDevRuns(): Flow<List<AutoDevRunEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevRun(run: AutoDevRunEntity): Long

    @Query("DELETE FROM dev_run_history")
    suspend fun clearDevRuns()

    @Query("SELECT * FROM release_history ORDER BY detectedAt DESC LIMIT 50")
    fun observeReleases(): Flow<List<ReleaseRecordEntity>>

    @Query("SELECT * FROM release_history WHERE tagName = :tagName LIMIT 1")
    suspend fun findReleaseByTag(tagName: String): ReleaseRecordEntity?

    @Query("SELECT * FROM release_history ORDER BY detectedAt DESC LIMIT 1")
    suspend fun getLatestReleaseOnce(): ReleaseRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelease(release: ReleaseRecordEntity): Long

    @Query("SELECT * FROM upgrade_error_logs ORDER BY timestamp DESC LIMIT 200")
    fun observeErrorLogs(): Flow<List<UpgradeErrorLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertErrorLog(log: UpgradeErrorLogEntity): Long

    @Query("DELETE FROM upgrade_error_logs")
    suspend fun clearErrorLogs()
}
