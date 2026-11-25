package com.example.spyder

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.example.spyder.ui.RobotScreen
import com.example.spyder.ui.RobotViewModel
import com.example.spyder.ui.theme.SpyderTheme

class MainActivity : ComponentActivity() {
    private lateinit var robot: RobotViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        robot = ViewModelProvider(this)[RobotViewModel::class.java]
        setContent {
            SpyderTheme {
                val state by robot.state.collectAsState()
                RobotScreen(
                    state = state,
                    onConnect = robot::connectWifi,
                    onCalibrate = robot::calibrate,
                    onZero = robot::zero,
                    onResetMap = robot::resetMap,
                    onStart = robot::startStream,
                    onStop = robot::stopStream,
                    onAction = robot::play
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        robot.lookForRobot()
    }
}
