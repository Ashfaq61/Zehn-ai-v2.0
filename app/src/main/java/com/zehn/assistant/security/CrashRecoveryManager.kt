package com.zehn.assistant.security

import android.content.Context
import com.zehn.assistant.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class CrashRecoveryReport(
    val exceptionType: String,
    val sanitizedMessage: String,
    val timestampMs: Long,
    val sanitizedStackTrace: String = "",
    val crashCountInWindow: Int = 1,
    val safeModeActive: Boolean = false
)

/**
 * Local privacy-preserving Crash Guard & Safe Mode manager.
 * - Captures uncaught crashes without leaking API keys or GitHub PATs.
 * - Tracks rolling crash timestamps in the post-update / active 5-minute window:
 *   if 3 or more crashes occur within 5 minutes, activates Safe Mode on next launch.
 */
@Singleton
class CrashRecoveryManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _lastCrashReport = MutableStateFlow(loadPendingReport())
    val lastCrashReport: StateFlow<CrashRecoveryReport?> = _lastCrashReport.asStateFlow()

    private val _safeModeActive = MutableStateFlow(prefs.getBoolean(KEY_SAFE_MODE, false))
    val safeModeActive: StateFlow<Boolean> = _safeModeActive.asStateFlow()

    private var installed = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun recordStartupVersion(currentVersionCode: Int = BuildConfig.VERSION_CODE, nowMs: Long = System.currentTimeMillis()) {
        val prevVer = prefs.getInt(KEY_LAST_VERSION_CODE, -1)
        if (prevVer != currentVersionCode) {
            prefs.edit()
                .putInt(KEY_LAST_VERSION_CODE, currentVersionCode)
                .putLong(KEY_LAST_UPDATE_TS, nowMs)
                .putString(KEY_CRASH_TIMESTAMPS, "")
                .commit()
        }
    }

    fun install() {
        if (installed) return
        installed = true
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordUncaughtCrash(throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun recordUncaughtCrash(throwable: Throwable, nowMs: Long = System.currentTimeMillis()) {
        val type = throwable::class.java.simpleName.ifBlank { "Exception" }
        val sanitizedMsg = sanitize(throwable.message ?: "Unexpected runtime error")
        val stackSnippet = sanitize(throwable.stackTraceToString(), maxLength = 3200)

        val windowStart = nowMs - CRASH_WINDOW_MS
        val existingTimestamps = prefs.getString(KEY_CRASH_TIMESTAMPS, "")
            .orEmpty()
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it >= windowStart && it <= nowMs }

        val updatedTimestamps = existingTimestamps + nowMs
        val triggerSafeMode = prefs.getBoolean(KEY_SAFE_MODE, false) ||
            updatedTimestamps.size >= SAFE_MODE_CRASH_THRESHOLD

        prefs.edit()
            .putBoolean(KEY_HAS_CRASH, true)
            .putString(KEY_TYPE, type)
            .putString(KEY_MESSAGE, sanitizedMsg)
            .putString(KEY_STACK_TRACE, stackSnippet)
            .putLong(KEY_TIMESTAMP, nowMs)
            .putString(KEY_CRASH_TIMESTAMPS, updatedTimestamps.joinToString(","))
            .putBoolean(KEY_SAFE_MODE, triggerSafeMode)
            .commit()

        _safeModeActive.value = triggerSafeMode
        _lastCrashReport.value = CrashRecoveryReport(
            exceptionType = type,
            sanitizedMessage = sanitizedMsg,
            timestampMs = nowMs,
            sanitizedStackTrace = stackSnippet,
            crashCountInWindow = updatedTimestamps.size,
            safeModeActive = triggerSafeMode
        )
    }

    fun dismissCrashReport() {
        prefs.edit().putBoolean(KEY_HAS_CRASH, false).commit()
        _lastCrashReport.value = null
    }

    fun exitSafeMode() {
        prefs.edit()
            .putBoolean(KEY_SAFE_MODE, false)
            .putBoolean(KEY_HAS_CRASH, false)
            .putString(KEY_CRASH_TIMESTAMPS, "")
            .commit()
        _safeModeActive.value = false
        _lastCrashReport.value = null
    }

    fun getLastUpdateTimestampMs(): Long = prefs.getLong(KEY_LAST_UPDATE_TS, 0L)

    fun getCrashTimestampsInWindow(nowMs: Long = System.currentTimeMillis()): List<Long> {
        val windowStart = nowMs - CRASH_WINDOW_MS
        return prefs.getString(KEY_CRASH_TIMESTAMPS, "")
            .orEmpty()
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it >= windowStart }
    }

    private fun loadPendingReport(): CrashRecoveryReport? {
        val safeMode = prefs.getBoolean(KEY_SAFE_MODE, false)
        if (!prefs.getBoolean(KEY_HAS_CRASH, false) && !safeMode) return null
        val type = prefs.getString(KEY_TYPE, "Exception") ?: "Exception"
        val msg = prefs.getString(KEY_MESSAGE, "Recovered from previous error") ?: "Recovered from previous error"
        val trace = prefs.getString(KEY_STACK_TRACE, "") ?: ""
        val ts = prefs.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        val count = getCrashTimestampsInWindow(ts.coerceAtLeast(System.currentTimeMillis())).size.coerceAtLeast(1)
        return CrashRecoveryReport(
            exceptionType = type,
            sanitizedMessage = msg,
            timestampMs = ts,
            sanitizedStackTrace = trace,
            crashCountInWindow = count,
            safeModeActive = safeMode
        )
    }

    internal fun sanitize(raw: String, maxLength: Int = 220): String {
        return raw
            .replace(Regex("""AIza[0-9A-Za-z\-_]{15,}"""), "[REDACTED_KEY]")
            .replace(Regex("""sk-[0-9A-Za-z\-_]{10,}"""), "[REDACTED_KEY]")
            .replace(Regex("""(ghp|gho|ghu|ghs|github_pat)_[0-9A-Za-z_]{8,}"""), "[REDACTED_TOKEN]")
            .replace(Regex("""(gsk|hf)_[0-9A-Za-z_\-]{6,}"""), "[REDACTED_KEY]")
            .replace(Regex("""Bearer\s+[0-9A-Za-z\-._~+/]+=*""", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
            .take(maxLength)
    }

    companion object {
        const val CRASH_WINDOW_MS = 5L * 60L * 1000L // 5 minutes
        const val SAFE_MODE_CRASH_THRESHOLD = 3

        private const val PREFS_NAME = "zehn_crash_recovery"
        private const val KEY_HAS_CRASH = "has_unacknowledged_crash"
        private const val KEY_TYPE = "crash_type"
        private const val KEY_MESSAGE = "crash_message"
        private const val KEY_STACK_TRACE = "crash_stack_trace"
        private const val KEY_TIMESTAMP = "crash_timestamp"
        private const val KEY_CRASH_TIMESTAMPS = "crash_timestamps_csv"
        private const val KEY_SAFE_MODE = "safe_mode_active"
        private const val KEY_LAST_VERSION_CODE = "last_version_code"
        private const val KEY_LAST_UPDATE_TS = "last_update_detected_ts"
    }
}
