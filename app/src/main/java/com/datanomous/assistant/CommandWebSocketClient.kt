package com.datanomous.assistant.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.datanomous.assistant.shared.AssistantBus
import com.datanomous.assistant.AssistantService
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ======================================================================
 *  CommandWebSocketClient — UI Text Channel (/text)
 * ======================================================================
 *
 * Responsibilities:
 *   ✓ /text → receive UI-only messages (NOT audio)
 *   ✓ Send user messages
 *   ✓ processing state → locks mic pipeline
 *
 * Generic / Extensible Payload Structure:
 *
 *   {
 *      "type": "message" | "processing" | "ping" | ...
 *      "payload": {
 *          "text": "...",
 *          "id": "...",
 *          "sender": "...",
 *          "ts": 12345,
 *          "extra": { ... }     // future safe
 *      }
 *   }
 *
 */
class CommandWebSocketClient(
    private val context: Context,
    private val url: String,
    private val onMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {

    companion object {
        private const val TAG = "Assistant - CommandWS"
        private const val HEARTBEAT_MS = 20_000L
        private const val RECONNECT_DELAY = 1200L
    }

    private val appCtx = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var heartbeatActive = false


    // ---------------------------------------------------------------------
    // WS STATE
    // ---------------------------------------------------------------------
    fun isConnected(): Boolean = webSocket != null

    fun close() {
        Log.w(TAG, "🧹 Closing /text WebSocket")
        stopHeartbeat()
        try { webSocket?.close(1000, "client exit") } catch (_: Throwable) {}
        webSocket = null
    }


    // ---------------------------------------------------------------------
    // CONNECT
    // ---------------------------------------------------------------------
    fun connect() {
        if (webSocket != null) {
            Log.w(TAG, "⚠️ connect() ignored → already connected")
            return
        }

        Log.i(TAG, "🌐 Connecting to $url")
        val req = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(req, wsListener)
    }


    // ---------------------------------------------------------------------
    // LISTENER
    // ---------------------------------------------------------------------
    private val wsListener = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "✅ /text connected")

            val deviceId = Settings.Secure.getString(
                appCtx.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            val hello = JSONObject()
                .put("type", "hello")
                .put("device_id", deviceId)

            ws.send(hello.toString())
            Log.i(TAG, "📤 hello(device_id=$deviceId)")

            heartbeatActive = true
            mainHandler.post(heartbeatRunnable)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.d(TAG, "📥 /text raw → $text")
            handleIncomingMessage(text)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "🔒 /text closed ($code: $reason)")
            stopHeartbeat()
            webSocket = null
            reconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
            Log.e(TAG, "🛑 /text failure → ${t.message}", t)
            stopHeartbeat()
            onError(t)
            webSocket = null
            reconnect()
        }
    }


    // ---------------------------------------------------------------------
    // HEARTBEAT
    // ---------------------------------------------------------------------
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                webSocket?.send("""{"type":"ping"}""")
            } catch (e: Throwable) {
                Log.e(TAG, "❌ heartbeat ping failed: ${e.message}")
            }

            if (heartbeatActive)
                mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun stopHeartbeat() {
        heartbeatActive = false
        mainHandler.removeCallbacks(heartbeatRunnable)
    }


    // ---------------------------------------------------------------------
    // RECONNECT
    // ---------------------------------------------------------------------
    private fun reconnect() {
        if (reconnectJob?.isActive == true) return

        Log.w(TAG, "🔄 Scheduling reconnect to /text…")

        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            try {
                Log.i(TAG, "🔄 Reconnect attempt…")
                connect()
            } catch (e: Throwable) {
                Log.e(TAG, "❌ Reconnect failed", e)
            }
        }
    }


    // ---------------------------------------------------------------------
    // MESSAGE HANDLER
    // ---------------------------------------------------------------------
    private fun handleIncomingMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val type = json.optString("type", "").lowercase()

            val payload = json.optJSONObject("payload")
            val text = payload?.optString("text", "") ?: ""

            when (type) {

                "ping" -> return

                "processing" -> {
                    AssistantService.lockPipeline()
                    return
                }

                "message" -> {
                    if (text.isNotBlank()) {
                        AssistantBus.emit(text)
                        onMessage(text)
                        Log.i(TAG, "💬 BOT → UI: $text")
                    }
                    AssistantService.unlockPipeline()
                }

                else -> {
                    // In future: payload.type may specify more actions
                    Log.w(TAG, "⚠️ Unknown /text message type='$type'")
                    AssistantService.unlockPipeline()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ JSON parse error: ${e.message}", e)
            AssistantService.unlockPipeline()
        }
    }


    // ---------------------------------------------------------------------
    // SEND USER TEXT
    // ---------------------------------------------------------------------
    fun send(text: String) {
        if (text.isBlank()) return

        val safe = text.replace("\"", "\\\"")

        val envelope = """
        {
          "type": "message",
          "payload": {
            "id": "u-${System.currentTimeMillis()}",
            "sender": "USER",
            "text": "$safe",
            "ts": ${System.currentTimeMillis()},
            "extra": {}
          }
        }
        """.trimIndent()

        try {
            webSocket?.send(envelope)
            Log.d(TAG, "📤 USER → /text: $safe")
        } catch (e: Exception) {
            Log.e(TAG, "❌ send() failed", e)
        }
    }


    // ---------------------------------------------------------------------
    // SEND COMMAND (reset, etc.)
    // ---------------------------------------------------------------------
    fun sendCommand(type: String) {
        try {
            webSocket?.send("""{"type":"$type"}""")
        } catch (e: Exception) {
            Log.e(TAG, "❌ sendCommand('$type') failed", e)
        }
    }
}
