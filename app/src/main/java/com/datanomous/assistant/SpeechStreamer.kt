// ============================================================================
// MicStreamer v2.1 — Clean, Stable, Response-WebSocket Compatible
// ============================================================================

package com.datanomous.logisticsassistant.audio

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
// CONFIG ENUMS
// ============================================================================
enum class NoiseProfile { QUIET, NOISY, EXTREME }

data class VadConfig(
    val speechThresh: Int,
    val silenceHoldMs: Int,
    val minUtteranceMs: Int
)

fun vadConfigFor(p: NoiseProfile) = when (p) {
    NoiseProfile.QUIET   -> VadConfig(5, 500, 450)
    NoiseProfile.NOISY   -> VadConfig(38, 500, 550)
    NoiseProfile.EXTREME -> VadConfig(45, 700, 800)
}

enum class MicDspProfile {
    OFF,
    SPEECH_FOCUS
}

// ============================================================================
// MICSTREAMER v2.1 (optimized for DECENTRALIZED RESPONSE MODE)
// ============================================================================
class MicStreamer(
    private val context: Context,
    private val serverUrl: String,
    private val language: String = "tr",
    private val sampleRate: Int = 16000,
    private val chunkMs: Int = 20,

    private var speechThresh: Int = 5,
    private var silenceHoldMs: Int = 400,
    private var minUtteranceMs: Int = 450,

    noiseProfile: NoiseProfile? = NoiseProfile.QUIET,
    private val dropShortSegmentsBelowMin: Boolean = true,

    private val onLevel: ((Int) -> Unit)? = null,
    private val onText: ((String) -> Unit)? = null,

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),

    private val dspProfile: MicDspProfile = MicDspProfile.SPEECH_FOCUS
) {

    companion object {
        private const val TAG = "LogisticsAssistant - MicStreamer"
    }

    // ----------------------------------------------------------------------------
    // RUNTIME STATE
    // ----------------------------------------------------------------------------
    private var ws: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var loopJob: Job? = null

    private var actualSR = sampleRate
    private var chunkBytes = 0

    private val isStreaming = AtomicBoolean(false)
    @Volatile private var sendActive = false

    // ----------------------------------------------------------------------------
    // INITIALIZER
    // ----------------------------------------------------------------------------
    init {
        if (noiseProfile != null) {
            val cfg = vadConfigFor(noiseProfile)
            speechThresh     = cfg.speechThresh
            silenceHoldMs    = cfg.silenceHoldMs
            minUtteranceMs   = cfg.minUtteranceMs
        }
    }

    // ----------------------------------------------------------------------------
    // PUBLIC API
    // ----------------------------------------------------------------------------
    fun isStreamingActive(): Boolean = isStreaming.get()
    fun getScope(): CoroutineScope = scope

    fun activateSending() {
        sendActive = true
        Log.i(TAG, "▶ [MIC] activateSending()")
    }

    fun muteSending() {
        sendActive = false
        Log.i(TAG, "⏸ [MIC] muteSending()")
    }

    fun pauseMic()  = muteSending()
    fun resumeMic() = activateSending()

    // ----------------------------------------------------------------------------
    // START
    // ----------------------------------------------------------------------------
    fun start(extScope: CoroutineScope) {
        Log.i(TAG, "🚀 [MIC] start()")

        if (isStreaming.getAndSet(true)) {
            Log.w(TAG, "⚠️ Already streaming")
            return
        }

        sendActive = false

        val req = Request.Builder().url(serverUrl).build()
        Log.i(TAG, "🌐 Connecting to STT WebSocket → $serverUrl")

        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(socket: WebSocket, response: Response) {
                Log.i(TAG, "✅ [WS-STT] Connected (${response.code})")
                startRecorderAndLoop(socket, extScope)
            }

            override fun onMessage(socket: WebSocket, text: String) {
                Log.d(TAG, "📩 [WS-STT] onMessage → $text")
                handleSttResponse(text)
            }

            override fun onFailure(socket: WebSocket, t: Throwable, r: Response?) {
                Log.e(TAG, "❌ [WS-STT] Failure → ${t.message}")
                stop()
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                if (socket === ws) {
                    Log.w(TAG, "🟠 [WS-STT] Closed (code=$code, reason=$reason)")
                }
                ws = null
                isStreaming.set(false)
            }
        })
    }

    // ----------------------------------------------------------------------------
    // STOP
    // ----------------------------------------------------------------------------
    fun stop() {
        if (!isStreaming.getAndSet(false)) {
            Log.w(TAG, "⚠️ stop() ignored — not streaming")
            return
        }

        Log.i(TAG, "🛑 [MIC] stop()")

        loopJob?.cancel()
        loopJob = null

        try {
            recorder?.run {
                try { stop() } catch (_: Throwable) {}
                release()
            }
        } catch (_: Throwable) {}

        recorder = null

        try { ws?.send("""{"type":"stop"}""") } catch (_: Throwable) {}
        try { ws?.close(1000, "client-stop") } catch (_: Throwable) {}

        ws = null
        sendActive = false
    }

    // ----------------------------------------------------------------------------
    // RECORDER & LOOP
    // ----------------------------------------------------------------------------
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

        val bps = (actualSR * 2) / 1000
        chunkBytes = maxOf(bps * chunkMs, minBuf / 4)
        val bufSize = maxOf(minBuf, chunkBytes * 4)

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            actualSR,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        configureBuiltInEffects()

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ AudioRecord not initialized")
            return
        }

        try {
            recorder!!.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "❌ startRecording failed: ${t.message}")
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

            fun flush() {
                val duration = (segment.size / 2) * 1000L / actualSR

                if (!sendActive) {
                    segment.clear(); return
                }

                if (dropShortSegmentsBelowMin && duration < minUtteranceMs) {
                    segment.clear(); return
                }

                try {
                    socket.send(segment.toByteArray().toByteString())
                    socket.send("""{"type":"stop"}""")
                } catch (_: Throwable) {}

                segment.clear()
            }

            while (isActive && isStreaming.get()) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) { delay(5); continue }

                val now = System.currentTimeMillis()
                val lvl = levelFromPcm(chunk, n)
                onLevel?.invoke(lvl)

                applyDspIfNeeded(chunk, n, lvl)

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

    // ----------------------------------------------------------------------------
    // STT RESPONSE HANDLING
    // ----------------------------------------------------------------------------
    private fun handleSttResponse(raw: String) {
        val json = try { JSONObject(raw) } catch (_: Throwable) { return }

        if (json.optString("type") != "stt") return

        val text = json.optString("text").trim()

        if (text.isBlank()) return

        try {
            onText?.invoke(text)
        } catch (_: Throwable) {
            Log.e(TAG, "❌ onText crashed")
        }
    }

    // ----------------------------------------------------------------------------
    // BLUETOOTH SCO STABILITY
    // ----------------------------------------------------------------------------
    private fun enableBluetoothMic() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            Thread.sleep(250)

            repeat(8) {
                am.startBluetoothSco()
                am.setBluetoothScoOn(true)
                Thread.sleep(350)
                if (am.isBluetoothScoOn) return
            }
            am.stopBluetoothSco()
            am.setBluetoothScoOn(false)
        } catch (_: Throwable) {
            Log.e(TAG, "❌ Bluetooth SCO error")
        }
    }

    private fun ensureScoOrFallback(am: AudioManager) {
        val limit = 2000L
        var waited = 0L

        while (!am.isBluetoothScoOn && waited < limit) {
            Thread.sleep(100)
            waited += 100
        }

        if (!am.isBluetoothScoOn) {
            forceFallbackToDeviceMic(am)
            return
        }

        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val btMic = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

        if (btMic == null) {
            forceFallbackToDeviceMic(am)
        }
    }

    private fun forceFallbackToDeviceMic(am: AudioManager) {
        try {
            am.stopBluetoothSco()
            am.setBluetoothScoOn(false)
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Throwable) {}
    }

    // ----------------------------------------------------------------------------
    // DSP + LEVEL
    // ----------------------------------------------------------------------------
    private fun configureBuiltInEffects() {
        val s = recorder?.audioSessionId ?: return
        try { NoiseSuppressor.create(s)?.enabled = true } catch (_: Throwable) {}
        try { AutomaticGainControl.create(s)?.enabled = true } catch (_: Throwable) {}
        try { AcousticEchoCanceler.create(s)?.enabled = false } catch (_: Throwable) {}
    }

    private fun applyDspIfNeeded(bytes: ByteArray, len: Int, lvl: Int) {
        if (dspProfile != MicDspProfile.SPEECH_FOCUS) return
        if (lvl >= 30) return

        val bb = ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        while (bb.remaining() >= 2) {
            val pos = bb.position()
            val s = bb.short
            val scaled = (s * 0.25f).toInt().coerceIn(-32768, 32767)
            bb.position(pos)
            bb.putShort(scaled.toShort())
        }
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

    // ----------------------------------------------------------------------------
    // OKHTTP CLIENT
    // ----------------------------------------------------------------------------
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
