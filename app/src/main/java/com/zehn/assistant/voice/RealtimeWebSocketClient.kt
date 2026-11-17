package com.zehn.assistant.voice

import com.zehn.assistant.security.KeystoreCryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Responses-API style WebSocket client allowing the voice loop to swap to real-time duplex streaming.
 */
@Singleton
class RealtimeWebSocketClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val crypto: KeystoreCryptoManager
) {
    private var webSocket: WebSocket? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events.asSharedFlow()

    suspend fun connect(personaPrompt: String): Boolean = withContext(Dispatchers.IO) {
        val url = crypto.getSecret(KeystoreCryptoManager.KEY_REALTIME_WS_URL).trim()
        val key = crypto.getSecret(KeystoreCryptoManager.KEY_REALTIME_WS_KEY).trim()
        if (url.isBlank()) {
            _events.tryEmit("[ERROR] Realtime WebSocket URL is not configured in API Keys.")
            return@withContext false
        }
        if (key.isBlank()) {
            _events.tryEmit("[ERROR] Realtime WebSocket API key is missing in API Keys.")
            return@withContext false
        }

        disconnect()
        return@withContext try {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()

            webSocket = okHttpClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _connected.value = true
                    runCatching {
                        val sessionUpdate = JSONObject()
                            .put("type", "session.update")
                            .put("session", JSONObject().put("instructions", personaPrompt))
                        webSocket.send(sessionUpdate.toString())
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val obj = JSONObject(text)
                        val type = obj.optString("type")
                        if (type == "response.text.delta" || type == "response.audio_transcript.delta") {
                            val delta = obj.optString("delta", "")
                            if (delta.isNotEmpty()) _events.tryEmit(delta)
                        } else if (type == "response.done") {
                            _events.tryEmit("[DONE]")
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connected.value = false
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _connected.value = false
                    _events.tryEmit("[ERROR] ${t.message ?: "WebSocket connection failed"}")
                }
            })
            true
        } catch (e: Exception) {
            _connected.value = false
            _events.tryEmit("[ERROR] ${e.message ?: "Invalid WebSocket URL"}")
            false
        }
    }

    suspend fun sendUserTurn(text: String) = withContext(Dispatchers.IO) {
        try {
            val item = JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))
                )
            webSocket?.send(item.toString())
            webSocket?.send(JSONObject().put("type", "response.create").toString())
        } catch (e: Exception) {
            _events.tryEmit("[ERROR] ${e.message ?: "Failed to send WebSocket turn"}")
        }
    }

    fun disconnect() {
        runCatching { webSocket?.close(1000, "Client closed") }
        webSocket = null
        _connected.value = false
    }
}
