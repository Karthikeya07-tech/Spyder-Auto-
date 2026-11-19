package com.example.spyder

import com.example.spyder.robot.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameTest {
    @Test
    fun forwardIsUpAndLeftIsLeft() {
        val center = Frame.screen(Frame.WorldPoint(0.0, 0.0), 100f, 200f, 0f, 0f, 10f)
        assertEquals(100f, center.x, 0.01f)
        assertEquals(200f, center.y, 0.01f)

        val forward = Frame.screen(Frame.WorldPoint(1.0, 0.0), 100f, 200f, 0f, 0f, 10f)
        assertEquals(100f, forward.x, 0.01f)
        assertEquals(190f, forward.y, 0.01f)

        val left = Frame.screen(Frame.WorldPoint(0.0, 1.0), 100f, 200f, 0f, 0f, 10f)
        assertEquals(90f, left.x, 0.01f)
        assertEquals(200f, left.y, 0.01f)
    }

    @Test
    fun scanIsCounterClockwiseFromTheNose() {
        val ahead = Frame.scanHit(0.0, 0.0, yawDeg = 0.0, degree = 0, rangeMm = 1000)
        assertEquals(1.0, ahead!!.x, 1e-6)
        assertEquals(0.0, ahead.y, 1e-6)

        val left = Frame.scanHit(0.0, 0.0, yawDeg = 0.0, degree = 90, rangeMm = 1000)
        assertEquals(0.0, left!!.x, 1e-6)
        assertEquals(1.0, left.y, 1e-6)

        val facingLeft = Frame.scanHit(0.0, 0.0, yawDeg = 90.0, degree = 0, rangeMm = 2000)
        assertEquals(0.0, facingLeft!!.x, 1e-6)
        assertEquals(2.0, facingLeft.y, 1e-6)

        assertNull(Frame.scanHit(0.0, 0.0, 0.0, 0, 0))
    }

    @Test
    fun gridPutsForwardUpAndLeftToTheLeft() {
        val forward = Frame.cellPixel(col = 199, row = 0, size = 200)
        assertEquals(0, forward.second)
        val left = Frame.cellPixel(col = 0, row = 199, size = 200)
        assertEquals(0, left.first)
    }

    @Test
    fun occupancyRasterMatchesTheScreenAxes() {
        assertEquals(200, Frame.mapSide(ByteArray(40_000)))
        assertNull(Frame.mapSide(ByteArray(10)))
        assertEquals(0, Frame.occupancyArgb(0))
        assertNotEquals(0, Frame.occupancyArgb(40) ushr 24)

        val cells = byteArrayOf(
            0, 40,
            (-10).toByte(), 0
        )
        val pixels = Frame.rasterOccupancy(cells, 2)
        assertNotEquals(0, pixels[1])
        assertNotEquals(0, pixels[2])
        assertEquals(0, pixels[3])
    }
}
