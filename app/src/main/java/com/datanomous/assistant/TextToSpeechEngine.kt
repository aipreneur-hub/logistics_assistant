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
 *   (ORIGINAL BEHAVIOR PRESERVED — WITH MULTI-PAUSE SUPPORT)
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

    private const val PAUSE_PATTERN = "\\[\\[PAUSE_(\\d+)S]]"


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
    // SYSTEM VOLUME
    // =====================================================================
    private fun setMaxVolume(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        } catch (_: Throwable) {}
    }


    // =====================================================================
    // LANGUAGE & VOICE
    // =====================================================================
    private fun configureLanguage() {
        try {
            val engine = tts ?: return
            var result = engine.setLanguage(preferredLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setLanguage(fallbackLocale)
            }
            engine.setSpeechRate(1.0f)
            engine.setPitch(1.0f)
        } catch (_: Exception) {}
    }

    private fun configureVoice() {
        try {
            val engine = tts ?: return
            val voices = engine.voices ?: emptySet()

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

        var out = text

        out = normalizeUppercaseWords(out)
        out = normalizeCodeNumbers(out)
        out = normalizeBarcodePattern(out)

        return out
    }

    private fun normalizeUppercaseWords(input: String): String {
        if (input.isBlank()) return input

        // Sadece TAMAMEN büyük harfli (TR harfleri dahil) 3+ harfli kelimeleri hedefliyoruz
        val upperWordRegex = Regex("\\b([A-ZÇĞİÖŞÜ]{3,})\\b")

        return upperWordRegex.replace(input) { match ->
            val word = match.groupValues[1]       // Örn: "IIG", "ÜLKER", "HAYLAYF"

            // 1) Eğer bu kelimenin hemen ardından boşluk / tire + 2–4 rakam geliyorsa
            //    bunu raf kodu kabul ediyoruz → DOKUNMUYORUZ
            val nextIndex = match.range.last + 1
            if (nextIndex < input.length) {
                val trailing = input.substring(nextIndex)
                val isShelfCode = Regex("^\\s*[- ]?\\d{2,4}").containsMatchIn(trailing)
                if (isShelfCode) {
                    // Örn: "IIG 050" → IIG raf kodu, aynen kalsın
                    return@replace word
                }
            }

            // 2) Diğer tüm uppercase kelimeleri (ÜLKER, HAYLAYF, OBAÇAY…) title-case yap
            val lower = word.lowercase(preferredLocale)
            lower.replaceFirstChar { it.titlecase(preferredLocale) }
        }
    }



    private fun normalizeCodeNumbers(input: String): String {

        val regex = Regex("([A-Za-z]{2,3})\\s*-?\\s*(\\d{2,4})(?=\\b|[,\\s])")

        return regex.replace(input) { m ->

            val prefix = m.groupValues[1].uppercase()
            var digits = m.groupValues[2]

            digits = digits.trimStart('0')
            if (digits.isBlank()) digits = "0"

            val spokenNumber = numberToTurkish(digits.toInt())

            val spokenPrefix = when (prefix) {
                "IIG" -> "ii güney"
                "IIK" -> "ii kuzey"
                else -> prefix.lowercase()
            }

            "$spokenPrefix $spokenNumber"
        }
    }











    private fun numberToTurkish(n: Int): String {
        val ones = arrayOf("", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz")
        val tens = arrayOf("", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan")

        return when {
            n < 10 -> ones[n]
            n < 100 -> tens[n / 10] + if (n % 10 > 0) " " + ones[n % 10] else ""
            n < 1000 -> {
                val hundreds = if (n / 100 == 1) "yüz" else ones[n / 100] + " yüz"
                (hundreds + " " + numberToTurkish(n % 100)).trim()
            }
            else -> n.toString()
        }
    }

    private fun normalizeBarcodePattern(input: String): String {
        val regex = Regex("(?i)(barkod\\s*[: ]\\s*)(\\d{2,8})(\\b)")
        return regex.replace(input) { match ->
            val prefix = match.groupValues[1]
            val digits = match.groupValues[2]
            "$prefix$digits ile bitmeli."
        }
    }


    // =====================================================================
    // MULTI PAUSE PARSER
    // =====================================================================
    private fun handlePauseSequence(ctx: Context, raw: String) {

        val regex = Regex(PAUSE_PATTERN, RegexOption.IGNORE_CASE)
        val segments = mutableListOf<Pair<String, Int>>()

        var lastIndex = 0

        for (match in regex.findAll(raw)) {

            val pauseSeconds = match.groupValues[1].toIntOrNull() ?: 1

            // BEFORE text — normalize ALWAYS
            val beforeRaw = raw.substring(lastIndex, match.range.first).trim()
            if (beforeRaw.isNotEmpty()) {
                val normalized = normalizeTextForEngine(beforeRaw)
                segments.add(normalized to pauseSeconds)
            }

            lastIndex = match.range.last + 1
        }

        // FINAL PART — also normalize
        val lastRaw = raw.substring(lastIndex).trim()
        if (lastRaw.isNotEmpty()) {
            val normalizedLast = normalizeTextForEngine(lastRaw)
            segments.add(normalizedLast to -1)
        }

        speakSequence(ctx, segments)
    }




    private fun speakSequence(ctx: Context, segments: List<Pair<String?, Int>>) {
        if (segments.isEmpty()) return

        val (text, pauseSeconds) = segments[0]

        if (!text.isNullOrEmpty()) {
            speakRawWithCallback(ctx, text) {
                if (pauseSeconds == -1) return@speakRawWithCallback
                mainHandler.postDelayed({
                    speakSequence(ctx, segments.drop(1))
                }, pauseSeconds * 1000L)
            }
        }
    }


    private fun speakRawWithCallback(
        ctx: Context,
        text: String,
        onDone: () -> Unit
    ) {
        ensureInit(ctx.applicationContext)

        if (!isReady.get()) {
            mainHandler.postDelayed({
                speakRawWithCallback(ctx, text, onDone)
            }, 150)
            return
        }

        val engine = tts ?: return

        try {
            setMaxVolume(ctx)

            val utteranceId = "pause-${System.currentTimeMillis()}"

            engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onDone(id: String?) {
                    if (id == utteranceId) onDone()
                }
                override fun onError(id: String?) {}
                override fun onStart(id: String?) {}
            })

            engine.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )

        } catch (_: Exception) {}
    }


    // =====================================================================
    // RAW SPEECH FOR PAUSE SEGMENTS (NO NORMALIZE)
    // =====================================================================
    private fun speakRaw(ctx: Context, text: String, flush: Boolean) {
        ensureInit(ctx.applicationContext)

        // TTS hazır değilse tekrar dene
        if (!isReady.get()) {
            mainHandler.postDelayed({
                speakRaw(ctx, text, flush)
            }, 150)
            return
        }

        val engine = tts ?: return

        try {
            setMaxVolume(ctx)

            // ❗ STOP KALDIRILDI → pause segmentleri kesilmesin
            engine.speak(
                text,
                if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "raw-${System.currentTimeMillis()}"
            )

        } catch (_: Exception) {}
    }


    // =====================================================================
    // PUBLIC SPEAK
    // =====================================================================
    fun run(appContext: Context, text: String, flush: Boolean = true) {

        if (text.isBlank()) return

        if (Regex(PAUSE_PATTERN, RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            handlePauseSequence(appContext, text)
            return
        }

        val safeText = normalizeTextForEngine(text)

        ensureInit(appContext.applicationContext)

        val engine = tts
        if (engine == null || !isReady.get()) {
            scheduleRetry(appContext, safeText, flush)
            return
        }

        try {
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
        tts = null
        isReady.set(false)
        initializing.set(false)
        retryScheduled.set(false)
    }
}


// =====================================================================
// FORCE NUMBER READING
// =====================================================================
private fun forceWholeNumberReading(input: String): String {
    val barcodeRegex = Regex("(?i)barkod")
    if (barcodeRegex.containsMatchIn(input)) return input

    val numberRegex = Regex("\\b(\\d{2,4})\\b")
    return numberRegex.replace(input) { match ->
        val num = match.groupValues[1]
        "${num} adet"
    }
}
