
package com.datanomous.logisticsassistant.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Runs the ConfigureVoice auto-calibration in a background thread.
 */
fun runVoiceCalibration(context: Context) {
    CoroutineScope(Dispatchers.IO).launch {
        Log.i("CALIBRATION", "▶ Starting 60-second microphone calibration...")

        try {
            val cfg = ConfigureVoice(context).runCalibration()

            Log.i("CALIBRATION", "🎯 FINAL CONFIGURATION:")
            Log.i("CALIBRATION", "noiseFloor       = ${cfg.noiseFloor}")
            Log.i("CALIBRATION", "nearPeak         = ${cfg.nearPeak}")
            Log.i("CALIBRATION", "farPeak          = ${cfg.farPeak}")
            Log.i("CALIBRATION", "speechThresh     = ${cfg.speechThresh}")
            Log.i("CALIBRATION", "silenceHoldMs    = ${cfg.silenceHoldMs}")
            Log.i("CALIBRATION", "minUtteranceMs   = ${cfg.minUtteranceMs}")
            Log.i("CALIBRATION", "profile          = ${cfg.profile}")

        } catch (e: Exception) {
            Log.e("CALIBRATION", "❌ Calibration failed: ${e.message}", e)
        }
    }
}
