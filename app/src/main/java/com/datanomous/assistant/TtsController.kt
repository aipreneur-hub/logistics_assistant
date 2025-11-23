package com.datanomous.assistant.tts

import android.content.Context
import android.util.Log

/**
 * TtsController
 *
 * Thin wrapper around the existing TTS singleton.
 * Single abstraction point for speech output.
 */
class TtsController(
    context: Context
) {

    private val TAG = "TtsController"
    private val appContext = context.applicationContext

    // Tracks last spoken canonical text to work around Android TTS
    // "same-utterance" suppression.
    // Canonical form: trimmed + lowercased.
    @Volatile
    private var lastCanonicalText: String? = null

    /**
     * Speak text using Android / Google TTS.
     *
     * @param text       Text to speak (ignored if blank).
     * @param flushQueue If true, flushes queue before speaking (interrupt mode).
     */
    fun speak(text: String, flushQueue: Boolean = false) {
        val raw = text ?: return

        if (raw.isBlank()) {
            Log.w(TAG, "speak() called with blank text → ignoring")
            return
        }

        try {
            val canonical = raw.trim().lowercase()

            // Decide what will actually be sent to the TTS engine.
            val speakText: String = synchronized(this) {
                val last = lastCanonicalText
                val result: String

                if (!canonical.isBlank() && last != null && canonical == last) {
                    // 🔥 Workaround: Android TTS often suppresses identical repeats.
                    // Add a trailing space so text is *slightly* different,
                    // but sounds the same to the user.
                    result = raw + " "
                    Log.d(
                        TAG,
                        "Forcing re-speak of identical text by appending space. " +
                                "canonical='$canonical'"
                    )
                } else {
                    result = raw
                }

                // Update last canonical text AFTER decision
                lastCanonicalText = canonical
                result
            }

            Log.i(TAG, "🗣 speak(text='${raw.take(80)}', effective='${speakText.take(80)}')")
            TextToSpeechEngine.run(appContext, speakText, flushQueue)

        } catch (e: Exception) {
            Log.e(TAG, "❌ speak() failed: ${e.message}", e)
        }
    }

    /**
     * Future-proof call:
     * Allow engine to play non-text audio types later (earcons, notification tones, etc).
     */
    fun play(type: String, payload: Any?) {
        try {
            when (type.lowercase()) {
                "text" -> speak(payload as? String ?: return)
                else -> {
                    Log.w(TAG, "⚠️ Unsupported play(type='$type') — ignoring")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ play() failed: ${e.message}", e)
        }
    }

    /**
     * Stop speaking immediately.
     */
    fun stop() {
        try {
            Log.i(TAG, "⛔ stop() requested")
            TextToSpeechEngine.stop()

        } catch (e: Exception) {
            Log.e(TAG, "❌ stop() failed: ${e.message}", e)
        }
    }

    /**
     * Full shutdown (used by Service.onDestroy).
     */
    fun shutdown() {
        try {
            Log.i(TAG, "🛑 shutdown() → TTS.shutdown()")
            TextToSpeechEngine.shutdown()
            // Optional: clear last text so next session starts fresh
            lastCanonicalText = null

        } catch (e: Exception) {
            Log.e(TAG, "❌ shutdown() failed: ${e.message}", e)
        }
    }
}
