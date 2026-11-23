package com.clipstudio.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class Work { IMPORT, ANALYZE, CAPTIONS, EXPORT, JOIN, PREVIEW }

internal data class StudioState(
    val project: StudioProject? = null,
    val jobs: Map<Work, String> = emptyMap(),
    val status: String = "",
    val exports: List<Uri> = emptyList(),
    val transcripts: Map<Uri, CaptionMemo> = emptyMap(),
    val library: StudioLibrary = StudioLibrary(),
    val joined: SourcePart? = null,
    val joinRevision: Long = 0,
    val storageWarning: String? = null,
    val playback: Pair<ExportClip, Uri>? = null,
    val queueExportActive: Boolean = false,
    val exportingClip: ExportClip? = null,
    val tracking: List<TrackingMemo> = emptyList()
) {
    val queue: List<ExportClip> get() = project?.queue.orEmpty()
    val exportStats: Map<Uri, RenderStats> get() = project?.renderStats.orEmpty()
}

internal data class ExportClip(
    val part: SourcePart, val start: Long, val end: Long,
    val layout: Layout, val camera: Crop, val gameplay: Crop,
    val width: Int, val muted: Boolean, val captions: List<CaptionCue> = emptyList(),
    val preset: CaptionPreset = CaptionPreset.KARAOKE,
    val audio: AudioFinish = AudioFinish(), val facePath: List<FacePoint> = emptyList(),
    val fastExport: Boolean = false, val originalFraming: Boolean = false, val highDetail: Boolean = false,
    val enhanceFacecam: Boolean = false,
    val captionPosition: CaptionPosition = CaptionPosition.LOWER,
    val captionSize: Int = 100
)

