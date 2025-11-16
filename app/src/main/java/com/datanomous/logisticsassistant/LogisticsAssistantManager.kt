package com.datanomous.logisticsassistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.datanomous.logisticsassistant.LogisticsAssistantService.Companion.MicState

/**
 * =====================================================================
 * 🎛 LogisticsAssistantManager
 * =====================================================================
 *
 * High-level façade for the voice assistant.
 *
 * Responsibilities:
 *   ✔ Start / stop the LogisticsAssistantService (foreground service)
 *   ✔ Provide a clean API for UI layer (Compose, Activities, etc.)
 *   ✔ Delegate mic / text / TTS actions to LogisticsAssistantService
 *
 * Notes:
 *   – This object should be the ONLY thing the UI talks to.
 *   – It hides service + pipeline details from the presentation layer.
 *   – Internally, it delegates to LogisticsAssistantService's public API.
 */
object LogisticsAssistantManager {

    private const val TAG = "AssistantManager"

    // -----------------------------------------------------------------
    // SERVICE LIFECYCLE CONTROL
    // -----------------------------------------------------------------

    /**
     * Starts the assistant foreground service.
     *
     * This will create:
     *   - Foreground notification
     *   - MicStreamer
     *   - TTSPlayer
     *   - ChatWebSocket (/text)
     *
     * It is safe to call multiple times; Android will route it to
     * the existing service instance if it's already running.
     */
    fun startAssistant(context: Context) {
        Log.i(TAG, "▶️ startAssistant() requested")

        val intent = Intent(context, LogisticsAssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Requests the assistant service to stop itself.
     * This will:
     *   - Close WebSocket
     *   - Stop mic / TTS
     *   - Release wake-lock
     */
    fun stopAssistant(context: Context) {
        Log.w(TAG, "⏹ stopAssistant() requested")

        val intent = Intent(context, LogisticsAssistantService::class.java).apply {
            action = "STOP_SERVICE"
        }
        context.startService(intent)
    }


    fun resetAssistant(context: Context) {
        Log.w(TAG, "🔄 resetAssistant() requested — restarting service")
        stopAssistant(context)
        startAssistant(context)
    }


    // -----------------------------------------------------------------
    // TEXT PIPELINE API
    // -----------------------------------------------------------------

    /**
     * Sends a text command to the backend via /text WebSocket.
     *
     * This is the modern, recommended API.
     * No foreground service restart. No Intents.
     */
    fun sendText(text: String) {
        Log.d(TAG, "📤 sendText() → '$text'")
        LogisticsAssistantService.sendText(text)
    }

    /**
     * Legacy behavior:
     *   - Starts/activates LogisticsAssistantService via an Intent
     *   - Triggers onStartCommand("SEND_TEXT")
     *
     * Kept ONLY for backward compatibility with old flows that
     * relied on Intent-based command dispatch.
     *
     * New code SHOULD NOT use this.
     */
    fun dispatchTextViaService(context: Context, text: String) {
        Log.w(TAG, "[LEGACY] dispatchTextViaService() → '$text'")
        LogisticsAssistantService.sendTextLegacy(context, text)
    }


    // -----------------------------------------------------------------
    // MIC CONTROL API
    // -----------------------------------------------------------------

    /**
     * Pauses microphone streaming (muted but pipeline stays alive).
     */
    fun pauseMic() {
        Log.i(TAG, "🔇 pauseMic()")
        LogisticsAssistantService.pauseMic()
    }

    /**
     * Resumes microphone streaming and sending audio.
     */
    fun resumeMic() {
        Log.i(TAG, "🎙️ resumeMic()")
        LogisticsAssistantService.resumeMic()
    }

    /**
     * Returns true if mic pipeline exists (not necessarily active).
     */
    fun isMicAvailable(): Boolean {
        val available = LogisticsAssistantService.isMicAvailable()
        Log.d(TAG, "🎙️ isMicAvailable() → $available")
        return available
    }

    /**
     * Returns current mic state (OFF, MUTED, ACTIVE).
     */
    fun getMicState(): MicState {
        val state = LogisticsAssistantService.getMicState()
        Log.d(TAG, "🎙️ getMicState() → $state")
        return state
    }


    // -----------------------------------------------------------------
    // CONNECTION / STATUS API
    // -----------------------------------------------------------------

    /**
     * Returns true when the /text WebSocket is connected.
     */
    fun isChatConnected(): Boolean {
        val connected = LogisticsAssistantService.isChatConnected()
        Log.d(TAG, "🌐 isChatConnected() → $connected")
        return connected
    }


    // -----------------------------------------------------------------
    // TTS CONTROL API
    // -----------------------------------------------------------------

    /**
     * Plays a TTS audio URL using the internal TTSPlayer queue.
     * This delegates to the modern LogisticsAssistantService.playTts().
     */
    fun playTts(url: String) {
        Log.i(TAG, "🔊 playTts(url=$url)")
        LogisticsAssistantService.playTts(url)
    }

    /**
     * Legacy behavior:
     *   - Starts / uses the service via PLAY_TTS intent
     *
     * Kept for backward compatibility only. Prefer [playTts].
     */
    fun playTtsLegacy(url: String) {
        Log.w(TAG, "[LEGACY] playTtsLegacy(url=$url)")
        LogisticsAssistantService.playTtsLegacy(url)
    }
}
