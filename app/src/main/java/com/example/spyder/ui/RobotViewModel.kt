package com.example.spyder.ui

import android.app.Application
import android.net.Network
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spyder.robot.Frame
import com.example.spyder.robot.RobotClient
import com.example.spyder.robot.RobotEndpoint
import com.example.spyder.robot.RobotJson
import com.example.spyder.robot.RobotWifi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RobotViewModel(app: Application) : AndroidViewModel(app) {
    private val wifi = RobotWifi(app)
    private val gate = Mutex()
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(RobotUiState())
    val state: kotlinx.coroutines.flow.StateFlow<RobotUiState> = _state

    private var client: RobotClient? = null
    private var activeNetwork: Network? = null
    private var liveJob: Job? = null
    private var mapJob: Job? = null
    private var mapGeneration = 0
    private var fetchedVersion = Long.MIN_VALUE

    @Volatile
    private var joining = false

    fun lookForRobot() {
        viewModelScope.launch {
            if (joining) return@launch
            _state.update { it.copy(ssid = wifi.ssid()) }
            if (isFresh()) return@launch
            _state.update { current ->
                if (current.link == LinkState.RequestingWifi || current.link == LinkState.Live) {
                    current
                } else {
                    current.copy(link = LinkState.Probing, statusLine = "Looking for the robot")
                }
            }
            val found = search()
            if (joining) {
                found?.client?.close()
                return@launch
            }
            if (found == null) {
                _state.update { current ->
                    if (current.link == LinkState.RequestingWifi || current.link == LinkState.Live) {
                        current
                    } else {
                        current.copy(
                            link = LinkState.Idle,
                            statusLine = "Join Twist-auto",
                            ssid = wifi.ssid(),
                            detail = if (current.detail.startsWith("Could not") || current.detail.startsWith("Twist-auto")) {
                                current.detail
                            } else {
                                ""
                            }
                        )
                    }
                }
                return@launch
            }
            gate.withLock {
                if (joining || isFresh()) {
                    found.client.close()
                    return@withLock
                }
                adopt(found)
            }
        }
    }

    fun connectWifi() {
        if (joining) return
        joining = true
        _state.update {
            it.copy(
                link = LinkState.RequestingWifi,
                statusLine = "Join Twist-auto",
                detail = "Confirm Twist-auto in the system dialog. The password is filled in."
            )
        }
        try {
            wifi.requestTwist(
                onReady = { network ->
                    joining = false
                    viewModelScope.launch { adoptNetwork(network) }
                },
                onLost = { network ->
                    joining = false
                    if (activeNetwork == network) {
                        dropLink("Robot Wi-Fi dropped")
                    }
                },
                onUnavailable = {
                    joining = false
                    _state.update {
                        it.copy(
                            link = LinkState.Idle,
                            statusLine = "Join Twist-auto",
                            detail = "Could not join Twist-auto. If that network is not listed, the ESP32-C6 is missing or not running hosted firmware. LiDAR and IMU still work from the serial console."
                        )
                    }
                }
            )
        } catch (error: SecurityException) {
            joining = false
            _state.update {
                it.copy(
                    link = LinkState.Idle,
                    detail = error.message ?: "Wi-Fi permission is missing"
                )
            }
        }
    }

    fun calibrate() = command(
        RobotEndpoint.CALIBRATE,
        "calibrate",
        "Front set. The teal arrow is the nose."
    )

    fun zero() = command(RobotEndpoint.ZERO, "zero", "Arrow zeroed")

    fun resetMap() = command(RobotEndpoint.RESET, "reset", "Map cleared") {
        fetchedVersion = Long.MIN_VALUE
        _state.update { it.copy(occupancy = ByteArray(0), mapSize = 0, mapVersion = -1) }
        client?.let { refreshMap(it, null) }
    }

    fun startStream() = command(
        RobotEndpoint.START,
        "start",
        "LiDAR spinning",
        streaming = true
    )

    fun stopStream() = command(
        RobotEndpoint.STOP,
        "stop",
        "LiDAR motor off",
        streaming = false
    )

    override fun onCleared() {
        liveJob?.cancel()
        mapJob?.cancel()
        client?.close()
        client = null
        wifi.release()
    }

    private suspend fun adoptNetwork(network: Network) {
        val found = withContext(Dispatchers.IO) {
            var discovered: Found? = null
            repeat(6) { attempt ->
                val bound = RobotClient(network)
                val body = runCatching { bound.get(RobotEndpoint.STATUS) }.getOrNull()
                if (body != null && RobotJson.isJsonObject(body)) {
                    discovered = Found(bound, network, body)
                    return@withContext discovered
                }
                bound.close()
                if (attempt < 5) delay(400)
            }
            discovered
        }
        if (found == null) {
            _state.update {
                it.copy(
                    link = LinkState.Idle,
                    statusLine = "Join Twist-auto",
                    detail = "Joined Twist-auto, but ${RobotEndpoint.HOST} did not answer."
                )
            }
            return
        }
        gate.withLock { adopt(found) }
    }

    private fun adopt(found: Found) {
        liveJob?.cancel()
        mapJob?.cancel()
        client?.close()
        client = found.client
        activeNetwork = found.network
        fetchedVersion = Long.MIN_VALUE
        val parsed = RobotJson.status(found.status)
        Log.i(TAG, "Connected to ${RobotEndpoint.HOST}")
        _state.update {
            it.copy(
                link = LinkState.Live,
                statusLine = "Connected",
                detail = "Hold the robot still, then calibrate front.",
                ssid = wifi.ssid() ?: RobotEndpoint.SSID,
                lidar = parsed.lidar,
                imu = parsed.imu,
                streaming = parsed.streaming,
                yawDeg = parsed.yawDeg ?: it.yawDeg,
                versions = parsed.versions.ifBlank { it.versions },
                resolutionM = parsed.resolutionM ?: it.resolutionM
            )
        }
        startLive(found.client)
        refreshMap(found.client, null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { found.client.post(RobotEndpoint.START) }
                .onSuccess {
                    _state.update { current ->
                        current.copy(
                            streaming = true,
                            detail = "Scan running. Hold the robot still, then calibrate front."
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { current -> current.copy(detail = friendly(error)) }
                }
            mergeCatalog(found.client)
        }
    }

    private suspend fun mergeCatalog(bound: RobotClient) {
        val body = runCatching { bound.get(RobotEndpoint.CATALOG) }.getOrNull() ?: return
        val extra = RobotJson.status(body)
        _state.update { current ->
            current.copy(
                lidar = extra.lidar ?: current.lidar,
                imu = extra.imu ?: current.imu,
                streaming = extra.streaming ?: current.streaming,
                versions = extra.versions.ifBlank { current.versions },
                resolutionM = extra.resolutionM ?: current.resolutionM
            )
        }
    }

    private fun startLive(bound: RobotClient) {
        liveJob?.cancel()
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            var skipSocket = false
            while (isActive && client === bound) {
                try {
                    if (!skipSocket) {
                        val opened = bound.streamSocket(
                            onOpen = { _state.update { it.copy(usingSocket = true) } },
                            onText = { handleLive(bound, it) }
                        )
                        if (!opened) skipSocket = true else continue
                    }
                    _state.update { it.copy(usingSocket = false) }
                    bound.pollLive { handleLive(bound, it) }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (client !== bound) break
                    _state.update { it.copy(detail = friendly(error)) }
                    delay(700)
                }
            }
        }
    }

    private fun handleLive(bound: RobotClient, body: String) {
        if (client !== bound) return
        val frame = RobotJson.live(body) ?: return
        _state.update { current ->
            current.copy(
                link = LinkState.Live,
                poseX = frame.x ?: current.poseX,
                poseY = frame.y ?: current.poseY,
                yawDeg = frame.yawDeg ?: current.yawDeg,
                scanMm = frame.scanMm ?: current.scanMm,
                mapVersion = frame.mapVersion ?: current.mapVersion,
                streaming = frame.streaming ?: current.streaming,
                lastPacketMs = System.currentTimeMillis()
            )
        }
        if (frame.mapVersion != null) refreshMap(bound, frame.mapVersion)
    }

    private fun refreshMap(bound: RobotClient, version: Long?) {
        if (version != null && version == fetchedVersion) return
        if (version != null) fetchedVersion = version
        val generation = ++mapGeneration
        mapJob?.cancel()
        mapJob = viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching { bound.getBytes(RobotEndpoint.MAP) }.getOrElse { error ->
                if (version != null) fetchedVersion = Long.MIN_VALUE
                _state.update { it.copy(detail = friendly(error)) }
                return@launch
            }
            if (!isActive || generation != mapGeneration) return@launch
            val side = Frame.mapSide(bytes)
            if (side == null) {
                _state.update { it.copy(detail = "Map was ${bytes.size} bytes, expected a square grid") }
                return@launch
            }
            _state.update { it.copy(occupancy = bytes, mapSize = side) }
        }
    }

    private fun refreshStatus(bound: RobotClient) {
        viewModelScope.launch(Dispatchers.IO) {
            val body = runCatching { bound.get(RobotEndpoint.STATUS) }.getOrNull() ?: return@launch
            val parsed = RobotJson.status(body)
            _state.update { current ->
                current.copy(
                    lidar = parsed.lidar ?: current.lidar,
                    imu = parsed.imu ?: current.imu,
                    streaming = parsed.streaming ?: current.streaming,
                    yawDeg = if (current.lastPacketMs > 0) current.yawDeg else parsed.yawDeg ?: current.yawDeg,
                    versions = parsed.versions.ifBlank { current.versions },
                    resolutionM = parsed.resolutionM ?: current.resolutionM
                )
            }
        }
    }

    private fun command(
        path: String,
        word: String,
        ok: String,
        streaming: Boolean? = null,
        after: () -> Unit = {}
    ) {
        val bound = client
        if (bound == null) {
            _state.update { it.copy(detail = "Connect to Twist-auto first") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, detail = ok) }
            val result = withContext(Dispatchers.IO) {
                runCatching { bound.post(path) }.recoverCatching { error ->
                    if (bound.send(word)) ok else throw error
                }
            }
            if (result.isSuccess) {
                after()
                val body = result.getOrNull().orEmpty().trim()
                val shown = when {
                    body.isBlank() || body == ok -> ok
                    body.length <= 80 -> body
                    else -> ok
                }
                _state.update { current ->
                    current.copy(
                        busy = false,
                        detail = shown,
                        streaming = streaming ?: current.streaming
                    )
                }
                refreshStatus(bound)
            } else {
                val failure = result.exceptionOrNull()
                _state.update {
                    it.copy(
                        busy = false,
                        detail = failure?.let(::friendly) ?: "Command failed"
                    )
                }
            }
        }
    }

    private suspend fun search(): Found? = withContext(Dispatchers.IO) {
        val networks = buildList {
            wifi.heldNetwork?.let { add(it) }
            addAll(wifi.wifiNetworks())
        }.distinct()
        for (network in networks) {
            probe(network)?.let { return@withContext it }
        }
        if (networks.isEmpty()) probe(null) else null
    }

    private fun probe(network: Network?): Found? {
        val bound = RobotClient(network)
        val body = runCatching { bound.get(RobotEndpoint.STATUS) }
            .onFailure { Log.i(TAG, "No robot on ${network ?: "default"}: ${it.message}") }
            .getOrNull()
        if (body != null && RobotJson.isJsonObject(body)) {
            return Found(bound, network, body)
        }
        bound.close()
        return null
    }

    private fun dropLink(message: String) {
        liveJob?.cancel()
        mapJob?.cancel()
        client?.close()
        client = null
        activeNetwork = null
        _state.update {
            it.copy(link = LinkState.Offline, statusLine = "Wi-Fi dropped", detail = message)
        }
    }

    private fun isFresh(): Boolean {
        val current = _state.value
        return current.link == LinkState.Live &&
            current.lastPacketMs > 0 &&
            System.currentTimeMillis() - current.lastPacketMs < 2_000 &&
            liveJob?.isActive == true
    }

    private fun friendly(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is SocketTimeoutException -> return "${RobotEndpoint.HOST} did not answer"
                is ConnectException -> return "Could not reach ${RobotEndpoint.HOST}"
                is UnknownHostException -> return "${RobotEndpoint.HOST} was not found"
            }
            current = current.cause
        }
        val message = error.message.orEmpty()
        return when {
            message.contains("cleartext", true) -> "Cleartext to the robot was blocked"
            message.startsWith("HTTP ") -> message.take(120)
            else -> message.ifBlank { "Could not reach the robot" }.take(160)
        }
    }

    private fun kotlinx.coroutines.flow.MutableStateFlow<RobotUiState>.update(
        block: (RobotUiState) -> RobotUiState
    ) {
        while (true) {
            val previous = value
            val next = block(previous)
            if (compareAndSet(previous, next)) return
        }
    }

    private data class Found(
        val client: RobotClient,
        val network: Network?,
        val status: String
    )

    private companion object {
        const val TAG = "Spyder"
    }
}
