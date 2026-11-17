package com.zehn.assistant.upgrade

import com.zehn.assistant.data.db.DiscoveredToolEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class LicenseEvaluation(
    val normalizedLicense: String,
    val status: String, // COMPATIBLE, INCOMPATIBLE
    val note: String
)

data class SecurityScanResult(
    val clean: Boolean,
    val blockingReasons: List<String>,
    val warnings: List<String>
)

data class IntegrationRiskAssessment(
    val riskLevel: String, // LOW, SENSITIVE, BLOCKED
    val requiresApproval: Boolean,
    val permissionsRequested: List<String>,
    val protectedPaths: List<String>,
    val summary: String
)

data class CommitFileChange(
    val path: String,
    val content: String,
    val isWorkflowFile: Boolean = path.startsWith(".github/workflows/")
)

/**
 * Evaluates license compatibility (strictly rejecting GPL, AGPL, non-commercial, and unknown),
 * active maintenance, min stars/downloads, Hugging Face model cards, RAM & Android compatibility,
 * duplicate overlap, 0-10 fit scoring, protected paths, and pre-commit secret/malware hygiene.
 */
@Singleton
class RepoSafetyAnalyzer @Inject constructor() {

    private val builtInCapabilities = mapOf(
        "camerax" to "Built-in CameraX + ML Kit OCR/QR (`CameraOcrSheet.kt`)",
        "mlkit" to "Built-in ML Kit text recognition & barcode scanning",
        "room" to "Built-in Room SQLite database (`ZehnDatabase.kt`)",
        "litert" to "Built-in LiteRT-LM on-device inference manager (`OnDeviceLiteRtManager.kt`)",
        "mediapipe" to "Built-in on-device LLM inference interface",
        "accessibilityservice" to "Built-in `ZehnAccessibilityService` GUI loop",
        "callscreening" to "Built-in `ZehnCallScreeningService` & `CallAssistantManager`",
        "gemini-live" to "Built-in `GeminiLiveAudioClient` WebSocket PCM client",
        "workmanager" to "Built-in Jetpack WorkManager scheduler",
        "keystore" to "Built-in AES-256-GCM `KeystoreCryptoManager`"
    )

    private val compatibleLicenses = setOf(
        "MIT", "APACHE-2.0", "BSD-3-CLAUSE", "BSD-2-CLAUSE",
        "ISC", "UNLICENSE", "0BSD", "CC0-1.0", "MPL-2.0"
    )

    fun evaluateLicense(spdxOrRaw: String, readmeText: String = ""): LicenseEvaluation {
        val raw = spdxOrRaw.trim().uppercase(Locale.US)
        val effective = when {
            raw.isNotBlank() && raw != "UNKNOWN" && raw != "NOASSERTION" && raw != "CHECK CARD" -> raw
            readmeText.contains("Apache-2.0", ignoreCase = true) || readmeText.contains("Apache License", ignoreCase = true) -> "APACHE-2.0"
            readmeText.contains("MIT License", ignoreCase = true) -> "MIT"
            readmeText.contains("BSD-3-Clause", ignoreCase = true) -> "BSD-3-CLAUSE"
            readmeText.contains("BSD-2-Clause", ignoreCase = true) -> "BSD-2-CLAUSE"
            readmeText.contains("AGPL", ignoreCase = true) -> "AGPL-3.0"
            readmeText.contains("GPL-3", ignoreCase = true) || readmeText.contains("GNU General Public License", ignoreCase = true) -> "GPL-3.0"
            readmeText.contains("non-commercial", ignoreCase = true) || readmeText.contains("cc-by-nc", ignoreCase = true) -> "NON-COMMERCIAL"
            else -> "UNKNOWN"
        }

        val isCopyleftOrRestricted =
            effective.contains("GPL") || // covers GPL-2.0, GPL-3.0, AGPL-3.0
                effective.contains("AGPL") ||
                effective.contains("SSPL") ||
                effective.contains("NON-COMMERCIAL") ||
                effective.contains("NC") && effective.startsWith("CC-") ||
                effective.contains("PROPRIETARY") ||
                effective == "UNKNOWN" ||
                effective == "NOASSERTION"

        return when {
            effective in compatibleLicenses && !isCopyleftOrRestricted -> LicenseEvaluation(
                effective,
                "COMPATIBLE",
                "Permissive commercial-friendly license ($effective)."
            )
            effective.contains("GPL") || effective.contains("AGPL") -> LicenseEvaluation(
                effective,
                "INCOMPATIBLE",
                "Rejected copyleft license ($effective): GPL/AGPL requires full source disclosure."
            )
            effective.contains("NON-COMMERCIAL") || (effective.contains("-NC") && effective.startsWith("CC-")) -> LicenseEvaluation(
                effective,
                "INCOMPATIBLE",
                "Rejected non-commercial license ($effective)."
            )
            else -> LicenseEvaluation(
                effective,
                "INCOMPATIBLE",
                "Rejected unknown or unverified license ('$effective')."
            )
        }
    }

