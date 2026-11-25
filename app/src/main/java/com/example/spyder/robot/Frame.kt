package com.example.spyder.robot

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Robot frame: +X is forward, +Y is left, yaw is degrees counter-clockwise from +X.
 * On screen +X is right and +Y is up, matching a plot with forward on the horizontal axis.
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
            x = centerX + panX + (point.x * pixelsPerMetre).toFloat(),
            y = centerY + panY - (point.y * pixelsPerMetre).toFloat()
        )
    }

    fun mapSide(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        val side = sqrt(bytes.size.toDouble()).toInt()
        return if (side * side == bytes.size) side else null
    }

    /** Pixel in a bitmap whose +X points right and +Y points up. */
    fun cellPixel(col: Int, row: Int, size: Int): Pair<Int, Int> {
        val px = col
        val py = size - 1 - row
        return px to py
    }

    fun wallCount(cells: ByteArray): Int = cells.count { it.toInt() > 0 }

    fun occupancyArgb(logOdds: Int): Int {
        if (logOdds > 0) return opaque(0x14, 0x14, 0x14)
        if (logOdds < 0) return opaque(0xFF, 0xFF, 0xFF)
        return opaque(0xC8, 0xC8, 0xC8)
    }

    private fun opaque(red: Int, green: Int, blue: Int): Int {
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
