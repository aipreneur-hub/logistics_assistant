package com.datanomous.assistant.tts

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * =====================================================================
 *   ANDROID TTS ENGINE — GOOGLE <-> SAMSUNG SWITCHABLE
 * =====================================================================
 */

object TextToSpeechEngine {

    private const val TAG = "TTS"
    private const val RETRY_DELAY_MS = 200L

    enum class EngineType { GOOGLE, SAMSUNG }

    var engineType: EngineType = EngineType.GOOGLE

    val googleEngine = "com.google.android.tts"
    val samsungEngine = "com.samsung.SMT"

    var preferredLocale: Locale = Locale("tr", "TR")
    var fallbackLocale: Locale = Locale.US

    var preferredGoogleVoiceName: String? = "tr-tr-x-oda-network"

    @Volatile private var tts: TextToSpeech? = null
    private val isReady = AtomicBoolean(false)
    private val initializing = AtomicBoolean(false)
    private val retryScheduled = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // =====================================================================
    // INIT
    // =====================================================================
    private fun ensureInit(appCtx: Context) {
        if (isReady.get() || initializing.get()) return

        synchronized(this) {
            if (isReady.get() || initializing.get()) return
            initializing.set(true)
        }

        val selectedEngine = when (engineType) {
            EngineType.GOOGLE -> googleEngine
            EngineType.SAMSUNG -> samsungEngine
        }

        try {
            Log.i(TAG, "🔄 Initializing TTS (engine=$selectedEngine)…")

            tts = TextToSpeech(
                appCtx,
                { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        configureLanguage()
                        configureVoice()
                    } else {
                        Log.e(TAG, "❌ TTS init failed status=$status")
                    }
                    initializing.set(false)
                },
                selectedEngine
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ ensureInit(): ${e.message}", e)
            initializing.set(false)
        }
    }

    // =====================================================================
    // 🔊 SYSTEM VOLUME CONTROL
    // =====================================================================
    private fun setMaxVolume(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        } catch (_: Throwable) {}
    }

    fun boostVolume(ctx: Context, steps: Int = 1) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        repeat(steps) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
            )
        }
    }

    // =====================================================================
    // LANGUAGE CONFIG
    // =====================================================================
    private fun configureLanguage() {
        try {
            val engine = tts ?: return

            var result = engine.setLanguage(preferredLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                result = engine.setLanguage(fallbackLocale)
            }

            engine.setSpeechRate(1.0f)
            engine.setPitch(1.0f)

        } catch (_: Exception) {}
    }

    // =====================================================================
    // VOICE CONFIG
    // =====================================================================
    private fun configureVoice() {
        try {
            val engine = tts ?: return
            val voices: Set<Voice> = engine.voices ?: emptySet()

            if (voices.isEmpty()) {
                isReady.set(true)
                return
            }

            when (engineType) {

                EngineType.GOOGLE -> {
                    val preferred = preferredGoogleVoiceName?.let { name ->
                        voices.firstOrNull { it.name == name }
                    }

                    if (preferred != null) {
                        engine.voice = preferred
                    } else {
                        val fallbackTR = voices
                            .filter { it.locale.language == "tr" }
                            .maxByOrNull { it.quality }

                        if (fallbackTR != null) engine.voice = fallbackTR
                    }
                }

                EngineType.SAMSUNG -> {
                    val tr = voices
                        .filter { it.locale.language == "tr" }
                        .maxByOrNull { it.quality }
                    if (tr != null) engine.voice = tr
                }
            }

            isReady.set(true)

        } catch (_: Exception) {}
    }

    // =====================================================================
    // NORMALIZATION
    // =====================================================================
    private fun normalizeTextForEngine(text: String): String {
        if (text.isBlank()) return text

        var normalized = text

        normalized = normalizeUppercaseWords(normalized)
        normalized = forceWholeNumberReading(normalized)
        normalized = normalizeBarcodePattern(normalized)
        normalized = normalizeCodeNumbers(normalized)

        return normalized
    }

    private fun normalizeUppercaseWords(input: String): String {
        val upperWordRegex = Regex("\\b[ABCÇDEFGĞHIİJKLMNOÖPRSŞTUÜVYZ]{3,}\\b")
        return upperWordRegex.replace(input) { match ->
            val word = match.value
            if (word.any { it.isDigit() }) word
            else word.lowercase(preferredLocale)
                .replaceFirstChar { it.titlecase(preferredLocale) }
        }
    }


    private fun normalizeCodeNumbers(input: String): String {
        // örnek eşleşmeler: "HHG 111", "IIG073-1", "IIG 161", "AAK028-1"
        val regex = Regex("([A-Za-z]{2,}\\s*-?)(\\d{2,6})")
        return regex.replace(input) { match ->
            val prefix = match.groupValues[1]
            val digits = match.groupValues[2]
            "$prefix$digits."
        }
    }

    private fun normalizeBarcodePattern(input: String): String {
        val regex = Regex("(?i)(barkod\\s*[: ]\\s*)(\\d{2,8})(\\b)")
        return regex.replace(input) { match ->
            val prefix = match.groupValues[1]
            val digits = match.groupValues[2]

            // Let Google TTS read the number as a whole: "Barkod 729 ile bitmeli."
            "$prefix$digits ile bitmeli."
        }
    }


    // =====================================================================
    // SPEAK
    // =====================================================================
    fun run(appContext: Context, text: String, flush: Boolean = true) {
        if (text.isBlank()) return

        val safeText = normalizeTextForEngine(text)

        ensureInit(appContext.applicationContext)

        val engine = tts
        if (engine == null || !isReady.get()) {
            scheduleRetry(appContext, safeText, flush)
            return
        }

        try {
            // 🔊 BOOST BEFORE SPEAK
            setMaxVolume(appContext)

            engine.stop()

            engine.speak(
                safeText,
                if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "tts-${System.currentTimeMillis()}"
            )
        } catch (_: Exception) {}
    }

    private fun scheduleRetry(ctx: Context, text: String, flush: Boolean) {
        if (retryScheduled.getAndSet(true)) return
        mainHandler.postDelayed({
            retryScheduled.set(false)
            run(ctx, text, flush)
        }, RETRY_DELAY_MS)
    }

    fun stop() = try { tts?.stop() } catch (_: Exception) {}
    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        finally {
            tts = null
            isReady.set(false)
            initializing.set(false)
            retryScheduled.set(false)
        }
    }
}

/**
 * Forces 2–4 digit numbers to be read as whole numbers
 */
private fun forceWholeNumberReading(input: String): String {
    val barcodeRegex = Regex("(?i)barkod")
    if (barcodeRegex.containsMatchIn(input)) return input

    val numberRegex = Regex("\\b(\\d{2,4})\\b")
    return numberRegex.replace(input) { match ->
        val num = match.groupValues[1]
        "${num} adet"
    }
}