    fun isActivelyMaintained(lastUpdated: String, referenceDate: LocalDate = LocalDate.now()): Boolean {
        val datePart = lastUpdated.trim().take(10)
        if (datePart.isBlank()) return true // Some minimal API payloads omit date; caught by other checks if dead
        val parsed = runCatching { LocalDate.parse(datePart) }.getOrNull() ?: return true
        val ageDays = ChronoUnit.DAYS.between(parsed, referenceDate)
        return ageDays <= 548L // Active within ~18 months
    }

    fun detectProtectedPaths(paths: List<String>, textHint: String = ""): List<String> {
        val hits = linkedSetOf<String>()
        val targets = paths + listOf(textHint)
        targets.forEach { raw ->
            val lower = raw.lowercase(Locale.US)
            if (lower.contains("androidmanifest") || lower.contains("<uses-permission") || lower.contains("uses-permission")) {
                hits.add("AndroidManifest Permissions")
            }
            if (lower.contains("touchguard") || lower.contains("security/") || lower.contains("safetypolicy")) {
                hits.add("Security & Touch Guard")
            }
            if (lower.contains("keystore") || lower.contains("token") || lower.contains("apikey") || lower.contains("api_keys")) {
                hits.add("Token & Keystore Handling")
            }
            if (lower.contains(".github/workflows")) {
                hits.add("GitHub Actions Workflows")
            }
            if (lower.contains("signingconfig") || lower.contains("debug.keystore") || lower.contains("build.gradle")) {
                hits.add("Signing & Gradle Build Config")
            }
        }
        return hits.toList()
    }

    fun scanTextSecurity(content: String, path: String = ""): SecurityScanResult {
        val blocking = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val secretPatterns = listOf(
            Regex("""AIza[0-9A-Za-z\-_]{15,}""") to "Potential Google/Gemini API key literal",
            Regex("""sk-[0-9A-Za-z\-_]{10,}""") to "Potential OpenAI secret key",
            Regex("""gsk_[0-9A-Za-z\-_]{10,}""") to "Potential Groq API key",
            Regex("""hf_[0-9A-Za-z\-_]{10,}""") to "Potential Hugging Face token",
            Regex("""(ghp|gho|ghu|ghs|github_pat)_[0-9A-Za-z_]{10,}""") to "Potential GitHub token"
        )
        secretPatterns.forEach { (regex, msg) ->
            if (regex.containsMatchIn(content)) {
                blocking.add("$msg found${if (path.isNotBlank()) " in $path" else ""}")
            }
        }

        val suspiciousPatterns = listOf(
            Regex("""DexClassLoader|PathClassLoader\s*\(.*http""") to "Dynamic external DEX/JAR loading (Play policy violation)",
            Regex("""Runtime\.getRuntime\(\)\.exec\s*\(\s*["'](su|sh|curl|wget)""") to "Suspicious shell/binary execution",
            Regex("""\.apk["']|\.dex["']|\.so["'].*https?://""") to "Unverified binary download pattern"
        )
        suspiciousPatterns.forEach { (regex, msg) ->
            if (regex.containsMatchIn(content)) {
                blocking.add(msg)
            }
        }

        val restrictedPerms = listOf(
            "REQUEST_INSTALL_PACKAGES",
            "MANAGE_EXTERNAL_STORAGE",
            "QUERY_ALL_PACKAGES",
            "READ_SMS",
            "PROCESS_OUTGOING_CALLS"
        )
        restrictedPerms.forEach { perm ->
            if (content.contains(perm)) {
                warnings.add("References Play-restricted permission: $perm")
            }
        }

        return SecurityScanResult(
            clean = blocking.isEmpty(),
            blockingReasons = blocking,
            warnings = warnings
        )
    }

