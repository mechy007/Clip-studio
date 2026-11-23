package com.clipstudio.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
internal fun FramingPreview(
    bitmap: Bitmap?, layout: Layout, camera: Crop, game: Crop,
    onCamera: (Crop) -> Unit, onGame: (Crop) -> Unit
) {
    val aspect = if (layout == Layout.Landscape) 16f / 9f else 9f / 16f
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
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (layout != Layout.Split && layout != Layout.Fill) return@detectTransformGestures
                            val upper = layout == Layout.Split && centroid.y < size.height / 2f
                            val crop = if (upper) activeCamera.value else activeGame.value
                            val height = if (layout == Layout.Split) size.height / 2f else size.height.toFloat()
                            val frameAspect = size.width / height
                            val before = cropWindow(image.width, image.height, frameAspect, crop)
                            val nextZoom = (crop.zoom * zoom).coerceIn(1f, 4f)
                            val after = cropWindow(image.width, image.height, frameAspect, crop.copy(zoom = nextZoom))
                            val left = before.x + before.width * (centroid.x / size.width) -
                                after.width * (centroid.x / size.width) - pan.x * before.width / size.width
                            val fractionY = (centroid.y - if (layout == Layout.Split && !upper) height else 0f) / height
                            val top = before.y + before.height * fractionY -
                                after.height * fractionY - pan.y * before.height / height
                            val next = Crop(
                                if (after.width == image.width) crop.x else (left / (image.width - after.width)).coerceIn(0f, 1f),
                                if (after.height == image.height) crop.y else (top / (image.height - after.height)).coerceIn(0f, 1f),
                                nextZoom
                            )
                            if (upper) cameraAction.value(next) else gameAction.value(next)
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
                    Layout.Fill -> panel(0, height, game)
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
