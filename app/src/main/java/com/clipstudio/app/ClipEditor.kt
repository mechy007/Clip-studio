package com.clipstudio.app

import android.graphics.Bitmap
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val CameraDefault = Crop(.05f, .85f, 3f)
private val GameplayDefault = Crop(.5f, .5f)
private val CropSaver = listSaver<Crop, Float>(
    save = { listOf(it.x, it.y, it.zoom) }, restore = { Crop(it[0], it[1], it[2]) }
)
private val Languages = listOf("Auto" to null, "English" to "en", "Malayalam" to "ml",
    "Hindi" to "hi", "Tamil" to "ta", "Spanish" to "es")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClipEditor(review: PartReview, layout: Layout, state: StudioState, notify: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val part = review.part
    var start by rememberSaveable { mutableStateOf(0L) }
    var end by rememberSaveable { mutableStateOf(minOf(30_000L, part.durationMs)) }
    var camera by rememberSaveable(stateSaver = CropSaver) { mutableStateOf(CameraDefault) }
    var gameplay by rememberSaveable(stateSaver = CropSaver) { mutableStateOf(GameplayDefault) }
    var width by rememberSaveable { mutableStateOf(1080) }
    var mute by rememberSaveable { mutableStateOf(false) }
    var preset by rememberSaveable { mutableStateOf(CaptionPreset.KARAOKE) }
    var captionEnabled by rememberSaveable { mutableStateOf(true) }
    var editCaptions by rememberSaveable { mutableStateOf(false) }
    var languageIndex by rememberSaveable { mutableStateOf(0) }
    var options by rememberSaveable { mutableStateOf(false) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var faceStatus by remember { mutableStateOf("") }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var frameAt by rememberSaveable { mutableStateOf(0L) }
    val busy = Work.EXPORT in state.jobs
    val transcript = state.transcripts[part.uri].orEmpty()
    val activeCues = remember(transcript, start, end, captionEnabled) {
        if (captionEnabled && end > start) CaptionTrack.fromTranscript(transcript, start, end, 36) else emptyList()
    }
    LaunchedEffect(part.uri, frameAt) {
        frame = try {
            StudioFraming.frame(context, part.uri, frameAt.coerceIn(0, part.durationMs - 1))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val highlights = review.highlights
    if (!highlights.isNullOrEmpty()) {
        Text("Suggested highlights", style = MaterialTheme.typography.titleLarge)
        highlights.take(5).forEach { highlight ->
            Card(onClick = {
                start = highlight.startMs
                end = highlight.endMs
                frameAt = start
                playing = false
            }, enabled = !busy) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${timestamp(part.offsetMs + highlight.startMs)} – ${timestamp(part.offsetMs + highlight.endMs)}",
                        style = MaterialTheme.typography.titleSmall)
                    Text(highlight.reason, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    } else if (highlights != null) {
        Text("No strong highlight signals in this section. You can trim it manually.")
    }
    Text("Framing", style = MaterialTheme.typography.titleLarge)
    if (playing) {
        key(part.uri, start) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                factory = { playerContext ->
                    VideoView(playerContext).apply {
                        setMediaController(MediaController(playerContext).also { it.setAnchorView(this) })
                        setVideoURI(part.uri)
                        setOnPreparedListener { seekTo(start.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()); this.start() }
                    }
                },
                onRelease = { it.stopPlayback() }
            )
        }
    } else {
        FramingPreview(frame, layout, camera, gameplay, { camera = it }, { gameplay = it })
        if (layout in listOf(Layout.Split, Layout.Fill, Layout.Square)) Text("Drag to reposition · pinch to zoom",
            style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { playing = !playing }) { Text(if (playing) "Frame preview" else "Play source") }
        if (layout == Layout.Split) {
            OutlinedButton(enabled = faceStatus != "Finding facecam…" && end > start && !busy, onClick = {
                faceStatus = "Finding facecam…"
                scope.launch {
                    try {
                        val suggested = StudioFraming.suggestFacecam(context, part, start, end)
                        if (suggested != null) camera = suggested
                        faceStatus = if (suggested == null) "No consistent facecam found" else "Facecam framing updated"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        faceStatus = "Facecam detection unavailable for this section"
                    }
                }
            }) { Text("Find facecam") }
        }
    }
    if (layout == Layout.Split && faceStatus.isNotBlank()) Text(faceStatus)
    if (layout in listOf(Layout.Split, Layout.Fill, Layout.Square)) {
        TextButton(onClick = { camera = CameraDefault; gameplay = GameplayDefault }) { Text("Reset framing") }
    }
    Text("Trim · ${timestamp(part.offsetMs + start)} – ${timestamp(part.offsetMs + end)}")
    RangeSlider(
        value = start.toFloat()..end.toFloat(), onValueChange = {
            start = it.start.toLong()
            end = it.endInclusive.toLong().coerceAtMost(part.durationMs)
        },
        onValueChangeFinished = { frameAt = start }, enabled = !busy,
        valueRange = 0f..part.durationMs.toFloat()
    )
    val canExport = !busy && end - start >= 500L
    val canCaption = canExport && end - start <= StudioAnalysis.MAX_CAPTION_DURATION_MS && Work.CAPTIONS !in state.jobs
    Text("Captions", style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = canCaption, onClick = {
            StudioSession.captions(context, part, start, end, Languages[languageIndex].second)
            notify()
        }) { Text("Generate captions") }
        TextButton(enabled = Work.CAPTIONS !in state.jobs,
            onClick = { languageIndex = (languageIndex + 1) % Languages.size }) { Text(Languages[languageIndex].first) }
    }
    if (end - start > StudioAnalysis.MAX_CAPTION_DURATION_MS) Text("Choose up to 2 minutes for automatic captions.")
    if (transcript.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = captionEnabled, onCheckedChange = { captionEnabled = it })
            Text("Burn into video")
            TextButton(onClick = { editCaptions = !editCaptions }) { Text(if (editCaptions) "Done" else "Edit text") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(CaptionPreset.CLEAN, CaptionPreset.KARAOKE, CaptionPreset.POP).forEach { item ->
                FilterChip(selected = item == preset, onClick = { preset = item }, label = { Text(item.label) })
            }
        }
        if (editCaptions) transcript.forEachIndexed { index, row ->
            OutlinedTextField(
                value = row.text, onValueChange = { StudioSession.editTranscript(part.uri, index, it) },
                label = { Text(timestamp(part.offsetMs + row.startMs)) }, modifier = Modifier.fillMaxWidth()
            )
        }
    }
    TextButton(onClick = { options = !options }) { Text(if (options) "Hide export settings" else "Export settings · ${width}p") }
    if (options) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(720, 1080).forEach {
                FilterChip(selected = width == it, onClick = { width = it }, label = { Text("${it}p") })
            }
            FilterChip(selected = mute, onClick = { mute = !mute }, label = { Text("Mute") })
        }
    }
    fun clip() = ExportClip(part, start, end, layout, camera, gameplay, width, mute, activeCues, preset)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = canExport, onClick = {
            StudioSession.export(context, listOf(clip()))
            notify()
        }) { Text("Export MP4") }
        OutlinedButton(enabled = canExport && state.queue.size < 10, onClick = { StudioSession.queue(clip()) }) {
            Text("Add to queue")
        }
    }
}
