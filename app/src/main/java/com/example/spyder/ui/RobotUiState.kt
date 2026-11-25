package com.example.spyder.ui

enum class LinkState {
    Probing,
    Idle,
    RequestingWifi,
    Live,
    Offline
}

data class RobotUiState(
    val link: LinkState = LinkState.Probing,
    val statusLine: String = "Looking for the robot",
    val detail: String = "",
    val ssid: String? = null,
    val lidar: Boolean? = null,
    val imu: Boolean? = null,
    val streaming: Boolean? = null,
    val yawDeg: Double? = null,
    val poseX: Double = 0.0,
    val poseY: Double = 0.0,
    val scanMm: IntArray = IntArray(0),
    val mapVersion: Long = -1,
    val occupancy: ByteArray = ByteArray(0),
    val mapSize: Int = 0,
    val mapStamp: Int = 0,
    val resolutionM: Double = 0.05,
    val versions: String = "",
    val busy: Boolean = false,
    val actionBusy: Boolean? = null,
    val actionCurrent: String? = null,
    val actionQueued: List<String> = emptyList(),
    val lastPacketMs: Long = 0L,
    val usingSocket: Boolean = false
)
