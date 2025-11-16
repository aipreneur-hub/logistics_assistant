package com.datanomous.logisticsassistant.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * =====================================================================
 *  MicStreamer — Mic → VAD → Segmentation → /stt WebSocket
 * =====================================================================
 *
 *  RESPONSIBILITIES:
 *  -----------------
 *  - Owns AudioRecord lifecycle.
 *  - Owns WebSocket connection to STT server (/stt).
 *  - Performs:
 *        • PCM capture
 *        • VAD-based speech detection
 *        • Segmentation into utterances
 *        • Sending segments to STT backend
 *  - Calls onText(text) when STT response arrives.
 *
 *  INTEGRATION WITH SERVICE:
 *  -------------------------
 *  - Service owns MicStreamer instance.
 *  - Service controls mic send mode:
 *        • start(scope)        → start WS + AudioRecord
 *        • muteSending()       → record but do NOT send segments (during TTS)
 *        • activateSending()   → record + send segments (user speaking)
 *        • stop()              → fully stop mic + WS.
 *
 *  STATE INSIDE MICSTREAMER:
 *  -------------------------
 *  - isStreaming (AtomicBoolean) → whether WS + AudioRecord loop is running.
 *  - sendActive (volatile Boolean) → whether we actually SEND segments to STT.
 *      • Controlled only via activateSending()/muteSending().
 *
 *  LOGGING PREFIXES:
 *  -----------------
 *  - [MIC]     → MicStreamer lifecycle
 *  - [WS-STT]  → WebSocket(/stt) operations
 *  - [AUDIO]   → AudioRecord setup
 *  - [VAD]     → Level & segmentation
 *  - [STT]     → STT responses
 *
 */

/**
 * MicStreamer configuration parameters
 *
 * These parameters must be tuned per-device because Android OEMs apply
 * different microphone gain, noise suppression, AGC, and DSP pipelines.
 *
 * Recommended tuning strategy:
 *  - Start with defaults below.
 *  - Log raw dB levels for typical speech/silence.
 *  - Adjust speechThresh + silenceHoldMs + minUtteranceMs until system
 *    captures clean starts/ends without fragmentation or over-triggering.
 *
 * --------------------------------------------------------------------------------
 * PARAMETER DETAILS + ENVIRONMENT-SPECIFIC RANGES
 * --------------------------------------------------------------------------------
 *
 * @param serverUrl         STT WebSocket endpoint.
 * @param context           Application context.
 *
 * @param language          "tr" by default. Does not affect VAD – only passed to STT.
 *
 * @param sampleRate        Raw PCM capture rate.
 *                          Typical values:
 *                            - 16000 → best STT accuracy/performance ratio.
 *                            - 8000  → low-end hardware only.
 *                            - 44100/48000 → some Samsung/Pixel mics internally run
 *                                            at this rate but we downsample anyway.
 *
 * @param chunkMs           Frame size in milliseconds sent to VAD.
 *                          Practical range: 10–30 ms.
 *                          - 10 ms → smoother VAD but higher CPU load.
 *                          - 20 ms → recommended baseline (balanced).
 *                          - 30 ms → good for noisy warehouses, reduces oscillation.
 *
 * @param speechThresh      RMS/level threshold to detect speech start.
 *                          **Highly device dependent.**
 *                          Typical observed ranges:
 *                            - Quiet office: 15–25
 *                            - Normal environment: 25–40
 *                            - Noisy warehouse: 35–55+
 *                          If too low → constant false triggers.
 *                          If too high → misses soft speech.
 *
 * @param silenceHoldMs     How long silence must persist before declaring speech end.
 *                          Practical range: 150–500 ms.
 *                          Short → fast response but may cut words.
 *                          Long  → smoother, but introduces latency.
 *                          Baseline: 300 ms.
 *
 * @param minUtteranceMs    Minimum segment length before STT is allowed to flush.
 *                          Prevents tiny blips from becoming micro-utterances.
 *                          Recommended:
 *                            - 400–500 ms for office.
 *                            - 600 ms default.
 *                            - 800–1000 ms for very noisy/echo environments.
 *
 * @param noiseProfile      Optional higher-level preset:
 *                          - QUIET   → office / meeting room
 *                          - NOISY   → typical warehouse / light machinery
 *                          - EXTREME → very loud environments
 *                          If non-null, overrides speechThresh/silenceHoldMs/minUtteranceMs
 *                          with profile-specific values. Pass null to use explicit values.
 *
 * @param dropShortSegmentsBelowMin
 *                          If true, segments shorter than minUtteranceMs are DROPPED
 *                          instead of being sent to STT (recommended to avoid flood).
 *                          If false, preserves legacy behavior and still sends them.
 *
 * @param onLevel           Optional callback for raw mic level visualization.
 *                          Useful for tuning speechThresh per environment.
 *
 * @param onText            STT output callback. Called after each completed utterance.
 *
 * @param scope             Coroutine scope for streaming. Usually left as default.
 */

