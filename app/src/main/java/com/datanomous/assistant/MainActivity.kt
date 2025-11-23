package com.datanomous.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.datanomous.assistant.ui.ChatScreen
import com.datanomous.assistant.ui.theme.LogisticsAssistantTheme

/**
 * =====================================================================
 *  MAIN ACTIVITY (SAFE, STABLE, NON-CRASHING)
 * =====================================================================
 *
 *  • Keeps screen always on
 *  • Shows over lockscreen
 *  • Foreground service holds continuous operations
 *  • No moveTaskToFront (NO crash)
 *  • Back button blocked
 *  • UI can go background safely
 */
class MainActivity : ComponentActivity() {

    private val sharedMessages = mutableStateListOf<Pair<String, Boolean>>()
    private var sharedMicLevel = mutableStateOf(0)

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("message")?.let {
                sharedMessages.add(it to false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake + show over lockscreen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        registerReceiver(
            messageReceiver,
            IntentFilter("VOICE_ASSISTANT_MESSAGE"),
            RECEIVER_EXPORTED
        )

        checkMicPermission()
    }

    // ---------------------------------------------------------------------
    // PERMISSIONS
    // ---------------------------------------------------------------------
    private fun checkMicPermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceAssistant()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1001
            )
        }
    }

    // ---------------------------------------------------------------------
    // START FOREGROUND SERVICE + UI
    // ---------------------------------------------------------------------
    private fun startVoiceAssistant() {
        val svcIntent = Intent(this, AssistantService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else
            startService(svcIntent)

        setContent {
            LogisticsAssistantTheme {
                val context = LocalContext.current

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen(
                        context = context,
                        messages = sharedMessages,
                        micLevel = { sharedMicLevel.value },
                        onSend = { text ->
                            sharedMessages.add(text to true)
                            AssistantManager.sendText(text)
                        },
                        onReset = {
                            sharedMessages.clear()
                            AssistantManager.resetAssistant(context)
                        }
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // PERMISSION CALLBACK
    // ---------------------------------------------------------------------
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Microphone permission granted.", Toast.LENGTH_SHORT).show()
                startVoiceAssistant()
            } else {
                Toast.makeText(this, "Microphone is required.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------------
    // DISABLE BACK BUTTON
    // ---------------------------------------------------------------------
    override fun onBackPressed() {
        // Block back
    }

    // ---------------------------------------------------------------------
    // NO moveTaskToFront (FIXED CRASH)
    // App can go background; service keeps running.
    // ---------------------------------------------------------------------
    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
    }

    // ---------------------------------------------------------------------
    // CLEANUP
    // ---------------------------------------------------------------------
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(messageReceiver) } catch (_: Exception) {}
    }
}
