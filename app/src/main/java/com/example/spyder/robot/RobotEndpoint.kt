package com.example.spyder.robot

object RobotEndpoint {
    const val SSID = "Twist-auto"
    const val PASSWORD = "twistmap1"
    const val HOST = "192.168.4.1"
    const val BASE = "http://192.168.4.1"
    const val LIVE_SOCKET = "ws://192.168.4.1/ws/live"

    const val STATUS = "/api/status"
    const val CATALOG = "/api"
    const val LIVE = "/api/live.json"
    const val MAP = "/api/map.bin"
    const val CALIBRATE = "/api/imu/calibrate"
    const val ZERO = "/api/imu/zero"
    const val RESET = "/api/map/reset"
    const val SCAN_START = "/api/scan/start"
    const val SCAN_STOP = "/api/scan/stop"
    const val STREAM_START = "/api/stream/start"
    const val STREAM_STOP = "/api/stream/stop"
    const val ACTION = "/api/action"

    fun action(name: String): String = "/api/action/$name"
}
