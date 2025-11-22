// ============================================================================
// MicStreamer v2.0 — Production-Safe, Bluetooth SCO-Stable, Warehouse-Ready
// ============================================================================

package com.datanomous.logisticsassistant.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.AudioDeviceInfo
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
import com.datanomous.logisticsassistant.LogisticsAssistantService


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
    NoiseProfile.QUIET -> VadConfig(5, 500, 450)
    NoiseProfile.NOISY -> VadConfig(38, 500, 550)
    NoiseProfile.EXTREME -> VadConfig(45, 700, 800)
}

enum class MicDspProfile {
    OFF,
    SPEECH_FOCUS
}


// ============================================================================
// MICSTREAMER v2.0
// ============================================================================
class MicStreamer(
    private val serverUrl: String,
    private val context: Context,
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

    companion object { private const val TAG = "MicStreamer" }

    // DSP tuning
    private val dspGateLevel = 30
    private val dspAttenFactor = 0.25f

    // Runtime state
    private var ws: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var loopJob: Job? = null

    private var actualSR = sampleRate
    private var chunkBytes = 0

    private val isStreaming = AtomicBoolean(false)
    @Volatile private var sendActive = false


    // ============================================================================
    // INIT
    // ============================================================================
    init {
        if (noiseProfile != null) {
            val c = vadConfigFor(noiseProfile)
            speechThresh = c.speechThresh
            silenceHoldMs = c.silenceHoldMs
            minUtteranceMs = c.minUtteranceMs
        }
    }


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
     * NOTE:
     *  - This does NOT automatically activate sending.
     *  - Service must call activateSending() when ready to send segments.
     */
    fun start(extScope: CoroutineScope) {
        Log.i(TAG, "🚀 [MIC] start() called")

        if (isStreaming.getAndSet(true)) {
            Log.w(TAG, "⚠️ [MIC] Already streaming → ignoring start()")
            return
        }

        sendActive = false // muted until service activates

        val req = Request.Builder().url(serverUrl).build()
        Log.i(TAG, "🌐 [WS-STT] Connecting to STT server → $serverUrl")

        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(socket: WebSocket, response: Response) {
                Log.i(TAG, "✅ [WS-STT] Connected (code=${response.code}, msg=${response.message})")
                startRecorderAndLoop(socket, extScope)
            }

            override fun onMessage(socket: WebSocket, text: String) {
                Log.d(TAG, "📩 [WS-STT] onMessage() → $text")
                handleSttResponse(text)
            }

            override fun onFailure(socket: WebSocket, t: Throwable, r: Response?) {
                Log.e(TAG, "❌ [WS-STT] onFailure → ${t.message}")
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
                    Log.w(TAG, "⚠️ [AUDIO] stop() threw: ${t.message}")
                }
                release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "❌ [AUDIO] Error releasing AudioRecord: ${t.message}", t)
        }

        recorder = null

        try { ws?.send("""{"type":"stop"}""") } catch (_: Throwable) {}
        try { ws?.close(1000, "client-stop") } catch (_: Throwable) {}

        ws = null
        sendActive = false

    }


    /**
     * Enable sending segments to STT.
     */
    fun activateSending() {
        sendActive = true
        Log.i(TAG, "▶ [MIC] activateSending() → sendActive=true")
    }

    /**
     * Disable sending segments to STT (but keep recording).
     */
    fun muteSending() {
        sendActive = false
        Log.i(TAG, "⏸ [MIC] muteSending() → sendActive=false")
    }


    /**
     * Backwards-compat: old API still calls through to new semantics.
     */
    fun pauseMic() {
        Log.w(TAG, "⚠️ pauseMic() is deprecated → redirecting to muteSending()")
        muteSending()
    }

    fun resumeMic() {
        Log.w(TAG, "⚠️ resumeMic() is deprecated → redirecting to activateSending()")
        activateSending()
    }


    // ============================================================================
    // RECORDER + LOOP
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

        val bytesPerMs = (actualSR * 2) / 1000
        chunkBytes = maxOf(bytesPerMs * chunkMs, minBuf / 4)
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
            if (recorder!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "❌ startRecording() failed — mic not active")
                return
            }
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Exception during startRecording(): ${t.message}")
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
                    segment.clear()
                    return
                }

                if (dropShortSegmentsBelowMin && duration < minUtteranceMs) {
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
                if (n <= 0) { delay(5); continue }

                val now = System.currentTimeMillis()
                val level = levelFromPcm(chunk, n)
                onLevel?.invoke(level)

                applyDspIfNeeded(chunk, n, level)

                if (!inSpeech) {
                    if (level >= speechThresh) {
                        inSpeech = true
                        lastSound = now
                        segment.addAll(chunk.copyOfRange(0, n).asList())
                    }
                } else {
                    segment.addAll(chunk.copyOfRange(0, n).asList())
                    if (level >= speechThresh) lastSound = now
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
    // BLUETOOTH SCO FIX (Stable v2.0)
    // ============================================================================
    private fun enableBluetoothMic() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            Thread.sleep(250)

            var success = false
            repeat(8) { attempt ->
                Log.i(TAG, "🎧 SCO attempt ${attempt + 1}/8")
                am.startBluetoothSco()
                am.setBluetoothScoOn(true)
                Thread.sleep(350)

                if (am.isBluetoothScoOn) {
                    Log.i(TAG, "🎧 SCO ACTIVE - Bluetooth mic engaged")
                    success = true
                    return@repeat
                }
            }

            if (!success) {
                Log.e(TAG, "🛑 Bluetooth SCO failed → using device mic")
                am.stopBluetoothSco()
                am.setBluetoothScoOn(false)
            }
        } catch (_: Throwable) {
            Log.e(TAG, "❌ Exception enabling Bluetooth SCO")
        }
    }



    private fun configureBuiltInEffects() {
        val sid = recorder?.audioSessionId ?: return
        try { NoiseSuppressor.create(sid)?.enabled = true } catch (_: Throwable) {}
        try { AutomaticGainControl.create(sid)?.enabled = true } catch (_: Throwable) {}
        try { AcousticEchoCanceler.create(sid)?.enabled = false } catch (_: Throwable) {}
    }


    private fun applyDspIfNeeded(bytes: ByteArray, len: Int, level: Int) {
        if (dspProfile != MicDspProfile.SPEECH_FOCUS) return
        if (level >= dspGateLevel) return

        val bb = ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        while (bb.remaining() >= 2) {
            val pos = bb.position()
            val s = bb.short
            val scaled = (s * dspAttenFactor)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bb.position(pos)
            bb.putShort(scaled.toShort())
        }
    }


    private fun levelFromPcm(bytes: ByteArray, len: Int): Int {
        val bb = ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var cnt = 0
        while (bb.remaining() >= 2) {
            val s = bb.short.toInt()
            sum += s * s
            cnt++
        }
        if (cnt == 0) return 0
        val rms = sqrt(sum / cnt)
        val db = 20 * log10((rms + 1e-9) / 32768.0)
        return (((db + 60) / 60).coerceIn(0.0, 1.0) * 100).toInt()
    }


    // ============================================================================
    // STT RESPONSE HANDLING (FINAL)
    // ============================================================================
    private fun handleSttResponse(raw: String) {
        val json = try { JSONObject(raw) } catch (_: Throwable) {
            if (!sendActive) sendActive = true
            return
        }

        if (json.optString("type") != "stt") return

        val text = json.optString("text").trim()

        if (text.isBlank()) {
            LogisticsAssistantService.unlockPipeline()
            activateSending()
            return
        }

        try {
            onText?.invoke(text)
        } catch (_: Throwable) {
            Log.e(TAG, "❌ onText callback crashed")
            activateSending()
        }
    }


    private fun ensureScoOrFallback(am: AudioManager) {
        val maxWait = 2000L
        val step = 100L
        var waited = 0L

        // 1. Wait for SCO to activate
        while (!am.isBluetoothScoOn && waited < maxWait) {
            Log.w(TAG, "🎧 SCO not ready… waiting ${waited}ms")
            Thread.sleep(step)
            waited += step
        }

        // 2. If SCO never activated → fallback to device mic
        if (!am.isBluetoothScoOn) {
            Log.e(TAG, "🛑 SCO failed to activate → forcing fallback to device mic")
            forceFallbackToDeviceMic(am)
            return
        }

        // 3. SCO is ON → check for ghost SCO (no active BT device)
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val btInput = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        if (btInput == null) {
            Log.e(TAG, "🛑 Ghost SCO detected (SCO ON but no SCO input device) → fallback")
            forceFallbackToDeviceMic(am)
            return
        }

        // 4. SCO is valid
        Log.i(TAG, "🎧 SCO route ready with valid Bluetooth input")
    }

    private fun forceFallbackToDeviceMic(am: AudioManager) {
        try {
            am.stopBluetoothSco()
            am.setBluetoothScoOn(false)
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = true
        } catch (_: Throwable) {}

        Log.i(TAG, "🔄 Fallback → Device mic active (SCO disabled)")
    }



    // Client
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
}

