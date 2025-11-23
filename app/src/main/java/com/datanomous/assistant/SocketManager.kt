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
    private val autoReconnect: Boolean = true,              // NEW (defaulted, non-breaking)
    private val maxReconnectDelayMs: Long = 8000L           // NEW
) : WebSocketListener() {

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var ws: WebSocket? = null
    private var connected = false
    private var closing = false

    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0

    // ----------------------------------------------------------
    // PUBLIC API
    // ----------------------------------------------------------

    fun connect() {
        if (connected || closing) return

        val request = Request.Builder()
            .url(url)
            .build()

        ws = client.newWebSocket(request, this)
    }

    fun disconnect() {
        closing = true
        try {
            ws?.close(1000, "client-close")
        } catch (_: Throwable) {}

        ws = null
        connected = false
        reconnectAttempts = 0
        closing = false
    }

    fun sendText(text: String) {
        if (!connected) return
        ws?.send(text)
    }

    fun sendBytes(bytes: ByteArray) {
        if (!connected) return
        ws?.send(ByteString.of(*bytes))
    }

    fun isConnected(): Boolean = connected

    // ----------------------------------------------------------
    // INTERNAL RECONNECT
    // ----------------------------------------------------------

    private fun scheduleReconnect() {
        if (!autoReconnect || closing) return

        reconnectAttempts++
        val delay = (reconnectAttempts * 1000L).coerceAtMost(maxReconnectDelayMs)

        handler.postDelayed(
            { connect() },
            delay
        )
    }

    // ----------------------------------------------------------
    // WebSocketListener overrides
    // ----------------------------------------------------------

    override fun onOpen(webSocket: WebSocket, response: Response) {
        connected = true
        closing = false
        reconnectAttempts = 0
        onConnected()

        // --- KEEPALIVE FIX ---
        handler.post(object : Runnable {
            override fun run() {
                if (connected) {
                    try { ws?.send("""{"type":"ping"}""") } catch (_: Throwable) {}
                    handler.postDelayed(this, 10000)   // send every 10s
                }
            }
        })
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            onJsonMessage(json)
        } catch (_: Throwable) {
            // ignore bad json
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        onBinaryMessage(bytes.toByteArray())
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        connected = false
        ws = null
        webSocket.close(code, reason)
        onDisconnected()
        if (!closing) scheduleReconnect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        connected = false
        ws = null
        onDisconnected()
        if (!closing) scheduleReconnect()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        connected = false
        ws = null
        onError(t)
        if (!closing) scheduleReconnect()
    }
}
