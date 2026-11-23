package com.clipstudio.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

@Composable
internal fun FramingPreview(
    bitmap: Bitmap?, layout: Layout, camera: Crop, game: Crop,
    onCamera: (Crop) -> Unit, onGame: (Crop) -> Unit
) {
    val (width, height) = layout.dimensions(720)
    val aspect = width.toFloat() / height
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val activeCamera = rememberUpdatedState(camera)
    val activeGame = rememberUpdatedState(game)
    val cameraAction = rememberUpdatedState(onCamera)
    val gameAction = rememberUpdatedState(onGame)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (image == null) {
            Text("Loading preview frame…", modifier = Modifier.heightIn(min = 90.dp))
        } else {
            Canvas(
                Modifier.heightIn(max = 380.dp).aspectRatio(aspect, matchHeightConstraintsFirst = layout != Layout.Landscape)
                    .background(Color(0xFF0D1015)).pointerInput(layout, image.width, image.height) {
                        if (layout != Layout.Split && layout != Layout.Fill && layout != Layout.Square) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val upper = layout == Layout.Split && down.position.y < size.height / 2f
                            val height = if (layout == Layout.Split) size.height / 2f else size.height.toFloat()
                            val frameAspect = size.width / height
                            var accumulatedPan = Offset.Zero
                            var accumulatedZoom = 1f
                            var moved = false
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.isConsumed }) break
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                var gesturePan = pan
                                var gestureZoom = zoom
                                if (!moved) {
                                    accumulatedPan += pan
                                    accumulatedZoom *= zoom
                                    val zoomPixels = abs(1f - accumulatedZoom) * event.calculateCentroidSize(false)
                                    moved = accumulatedPan.getDistance() > viewConfiguration.touchSlop ||
                                        zoomPixels > viewConfiguration.touchSlop
                                    gesturePan = accumulatedPan
                                    gestureZoom = accumulatedZoom
                                }
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (moved && centroid.isSpecifiedPoint()) {
                                    val crop = if (upper) activeCamera.value else activeGame.value
                                    val before = cropWindow(image.width, image.height, frameAspect, crop)
                                    val nextZoom = (crop.zoom * gestureZoom).coerceIn(1f, 4f)
                                    val after = cropWindow(image.width, image.height, frameAspect, crop.copy(zoom = nextZoom))
                                    val fractionX = centroid.x / size.width
                                    val fractionY = (centroid.y - if (layout == Layout.Split && !upper) height else 0f) / height
                                    val left = before.x + (before.width - after.width) * fractionX -
                                        gesturePan.x * before.width / size.width
                                    val top = before.y + (before.height - after.height) * fractionY -
                                        gesturePan.y * before.height / height
                                    val next = Crop(
                                        if (after.width == image.width) crop.x else (left / (image.width - after.width)).coerceIn(0f, 1f),
                                        if (after.height == image.height) crop.y else (top / (image.height - after.height)).coerceIn(0f, 1f),
                                        nextZoom
                                    )
                                    if (upper) cameraAction.value(next) else gameAction.value(next)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            ) {
                val width = size.width.toInt().coerceAtLeast(1)
                val height = size.height.toInt().coerceAtLeast(1)
                fun panel(y: Int, panelHeight: Int, crop: Crop) {
                    if (panelHeight < 1) return
                    val window = cropWindow(image.width, image.height, width.toFloat() / panelHeight, crop)
                    drawImage(image, IntOffset(window.x, window.y), IntSize(window.width, window.height),
                        IntOffset(0, y), IntSize(width, panelHeight))
                }
                when (layout) {
                    Layout.Split -> {
                        panel(0, height / 2, camera)
                        panel(height / 2, height - height / 2, game)
                    }
                    Layout.Fill, Layout.Square -> panel(0, height, game)
                    Layout.Fit, Layout.Landscape -> {
                        val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
                        val content = IntSize((image.width * scale).toInt(), (image.height * scale).toInt())
                        drawImage(image, IntOffset.Zero, IntSize(image.width, image.height),
                            IntOffset((width - content.width) / 2, (height - content.height) / 2), content)
                    }
                }
            }
        }
    }
}

private fun Offset.isSpecifiedPoint() = this != Offset.Unspecified && x.isFinite() && y.isFinite()
