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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class Work { IMPORT, ANALYZE, CAPTIONS, EXPORT, JOIN, PREVIEW, STORAGE }

internal data class StudioState(
    val project: StudioProject? = null,
    val jobs: Map<Work, String> = emptyMap(),
    val status: String = "",
    val exports: List<Uri> = emptyList(),
    val transcripts: Map<String, CaptionMemo> = emptyMap(),
    val library: StudioLibrary = StudioLibrary(),
    val persistedDrafts: Map<String, Map<String, ClipDraft>> = emptyMap(),
    val joined: SourcePart? = null,
    val joinRevision: Long = 0,
    val guidedPart: SourcePart? = null,
    val guidedDownload: GuidedDownloadProgress? = null,
    val guidedExportRevision: Int = 0,
    val storageWarning: String? = null,
    val playback: Pair<ExportClip, Uri>? = null,
    val queueExportActive: Boolean = false,
    val finishingPart: Boolean = false,
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
    val captionSize: Int = 100,
    val enhancement: VideoEnhancement = VideoEnhancement.OFF
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
            update { it.copy(persistedDrafts = recovered.projects.associate { project -> project.id to project.drafts }) }
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
                    withContext(Dispatchers.IO) {
                        store.save(library)
                        library.projects.filter { it.guidedImport && it.importComplete }.forEach {
                            runCatching { StudioMedia.guidedFullFile(app, it.id).delete() }
                        }
                    }
                    update { it.copy(persistedDrafts = library.projects.associate { project -> project.id to project.drafts }) }
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

    fun importYouTube(
        context: Context, link: String, layout: Layout, guided: Boolean = false, startMs: Long = 0,
        fromFull: Boolean = false, fastParts: Boolean = false
    ) =
        work(context, Work.IMPORT, "Opening YouTube recording") { app ->
            val id = YouTubeLinkClassifier.parseVideoId(link)
            newProject("YouTube · $id", layout, "https://www.youtube.com/watch?v=$id", guided, startMs, fromFull, fastParts)
            if (guided) importGuidedPart(app) else importRecording(app, link)
        }

    fun analyzeYouTubeParts(context: Context, link: String, layout: Layout, startMs: Long) =
        work(context, Work.IMPORT, "Opening recording for analysis", "Analysis paused · saved samples kept") { app ->
            val id = YouTubeLinkClassifier.parseVideoId(link)
            newProject("YouTube · $id", layout, "https://www.youtube.com/watch?v=$id",
                guided = true, startMs = startMs, fastParts = true, scanFirst = true)
            scanCurrentRecording(app)
        }

    fun retryPartAnalysis(context: Context) = work(context, Work.IMPORT, "Opening recording for analysis", "Analysis paused · saved samples kept") { app ->
        if (state.value.project?.scannedParts != null) scanCurrentRecording(app)
    }

    private suspend fun scanCurrentRecording(app: Context) {
        val project = state.value.project ?: return
        val link = project.youtubeLink ?: return
        val saved = project.scannedParts ?: return
        val parts = StudioMedia.scanRecording(app, link, project.guidedStartMs, saved, progress = { current, total ->
            withContext(Dispatchers.Main.immediate) { describe(Work.IMPORT, "Sampling audio · part $current of $total") }
        }, checkpoint = { results ->
            withContext(Dispatchers.Main.immediate) { updateProject(project.id) { it.copy(scannedParts = results) } }
        })
        status(when {
            parts.none { it.score != null } -> "Choose by timestamp or retry the audio scan."
            parts.any { it.needsSampling } -> "Choose a part to download or continue the audio scan."
            else -> "Choose a part to download"
        })
    }

    fun downloadScannedPart(context: Context, startMs: Long) = work(context, Work.IMPORT, "Preparing selected part") { app ->
        val project = state.value.project ?: return@work
        val selected = project.scannedParts?.firstOrNull { it.range.startMs == startMs } ?: return@work
        if (project.availableParts.any { !it.part.joined && it.part.offsetMs == startMs }) return@work
        importGuidedPart(app, selected)
    }

    fun resume(context: Context, useFullDownload: Boolean = false, useFastParts: Boolean = false) = work(context, Work.IMPORT, "Resuming YouTube recording") { app ->
        val project = state.value.project ?: return@work
        val link = project.youtubeLink ?: return@work
        if (!project.importComplete && project.scannedParts == null) {
            if (project.guidedImport) {
                if (useFullDownload) save(project.copy(guidedFromFull = true, fastGuidedParts = false))
                else if (useFastParts) save(project.copy(guidedFromFull = false, fastGuidedParts = true))
                importGuidedPart(app)
            } else importRecording(app, link)
        }
    }

    private suspend fun importGuidedPart(app: Context, selected: RecordingScanPart? = null) {
        val project = state.value.project ?: return
        val link = project.youtubeLink ?: return
        val previous = project.latestSourcePart
        val partNumber = if (selected != null) project.scannedParts.orEmpty().indexOf(selected) + 1
            else project.parts.count { !it.part.joined } + 1
        describe(Work.IMPORT, "Preparing part $partNumber")
        val activeImport = jobs[Work.IMPORT]
        var downloaded = false
        val start = selected?.range?.startMs ?: previous?.nextOffsetMs ?: project.guidedStartMs
        val publish: suspend (SourcePart, Boolean) -> Unit = { part, last ->
            withContext(Dispatchers.Main.immediate) {
                addPart(app, part)
                val allDone = if (selected == null) last else state.value.project?.let { owner ->
                    val offsets = owner.parts.filterNot { it.part.joined }.map { it.part.offsetMs }.toSet()
                    owner.scannedParts.orEmpty().all { it.range.startMs in offsets }
                } == true
                if (allDone) state.value.project?.let { save(it.copy(importComplete = true)) }
                update { it.copy(guidedPart = part) }
                status(when {
                    selected != null -> "Part $partNumber ready to clip"
                    last -> "Final part ready to clip"
                    project.guidedFromFull -> "Part $partNumber ready. Next part uses the saved recording."
                    else -> "Part $partNumber ready. Download paused."
                })
                downloaded = true
            }
        }
        if (project.guidedFromFull) {
            fun report(label: String, percent: Int) {
                scope.launch {
                    if (jobs[Work.IMPORT] != activeImport || activeImport?.isActive != true) return@launch
                    update { it.copy(guidedDownload = GuidedDownloadProgress(percent, null),
                        jobs = it.jobs + (Work.IMPORT to "$label · $percent%")) }
                }
            }
            StudioMedia.preparePartFromFull(app, project.id, link, start,
                downloading = { report("Downloading full video", it) },
                cutting = { report("Preparing part $partNumber from saved video", it) },
                publish = publish)
        } else {
            StudioMedia.downloadRecordingPart(app, project.id, link, start,
                if (selected == null) previous?.formatId else null, fast = project.fastGuidedParts, requestedEndMs = selected?.range?.endMs, progress = { progress ->
                    scope.launch {
                        if (jobs[Work.IMPORT] != activeImport || activeImport?.isActive != true) return@launch
                        val current = state.value.guidedDownload
                        if (!progress.newAttempt && (current?.assembling == true || progress.percent < (current?.percent ?: 0))) return@launch
                        val label = if (progress.newAttempt) "Trying resumable download for part $partNumber"
                        else if (progress.assembling) "Assembling part $partNumber" else if (progress.percent == 0 && progress.bytesFetched != null) {
                            if (progress.bytesFetched == 0L) "Connecting for part $partNumber"
                            else "Downloading part $partNumber · ${android.text.format.Formatter.formatFileSize(app, progress.bytesFetched)} received"
                        } else if (progress.percent == 0) "Preparing download for part $partNumber" else buildString {
                            append("Downloading part $partNumber · ${progress.percent}%")
                            progress.etaMs?.let { append(" · about ${timestamp(it)} left") }
                        }
                        update { it.copy(guidedDownload = progress, jobs = it.jobs + (Work.IMPORT to label)) }
                    }
                }, publish = publish)
        }
        if (!downloaded) {
            check(selected == null) { "The selected part is past the end of the available recording." }
            check(previous != null) { "The start time must be before the end of the recording." }
            state.value.project?.let { save(it.copy(importComplete = true)) }
            status("All parts downloaded")
        }
    }

    private suspend fun importRecording(app: Context, link: String) {
        val last = state.value.project?.latestSourcePart
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
        if (project.availableParts.none { it.part == first } || project.availableParts.none { it.part == second }) return@work
        val part = project.availableParts.map { it.part }.firstOrNull {
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
                val pending = current.availableParts.firstOrNull { it.highlights == null } ?: break
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
        context: Context, draftId: String, part: SourcePart, start: Long, end: Long, language: String?, audioTrack: Int
    ) = work(context, Work.CAPTIONS, "Generating captions from audio track ${audioTrack + 1}") { app ->
        val projectId = state.value.project?.id ?: return@work
        val cached = captionMemos.lastOrNull { it.covers(part, start, end, language, audioTrack) }
        val memo = cached ?: CaptionMemo(part, start, end, language,
            StudioAnalysis.captions(app, part, start, end, language, audioTrack), audioTrack).also(::rememberCaptions)
        updateProject(projectId) { owner ->
            val draft = owner.drafts[draftId] ?: return@updateProject owner
            owner.copy(drafts = owner.drafts + (draftId to draft.copy(transcript = memo)))
        }
        if (projectId != state.value.project?.id) return@work
        update {
            val recent = (it.transcripts - draftId + (draftId to memo)).toList().takeLast(24).toMap()
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
        if (state.value.finishingPart || clip !in project.queue || clip == state.value.exportingClip) return
        val active = state.value.exportingClip?.takeIf { it in project.queue }
        save(project.copy(queue = listOfNotNull(active, clip) + project.queue.filter { it != active && it != clip }))
        if (jobs[Work.EXPORT] == null) export(context, listOf(clip), fromQueue = true)
    }

    fun finishPart(context: Context, part: SourcePart) {
        val project = state.value.project ?: return
        if (jobs[Work.EXPORT] != null || jobs[Work.IMPORT] != null || jobs[Work.STORAGE] != null ||
            !project.guidedImport || project.availableParts.none { it.part == part }) return
        val queued = project.queue.filter { it.part == part }
        if (queued.isNotEmpty()) {
            export(context, queued, finishing = part)
        } else if (part.uri in project.exportedParts) {
            offerNextPart(project, part)
            status("Part finished")
        }
    }

    private fun offerNextPart(project: StudioProject, part: SourcePart) {
        if (project.scannedParts == null && !project.importComplete && part == project.latestSourcePart) {
            update { it.copy(guidedExportRevision = it.guidedExportRevision + 1) }
        }
    }

    fun releasePart(context: Context, part: SourcePart) {
        if (jobs.isNotEmpty()) return
        work(context, Work.STORAGE, "Removing downloaded footage") { app ->
            val project = state.value.project ?: return@work
            if (!project.canRelease(part)) return@work
            val shared = state.value.library.projects.any { owner ->
                owner.id != project.id && owner.availableParts.any { it.part.uri == part.uri }
            }
            check(!shared) { "This footage is also used in another project." }
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    val path = part.uri.path
                    check(part.uri.scheme == "file" && path != null) { "Only downloaded footage can be removed here." }
                    val imports = File(app.filesDir, "imports").canonicalFile
                    val source = File(path).canonicalFile
                    check(source.path.startsWith(imports.path + File.separator)) { "Only downloaded footage can be removed here." }
                    check(!source.exists() || source.isFile && source.delete()) { "Could not remove the downloaded footage." }
                }
                clearPlayback()
                updateProject(project.id) { owner ->
                    owner.copy(parts = owner.parts.map {
                        if (it.part == part) it.copy(highlights = null, released = true) else it
                    }, drafts = owner.drafts.filterValues { it.edit.part.uri != part.uri }, bookmarks = owner.bookmarks - part.uri)
                }
                captionMemos.removeAll { it.part == part }
                update { it.copy(transcripts = it.transcripts.filterValues { memo -> memo.part.uri != part.uri }, tracking = it.tracking.filterNot { memo -> memo.part == part }) }
                status("Downloaded footage removed")
            }
        }
    }

    fun export(context: Context, clips: List<ExportClip>, fromQueue: Boolean = false, finishing: SourcePart? = null) =
        work(context, Work.EXPORT, "Preparing export") { app ->
            val source = state.value.project ?: return@work
            if (clips.isEmpty() || clips.any { clip -> source.availableParts.none { it.part == clip.part } }) return@work
            update { it.copy(queueExportActive = fromQueue, finishingPart = finishing != null) }
            jobs[Work.ANALYZE]?.let { analysis ->
                resumeAnalysis = true
                describe(Work.ANALYZE, "Pausing analysis for export")
                analysis.cancelAndJoin()
            }
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
                                queue = if (fromQueue || finishing != null) project.queue - clip else project.queue,
                                exportedParts = project.exportedParts + clip.part.uri,
                                renderStats = (project.renderStats + (uri to stats)).toList().takeLast(24).toMap())
                        }
                    }
                }
                count++
            }
            if (finishing != null) {
                val project = state.value.project
                if (project?.id == source.id && project.queue.none { it.part == finishing }) offerNextPart(project, finishing)
            }
            if (count > 0) status(if (count == 1) "Saved to Movies/Clip Studio" else "$count clips saved to Movies/Clip Studio")
        }

    fun restoreDraftTranscript(projectId: String, draftId: String, memo: CaptionMemo) {
        if (state.value.project?.id != projectId || draftId in state.value.transcripts) return
        update { it.copy(transcripts = it.transcripts + (draftId to memo)) }
    }

    fun saveDraft(projectId: String, draftId: String, draft: ClipDraft, create: Boolean) {
        val project = state.value.library.projects.firstOrNull { it.id == projectId } ?: return
        val uri = draft.edit.part.uri
        val saved = project.drafts[draftId]
        if ((!create && saved == null) || project.availableParts.none { it.part.uri == uri }) return
        val activeMemo = state.value.transcripts[draftId].takeIf { state.value.project?.id == projectId }
        val updated = draft.copy(transcript = activeMemo ?: saved?.transcript ?: draft.transcript)
        if (saved == updated) return
        updateProject(projectId) { it.copy(drafts = it.drafts + (draftId to updated)) }
    }

    fun updateTranscript(draftId: String, index: Int, replace: (TranscriptSegment) -> List<TranscriptSegment>) {
        val memo = state.value.transcripts[draftId] ?: return
        val row = memo.words.getOrNull(index) ?: return
        val updated = memo.words.toMutableList().apply {
            removeAt(index)
            addAll(index, replace(row))
        }
        val edited = memo.copy(words = updated)
        val projectId = state.value.project?.id ?: return
        update { it.copy(transcripts = it.transcripts + (draftId to edited)) }
        updateProject(projectId) { owner ->
            val draft = owner.drafts[draftId] ?: return@updateProject owner
            owner.copy(drafts = owner.drafts + (draftId to draft.copy(transcript = edited)))
        }
    }

    fun saveFraming(projectId: String, framing: ProjectFraming) {
        updateProject(projectId) { it.copy(framing = framing) }
    }

    fun setBookmark(projectId: String, part: SourcePart, timeMs: Long, add: Boolean) {
        if (timeMs !in 0 until part.durationMs) return
        updateProject(projectId) { project ->
            val marks = project.bookmarks[part.uri].orEmpty()
            val updated = if (add) (marks + timeMs).distinct().sorted() else marks - timeMs
            project.copy(bookmarks = project.bookmarks + (part.uri to updated))
        }
    }

    fun queue(clip: ExportClip) {
        if (state.value.finishingPart || Work.STORAGE in jobs) return
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
        val previous = project.parts.indexOfFirst { it.released && !it.part.joined && it.part.offsetMs == part.offsetMs }
        val parts = if (project.scannedParts != null && previous >= 0 && !part.joined) {
            project.parts.toMutableList().apply { this[previous] = PartReview(part) }
        } else project.parts + PartReview(part)
        save(project.copy(parts = parts))
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
        update { it.copy(exports = project.exports, transcripts = emptyMap(), joined = null, guidedPart = null, status = "") }
        save(project)
    }

    fun savePreset(preset: CreatorPreset) {
        val library = state.value.library
        val presets = listOf(preset) + library.presets.filterNot { it.name.equals(preset.name, true) }
        persist(library.copy(presets = presets.take(12)))
    }

    private fun newProject(
        name: String, layout: Layout, link: String? = null, guided: Boolean = false, startMs: Long = 0,
        fromFull: Boolean = false, fastParts: Boolean = false, scanFirst: Boolean = false
    ) {
        autoAnalyze = true
        clearPlayback()
        update { it.copy(exports = emptyList(), transcripts = emptyMap(), joined = null, guidedPart = null, status = "") }
        save(StudioProject(name, layout, emptyList(), youtubeLink = link, guidedImport = guided,
            guidedStartMs = startMs, guidedFromFull = guided && fromFull, fastGuidedParts = guided && fastParts && !fromFull,
            scannedParts = if (scanFirst) emptyList() else null))
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

    private fun work(
        context: Context, key: Work, label: String, cancelStatus: String? = null, body: suspend (Context) -> Unit
    ) {
        if (jobs[key] != null || Work.STORAGE in jobs) return
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
                if (cancelStatus != null) {
                    status(cancelStatus)
                } else if (key == Work.IMPORT && state.value.project?.guidedImport == true) {
                    status("Download paused · saved pieces kept")
                } else if (key != Work.ANALYZE || !resumeAnalysis) status("Cancelled · completed work was kept")
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
                    if (state.value.project?.availableParts?.any { it.highlights == null } == true) analyze(app)
                }
                update {
                    if (key == Work.EXPORT) it.copy(jobs = it.jobs - key, exportingClip = null, queueExportActive = false, finishingPart = false)
                    else if (key == Work.IMPORT) it.copy(jobs = it.jobs - key, guidedDownload = null)
                    else it.copy(jobs = it.jobs - key)
                }
            }
        }
        jobs[key] = job
        job.start()
    }
}
