package com.datanomous.assistant.tts

import android.content.Context
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
 *
 *  FEATURES:
 *   ✓ Choose engine: GOOGLE or SAMSUNG
 *   ✓ High-quality neural Turkish Google voices (TR-TR-Standard…)
 *   ✓ Samsung neural Turkish voice (Live Speech Turkish)
 *   ✓ Automatically picks best TR voice per engine
 *   ✓ Auto fallback to English
 *   ✓ Thread-safe, retry-safe, lazy initialization
 *
 *  FIXES:
 *   ✓ Avoid letter-by-letter spelling for ALL-CAPS words (e.g. ULKER)
 *   ✓ Normalize spaced 3-digit sequences → single number (7 2 9 → 729)
 *   ✓ Special-case “Barkod 729” → “Barkod 7 2 9” for stable barcode reading
 */
object TextToSpeechEngine {

    private const val TAG = "TTS"
    private const val RETRY_DELAY_MS = 200L

    // -----------------------------------------------------------
    // 🔧 CONFIG — SWITCH ENGINE HERE (GOOGLE or SAMSUNG)
    // -----------------------------------------------------------

    enum class EngineType { GOOGLE, SAMSUNG }

    var engineType: EngineType = EngineType.GOOGLE

    val googleEngine = "com.google.android.tts"
    val samsungEngine = "com.samsung.SMT"

    /** Preferred language (TR) */
    var preferredLocale: Locale = Locale("tr", "TR")

    /** Fallback EN */
    var fallbackLocale: Locale = Locale.US

    /** Preferred Google Turkish neural voice */
    var preferredGoogleVoiceName: String? = "tr-tr-x-oda-network"
    // Other options:
    //  "tr-tr-x-eyo-network"
    //  "tr-tr-x-afs-local"
    //  "tr-tr-x-oda-local"

    // -----------------------------------------------------------

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
    // LANGUAGE CONFIG
    // =====================================================================
    private fun configureLanguage() {
        try {
            val engine = tts ?: return

            var result = engine.setLanguage(preferredLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w(TAG, "⚠️ TR not supported → fallback EN")
                result = engine.setLanguage(fallbackLocale)
            }

            // Keep speech rate & pitch stable
            engine.setSpeechRate(1.0f)
            engine.setPitch(1.0f)

            Log.i(TAG, "🌐 Language set: ${engine.language} (result=$result)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ configureLanguage(): ${e.message}")
        }
    }

    // =====================================================================
    // VOICE CONFIG (DIFFERENT FOR GOOGLE & SAMSUNG)
    // =====================================================================
    private fun configureVoice() {
        try {
            val engine = tts ?: return
            val voices: Set<Voice> = engine.voices ?: emptySet()

            if (voices.isEmpty()) {
                Log.w(TAG, "⚠️ No voices found on engine")
                isReady.set(true)
                return
            }

            voices.forEach { v ->
                Log.i(TAG, "🔊 Voice: ${v.name} | locale=${v.locale} | quality=${v.quality}")
            }

            when (engineType) {

                // -------------------------------------------------------
                // 🔵 GOOGLE
                // -------------------------------------------------------
                EngineType.GOOGLE -> {
                    val preferred = preferredGoogleVoiceName?.let { prefName ->
                        voices.firstOrNull { it.name == prefName }
                    }

                    if (preferred != null) {
                        engine.voice = preferred
                        Log.i(TAG, "✅ Selected Google voice: ${preferred.name}")
                    } else {
                        val fallbackTR = voices
                            .filter { it.locale.language == "tr" }
                            .maxByOrNull { it.quality } // pick highest quality TR voice
                        if (fallbackTR != null) {
                            engine.voice = fallbackTR
                            Log.i(TAG, "🔄 Google fallback TR: ${fallbackTR.name}")
                        } else {
                            Log.w(TAG, "⚠️ No Turkish Google voice found, using engine default")
                        }
                    }
                }

                // -------------------------------------------------------
                // 🟡 SAMSUNG (auto Turkish voice)
                // -------------------------------------------------------
                EngineType.SAMSUNG -> {
                    val turkishVoice = voices
                        .filter { it.locale.language == "tr" }
                        .maxByOrNull { it.quality }

                    if (turkishVoice != null) {
                        engine.voice = turkishVoice
                        Log.i(TAG, "🇹🇷 Samsung TR voice selected: ${turkishVoice.name}")
                    } else {
                        Log.w(TAG, "⚠️ Samsung TR voice not found — using default")
                    }
                }
            }

            isReady.set(true)

        } catch (e: Exception) {
            Log.e(TAG, "❌ configureVoice(): ${e.message}")
        }
    }

    // =====================================================================
    // TEXT NORMALIZATION LAYER
    // =====================================================================

