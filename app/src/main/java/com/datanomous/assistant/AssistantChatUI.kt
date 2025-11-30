package com.datanomous.assistant.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.datanomous.assistant.AssistantManager
import com.datanomous.assistant.AssistantService
import com.datanomous.assistant.audio.MicUiState
import com.datanomous.assistant.audio.runVoiceCalibration
import com.datanomous.assistant.monitor.HealthMonitor
import com.datanomous.assistant.monitor.SystemHealth
import com.datanomous.assistant.shared.AssistantBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 🎨 AssistantUI
 *
 * PURE UI — does NOT manage microphone or WebSocket.
 * Those are handled entirely inside LogisticsAssistantService.
 *
 * Responsibilities:
 *    - Display bot & user messages
 *    - Show mic level (optional)
 *    - Send user commands → LogisticsAssistantManager.sendText()
 */
class AssistantUI : ComponentActivity() {

    private val messages = mutableStateListOf<Pair<String, Boolean>>() // (text, isUser)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {

            // This MUST BE inside the Composable
            val uiScope = rememberCoroutineScope()

            // Subscribe safely inside Composition
            LaunchedEffect(Unit) {
                AssistantBus.botMsgs.collectLatest { text ->
                    uiScope.launch {
                        messages.add(text to false)
                    }
                }
            }

            val micLevelState by MicUiState.level.collectAsState()

            ChatScreen(
                context = this,
                messages = messages,
                micLevel = { micLevelState },
                onSend = { text ->
                    if (text.isNotBlank()) {
                        uiScope.launch { messages.add(text to true) }
                        AssistantManager.sendText(text)
                    }
                },
                onReset = {
                    uiScope.launch { messages.clear() }
                    AssistantManager.resetAssistant(this)
                }
            )
        }
    }
}



/**
 * =====================================================================
 * 💬 Main Chat UI
 * =====================================================================
 */
@Composable
fun ChatScreen(
    context: Context,
    messages: SnapshotStateList<Pair<String, Boolean>>,
    micLevel: () -> Int,
    onSend: (String) -> Unit,
    onReset: () -> Unit
) {
    var input by remember { mutableStateOf("") }

    // ⭐️ HealthMonitor state for top bar & mic icon
    val healthState by SystemHealth.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E0E))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {

        // HEADER
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "AKCA - DEPO ASİSTANI",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                onReset()
                            }
                        )
                    }
            )
        }

        // ⭐️ TOP STATUS BAR
        StatusBar()

        // CHAT LIST
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
            reverseLayout = true
        ) {
            items(messages.reversed()) { (msg, isUser) ->
                MessageBubble(msg, isUser)
            }
        }

        // ⭐️ BOTTOM MIC LEVEL BAR
        MicIndicator(level = micLevel())

        // INPUT FIELD + BUTTONS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(fontSize = 14.sp, color = Color.White),
                singleLine = true
            )

            Spacer(Modifier.width(6.dp))

            // MIC BUTTON WITH DOUBLE-TAP CALIBRATION
            val micActive = healthState.mic == HealthMonitor.State.ONLINE

            MicButtonWithCalibration(
                micActive = micActive,
                context = context,
                onMicPressed = {
                    try {
                        if (micActive)
                            AssistantService.uiMuteMic()
                        else
                            AssistantService.uiActivateMic()
                    } catch (_: Throwable) {}
                }
            )

            Spacer(Modifier.width(6.dp))

            // SEND BUTTON
            IconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        onSend(input.trim())
                        input = ""
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "Send / Prompt",
                    tint = Color(0xFF69A6FF),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}


/**
 * ⭐️ STATUS BAR — DRIVEN BY SystemHealth.state
 */
@Composable
fun StatusBar() {
    val health by SystemHealth.state.collectAsState()
    val micLevel by MicUiState.level.collectAsState()

    fun colorFor(state: HealthMonitor.State): Color = when (state) {
        HealthMonitor.State.ONLINE   -> Color(0xFF3DDC84)
        HealthMonitor.State.DEGRADED -> Color(0xFFFFC300)
        HealthMonitor.State.OFFLINE  -> Color(0xFFFF3B30)
        HealthMonitor.State.UNKNOWN  -> Color.Gray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .height(30.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {

        Text("📡 Bağlantı", color = colorFor(health.chat), fontSize = 10.sp)
        Spacer(Modifier.width(10.dp))

        // ⭐️ MIC + LIVE BAR
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🎤", color = colorFor(health.mic), fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            MicLiveBar(level = micLevel)   // <—— NEW
        }
    }
}



/**
 * Chat bubble UI
 */
@Composable
fun MessageBubble(text: String, isUser: Boolean) {
    val bubbleColor = if (isUser) Color(0xFF1E88E5) else Color(0xFF2E7D32)
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Box(
            Modifier
                .align(align)
                .clip(MaterialTheme.shapes.large)
                .background(bubbleColor)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}


/**
 * ⭐️ MIC LEVEL INDICATOR — uses MicUiState.level
 * (Assuming you already have this; otherwise add:)
 */
@Composable
fun MicIndicator(level: Int) {
    val barColor = if (level > 40) Color.Green else Color.Gray

    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color(0xFF303030))
    ) {
        Box(
            Modifier
                .fillMaxWidth(level.coerceIn(0, 100) / 100f)
                .height(6.dp)
                .background(barColor)
        )
    }
}


/**
 * ⭐️ MIC BUTTON WITH DOUBLE-TAP CALIBRATION
 *
 * - Single tap → toggles mic (pause/resume) via onMicPressed()
 * - Double tap (within 300ms) → runs ConfigureVoice calibration
 */
@Composable
fun MicButtonWithCalibration(
    micActive: Boolean,
    context: Context,
    onMicPressed: () -> Unit
) {
    var lastTapTime by remember { mutableStateOf(0L) }

    IconButton(
        onClick = {
            val now = System.currentTimeMillis()
            val diff = now - lastTapTime
            lastTapTime = now

            if (diff < 300) {
                // DOUBLE TAP → RUN AUTO CALIBRATION
                runVoiceCalibration(context)
            } else {
                // SINGLE TAP → NORMAL MIC ACTION
                onMicPressed()
            }
        }
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Mic",
            tint = if (micActive) Color(0xFF3DDC84) else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun MicLiveBar(level: Int) {
    val pct = level.coerceIn(0, 100) / 100f

    Box(
        Modifier
            .width(40.dp)
            .height(6.dp)
            .background(Color.DarkGray, CircleShape)
    ) {
        Box(
            Modifier
                .fillMaxWidth(pct)
                .height(6.dp)
                .background(
                    when {
                        level > 60 -> Color.Green
                        level > 30 -> Color.Yellow
                        else -> Color.Red
                    },
                    CircleShape
                )
        )
    }
}
