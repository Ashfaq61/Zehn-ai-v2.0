package com.zehn.assistant.ai

import com.zehn.assistant.security.KeystoreCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubRepoSuggestion(
    val fullName: String,
    val description: String,
    val stars: Int,
    val license: String,
    val language: String,
    val updatedAt: String,
    val htmlUrl: String,
    val fitAssessment: String
)

data class HuggingFaceModelSuggestion(
    val modelId: String,
    val author: String,
    val pipelineTag: String,
    val downloads: Int,
    val likes: Int,
    val license: String,
    val languages: List<String>,
    val hardwareFit: String,
    val htmlUrl: String
)

/**
 * Read-only safe open-source discovery module: queries GitHub Search API and Hugging Face Models API
 * for metadata, license compatibility, popularity, and device RAM/hardware fit without downloading
 * or executing any external unverified code.
 */
@Singleton
class OpenSourceDiscoveryClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager,
    private val liteRtManager: OnDeviceLiteRtManager
) {
    suspend fun searchGitHubTools(query: String): Result<List<GitHubRepoSuggestion>> = withContext(Dispatchers.IO) {
        val clean = query.trim().ifBlank { "android voice assistant kotlin" }
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = "https://api.github.com/search/repositories?q=$encoded&sort=stars&order=desc&per_page=10"
        runCatching {
            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "MahiZehnAssistant/3.3")
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("GitHub Search HTTP ${resp.code}")
                }
                parseGitHubReposJson(resp.body?.string().orEmpty())
            }
        }
    }

    internal fun parseGitHubReposJson(json: String): List<GitHubRepoSuggestion> {
        val root = JSONObject(json)
        val items = root.optJSONArray("items") ?: return emptyList()
        val list = mutableListOf<GitHubRepoSuggestion>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val fullName = obj.optString("full_name", "")
            if (fullName.isBlank()) continue
            val license = obj.optJSONObject("license")?.optString("spdx_id", "Unknown").orEmpty().ifBlank { "Unknown" }
            val language = obj.optString("language", "Mixed").ifBlank { "Mixed" }
            val stars = obj.optInt("stargazers_count", 0)
            val updated = obj.optString("pushed_at", "").take(10)
            val fit = assessGitHubFit(language, license)
            list.add(
                GitHubRepoSuggestion(
                    fullName = fullName,
                    description = obj.optString("description", "No description").ifBlank { "No description" },
                    stars = stars,
                    license = license,
                    language = language,
                    updatedAt = updated,
                    htmlUrl = obj.optString("html_url", "https://github.com/$fullName"),
                    fitAssessment = fit
                )
            )
        }
        return list
    }

    private fun assessGitHubFit(language: String, license: String): String {
        val permissive = license.uppercase() in setOf("MIT", "APACHE-2.0", "BSD-3-CLAUSE", "BSD-2-CLAUSE", "ISC")
        val mobileFriendly = language.equals("Kotlin", true) || language.equals("Java", true) || language.equals("C++", true)
        return when {
            mobileFriendly && permissive -> "High Fit • Android-native language ($language) + permissive $license license"
            permissive -> "Reference Pattern • Permissive $license ($language) — review API design only"
            license.contains("GPL", true) -> "License Caution • $license may require source disclosure"
            else -> "Review Manually • License: $license ($language)"
        }
    }

    suspend fun searchHuggingFaceModels(query: String): Result<List<HuggingFaceModelSuggestion>> = withContext(Dispatchers.IO) {
        val clean = query.trim().ifBlank { "gemma" }
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = "https://huggingface.co/api/models?search=$encoded&sort=downloads&direction=-1&limit=10"
        runCatching {
            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .get()
            val hfToken = crypto.getSecret(KeystoreCryptoManager.KEY_HF_TOKEN).trim()
            if (hfToken.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $hfToken")
            }
            val totalRamMb = liteRtManager.state.value.totalRamMb
            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("Hugging Face API HTTP ${resp.code}")
                }
                parseHuggingFaceModelsJson(resp.body?.string().orEmpty(), totalRamMb)
            }
        }
    }

    internal fun parseHuggingFaceModelsJson(json: String, deviceRamMb: Long): List<HuggingFaceModelSuggestion> {
        val arr = JSONArray(json)
        val out = mutableListOf<HuggingFaceModelSuggestion>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val modelId = obj.optString("id", obj.optString("modelId", "")).trim()
            if (modelId.isBlank()) continue
            val author = modelId.substringBefore("/", "huggingface")
            val tags = obj.optJSONArray("tags")
            var license = "Check model card"
            val langs = mutableListOf<String>()
            if (tags != null) {
                for (t in 0 until tags.length()) {
                    val tag = tags.optString(t)
                    if (tag.startsWith("license:")) {
                        license = tag.removePrefix("license:")
                    } else if (tag in setOf("en", "ur", "hi", "multilingual")) {
                        langs.add(tag.uppercase())
                    }
                }
            }
            val hardwareFit = assessModelRamFit(modelId, deviceRamMb)
            out.add(
                HuggingFaceModelSuggestion(
                    modelId = modelId,
                    author = author,
                    pipelineTag = obj.optString("pipeline_tag", "text-generation").ifBlank { "text-generation" },
                    downloads = obj.optInt("downloads", 0),
                    likes = obj.optInt("likes", 0),
                    license = license,
                    languages = langs.ifEmpty { listOf("Check Card") },
                    hardwareFit = hardwareFit,
                    htmlUrl = "https://huggingface.co/$modelId"
                )
            )
        }
        return out
    }

    internal fun assessModelRamFit(modelId: String, deviceRamMb: Long): String {
        val lower = modelId.lowercase()
        val estMinRam = when {
            lower.contains("70b") || lower.contains("72b") || lower.contains("32b") || lower.contains("27b") -> 18_000L
            lower.contains("7b") || lower.contains("8b") || lower.contains("9b") -> 6_000L
            lower.contains("e4b") || lower.contains("4b") -> 4_500L
            lower.contains("e2b") || lower.contains("2b") || lower.contains("3b") -> 3_400L
            lower.contains("0.6b") || lower.contains("1b") || lower.contains("tiny") || lower.contains("mini") -> 2_200L
            else -> 4_000L
        }
        return when {
            estMinRam >= 16_000L -> "Cloud Inference Only (Too large for mobile RAM; use HF Inference Router)"
            deviceRamMb <= 0L -> "Estimated Min RAM ~${estMinRam} MB"
            deviceRamMb >= estMinRam -> "Device Compatible (${deviceRamMb} MB RAM >= ~${estMinRam} MB est.)"
            else -> "Cloud Recommended (${deviceRamMb} MB RAM < ~${estMinRam} MB est.)"
        }
    }
}