    /**
     * Normalize text before sending to Android TTS:
     *  - Convert ALL-CAPS Turkish words (3+ letters, no digits) into Title Case
     *    so they are not spelled letter-by-letter (ULKER → Ulker).
     *  - Join spaced 3-digit sequences ("7 2 9" → "729") so Google reads numbers.
     *  - For "Barkod 729" / "Barkod: 729" convert digits to spaced digits:
     *      → "Barkod 7 2 9" (always spelled digit-by-digit).
     */
    private fun normalizeTextForEngine(text: String): String {
        if (text.isBlank()) return text

        var normalized = text

        normalized = normalizeUppercaseWords(normalized)
        normalized = forceWholeNumberReading(normalized)   // <── NEW
        normalized = normalizeBarcodePattern(normalized)   // barcode stays digit-by-digit

        return normalized
    }
    /**
     * Converts ALL-CAPS tokens (A-Z + Turkish chars, no digits) of length >= 3
     * to Title Case to avoid letter-by-letter spelling.
     *
     * Example:
     *   "DDG034-1 adresinden ULKER DANKEK" ->
     *   "DDG034-1 adresinden Ulker Dankek"
     */
    private fun normalizeUppercaseWords(input: String): String {
        // Includes Turkish uppercase letters
        val upperWordRegex = Regex("\\b[ABCÇDEFGĞHIİJKLMNOÖPRSŞTUÜVYZ]{3,}\\b")

        return upperWordRegex.replace(input) { match ->
            val word = match.value

            // Skip if contains digits (e.g. DDG034)
            if (word.any { it.isDigit() }) {
                word
            } else {
                // Title case: Ulker, DANKEK → Dankek
                word.lowercase(preferredLocale)
                    .replaceFirstChar { it.titlecase(preferredLocale) }
            }
        }
    }

    /**
     * Detects "Barkod 729" / "Barkod: 729." and converts the number part
     * to spaced digits so TTS doesn't treat it as an ordinal or weird form.
     *
     *   "Barkod: 729." → "Barkod: 7 2 9."
     */
    /**
     * Detects "Barkod 729." / "Barkod: 729." and converts:
     *
     *   "Barkod: 729." → "Barkod numarası 7 2 9"
     *
     * We:
     *  - expand digits to spaced digits (7 2 9)
     *  - DROP the trailing '.' so Google doesn't read it as an ordinal
     *  - insert "numarası" to make the phrase more natural for Turkish TTS
     */
    private fun normalizeBarcodePattern(input: String): String {
        val regex = Regex("(?i)(barkod\\s*[: ]\\s*)(\\d{2,8})(\\b)")

        return regex.replace(input) { match ->
            val prefix = match.groupValues[1]
            val digits = match.groupValues[2]

            // Force correct reading: "Barkod 729"
            "$prefix$digits ile bitmeli."
        }
    }


    /**
     * Join spaced three-digit sequences into a single number:
     *   "7 2 9" -> "729"
     *   "1 0 0" -> "100"
     *
     * This makes Google TTS read them as whole numbers instead of digits.
     * Barkod-specific patterns are re-expanded later by normalizeBarcodePattern().
     */
    private fun normalizeSpacedThreeDigitNumbers(input: String): String {
        // matches: "7 2 9" with word boundaries
        val spaced3Digits = Regex("\\b(\\d)\\s+(\\d)\\s+(\\d)\\b")

        return spaced3Digits.replace(input) { match ->
            val d1 = match.groupValues[1]
            val d2 = match.groupValues[2]
            val d3 = match.groupValues[3]
            d1 + d2 + d3
        }
    }

    // =====================================================================
    // SPEAK
    // =====================================================================
    fun run(appContext: Context, text: String, flush: Boolean = true) {
        if (text.isBlank()) return

        // Normalize text to avoid TTS quirks
        val safeText = normalizeTextForEngine(text)

        ensureInit(appContext.applicationContext)

        val engine = tts
        if (engine == null || !isReady.get()) {
            Log.w(TAG, "TTS not ready yet, scheduling retry…")
            scheduleRetry(appContext, safeText, flush)
            return
        }

        try {
            Log.i(TAG, "🗣 speak(normalized='${safeText.take(120)}', flush=$flush)")
            try {
                // Prevent Google TTS queue deadlocks
                engine.stop()
            } catch (_: Throwable) {
            }

            engine.speak(
                safeText,
                if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "tts-${System.currentTimeMillis()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ speak(): ${e.message}", e)
        }
    }

    // =====================================================================
    private fun scheduleRetry(ctx: Context, text: String, flush: Boolean) {
        if (retryScheduled.getAndSet(true)) return

        mainHandler.postDelayed({
            retryScheduled.set(false)
            run(ctx, text, flush)
        }, RETRY_DELAY_MS)
    }

    // =====================================================================
    fun stop() = try {
        tts?.stop()
    } catch (_: Exception) {
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        } finally {
            tts = null
            isReady.set(false)
            initializing.set(false)
            retryScheduled.set(false)
        }
    }
}


/**
 * Forces 2–4 digit numbers to be read as whole numbers
 * by adding a neutral suffix "adet" unless it's a barcode.
 */
private fun forceWholeNumberReading(input: String): String {
    // ignore barcodes (handled separately)
    val barcodeRegex = Regex("(?i)barkod")
    if (barcodeRegex.containsMatchIn(input)) return input

    // match 2–4 digit standalone numbers
    val numberRegex = Regex("\\b(\\d{2,4})\\b")

    return numberRegex.replace(input) { match ->
        val num = match.groupValues[1]
        "${num} adet"
    }
}