package com.datanomous.logisticsassistant.tts

import android.content.Context
import android.util.Log

/**
 * TtsController
 *
 * Thin wrapper around the existing TTS singleton.
 * Use this everywhere instead of calling TTS.run() directly.
 *
 * Benefits:
 *  - Single abstraction point for speech output
 *  - Easy to swap engine or add hooks later
 *  - Keeps WS / UI code clean
 */
class TtsController(
    context: Context
) {

    private val TAG = "TtsController"
    private val appContext = context.applicationContext

    /**
     * Speak text using Android / Google TTS.
     *
     * @param text       Text to speak (ignored if blank).
     * @param flushQueue If true, flushes current queue before speaking.
     */
    fun speak(text: String, flushQueue: Boolean = false) {
        if (text.isBlank()) {
            Log.w(TAG, "speak() called with blank text, ignoring")
            return
        }

        try {
            TextToSpeechEngine.run(appContext, text, flushQueue)
        } catch (e: Exception) {
            Log.e(TAG, "speak() failed: ${e.message}", e)
        }
    }

    /**
     * Stop speaking ASAP.
     *
     * NOTE:
     *  - For now, this is mapped to TTS.shutdown(), which is a hard stop.
     *  - Later we can extend TTS to provide a lightweight stop() without full shutdown.
     */
    fun stop() {
        try {
            Log.i(TAG, "stop() → shutting down TTS")
            TextToSpeechEngine.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "stop() failed: ${e.message}", e)
        }
    }

    /**
     * Full shutdown (e.g. when Service is destroyed).
     */
    fun shutdown() {
        try {
            Log.i(TAG, "shutdown() → TTS.shutdown()")
            TextToSpeechEngine.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "shutdown() failed: ${e.message}", e)
        }
    }
}
