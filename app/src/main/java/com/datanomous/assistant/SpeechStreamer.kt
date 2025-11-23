// ============================================================================
// MicStreamer v3.1 — FINAL IMMORTAL VERSION (NO WS DROP, NO MIC FREEZE)
// ============================================================================

package com.datanomous.assistant.audio

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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


// ============================================================================
// CONFIG
// ============================================================================

enum class NoiseProfile { QUIET, NOISY, EXTREME }

data class VadConfig(
    val speechThresh: Int,
    val silenceHoldMs: Int,
    val minUtteranceMs: Int
)

fun vadConfigFor(p: NoiseProfile) = when (p) {
    NoiseProfile.QUIET -> VadConfig(5, 500, 450)
    NoiseProfile.NOISY -> VadConfig(38, 500, 550)
    NoiseProfile.EXTREME -> VadConfig(45, 700, 800)
}

enum class MicDspProfile { OFF, SPEECH_FOCUS }


// ============================================================================
// MicStreamer v3.1 — FINAL
// ============================================================================

class SpeechStreamer(
    private val context: Context,
    private val serverUrl: String,
    private val language: String = "tr",
    private val sampleRate: Int = 16000,
    private val chunkMs: Int = 20,

    private var speechThresh: Int = 5,
    private var silenceHoldMs: Int = 400,
    private var minUtteranceMs: Int = 450,

    noiseProfile: NoiseProfile? = NoiseProfile.QUIET,
    private val dropShortSegments: Boolean = true,

    private val onLevel: ((Int) -> Unit)? = null,
    private val onText: ((String) -> Unit)? = null,

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),

    private val dspProfile: MicDspProfile = MicDspProfile.SPEECH_FOCUS
) {

    companion object {
        private const val TAG = "Assistant - SpeechStreamer"
    }

    private val isStreaming = AtomicBoolean(false)
    private val isReady = AtomicBoolean(false)

    @Volatile private var sendActive = false
    @Volatile private var ws: WebSocket? = null

    private var recorder: AudioRecord? = null
    private var loopJob: Job? = null

    private var actualSR = sampleRate
    private var chunkBytes = 0

    private var reconnectAttempts = 0
    private val MAX_RECONNECT_DELAY = 8000L


    // ============================================================================
    // PUBLIC API
    // ============================================================================
    fun isStreamingActive(): Boolean = isStreaming.get()
    fun getScope() = scope

    fun activateSending() {
        sendActive = true
        Log.i(TAG, "▶ MIC SEND ACTIVE")
    }

    fun muteSending() {
        sendActive = false
        Log.i(TAG, "⏸ MIC MUTED")
    }

    fun pauseMic() = muteSending()
    fun resumeMic() = activateSending()


    // ============================================================================
    // START
    // ============================================================================
    fun start(extScope: CoroutineScope) {
        Log.i(TAG, "🚀 MicStreamer.start()")

        if (isStreaming.getAndSet(true)) {
            Log.w(TAG, "⚠ Already streaming")
            return
        }

        sendActive = false
        isReady.set(false)
        connectWebSocket(extScope)
    }


    // ============================================================================
    // CONNECT
    // ============================================================================
    private fun connectWebSocket(extScope: CoroutineScope) {
        Log.i(TAG, "🌐 Connecting to $serverUrl (attempt=$reconnectAttempts)")

        val req = Request.Builder().url(serverUrl).build()

        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(socket: WebSocket, resp: Response) {
                Log.i(TAG, "🟢 WS-STT connected (${resp.code})")
                reconnectAttempts = 0
                isReady.set(true)

                startRecorderAndLoop(socket, extScope)
            }

            override fun onMessage(socket: WebSocket, text: String) {
                handleSttResponse(text)
            }

            override fun onFailure(socket: WebSocket, t: Throwable, r: Response?) {
                Log.e(TAG, "❌ WS-STT failure → ${t.message}")
                restart(extScope)
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "🟠 WS-STT closed → $reason")
                restart(extScope)
            }
        })
    }


    // ============================================================================
    // RESTART LOGIC
    // ============================================================================
    private fun restart(extScope: CoroutineScope) {
        if (!isStreaming.get()) return

        safeStopRecorder()
        ws = null
        isReady.set(false)

        reconnectAttempts++
        val delayMs = (reconnectAttempts * 1000L).coerceAtMost(MAX_RECONNECT_DELAY)

        Log.w(TAG, "⏳ Reconnecting STT in ${delayMs}ms")

        scope.launch {
            delay(delayMs)
            if (isStreaming.get()) connectWebSocket(extScope)
        }
    }


    // ============================================================================
    // STOP
    // ============================================================================
    fun stop() {
        if (!isStreaming.getAndSet(false)) return

        Log.i(TAG, "🛑 MicStreamer.stop()")

        safeStopRecorder()

        try { ws?.close(1000, "client-stop") } catch (_: Throwable) {}
        ws = null
        isReady.set(false)
        sendActive = false
    }


    private fun safeStopRecorder() {
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}

        recorder = null

        loopJob?.cancel()
        loopJob = null
    }


    // ============================================================================
    // RECORDER LOOP
    // ============================================================================
    private fun startRecorderAndLoop(socket: WebSocket, extScope: CoroutineScope) {
        enableBluetoothMic()

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        ensureScoOrFallback(am)

        actualSR = sampleRate

        val minBuf = AudioRecord.getMinBufferSize(
            actualSR,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bps = actualSR * 2 / 1000
        chunkBytes = maxOf(bps * chunkMs, minBuf / 4)
        val buf = maxOf(minBuf, chunkBytes * 4)

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            actualSR,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            buf
        )

        configureDsp()

        try {
            recorder!!.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "❌ startRecording failed → ${e.message}")
            restart(extScope)
            return
        }

        socket.send("""{"type":"start","sr":$actualSR,"lang":"$language"}""")
        launchRecorderLoop(socket, extScope)
    }


    private fun launchRecorderLoop(socket: WebSocket, extScope: CoroutineScope) {
        val rec = recorder ?: return

        loopJob = extScope.launch(Dispatchers.IO) {

            val chunk = ByteArray(chunkBytes)
            val segment = ArrayList<Byte>()

            var inSpeech = false
            var lastSound = 0L
            var lastAudioTime = System.currentTimeMillis()

            fun flush() {
                val durMs = (segment.size / 2) * 1000L / actualSR

                if (!sendActive) {
                    segment.clear()
                    return
                }

                if (dropShortSegments && durMs < minUtteranceMs) {
                    segment.clear()
                    return
                }

                try {
                    socket.send(segment.toByteArray().toByteString())
                    socket.send("""{"type":"stop"}""")
                } catch (_: Throwable) {}

                segment.clear()
            }

            while (isActive && isStreaming.get()) {

                val n = rec.read(chunk, 0, chunk.size)

                // --- MIC STALL DETECTION (fix for speech freeze after TTS) ---
                if (n <= 0) {
                    if (System.currentTimeMillis() - lastAudioTime > 1200) {
                        Log.e(TAG, "❌ MIC STALLED (read=0 >1200ms) → restarting mic pipeline")
                        // Prefer your existing restart logic if you have it:
                        // restart(extScope)
                        stop()
                        start(extScope)
                        return@launch
                    }
                    delay(5)
                    continue
                }
                // --------------------------------------------------------------

                lastAudioTime = System.currentTimeMillis()

                val now = System.currentTimeMillis()
                val lvl = levelFromPcm(chunk, n)
                onLevel?.invoke(lvl)

                // Speech detection (VAD)
                if (!inSpeech) {
                    if (lvl >= speechThresh) {
                        inSpeech = true
                        lastSound = now
                        segment.addAll(chunk.copyOfRange(0, n).asList())
                    }
                } else {
                    segment.addAll(chunk.copyOfRange(0, n).asList())
                    if (lvl >= speechThresh) lastSound = now

                    if (now - lastSound >= silenceHoldMs) {
                        inSpeech = false
                        flush()
                    }
                }
            }

            if (segment.isNotEmpty()) flush()
        }
    }



    // ============================================================================
    // HANDLE STT OUTPUT
    // ============================================================================
    private fun handleSttResponse(raw: String) {
        val json = try { JSONObject(raw) } catch (_: Throwable) { return }
        if (json.optString("type") != "stt") return

        val text = json.optString("text").trim()
        if (text.isEmpty()) return

        // FIX: ignore duplicate empty frames that break the loop
        if (text == "." || text == " " || text.length == 1) return

        try { onText?.invoke(text) }
        catch (e: Exception) {
            Log.e(TAG, "❌ onText error: ${e.message}")
        }
    }


    // ============================================================================
    // AUDIO DSP
    // ============================================================================
    private fun enableBluetoothMic() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            Thread.sleep(200)
            am.startBluetoothSco()
            am.setBluetoothScoOn(true)
        } catch (_: Throwable) {}
    }

    private fun ensureScoOrFallback(am: AudioManager) {
        if (!am.isBluetoothScoOn) {
            am.stopBluetoothSco()
            am.setBluetoothScoOn(false)
        }
    }

    private fun configureDsp() {
        val id = recorder?.audioSessionId ?: return
        try { NoiseSuppressor.create(id)?.enabled = true } catch (_: Throwable) {}
        try { AutomaticGainControl.create(id)?.enabled = true } catch (_: Throwable) {}
        try { AcousticEchoCanceler.create(id)?.enabled = false } catch (_: Throwable) {}
    }


    private fun levelFromPcm(bytes: ByteArray, len: Int): Int {
        val bb = ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var c = 0
        while (bb.remaining() >= 2) {
            val s = bb.short.toInt()
            sum += s * s
            c++
        }
        if (c == 0) return 0

        val rms = sqrt(sum / c)
        val db = 20 * log10((rms + 1e-9) / 32768.0)

        return (((db + 60) / 60).coerceIn(0.0, 1.0) * 100).toInt()
    }


    // ============================================================================
    // OKHTTP (IMMORTAL WS)
    // ============================================================================
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
}
