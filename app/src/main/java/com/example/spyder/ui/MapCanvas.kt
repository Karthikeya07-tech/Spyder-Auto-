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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.spyder.ui.theme.NoseTeal
import com.example.spyder.ui.theme.ScanOrange
import com.example.spyder.ui.theme.SpyderBlack
import com.example.spyder.ui.theme.SpyderMaroon
import com.example.spyder.ui.theme.SpyderWhite
import kotlin.math.min

@Composable
fun MapCanvas(
    poseX: Double,
    poseY: Double,
    yawDeg: Double,
    scanMm: IntArray,
    occupancy: ByteArray,
    mapSize: Int,
    resolutionM: Double,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val metreLabel = remember(measurer) {
        measurer.measure(
            "1 m",
            TextStyle(
                color = SpyderWhite.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        )
    }
    val pixels = remember(occupancy, mapSize) {
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
            .border(1.dp, SpyderMaroon, RoundedCornerShape(18.dp))
            .background(SpyderBlack)
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
            val ppm = (shortest / 8f) * zoom
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

            val grid = SpyderWhite.copy(alpha = 0.14f)
            val hairline = 1.dp.toPx()
            for (metre in -8..8) {
                drawLine(grid, place(metre.toDouble(), -8.0), place(metre.toDouble(), 8.0), strokeWidth = hairline)
                drawLine(grid, place(-8.0, metre.toDouble()), place(8.0, metre.toDouble()), strokeWidth = hairline)
            }

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

            val robot = place(poseX, poseY)
            drawCircle(
                color = SpyderWhite.copy(alpha = 0.28f),
                radius = ppm,
                center = robot,
                style = Stroke(width = 1.5f)
            )
            drawCircle(
                color = SpyderWhite.copy(alpha = 0.16f),
                radius = ppm * 2f,
                center = robot,
                style = Stroke(width = 1.5f)
            )
            val oneMetre = place(poseX + 1.0, poseY)
            drawText(
                metreLabel,
                topLeft = Offset(oneMetre.x + 6f, oneMetre.y - metreLabel.size.height / 2f)
            )

            val dot = 3.dp.toPx()
            for (degree in scanMm.indices) {
                val hit = Frame.scanHit(poseX, poseY, yawDeg, degree, scanMm[degree]) ?: continue
                val at = place(hit.x, hit.y)
                drawCircle(ScanOrange, radius = dot, center = at)
            }

            val length = 22.dp.toPx()
            rotate(degrees = -yawDeg.toFloat(), pivot = robot) {
                val arrow = Path().apply {
                    moveTo(robot.x, robot.y - length)
                    lineTo(robot.x + length * 0.38f, robot.y + length * 0.45f)
                    lineTo(robot.x, robot.y + length * 0.12f)
                    lineTo(robot.x - length * 0.38f, robot.y + length * 0.45f)
                    close()
                }
                drawPath(arrow, color = SpyderBlack, style = Stroke(width = 8f))
                drawPath(arrow, color = NoseTeal)
            }
        }
        TextButton(
            onClick = {
                zoom = 1f
                pan = Offset.Zero
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
        ) {
            Text("Recenter", color = SpyderWhite, fontSize = 12.sp)
        }
    }
}
