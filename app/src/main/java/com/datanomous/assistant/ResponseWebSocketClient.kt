package com.datanomous.logisticsassistant.network

import android.util.Log
import com.datanomous.logisticsassistant.tts.TtsController
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client dedicated for /response endpoint.
 * Push-only channel for assistant messages.
 */
class ResponseWebSocketClient(
    private val deviceId: String,
    private val tts: TtsController,
    private val onConnected: (() -> Unit)? = null,
    private val onDisconnected: (() -> Unit)? = null,
) : WebSocketListener() {

    private val TAG = "ResponseWS"

    private var ws: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun connect(url: String) {
        val request = Request.Builder()
            .url(url)
            .build()

        ws = client.newWebSocket(request, this)
    }

    fun close() {
        try {
            ws?.close(1000, "Closing Response WS")
        } catch (_: Exception) {}
        scope.cancel()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.i(TAG, "Connected to /response")

        // Perform handshake
        val hello = JSONObject()
            .put("type", "hello")
            .put("device_id", deviceId)

        webSocket.send(hello.toString())

        onConnected?.invoke()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            // Ping from server
            if (type == "ping") return

            if (type == "assistant_response") {
                val payload = json.getJSONObject("payload")
                val message = payload.getString("text")

                // Speak immediately
                tts.speak(message)

                return
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $e")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        // Not used — ignore
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.w(TAG, "Closing /response WS: $code $reason")
        onDisconnected?.invoke()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(TAG, "Failure /response WS: ${t.message}")
        onDisconnected?.invoke()

        // Auto-reconnect
        scope.launch {
            delay(2000)
            Log.i(TAG, "Reconnecting /response…")
            try {
                connect(webSocket.request().url.toString())
            } catch (_: Exception) {}
        }
    }
}
