package com.datanomous.logisticsassistant.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ========================================================================
 *  ConfigureVoice — Auto-Calibration Tool for MicStreamer VAD Settings
 * ========================================================================
 *
 *  USAGE:
 *  ------
 *  val cfg = ConfigureVoice(context)
 *  cfg.runCalibration()  // runs for ~60 seconds
 *
 *  RESULT:
 *  -------
 *  Prints recommended:
 *      - speechThresh
 *      - silenceHoldMs
 *      - minUtteranceMs
 *      - noise floor
 *      - near-speech peak
 *      - far-speech peak
 *      - recommended profile (QUIET / NOISY / EXTREME)
 *
 */

class ConfigureVoice(private val context: Context) {

    companion object {
        private const val TAG = "ConfigureVoice"
    }

    private val sampleRate = 16000
    private val chunkMs = 20

    private fun levelFromPcm(bytes: ByteArray, len: Int): Int {
        val bb = ByteBuffer.wrap(bytes, 0, len)
            .order(ByteOrder.LITTLE_ENDIAN)
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
        return (((db + 60) / 60).coerceIn(0.0, 1.0) * 100).toInt()
    }

    suspend fun runCalibration(durationMs: Long = 60_000): CalibratedConfig {

        Log.i(TAG, "🎛️ Starting microphone calibration ($durationMs ms)")

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bytesPerMs = (sampleRate * 2) / 1000
        val chunkBytes = bytesPerMs * chunkMs

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunkBytes * 4)
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        recorder.startRecording()
        Log.i(TAG, "🎤 Mic started for calibration")

        val chunk = ByteArray(chunkBytes)
        val start = System.currentTimeMillis()

        var noiseFloorSum = 0L
        var noiseSamples = 0

        var nearPeak = 0
        var farPeak = 0

        var totalSamples = 0

        while (System.currentTimeMillis() - start < durationMs) {
            val n = recorder.read(chunk, 0, chunk.size)
            if (n <= 0) continue

            val level = levelFromPcm(chunk, n)

            // moving noise floor (low-level frames)
            if (level < 15) {
                noiseFloorSum += level
                noiseSamples++
            }

            // detect high speech peaks
            if (level >= nearPeak) {
                nearPeak = level
            }

            // detect low-level speech (far speaker)
            if (level in 10..35 && level > farPeak) {
                farPeak = level
            }

            totalSamples++
        }

        recorder.stop()
        recorder.release()

        val noiseFloor = if (noiseSamples > 0) (noiseFloorSum / noiseSamples).toInt() else 0

        // determine thresholds based on ratios
        val speechThresh = calculateThresh(noiseFloor, nearPeak, farPeak)
        val silenceHold = calculateSilenceHold(noiseFloor, nearPeak)
        val minUtt = calculateMinUtterance(noiseFloor, nearPeak)

        val profile = recommendProfile(noiseFloor, nearPeak)

        Log.i(TAG, "============== FINAL CONFIG ==============")
        Log.i(TAG, "Noise floor:         $noiseFloor")
        Log.i(TAG, "Near-speech peak:    $nearPeak")
        Log.i(TAG, "Far-speech peak:     $farPeak")
        Log.i(TAG, "------------------------------------------")
        Log.i(TAG, "speechThresh:        $speechThresh")
        Log.i(TAG, "silenceHoldMs:       $silenceHold")
        Log.i(TAG, "minUtteranceMs:      $minUtt")
        Log.i(TAG, "Recommended profile: $profile")
        Log.i(TAG, "==========================================")

        return CalibratedConfig(
            noiseFloor = noiseFloor,
            nearPeak = nearPeak,
            farPeak = farPeak,
            speechThresh = speechThresh,
            silenceHoldMs = silenceHold,
            minUtteranceMs = minUtt,
            profile = profile
        )
    }

    private fun calculateThresh(noise: Int, near: Int, far: Int): Int {
        val dynamicGain = (near - noise).coerceAtLeast(10)
        val base = noise + dynamicGain / 2

        return base
            .coerceAtLeast(15)
            .coerceAtMost(70)
    }

    private fun calculateSilenceHold(noise: Int, near: Int): Int {
        val ratio = near - noise
        return when {
            ratio < 15 -> 700
            ratio < 25 -> 600
            ratio < 40 -> 500
            else       -> 400
        }
    }

    private fun calculateMinUtterance(noise: Int, near: Int): Int {
        val ratio = near - noise
        return when {
            ratio < 20 -> 800
            ratio < 30 -> 600
            else       -> 450
        }
    }

    private fun recommendProfile(noise: Int, near: Int): String {
        val ratio = near - noise
        return when {
            noise < 10 && ratio > 40 -> "QUIET"
            noise < 25 && ratio > 25 -> "NOISY"
            else                     -> "EXTREME"
        }
    }

    data class CalibratedConfig(
        val noiseFloor: Int,
        val nearPeak: Int,
        val farPeak: Int,
        val speechThresh: Int,
        val silenceHoldMs: Int,
        val minUtteranceMs: Int,
        val profile: String
    )
}
