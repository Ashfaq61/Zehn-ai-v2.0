package com.zehn.assistant.upgrade

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.UpgradeCenterDao
import com.zehn.assistant.security.CrashRecoveryManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpgradeWorkerEntryPoint {
    fun preferencesRepository(): PreferencesRepository
    fun researchAgent(): AutonomousResearchAgent
    fun autoDevEngine(): GitHubAutoDevEngine
    fun releaseUpdateManager(): ReleaseUpdateManager
    fun upgradeCenterDao(): UpgradeCenterDao
    fun crashRecoveryManager(): CrashRecoveryManager
}

class ResearchScheduleWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, UpgradeWorkerEntryPoint::class.java)
        if (deps.crashRecoveryManager().safeModeActive.value) return Result.success()

        val prefs = deps.preferencesRepository()
        val settings = prefs.settings.first()
        if (settings.researchPaused || settings.allAutoUpgradesPaused) return Result.success()

        // Step 1: DISCOVER -> PRE-FILTER -> REPORT
        val summaryRes = deps.researchAgent().runFullResearchCycle(
            includeGitHub = true,
            includeHuggingFace = true
        )

        // Step 2: Only stage auto-approved proposals (fitScore >= 8 && autoApproveEnabled && no protected paths && max 2 open PRs)
        val autoApprovedIds = summaryRes.getOrNull()?.autoApprovedProposalIds.orEmpty()
        if (settings.autoDevEnabled && settings.autoApproveEnabled && autoApprovedIds.isNotEmpty()) {
            for (propId in autoApprovedIds.take(2)) {
                deps.autoDevEngine().executeIntegrationProposal(
                    proposalId = propId,
                    onBuildFailureNotify = { err ->
                        if (settings.notifyOnBuildFailure) {
                            deps.releaseUpdateManager().showBuildFailureNotification(err)
                        }
                    }
                )
            }
        }

        // Step 3: Check for verified published GitHub Releases
        if (settings.githubRepoSlug.contains('/')) {
            deps.releaseUpdateManager().checkForVerifiedReleaseUpdate(notifyIfNewer = true)
        }
        return Result.success()
    }
}

class ReleaseCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, UpgradeWorkerEntryPoint::class.java)
        if (deps.crashRecoveryManager().safeModeActive.value) return Result.success()
        val settings = deps.preferencesRepository().settings.first()
        if (settings.allAutoUpgradesPaused || !settings.githubRepoSlug.contains('/')) return Result.success()
        deps.releaseUpdateManager().checkForVerifiedReleaseUpdate(notifyIfNewer = true)
        return Result.success()
    }
}

@Singleton
class UpgradeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesRepository
) {
    suspend fun applySchedule(
        mode: String,
        focusMode: String,
        paused: Boolean,
        wifiOnlyDiscovery: Boolean = true
    ) {
        val intervalHours = when (mode) {
            "DAILY" -> 24L
            "WEEKLY" -> 24L * 7L
            else -> 0L
        }
        val nextTs = if (intervalHours > 0L && !paused) {
            System.currentTimeMillis() + TimeUnit.HOURS.toMillis(intervalHours)
        } else {
            0L
        }

        prefs.updateSettings {
            it.copy(
                researchScheduleMode = mode,
                researchFocusMode = focusMode,
                researchPaused = paused,
                discoveryWifiOnly = wifiOnlyDiscovery,
                nextScheduledResearchTimestamp = nextTs
            )
        }

        val wm = WorkManager.getInstance(context)
        if (paused || intervalHours == 0L) {
            wm.cancelUniqueWork(WORK_PERIODIC_RESEARCH)
        } else {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnlyDiscovery) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<ResearchScheduleWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_PERIODIC_RESEARCH, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        // Periodic verified GitHub Release watcher (every 4 hours when battery is not low)
        val releaseConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val releaseReq = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(4, TimeUnit.HOURS)
            .setConstraints(releaseConstraints)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC_RELEASE, ExistingPeriodicWorkPolicy.UPDATE, releaseReq)
    }

    suspend fun pauseAllWorkersImmediately() {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WORK_PERIODIC_RESEARCH)
        wm.cancelUniqueWork(WORK_PERIODIC_RELEASE)
    }

    fun triggerImmediateBackgroundResearch(wifiOnly: Boolean = true) {
        val req = OneTimeWorkRequestBuilder<ResearchScheduleWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }

    companion object {
        const val WORK_PERIODIC_RESEARCH = "zehn_periodic_research"
        const val WORK_PERIODIC_RELEASE = "zehn_periodic_release_check"
    }
}
