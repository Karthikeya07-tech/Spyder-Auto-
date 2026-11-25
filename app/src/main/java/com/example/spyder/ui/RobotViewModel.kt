package com.example.spyder.ui

import android.app.Application
import android.net.Network
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spyder.robot.ActionSnapshot
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
    private var actionJob: Job? = null
    private var seenMapVersion = Long.MIN_VALUE
    private var mapFollowUp = false

    @Volatile
    private var joining = false

    fun lookForRobot() {
        viewModelScope.launch {
            if (joining) return@launch
            _state.update { it.copy(ssid = wifi.ssid()) }
            if (isLinked()) return@launch
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
                if (joining || isLinked()) {
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
        "Front set. The blue arrow points forward."
    )

    fun zero() = command(RobotEndpoint.ZERO, "zero", "Arrow zeroed")

    fun resetMap() = command(RobotEndpoint.RESET, "reset", "Map cleared") {
        _state.update {
            it.copy(occupancy = ByteArray(0), mapSize = 0, mapVersion = -1, mapStamp = it.mapStamp + 1)
        }
    }

    fun startStream() {
        val bound = client
        if (bound == null) {
            _state.update { it.copy(detail = "Connect to Twist-auto first") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, detail = "Starting scan") }
            val result = withContext(Dispatchers.IO) {
                runCatching { postScan(bound, RobotEndpoint.SCAN_START, RobotEndpoint.STREAM_START) }
            }
            if (result.isFailure) {
                val failure = result.exceptionOrNull()
                _state.update {
                    it.copy(
                        busy = false,
                        streaming = false,
                        detail = failure?.let(::friendly) ?: "Could not start the scan"
                    )
                }
                return@launch
            }
            seenMapVersion = Long.MIN_VALUE
            _state.update {
                it.copy(
                    busy = false,
                    streaming = true,
                    detail = "Scan started. The motor is spinning."
                )
            }
            startLive(bound)
        }
    }

    fun play(name: String) {
        val bound = client
        if (bound == null) {
            _state.update { it.copy(detail = "Connect to Twist-auto first") }
            return
        }
        val label = motionLabel(name)
        viewModelScope.launch {
            _state.update { it.copy(busy = true, detail = "Queuing $label") }
            val result = withContext(Dispatchers.IO) {
                runCatching { bound.post(RobotEndpoint.action(name)) }.recoverCatching { error ->
                    if (isQueueFull(error) || !bound.send("action $name")) throw error
                    "queued"
                }
            }
            val failure = result.exceptionOrNull()
            _state.update { current ->
                current.copy(
                    busy = false,
                    detail = when {
                        failure == null -> "$label queued"
                        isQueueFull(failure) -> "Queue is full. One move is playing and two are waiting."
                        else -> friendly(failure)
                    }
                )
            }
            if (failure == null || isQueueFull(failure)) {
                withContext(Dispatchers.IO) { refreshAction(bound) }
            }
        }
    }

    fun stopStream() {
        val bound = client
        if (bound == null) {
            _state.update { it.copy(detail = "Connect to Twist-auto first") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, detail = "Stopping scan") }
            stopLive()
            val result = withContext(Dispatchers.IO) {
                runCatching { postScan(bound, RobotEndpoint.SCAN_STOP, RobotEndpoint.STREAM_STOP) }
            }
            val failure = result.exceptionOrNull()
            _state.update {
                it.copy(
                    busy = false,
                    streaming = false,
                    usingSocket = false,
                    detail = if (failure == null) "Scan stopped. Motor is off." else friendly(failure)
                )
            }
        }
    }

    override fun onCleared() {
        liveJob?.cancel()
        mapJob?.cancel()
        actionJob?.cancel()
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
        actionJob?.cancel()
        client?.close()
        client = found.client
        activeNetwork = found.network
        val parsed = RobotJson.status(found.status)
        Log.i(TAG, "Connected to ${RobotEndpoint.HOST}")
        _state.update {
            it.copy(
                link = LinkState.Live,
                statusLine = "Connected",
                detail = "LiDAR found. Motor is off until you start the scan.",
                ssid = wifi.ssid() ?: RobotEndpoint.SSID,
                lidar = parsed.lidar,
                imu = parsed.imu,
                streaming = false,
                yawDeg = parsed.yawDeg ?: it.yawDeg,
                versions = parsed.versions.ifBlank { it.versions },
                resolutionM = parsed.resolutionM ?: it.resolutionM
            )
        }
        viewModelScope.launch(Dispatchers.IO) { mergeCatalog(found.client) }
        startActionPoll(found.client)
    }

    private suspend fun mergeCatalog(bound: RobotClient) {
        val body = runCatching { bound.get(RobotEndpoint.CATALOG) }.getOrNull() ?: return
        val extra = RobotJson.status(body)
        _state.update { current ->
            current.copy(
                lidar = extra.lidar ?: current.lidar,
                imu = extra.imu ?: current.imu,
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
        frame.mapVersion?.let { noteMapVersion(bound, it) }
    }

    private fun noteMapVersion(bound: RobotClient, version: Long) {
        if (version == seenMapVersion) return
        seenMapVersion = version
        if (mapJob?.isActive == true) {
            mapFollowUp = true
            return
        }
        pullMap(bound)
    }

    private fun pullMap(bound: RobotClient) {
        mapJob = viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching { bound.getBytes(RobotEndpoint.MAP) }.getOrElse { error ->
                Log.i(TAG, "Map fetch failed: ${error.message}")
                seenMapVersion = Long.MIN_VALUE
                null
            }
            val side = bytes?.let(Frame::mapSide)
            if (bytes != null && side != null && client === bound && !bytes.contentEquals(_state.value.occupancy)) {
                _state.update {
                    it.copy(occupancy = bytes, mapSize = side, mapStamp = it.mapStamp + 1)
                }
            }
            if (mapFollowUp && isActive && client === bound) {
                mapFollowUp = false
                pullMap(bound)
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        mapJob?.cancel()
        mapJob = null
        mapFollowUp = false
    }

    private fun postScan(bound: RobotClient, path: String, samePath: String): String {
        return try {
            bound.post(path)
        } catch (error: Exception) {
            Log.i(TAG, "$path failed: ${error.message}")
            bound.post(samePath)
        }
    }

    private fun startActionPoll(bound: RobotClient) {
        actionJob?.cancel()
        actionJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && client === bound) {
                refreshAction(bound)
                delay(800)
            }
        }
    }

    private fun refreshAction(bound: RobotClient) {
        val body = runCatching { bound.get(RobotEndpoint.ACTION) }.getOrNull() ?: return
        val snap = RobotJson.action(body) ?: return
        if (client !== bound) return
        _state.update { current -> current.withAction(snap) }
    }

    private fun refreshStatus(bound: RobotClient) {
        viewModelScope.launch(Dispatchers.IO) {
            val body = runCatching { bound.get(RobotEndpoint.STATUS) }.getOrNull() ?: return@launch
            val parsed = RobotJson.status(body)
            val motion = RobotJson.action(body)
            _state.update { current ->
                val withMotion = if (motion == null) current else current.withAction(motion)
                withMotion.copy(
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
        stopLive()
        actionJob?.cancel()
        actionJob = null
        client?.close()
        client = null
        activeNetwork = null
        _state.update {
            it.copy(link = LinkState.Offline, statusLine = "Wi-Fi dropped", detail = message)
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

    private fun isQueueFull(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.message?.contains("HTTP 409") == true) return true
            current = current.cause
        }
        return false
    }

    private fun RobotUiState.withAction(snap: ActionSnapshot): RobotUiState {
        return copy(
            actionBusy = snap.busy ?: actionBusy,
            actionCurrent = if (snap.sawCurrent) snap.current else actionCurrent,
            actionQueued = if (snap.sawQueued) snap.queued else actionQueued
        )
    }

    private fun isLinked(): Boolean {
        return _state.value.link == LinkState.Live && client != null
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
