package com.example.spyder.robot

import org.json.JSONArray
import org.json.JSONObject

data class StatusSnapshot(
    val lidar: Boolean?,
    val imu: Boolean?,
    val streaming: Boolean?,
    val yawDeg: Double?,
    val versions: String,
    val resolutionM: Double?
) {
    companion object {
        val Empty = StatusSnapshot(null, null, null, null, "", null)
    }
}

data class ActionSnapshot(
    val busy: Boolean?,
    val current: String?,
    val queued: List<String>,
    val sawCurrent: Boolean,
    val sawQueued: Boolean
)

data class LiveFrame(
    val x: Double?,
    val y: Double?,
    val yawDeg: Double?,
    val scanMm: IntArray?,
    val mapVersion: Long?,
    val streaming: Boolean?
)

object RobotJson {
    fun isJsonObject(body: String): Boolean = body.trimStart().startsWith("{")

    fun status(body: String): StatusSnapshot {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return StatusSnapshot.Empty
        val source = root.optJSONObject("status") ?: root
        val versions = versionsText(source.opt("versions"))
            .ifBlank { versionsText(source.opt("version")) }
            .ifBlank { versionsText(root.opt("versions")) }
        return StatusSnapshot(
            lidar = flag(source, "lidar", "lidar_ok", "has_lidar", "lidar_up"),
            imu = flag(source, "imu", "imu_ok", "has_imu", "imu_up"),
            streaming = flag(source, "stream", "streaming", "lidar_stream", "mapping"),
            yawDeg = yaw(source),
            versions = versions,
            resolutionM = resolution(source) ?: root.optJSONObject("map")?.let { resolution(it) }
        )
    }

    fun live(body: String): LiveFrame? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val hasPose = root.has("pose") || root.has("yaw_deg") || root.has("yaw") || root.has("x")
        val hasScan = root.has("scan_mm") || root.has("scan")
        val hasMap = root.has("map_version")
        val hasStream = root.has("stream") || root.has("streaming")
        if (!hasPose && !hasScan && !hasMap && !hasStream) return null

        var x: Double? = null
        var y: Double? = null
        var yaw: Double? = null
        when (val pose = if (root.has("pose")) root.opt("pose") else null) {
            is JSONObject -> {
                x = number(pose, "x")
                y = number(pose, "y")
                yaw = yaw(pose)
            }
            is JSONArray -> {
                x = pose.optionalDouble(0)
                y = pose.optionalDouble(1)
                yaw = pose.optionalDouble(2)
            }
        }
        if (x == null) x = number(root, "x")
        if (y == null) y = number(root, "y")
        if (yaw == null) yaw = yaw(root)

        val scan = when {
            root.has("scan_mm") -> ints(root.opt("scan_mm"))
            root.has("scan") -> ints(root.opt("scan"))
            else -> null
        }
        val version = if (root.has("map_version") && !root.isNull("map_version")) {
            root.optLong("map_version")
        } else {
            null
        }
        return LiveFrame(
            x = x,
            y = y,
            yawDeg = yaw,
            scanMm = scan,
            mapVersion = version,
            streaming = flag(root, "stream", "streaming")
        )
    }

    fun action(body: String): ActionSnapshot? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val sawCurrent = root.has("current") || root.has("action")
        val sawQueued = root.has("queued") || root.has("action_queued")
        return ActionSnapshot(
            busy = flag(root, "busy", "action_busy"),
            current = actionName(root, "current", "action"),
            queued = actionList(root, "queued", "action_queued") ?: emptyList(),
            sawCurrent = sawCurrent,
            sawQueued = sawQueued
        )
    }

    private fun yaw(source: JSONObject): Double? {
        number(source, "yaw_deg")?.let { return it }
        number(source, "yaw")?.let { return it }
        return null
    }

    private fun number(source: JSONObject, key: String): Double? {
        if (!source.has(key) || source.isNull(key)) return null
        val value = source.optDouble(key, Double.NaN)
        return if (value.isNaN()) null else value
    }

    private fun resolution(source: JSONObject): Double? {
        val keys = arrayOf(
            "resolution",
            "resolution_m",
            "cell_m",
            "cell_size",
            "cell_size_m",
            "metres_per_cell",
            "meters_per_cell"
        )
        for (key in keys) {
            val value = number(source, key) ?: continue
            if (value in 0.005..1.0) return value
        }
        val nested = source.optJSONObject("map") ?: return null
        if (nested === source) return null
        for (key in keys) {
            val value = number(nested, key) ?: continue
            if (value in 0.005..1.0) return value
        }
        return null
    }

    private fun flag(source: JSONObject, vararg keys: String): Boolean? {
        for (key in keys) {
            if (!source.has(key) || source.isNull(key)) continue
            asFlag(source.get(key))?.let { return it }
        }
        return null
    }

    private fun asFlag(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.lowercase()) {
            "1", "true", "yes", "on", "up", "ok", "running" -> true
            "0", "false", "no", "off", "down" -> false
            else -> null
        }
        is JSONObject -> when {
            value.has("ok") -> asFlag(value.get("ok"))
            value.has("up") -> asFlag(value.get("up"))
            value.has("running") -> asFlag(value.get("running"))
            value.has("enabled") -> asFlag(value.get("enabled"))
            else -> null
        }
        else -> null
    }

    private fun versionsText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is Number -> value.toString()
        is JSONObject -> {
            val parts = ArrayList<String>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (value.isNull(key)) continue
                parts += "$key ${value.opt(key)}"
            }
            parts.joinToString(" · ")
        }
        else -> ""
    }

    private fun ints(value: Any?): IntArray? = when (value) {
        is JSONArray -> IntArray(value.length()) { index ->
            when (val item = value.opt(index)) {
                is Number -> item.toInt()
                is String -> item.toDoubleOrNull()?.toInt() ?: 0
                else -> 0
            }
        }
        is String -> {
            val parts = value.split(',')
            IntArray(parts.size) { index -> parts[index].trim().toDoubleOrNull()?.toInt() ?: 0 }
        }
        else -> null
    }

    private fun actionName(source: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!source.has(key) || source.isNull(key)) continue
            nameOf(source.get(key))?.let { return it }
        }
        return null
    }

    private fun actionList(source: JSONObject, vararg keys: String): List<String>? {
        for (key in keys) {
            if (!source.has(key) || source.isNull(key)) continue
            return when (val value = source.get(key)) {
                is JSONArray -> buildList {
                    for (index in 0 until value.length()) {
                        nameOf(value.opt(index))?.let { add(it) }
                    }
                }
                is String -> listOfNotNull(nameOf(value))
                is JSONObject -> listOfNotNull(nameOf(value))
                else -> emptyList()
            }
        }
        return null
    }

    private fun nameOf(value: Any?): String? = when (value) {
        is String -> value.trim().ifBlank { null }
        is JSONObject -> actionName(value, "name", "action")
        else -> null
    }

    private fun JSONArray.optionalDouble(index: Int): Double? {
        if (index >= length()) return null
        return when (val item = opt(index)) {
            is Number -> item.toDouble()
            is String -> item.toDoubleOrNull()
            else -> null
        }
    }
}
