package com.datanomous.logisticsassistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * =====================================================================
 *  ANDROID TTS SINGLETON (FINAL)
 * =====================================================================
 *
 *  Responsibilities:
 *   ✓ Provide a safe, global TTS instance
 *   ✓ Speak immediately (queue flush supported)
 *   ✓ No memory leaks — uses appContext only
 *   ✓ Automatic fallback to Turkish or English
 *   ✓ Never blocks UI thread
 *   ✓ Graceful shutdown and restart
 *
 *  Used by:
 *    - TtsController
 *    - ResponseWebSocketClient (indirectly)
 *    - LogisticsAssistantService
 *
 *  NOTES:
 *    • All calls use applicationContext only (no Activity refs)
 *    • TTS engine is created lazily
 *    • Rapid speak(text) calls are safe
 */
object TextToSpeechEngine {

    private const val TAG = "TTS"

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var isReady = AtomicBoolean(false)

    @Volatile
    private var initializing = AtomicBoolean(false)

    /**
     * Initialize the TTS engine lazily.
     * Safe to call many times — only initializes once.
     */
    private fun ensureInit(appCtx: Context) {
        if (isReady.get() || initializing.get()) return

        synchronized(this) {
            if (isReady.get() || initializing.get()) return
            initializing.set(true)
        }

        try {
            Log.i(TAG, "🔄 Initializing Android TextToSpeech…")

            tts = TextToSpeech(appCtx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech

                    // Preferred language: Turkish
                    val resultTr = engine.setLanguage(Locale("tr", "TR"))
                    if (resultTr == TextToSpeech.LANG_MISSING_DATA ||
                        resultTr == TextToSpeech.LANG_NOT_SUPPORTED) {

                        Log.w(TAG, "⚠️ Turkish not supported → fallback to English")

                        val resultEn = engine.setLanguage(Locale.US)
                        if (resultEn == TextToSpeech.LANG_MISSING_DATA ||
                            resultEn == TextToSpeech.LANG_NOT_SUPPORTED) {

                            Log.e(TAG, "❌ English not supported — no usable TTS language")
                        }
                    }

                    engine.setSpeechRate(1.0f)
                    engine.setPitch(1.0f)

                    isReady.set(true)
                    initializing.set(false)

                    Log.i(TAG, "✅ TTS ready")
                } else {
                    Log.e(TAG, "❌ TextToSpeech init failed with code $status")
                    isReady.set(false)
                    initializing.set(false)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ ensureInit() failed: ${e.message}", e)
            isReady.set(false)
            initializing.set(false)
        }
    }

    /**
     * Speak text immediately.
     *
     * flushQueue=true:
     *    Interrupt any ongoing speech immediately.
     */
    fun run(appContext: Context, text: String, flushQueue: Boolean = true) {
        if (text.isBlank()) {
            Log.w(TAG, "⚠️ run(): blank text ignored")
            return
        }

        // Lazy initialization
        ensureInit(appContext.applicationContext)

        val engine = tts
        if (engine == null || !isReady.get()) {
            Log.w(TAG, "⚠️ TTS not ready yet → retry in 200ms")
            // Retry later (non-blocking)
            android.os.Handler(appContext.mainLooper).postDelayed({
                run(appContext, text, flushQueue)
            }, 200)
            return
        }

        try {
            Log.i(TAG, "🗣 Speaking: \"$text\"")

            engine.speak(
                text,
                if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "tts-${System.currentTimeMillis()}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ TTS.speak() failed: ${e.message}", e)
        }
    }

    /**
     * Stop speaking immediately.
     * Safe to call even if TTS isn't active.
     */
    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "❌ stop() failed: ${e.message}", e)
        }
    }

    /**
     * Full shutdown — used when the service is destroyed.
     */
    fun shutdown() {
        try {
            Log.i(TAG, "🛑 Shutting down TTS engine")
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "❌ shutdown() failed: ${e.message}", e)
        } finally {
            tts = null
            isReady.set(false)
            initializing.set(false)
        }
    }
}