    fun scanCommitDiff(
        changes: List<CommitFileChange>,
        allowWorkflowEdits: Boolean,
        maxFiles: Int = 10,
        maxBytesPerFile: Int = 512 * 1024
    ): SecurityScanResult {
        val blocking = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (changes.isEmpty()) {
            blocking.add("Commit contains no files.")
        }
        if (changes.size > maxFiles) {
            blocking.add("Commit exceeds file cap (${changes.size} > $maxFiles).")
        }

        changes.forEach { file ->
            if (file.path.contains("..") || file.path.startsWith("/")) {
                blocking.add("Invalid repository relative path: ${file.path}")
            }
            if ((file.isWorkflowFile || file.path.startsWith(".github/workflows")) && !allowWorkflowEdits) {
                blocking.add("Workflow edit blocked (${file.path}): 'Allow workflow edits' is OFF.")
            }
            if (file.content.toByteArray().size > maxBytesPerFile) {
                blocking.add("File exceeds 512 KB cap: ${file.path}")
            }
            val fileScan = scanTextSecurity(file.content, file.path)
            blocking.addAll(fileScan.blockingReasons)
            warnings.addAll(fileScan.warnings)
        }

        return SecurityScanResult(
            clean = blocking.isEmpty(),
            blockingReasons = blocking,
            warnings = warnings
        )
    }

    fun detectDuplicateFunctionality(name: String, description: String, dependencies: String): Pair<Boolean, String> {
        val haystack = "$name $description $dependencies".lowercase(Locale.US)
        val matched = builtInCapabilities.entries.firstOrNull { (key, _) ->
            haystack.contains(key)
        }
        return if (matched != null) {
            true to "Overlaps with ${matched.value}"
        } else {
            false to ""
        }
    }

