package com.example.spyder.robot

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Robot frame: +X is forward, +Y is left, yaw is degrees counter-clockwise from +X.
 * On screen +X is up and +Y is left, so a calibrated nose points up.
 * scan index i is i degrees counter-clockwise from the nose. 0 mm is no hit.
 * The occupancy grid is row-major, column along +X and row along +Y, centred on (0, 0).
 */
object Frame {
    data class WorldPoint(val x: Double, val y: Double)
    data class ScreenPoint(val x: Float, val y: Float)

    fun scanHit(
        poseX: Double,
        poseY: Double,
        yawDeg: Double,
        degree: Int,
        rangeMm: Int
    ): WorldPoint? {
        if (rangeMm <= 0 || rangeMm > 60_000) return null
        val radians = (yawDeg + degree) * PI / 180.0
        val metres = rangeMm / 1000.0
        return WorldPoint(
            x = poseX + metres * cos(radians),
            y = poseY + metres * sin(radians)
        )
    }

    fun screen(
        point: WorldPoint,
        centerX: Float,
        centerY: Float,
        panX: Float,
        panY: Float,
        pixelsPerMetre: Float
    ): ScreenPoint {
        return ScreenPoint(
            x = centerX + panX - (point.y * pixelsPerMetre).toFloat(),
            y = centerY + panY - (point.x * pixelsPerMetre).toFloat()
        )
    }

    fun mapSide(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        val side = sqrt(bytes.size.toDouble()).toInt()
        return if (side * side == bytes.size) side else null
    }

    /** Pixel in a bitmap whose +X points up and +Y points left. */
    fun cellPixel(col: Int, row: Int, size: Int): Pair<Int, Int> {
        val px = size - 1 - row
        val py = size - 1 - col
        return px to py
    }

    fun occupancyArgb(logOdds: Int): Int {
        if (logOdds == 0) return 0
        if (logOdds > 0) {
            val strength = logOdds.coerceIn(1, 80) / 80f
            val alpha = (170 + 85f * strength).toInt().coerceIn(0, 255)
            return (alpha shl 24) or 0x00FFFFFF
        }
        val strength = (-logOdds).coerceIn(1, 80) / 80f
        val red = (36 + 28f * strength).toInt()
        val green = (10 + 8f * strength).toInt()
        val blue = (16 + 10f * strength).toInt()
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    fun rasterOccupancy(cells: ByteArray, size: Int): IntArray {
        val pixels = IntArray(size * size)
        for (row in 0 until size) {
            val rowStart = row * size
            for (col in 0 until size) {
                val (px, py) = cellPixel(col, row, size)
                pixels[py * size + px] = occupancyArgb(cells[rowStart + col].toInt())
            }
        }
        return pixels
    }
}
