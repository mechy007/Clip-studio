package com.clipstudio.app

import android.graphics.Bitmap
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
internal fun ClipEditor(
    part: SourcePart, layout: Layout, state: StudioState, suggestedStart: Long, suggestedEnd: Long,
    momentSelection: Int, notify: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val suggested = suggestedStart in 0 until suggestedEnd && suggestedEnd <= part.durationMs
    var start by rememberSaveable { mutableStateOf(if (suggested) suggestedStart else 0L) }
    var end by rememberSaveable { mutableStateOf(if (suggested) suggestedEnd else minOf(30_000L, part.durationMs)) }
    var camera by rememberSaveable(stateSaver = CropSaver) { mutableStateOf(CameraDefault) }
    var gameplay by rememberSaveable(stateSaver = CropSaver) { mutableStateOf(GameplayDefault) }
    var width by rememberSaveable { mutableStateOf(1080) }
    var mute by rememberSaveable { mutableStateOf(false) }
    var lockCamera by rememberSaveable { mutableStateOf(false) }
    var lockGame by rememberSaveable { mutableStateOf(false) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var normalize by rememberSaveable { mutableStateOf(false) }
    var fades by rememberSaveable { mutableStateOf(false) }
    var mix by rememberSaveable { mutableStateOf(false) }
    var voiceLevel by rememberSaveable { mutableStateOf(1f) }
    var gameLevel by rememberSaveable { mutableStateOf(.6f) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var savingPreset by rememberSaveable { mutableStateOf(false) }
    var showingPresets by rememberSaveable { mutableStateOf(false) }
    var waveform by remember { mutableStateOf<List<Float>?>(null) }
    var audioTracks by remember { mutableStateOf(0) }
    var fineFraming by rememberSaveable { mutableStateOf(false) }
    var preset by rememberSaveable { mutableStateOf(CaptionPreset.KARAOKE) }
    var captionEnabled by rememberSaveable { mutableStateOf(true) }
    var editCaptions by rememberSaveable { mutableStateOf(false) }
    var languageIndex by rememberSaveable { mutableStateOf(0) }
    var options by rememberSaveable { mutableStateOf(false) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var faceStatus by remember { mutableStateOf("") }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var frameAt by rememberSaveable { mutableStateOf(start) }
    var appliedSelection by rememberSaveable { mutableStateOf(momentSelection) }
    LaunchedEffect(momentSelection) {
        if (suggested && appliedSelection != momentSelection) {
            start = suggestedStart
            end = suggestedEnd
            frameAt = suggestedStart
            playing = false
        }
        appliedSelection = momentSelection
    }
    val busy = Work.EXPORT in state.jobs
    var facePath by remember(start, end, camera, layout) { mutableStateOf(emptyList<FacePoint>()) }
    LaunchedEffect(part.uri) {
        audioTracks = withContext(Dispatchers.IO) { StudioMedia.audioTrackCount(context, part.uri) }
        waveform = try {
            StudioMedia.waveform(context, part)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }
    val transcript = state.transcripts[part.uri].orEmpty()
    val activeCues = remember(transcript, start, end) {
        if (end > start) CaptionTrack.fromTranscript(transcript, start, end, 36) else emptyList()
    }
    fun audioOptions() = AudioFinish(normalize, fades, mix && audioTracks >= 2, voiceLevel, gameLevel)
    fun clip() = ExportClip(
        part, start, end, layout, camera, gameplay, width, mute, if (captionEnabled) activeCues else emptyList(),
        preset, audioOptions(), facePath
    )
    val previewUri = state.playback?.takeIf { it.first == clip() }?.second
    LaunchedEffect(part.uri, frameAt) {
        frame = try {
            StudioFraming.frame(context, part.uri, frameAt.coerceIn(0, part.durationMs - 1))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    Text("Framing", style = MaterialTheme.typography.titleLarge)
    if (showPreview && previewUri != null) {
        val (previewWidth, previewHeight) = layout.dimensions(540)
        key(previewUri) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = Modifier.heightIn(max = 410.dp).aspectRatio(previewWidth.toFloat() / previewHeight,
                        matchHeightConstraintsFirst = previewHeight > previewWidth),
                    factory = { playerContext ->
                        VideoView(playerContext).apply {
                            setMediaController(MediaController(playerContext).also { it.setAnchorView(this) })
                            setVideoURI(previewUri)
                            setOnPreparedListener { this.start() }
                        }
                    }, onRelease = { it.stopPlayback() }
                )
            }
        }
    } else if (playing) {
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
        FramingPreview(frame, layout, camera, gameplay, { if (!lockCamera) camera = it }, { if (!lockGame) gameplay = it })
        if (layout in listOf(Layout.Split, Layout.Fill, Layout.Square)) Text("Drag to reposition · pinch to zoom",
            style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showPreview = false; playing = !playing }) { Text(if (playing) "Frame preview" else "Play source") }
        if (layout == Layout.Split) {
            OutlinedButton(enabled = faceStatus != "Finding facecam…" && end > start && !busy && !lockCamera, onClick = {
                faceStatus = "Finding facecam…"
                scope.launch {
                    try {
                        val suggested = StudioFraming.suggestFacecam(context, part, start, end)
                        if (suggested != null && !lockCamera) camera = suggested
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy && end - start >= 500 && Work.PREVIEW !in state.jobs, onClick = {
            playing = false
            showPreview = true
            StudioSession.preview(context, clip())
            notify()
        }) { Text("Preview edited clip") }
        if (showPreview && previewUri != null) TextButton(onClick = { showPreview = false }) { Text("Stop preview") }
    }
    if (layout == Layout.Split) {
        val expectedStart = start
        val expectedEnd = end
        val expectedCamera = camera
        val latestTrim by rememberUpdatedState(Triple(start, end, camera))
        val locked by rememberUpdatedState(lockCamera)
        OutlinedButton(enabled = !lockCamera && !busy && end - start >= 1000 &&
            faceStatus != "Tracking facecam…" && faceStatus != "Finding facecam…", onClick = {
            if (facePath.isNotEmpty()) {
                facePath = emptyList()
                faceStatus = "Facecam tracking off"
            } else {
                faceStatus = "Tracking facecam…"
                scope.launch {
                    try {
                        val tracked = StudioFraming.track(context, part, expectedStart, expectedEnd, expectedCamera)
                        if (latestTrim == Triple(expectedStart, expectedEnd, expectedCamera) && !locked) {
                            facePath = tracked
                            faceStatus = if (tracked.isEmpty()) "No consistent facecam path found" else "Facecam path ready"
                        } else faceStatus = "Framing changed. Run tracking again."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { faceStatus = "Facecam tracking unavailable for this section" }
                }
            }
        }) { Text(if (facePath.isEmpty()) "Track facecam" else "Turn tracking off") }
    }
    if (layout == Layout.Split && faceStatus.isNotBlank()) Text(faceStatus)
    if (layout in listOf(Layout.Split, Layout.Fill, Layout.Square)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { fineFraming = !fineFraming }) { Text(if (fineFraming) "Hide controls" else "Fine controls") }
            TextButton(onClick = { if (!lockCamera) camera = CameraDefault; if (!lockGame) gameplay = GameplayDefault }) { Text("Reset framing") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (layout == Layout.Split) FilterChip(selected = lockCamera, onClick = { lockCamera = !lockCamera },
                label = { Text("Lock facecam") })
            FilterChip(selected = lockGame, onClick = { lockGame = !lockGame }, label = { Text("Lock gameplay") })
        }
        if (fineFraming) {
            if (layout == Layout.Split && !lockCamera) FramingSliders("Facecam", camera) { camera = it }
            if (!lockGame) FramingSliders("Gameplay", gameplay) { gameplay = it }
        }
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
    val waveformData = waveform
    if (waveformData == null) Text("Reading audio waveform…", style = MaterialTheme.typography.bodySmall)
    else if (waveformData.isNotEmpty()) {
        WaveformTimeline(waveformData, part.durationMs, start, end, frameAt, seek = { time ->
            playing = false
            showPreview = false
            frameAt = time
        }, onTrim = { first, last ->
            start = first
            end = last
        }, onTrimFinished = {
            playing = false
            showPreview = false
            frameAt = start
        })
    }
    var scrub by remember(frameAt) { mutableStateOf(frameAt.toFloat()) }
    Text("Playhead · ${timestamp(part.offsetMs + scrub.toLong())}", style = MaterialTheme.typography.bodySmall)
    Slider(scrub, { scrub = it }, valueRange = 0f..part.durationMs.toFloat(), onValueChangeFinished = {
        playing = false
        showPreview = false
        frameAt = scrub.toLong().coerceAtMost(part.durationMs - 1)
    })
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
    val latestCues by rememberUpdatedState(activeCues)
    var subtitleStatus by remember { mutableStateOf("") }
    val saveSubtitles = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) {
            val srt = CaptionTrack.toSrt(latestCues)
            scope.launch {
                subtitleStatus = try {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri) ?: error("Could not open subtitle file.")
                        stream.writer(Charsets.UTF_8).use { it.write(srt) }
                    }
                    "Subtitle file saved"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    "Could not save subtitle file"
                }
            }
        }
    }
    if (activeCues.isNotEmpty()) TextButton(onClick = { saveSubtitles.launch("Clip Studio.srt") }) { Text("Save subtitles (.srt)") }
    if (subtitleStatus.isNotBlank()) Text(subtitleStatus)
    TextButton(onClick = { options = !options }) { Text(if (options) "Hide export settings" else "Export settings · ${width}p") }
    if (options) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(720, 1080).forEach {
                FilterChip(selected = width == it, onClick = { width = it }, label = { Text("${it}p") })
            }
            FilterChip(selected = mute, onClick = { mute = !mute }, label = { Text("Mute") })
        }
    }
    TextButton(onClick = { showingPresets = !showingPresets }) { Text("Creator presets") }
    if (showingPresets) {
        state.library.presets.forEach { saved ->
            OutlinedButton(onClick = {
                StudioSession.changeLayout(saved.layout)
                if (!lockCamera) camera = saved.camera
                if (!lockGame) gameplay = saved.game
                width = saved.width
                preset = saved.captions
                mute = saved.muted
                normalize = saved.audio.normalize
                fades = saved.audio.fade
                mix = saved.audio.mixTracks
                voiceLevel = saved.audio.voice
                gameLevel = saved.audio.game
            }) { Text(saved.name) }
        }
        TextButton(onClick = { savingPreset = true }) { Text("Save current settings as preset") }
    }
    if (savingPreset) AlertDialog(
        onDismissRequest = { savingPreset = false }, title = { Text("Save creator preset") },
        text = { OutlinedTextField(presetName, { presetName = it.take(44) }, label = { Text("Preset name") }) },
        confirmButton = {
            TextButton(enabled = presetName.isNotBlank(), onClick = {
                StudioSession.savePreset(CreatorPreset(presetName.trim(), layout, camera, gameplay,
                    width, preset, mute, audioOptions()))
                savingPreset = false
                presetName = ""
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { savingPreset = false }) { Text("Cancel") } }
    )
    if (options && !mute) {
        Text("Audio finishing", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = normalize, onClick = { normalize = !normalize }, label = { Text("Normalize") })
            FilterChip(selected = fades, onClick = { fades = !fades }, label = { Text("Add fades") })
        }
        if (audioTracks >= 2) {
            FilterChip(selected = mix, onClick = { mix = !mix }, label = { Text("Mix first two audio tracks") })
            if (mix) {
                Text("Track 1 · voice")
                Slider(voiceLevel, { voiceLevel = it })
                Text("Track 2 · gameplay")
                Slider(gameLevel, { gameLevel = it })
            }
        }
    }
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

@Composable
private fun FramingSliders(title: String, crop: Crop, onChange: (Crop) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text("Horizontal position", style = MaterialTheme.typography.bodySmall)
        Slider(crop.x, { onChange(crop.copy(x = it)) })
        Text("Vertical position", style = MaterialTheme.typography.bodySmall)
        Slider(crop.y, { onChange(crop.copy(y = it)) })
        Text("Zoom", style = MaterialTheme.typography.bodySmall)
        Slider(crop.zoom, { onChange(crop.copy(zoom = it)) }, valueRange = 1f..4f)
    }
}

@Composable
private fun WaveformTimeline(
    samples: List<Float>, duration: Long, start: Long, end: Long, cursor: Long, seek: (Long) -> Unit,
    onTrim: (Long, Long) -> Unit, onTrimFinished: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outline
    val playhead = MaterialTheme.colorScheme.secondary
    val onSeek by rememberUpdatedState(seek)
    val currentTrim by rememberUpdatedState(start to end)
    val trim by rememberUpdatedState(onTrim)
    val finish by rememberUpdatedState(onTrimFinished)
    Canvas(Modifier.fillMaxWidth().height(52.dp)
        .pointerInput(duration) {
            detectTapGestures { point -> onSeek(((point.x / size.width).coerceIn(0f, 1f) * (duration - 1)).toLong()) }
        }
        .pointerInput(duration) {
            var handle = 0
            fun time(x: Float) = ((x / size.width).coerceIn(0f, 1f) * duration).toLong()
            detectDragGestures(onDragStart = { position ->
                val (first, last) = currentTrim
                val at = time(position.x)
                val startDistance = kotlin.math.abs(first - at)
                val endDistance = kotlin.math.abs(last - at)
                val reach = 24.dp.toPx() / size.width * duration
                handle = if (duration < 500 || minOf(startDistance, endDistance) > reach) 0
                    else if (startDistance <= endDistance) 1 else 2
            }, onDragEnd = {
                if (handle != 0) finish()
                handle = 0
            }, onDragCancel = {
                if (handle != 0) finish()
                handle = 0
            }) { change, _ ->
                val (first, last) = currentTrim
                when (handle) {
                    1 -> trim(time(change.position.x).coerceIn(0, (last - 500).coerceAtLeast(0)), last)
                    2 -> trim(first, time(change.position.x).coerceIn((first + 500).coerceAtMost(duration), duration))
                }
                if (handle != 0) change.consume()
            }
        }
    ) {
        val step = size.width / samples.size
        samples.forEachIndexed { index, peak ->
            val time = (index + .5f) / samples.size * duration
            val bar = peak.coerceIn(.04f, 1f) * size.height / 2f
            val x = (index + .5f) * step
            drawLine(if (time >= start && time <= end) primary else inactive,
                Offset(x, size.height / 2 - bar), Offset(x, size.height / 2 + bar), strokeWidth = maxOf(1f, step * .65f))
        }
        listOf(start, end).forEach {
            val handle = (it.toFloat() / duration * size.width).coerceIn(2.dp.toPx(), size.width - 2.dp.toPx())
            drawLine(primary, Offset(handle, 0f), Offset(handle, size.height), strokeWidth = 3.dp.toPx())
        }
        val x = cursor.toFloat() / duration * size.width
        drawLine(playhead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    }
}
