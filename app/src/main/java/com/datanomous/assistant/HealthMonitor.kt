package com.datanomous.assistant.monitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.datanomous.assistant.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import com.datanomous.assistant.AssistantService
/**
 * 🩺 HealthMonitor
 *
 * Periodically checks:
 *  - Network status (via NetworkMonitor)
 *  - Chat WebSocket status (via LogisticsAssistantService.isChatConnected())
 *  - Mic pipeline availability (via LogisticsAssistantService.isMicAvailable())
 *
 * Emits HealthState -> SystemHealth.state (UI collects it).
 */
class HealthMonitor(
    private val context: Context,
    private val onHealthUpdate: (HealthState) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    private val lastChatState  = AtomicReference(State.UNKNOWN)
    private val lastNetworkState = AtomicReference(State.UNKNOWN)

    private var networkMonitor: NetworkMonitor? = null
    @Volatile private var running = false

    // ---------------------------------------------------------------
    // ENUMS + DATA MODEL
    // ---------------------------------------------------------------
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

    // ---------------------------------------------------------------
    // PUBLIC API
    // ---------------------------------------------------------------
    fun start() {
        if (running) return
        running = true

        Log.i("LogisticsAssistant - HealthMonitor", "🩺 HealthMonitor started")

        // Start callback-based network listener
        monitorNetwork()

        // Start periodic WS + MIC loop
        monitorLoop()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)

        try {
            networkMonitor?.stop()
        } catch (_: Throwable) {}

        networkMonitor = null

        Log.i("LogisticsAssistant - HealthMonitor", "🩺 HealthMonitor stopped")
    }

    // ---------------------------------------------------------------
    // 🌐 NETWORK MONITOR
    // ---------------------------------------------------------------
    private fun monitorNetwork() {
        networkMonitor = NetworkMonitor(context) { status ->
            val mapped = when (status) {
                NetworkMonitor.Status.ONLINE   -> State.ONLINE
                NetworkMonitor.Status.DEGRADED -> State.DEGRADED
                NetworkMonitor.Status.OFFLINE  -> State.OFFLINE
            }

            // Update only on changes
            if (lastNetworkState.getAndSet(mapped) != mapped) {
                emit()
            }
        }.also { it.start() }
    }

    // ---------------------------------------------------------------
    // 🔁 WS + MIC LOOP
    // ---------------------------------------------------------------
    private fun monitorLoop() {
        scope.launch {
            while (running) {
                try {
                    val chatState =
                        if (AssistantService.uiIsChatConnected())
                            State.ONLINE else State.OFFLINE

                    val micState =
                        if (AssistantService.uiIsMicAvailable())
                            State.ONLINE else State.OFFLINE

                    val networkState = lastNetworkState.get()

                    val prevChat = lastChatState.getAndSet(chatState)
                    if (chatState != prevChat) {
                        Log.i("HealthMonitor", "🌐 Chat WS changed → $chatState")
                    }

                    val health = HealthState(
                        network = networkState,
                        chat = chatState,
                        mic = micState
                    )

                    onHealthUpdate(health)

                } catch (e: Exception) {
                    Log.e("LogisticsAssistant - HealthMonitor", "❌ Health loop error: ${e.message}", e)
                }

                delay(5000)
            }
        }
    }

    // ---------------------------------------------------------------
    // 🔔 Emit Immediate Update
    // ---------------------------------------------------------------
    private fun emit() {
        try {
            val health = HealthState(
                network = lastNetworkState.get(),
                chat = lastChatState.get(),
                mic = if (AssistantService.uiIsMicAvailable())
                    State.ONLINE else State.OFFLINE
            )
            onHealthUpdate(health)
        } catch (e: Exception) {
            Log.e("LogisticsAssistant - HealthMonitor", "❌ Emit error: ${e.message}", e)
        }
    }
}
