package com.zehn.assistant.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.data.model.AiProvider
import com.zehn.assistant.security.KeystoreCryptoManager
import com.zehn.assistant.security.SafetyPolicyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

data class LlmMessage(
    val role: String,
    val content: String
)

data class LlmToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String
)

data class LlmToolCall(
    val name: String,
    val argumentsJson: String
)

data class ApiProviderUsage(
    val provider: AiProvider,
    val requests: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val lastLatencyMs: Long = 0L,
    val lastHttpCode: Int = 0,
    val lastStatusText: String = "Untested"
)

data class ProviderConnectionTestResult(
    val provider: AiProvider,
    val connected: Boolean,
    val httpCode: Int,
    val latencyMs: Long,
    val message: String
)

sealed class LlmStreamEvent {
    data class TextDelta(val delta: String) : LlmStreamEvent()
    data class ToolCallEvent(val call: LlmToolCall) : LlmStreamEvent()
    data class ErrorEvent(val message: String, val code: Int = 0) : LlmStreamEvent()
    data object Completed : LlmStreamEvent()
}

@Singleton
class CloudLlmClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager,
    private val safetyPolicy: SafetyPolicyManager,
    private val actionLogDao: ActionLogDao
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val _providerUsage = MutableStateFlow<Map<AiProvider, ApiProviderUsage>>(emptyMap())
    val providerUsage: StateFlow<Map<AiProvider, ApiProviderUsage>> = _providerUsage.asStateFlow()

    private fun recordUsage(provider: AiProvider, success: Boolean, httpCode: Int, latencyMs: Long, statusText: String) {
        _providerUsage.update { current ->
            val prev = current[provider] ?: ApiProviderUsage(provider)
            val updated = prev.copy(
                requests = prev.requests + 1,
                successes = prev.successes + if (success) 1 else 0,
                failures = prev.failures + if (success) 0 else 1,
                lastLatencyMs = latencyMs,
                lastHttpCode = httpCode,
                lastStatusText = sanitizeError(statusText).take(90)
            )
            current + (provider to updated)
        }
    }

    fun streamChat(
        provider: AiProvider,
        model: String,
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition> = emptyList(),
        imageUri: Uri? = null
    ): Flow<LlmStreamEvent> = flow {
        val base64Image = imageUri?.let { encodeUriToJpegBase64(it) }

        when (provider) {
            AiProvider.GEMINI -> streamGeminiWithRotation(model, systemPrompt, messages, tools, base64Image)
                .collect { emit(it) }

            AiProvider.ANTHROPIC -> streamAnthropic(model, systemPrompt, messages, base64Image)
                .collect { emit(it) }

            AiProvider.COHERE -> callCohere(model, systemPrompt, messages)
                .collect { emit(it) }

            AiProvider.WEBHOOK -> callWebhook(messages.lastOrNull()?.content.orEmpty())
                .collect { emit(it) }

            AiProvider.OPENAI_COMPATIBLE,
            AiProvider.GROQ,
            AiProvider.OPENROUTER,
            AiProvider.TOGETHER,
            AiProvider.HUGGINGFACE,
            AiProvider.OLLAMA -> streamOpenAiCompatible(provider, model, systemPrompt, messages, tools, base64Image)
                .collect { emit(it) }

            AiProvider.ON_DEVICE_LITERT -> {
                emit(LlmStreamEvent.ErrorEvent("Route ON_DEVICE_LITERT to OnDeviceLiteRtManager"))
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun testProviderConnection(provider: AiProvider): ProviderConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            when (provider) {
                AiProvider.ON_DEVICE_LITERT -> {
                    return@withContext ProviderConnectionTestResult(
                        provider = provider,
                        connected = true,
                        httpCode = 200,
                        latencyMs = 0L,
                        message = "On-device runtime uses local storage & RAM (no cloud key needed)."
                    )
                }

                AiProvider.GEMINI -> {
                    val keys = crypto.getGeminiKeys()
                    if (keys.isEmpty()) {
                        recordUsage(provider, false, 401, 0L, "No key stored")
                        return@withContext ProviderConnectionTestResult(provider, false, 401, 0L, "No Gemini API key stored in Vault.")
                    }
                    val (_, rawKey) = crypto.nextGeminiKey() ?: return@withContext ProviderConnectionTestResult(provider, false, 401, 0L, "Empty Gemini key.")
                    val req = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=25")
                        .addHeader("x-goog-api-key", rawKey.trim())
                        .get()
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        val ms = System.currentTimeMillis() - start
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val count = runCatching { JSONObject(body).optJSONArray("models")?.length() ?: 0 }.getOrDefault(0)
                            val msg = "Connected (${ms}ms) • $count Gemini models accessible"
                            recordUsage(provider, true, resp.code, ms, msg)
                            ProviderConnectionTestResult(provider, true, resp.code, ms, msg)
                        } else {
                            val msg = "HTTP ${resp.code}: ${sanitizeError(body).take(120)}"
                            recordUsage(provider, false, resp.code, ms, msg)
                            ProviderConnectionTestResult(provider, false, resp.code, ms, msg)
                        }
                    }
                }

                AiProvider.OPENAI_COMPATIBLE,
                AiProvider.GROQ,
                AiProvider.OPENROUTER,
                AiProvider.TOGETHER,
                AiProvider.HUGGINGFACE,
                AiProvider.OLLAMA -> {
                    val key = secretForOpenAiCompatible(provider)
                    if (provider.requiresKey && key.isBlank()) {
                        recordUsage(provider, false, 401, 0L, "Missing API key")
                        return@withContext ProviderConnectionTestResult(provider, false, 401, 0L, "Missing API key for ${provider.displayName}.")
                    }
                    val baseUrl = baseUrlForOpenAiCompatible(provider)
                    val testUrl = if (provider == AiProvider.OLLAMA) {
                        "${baseUrl.removeSuffix("/v1")}/api/tags"
                    } else {
                        "$baseUrl/models"
                    }
                    val reqBuilder = Request.Builder().url(testUrl).get()
                    if (key.isNotBlank() && provider != AiProvider.OLLAMA) {
                        reqBuilder.addHeader("Authorization", "Bearer $key")
                    }
                    okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        val ms = System.currentTimeMillis() - start
                        val ok = resp.isSuccessful
                        val msg = if (ok) {
                            "Connected (${ms}ms) • HTTP ${resp.code}"
                        } else {
                            "HTTP ${resp.code}: ${sanitizeError(resp.body?.string().orEmpty()).take(100)}"
                        }
                        recordUsage(provider, ok, resp.code, ms, msg)
                        ProviderConnectionTestResult(provider, ok, resp.code, ms, msg)
                    }
                }

                AiProvider.ANTHROPIC -> {
                    val key = crypto.getSecret(KeystoreCryptoManager.KEY_ANTHROPIC).trim()
                    if (key.isBlank()) {
                        recordUsage(provider, false, 401, 0L, "Missing API key")
                        return@withContext ProviderConnectionTestResult(provider, false, 401, 0L, "Missing Anthropic API key.")
                    }
                    val req = Request.Builder()
                        .url("https://api.anthropic.com/v1/models")
                        .addHeader("x-api-key", key)
                        .addHeader("anthropic-version", "2023-06-01")
                        .get()
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        val ms = System.currentTimeMillis() - start
                        val ok = resp.isSuccessful
                        val msg = if (ok) "Connected (${ms}ms)" else "HTTP ${resp.code}"
                        recordUsage(provider, ok, resp.code, ms, msg)
                        ProviderConnectionTestResult(provider, ok, resp.code, ms, msg)
                    }
                }

                AiProvider.COHERE -> {
                    val key = crypto.getSecret(KeystoreCryptoManager.KEY_COHERE).trim()
                    if (key.isBlank()) {
                        recordUsage(provider, false, 401, 0L, "Missing API key")
                        return@withContext ProviderConnectionTestResult(provider, false, 401, 0L, "Missing Cohere API key.")
                    }
                    val req = Request.Builder()
                        .url("https://api.cohere.com/v1/models")
                        .addHeader("Authorization", "Bearer $key")
                        .get()
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        val ms = System.currentTimeMillis() - start
                        val ok = resp.isSuccessful
                        val msg = if (ok) "Connected (${ms}ms)" else "HTTP ${resp.code}"
                        recordUsage(provider, ok, resp.code, ms, msg)
                        ProviderConnectionTestResult(provider, ok, resp.code, ms, msg)
                    }
                }

                AiProvider.WEBHOOK -> {
                    val url = crypto.getSecret(KeystoreCryptoManager.KEY_WEBHOOK_URL).trim()
                    if (url.isBlank()) {
                        return@withContext ProviderConnectionTestResult(provider, false, 400, 0L, "Webhook URL is empty.")
                    }
                    val req = Request.Builder().url(url).head().build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        val ms = System.currentTimeMillis() - start
                        val ok = resp.code in 200..405
                        val msg = "Reachable (${ms}ms) • HTTP ${resp.code}"
                        recordUsage(provider, ok, resp.code, ms, msg)
                        ProviderConnectionTestResult(provider, ok, resp.code, ms, msg)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - start
            val msg = sanitizeError(e.message ?: "Connection error")
            recordUsage(provider, false, 0, ms, msg)
            ProviderConnectionTestResult(provider, false, 0, ms, msg)
        }
    }

    private fun streamGeminiWithRotation(
        model: String,
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition>,
        base64Image: String?
    ): Flow<LlmStreamEvent> = flow {
        val totalKeys = crypto.getGeminiKeys().size
        if (totalKeys == 0) {
            recordUsage(AiProvider.GEMINI, false, 401, 0L, "No key stored")
            emit(LlmStreamEvent.ErrorEvent("No Gemini API key stored in Encrypted Vault.", 401))
            return@flow
        }

        var attempt = 0
        var succeeded = false
        var lastError = "Gemini request failed"
        var lastCode = 500
        val startMs = System.currentTimeMillis()

        while (attempt < totalKeys.coerceAtLeast(1) && !succeeded) {
            val pair = crypto.nextGeminiKey() ?: break
            val (keyIdx, rawApiKey) = pair
            val apiKey = rawApiKey.trim()
            if (apiKey.isEmpty()) {
                lastCode = 401
                lastError = "Gemini API key #${keyIdx + 1} is empty."
                attempt++
                continue
            }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse"

            val payload = JSONObject().apply {
                if (systemPrompt.isNotBlank()) {
                    put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                }
                val contents = JSONArray()
                messages.forEachIndexed { idx, msg ->
                    val role = if (msg.role == "assistant") "model" else "user"
                    val parts = JSONArray().put(JSONObject().put("text", msg.content))
                    if (idx == messages.lastIndex && base64Image != null && role == "user") {
                        parts.put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject().put("mime_type", "image/jpeg").put("data", base64Image)
                            )
                        )
                    }
                    contents.put(JSONObject().put("role", role).put("parts", parts))
                }
                put("contents", contents)
                if (tools.isNotEmpty()) {
                    val decls = JSONArray()
                    tools.forEach { t ->
                        decls.put(
                            JSONObject().apply {
                                put("name", t.name)
                                put("description", t.description)
                                runCatching { JSONObject(t.parametersJson) }.getOrNull()?.let {
                                    put("parameters", it)
                                }
                            }
                        )
                    }
                    put("tools", JSONArray().put(JSONObject().put("function_declarations", decls)))
                }
            }

            val req = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            try {
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 429 || resp.code == 403) {
                        lastCode = resp.code
                        lastError = "Gemini quota/rate limit (HTTP ${resp.code}) on key #${keyIdx + 1}"
                        val rotated = crypto.markGeminiKeyQuotaLimited(keyIdx)
                        actionLogDao.insert(
                            ActionLogEntity(
                                category = "MODEL",
                                title = "Gemini Key Rotation",
                                detail = "$lastError; rotated=$rotated",
                                status = if (rotated) "INFO" else "FAILED"
                            )
                        )
                        attempt++
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        lastCode = resp.code
                        lastError = "Gemini HTTP ${resp.code}: ${sanitizeError(resp.body?.string().orEmpty()).take(200)}"
                        attempt = totalKeys
                        return@use
                    }

                    val body = resp.body ?: run {
                        lastError = "Empty Gemini response stream"
                        return@use
                    }
                    val reader = BufferedReader(InputStreamReader(body.byteStream()))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (safetyPolicy.policy.value.emergencyStopped) break
                        val raw = line?.trim().orEmpty()
                        if (!raw.startsWith("data:")) continue
                        val jsonStr = raw.removePrefix("data:").trim()
                        if (jsonStr == "[DONE]" || jsonStr.isEmpty()) continue
                        runCatching {
                            val obj = JSONObject(jsonStr)
                            val candidates = obj.optJSONArray("candidates") ?: return@runCatching
                            if (candidates.length() == 0) return@runCatching
                            val parts = candidates.getJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts") ?: return@runCatching
                            for (i in 0 until parts.length()) {
                                val p = parts.getJSONObject(i)
                                val text = p.optString("text", "")
                                if (text.isNotEmpty()) emit(LlmStreamEvent.TextDelta(text))
                                val fc = p.optJSONObject("functionCall")
                                if (fc != null) {
                                    emit(
                                        LlmStreamEvent.ToolCallEvent(
                                            LlmToolCall(
                                                name = fc.optString("name"),
                                                argumentsJson = fc.optJSONObject("args")?.toString() ?: "{}"
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    }
                    succeeded = true
                    recordUsage(AiProvider.GEMINI, true, resp.code, System.currentTimeMillis() - startMs, "OK ($model)")
                    emit(LlmStreamEvent.Completed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastCode = 0
                lastError = "Gemini network error: ${sanitizeError(e.message ?: "Request failed")}"
                attempt = totalKeys
            }
        }

        if (!succeeded) {
            recordUsage(AiProvider.GEMINI, false, lastCode, System.currentTimeMillis() - startMs, lastError)
            emit(LlmStreamEvent.ErrorEvent(lastError, lastCode))
        }
    }

    private fun secretForOpenAiCompatible(provider: AiProvider): String = when (provider) {
        AiProvider.OPENAI_COMPATIBLE -> crypto.getSecret(KeystoreCryptoManager.KEY_OPENAI)
        AiProvider.GROQ -> crypto.getSecret(KeystoreCryptoManager.KEY_GROQ)
        AiProvider.OPENROUTER -> crypto.getSecret(KeystoreCryptoManager.KEY_OPENROUTER)
        AiProvider.TOGETHER -> crypto.getSecret(KeystoreCryptoManager.KEY_TOGETHER)
        AiProvider.HUGGINGFACE -> crypto.getSecret(KeystoreCryptoManager.KEY_HF_TOKEN)
        AiProvider.OLLAMA -> "ollama"
        else -> ""
    }.trim()

    private fun baseUrlForOpenAiCompatible(provider: AiProvider): String = when (provider) {
        AiProvider.OPENAI_COMPATIBLE -> crypto.getSecret(KeystoreCryptoManager.KEY_OPENAI_URL).ifBlank { provider.defaultBaseUrl }
        AiProvider.OLLAMA -> crypto.getSecret(KeystoreCryptoManager.KEY_OLLAMA_URL).ifBlank { provider.defaultBaseUrl }
        else -> provider.defaultBaseUrl
    }.trimEnd('/')

    private fun streamOpenAiCompatible(
        provider: AiProvider,
        model: String,
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition>,
        base64Image: String?
    ): Flow<LlmStreamEvent> = flow {
        val apiKey = secretForOpenAiCompatible(provider)
        if (provider.requiresKey && apiKey.isBlank()) {
            recordUsage(provider, false, 401, 0L, "Missing API key")
            emit(LlmStreamEvent.ErrorEvent("Missing API key for ${provider.displayName}", 401))
            return@flow
        }

        val baseUrl = baseUrlForOpenAiCompatible(provider)
        val startMs = System.currentTimeMillis()

        val msgArray = JSONArray().apply {
            if (systemPrompt.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            messages.forEachIndexed { idx, msg ->
                if (idx == messages.lastIndex && base64Image != null && msg.role == "user" && provider.supportsVision) {
                    val contentArr = JSONArray()
                        .put(JSONObject().put("type", "text").put("text", msg.content))
                        .put(
                            JSONObject().put("type", "image_url").put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64,$base64Image")
                            )
                        )
                    put(JSONObject().put("role", msg.role).put("content", contentArr))
                } else {
                    put(JSONObject().put("role", msg.role).put("content", msg.content))
                }
            }
        }

        val bodyJson = JSONObject().apply {
            put("model", model)
            put("messages", msgArray)
            put("stream", true)
            if (tools.isNotEmpty() && provider.supportsTools) {
                val toolsArr = JSONArray()
                tools.forEach { t ->
                    toolsArr.put(
                        JSONObject().put("type", "function").put(
                            "function",
                            JSONObject().apply {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", runCatching { JSONObject(t.parametersJson) }.getOrElse { JSONObject() })
                            }
                        )
                    )
                }
                put("tools", toolsArr)
            }
        }

        try {
            val reqBuilder = Request.Builder()
                .url("$baseUrl/chat/completions")
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = "${provider.displayName} HTTP ${resp.code}: ${sanitizeError(resp.body?.string().orEmpty()).take(180)}"
                    recordUsage(provider, false, resp.code, System.currentTimeMillis() - startMs, msg)
                    emit(LlmStreamEvent.ErrorEvent(msg, resp.code))
                    return@use
                }
                val body = resp.body ?: run {
                    recordUsage(provider, false, resp.code, System.currentTimeMillis() - startMs, "Empty stream")
                    emit(LlmStreamEvent.ErrorEvent("Empty stream"))
                    return@use
                }
                val reader = BufferedReader(InputStreamReader(body.byteStream()))
                var pendingToolName = ""
                val pendingToolArgs = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (safetyPolicy.policy.value.emergencyStopped) break
                    val raw = line?.trim().orEmpty()
                    if (!raw.startsWith("data:")) continue
                    val data = raw.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    runCatching {
                        val obj = JSONObject(data)
                        val choices = obj.optJSONArray("choices") ?: return@runCatching
                        if (choices.length() == 0) return@runCatching
                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return@runCatching
                        val text = delta.optString("content", "")
                        if (text.isNotEmpty()) emit(LlmStreamEvent.TextDelta(text))
                        val tcs = delta.optJSONArray("tool_calls")
                        if (tcs != null && tcs.length() > 0) {
                            val fn = tcs.getJSONObject(0).optJSONObject("function")
                            if (fn != null) {
                                val n = fn.optString("name", "")
                                if (n.isNotEmpty()) pendingToolName = n
                                val argsChunk = fn.optString("arguments", "")
                                if (argsChunk.isNotEmpty()) pendingToolArgs.append(argsChunk)
                            }
                        }
                    }
                }
                if (pendingToolName.isNotEmpty()) {
                    emit(LlmStreamEvent.ToolCallEvent(LlmToolCall(pendingToolName, pendingToolArgs.toString().ifBlank { "{}" })))
                }
                recordUsage(provider, true, resp.code, System.currentTimeMillis() - startMs, "OK ($model)")
                emit(LlmStreamEvent.Completed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "${provider.displayName} error: ${sanitizeError(e.message ?: "Request failed")}"
            recordUsage(provider, false, 0, System.currentTimeMillis() - startMs, msg)
            emit(LlmStreamEvent.ErrorEvent(msg))
        }
    }

    private fun streamAnthropic(
        model: String,
        systemPrompt: String,
        messages: List<LlmMessage>,
        base64Image: String?
    ): Flow<LlmStreamEvent> = flow {
        val apiKey = crypto.getSecret(KeystoreCryptoManager.KEY_ANTHROPIC).trim()
        if (apiKey.isBlank()) {
            recordUsage(AiProvider.ANTHROPIC, false, 401, 0L, "Missing API key")
            emit(LlmStreamEvent.ErrorEvent("Missing Anthropic API key", 401))
            return@flow
        }
        val startMs = System.currentTimeMillis()
        val msgArr = JSONArray()
        messages.forEachIndexed { idx, m ->
            val role = if (m.role == "assistant") "assistant" else "user"
            if (idx == messages.lastIndex && base64Image != null && role == "user") {
                val arr = JSONArray()
                    .put(
                        JSONObject().put("type", "image").put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", "image/jpeg")
                                .put("data", base64Image)
                        )
                    )
                    .put(JSONObject().put("type", "text").put("text", m.content))
                msgArr.put(JSONObject().put("role", role).put("content", arr))
            } else {
                msgArr.put(JSONObject().put("role", role).put("content", m.content))
            }
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 2048)
            put("stream", true)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", msgArr)
        }
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = "Anthropic HTTP ${resp.code}: ${sanitizeError(resp.body?.string().orEmpty()).take(180)}"
                    recordUsage(AiProvider.ANTHROPIC, false, resp.code, System.currentTimeMillis() - startMs, msg)
                    emit(LlmStreamEvent.ErrorEvent(msg, resp.code))
                    return@use
                }
                val body = resp.body ?: run {
                    emit(LlmStreamEvent.ErrorEvent("Empty Anthropic stream"))
                    return@use
                }
                val reader = BufferedReader(InputStreamReader(body.byteStream()))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (safetyPolicy.policy.value.emergencyStopped) break
                    val raw = line?.trim().orEmpty()
                    if (!raw.startsWith("data:")) continue
                    val data = raw.removePrefix("data:").trim()
                    runCatching {
                        val obj = JSONObject(data)
                        if (obj.optString("type") == "content_block_delta") {
                            val delta = obj.optJSONObject("delta")?.optString("text", "").orEmpty()
                            if (delta.isNotEmpty()) emit(LlmStreamEvent.TextDelta(delta))
                        }
                    }
                }
                recordUsage(AiProvider.ANTHROPIC, true, resp.code, System.currentTimeMillis() - startMs, "OK ($model)")
                emit(LlmStreamEvent.Completed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Anthropic error: ${sanitizeError(e.message ?: "Request failed")}"
            recordUsage(AiProvider.ANTHROPIC, false, 0, System.currentTimeMillis() - startMs, msg)
            emit(LlmStreamEvent.ErrorEvent(msg))
        }
    }

    private fun callCohere(
        model: String,
        systemPrompt: String,
        messages: List<LlmMessage>
    ): Flow<LlmStreamEvent> = flow {
        val apiKey = crypto.getSecret(KeystoreCryptoManager.KEY_COHERE).trim()
        if (apiKey.isBlank()) {
            recordUsage(AiProvider.COHERE, false, 401, 0L, "Missing API key")
            emit(LlmStreamEvent.ErrorEvent("Missing Cohere API key", 401))
            return@flow
        }
        val startMs = System.currentTimeMillis()
        val msgArr = JSONArray().apply {
            if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
            messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", msgArr)
        }
        val req = Request.Builder()
            .url("https://api.cohere.com/v2/chat")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordUsage(AiProvider.COHERE, false, resp.code, System.currentTimeMillis() - startMs, "HTTP ${resp.code}")
                    emit(LlmStreamEvent.ErrorEvent("Cohere HTTP ${resp.code}", resp.code))
                    return@use
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                val content = obj.optJSONObject("message")
                    ?.optJSONArray("content")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    .orEmpty()
                if (content.isNotEmpty()) emit(LlmStreamEvent.TextDelta(content))
                recordUsage(AiProvider.COHERE, true, resp.code, System.currentTimeMillis() - startMs, "OK ($model)")
                emit(LlmStreamEvent.Completed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Cohere error: ${sanitizeError(e.message ?: "Request failed")}"
            recordUsage(AiProvider.COHERE, false, 0, System.currentTimeMillis() - startMs, msg)
            emit(LlmStreamEvent.ErrorEvent(msg))
        }
    }

    private fun callWebhook(text: String): Flow<LlmStreamEvent> = flow {
        val url = crypto.getSecret(KeystoreCryptoManager.KEY_WEBHOOK_URL).trim()
        if (url.isBlank()) {
            emit(LlmStreamEvent.ErrorEvent("Webhook URL not configured in API Keys", 400))
            return@flow
        }
        val startMs = System.currentTimeMillis()
        val payload = JSONObject().put("text", text).put("timestamp", System.currentTimeMillis())
        try {
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordUsage(AiProvider.WEBHOOK, false, resp.code, System.currentTimeMillis() - startMs, "HTTP ${resp.code}")
                    emit(LlmStreamEvent.ErrorEvent("Webhook HTTP ${resp.code}", resp.code))
                    return@use
                }
                val raw = resp.body?.string().orEmpty()
                val reply = runCatching {
                    val obj = JSONObject(raw)
                    obj.optString("reply", obj.optString("text", raw))
                }.getOrDefault(raw)
                emit(LlmStreamEvent.TextDelta(reply))
                recordUsage(AiProvider.WEBHOOK, true, resp.code, System.currentTimeMillis() - startMs, "OK")
                emit(LlmStreamEvent.Completed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Webhook error: ${sanitizeError(e.message ?: "Request failed")}"
            recordUsage(AiProvider.WEBHOOK, false, 0, System.currentTimeMillis() - startMs, msg)
            emit(LlmStreamEvent.ErrorEvent(msg))
        }
    }

    /**
     * Generates an image via OpenAI-compatible Images API or Gemini Imagen when enabled by safety policy.
     */
    suspend fun generateImage(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        if (!safetyPolicy.policy.value.imageGenerationEnabled) {
            actionLogDao.insert(
                ActionLogEntity("SAFETY", "Image Generation Blocked", "Toggle is OFF in Safety Settings", "BLOCKED")
            )
            return@withContext Result.failure(IllegalStateException("Image Generation toggle is OFF in Safety settings."))
        }
        val openAiKey = crypto.getSecret(KeystoreCryptoManager.KEY_OPENAI).trim()
        val geminiKey = crypto.nextGeminiKey()?.second?.trim().orEmpty()
        try {
            when {
                openAiKey.isNotBlank() -> {
                    val baseUrl = crypto.getSecret(KeystoreCryptoManager.KEY_OPENAI_URL)
                        .ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
                    val payload = JSONObject()
                        .put("model", "gpt-image-1")
                        .put("prompt", prompt)
                        .put("size", "1024x1024")
                        .put("response_format", "b64_json")
                    val req = Request.Builder()
                        .url("$baseUrl/images/generations")
                        .addHeader("Authorization", "Bearer $openAiKey")
                        .post(payload.toString().toRequestBody(jsonMediaType))
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(IllegalStateException("Image API HTTP ${resp.code}"))
                        }
                        val b64 = JSONObject(resp.body?.string().orEmpty())
                            .optJSONArray("data")
                            ?.optJSONObject(0)
                            ?.optString("b64_json", "")
                            .orEmpty()
                        if (b64.isBlank()) return@withContext Result.failure(IllegalStateException("Empty image bytes"))
                        val outFile = saveBase64Image(b64)
                        Result.success(outFile.absolutePath)
                    }
                }
                geminiKey.isNotBlank() -> {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-002:predict"
                    val payload = JSONObject()
                        .put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
                        .put("parameters", JSONObject().put("sampleCount", 1))
                    val req = Request.Builder()
                        .url(url)
                        .addHeader("x-goog-api-key", geminiKey)
                        .post(payload.toString().toRequestBody(jsonMediaType))
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(IllegalStateException("Imagen HTTP ${resp.code}"))
                        }
                        val b64 = JSONObject(resp.body?.string().orEmpty())
                            .optJSONArray("predictions")
                            ?.optJSONObject(0)
                            ?.optString("bytesBase64Encoded", "")
                            .orEmpty()
                        if (b64.isBlank()) return@withContext Result.failure(IllegalStateException("No image data returned"))
                        val outFile = saveBase64Image(b64)
                        Result.success(outFile.absolutePath)
                    }
                }
                else -> Result.failure(IllegalStateException("Configure a Gemini or OpenAI API key to generate images."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveBase64Image(base64: String): File {
        val dir = File(context.filesDir, "generated_images").apply { mkdirs() }
        val file = File(dir, "zehn_img_${System.currentTimeMillis()}.png")
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    private fun encodeUriToJpegBase64(uri: Uri): String? {
        return runCatching {
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            val bmp = stream.use { BitmapFactory.decodeStream(it) } ?: return null
            val scaled = if (bmp.width > 1024 || bmp.height > 1024) {
                val ratio = minOf(1024f / bmp.width, 1024f / bmp.height)
                Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    companion object {
        fun sanitizeError(raw: String): String {
            return raw
                .replace(Regex("""AIza[0-9A-Za-z\-_]{10,}"""), "[REDACTED_KEY]")
                .replace(Regex("""sk-[0-9A-Za-z\-_]{6,}"""), "[REDACTED_KEY]")
                .replace(Regex("""gsk_[0-9A-Za-z\-_]{6,}"""), "[REDACTED_GROQ]")
                .replace(Regex("""hf_[0-9A-Za-z\-_]{6,}"""), "[REDACTED_HF]")
                .replace(Regex("""(ghp|gho|ghu|ghs|github_pat)_[0-9A-Za-z_]{8,}"""), "[REDACTED_GH_TOKEN]")
                .replace(Regex("""Bearer\s+[0-9A-Za-z\-._~+/]+=*""", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
        }
    }
}