    fun extractMinSdkFromGradle(gradleText: String): Int? {
        if (gradleText.isBlank()) return null
        val match = Regex("""minSdk(?:Version)?\s*[=\s]\s*(\d{2})""").find(gradleText)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun extractDependenciesFromGradleOrReadme(gradleText: String, readmeText: String): String {
        val deps = linkedSetOf<String>()
        Regex("""implementation\s*\(\s*["']([^"']+)["']\s*\)""").findAll(gradleText).take(12).forEach {
            deps.add(it.groupValues[1])
        }
        val combined = "$gradleText $readmeText".lowercase(Locale.US)
        listOf("kotlin", "compose", "okhttp", "retrofit", "room", "hilt", "workmanager", "onnx", "llamacpp", "vosk", "sherpa", "mcp", "litert", "silero")
            .forEach { kw -> if (combined.contains(kw)) deps.add(kw) }
        return if (deps.isEmpty()) "Standard Kotlin/HTTP" else deps.joinToString(", ")
    }

    fun evaluateTool(
        source: String,
        identifier: String,
        name: String,
        description: String,
        starsOrDownloads: Int,
        lastUpdated: String,
        rawLicense: String,
        languageOrPipeline: String,
        readmeText: String,
        gradleText: String,
        htmlUrl: String,
        deviceRamMb: Long,
        hasModelCard: Boolean = true
    ): DiscoveredToolEntity {
        val licenseEval = evaluateLicense(rawLicense, readmeText)
        val minSdk = extractMinSdkFromGradle(gradleText)
        val depsSummary = extractDependenciesFromGradleOrReadme(gradleText, readmeText)
        val (isDup, dupReason) = detectDuplicateFunctionality(name, description, depsSummary)
        val secScan = scanTextSecurity("$readmeText\n$gradleText", identifier)
        val maintained = isActivelyMaintained(lastUpdated)

        val estRamMb = estimateRamMb(identifier, description, depsSummary)
        val estStorageMb = estimateStorageMb(identifier, description, source)
        val batteryImpact = when {
            estRamMb >= 2500L || identifier.contains("video", true) || identifier.contains("whisper", true) -> "Medium-High (Foreground/On-Device DSP)"
            languageOrPipeline.equals("Kotlin", true) || depsSummary.contains("okhttp", true) -> "Low"
            else -> "Low-Medium"
        }

        // Strict pre-filter rejections BEFORE generating reports/proposals
        val rejectionReasons = mutableListOf<String>()
        if (licenseEval.status != "COMPATIBLE") {
            rejectionReasons.add(licenseEval.note)
        }
        if (source == "GITHUB" && starsOrDownloads < MIN_GITHUB_STARS) {
            rejectionReasons.add("Below minimum stars ($starsOrDownloads < $MIN_GITHUB_STARS)")
        }
        if (source == "HUGGINGFACE" && starsOrDownloads < MIN_HF_DOWNLOADS) {
            rejectionReasons.add("Below minimum downloads ($starsOrDownloads < $MIN_HF_DOWNLOADS)")
        }
        if (source == "HUGGINGFACE" && !hasModelCard) {
            rejectionReasons.add("Missing model card / documentation (tiny unverified fine-tune rejected)")
        }
        if (!maintained) {
            rejectionReasons.add("Stale or unmaintained (last updated $lastUpdated > 18 months ago)")
        }
        if (languageOrPipeline.equals("Swift", true) || languageOrPipeline.equals("Objective-C", true) ||
            (minSdk != null && minSdk > 35)
        ) {
            rejectionReasons.add("Incompatible with Android minSdk/runtime ($languageOrPipeline)")
        }
        val effectiveRamCeiling = maxOf((deviceRamMb * 0.70).toLong(), 2048L)
        if (estRamMb > effectiveRamCeiling) {
            rejectionReasons.add("Exceeds device RAM fit (~${estRamMb}MB > ~${effectiveRamCeiling}MB limit)")
        }
        if (!secScan.clean) {
            rejectionReasons.add("Security violation: ${secScan.blockingReasons.joinToString("; ")}")
        }

        // Multi-factor compatibility score (0..100 and 0..10)
        var score = 45
        if (licenseEval.status == "COMPATIBLE") score += 20 else score -= 40

        if (languageOrPipeline.equals("Kotlin", true) || languageOrPipeline.equals("Java", true) ||
            depsSummary.contains("kotlin", true) || depsSummary.contains("mcp", true) || source == "HUGGINGFACE"
        ) {
            score += 18
        }
        if (minSdk == null || minSdk <= 26) score += 8 else if (minSdk > 31) score -= 12
        if (deviceRamMb > 0L && estRamMb > deviceRamMb / 2) score -= 18
        if (isDup) score -= 10
        if (starsOrDownloads >= 50) score += 6
        if (starsOrDownloads >= 300) score += 4
        if (rejectionReasons.isNotEmpty()) score = score.coerceAtMost(35)
        if (!secScan.clean) score = 0

        val clampedScore = score.coerceIn(0, 100)
        val fitOutOf10 = ((clampedScore / 10.0) * 10.0).roundToInt() / 10.0

        val securitySummary = when {
            !secScan.clean -> "BLOCKED: ${secScan.blockingReasons.joinToString("; ")}"
            secScan.warnings.isNotEmpty() -> "WARNINGS: ${secScan.warnings.joinToString("; ")}"
            else -> "CLEAR"
        }

        val statusTag = when {
            rejectionReasons.isNotEmpty() -> "Rejected"
            fitOutOf10 < 7.0 -> "Suggested"
            else -> "Pending Approval"
        }

        val implPlan = if (rejectionReasons.isNotEmpty()) {
            "Rejected by pre-filter: ${rejectionReasons.joinToString("; ")}"
        } else {
            buildString {
                appendLine("1. Verify permissive license (${licenseEval.normalizedLicense}) & dependencies ($depsSummary).")
                appendLine("2. Wrap behind isolated adapter interface under upgrade/adapters/ (~${estRamMb}MB RAM, ~${estStorageMb}MB storage).")
                appendLine("3. Run Robolectric unit tests + Android Lint + GitHub Actions CI before opening PR to zehn-auto.")
            }.trim()
        }

        val detailedReport = buildString {
            appendLine("Summary: ${description.ifBlank { name }}")
            appendLine("Fit Score: $fitOutOf10/10 ($clampedScore/100) • Status: $statusTag")
            appendLine("License: ${licenseEval.normalizedLicense} (${licenseEval.note})")
            appendLine("Resources: Est RAM ~${estRamMb}MB, Storage ~${estStorageMb}MB, Battery: $batteryImpact")
            appendLine("Security: $securitySummary")
            if (isDup) appendLine("Overlap Note: $dupReason")
            if (rejectionReasons.isNotEmpty()) appendLine("Pre-Filter Rejections: ${rejectionReasons.joinToString("; ")}")
            appendLine("Plan: $implPlan")
        }.trim()

        return DiscoveredToolEntity(
            source = source,
            identifier = identifier,
            name = name,
            description = description.take(240),
            starsOrDownloads = starsOrDownloads,
            lastUpdated = lastUpdated,
            license = licenseEval.normalizedLicense,
            languageOrPipeline = languageOrPipeline,
            dependenciesSummary = depsSummary.take(220),
            supportedAndroidMinSdk = minSdk,
            estimatedRamMb = estRamMb,
            estimatedStorageMb = estStorageMb,
            batteryImpact = batteryImpact,
            compatibilityScore = clampedScore,
            compatibilityReport = detailedReport,
            securityFlags = securitySummary,
            isDuplicate = isDup,
            duplicateReason = dupReason,
            htmlUrl = htmlUrl,
            fitScore = fitOutOf10,
            statusTag = statusTag,
            implementationPlan = implPlan,
            hasModelCard = hasModelCard,
            activelyMaintained = maintained,
            rejectionReason = rejectionReasons.joinToString("; ")
        )
    }

    fun assessIntegrationRisk(tool: DiscoveredToolEntity, proposedPaths: List<String>): IntegrationRiskAssessment {
        if (tool.securityFlags.startsWith("BLOCKED") || tool.rejectionReason.isNotBlank() || tool.statusTag == "Rejected") {
            return IntegrationRiskAssessment(
                riskLevel = "BLOCKED",
                requiresApproval = true,
                permissionsRequested = emptyList(),
                protectedPaths = emptyList(),
                summary = "Blocked by pre-filter/security: ${tool.rejectionReason.ifBlank { tool.securityFlags }}."
            )
        }

        val combined = "${tool.name} ${tool.description} ${tool.dependenciesSummary} ${proposedPaths.joinToString(" ")}".lowercase(Locale.US)
        val requestedPerms = mutableListOf<String>()
        if (combined.contains("accessibility")) requestedPerms.add("BIND_ACCESSIBILITY_SERVICE")
        if (combined.contains("call") || combined.contains("telecom") || combined.contains("incall")) requestedPerms.add("READ_PHONE_STATE / ANSWER_PHONE_CALLS")
        if (combined.contains("sms")) requestedPerms.add("SEND_SMS")
        if (combined.contains("microphone") || combined.contains("audio") || combined.contains("speech")) requestedPerms.add("RECORD_AUDIO")
        if (combined.contains("camera") || combined.contains("vision") || combined.contains("ocr")) requestedPerms.add("CAMERA")

        val protectedHits = detectProtectedPaths(proposedPaths, combined)
        val touchesSensitive = requestedPerms.isNotEmpty() || protectedHits.isNotEmpty()

        return if (touchesSensitive) {
            IntegrationRiskAssessment(
                riskLevel = "SENSITIVE",
                requiresApproval = true,
                permissionsRequested = requestedPerms,
                protectedPaths = protectedHits,
                summary = "Protected area touched (${protectedHits.joinToString(", ").ifBlank { "Sensitive permissions" }}) — manual approval mandatory."
            )
        } else {
            IntegrationRiskAssessment(
                riskLevel = "LOW",
                requiresApproval = false,
                permissionsRequested = emptyList(),
                protectedPaths = emptyList(),
                summary = "Low-risk modular adapter suitable for Auto-Dev PR staging on zehn-auto."
            )
        }
    }

    private fun estimateRamMb(id: String, desc: String, deps: String): Long {
        val text = "$id $desc $deps".lowercase(Locale.US)
        return when {
            text.contains("70b") || text.contains("72b") -> 18_000L
            text.contains("7b") || text.contains("8b") || text.contains("9b") -> 6_000L
            text.contains("4b") || text.contains("e4b") -> 4_500L
            text.contains("2b") || text.contains("3b") || text.contains("whisper-large") -> 3_200L
            text.contains("0.6b") || text.contains("1b") || text.contains("sherpa") || text.contains("vosk") || text.contains("tts") -> 650L
            else -> 96L
        }
    }

    private fun estimateStorageMb(id: String, desc: String, source: String): Long {
        val text = "$id $desc".lowercase(Locale.US)
        return when {
            source == "HUGGINGFACE" && (text.contains("4b") || text.contains("7b")) -> 3_800L
            source == "HUGGINGFACE" && (text.contains("1b") || text.contains("2b") || text.contains("0.6b")) -> 1_100L
            source == "HUGGINGFACE" -> 350L
            text.contains("onnx") || text.contains("whisper") || text.contains("litert") -> 180L
            else -> 12L
        }
    }

    companion object {
        const val MIN_GITHUB_STARS = 15
        const val MIN_HF_DOWNLOADS = 50
    }
}
