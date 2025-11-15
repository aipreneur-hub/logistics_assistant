package com.datanomous.logisticsassistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.datanomous.logisticsassistant.ui.ChatScreen
import com.datanomous.logisticsassistant.ui.theme.LogisticsAssistantTheme
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri

/**
 * =====================================================================
 *  MAINACTIVITY — CLEAN DESIGN (UI-only) + FULL COMMENTS
 * =====================================================================
 *
 *  PURPOSE OF THIS ACTIVITY:
 *  -------------------------
 *  ✓ Owns ONLY UI rendering and user interactions.
 *  ✓ Does NOT touch microphone, AudioRecord, WebSockets, or TTS.
 *  ✓ Starts the ForegroundService that owns all audio + networking.
 *  ✓ Receives assistant messages through a broadcast.
 *  ✓ Sends user messages to the Service using Intents.
 *
 *  WHY THIS DESIGN:
 *  ----------------
 *  • Avoids race conditions caused by Activity lifecycle.
 *  • Prevents mic from being recreated when Activity rotates.
 *  • Ensures Service is the single owner of long-running resources.
 *  • Makes MainActivity simple, testable, and disposable.
 *
 */
class MainActivity : ComponentActivity() {

    // Chat UI state (list of messages)
    private val sharedMessages = mutableStateListOf<Pair<String, Boolean>>()

    // Optional mic level (if later used via broadcast)
    private var sharedMicLevel by mutableStateOf(0)

    /**
     * BroadcastReceiver → receives backend messages pushed from Service.
     * The Service sends broadcasts whenever /text WebSocket receives a message.
     */
    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra("message") ?: return

            // Add assistant message to UI (isUser = false)
            sharedMessages.add(msg to false)
        }
    }

    // --------------------------------------------------------------------
    // ACTIVITY LIFECYCLE
    // --------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES
        )

        // Register broadcast listener BEFORE UI starts,
        // so no messages are missed.
        registerReceiver(
            messageReceiver,
            IntentFilter("VOICE_ASSISTANT_MESSAGE"),
            RECEIVER_EXPORTED
        )

        // App flow begins with microphone permission check.
        // Service cannot start without mic permission.
        checkMicPermission()
    }

    // --------------------------------------------------------------------
    // MICROPHONE PERMISSION → START SERVICE
    // --------------------------------------------------------------------
    /**
     * Checks microphone permission.
     * If granted → start the assistant service + load UI.
     * If not → request permission.
     */
    private fun checkMicPermission() {
        val permission = Manifest.permission.RECORD_AUDIO

        if (ContextCompat.checkSelfPermission(this, permission)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceAssistant()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 1001)
        }
    }

    /**
     * START THE FOREGROUND SERVICE
     * -----------------------------
     * This Service owns:
     *      - MicStreamer (AudioRecord + /stt WebSocket)
     *      - TTSPlayer
     *      - ChatWebSocket (/text)
     *
     * Activity NEVER creates MicStreamer anymore.
     */
    private fun startVoiceAssistant() {

        val svcIntent = Intent(this, LogisticsAssistantService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else
            startService(svcIntent)


        // -----------------------------------------------------------------
        // UI LAYER — Compose
        // -----------------------------------------------------------------
        setContent {
            LogisticsAssistantTheme {
                val context = LocalContext.current

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    /**
                     * ChatScreen handles:
                     *    • Chat layout
                     *    • Input box
                     *    • Sending messages
                     *
                     * onSend → forwards text to the Service ONLY.
                     */
                    ChatScreen(
                        context = context,
                        messages = sharedMessages,
                        micLevel = { sharedMicLevel },
                        onSend = { text ->
                            // Add user message to UI
                            sharedMessages.add(text to true)

                            // Send user text to Service
                            com.datanomous.logisticsassistant.LogisticsAssistantManager.sendText(text)
                        }
                    )
                }
            }
        }
    }

    // --------------------------------------------------------------------
    // PERMISSION RESULT
    // --------------------------------------------------------------------
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

    // --------------------------------------------------------------------
    // CLEANUP
    // --------------------------------------------------------------------
    override fun onDestroy() {
        super.onDestroy()

        // Remove broadcast listener
        unregisterReceiver(messageReceiver)
    }
}
