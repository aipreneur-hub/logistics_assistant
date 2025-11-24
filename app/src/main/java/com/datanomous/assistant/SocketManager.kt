package com.datanomous.assistant.network

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SocketManager(
    private val url: String,
    private val onJsonMessage: (JSONObject) -> Unit = {},
    private val onBinaryMessage: (ByteArray) -> Unit = {},
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val autoReconnect: Boolean = true,
    private val maxReconnectDelayMs: Long = 8000L
) : WebSocketListener() {

    private val client = OkHttpClient.Builder()
        // OkHttp will send protocol-level PING frames every 15s
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var ws: WebSocket? = null
    private var connected = false
    private var closing = false           // true only when caller explicitly disconnects
    private var reconnectAttempts = 0

    private val handler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------
    // PUBLIC API
    // ------------------------------------------------------------------

    fun connect() {
        if (closing || connected) return

        val request = Request.Builder()
            .url(url)
            .build()

        ws = client.newWebSocket(request, this)
    }

    fun disconnect() {
        closing = true
        connected = false

        try {
            ws?.close(1000, "client-close")
        } catch (_: Throwable) {
        }

        ws = null
        reconnectAttempts = 0
    }

    fun isConnected(): Boolean = connected

    fun sendText(text: String) {
        if (connected) {
            ws?.send(text)
        }
    }

    fun sendBytes(bytes: ByteArray) {
        if (connected) {
            ws?.send(ByteString.of(*bytes))
        }
    }

    // ------------------------------------------------------------------
    // INTERNAL RECONNECT
    // ------------------------------------------------------------------

    private fun scheduleReconnect() {
        if (!autoReconnect || closing) return

        reconnectAttempts++
        val delay = (reconnectAttempts * 1000L).coerceAtMost(maxReconnectDelayMs)

        handler.postDelayed({ connect() }, delay)
    }

    // ------------------------------------------------------------------
    // WebSocketListener overrides
    // ------------------------------------------------------------------

    override fun onOpen(webSocket: WebSocket, response: Response) {
        connected = true
        closing = false
        reconnectAttempts = 0

        // 🔥 ALWAYS send hello directly on the real socket
        try {
            onConnected()
        } catch (_: Throwable) {}
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            onJsonMessage(JSONObject(text))
        } catch (_: Throwable) {
            // ignore malformed JSON
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        onBinaryMessage(bytes.toByteArray())
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        connected = false
        ws = null

        onDisconnected()

        if (!closing) {
            scheduleReconnect()
        }

        webSocket.close(code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        connected = false
        ws = null

        onDisconnected()

        if (!closing) {
            scheduleReconnect()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        connected = false
        ws = null

        onError(t)

        if (!closing) {
            scheduleReconnect()
        }
    }
}
