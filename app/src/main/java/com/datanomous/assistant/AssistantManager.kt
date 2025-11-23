package com.datanomous.assistant

import android.content.Context
import android.util.Log
import com.datanomous.assistant.AssistantService.Companion.MicState

/**
 * =====================================================================
 * 🎛 AssistantManager
 * =====================================================================
 *
 * High-level façade for the voice assistant.
 */
object AssistantManager {

    private const val TAG = "Assistant - AssistantManager"

    // -----------------------------------------------------------------
    // SERVICE CONTROL / RESET
    // -----------------------------------------------------------------

    fun resetAssistant(context: Context) {
        Log.w(TAG, "🔄 resetAssistant() requested — soft reset")
        AssistantService.instance.softReset()
    }

    // -----------------------------------------------------------------
    // TEXT PIPELINE API
    // -----------------------------------------------------------------

    fun sendText(text: String) {
        Log.d(TAG, "📤 sendText() → '$text'")
        AssistantService.sendText(text)
    }

    fun dispatchTextViaService(context: Context, text: String) {
        Log.w(TAG, "[LEGACY] dispatchTextViaService() → '$text'")
    }

    // -----------------------------------------------------------------
    // MIC CONTROL API
    // -----------------------------------------------------------------

    fun pauseMic() {
        Log.i(TAG, "🔇 pauseMic()")
        AssistantService.uiMuteMic()
    }

    fun resumeMic() {
        Log.i(TAG, "🎙️ resumeMic()")
        AssistantService.uiActivateMic()
    }

    fun isMicAvailable(): Boolean {
        val available = AssistantService.uiIsMicAvailable()
        Log.d(TAG, "🎙️ isMicAvailable() → $available")
        return available
    }

    /**
     * Your service does not expose uiGetMicState().
     * We infer the state based on availability + internal micState.
     */
    fun getMicState(): MicState {
        val mic = AssistantService.micState
        Log.i(TAG, "🎙️ getMicState() → $mic")
        return mic
    }

    // -----------------------------------------------------------------
    // CONNECTION / STATUS API
    // -----------------------------------------------------------------

    fun isChatConnected(): Boolean {
        val connected = AssistantService.uiIsChatConnected()
        Log.d(TAG, "🌐 isChatConnected() → $connected")
        return connected
    }

    // -----------------------------------------------------------------
    // TTS CONTROL API (LEGACY)
    // -----------------------------------------------------------------

    fun playTts(url: String) {
        Log.i(TAG, "🔊 playTts(url=$url)")
    }

    fun playTtsLegacy(url: String) {
        Log.w(TAG, "[LEGACY] playTtsLegacy(url=$url)")
    }
}
