package com.datanomous.logisticsassistant.core

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.datanomous.logisticsassistant.tts.TtsController

/**
 * Central handler for assistant messages coming from /response WS.
 *
 * Responsibilities:
 * - Trigger Google TTS playback
 * - Expose last assistant text for UI (LiveData)
 * - Optional buffering or logging
 * - Keeps ViewModels thin
 */
class ResponseDispatcher(
    private val tts: TtsController
) {

    private val TAG = "AssistantResponseHandler"

    // UI can observe this to update chat/history
    val lastAssistantMessage = MutableLiveData<String>()

    /**
     * Called by ResponseWebSocketClient when a new assistant_response arrives.
     */
    fun handleAssistantMessage(text: String) {
        try {
            // Update UI observers
            lastAssistantMessage.postValue(text)

            // Speak immediately
            tts.speak(text)

            Log.i(TAG, "Assistant message handled → $text")

        } catch (e: Exception) {
            Log.e(TAG, "handleAssistantMessage error: $e")
        }
    }

    /**
     * Optional: gracefully stop ongoing TTS if needed.
     */
    fun stopSpeaking() {
        try {
            tts.stop()
        } catch (_: Exception) {}
    }
}
