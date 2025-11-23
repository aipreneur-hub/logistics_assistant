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

class MainActivity : ComponentActivity() {

    private val sharedMessages = mutableStateListOf<Pair<String, Boolean>>()
    private var sharedMicLevel by mutableStateOf(0)

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra("message") ?: return
            sharedMessages.add(msg to false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES
        )

        registerReceiver(
            messageReceiver,
            IntentFilter("VOICE_ASSISTANT_MESSAGE"),
            RECEIVER_EXPORTED
        )

        checkMicPermission()
    }

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
                        micLevel = { sharedMicLevel },
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

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(messageReceiver)
    }
}
