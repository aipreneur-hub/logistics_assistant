package com.datanomous.assistant.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * =====================================================================
 * 🧩 AssistantConstants
 * =====================================================================
 * Centralized config:
 *   – WebSocket URLs
 *   – HTTP endpoints
 *   – Heartbeat & reconnect timers
 *   – Audio constants
 */
object AssistantConstants {
    const val NOTIF_CHANNEL_ID = "assistant_channel"
    const val NOTIF_CHANNEL_NAME = "Voice Assistant"
    const val NOTIF_ID = 1

    const val WS_CHAT_URL = "wss://unpalatal-danille-semiexternally.ngrok-free.dev/text"
    const val WS_STT_URL  = "wss://unpalatal-danille-semiexternally.ngrok-free.dev/stt"
    const val HTTP_ONSTART = "https://unpalatal-danille-semiexternally.ngrok-free.dev/onstart"
    const val HTTP_TTS     = "https://unpalatal-danille-semiexternally.ngrok-free.dev/tts"

    const val HEARTBEAT_MS = 20_000L
    const val RECONNECT_BASE_MS = 1_000L
    const val RECONNECT_MAX_MS  = 60_000L

    const val SAMPLE_RATE = 16_000
    const val MIC_CHUNK_MS = 20
}

/**
 * Creates/ensures the foreground notification channel.
 */
object AssistantNotifier {
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(AssistantConstants.NOTIF_CHANNEL_ID) != null) return

        val ch = NotificationChannel(
            AssistantConstants.NOTIF_CHANNEL_ID,
            AssistantConstants.NOTIF_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }
}

/**
 * Safe service starter
 */
object AssistantServiceStarter {
    fun startForeground(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        } catch (e: Exception) {
            Log.e("AssistantStarter", "❌ Failed: ${e.message}", e)
        }
    }
}

/**
 * Simple network monitor wrapper
 */
class NetworkMonitor(
    private val ctx: Context,
    private val onChange: (Status) -> Unit
) {
    enum class Status { ONLINE, DEGRADED, OFFLINE }

    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var cb: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (cb != null) return

        cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(Status.ONLINE)
            override fun onLost(network: Network) {
                onChange(if (online()) Status.ONLINE else Status.OFFLINE)
            }
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                onChange(if (ok) Status.ONLINE else Status.DEGRADED)
            }
        }

        try {
            cm?.registerDefaultNetworkCallback(cb!!)
        } catch (e: Exception) {
            Log.e("NetworkMonitor", "❌ registering failed: ${e.message}")
            onChange(if (online()) Status.ONLINE else Status.OFFLINE)
        }
    }

    fun stop() {
        try { cb?.let { cm?.unregisterNetworkCallback(it) } } catch (_: Throwable) {}
        cb = null
    }

    private fun online(): Boolean {
        val net = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/**
 * Wake/Wi-Fi Locks
 */
class PowerLocks(private val ctx: Context) {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    fun acquire() {
        try {
            val pm = ctx.getSystemService(PowerManager::class.java)
            wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LA:Wake")
            wake?.setReferenceCounted(false)
            wake?.acquire()

            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            wifi = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LA:Wifi")
            wifi?.setReferenceCounted(false)
            wifi?.acquire()

        } catch (e: Exception) {
            Log.e("PowerLocks", "❌ acquire() failed: ${e.message}", e)
        }
    }

    fun release() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Throwable) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Throwable) {}

        wake = null; wifi = null
    }
}
