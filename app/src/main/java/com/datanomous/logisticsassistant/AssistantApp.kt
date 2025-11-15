package com.datanomous.logisticsassistant

import android.app.Application
import android.util.Log
import com.datanomous.logisticsassistant.util.AssistantNotifier
import com.datanomous.logisticsassistant.util.PowerLocks
import com.datanomous.logisticsassistant.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * =====================================================================
 * 🧠 AssistantApp
 * =====================================================================
 * Global application entry point.
 *
 * Responsibilities:
 *   ✔ Create Notification Channel
 *   ✔ Acquire Wake/Wi-Fi locks (24/7 operation)
 *   ✔ Start network state monitor
 *   ✔ Provide global IO coroutine scope
 *
 * Notes:
 *   – Does NOT handle any audio, TTS, or WebSocket logic.
 *   – Pure infrastructure layer.
 */
class AssistantApp : Application() {

    companion object {
        lateinit var instance: AssistantApp
            private set

        // Global coroutine scope for background tasks
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val TAG = "AssistantApp"
    }

    private var locks: PowerLocks? = null
    private var networkMonitor: NetworkMonitor? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "🚀 Application created")

        // ------------------------------------------------------------
        // 🔔 Notification Channel
        // ------------------------------------------------------------
        try {
            AssistantNotifier.ensureChannel(this)
            Log.d(TAG, "🔔 Notification channel ready")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Notification channel init failed: ${e.message}", e)
        }

        // ------------------------------------------------------------
        // ⚡ Acquire power locks
        // ------------------------------------------------------------
        try {
            locks = PowerLocks(this).apply { acquire() }
            Log.i(TAG, "🔒 Wake/Wi-Fi locks acquired")
        } catch (e: Exception) {
            Log.e(TAG, "❌ PowerLocks acquisition failed: ${e.message}", e)
        }

        // ------------------------------------------------------------
        // 🌐 Network Status Monitor
        // ------------------------------------------------------------
        try {
            networkMonitor = NetworkMonitor(this) { status ->
                Log.d(TAG, "🌐 Network Status → $status")
            }.also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "❌ NetworkMonitor init failed: ${e.message}", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        try {
            locks?.release()
            networkMonitor?.stop()
        } catch (_: Throwable) {}

        Log.i(TAG, "🧹 Application terminated")
    }
}
