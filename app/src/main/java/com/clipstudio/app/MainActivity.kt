package com.clipstudio.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFF7B967))) {
                Studio()
            }
        }
    }

    @Composable
    private fun Studio() {
        val scope = rememberCoroutineScope()
        var link by remember { mutableStateOf("") }
        var layout by remember { mutableStateOf(Layout.Split) }
        var parts by remember { mutableStateOf(emptyList<SourcePart>()) }
        var selected by remember { mutableStateOf<SourcePart?>(null) }
        var importing by remember { mutableStateOf(false) }
        var exporting by remember { mutableStateOf(false) }
        var importStatus by remember { mutableStateOf("") }
        var exportStatus by remember { mutableStateOf("") }
        var saved by remember { mutableStateOf<Uri?>(null) }

        fun accept(part: SourcePart) {
            parts = parts + part
            if (selected == null) selected = part
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    importing = true
                    try {
                        val duration = StudioMedia.duration(this@MainActivity, uri)
                        parts = emptyList()
                        selected = null
                        accept(SourcePart(uri, 0L, duration))
                        importStatus = "Video ready"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        importStatus = failure.message ?: "Could not open the selected video."
                    } finally {
                        importing = false
                    }
                }
            }
        }

        Scaffold { inset ->
            Column(
                modifier = Modifier.padding(inset).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Clip Studio", style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(
                    value = link, onValueChange = { link = it }, singleLine = true,
                    enabled = !importing, label = { Text("YouTube link") }, modifier = Modifier.fillMaxWidth()
                )
                Text("Export layout", style = MaterialTheme.typography.titleMedium)
                listOf(listOf(Layout.Split, Layout.Fill), listOf(Layout.Fit, Layout.Landscape)).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            FilterChip(selected = layout == option, onClick = { layout = option },
                                enabled = !importing && !exporting, label = { Text(option.label) })
                        }
                    }
                }
                Button(
                    enabled = !importing && !exporting && YouTubeLinkClassifier.isYouTubeUrl(link),
                    onClick = {
                        val sourceLink = link.trim()
                        importing = true
                        parts = emptyList()
                        selected = null
                        importStatus = "Opening YouTube recording…"
                        scope.launch {
                            try {
                                val segmented = try {
                                    StudioMedia.downloadInParts(this@MainActivity, sourceLink) { part ->
                                        accept(part)
                                        importStatus = "Downloading · ${parts.size} sections ready"
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    if (parts.isNotEmpty()) throw failure
                                    false
                                }
                                if (!segmented) {
                                    importStatus = "Preparing full-video import…"
                                    val uri = YouTubeVideoImporter.download(this@MainActivity, sourceLink, 1080) { percent ->
                                        scope.launch {
                                            if (importing) importStatus = "Downloading full video · $percent%"
                                        }
                                    }
                                    accept(SourcePart(uri, 0L, StudioMedia.duration(this@MainActivity, uri)))
                                }
                                importStatus = "Import complete · ${parts.size} sections ready"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                importStatus = failure.message ?: "Import failed."
                            } finally {
                                importing = false
                            }
                        }
                    }
                ) { Text("Import & clip") }
                OutlinedButton(enabled = !importing && !exporting, onClick = { picker.launch(arrayOf("video/*")) }) {
                    Text("Choose a video")
                }
                if (importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (importStatus.isNotBlank()) Text(importStatus)
                if (parts.size > 1) {
                    Text("Ready sections", style = MaterialTheme.typography.titleMedium)
                    parts.forEach { part ->
                        FilterChip(
                            selected = part == selected, onClick = { selected = part }, enabled = !exporting,
                            label = { Text("${timestamp(part.offsetMs)} – ${timestamp(part.offsetMs + part.durationMs)}") }
                        )
                    }
                }
                selected?.let { part ->
                    key(part.uri) {
                        ClipControls(part, layout, exporting) { start, end, camera, game ->
                            exporting = true
                            exportStatus = "Rendering clip…"
                            saved = null
                            val exportLayout = layout
                            scope.launch {
                                try {
                                    saved = StudioMedia.export(this@MainActivity, part, start, end, exportLayout, camera, game)
                                    exportStatus = "Saved to Movies/Clip Studio"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    exportStatus = failure.message ?: "Export failed."
                                } finally {
                                    exporting = false
                                }
                            }
                        }
                    }
                }
                if (exportStatus.isNotBlank()) Text(exportStatus)
                saved?.let { uri ->
                    OutlinedButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).setType("video/mp4")
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(share, "Share clip"))
                    }) { Text("Share MP4") }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ClipControls(
    part: SourcePart, layout: Layout, exporting: Boolean,
    onExport: (Long, Long, Crop, Crop) -> Unit
) {
    var trim by remember { mutableStateOf(0f..minOf(30_000L, part.durationMs).toFloat()) }
    var camera by remember { mutableStateOf(Crop(0.05f, 0.85f, 3f)) }
    var game by remember { mutableStateOf(Crop(0.5f, 0.5f)) }
    AndroidView(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        factory = { context ->
            VideoView(context).apply {
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setVideoURI(part.uri)
                setOnPreparedListener { seekTo(1) }
            }
        },
        onRelease = { it.stopPlayback() }
    )
    Text("Trim · ${timestamp(part.offsetMs + trim.start.toLong())} – ${timestamp(part.offsetMs + trim.endInclusive.toLong())}")
    RangeSlider(
        value = trim, onValueChange = { trim = it }, enabled = !exporting,
        valueRange = 0f..part.durationMs.toFloat()
    )
    if (layout == Layout.Split) CropControls("Facecam crop", camera, !exporting) { camera = it }
    if (layout in listOf(Layout.Split, Layout.Fill)) CropControls("Gameplay crop", game, !exporting) { game = it }
    Button(
        enabled = !exporting && trim.endInclusive - trim.start >= 500,
        onClick = { onExport(trim.start.toLong(), trim.endInclusive.toLong().coerceAtMost(part.durationMs), camera, game) }
    ) { Text(if (exporting) "Rendering…" else "Export MP4") }
}

@Composable
private fun CropControls(title: String, crop: Crop, enabled: Boolean, onChange: (Crop) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text("Horizontal")
    Slider(crop.x, { onChange(crop.copy(x = it)) }, enabled = enabled)
    Text("Vertical")
    Slider(crop.y, { onChange(crop.copy(y = it)) }, enabled = enabled)
    Text("Zoom")
    Slider(crop.zoom, { onChange(crop.copy(zoom = it)) }, valueRange = 1f..4f, enabled = enabled)
}

private fun timestamp(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