enum class NoiseProfile { QUIET, NOISY, EXTREME }

data class VadConfig(
    val speechThresh: Int,
    val silenceHoldMs: Int,
    val minUtteranceMs: Int
)

fun vadConfigFor(profile: NoiseProfile) = when (profile) {
    NoiseProfile.QUIET -> VadConfig(
        speechThresh = 20,
        silenceHoldMs = 600,
        minUtteranceMs = 450
    )
    NoiseProfile.NOISY -> VadConfig(
        speechThresh = 35,
        silenceHoldMs = 600,
        minUtteranceMs = 450
    )
    NoiseProfile.EXTREME -> VadConfig(
        speechThresh = 40,
        silenceHoldMs = 600,
        minUtteranceMs = 450
    )
}

class MicStreamer(
    private val serverUrl: String,
    private val context: Context,
    private val language: String = "tr",
    private val sampleRate: Int = 16000,
    private val chunkMs: Int = 20,

    // Original VAD knobs (still usable directly if noiseProfile == null)
    private var speechThresh: Int = 30,
    private var silenceHoldMs: Int = 300,
    private var minUtteranceMs: Int = 600,

    // Optional profile → overrides the three VAD knobs above when non-null
    noiseProfile: NoiseProfile? = NoiseProfile.NOISY,

    // New safeguard: drop very short segments instead of sending them
    private val dropShortSegmentsBelowMin: Boolean = true,
    private val onLevel: ((Int) -> Unit)? = null,
    private val onText: ((String) -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

) {

    companion object { private const val TAG = "MicStreamer" }

    init {
        if (noiseProfile != null) {
            val cfg = vadConfigFor(noiseProfile)
            speechThresh = cfg.speechThresh
            silenceHoldMs = cfg.silenceHoldMs
            minUtteranceMs = cfg.minUtteranceMs
            Log.i(
                TAG,
                "🔧 [VAD] Applied noiseProfile=$noiseProfile → " +
                        "speechThresh=$speechThresh silenceHoldMs=$silenceHoldMs minUtteranceMs=$minUtteranceMs"
            )
        } else {
            Log.i(
                TAG,
                "🔧 [VAD] Using explicit VAD params → " +
                        "speechThresh=$speechThresh silenceHoldMs=$silenceHoldMs minUtteranceMs=$minUtteranceMs"
            )
        }
    }

    // OkHttp client for STT WS
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var ws: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var loopJob: Job? = null

    // Whether streaming (WS + AudioRecord loop) is active
    private val isStreaming = AtomicBoolean(false)

    private val levelHistory = ArrayDeque<Int>(10)
    private val minSpeechStartMs: Int = 120
    private val minShortSpeechMs: Int = 350   // allows short commands

    // Actual sample rate used (emulator vs device)
    private var actualSampleRate: Int = sampleRate

    // Chunk size measured in bytes per read() loop
    private var chunkBytesPerLoop: Int = 0

    // Whether segments should actually be SENT to STT
    @Volatile private var sendActive: Boolean = false

    // ---------------------------------------------------------------------
    // PUBLIC API
    // ---------------------------------------------------------------------

    fun isStreamingActive(): Boolean = isStreaming.get()
    fun getScope(): CoroutineScope = scope

    /**
     * Start the STT pipeline:
     *  - Opens WebSocket to /stt.
     *  - On open, initializes AudioRecord and launches capture loop.
     *
     *  NOTE:
     *   - This does NOT automatically activate sending.
     *   - Service must call activateSending() when ready to send segments.
     */
    fun start(extScope: CoroutineScope) {
        Log.i(TAG, "🚀 [MIC] start() called")

        if (isStreaming.getAndSet(true)) {
            Log.w(TAG, "⚠️ [MIC] Already streaming → ignoring start()")
            return
        }

        sendActive = false  // default to muted until Service says ACTIVE

        val req = Request.Builder().url(serverUrl).build()
        Log.i(TAG, "🌐 [WS-STT] Connecting to STT server → $serverUrl")

        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(socket: WebSocket, response: Response) {
                Log.i(TAG, "✅ [WS-STT] Connected (code=${response.code}, msg=${response.message})")
                Log.d(TAG, "[WS-STT] onOpen() socket=$socket isStreaming=${isStreaming.get()}")

                startRecorderAndLoop(socket, extScope)
            }

            override fun onMessage(socket: WebSocket, text: String) {
                Log.d(TAG, "📩 [WS-STT] onMessage() → $text")
                handleSttResponse(text)
            }

            override fun onFailure(socket: WebSocket, t: Throwable, r: Response?) {
                Log.e(TAG, "❌ [WS-STT] onFailure → ${t.message}", t)
                Log.e(TAG, "❌ [WS-STT] Response on failure: $r")
                stop()
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                if (socket === ws) {
                    Log.w(TAG, "🟠 [WS-STT] Active socket closed (code=$code, reason=$reason)")
                    ws = null
                    isStreaming.set(false)
                } else {
                    Log.w(TAG, "⚠️ [WS-STT] Ignored closure of stale socket")
                }
            }
        })
    }

    /**
     * Stop streaming:
     *  - Cancels capture loop.
     *  - Stops + releases AudioRecord.
     *  - Sends "stop" to STT if needed.
     *  - Closes WebSocket.
     */
    fun stop() {
        if (!isStreaming.getAndSet(false)) {
            Log.w(TAG, "⚠️ [MIC] stop() called but was not streaming")
            return
        }

        Log.i(TAG, "🛑 [MIC] Stopping MicStreamer")

        loopJob?.cancel()
        loopJob = null

        try {
            recorder?.run {
                try { stop() } catch (t: Throwable) {
                    Log.w(TAG, "⚠️ [AUDIO] stop() threw: ${t.message}", t)
                }
                release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "❌ [AUDIO] Error releasing AudioRecord: ${t.message}", t)
        }

        recorder = null

        try {
            ws?.send("""{"type":"stop"}""")
        } catch (t: Throwable) {
            Log.w(TAG, "⚠️ [WS-STT] Failed to send final stop: ${t.message}", t)
        }

        try {
            ws?.close(1000, "client-stop")
        } catch (t: Throwable) {
            Log.w(TAG, "⚠️ [WS-STT] Error closing WS: ${t.message}", t)
        }

        ws = null
        sendActive = false
    }

    /**
     * Enable sending segments to STT.
     * Called by Service when mic state becomes ACTIVE.
     */
    fun activateSending() {
        sendActive = true
        Log.i(TAG, "▶ [MIC] activateSending() → sendActive=true")
    }

    /**
     * Disable sending segments to STT (but keep recording).
     * Called by Service when mic state becomes MUTED (e.g., during TTS).
     */
    fun muteSending() {
        sendActive = false
        Log.i(TAG, "⏸ [MIC] muteSending() → sendActive=false (segments will be dropped)")
    }

    /**
     * Backwards-compat: old API still calls through to new semantics.
     */
    fun pauseMic() {
        Log.w(TAG, "⚠️ pauseMic() is deprecated, using muteSending() instead")
        muteSending()
    }

    fun resumeMic() {
        Log.w(TAG, "⚠️ resumeMic() is deprecated, using activateSending() instead")
        activateSending()
    }

    fun getWsDebug(): String = ws?.toString() ?: "NULL"

    // ---------------------------------------------------------------------
    // STT RESPONSE HANDLING
    // ---------------------------------------------------------------------
    private fun handleSttResponse(raw: String) {
        Log.d(TAG, "📩 [STT] Raw response: $raw")

        try {
            val json = JSONObject(raw)
            val type = json.optString("type", "")
            if (type != "stt") {
                Log.d(TAG, "ℹ️ [STT] Ignored non-stt message (type='$type')")
                return
            }

            val text = json.optString("text", "").trim()
            val ttsUrl = json.optString("tts_url", "").trim()

            if (text.isNotBlank()) {
                Log.i(TAG, "📝 [STT] Recognized text='$text'")
                onText?.invoke(text)
            } else {
                Log.w(TAG, "⚠️ [STT] Empty text in STT response")
            }

            if (ttsUrl.isNotBlank()) {
                Log.i(TAG, "ℹ️ [STT] tts_url present in STT response (ignored by MicStreamer, handled at higher layer)")
                // New design: MicStreamer does NOT trigger TTS directly.
                // TTS is coordinated via the /text pipeline + Service.
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ [STT] Parse error: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------------------
    // AUDIO RECORDING + SEGMENTATION
    // ---------------------------------------------------------------------
    private fun startRecorderAndLoop(socket: WebSocket, extScope: CoroutineScope) {
        Log.i(TAG, "🎤 [AUDIO] startRecorderAndLoop()")

        val onEmulator = android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.MODEL.contains("sdk")

        actualSampleRate = if (onEmulator) 44100 else sampleRate

        val minBuf = AudioRecord.getMinBufferSize(
            actualSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bytesPerMs = (actualSampleRate * 2) / 1000
        val chunkBytes = maxOf(bytesPerMs * chunkMs, minBuf / 4)
        chunkBytesPerLoop = chunkBytes
        val bufSize = maxOf(minBuf, chunkBytes * 4)

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            actualSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        Log.i(
            TAG,
            "🎤 [AUDIO] AudioRecord created → sr=$actualSampleRate buf=$bufSize chunkBytesPerLoop=$chunkBytesPerLoop"
        )

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ [AUDIO] AudioRecord init failed (state=${recorder?.state})")
            return
        }

        try {
            socket.send(
                """{"type":"start","sr":$actualSampleRate,"lang":"$language","format":"pcm_s16le"}"""
            )
            Log.d(TAG, "📡 [WS-STT] Sent start message")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ [WS-STT] Failed to send start message: ${t.message}", t)
        }

        recorder!!.startRecording()
        Log.i(TAG, "🎙 [AUDIO] Mic started → begin capture loop")

        launchRecorderLoop(socket, extScope)
    }

    private fun launchRecorderLoop(socket: WebSocket, extScope: CoroutineScope) {
        val rec = recorder ?: run {
            Log.e(TAG, "❌ [AUDIO] recorder is null in launchRecorderLoop()")
            return
        }

        val chunkSize = chunkBytesPerLoop
        if (chunkSize <= 0) {
            Log.e(TAG, "❌ [AUDIO] Invalid chunkBytesPerLoop=$chunkSize")
            return
        }

        loopJob?.cancel()
        loopJob = extScope.launch(Dispatchers.IO) {

            Log.i(TAG, "📡 [AUDIO] Capture loop started (isActive=$isActive, isStreaming=${isStreaming.get()})")

            val chunk = ByteArray(chunkSize)
            val segment = ArrayList<Byte>()
            var inSpeech = false
            var lastSoundMs = 0L

            fun flush() {
                if (segment.isEmpty()) {
                    Log.w(TAG, "⚠️ [VAD] flush() with empty segment → ignoring")
                    return
                }

                val durationMs = (segment.size / 2) * 1000L / actualSampleRate
                Log.i(
                    TAG,
                    "📤 [VAD] FLUSH segment bytes=${segment.size} duration=${durationMs}ms sendActive=$sendActive"
                )

                if (!sendActive) {
                    Log.w(
                        TAG,
                        "🚫 [VAD] Segment ready but sendActive=false → dropping (mic muted / TTS phase)"
                    )
                    segment.clear()
                    return
                }

                // NEW — allow very short commands if they "look like speech"
                if (durationMs < minUtteranceMs) {

                    if (!looksLikeSpeech()) {
                        if (dropShortSegmentsBelowMin) {
                            Log.w(
                                TAG,
                                "⚠️ [VAD] Short/noisy segment (duration=$durationMs < $minUtteranceMs) — DROPPING (not speech-like)"
                            )
                            segment.clear()
                            return
                        } else {
                            Log.w(
                                TAG,
                                "⚠️ [VAD] Short-noise segment — sending anyway (compat mode)"
                            )
                        }
                    } else {
                        // short but speech-like → allow
                        Log.i(
                            TAG,
                            "🎤 [VAD] Short but speech-like segment accepted (duration=$durationMs)"
                        )
                    }
                }


                try {
                    socket.send(segment.toByteArray().toByteString())
                    Log.i(TAG, "📡 [WS-STT] Sent audio segment bytes=${segment.size}")

                    socket.send("""{"type":"stop"}""")
                    Log.i(TAG, "📡 [WS-STT] Sent {\"type\":\"stop\"}")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [WS-STT] flush() send error: ${e.message}", e)
                } finally {
                    segment.clear()
                }
            }


            while (isActive && isStreaming.get()) {
                val n = try {
                    rec.read(chunk, 0, chunk.size)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [AUDIO] read() error: ${e.message}", e)
                    break
                }

                if (n <= 0) {
                    delay(5)
                    continue
                }

                val level = levelFromPcm(chunk, n)
                levelHistory.addLast(level)
                if (levelHistory.size > 10) levelHistory.removeFirst()
                onLevel?.invoke(level)

                val now = System.currentTimeMillis()

                if (!inSpeech) {
                    if (level >= speechThresh) {

                        // collect level history for shape detection
                        levelHistory.addLast(level)
                        if (levelHistory.size > 10) levelHistory.removeFirst()

                        inSpeech = true
                        lastSoundMs = now
                        segment.addAll(chunk.copyOfRange(0, n).asList())
                        Log.i(TAG, "🗣 [VAD] Speech START (level=$level, bytes=$n)")
                    }
                } else {
                    // Already in speech: accumulate
                    segment.addAll(chunk.copyOfRange(0, n).asList())
                    if (level >= speechThresh) {
                        lastSoundMs = now
                    }

                    val diff = now - lastSoundMs

                    if (diff >= silenceHoldMs) {
                        inSpeech = false
                        Log.i(TAG, "🧵 [VAD] Speech END after $diff ms of silence → flushing segment")
                        flush()
                    }
                }
            }

            if (segment.isNotEmpty()) {
                Log.i(TAG, "🧵 [AUDIO] Loop ending with pending segment → final flush()")
                flush()
            }

            Log.i(TAG, "📡 [AUDIO] Capture loop ended (isStreaming=${isStreaming.get()})")
        }
    }



    private fun looksLikeSpeech(): Boolean {
        if (levelHistory.isEmpty()) return false

        // real speech usually has >3 frames above 60% normalized
        val highPeaks = levelHistory.count { it > 60 }

        // also ensure at least 8 frames exist (approx ~160ms)
        return highPeaks >= 3
    }


    // ---------------------------------------------------------------------
    // LEVEL CALCULATION
    // ---------------------------------------------------------------------
    private fun levelFromPcm(bytes: ByteArray, len: Int): Int {
        val bb = ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        var sumSq = 0.0
        var count = 0

        while (bb.remaining() >= 2) {
            val s = bb.short.toInt()
            sumSq += s * s.toDouble()
            count++
        }

        if (count == 0) return 0

        val rms = sqrt(sumSq / count)
        val db = 20 * log10((rms + 1e-9) / 32768.0)
        val normalized = (((db + 60) / 60).coerceIn(0.0, 1.0) * 100).toInt()

        //Log.d(TAG, "🎚 [VAD] levelFromPcm → db=$db normalized=$normalized count=$count")
        return normalized
    }
}
