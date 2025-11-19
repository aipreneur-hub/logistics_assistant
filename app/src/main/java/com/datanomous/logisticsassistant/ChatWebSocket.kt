package com.datanomous.logisticsassistant.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.datanomous.logisticsassistant.LogisticsAssistantService
import com.datanomous.logisticsassistant.shared.MessageBus
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
/**
 * =====================================================================
 * 🌐 ChatWebSocket — NEW DESIGN (A-Level Logging)
 * =====================================================================
 *
 * Responsibilities:
 *   ✔ Maintain /text WebSocket connection (server -> device)
 *   ✔ Send user messages (device -> server)
 *   ✔ Receive assistant messages (text + TTS)
 *   ✔ Forward TTS URLs to LogisticsAssistantService for playback
 *   ✔ Forward text messages to MessageBus (UI)
 *   ✔ Perform reconnection + heartbeat
 *
 * ❗ ChatWebSocket NEVER:
 *   – Touches microphone
 *   – Touches AudioRecord
 *   – Touches segmentation
 *
 * All audio logic is handled by:
 *   • MicStreamer (recording/VAD/STT)
 *   • TTSPlayer (playback)
 *   • LogisticsAssistantService (state machine)
 *
 * This class is PURE network IO + dispatch.
 */
class ChatWebSocket(
    private val context: Context,
    private val url: String,
    private val onMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {

    companion object {
        private const val TAG = "ChatWebSocket"
    }

    // ------------------------------------------------------------------
    // Core
    // ------------------------------------------------------------------
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite stream allowed
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectDelay = 1_000L
    private var isReconnecting = false

    private var greetedOnce = false
    private var heartbeatActive = false

    private val scope = CoroutineScope(Dispatchers.IO)

    private val feedbackCacheFile =
        File(context.cacheDir, "feedback_cached.wav")

    // ------------------------------------------------------------------
    // 🚀 Public API
    // ------------------------------------------------------------------
    fun isConnected(): Boolean = webSocket != null

    fun connect() {
        if (webSocket != null) {
            Log.w(TAG, "⚠️ connect() ignored → already connected")
            return
        }

        Log.i(TAG, "🛰️ [WS] Connecting → $url")
        val req = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(req, wsListener)
    }

    fun close() {
        Log.w(TAG, "🧹 [WS] Close requested")
        stopHeartbeat()

        try {
            webSocket?.close(1000, "client-exit")
        } catch (_: Throwable) {}
        webSocket = null
    }

    // ------------------------------------------------------------------
    // WebSocket Listener
    // ------------------------------------------------------------------
    private val wsListener = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "✅ [WS] Connected (code=${response.code})")
            reconnectDelay = 2_000L
            isReconnecting = false

            val deviceId = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            val hello = """{"type":"hello","device_id":"$deviceId"}"""
            ws.send(hello)
            Log.i(TAG, "📤 [WS] Hello sent")

            heartbeatActive = true
            mainHandler.post(heartbeatRunnable)

            if (!greetedOnce) {
                greetedOnce = true
                greetOnStart(deviceId)
                cacheFeedbackAudio()
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.d(TAG, "📩 [WS] Raw message → $text")
            handleServerMessage(text)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "🔒 [WS] Closed (code=$code reason=$reason)")
            stopHeartbeat()
            webSocket = null
            reconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "🛑 [WS] Failure → ${t.message}", t)
            stopHeartbeat()
            onError(t)
            webSocket = null
            reconnect()
        }
    }

    // ------------------------------------------------------------------
    // ❤️ Heartbeat
    // ------------------------------------------------------------------
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            try {
                webSocket?.send("""{"type":"ping"}""")
                Log.v(TAG, "💓 [WS] Ping sent")
            } catch (e: Throwable) {
                Log.e(TAG, "❌ [WS] Ping failed: ${e.message}")
            }

            if (heartbeatActive)
                mainHandler.postDelayed(this, 20_000L)
        }
    }

    private fun stopHeartbeat() {
        heartbeatActive = false
        mainHandler.removeCallbacks(heartbeatRunnable)
    }

    // ------------------------------------------------------------------
    // 🔁 Reconnect Logic
    // ------------------------------------------------------------------
    private fun reconnect() {
        if (isReconnecting) return
        isReconnecting = true

        Log.w(TAG, "🔁 [WS] Reconnecting in ${reconnectDelay}ms…")

        scope.launch {
            delay(reconnectDelay)
            try {
                connect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ [WS] Reconnect crash: ${e.message}", e)
            } finally {
                isReconnecting = false
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(60_000L)
            }
        }
    }

    // ------------------------------------------------------------------
    // 🎧 Incoming Server Messages
    // ------------------------------------------------------------------
    private fun handleServerMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val type = json.optString("type", "")
            val payload = json.optJSONObject("payload")

            val text = payload?.optString("text", "")
                ?: json.optString("text", "")
            val ttsUrl = payload?.optString("tts_url", "")
                ?: json.optString("tts_url", "")

            Log.i(TAG, "🎧 [WS] Parsed → type=$type text='$text' tts='$ttsUrl'")

            when (type.lowercase()) {

                "ping" -> return

                "processing" -> {
                    Log.d(TAG, "⏳ [WS] Processing and locking the mic.")
                    LogisticsAssistantService.lockPipeline()

                    return
                }

                "message", "tts" -> {

                    // 1) Forward text (if any)
                    if (text.isNotBlank()) {
                        MessageBus.emit(text)
                        onMessage(text)
                        Log.i(TAG, "💬 [BOT] $text")
                    }

                    // 2) If TTS is present → TTSPlayer will unlock at end
                    if (ttsUrl.isNotBlank()) {
                        Log.i(TAG, "🔊 [WS] Dispatching TTS → Service.playTts()")
                        LogisticsAssistantService.playTts(ttsUrl)
                        return  // leave locked until TTS finishes
                    }

                    // 3) No TTS → silent fallback or silent command → UNLOCK
                    LogisticsAssistantService.unlockPipeline()
                    Log.i(TAG, "🔓 [WS] Silent reply → pipeline / mic unlocked")
                }


                else -> {
                    LogisticsAssistantService.unlockPipeline()
                    Log.d(TAG, "ℹ️ [WS] Ignored type '$type' → pipeline / mic unlocked")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ [WS] Parse error: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // 🔊 Cached "Please wait" feedback
    // ------------------------------------------------------------------
    private fun playFeedbackIfCached() {
        if (!feedbackCacheFile.exists() || feedbackCacheFile.length() < 5000) {
            Log.w(TAG, "⚠️ [WS] Feedback cache missing")
            return
        }

        try {
            Log.i(TAG, "🎧 [WS] Playing cached feedback")
            LogisticsAssistantService.playTts(feedbackCacheFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "❌ [WS] Feedback play failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 👋 Greeting on first connect
    // ------------------------------------------------------------------
    private fun greetOnStart(deviceId: String) {
        try {
            val req = Request.Builder()
                .url("http://128.140.66.158:8000/onstart")
                .post("""{"device_id":"$deviceId"}""".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "❌ [WS] Greeting failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    Log.i(TAG, "👋 [WS] Greeting success (${response.code})")
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ [WS] greetOnStart error: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // ⚠️ PRELOAD FEEDBACK
    // ------------------------------------------------------------------
    private fun cacheFeedbackAudio() {
        val json = """{"text": "Komutu aldım. Lütfen bekleyin."}"""

        val req = Request.Builder()
            .url("http://128.140.66.158:8000/tts")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ [WS] Feedback download failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "⚠️ [WS] Feedback error: ${response.code}")
                    response.close()
                    return
                }

                try {
                    response.body?.byteStream()?.use { input ->
                        feedbackCacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "💾 [WS] Feedback cached (${feedbackCacheFile.length()} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [WS] Feedback save failed: ${e.message}", e)
                } finally {
                    response.close()
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // 📤 Send user messages
    // ------------------------------------------------------------------
    fun send(text: String) {
        if (text.isBlank()) return

        try {
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

            Log.i(TAG, "📤 [WS] Sending → $safe")

            // playFeedbackIfCached()

            webSocket?.send(envelope)

        } catch (e: Exception) {
            Log.e(TAG, "❌ [WS] Send failed: ${e.message}", e)
        }
    }
}
