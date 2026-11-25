package com.example.spyder.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.spyder.robot.Frame
import com.example.spyder.ui.theme.ScanOrange
import java.util.Locale
import kotlin.math.min

private val MapPaper = Color(0xFFC8C8C8)
private val MapLine = Color(0xFF8E8E8E)
private val MapInk = Color(0xFF2A2A2A)
private val MapEdge = Color(0xFF9A9A9A)
private val StartRed = Color(0xFFE10600)
private val HeadingBlue = Color(0xFF2F6FE4)

@Composable
fun MapCanvas(
    poseX: Double,
    poseY: Double,
    yawDeg: Double,
    scanMm: IntArray,
    occupancy: ByteArray,
    mapSize: Int,
    mapStamp: Int,
    resolutionM: Double,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val wallCells = remember(mapStamp, occupancy) { Frame.wallCount(occupancy) }
    val caption = remember(measurer, wallCells, resolutionM) {
        val centimetres = resolutionM * 100.0
        val cell = if (centimetres in 0.5..100.0) {
            String.format(Locale.US, "%.0f", centimetres)
        } else {
            "5"
        }
        measurer.measure(
            "$wallCells wall cells, $cell cm grid",
            TextStyle(color = MapInk, fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
        )
    }
    val startMark = remember(measurer) {
        measurer.measure(
            "A",
            TextStyle(color = StartRed, fontSize = 12.sp, fontFamily = FontFamily.SansSerif)
        )
    }
    val pixels = remember(mapStamp, occupancy, mapSize) {
        if (mapSize > 0 && occupancy.size == mapSize * mapSize) {
            Frame.rasterOccupancy(occupancy, mapSize)
        } else {
            null
        }
    }
    val image = remember(pixels, mapSize) {
        if (pixels == null || mapSize <= 0) {
            null
        } else {
            Bitmap.createBitmap(mapSize, mapSize, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, mapSize, 0, 0, mapSize, mapSize)
                density = Bitmap.DENSITY_NONE
            }.asImageBitmap()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, MapEdge, RoundedCornerShape(18.dp))
            .background(MapPaper)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(0.35f, 12f)
                        pan += panChange
                    }
                }
        ) {
            val shortest = min(size.width, size.height)
            if (shortest <= 0f) return@Canvas
            val ppm = (shortest / 12f) * zoom
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            fun place(x: Double, y: Double): Offset {
                val point = Frame.screen(
                    Frame.WorldPoint(x, y),
                    centerX,
                    centerY,
                    pan.x,
                    pan.y,
                    ppm
                )
                return Offset(point.x, point.y)
            }

            drawRect(MapPaper)
            val grid = MapLine.copy(alpha = 0.55f)
            val hairline = 1.dp.toPx()
            for (metre in -12..12 step 2) {
                val at = metre.toDouble()
                drawLine(grid, place(at, -12.0), place(at, 12.0), strokeWidth = hairline)
                drawLine(grid, place(-12.0, at), place(12.0, at), strokeWidth = hairline)
            }
            drawText(caption, topLeft = Offset(12.dp.toPx(), 8.dp.toPx()))

            val bitmap = image
            if (bitmap != null) {
                val mapPx = (mapSize * resolutionM * ppm).toFloat()
                val origin = place(0.0, 0.0)
                val left = origin.x - mapPx / 2f
                val top = origin.y - mapPx / 2f
                translate(left, top) {
                    scale(mapPx / bitmap.width, mapPx / bitmap.height, pivot = Offset.Zero) {
                        drawImage(bitmap, filterQuality = FilterQuality.None)
                    }
                }
            }

            val dot = 2.2.dp.toPx()
            for (degree in scanMm.indices) {
                val hit = Frame.scanHit(poseX, poseY, yawDeg, degree, scanMm[degree]) ?: continue
                val at = place(hit.x, hit.y)
                drawCircle(ScanOrange, radius = dot, center = at)
            }

            val start = place(0.0, 0.0)
            val robot = place(poseX, poseY)
            drawCircle(StartRed, radius = 6.dp.toPx(), center = start)
            drawText(
                startMark,
                topLeft = Offset(start.x + 8.dp.toPx(), start.y - startMark.size.height - 2.dp.toPx())
            )

            val length = 16.dp.toPx()
            rotate(degrees = -yawDeg.toFloat(), pivot = robot) {
                val arrow = Path().apply {
                    moveTo(robot.x + length, robot.y)
                    lineTo(robot.x - length * 0.55f, robot.y - length * 0.42f)
                    lineTo(robot.x - length * 0.2f, robot.y)
                    lineTo(robot.x - length * 0.55f, robot.y + length * 0.42f)
                    close()
                }
                drawPath(arrow, color = Color.White, style = Stroke(width = 4f))
                drawPath(arrow, color = HeadingBlue)
            }
        }
        Text(
            text = "Recenter",
            color = MapInk,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clickable {
                    zoom = 1f
                    pan = Offset.Zero
                }
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}
