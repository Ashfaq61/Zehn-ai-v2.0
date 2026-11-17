package com.zehn.assistant.upgrade

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zehn.assistant.BuildConfig
import com.zehn.assistant.R
import com.zehn.assistant.ZehnApplication
import com.zehn.assistant.data.BackupManager
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.AutoDevRunEntity
import com.zehn.assistant.data.db.ReleaseRecordEntity
import com.zehn.assistant.data.db.UpgradeCenterDao
import com.zehn.assistant.data.db.UpgradeErrorLogEntity
import com.zehn.assistant.security.KeystoreCryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateCheckSummary(
    val latestRelease: ReleaseRecordEntity? = null,
    val updateAvailable: Boolean = false,
    val statusMessage: String = ""
)

data class ApkDownloadProgress(
    val active: Boolean = false,
    val tagName: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val downloadedApkPath: String = "",
    val verifiedSha256: Boolean = false,
    val verifiedSigningCert: Boolean = false,
    val statusMessage: String = ""
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class InstallLaunchResult(
    val launched: Boolean = false,
    val needsUnknownSourcesPermission: Boolean = false,
    val message: String = ""
)

/**
 * In-App Self-Updater for Zehn AI (`com.zehn.assistant`):
 * - Checks GitHub Releases (`latest` for Stable, `/releases` including pre-releases for Beta)
 * - Supports private repositories via encrypted GitHub PAT (`Accept: application/octet-stream`)
 * - Strict monotonic upward `versionCode` check (never downgrades)
 * - Resumable HTTP `Range` download into `cacheDir/updates/` with metered/Wi-Fi policy & data counter
 * - Mandatory SHA-256 checksum & APK signing certificate match verification before install
 * - Automatic pre-update backup snapshot via BackupManager
 * - Installs via Android PackageInstaller Session API (`USER_ACTION_NOT_REQUIRED` on Android 12+ where allowed,
 *   honestly handling OS confirmation prompts & failures)
 */
@Singleton
class ReleaseUpdateManager private constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager,
    private val prefs: PreferencesRepository,
    private val upgradeDao: UpgradeCenterDao,
    private val backupManager: BackupManager?,
    @Suppress("UNUSED_PARAMETER") internalMarker: Int
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        crypto: KeystoreCryptoManager,
        prefs: PreferencesRepository,
        upgradeDao: UpgradeCenterDao,
        backupManager: BackupManager
    ) : this(context, okHttpClient, crypto, prefs, upgradeDao, backupManager, 0)

    constructor(
        context: Context,
        okHttpClient: OkHttpClient,
        crypto: KeystoreCryptoManager,
        prefs: PreferencesRepository,
        upgradeDao: UpgradeCenterDao
    ) : this(context, okHttpClient, crypto, prefs, upgradeDao, null, 0)

    private val _downloadProgress = MutableStateFlow(ApkDownloadProgress())
    val downloadProgress: StateFlow<ApkDownloadProgress> = _downloadProgress.asStateFlow()

    suspend fun checkForVerifiedReleaseUpdate(notifyIfNewer: Boolean = true): Result<UpdateCheckSummary> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val repoSlug = settings.githubRepoSlug.trim().trim('/')
        if (!repoSlug.contains('/')) {
            return@withContext Result.failure(IllegalStateException("Configure GitHub 'owner/repo' to check releases."))
        }

        val includePrereleases = settings.releaseChannel.equals("BETA", ignoreCase = true)
        val url = if (includePrereleases) {
            "https://api.github.com/repos/$repoSlug/releases?per_page=10"
        } else {
            "https://api.github.com/repos/$repoSlug/releases/latest"
        }
        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()

        val req = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "ZehnAI-ReleaseWatcher/4.2")
            .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
            .get()
            .build()

        runCatching {
            val respPair = okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && !includePrereleases && resp.code == 404) {
                    null
                } else if (!resp.isSuccessful) {
                    error("GitHub Releases HTTP ${resp.code}")
                } else {
                    resp.code to resp.body?.string().orEmpty()
                }
            } ?: run {
                val fallbackReq = req.newBuilder()
                    .url("https://api.github.com/repos/$repoSlug/releases?per_page=10")
                    .build()
                okHttpClient.newCall(fallbackReq).execute().use { resp ->
                    if (!resp.isSuccessful) error("GitHub Releases HTTP ${resp.code}")
                    resp.code to resp.body?.string().orEmpty()
                }
            }

            val (httpCode, rawBody) = respPair
            val parsed = parseReleasesJson(
                json = rawBody,
                installedMinSdk = 24,
                deviceSdk = Build.VERSION.SDK_INT,
                includePrereleases = includePrereleases
            )

            // Resolve remote .sha256 assets if any release has sha256 referencing a remote asset URL
            val enriched = parsed.map { rel ->
                val resolvedSha = resolveSha256IfRemote(rel.sha256, token)
                rel.copy(sha256 = resolvedSha)
            }

            for (rel in enriched) {
                val existing = upgradeDao.findReleaseByTag(rel.tagName)
                upgradeDao.upsertRelease(
                    if (existing != null) {
                        rel.copy(
                            id = existing.id,
                            notified = existing.notified,
                            verifiedSha256 = existing.verifiedSha256,
                            verifiedSigningCert = existing.verifiedSigningCert,
                            installedFromApp = existing.installedFromApp,
                            installedAt = existing.installedAt
                        )
                    } else {
                        rel
                    }
                )
            }

            val newest = enriched.firstOrNull()
            if (newest == null) {
                upgradeDao.insertDevRun(
                    AutoDevRunEntity(
                        actionType = "RELEASE_CHECK",
                        branchOrRef = repoSlug,
                        httpStatus = httpCode,
                        summary = "No published releases found for ${settings.releaseChannel} channel",
                        detail = "",
                        success = true
                    )
                )
                return@runCatching UpdateCheckSummary(null, false, "No published GitHub Releases found for $repoSlug (${settings.releaseChannel}).")
            }

            val newer = isCandidateStrictlyNewer(
                candidateVersionCode = newest.parsedVersionCode,
                candidateVersionName = newest.versionName,
                installedVersionCode = BuildConfig.VERSION_CODE,
                installedVersionName = BuildConfig.VERSION_NAME
            ) && newest.compatibleWithDevice

            val existingNewest = upgradeDao.findReleaseByTag(newest.tagName)
            if (newer && notifyIfNewer && settings.notifyOnUpdateReady && existingNewest?.notified != true) {
                showUpdateReadyNotification(newest)
                upgradeDao.upsertRelease((existingNewest ?: newest).copy(notified = true))
            }

            upgradeDao.insertDevRun(
                AutoDevRunEntity(
                    actionType = "RELEASE_CHECK",
                    branchOrRef = newest.tagName,
                    httpStatus = httpCode,
                    summary = if (newer) "Verified update ${newest.versionName} ready" else "Up to date (v${BuildConfig.VERSION_NAME}, code ${BuildConfig.VERSION_CODE})",
                    detail = newest.apkDownloadUrl.ifBlank { newest.releaseHtmlUrl },
                    success = true
                )
            )

            UpdateCheckSummary(
                latestRelease = newest,
                updateAvailable = newer,
                statusMessage = if (newer) {
                    "Update ${newest.tagName} (${formatBytes(newest.apkSizeBytes)}) available on ${settings.releaseChannel} channel."
                } else {
                    "Up to date: v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}). Latest: ${newest.tagName}."
                }
            )
        }.onFailure { err ->
            recordError("UPDATER", "Release check failed", err.message ?: "Network error")
        }
    }

    /**
     * Downloads an APK with HTTP Range resume into cacheDir/updates/, verifies SHA-256 and
     * signing certificate match against installed app, and stores verification flags in Room.
     */
    suspend fun downloadAndVerifyReleaseApk(
        release: ReleaseRecordEntity,
        bypassWifiOnly: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        if (!bypassWifiOnly && settings.updaterWifiOnly && !isUnmeteredNetworkAvailable()) {
            val msg = "Updater Wi-Fi-only mode is ON and no unmetered Wi-Fi connection is active."
            _downloadProgress.value = ApkDownloadProgress(statusMessage = msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        if (!isCandidateStrictlyNewer(release.parsedVersionCode, release.versionName, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)) {
            val msg = "Downgrade blocked: release version (${release.tagName}, code=${release.parsedVersionCode}) is not newer than installed code ${BuildConfig.VERSION_CODE}."
            recordError("UPDATER", "Downgrade blocked", msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val dlUrl = release.apkDownloadUrl.trim()
        if (!isTrustedGitHubUrl(dlUrl)) {
            val msg = "Blocked untrusted download URL."
            recordError("UPDATER", msg, dlUrl)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeName = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val partFile = File(updatesDir, "$safeName.apk.part")
        val finalFile = File(updatesDir, "$safeName.apk")

        val token = crypto.getSecret(KeystoreCryptoManager.KEY_GITHUB_TOKEN).trim()
        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        _downloadProgress.value = ApkDownloadProgress(
            active = true,
            tagName = release.tagName,
            downloadedBytes = existingBytes,
            totalBytes = release.apkSizeBytes,
            statusMessage = if (existingBytes > 0) "Resuming download (${formatBytes(existingBytes)})..." else "Downloading ${release.tagName}..."
        )

        val isGitHubApiAsset = dlUrl.startsWith("https://api.github.com/")
        val reqBuilder = Request.Builder()
            .url(dlUrl)
            .addHeader("User-Agent", "ZehnAI-Updater/4.2")
            .addHeader("Accept", if (isGitHubApiAsset) "application/octet-stream" else "*/*")
        if (existingBytes > 0L) {
            reqBuilder.addHeader("Range", "bytes=$existingBytes-")
        }
        if (token.isNotBlank() && (isGitHubApiAsset || dlUrl.startsWith("https://github.com/"))) {
            reqBuilder.addHeader("Authorization", "Bearer $token")
        }

        val downloadRes = runCatching {
            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 416) {
                    error("Download HTTP ${resp.code}")
                }
                if (resp.code == 416 && partFile.exists() && partFile.length() > 0L) {
                    partFile.renameTo(finalFile)
                    return@use finalFile
                }

                val isResume = resp.code == 206 && existingBytes > 0L
                val body = resp.body ?: error("Empty APK response body")
                val expectedTotal = if (isResume) {
                    existingBytes + body.contentLength().coerceAtLeast(0L)
                } else {
                    if (body.contentLength() > 0L) body.contentLength() else release.apkSizeBytes
                }

                var writtenSession = 0L
                var currentTotalWritten = if (isResume) existingBytes else 0L

                FileOutputStream(partFile, isResume).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            writtenSession += read
                            currentTotalWritten += read
                            if (writtenSession % (128 * 1024) < 16 * 1024) {
                                _downloadProgress.value = ApkDownloadProgress(
                                    active = true,
                                    tagName = release.tagName,
                                    downloadedBytes = currentTotalWritten,
                                    totalBytes = expectedTotal,
                                    statusMessage = "Downloading ${release.tagName}: ${formatBytes(currentTotalWritten)}"
                                )
                            }
                        }
                    }
                }

                if (writtenSession > 0L) {
                    prefs.updateSettings { it.copy(updaterBytesDownloaded = it.updaterBytesDownloaded + writtenSession) }
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                finalFile
            }
        }

        if (downloadRes.isFailure) {
            val errMsg = downloadRes.exceptionOrNull()?.message ?: "Download error"
            recordError("UPDATER", "APK download failed", errMsg)
            _downloadProgress.value = ApkDownloadProgress(active = false, tagName = release.tagName, statusMessage = "Download failed: $errMsg")
            return@withContext Result.failure(downloadRes.exceptionOrNull()!!)
        }

        val apkFile = downloadRes.getOrThrow()

        // 1. Verify SHA-256 checksum
        val actualSha = computeSha256Hex(apkFile)
        val expectedSha = release.sha256.trim().lowercase(Locale.US)
        if (expectedSha.isNotBlank() && !expectedSha.startsWith("http") && !actualSha.equals(expectedSha, ignoreCase = true)) {
            apkFile.delete()
            val msg = "SHA-256 verification FAILED for ${release.tagName}. Expected $expectedSha, got $actualSha. File deleted."
            recordError("UPDATER", "SHA-256 mismatch", msg)
            _downloadProgress.value = ApkDownloadProgress(active = false, tagName = release.tagName, statusMessage = msg)
            return@withContext Result.failure(SecurityException(msg))
        }
        val shaVerified = expectedSha.isNotBlank() && actualSha.equals(expectedSha, ignoreCase = true)

        // 2. Verify APK signing certificate matches currently installed Zehn AI certificate
        val certVerified = verifyDownloadedApkSigningCertificate(apkFile)
        if (!certVerified) {
            apkFile.delete()
            val msg = "Signing certificate verification FAILED for ${release.tagName}. APK signature does not match installed Zehn AI key."
            recordError("UPDATER", "Signing cert mismatch", msg)
            _downloadProgress.value = ApkDownloadProgress(active = false, tagName = release.tagName, statusMessage = msg)
            return@withContext Result.failure(SecurityException(msg))
        }

        val existing = upgradeDao.findReleaseByTag(release.tagName) ?: release
        upgradeDao.upsertRelease(
            existing.copy(
                sha256 = actualSha,
                verifiedSha256 = shaVerified || expectedSha.isBlank(),
                verifiedSigningCert = true
            )
        )

        _downloadProgress.value = ApkDownloadProgress(
            active = false,
            tagName = release.tagName,
            downloadedBytes = apkFile.length(),
            totalBytes = apkFile.length(),
            downloadedApkPath = apkFile.absolutePath,
            verifiedSha256 = shaVerified,
            verifiedSigningCert = true,
            statusMessage = "Verified ${release.tagName} (${if (shaVerified) "SHA-256 & Signing Cert OK" else "Signing Cert OK"}). Ready to install."
        )
        Result.success(apkFile)
    }

    /**
     * Creates an automatic BackupManager snapshot, verifies install-packages permission,
     * and launches Android's PackageInstaller Session API.
     */
    suspend fun installVerifiedApk(release: ReleaseRecordEntity, apkFile: File): InstallLaunchResult = withContext(Dispatchers.IO) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return@withContext InstallLaunchResult(false, false, "Downloaded APK file is missing.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            return@withContext InstallLaunchResult(
                launched = false,
                needsUnknownSourcesPermission = true,
                message = "Allow 'Install unknown apps' for Zehn AI in Android Settings first."
            )
        }

        // Re-verify certificate right before session open
        if (!verifyDownloadedApkSigningCertificate(apkFile)) {
            return@withContext InstallLaunchResult(false, false, "Refused install: signing certificate does not match installed Zehn AI.")
        }

        // Pre-update automatic snapshot
        backupManager?.createPreUpdateBackupSnapshot(release.tagName)

        return@withContext runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apkFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                    FileInputStream(apkFile).use { input ->
                        input.copyTo(out)
                    }
                    session.fsync(out)
                }

                val callbackIntent = Intent(context, PackageInstallStatusReceiver::class.java).apply {
                    `package` = context.packageName
                    putExtra(PackageInstallStatusReceiver.EXTRA_TAG_NAME, release.tagName)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val statusPendingIntent = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)

                InstallSessionTracker.update(
                    ApkInstallStatus(
                        state = "INSTALLING",
                        message = "Submitting ${release.tagName} to Android PackageInstaller...",
                        sessionId = sessionId,
                        tagName = release.tagName
                    )
                )
                session.commit(statusPendingIntent.intentSender)
            }

            val existing = upgradeDao.findReleaseByTag(release.tagName) ?: release
            upgradeDao.upsertRelease(
                existing.copy(installedFromApp = true, installedAt = System.currentTimeMillis())
            )

            InstallLaunchResult(
                launched = true,
                needsUnknownSourcesPermission = false,
                message = "Update ${release.tagName} committed to PackageInstaller (backup saved)."
            )
        }.getOrElse { err ->
            val msg = "PackageInstaller failed: ${err.message}"
            recordError("INSTALLER", msg)
            InstallSessionTracker.update(ApkInstallStatus(state = "FAILED", message = msg, tagName = release.tagName))
            InstallLaunchResult(false, false, msg)
        }
    }

    fun buildManageUnknownSourcesIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
    }

    internal fun verifyDownloadedApkSigningCertificate(apkFile: File): Boolean {
        val pm = context.packageManager
        val installedDigests = getPackageSigningDigests(pm, context.packageName, isArchive = false)
        val archiveDigests = getPackageSigningDigests(pm, apkFile.absolutePath, isArchive = true)
        if (installedDigests.isEmpty() || archiveDigests.isEmpty()) {
            return false
        }
        return archiveDigests.any { it in installedDigests }
    }

    internal fun verifyCertDigestsMatch(installedDigests: Set<String>, candidateDigests: Set<String>): Boolean {
        if (installedDigests.isEmpty() || candidateDigests.isEmpty()) return false
        return candidateDigests.any { it in installedDigests }
    }

    @Suppress("DEPRECATION")
    private fun getPackageSigningDigests(pm: PackageManager, packageOrPath: String, isArchive: Boolean): Set<String> {
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                if (isArchive) pm.getPackageArchiveInfo(packageOrPath, flags) else pm.getPackageInfo(packageOrPath, flags)
            } else {
                val flags = PackageManager.GET_SIGNATURES
                if (isArchive) pm.getPackageArchiveInfo(packageOrPath, flags) else pm.getPackageInfo(packageOrPath, flags)
            } ?: return emptySet()

            val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val si = info.signingInfo ?: return emptySet()
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } else {
                info.signatures ?: emptyArray()
            }
            signatures.map { sig -> sha256Hex(sig.toByteArray()) }.toSet()
        }.getOrDefault(emptySet())
    }

    internal fun computeSha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    internal fun parseReleasesJson(
        json: String,
        installedMinSdk: Int,
        deviceSdk: Int,
        includePrereleases: Boolean = false
    ): List<ReleaseRecordEntity> {
        val trimmed = json.trim()
        val arr = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else if (trimmed.startsWith("{")) {
            JSONArray().put(JSONObject(trimmed))
        } else {
            return emptyList()
        }

        val list = mutableListOf<ReleaseRecordEntity>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            val isPre = obj.optBoolean("prerelease", false)
            if (isPre && !includePrereleases) continue

            val tag = obj.optString("tag_name", "").trim()
            if (tag.isBlank()) continue
            val verName = tag.removePrefix("v").removePrefix("V").trim()
            val htmlUrl = obj.optString("html_url", "")
            val bodyText = obj.optString("body", "No release notes provided.")
            val assets = obj.optJSONArray("assets")

            var apkName = ""
            var apkUrl = ""
            var apkSize = 0L
            var shaAssetOrHex = extractSha256FromBody(bodyText)

            if (assets != null) {
                for (a in 0 until assets.length()) {
                    val asset = assets.optJSONObject(a) ?: continue
                    val name = asset.optString("name", "")
                    val browserUrl = asset.optString("browser_download_url", "")
                    val apiAssetUrl = asset.optString("url", "")
                    val preferredUrl = browserUrl.takeIf { isTrustedGitHubUrl(it) }
                        ?: apiAssetUrl.takeIf { isTrustedGitHubUrl(it) }
                        ?: ""

                    if (name.endsWith(".apk", ignoreCase = true) && preferredUrl.isNotBlank()) {
                        apkName = name
                        apkUrl = preferredUrl
                        apkSize = asset.optLong("size", 0L)
                        if (name.contains("release", ignoreCase = true) || name.contains("zehn", ignoreCase = true)) {
                            // Prefer main app APK if multiple APKs exist
                        }
                    } else if ((name.endsWith(".sha256", ignoreCase = true) || name.contains("checksum", ignoreCase = true)) &&
                        preferredUrl.isNotBlank() && shaAssetOrHex.isBlank()
                    ) {
                        shaAssetOrHex = preferredUrl
                    }
                }
            }

            val compat = deviceSdk >= installedMinSdk && isTrustedGitHubUrl(htmlUrl.ifBlank { apkUrl })
            val compatNote = if (compat) "Compatible with Android API $deviceSdk (>= minSdk $installedMinSdk)" else "Blocked: untrusted URL or Android API mismatch"

            list.add(
                ReleaseRecordEntity(
                    tagName = tag,
                    versionName = verName,
                    parsedVersionCode = extractVersionCodeHint(bodyText, verName),
                    releaseName = obj.optString("name", tag),
                    changelog = bodyText.take(2500),
                    apkAssetName = apkName,
                    apkDownloadUrl = apkUrl,
                    releaseHtmlUrl = htmlUrl,
                    apkSizeBytes = apkSize,
                    sha256 = shaAssetOrHex,
                    compatibleWithDevice = compat,
                    compatibilityNote = compatNote,
                    publishedAt = obj.optString("published_at", "").take(10),
                    isPrerelease = isPre
                )
            )
        }
        return list
    }

    private fun resolveSha256IfRemote(shaOrUrl: String, token: String): String {
        if (!shaOrUrl.startsWith("https://")) return shaOrUrl
        return runCatching {
            val isApi = shaOrUrl.startsWith("https://api.github.com/")
            val req = Request.Builder()
                .url(shaOrUrl)
                .addHeader("User-Agent", "ZehnAI-ReleaseWatcher/4.2")
                .addHeader("Accept", if (isApi) "application/octet-stream" else "*/*")
                .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val text = resp.body?.string().orEmpty()
                Regex("""([0-9a-fA-F]{64})""").find(text)?.groupValues?.getOrNull(1)?.lowercase(Locale.US).orEmpty()
            }
        }.getOrDefault("")
    }

    internal fun isTrustedGitHubUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return url.startsWith("https://github.com/") ||
            url.startsWith("https://api.github.com/") ||
            url.startsWith("https://objects.githubusercontent.com/") ||
            url.startsWith("https://release-assets.githubusercontent.com/")
    }

    internal fun isCandidateStrictlyNewer(
        candidateVersionCode: Int?,
        candidateVersionName: String,
        installedVersionCode: Int,
        installedVersionName: String
    ): Boolean {
        if (candidateVersionCode != null && candidateVersionCode > installedVersionCode) {
            return true
        }
        if (candidateVersionCode != null && candidateVersionCode <= installedVersionCode) {
            return false
        }
        return isVersionNewer(candidateVersionName, installedVersionName)
    }

    internal fun isVersionNewer(candidate: String, current: String): Boolean {
        val candParts = candidate.split(".", "-", "+").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val currParts = current.split(".", "-", "+").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val maxLen = maxOf(candParts.size, currParts.size)
        for (i in 0 until maxLen) {
            val c = candParts.getOrElse(i) { 0 }
            val r = currParts.getOrElse(i) { 0 }
            if (c > r) return true
            if (c < r) return false
        }
        return false
    }

    private fun extractSha256FromBody(body: String): String {
        return Regex("""sha256[:=\s]+([0-9a-fA-F]{64})""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.lowercase(Locale.US).orEmpty()
    }

    private fun extractVersionCodeHint(body: String, verName: String): Int? {
        Regex("""versionCode[:=\s]+(\d+)""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val digits = verName.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        return if (digits.size >= 3) digits.last() else null
    }

    private fun isUnmeteredNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun openVerifiedUpdateInBrowser(release: ReleaseRecordEntity): Boolean {
        val targetUrl = release.apkDownloadUrl.takeIf { isTrustedGitHubUrl(it) }
            ?: release.releaseHtmlUrl.takeIf { isTrustedGitHubUrl(it) }
            ?: return false
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun showUpdateReadyNotification(release: ReleaseRecordEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val targetUrl = release.apkDownloadUrl.ifBlank { release.releaseHtmlUrl }
        if (!isTrustedGitHubUrl(targetUrl)) return

        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context,
            release.tagName.hashCode(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sizeLabel = if (release.apkSizeBytes > 0) " • ${formatBytes(release.apkSizeBytes)}" else ""
        val notif = NotificationCompat.Builder(context, ZehnApplication.CHANNEL_UPDATES)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Zehn AI ${release.tagName} Ready$sizeLabel")
            .setContentText("Open Upgrade Center -> Updater to verify SHA-256 & install.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${release.changelog.take(260)}\n\nAPK: ${release.apkAssetName.ifBlank { "GitHub Release" }}$sizeLabel"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "View Release", pi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_UPDATE, notif)
        }
    }

    fun showBuildFailureNotification(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notif = NotificationCompat.Builder(context, ZehnApplication.CHANNEL_UPDATES)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Zehn Auto-Dev Build Failed")
            .setContentText(message.take(120))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_BUILD_FAIL, notif)
        }
    }

    private suspend fun recordError(source: String, msg: String, detail: String = "") {
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

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "APK"
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    companion object {
        const val NOTIF_ID_UPDATE = 4001
        const val NOTIF_ID_BUILD_FAIL = 4002
    }
}
