package com.datanomous.assistant.network

import android.util.Log
import com.datanomous.assistant.tts.TtsController
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ResponseWebSocketClient(
    private val deviceId: String,
    private val tts: TtsController,
    private val onConnected: (() -> Unit)? = null,
    private val onDisconnected: (() -> Unit)? = null,
) : WebSocketListener() {

    private val TAG = "Assistant-ResponseWS"

    @Volatile
    private var ws: WebSocket? = null

    // Auto-reconnect control
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var lastUrl: String = ""
    @Volatile
    private var shouldReconnect: Boolean = true
    @Volatile
    private var reconnectJob: Job? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)       // keep-alive
        .retryOnConnectionFailure(true)
        .readTimeout(0, TimeUnit.SECONDS)        // no read timeout (server controls)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------
    // CONNECT + remember URL for reconnection
    // ------------------------------------------------------------
    fun connect(url: String) {
        if (url.isBlank()) {
            Log.e(TAG, "❌ connect() called with blank URL")
            return
        }

        lastUrl = url
        shouldReconnect = true

        val request = Request.Builder()
            .url(url)
            .build()

        Log.i(TAG, "🌐 Connecting to /response → $url")
        ws = client.newWebSocket(request, this)
    }

    fun close() {
        Log.w(TAG, "🔻 close() requested → no further reconnects")
        shouldReconnect = false

        try {
            reconnectJob?.cancel()
        } catch (_: Exception) {}

        try {
            ws?.close(1000, "client-close")
        } catch (_: Exception) {}

        ws = null

        try {
            scope.cancel()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------
    // ON OPEN: SEND HANDSHAKE (REQUIRED BY SERVER)
    // ------------------------------------------------------------
    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.i(TAG, "🟢 Connected to /response (code=${response.code})")

        // reset reconnect state on successful connection
        shouldReconnect = true

        val hello = JSONObject()
            .put("type", "hello")
            .put("channel", "response")
            .put("device_id", deviceId)
            .put("payload", JSONObject())

        try {
            webSocket.send(hello.toString())
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send hello: ${e.message}")
        }

        try {
            onConnected?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "❌ onConnected callback failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------
    // MESSAGE HANDLER
    // ------------------------------------------------------------
    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            if (type == "ping") {
                // Server keep-alive
                return
            }

            if (type == "assistant_response") {
                val payload = json.optJSONObject("payload")
                val message = payload?.optString("text", "") ?: ""

                if (message.isNotBlank()) {
                    Log.i(TAG, "🔊 TTS → $message")
                    tts.speak(message)
                }
                return
            }

            Log.w(TAG, "⚠️ Unknown message type from /response: $type")

        } catch (e: Exception) {
            Log.e(TAG, "❌ onMessage parse fail: ${e.message}")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        // Binary messages not used for this channel
    }

    // ------------------------------------------------------------
    // SERVER INITIATES CLOSE
    // ------------------------------------------------------------
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.w(TAG, "⚠️ onClosing /response: code=$code, reason=$reason")
        safeDisconnected()
        webSocket.close(code, reason) // confirm close
        scheduleReconnect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.w(TAG, "🔴 onClosed /response: code=$code, reason=$reason")
        safeDisconnected()
        scheduleReconnect()
    }

    // ------------------------------------------------------------
    // FAILURE  → AUTO RECONNECT
    // ------------------------------------------------------------
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(
            TAG,
            "❌ onFailure /response: ${t.message} " +
                    (response?.code?.let { "(code=$it)" } ?: "")
        )

        safeDisconnected()
        scheduleReconnect()
    }

    // ------------------------------------------------------------
    // COMMON DISCONNECT HANDLER
    // ------------------------------------------------------------
    private fun safeDisconnected() {
        ws = null
        try {
            onDisconnected?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "❌ onDisconnected callback failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------
    // ALWAYS RECONNECT AFTER A SHORT DELAY (UNLESS MANUAL CLOSE)
    // ------------------------------------------------------------
    private fun scheduleReconnect() {
        if (!shouldReconnect) {
            Log.i(TAG, "⛔ Reconnect disabled (client closed or service shutting down)")
            return
        }

        if (lastUrl.isBlank()) {
            Log.e(TAG, "❌ Cannot reconnect: lastUrl is blank")
            return
        }

        // cancel previous pending reconnect if any
        reconnectJob?.cancel()

        reconnectJob = scope.launch {
            delay(2000)
            Log.i(TAG, "🔄 Reconnecting to /response …")
            try {
                connect(lastUrl)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Reconnect error: ${e.message}")
            }
        }
    }
}
