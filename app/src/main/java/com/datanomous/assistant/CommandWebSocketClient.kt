package com.datanomous.logisticsassistant.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.datanomous.logisticsassistant.AssistantService
import com.datanomous.logisticsassistant.shared.AssistantBus
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/**
 * ======================================================================
 *  ChatWebSocket — UI Text Channel (/text)
 * ======================================================================
 *
 * Responsibilities:
 *   ✓ Connect to /text WebSocket
 *   ✓ Send user chat messages
 *   ✓ Receive NON-SPEECH bot messages (for UI only)
 *   ✓ "processing" → pipeline lock
 *   ✓ "message" → update UI
 *
 *  🚫 NO audio playback
 *  🚫 NO Android TTS
 *  🚫 NO WAV playback
 *  🚫 NO fallback to /tts
 *
 *  Audio output is now fully handled through /response WebSocket.
 */
class CommandWebSocketClient(
    private val context: Context,
    private val url: String,
    private val onMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {

    companion object {
        private const val TAG = "ChatWebSocket"
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
    private val scope = CoroutineScope(Dispatchers.IO)

    private var greetedOnce = false
    private var heartbeatActive = false


    // ---------------------------------------------------------------------
    // WS STATE HELPERS
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
    // WS LISTENER
    // ---------------------------------------------------------------------
    private val wsListener = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "✅ /text connected")

            val deviceId = Settings.Secure.getString(
                appCtx.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            // Register device
            ws.send("""{"type":"hello","device_id":"$deviceId"}""")
            Log.i(TAG, "📤 hello(device_id=$deviceId) sent to /text")

            heartbeatActive = true
            mainHandler.post(heartbeatRunnable)

            greetedOnce = true
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.d(TAG, "📥 Raw /text message → $text")
            handleIncomingMessage(text)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "🔒 /text closed (code=$code, reason=$reason)")
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
                Log.e(TAG, "❌ ping failed: ${e.message}")
            }

            if (heartbeatActive)
                mainHandler.postDelayed(this, 20_000L)
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
            delay(1200)
            try {
                Log.i(TAG, "🔄 Reconnect attempt…")
                connect()
            } catch (e: Throwable) {
                Log.e(TAG, "❌ Reconnect failed", e)
            }
        }
    }


    // ---------------------------------------------------------------------
    // MESSAGE HANDLER (UI ONLY)
    // ---------------------------------------------------------------------
    private fun handleIncomingMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val type = json.optString("type", "")
            val payload = json.optJSONObject("payload")

            val text = payload?.optString("text", "")
                ?: json.optString("text", "")

            when (type.lowercase()) {

                "ping" -> return

                "processing" -> {
                    AssistantService.lockPipeline()
                    return
                }

                "message" -> {
                    if (text.isNotBlank()) {
                        // Send to UI
                        AssistantBus.emit(text)
                        onMessage(text)
                        Log.i(TAG, "💬 BOT → UI: $text")
                    }
                    AssistantService.unlockPipeline()
                }

                else -> {
                    // Unknown → do nothing but unlock
                    AssistantService.unlockPipeline()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ parse error: ${e.message}", e)
        }
    }


    // ---------------------------------------------------------------------
    // SEND USER MESSAGE
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
            "ts": ${System.currentTimeMillis()}
          }
        }
        """.trimIndent()

        try {
            webSocket?.send(envelope)
            Log.d(TAG, "📤 Sent to /text → $safe")
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
            Log.e(TAG, "❌ sendCommand failed", e)
        }
    }
}
