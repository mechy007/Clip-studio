package com.clipstudio.app

import android.graphics.Bitmap
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.withFrameNanos
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

private enum class EditorTool(val label: String) {
    Layout("Layout"), Crop("Crop"), Captions("Captions"), Audio("Audio"), Export("Export")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ClipEditor(
    part: SourcePart, layout: Layout, state: StudioState, suggestedStart: Long, suggestedEnd: Long,
    momentSelection: Int, exportRequest: Int, notify: () -> Unit
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
    var live by rememberSaveable { mutableStateOf(false) }
    var fast by rememberSaveable { mutableStateOf(true) }
    var enhanceFacecam by rememberSaveable { mutableStateOf(false) }
    var highDetail by rememberSaveable { mutableStateOf(false) }
    var originalSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var sourceSize by remember { mutableStateOf(0 to 0) }
    var trackingOn by rememberSaveable { mutableStateOf(false) }
    var normalize by rememberSaveable { mutableStateOf(false) }
    var fades by rememberSaveable { mutableStateOf(false) }
    var mix by rememberSaveable { mutableStateOf(false) }
    var voiceLevel by rememberSaveable { mutableStateOf(1f) }
    var gameLevel by rememberSaveable { mutableStateOf(.6f) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var savingPreset by rememberSaveable { mutableStateOf(false) }
    var showingPresets by rememberSaveable { mutableStateOf(false) }
    var waveform by remember { mutableStateOf<List<Float>?>(null) }
    var audioTracks by remember { mutableStateOf<Int?>(null) }
    var fineFraming by rememberSaveable { mutableStateOf(false) }
    var preset by rememberSaveable { mutableStateOf(CaptionPreset.KARAOKE) }
    var captionPosition by rememberSaveable { mutableStateOf(CaptionPosition.LOWER) }
    var captionSize by rememberSaveable { mutableStateOf(100) }
    var customCaptions by rememberSaveable { mutableStateOf(false) }
    var customText by rememberSaveable { mutableStateOf("") }
    var streamerTrack by rememberSaveable { mutableStateOf(0) }
    var captionEnabled by rememberSaveable { mutableStateOf(true) }
    var editCaptions by rememberSaveable { mutableStateOf(false) }
    var languageIndex by rememberSaveable { mutableStateOf(0) }
    var tool by rememberSaveable { mutableStateOf(EditorTool.Layout) }
    var handledExportRequest by rememberSaveable { mutableStateOf(0) }
    val toolAnchor = remember { BringIntoViewRequester() }
    LaunchedEffect(exportRequest) {
        if (exportRequest != handledExportRequest) {
            handledExportRequest = exportRequest
            tool = EditorTool.Export
            withFrameNanos { }
            toolAnchor.bringIntoView()
        }
    }
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
    val tracking = state.tracking.lastOrNull { it.part == part && it.camera == camera && it.start <= start && it.end >= end }
    val facePath = remember(trackingOn, tracking, start, end, layout) {
        if (trackingOn && tracking != null && layout == Layout.Split) {
            rebaseTracking(tracking.path, start - tracking.start, end - tracking.start)
        } else emptyList()
    }
    LaunchedEffect(part.uri, layout) {
        if (layout == Layout.Split && originalSize == null) {
            originalSize = StudioFraming.sourceDimensions(context, part.uri)
        }
    }
    LaunchedEffect(part.uri) {
        audioTracks = withContext(Dispatchers.IO) { StudioMedia.audioTrackCount(context, part.uri) }
        waveform = try {
            StudioMedia.waveform(context, part)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }
    val trackCount = audioTracks ?: 0
    val language = Languages[languageIndex].second
    val transcript = state.transcripts[part.uri]?.takeIf {
        it.audioTrack == streamerTrack && it.language == language
    }?.words.orEmpty()
    val activeCues = remember(transcript, start, end, customCaptions, customText) {
        when {
            customCaptions -> CaptionTrack.custom(customText, end - start)
            end > start -> CaptionTrack.fromTranscript(transcript, start, end, 36)
            else -> emptyList()
        }
    }
    fun audioOptions() = AudioFinish(normalize, fades, mix && trackCount >= 2, voiceLevel, gameLevel)
    fun clip() = ExportClip(
        part, start, end, layout, camera, gameplay, width, mute, if (captionEnabled) activeCues else emptyList(),
        preset, audioOptions(), facePath, fastExport = fast, highDetail = highDetail, enhanceFacecam = layout == Layout.Split && enhanceFacecam,
        captionPosition = captionPosition, captionSize = captionSize
    )
    val generatingCaptions = Work.CAPTIONS in state.jobs
    val waitingForCaptions = captionEnabled && !customCaptions && generatingCaptions
    val canRenderEdit = !busy && !waitingForCaptions && end - start >= 500L
    val canPreviewEdit = canRenderEdit && Work.PREVIEW !in state.jobs
    fun previewEdit() {
        playing = false
        live = false
        showPreview = true
        StudioSession.preview(context, clip())
        notify()
    }
    val previewUri = state.playback?.takeIf { it.first == clip() }?.second
    val renderingPreview = Work.PREVIEW in state.jobs
    LaunchedEffect(showPreview, previewUri, renderingPreview) {
        if (showPreview && previewUri == null && !renderingPreview) showPreview = false
    }
    LaunchedEffect(part.uri, frameAt, live, layout) {
        if (live) return@LaunchedEffect
        frame = try {
            StudioFraming.frame(context, part.uri, frameAt.coerceIn(0, part.durationMs - 1),
                maxEdge = if (layout == Layout.Split) 1440 else 960)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(selected = !live && !playing && !showPreview, onClick = {
                live = false
                playing = false
                showPreview = false
            }, label = { Text("Frame") })
            FilterChip(selected = live, enabled = end > start, onClick = {
                live = true
                playing = false
                showPreview = false
            }, label = { Text("Live") })
            FilterChip(selected = playing, onClick = {
                live = false
                playing = true
                showPreview = false
            }, label = { Text("Source") })
            FilterChip(selected = showPreview, enabled = canPreviewEdit,
                onClick = ::previewEdit, label = { Text("Edited") })
        }
        if (showPreview && previewUri != null) {
            val (previewWidth, previewHeight) = layout.dimensions(540)
            key(previewUri) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AndroidView(
                        modifier = Modifier.heightIn(max = 286.dp).aspectRatio(previewWidth.toFloat() / previewHeight,
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
            Text("Draft preview · final MP4 uses your export settings", style = MaterialTheme.typography.bodySmall)
        } else if (showPreview && renderingPreview) {
            Text("Rendering edited preview…", style = MaterialTheme.typography.bodySmall)
        } else if (live) {
            val (liveWidth, liveHeight) = layout.dimensions(720)
            val gestures = framingGestures(layout, sourceSize.first, sourceSize.second, camera, gameplay,
                { if (!lockCamera) camera = it }, { if (!lockGame) gameplay = it })
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.heightIn(max = 286.dp).aspectRatio(liveWidth.toFloat() / liveHeight,
                    matchHeightConstraintsFirst = liveHeight > liveWidth).then(gestures)) {
                    AndroidView(
                        factory = { playerContext ->
                            LiveFramingView(playerContext, part.uri, { w, h -> sourceSize = w to h }) {
                                live = false
                                faceStatus = "Live playback unavailable for this source"
                            }
                        },
                        modifier = Modifier.matchParentSize(),
                        update = { it.updateFrame(layout, camera, gameplay, facePath, start, end, mute) },
                        onRelease = { it.close() }
                    )
                }
            }
            Text("Live framing · see captions and audio effects in Edited",
                style = MaterialTheme.typography.bodySmall)
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
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
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
                live = false
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
            live = false
            frameAt = scrub.toLong().coerceAtMost(part.durationMs - 1)
        })
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        EditorTool.entries.forEach { item ->
            FilterChip(selected = tool == item, onClick = { tool = item }, label = { Text(item.label) })
        }
    }
    val canCaption = canRenderEdit && !generatingCaptions && end - start <= StudioAnalysis.MAX_CAPTION_DURATION_MS
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(toolAnchor).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (tool) {
            EditorTool.Layout -> {
                Text("Output layout", style = MaterialTheme.typography.titleMedium)
                LayoutPicker(layout, !busy, StudioSession::changeLayout)
                TextButton(onClick = { showingPresets = !showingPresets }) { Text("Creator presets") }
                if (showingPresets) {
                    state.library.presets.forEach { saved ->
                        OutlinedButton(onClick = {
                            StudioSession.changeLayout(saved.layout)
                            if (!lockCamera) camera = saved.camera
                            if (!lockGame) gameplay = saved.game
                            width = saved.width
                            preset = saved.captions
                            captionPosition = saved.captionPosition
                            captionSize = saved.captionSize
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
                                width, preset, mute, audioOptions(), captionPosition, captionSize))
                            savingPreset = false
                            presetName = ""
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { savingPreset = false }) { Text("Cancel") } }
                )
            }
            EditorTool.Crop -> {
                if (layout == Layout.Fit || layout == Layout.Landscape) {
                    Text("This layout fits the full source. Choose a fill or 50/50 layout to crop.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (layout == Layout.Split) {
                    val expectedStart = start
                    val expectedEnd = end
                    val expectedCamera = camera
                    val latestTrim by rememberUpdatedState(Triple(start, end, camera))
                    val locked by rememberUpdatedState(lockCamera)
                    OutlinedButton(enabled = !lockCamera && !busy && end - start >= 1000 &&
                        faceStatus != "Tracking facecam…" && faceStatus != "Finding facecam…", onClick = {
                        if (facePath.isNotEmpty()) {
                            trackingOn = false
                            faceStatus = "Facecam tracking off"
                        } else if (tracking != null) {
                            trackingOn = true
                            faceStatus = "Reused facecam tracking"
                        } else {
                            faceStatus = "Tracking facecam…"
                            scope.launch {
                                try {
                                    val tracked = StudioFraming.track(context, part, expectedStart, expectedEnd, expectedCamera)
                                    if (latestTrim == Triple(expectedStart, expectedEnd, expectedCamera) && !locked) {
                                        if (tracked.isNotEmpty()) {
                                            StudioSession.rememberTracking(TrackingMemo(part, expectedStart, expectedEnd, expectedCamera, tracked))
                                            trackingOn = true
                                        }
                                        faceStatus = if (tracked.isEmpty()) "No consistent facecam path found" else "Facecam path ready"
                                    } else faceStatus = "Framing changed. Run tracking again."
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { faceStatus = "Facecam tracking unavailable for this section" }
                            }
                        }
                    }) { Text(if (facePath.isEmpty()) "Track facecam" else "Turn tracking off") }
                }
                if (faceStatus.isNotBlank()) Text(faceStatus)
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
                if (layout == Layout.Split) originalSize?.let { (sourceWidth, sourceHeight) ->
                    val (cropWidth, cropHeight) = facecamCropPixels(sourceWidth, sourceHeight, camera.zoom)
                    val panelHeight = layout.dimensions(width).second / 2
                    val enlarged = cropWidth < width || cropHeight < panelHeight
                    Text("Facecam crop · ${cropWidth}×$cropHeight source pixels · ${width}×$panelHeight output",
                        style = MaterialTheme.typography.bodySmall)
                    if (enlarged) Text("The export enlarges this crop. Try a wider crop or a higher-resolution source for more detail.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            EditorTool.Captions -> {
                Text("Captions", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !customCaptions, onClick = { customCaptions = false }, label = { Text("Streamer audio") })
                    FilterChip(selected = customCaptions, onClick = { customCaptions = true }, label = { Text("Custom text") })
                }
                if (customCaptions) {
                    OutlinedTextField(
                        value = customText, onValueChange = { customText = it.take(140) },
                        label = { Text("Caption text") }, supportingText = { Text("Applies to the selected trim") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4
                    )
                } else {
                    Text("Choose the audio track that contains the streamer’s voice.", style = MaterialTheme.typography.bodySmall)
                    if (audioTracks == 1) {
                        Text("This file has one audio track. Use Custom text if gameplay and voice are mixed together.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(trackCount.coerceAtLeast(0)) { index ->
                            FilterChip(selected = index == streamerTrack, enabled = !generatingCaptions,
                                onClick = { streamerTrack = index }, label = { Text("Track ${index + 1}") })
                        }
                    }
                    val trackMessage = when (audioTracks) {
                        null -> "Reading audio tracks…"
                        -1 -> "Could not read this file’s audio tracks."
                        0 -> "This file has no audio track."
                        else -> null
                    }
                    trackMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = canCaption && streamerTrack in 0 until trackCount, onClick = {
                            StudioSession.captions(context, part, start, end, language, streamerTrack)
                            notify()
                        }) { Text("Generate captions") }
                        TextButton(enabled = !generatingCaptions,
                            onClick = { languageIndex = (languageIndex + 1) % Languages.size }) { Text(Languages[languageIndex].first) }
                    }
                    if (end - start > StudioAnalysis.MAX_CAPTION_DURATION_MS) Text("Choose up to 2 minutes for automatic captions.")
                }
                if (!customCaptions && generatingCaptions) {
                    Text("Generating captions…", style = MaterialTheme.typography.bodySmall)
                } else if (activeCues.isNotEmpty()) {
                    OutlinedButton(enabled = captionEnabled && canPreviewEdit, onClick = ::previewEdit) {
                        Text("Preview captions")
                    }
                } else if (!customCaptions && transcript.isNotEmpty()) {
                    Text("No captions overlap this trim. Generate captions for the selected range.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (activeCues.isNotEmpty() || (!customCaptions && transcript.isNotEmpty())) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = captionEnabled, onCheckedChange = { captionEnabled = it })
                        Text("Burn into video")
                        if (!customCaptions && transcript.isNotEmpty()) {
                            TextButton(onClick = { editCaptions = !editCaptions }) { Text(if (editCaptions) "Done" else "Edit text") }
                        }
                    }
                    Text("Caption style", style = MaterialTheme.typography.titleSmall)
                    val styles = listOf(CaptionPreset.CLEAN, CaptionPreset.KARAOKE, CaptionPreset.POP,
                        CaptionPreset.BOUNCE, CaptionPreset.WIPE)
                    styles.chunked(3).forEach { row ->
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { item ->
                                FilterChip(selected = item == preset, onClick = { preset = item }, label = { Text(item.label) })
                            }
                        }
                    }
                    Text("Caption position", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CaptionPosition.entries.forEach { item ->
                            FilterChip(selected = item == captionPosition, onClick = { captionPosition = item },
                                label = { Text(item.label) })
                        }
                    }
                    Text("Caption size · $captionSize%", style = MaterialTheme.typography.titleSmall)
                    Slider(captionSize.toFloat(), { captionSize = it.toInt() }, valueRange = 60f..150f, steps = 8)
                    if (!customCaptions && editCaptions) transcript.forEachIndexed { index, row ->
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
            }
            EditorTool.Audio -> {
                FilterChip(selected = mute, onClick = { mute = !mute }, label = { Text("Mute") })
                if (!mute) {
                    Text("Audio finishing", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = normalize, onClick = { normalize = !normalize }, label = { Text("Normalize") })
                        FilterChip(selected = fades, onClick = { fades = !fades }, label = { Text("Add fades") })
                    }
                    if (trackCount >= 2) {
                        FilterChip(selected = mix, onClick = { mix = !mix }, label = { Text("Mix first two audio tracks") })
                        if (mix) {
                            Text("Track 1 · voice")
                            Slider(voiceLevel, { voiceLevel = it })
                            Text("Track 2 · gameplay")
                            Slider(gameLevel, { gameLevel = it })
                        }
                    }
                }
            }
            EditorTool.Export -> {
                FilterChip(selected = fast, onClick = { fast = !fast }, label = { Text("Fast Export") })
                if (fast) Text("Tries hardware H.264 at the selected resolution", style = MaterialTheme.typography.bodySmall)
                FilterChip(selected = highDetail, onClick = { highDetail = !highDetail }, label = { Text("High detail") })
                if (highDetail) Text("Higher bitrate preserves more detail and makes larger MP4 files.",
                    style = MaterialTheme.typography.bodySmall)
                val burnIn = captionEnabled && activeCues.isNotEmpty()
                if (waitingForCaptions || burnIn) {
                    Text(if (waitingForCaptions) "Waiting for captions…" else "${preset.label} captions will be burned into the MP4",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(enabled = canRenderEdit && !burnIn, onClick = {
                    StudioSession.export(context, listOf(clip().copy(fastExport = true, originalFraming = true,
                        captions = emptyList(), facePath = emptyList(), audio = AudioFinish(), muted = false)))
                    notify()
                }) { Text("Quick trim · no captions") }
                Text("Output resolution", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(720, 1080, 1440).forEach {
                        FilterChip(selected = width == it, onClick = { width = it }, label = { Text("${it}p") })
                    }
                }
                if (width == 1440) Text("Larger files and longer renders. Choose this for high-resolution recordings.",
                    style = MaterialTheme.typography.bodySmall)
                if (layout == Layout.Split) {
                    FilterChip(selected = enhanceFacecam, onClick = { enhanceFacecam = !enhanceFacecam },
                        label = { Text("Enhance facecam") })
                    Text("Sharpens edges in the facecam panel. Open Edited to review the effect.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = canRenderEdit, onClick = {
                        StudioSession.export(context, listOf(clip()))
                        notify()
                    }) { Text("Export MP4") }
                    OutlinedButton(enabled = canRenderEdit && state.queue.size < 10, onClick = { StudioSession.queue(clip()) }) {
                        Text("Add to queue")
                    }
                }
            }
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
            fun time(x: Float) = ((x / size.width).coerceIn(0f, 1f) * duration).toLong()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val (first, last) = currentTrim
                val at = time(down.position.x)
                val startDistance = kotlin.math.abs(first - at)
                val endDistance = kotlin.math.abs(last - at)
                val reach = 24.dp.toPx() / size.width * duration
                if (duration < 500 || minOf(startDistance, endDistance) > reach) return@awaitEachGesture
                val startHandle = startDistance <= endDistance
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    ?: return@awaitEachGesture
                fun move(x: Float) {
                    val (currentStart, currentEnd) = currentTrim
                    if (startHandle) trim(time(x).coerceIn(0, (currentEnd - 500).coerceAtLeast(0)), currentEnd)
                    else trim(currentStart, time(x).coerceIn((currentStart + 500).coerceAtMost(duration), duration))
                }
                move(drag.position.x)
                horizontalDrag(drag.id) { change ->
                    move(change.position.x)
                    change.consume()
                }
                finish()
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
