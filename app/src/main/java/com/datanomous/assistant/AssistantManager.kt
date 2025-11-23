package com.datanomous.logisticsassistant

import android.content.Context
import android.util.Log
import com.datanomous.logisticsassistant.AssistantService.Companion.MicState

/**
 * =====================================================================
 * 🎛 LogisticsAssistantManager
 * =====================================================================
 *
 * High-level façade for the voice assistant.
 *
 * Responsibilities:
 *   ✔ Control the LogisticsAssistantService (foreground service lifecycle is started by UI)
 *   ✔ Provide a clean API for UI layer (Compose, Activities, etc.)
 *   ✔ Delegate mic / text / TTS actions to LogisticsAssistantService
 *
 * Notes:
 *   – This object should be the ONLY thing the UI talks to.
 *   – It hides service + pipeline details from the presentation layer.
 *   – Internally, it delegates to LogisticsAssistantService's public API.
 */
object AssistantManager {

    private const val TAG = "LogisticsAssistant - Assistant Manager"

    // -----------------------------------------------------------------
    // SERVICE CONTROL / RESET
    // -----------------------------------------------------------------

    fun resetAssistant(context: Context) {
        Log.w(TAG, "🔄 resetAssistant() requested — soft reset")
        // LogisticsAssistantService.hardRestartApp(context)  // full relaunch, kept as option
        AssistantService.softReset()
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
        AssistantService.sendText(text)
    }

    /**
     * Legacy behavior:
     *   - Uses Intent-based dispatch into LogisticsAssistantService
     *   - Triggers onStartCommand("SEND_TEXT")
     *
     * Kept ONLY for backward compatibility with old flows that
     * relied on Intent-based command dispatch.
     *
     * New code SHOULD NOT use this.
     */
    fun dispatchTextViaService(context: Context, text: String) {
        Log.w(TAG, "[LEGACY] dispatchTextViaService() → '$text'")
        AssistantService.sendTextLegacy(context, text)
    }


    // -----------------------------------------------------------------
    // MIC CONTROL API
    // -----------------------------------------------------------------

    /**
     * Pauses microphone streaming (muted but pipeline stays alive).
     */
    fun pauseMic() {
        Log.i(TAG, "🔇 pauseMic()")
        AssistantService.pauseMic()
    }

    /**
     * Resumes microphone streaming and sending audio.
     */
    fun resumeMic() {
        Log.i(TAG, "🎙️ resumeMic()")
        AssistantService.resumeMic()
    }

    /**
     * Returns true if mic pipeline exists and is ACTIVE.
     */
    fun isMicAvailable(): Boolean {
        val available = AssistantService.isMicAvailable()
        Log.d(TAG, "🎙️ isMicAvailable() → $available")
        return available
    }

    /**
     * Returns current mic state (OFF, MUTED, ACTIVE).
     */
    fun getMicState(): MicState {
        val state = AssistantService.getMicState()
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
        val connected = AssistantService.isChatConnected()
        Log.d(TAG, "🌐 isChatConnected() → $connected")
        return connected
    }


    // -----------------------------------------------------------------
    // TTS CONTROL API (LEGACY WAV PATH)
    // -----------------------------------------------------------------

    /**
     * Plays a TTS audio URL using the internal TTSPlayer queue.
     * This delegates to LogisticsAssistantService.playTts().
     *
     * Kept for compatibility with old server-side WAV URLs.
     * New flow uses /response + Google TTS on-device.
     */
    fun playTts(url: String) {
        Log.i(TAG, "🔊 playTts(url=$url)")
        AssistantService.playTts(url)
    }

    /**
     * Legacy behavior:
     *   - Starts / uses the service via PLAY_TTS intent
     *
     * Kept for backward compatibility only. Prefer [playTts].
     */
    fun playTtsLegacy(url: String) {
        Log.w(TAG, "[LEGACY] playTtsLegacy(url=$url)")
        AssistantService.playTtsLegacy(url)
    }
}
