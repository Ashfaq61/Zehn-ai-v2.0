package com.zehn.assistant.di

import android.content.Context
import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ConversationDao
import com.zehn.assistant.data.db.MemoryDao
import com.zehn.assistant.data.db.MessageDao
import com.zehn.assistant.data.db.ProjectRagDao
import com.zehn.assistant.data.db.SkillDao
import com.zehn.assistant.data.db.TriggerDao
import com.zehn.assistant.data.db.UpgradeCenterDao
import com.zehn.assistant.data.db.WorkflowDao
import com.zehn.assistant.data.db.ZehnDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
            redactHeader("x-api-key")
            redactHeader("x-goog-api-key")
        }
        return OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ZehnDatabase =
        ZehnDatabase.build(context)

    @Provides fun provideConversationDao(db: ZehnDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideMessageDao(db: ZehnDatabase): MessageDao = db.messageDao()
    @Provides fun provideMemoryDao(db: ZehnDatabase): MemoryDao = db.memoryDao()
    @Provides fun provideProjectRagDao(db: ZehnDatabase): ProjectRagDao = db.projectRagDao()
    @Provides fun provideSkillDao(db: ZehnDatabase): SkillDao = db.skillDao()
    @Provides fun provideWorkflowDao(db: ZehnDatabase): WorkflowDao = db.workflowDao()
    @Provides fun provideTriggerDao(db: ZehnDatabase): TriggerDao = db.triggerDao()
    @Provides fun provideActionLogDao(db: ZehnDatabase): ActionLogDao = db.actionLogDao()
    @Provides fun provideUpgradeCenterDao(db: ZehnDatabase): UpgradeCenterDao = db.upgradeCenterDao()
}
