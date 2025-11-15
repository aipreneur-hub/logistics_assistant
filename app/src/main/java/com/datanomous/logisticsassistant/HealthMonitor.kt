package com.datanomous.logisticsassistant.monitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.datanomous.logisticsassistant.LogisticsAssistantService
import com.datanomous.logisticsassistant.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * 🩺 HealthMonitor
 *
 * Periodically checks:
 *  - Network status (via NetworkMonitor)
 *  - Chat WebSocket status (via LogisticsAssistantService.isChatConnected())
 *  - Mic pipeline availability (via LogisticsAssistantService.isMicAvailable())
 * Emits status changes to observers and logs transitions.
 */
class HealthMonitor(
    private val context: Context,
    private val onHealthUpdate: (HealthState) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    private val lastChatState = AtomicReference(State.UNKNOWN)
    private val lastNetworkState = AtomicReference(State.UNKNOWN)

    private var networkMonitor: NetworkMonitor? = null
    private var running = false

    enum class State { ONLINE, DEGRADED, OFFLINE, UNKNOWN }

    data class HealthState(
        val network: State,
        val chat: State,
        val mic: State,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val overall: State
            get() = when {
                network == State.OFFLINE || chat == State.OFFLINE -> State.OFFLINE
                network == State.DEGRADED || chat == State.DEGRADED -> State.DEGRADED
                else -> State.ONLINE
            }
    }

    fun start() {
        if (running) return
        running = true
        Log.i("LogisticsAssistant - HealthMonitor", "🩺 HealthMonitor started")
        monitorNetwork()
        monitorLoop()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        networkMonitor?.stop()
        networkMonitor = null
        Log.i("LogisticsAssistant - HealthMonitor", "🩺 HealthMonitor stopped")
    }

    // --------------------------------------------------------------------
    // 🌐 Network Monitor
    // --------------------------------------------------------------------
    private fun monitorNetwork() {
        networkMonitor = NetworkMonitor(context) { status ->
            val mapped = when (status) {
                NetworkMonitor.Status.ONLINE   -> State.ONLINE
                NetworkMonitor.Status.DEGRADED -> State.DEGRADED
                NetworkMonitor.Status.OFFLINE  -> State.OFFLINE
            }
            if (lastNetworkState.getAndSet(mapped) != mapped) {
                emit()
            }
        }.also { it.start() }
    }

    // --------------------------------------------------------------------
    // 🔁 Periodic Chat & Mic Checks
    // --------------------------------------------------------------------
    private fun monitorLoop() {
        scope.launch {
            while (running) {
                try {
                    // Chat WS status from service accessor
                    val chatState =
                        if (LogisticsAssistantService.isChatConnected())
                            State.ONLINE
                        else
                            State.OFFLINE

                    // Mic availability from service accessor
                    val micState =
                        if (LogisticsAssistantService.isMicAvailable())
                            State.ONLINE
                        else
                            State.OFFLINE

                    val networkState = lastNetworkState.get()
                    val prevChat = lastChatState.getAndSet(chatState)

                    if (chatState != prevChat) {
                        Log.i(
                            "LogisticsAssistant - HealthMonitor",
                            "🌐 Chat WS state changed → $chatState"
                        )
                    }

                    val health = HealthState(networkState, chatState, micState)
                    onHealthUpdate(health)

                } catch (e: Exception) {
                    Log.e(
                        "LogisticsAssistant - HealthMonitor",
                        "❌ Health loop error: ${e.message}",
                        e
                    )
                }

                delay(5_000)
            }
        }
    }

    private fun emit() {
        try {
            val health = HealthState(
                network = lastNetworkState.get(),
                chat = lastChatState.get(),
                mic = if (LogisticsAssistantService.isMicAvailable())
                    State.ONLINE
                else
                    State.OFFLINE
            )
            onHealthUpdate(health)
        } catch (e: Exception) {
            Log.e(
                "LogisticsAssistant - HealthMonitor",
                "❌ Emit error: ${e.message}",
                e
            )
        }
    }
}