internal object StudioSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateFlow = MutableStateFlow(StudioState())
    val state = stateFlow.asStateFlow()
    private val jobs = mutableMapOf<Work, Job>()
    private val saves = Channel<StudioLibrary>(Channel.CONFLATED)
    private var initialized = false
    private var autoAnalyze = true
    private var resumeAnalysis = false
    private val captionMemos = mutableListOf<CaptionMemo>()

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            val store = StudioProjectStore(app)
            val recovered = try {
                withContext(Dispatchers.IO) {
                    try { store.load() }
                    catch (_: Exception) { delay(250); store.load() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                update { it.copy(storageWarning = "Saved projects could not be opened. Existing files were kept. Reopen Clip Studio to retry.") }
                return@launch
            }
            val ongoing = state.value
            if (ongoing.project == null) {
                val project = recovered.projects.firstOrNull { it.id == recovered.currentId } ?: recovered.projects.firstOrNull()
                update { it.copy(project = project, library = recovered, exports = project?.exports.orEmpty()) }
            } else {
                val merged = ongoing.library.copy(
                    projects = (ongoing.library.projects + recovered.projects).distinctBy { it.id },
                    presets = (ongoing.library.presets + recovered.presets).distinctBy { it.name }
                )
                update { it.copy(library = merged) }
                saves.trySend(merged)
            }
            for (library in saves) {
                try {
                    withContext(Dispatchers.IO) { store.save(library) }
                } catch (_: java.io.IOException) {
                    update { it.copy(status = "Could not save the project. You can still export clips.") }
                }
            }
        }
    }

    fun importLocal(context: Context, source: Uri, name: String, layout: Layout) =
        work(context, Work.IMPORT, "Opening video") { app ->
            val part = SourcePart(source, 0, StudioMedia.duration(app, source))
            newProject(name, layout)
            addPart(app, part)
            state.value.project?.let { save(it.copy(importComplete = true)) }
            status("Video ready")
        }

    fun importYouTube(context: Context, link: String, layout: Layout) =
        work(context, Work.IMPORT, "Opening YouTube recording") { app ->
            val id = YouTubeLinkClassifier.parseVideoId(link)
            newProject("YouTube · $id", layout, "https://www.youtube.com/watch?v=$id")
            importRecording(app, link)
        }

    fun resume(context: Context) = work(context, Work.IMPORT, "Resuming YouTube recording") { app ->
        val project = state.value.project ?: return@work
        val link = project.youtubeLink ?: return@work
        if (!project.importComplete) importRecording(app, link)
    }

    private suspend fun importRecording(app: Context, link: String) {
        val last = state.value.project?.parts?.map { it.part }?.filter { !it.joined }?.maxByOrNull { it.nextOffsetMs }
        val offset = last?.nextOffsetMs ?: 0L
        val segmented = try {
            StudioMedia.downloadInParts(app, link, offset, last?.formatId) { part ->
                withContext(Dispatchers.Main.immediate) {
                    addPart(app, part)
                    describe(Work.IMPORT, "Downloading · ${state.value.project?.parts?.count { !it.part.joined } ?: 0} sections ready")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (state.value.project?.parts?.isNotEmpty() == true) throw error
            false
        }
        if (!segmented) {
            check(offset == 0L) { "Could not resume this format. Completed sections are still available." }
            describe(Work.IMPORT, "Preparing full-video import")
            val uri = YouTubeVideoImporter.download(app, link, 1080) { percent ->
                scope.launch {
                    if (jobs[Work.IMPORT]?.isActive == true) describe(Work.IMPORT, "Downloading full video · $percent%")
                }
            }
            addPart(app, SourcePart(uri, 0, StudioMedia.duration(app, uri)))
        }
        state.value.project?.let { save(it.copy(importComplete = true)) }
        status("Import complete")
    }

    fun join(context: Context, first: SourcePart, second: SourcePart) = work(context, Work.JOIN, "Joining sections") { app ->
        val project = state.value.project ?: return@work
        if (project.parts.none { it.part == first } || project.parts.none { it.part == second }) return@work
        val part = project.parts.map { it.part }.firstOrNull {
            it.joined && it.offsetMs == first.offsetMs && it.nextOffsetMs == second.nextOffsetMs
        } ?: StudioMedia.join(app, listOf(first, second)).also { joined ->
            updateProject(project.id) { it.copy(parts = it.parts + PartReview(joined, emptyList())) }
        }
        if (state.value.project?.id == project.id) {
            update { it.copy(joined = part, joinRevision = it.joinRevision + 1) }
            status("Joined sections are ready to trim")
        }
    }

    fun preview(context: Context, clip: ExportClip) = work(context, Work.PREVIEW, "Rendering playback preview") { app ->
        val uri = StudioMedia.preview(app, clip)
        clearPlayback()
        update { it.copy(playback = clip to uri) }
    }

    private fun clearPlayback() {
        state.value.playback?.second?.path?.let { File(it).delete() }
        update { it.copy(playback = null) }
    }

    fun analyze(context: Context) {
        if (Work.EXPORT in jobs) {
            resumeAnalysis = true
            return
        }
        work(context, Work.ANALYZE, "Finding highlights") { app ->
            while (true) {
                val current = state.value.project ?: break
                val pending = current.parts.firstOrNull { it.highlights == null } ?: break
                describe(Work.ANALYZE, "Finding highlights · section ${current.parts.indexOf(pending) + 1}")
                val highlights = StudioAnalysis.highlights(app, pending.part, current.mode)
                updateProject(current.id) { project ->
                    project.copy(parts = project.parts.map {
                        if (it.part.uri == pending.part.uri) it.copy(highlights = highlights) else it
                    })
                }
            }
        }
    }

    fun captions(
        context: Context, part: SourcePart, start: Long, end: Long, language: String?, audioTrack: Int
    ) = work(context, Work.CAPTIONS, "Generating captions from audio track ${audioTrack + 1}") { app ->
        val projectId = state.value.project?.id
        val cached = captionMemos.lastOrNull { it.covers(part, start, end, language, audioTrack) }
        val memo = cached ?: CaptionMemo(part, start, end, language,
            StudioAnalysis.captions(app, part, start, end, language, audioTrack), audioTrack).also(::rememberCaptions)
        if (projectId != state.value.project?.id) return@work
        update {
            val recent = (it.transcripts - part.uri + (part.uri to memo)).toList().takeLast(24).toMap()
            it.copy(transcripts = recent)
        }
        status(when {
            memo.words.isEmpty() -> "No speech found in this clip"
            cached != null -> "Reused captions for this range"
            else -> "Captions ready to edit"
        })
    }

    private fun rememberCaptions(memo: CaptionMemo) {
        captionMemos.removeAll { it.covers(memo.part, memo.start, memo.end, memo.language, memo.audioTrack) &&
            it.start == memo.start && it.end == memo.end }
        captionMemos += memo
        if (captionMemos.size > 24) captionMemos.removeAt(0)
    }

    fun rememberTracking(memo: TrackingMemo) {
        if (memo.path.isNotEmpty()) update { it.copy(tracking = (it.tracking + memo).takeLast(16)) }
    }

    fun exportFirst(context: Context, clip: ExportClip) {
        val project = state.value.project ?: return
        if (clip !in project.queue || clip == state.value.exportingClip) return
        val active = state.value.exportingClip?.takeIf { it in project.queue }
        save(project.copy(queue = listOfNotNull(active, clip) + project.queue.filter { it != active && it != clip }))
        if (jobs[Work.EXPORT] == null) export(context, listOf(clip), fromQueue = true)
    }

    fun export(context: Context, clips: List<ExportClip>, fromQueue: Boolean = false) =
        work(context, Work.EXPORT, "Preparing export") { app ->
            val source = state.value.project ?: return@work
            if (clips.isEmpty() || clips.any { clip -> source.parts.none { it.part == clip.part } }) return@work
            jobs[Work.ANALYZE]?.let { analysis ->
                resumeAnalysis = true
                describe(Work.ANALYZE, "Pausing analysis for export")
                analysis.cancelAndJoin()
            }
            update { it.copy(queueExportActive = fromQueue) }
            var count = 0
            while (true) {
                val clip = if (fromQueue) state.value.library.projects.firstOrNull { it.id == source.id }
                    ?.queue?.firstOrNull() else clips.getOrNull(count)
                if (clip == null) break
                update { it.copy(exportingClip = clip) }
                describe(Work.EXPORT, "Exporting clip ${count + 1}")
                StudioMedia.export(app, clip, { phase ->
                    scope.launch {
                        if (state.value.exportingClip == clip) describe(Work.EXPORT, phase)
                    }
                }) { uri, stats ->
                    withContext(Dispatchers.Main.immediate) {
                        updateProject(source.id) { project ->
                            project.copy(exports = project.exports + uri,
                                queue = if (fromQueue) project.queue - clip else project.queue,
                                renderStats = (project.renderStats + (uri to stats)).toList().takeLast(24).toMap())
                        }
                    }
                }
                count++
            }
            if (count > 0) status(if (count == 1) "Saved to Movies/Clip Studio" else "$count clips saved to Movies/Clip Studio")
        }

    fun editTranscript(uri: Uri, index: Int, text: String) {
        val memo = state.value.transcripts[uri] ?: return
        val transcript = memo.words
        val row = transcript.getOrNull(index) ?: return
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val timing = if (words.size == row.words.size) row.words.zip(words) { word, value -> word.copy(word = value) }
        else emptyList()
        val updated = transcript.toMutableList().apply { set(index, row.copy(text = text, words = timing)) }
        val edited = memo.copy(words = updated)
        rememberCaptions(edited)
        update { it.copy(transcripts = it.transcripts + (uri to edited)) }
    }

    fun queue(clip: ExportClip) {
        val project = state.value.project ?: return
        if (clip in project.queue || project.queue.size >= 10) return
        save(project.copy(queue = project.queue + clip))
        status("Clip added to export queue")
    }

    fun removeQueued(index: Int) {
        val project = state.value.project ?: return
        save(project.copy(queue = project.queue.filterIndexed { position, _ -> position != index }))
    }

    fun changeLayout(layout: Layout) {
        state.value.project?.let { save(it.copy(layout = layout)) }
    }

    fun cancel(key: Work) {
        jobs[key]?.cancel()
        if (key == Work.ANALYZE) {
            resumeAnalysis = false
            if (Work.IMPORT in jobs) autoAnalyze = false
        }
    }

    fun cancelAll() {
        autoAnalyze = false
        resumeAnalysis = false
        jobs.values.toList().forEach { it.cancel() }
    }

    private fun addPart(app: Context, part: SourcePart) {
        val project = state.value.project ?: return
        save(project.copy(parts = project.parts + PartReview(part)))
        if (autoAnalyze) analyze(app)
    }

    fun setMode(context: Context, mode: HighlightMode) {
        val project = state.value.project ?: return
        if (project.mode == mode || Work.ANALYZE in jobs) return
        save(project.copy(mode = mode, parts = project.parts.map { it.copy(highlights = if (it.part.joined) emptyList() else null) }))
        analyze(context)
    }

    fun openProject(id: String) {
        if (jobs.isNotEmpty()) return
        val project = state.value.library.projects.firstOrNull { it.id == id } ?: return
        clearPlayback()
        update { it.copy(exports = project.exports, transcripts = emptyMap(), joined = null, status = "") }
        save(project)
    }

    fun savePreset(preset: CreatorPreset) {
        val library = state.value.library
        val presets = listOf(preset) + library.presets.filterNot { it.name.equals(preset.name, true) }
        persist(library.copy(presets = presets.take(12)))
    }

    private fun newProject(name: String, layout: Layout, link: String? = null) {
        autoAnalyze = true
        clearPlayback()
        update { it.copy(exports = emptyList(), transcripts = emptyMap(), joined = null, status = "") }
        save(StudioProject(name, layout, emptyList(), youtubeLink = link))
    }

    private fun save(project: StudioProject) {
        val library = state.value.library
        update { it.copy(project = project, exports = project.exports) }
        persist(library.copy(projects = listOf(project) + library.projects.filterNot { it.id == project.id },
            currentId = project.id))
    }

    private fun updateProject(id: String, change: (StudioProject) -> StudioProject) {
        val current = state.value
        val existing = current.library.projects.firstOrNull { it.id == id } ?: return
        val updated = change(existing)
        if (current.project?.id == id) save(updated)
        else persist(current.library.copy(projects = current.library.projects.map { if (it.id == id) updated else it }))
    }

    private fun persist(library: StudioLibrary) {
        update { it.copy(library = library) }
        saves.trySend(library)
    }

    private fun status(status: String) = update { it.copy(status = status) }

    private fun describe(key: Work, text: String) = update { it.copy(jobs = it.jobs + (key to text)) }

    private inline fun update(change: (StudioState) -> StudioState) {
        stateFlow.value = change(stateFlow.value)
    }

    private fun work(context: Context, key: Work, label: String, body: suspend (Context) -> Unit) {
        if (jobs[key] != null) return
        val app = context.applicationContext
        initialize(app)
        if (jobs.isEmpty() && state.value.jobs.isEmpty()) {
            try {
                ContextCompat.startForegroundService(app, Intent(app, StudioWorkService::class.java))
            } catch (_: IllegalStateException) {
                status("Open Clip Studio to start processing")
                return
            }
        }
        describe(key, label)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                body(app)
            } catch (cancelled: CancellationException) {
                if (key != Work.ANALYZE || !resumeAnalysis) status("Cancelled · completed work was kept")
                throw cancelled
            } catch (error: Exception) {
                status(error.localizedMessage ?: "$label failed.")
            } catch (_: LinkageError) {
                status("This phone could not load the required media component.")
            } finally {
                jobs.remove(key)
                // Start the replacement foreground work before publishing an idle state.
                if (key == Work.EXPORT && resumeAnalysis) {
                    resumeAnalysis = false
                    if (state.value.project?.parts?.any { it.highlights == null } == true) analyze(app)
                }
                update {
                    if (key == Work.EXPORT) it.copy(jobs = it.jobs - key, exportingClip = null, queueExportActive = false)
                    else it.copy(jobs = it.jobs - key)
                }
            }
        }
        jobs[key] = job
        job.start()
    }
}
