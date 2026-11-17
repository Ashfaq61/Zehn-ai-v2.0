package com.zehn.assistant.upgrade

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ApkInstallStatus(
    val state: String = "IDLE", // IDLE, INSTALLING, PENDING_USER_ACTION, SUCCEEDED, FAILED
    val message: String = "",
    val sessionId: Int = -1,
    val tagName: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

object InstallSessionTracker {
    private val _status = MutableStateFlow(ApkInstallStatus())
    val status: StateFlow<ApkInstallStatus> = _status.asStateFlow()

    fun update(next: ApkInstallStatus) {
        _status.value = next
    }
}

/**
 * Receives Android PackageInstaller session commit callbacks and truthfully handles
 * STATUS_PENDING_USER_ACTION, STATUS_SUCCESS, and failure codes.
 */
class PackageInstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val statusCode = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val statusMsg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val tagName = intent.getStringExtra(EXTRA_TAG_NAME).orEmpty()

        when (statusCode) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                InstallSessionTracker.update(
                    ApkInstallStatus(
                        state = "PENDING_USER_ACTION",
                        message = "Android system requires your confirmation tap to complete installing $tagName.",
                        sessionId = sessionId,
                        tagName = tagName
                    )
                )
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmIntent) }
                        .onFailure { err ->
                            Log.w(TAG, "Unable to launch package installer confirmation UI: ${err.message}")
                            InstallSessionTracker.update(
                                ApkInstallStatus(
                                    state = "FAILED",
                                    message = "Confirmation UI blocked by OS: ${err.message}",
                                    sessionId = sessionId,
                                    tagName = tagName
                                )
                            )
                        }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                cleanupCachedUpdateApks(context)
                InstallSessionTracker.update(
                    ApkInstallStatus(
                        state = "SUCCEEDED",
                        message = "Installed $tagName successfully via PackageInstaller.",
                        sessionId = sessionId,
                        tagName = tagName
                    )
                )
            }

            else -> {
                val reason = when (statusCode) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation aborted by user or system."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked by device policy."
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Signature or package conflict with existing install."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK is incompatible with this Android device/ABI."
                    PackageInstaller.STATUS_FAILURE_INVALID -> "Corrupt or invalid APK archive."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Insufficient storage space to install update."
                    else -> "Install failed (code $statusCode)"
                }
                val fullMsg = if (statusMsg.isNotBlank()) "$reason ($statusMsg)" else reason
                Log.w(TAG, fullMsg)
                InstallSessionTracker.update(
                    ApkInstallStatus(
                        state = "FAILED",
                        message = fullMsg,
                        sessionId = sessionId,
                        tagName = tagName
                    )
                )
            }
        }
    }

    private fun cleanupCachedUpdateApks(context: Context) {
        runCatching {
            File(context.cacheDir, "updates").listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".apk") || f.name.endsWith(".part"))) {
                    f.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "PackageInstallReceiver"
        const val EXTRA_TAG_NAME = "extra_tag_name"
    }
}
