package com.example.spyder.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.spyder.robot.RobotEndpoint
import com.example.spyder.ui.theme.ScanOrange
import com.example.spyder.ui.theme.SpyderBackground
import com.example.spyder.ui.theme.SpyderBlack
import com.example.spyder.ui.theme.SpyderMaroon
import com.example.spyder.ui.theme.SpyderMaroonDeep
import com.example.spyder.ui.theme.SpyderRed
import com.example.spyder.ui.theme.SpyderWhite
import kotlinx.coroutines.delay
import java.util.Locale

private const val HINT =
    "Tap Start scan. The motor stays off until then. Hold still, then Calibrate front. The blue arrow is the heading. Red A is the start. Orange dots are the live scan."

@Composable
fun RobotScreen(
    state: RobotUiState,
    onConnect: () -> Unit,
    onCalibrate: () -> Unit,
    onZero: () -> Unit,
    onResetMap: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAction: (String) -> Unit
) {
    val view = LocalView.current
    DisposableEffect(state.link) {
        view.keepScreenOn = state.link == LinkState.Live
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SpyderBackground)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(SpyderRed)
        )
        Header()
        if (state.link == LinkState.Live) {
            LivePane(
                state = state,
                onCalibrate = onCalibrate,
                onZero = onZero,
                onResetMap = onResetMap,
                onStart = onStart,
                onStop = onStop,
                onAction = onAction,
                modifier = Modifier.weight(1f)
            )
        } else {
            JoinPane(
                state = state,
                onConnect = onConnect,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(SpyderRed)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SPYDER",
                color = SpyderWhite,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                letterSpacing = 3.sp
            )
            Text(
                text = "Robot map",
                color = SpyderWhite.copy(alpha = 0.68f),
                fontSize = 13.sp
            )
        }
        Text(
            text = RobotEndpoint.HOST,
            color = SpyderWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun LivePane(
    state: RobotUiState,
    onCalibrate: () -> Unit,
    onZero: () -> Unit,
    onResetMap: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(400)
        }
    }
    val fresh = state.lastPacketMs > 0 && now - state.lastPacketMs < 1_500
    val ready = !state.busy
    val streaming = state.streaming == true

    Column(modifier = modifier.fillMaxSize()) {
        StatusRow(state = state, fresh = fresh)
        Text(
            text = telemetry(state),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = SpyderWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        if (state.versions.isNotBlank()) {
            Text(
                text = state.versions,
                modifier = Modifier.padding(horizontal = 16.dp),
                color = SpyderWhite.copy(alpha = 0.6f),
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        MapCanvas(
            poseX = state.poseX,
            poseY = state.poseY,
            yawDeg = state.yawDeg ?: 0.0,
            scanMm = state.scanMm,
            occupancy = state.occupancy,
            mapSize = state.mapSize,
            mapStamp = state.mapStamp,
            resolutionM = state.resolutionM,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Legend(Color.White, "Free")
            Legend(Color.Black, "Wall")
            Legend(Color(0xFFE10600), "A")
            Legend(Color(0xFF2F6FE4), "Nose")
            Legend(ScanOrange, "Scan")
            Text(
                text = "+X right  ·  +Y up",
                color = SpyderWhite.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
        Column(
            modifier = Modifier
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SpyderMaroon)
            )
            if (state.detail.isNotBlank() && state.detail != HINT) {
                Text(
                    text = state.detail,
                    color = SpyderRed,
                    fontSize = 13.sp
                )
            }
            Text(
                text = HINT,
                color = SpyderWhite.copy(alpha = 0.72f),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
            Button(
                onClick = onCalibrate,
                enabled = ready,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpyderRed,
                    contentColor = SpyderWhite,
                    disabledContainerColor = SpyderMaroon,
                    disabledContentColor = SpyderWhite.copy(alpha = 0.5f)
                )
            ) {
                Text("Calibrate front", fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(
                    text = "Zero arrow",
                    filled = false,
                    enabled = ready,
                    onClick = onZero,
                    modifier = Modifier.weight(1f)
                )
                Choice(
                    text = "Reset map",
                    filled = false,
                    enabled = ready,
                    onClick = onResetMap,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(
                    text = "Start scan",
                    filled = !streaming,
                    enabled = ready,
                    onClick = onStart,
                    modifier = Modifier.weight(1f)
                )
                Choice(
                    text = "Stop",
                    filled = streaming,
                    enabled = ready,
                    onClick = onStop,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = actionLine(state),
                color = SpyderWhite,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            val motionReady = ready && !queueFull(state)
            Choice(
                text = "Forward",
                filled = state.actionCurrent == "walk_forward",
                enabled = motionReady,
                onClick = { onAction("walk_forward") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(
                    text = "Left",
                    filled = state.actionCurrent == "turn_left",
                    enabled = motionReady,
                    onClick = { onAction("turn_left") },
                    modifier = Modifier.weight(1f)
                )
                Choice(
                    text = "Neutral",
                    filled = state.actionCurrent == "neutral",
                    enabled = motionReady,
                    onClick = { onAction("neutral") },
                    modifier = Modifier.weight(1f)
                )
                Choice(
                    text = "Right",
                    filled = state.actionCurrent == "turn_right",
                    enabled = motionReady,
                    onClick = { onAction("turn_right") },
                    modifier = Modifier.weight(1f)
                )
            }
            Choice(
                text = "Back",
                filled = state.actionCurrent == "walk_backward",
                enabled = motionReady,
                onClick = { onAction("walk_backward") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StatusRow(state: RobotUiState, fresh: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Chip("Wi-Fi", on = true)
        Chip("LiDAR", on = state.lidar == true, known = state.lidar != null)
        Chip("IMU", on = state.imu == true, known = state.imu != null)
        Chip("Stream", on = state.streaming == true, known = state.streaming != null)
        Chip(formatYaw(state.yawDeg), on = state.yawDeg != null)
        Chip(
            label = when {
                state.streaming != true -> "Idle"
                state.lastPacketMs == 0L -> if (state.usingSocket) "Socket" else "Link"
                fresh -> if (state.usingSocket) "Socket" else "Poll"
                else -> "No packets"
            },
            on = state.streaming == true && fresh,
            warn = state.streaming == true && state.lastPacketMs > 0 && !fresh
        )
    }
}

@Composable
private fun JoinPane(
    state: RobotUiState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (permissions.all { granted[it] == true }) {
            notice = connectIfAllowed(context, onConnect)
        } else {
            notice = "Allow nearby Wi-Fi so Spyder can join Twist-auto."
        }
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = SpyderBlack,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SpyderMaroon, RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.statusLine,
                        color = SpyderWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.link == LinkState.Probing || state.link == LinkState.RequestingWifi) {
                        CircularProgressIndicator(
                            color = SpyderRed,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Text(
                    text = networkLine(state.ssid),
                    color = SpyderWhite.copy(alpha = 0.7f),
                    fontSize = 13.sp
                )
                Text(
                    text = "The phone joins the robot Wi-Fi, then uses ${RobotEndpoint.HOST}. The P4 has no Wi-Fi of its own. This is the onboard ESP32-C6.",
                    color = SpyderWhite,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Text(
                    text = "PASSWORD",
                    color = SpyderWhite.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp
                )
                SelectionContainer {
                    Text(
                        text = RobotEndpoint.PASSWORD,
                        color = SpyderWhite,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Step("1", "Connect, then confirm Twist-auto on the system dialog.")
                Step("2", "Tap Start scan. Nothing is live until the motor spins.")
                Step("3", "Hold still, calibrate front, and turn. Walls fill in as you go.")
                val message = notice?.takeIf { it.isNotBlank() } ?: state.detail
                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        color = SpyderRed,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
                Button(
                    onClick = {
                        val missing = permissions.any {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing) {
                            launcher.launch(permissions)
                        } else {
                            notice = connectIfAllowed(context, onConnect)
                        }
                    },
                    enabled = state.link != LinkState.RequestingWifi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpyderRed,
                        contentColor = SpyderWhite,
                        disabledContainerColor = SpyderMaroon,
                        disabledContentColor = SpyderWhite.copy(alpha = 0.55f)
                    )
                ) {
                    Text(
                        text = if (state.link == LinkState.RequestingWifi) "Waiting for confirmation" else "Connect",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SpyderMaroon),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SpyderWhite)
                ) {
                    Text("Wi-Fi settings")
                }
            }
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(SpyderMaroonDeep, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = SpyderWhite, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Text(
            text = text,
            color = SpyderWhite.copy(alpha = 0.86f),
            fontSize = 14.sp,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Choice(
    text: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SpyderMaroon,
                contentColor = SpyderWhite,
                disabledContainerColor = SpyderMaroonDeep,
                disabledContentColor = SpyderWhite.copy(alpha = 0.45f)
            )
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, SpyderMaroon),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = SpyderWhite,
                disabledContentColor = SpyderWhite.copy(alpha = 0.4f)
            )
        ) {
            Text(text)
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, known: Boolean = true, warn: Boolean = false) {
    val border = when {
        warn -> SpyderRed
        on -> SpyderMaroon
        else -> SpyderWhite.copy(alpha = 0.28f)
    }
    val fill = when {
        on -> SpyderMaroonDeep
        else -> Color.Transparent
    }
    val text = when {
        !known -> SpyderWhite.copy(alpha = 0.45f)
        warn -> SpyderRed
        else -> SpyderWhite
    }
    Text(
        text = label,
        color = text,
        fontSize = 12.sp,
        modifier = Modifier
            .background(fill, RoundedCornerShape(50))
            .border(1.dp, border, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = SpyderWhite.copy(alpha = 0.82f), fontSize = 12.sp)
    }
}

private fun connectIfAllowed(context: Context, onConnect: () -> Unit): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !locationEnabled(context)) {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        return "Turn location on, then tap Connect again."
    }
    onConnect()
    return null
}

private fun locationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(LocationManager::class.java) ?: return true
    return manager.isLocationEnabled
}

private fun networkLine(ssid: String?): String = when {
    ssid.isNullOrBlank() -> "Wi-Fi network not reported yet"
    ssid == RobotEndpoint.SSID -> "Phone is on Twist-auto"
    else -> "Phone is on $ssid"
}

private fun queueFull(state: RobotUiState): Boolean {
    return state.actionBusy == true && state.actionQueued.size >= 2
}

private fun actionLine(state: RobotUiState): String {
    val now = state.actionCurrent?.let(::motionLabel)
    val waiting = state.actionQueued.map(::motionLabel)
    return when {
        now == null && waiting.isEmpty() -> "No move playing. One runs, two can wait."
        now != null && waiting.isEmpty() -> "Now $now"
        now != null -> "Now $now · waiting ${waiting.joinToString(", ")}"
        else -> "Waiting ${waiting.joinToString(", ")}"
    }
}

private fun motionLabel(name: String): String = when (name) {
    "neutral" -> "Neutral"
    "walk_forward" -> "Forward"
    "walk_backward" -> "Back"
    "turn_left" -> "Left"
    "turn_right" -> "Right"
    else -> name
}

private fun formatYaw(yaw: Double?): String {
    if (yaw == null) return "yaw —"
    val wrapped = ((yaw % 360.0) + 360.0) % 360.0
    return "yaw " + String.format(Locale.US, "%.0f°", wrapped)
}

private fun telemetry(state: RobotUiState): String {
    val hits = state.scanMm.count { it > 0 }
    val map = if (state.mapVersion >= 0) state.mapVersion.toString() else "—"
    return String.format(
        Locale.US,
        "x %+.2f   y %+.2f m   %d hits   map %s",
        state.poseX,
        state.poseY,
        hits,
        map
    )
}
