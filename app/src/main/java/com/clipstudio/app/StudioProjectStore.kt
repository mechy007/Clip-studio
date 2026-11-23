package com.clipstudio.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

internal data class PartReview(
    val part: SourcePart, val highlights: List<Highlight>? = null, val released: Boolean = false
)
internal data class StudioProject(
    val name: String, val layout: Layout, val parts: List<PartReview>, val queue: List<ExportClip> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
    val youtubeLink: String? = null, val importComplete: Boolean = false,
    val mode: HighlightMode = HighlightMode.BALANCED, val exports: List<Uri> = emptyList(),
    val renderStats: Map<Uri, RenderStats> = emptyMap(),
    val guidedImport: Boolean = false,
    val guidedStartMs: Long = 0,
    val drafts: Map<String, ClipDraft> = emptyMap(),
    val exportedParts: Set<Uri> = emptySet(),
    val framing: ProjectFraming? = null,
    val bookmarks: Map<Uri, List<Long>> = emptyMap(),
    val guidedFromFull: Boolean = false,
    val fastGuidedParts: Boolean = false
) {
    val availableParts: List<PartReview> get() = parts.filterNot { it.released }

    fun canRelease(part: SourcePart): Boolean =
        guidedImport && !part.joined && part.uri in exportedParts &&
            availableParts.any { it.part == part } && queue.none { it.part == part }

    val latestSourcePart: SourcePart? get() = parts.asSequence().map { it.part }
        .filter { !it.joined }.maxByOrNull { it.nextOffsetMs }
}

internal data class ClipDraft(
    val edit: ExportClip,
    val captionEnabled: Boolean,
    val customCaptions: Boolean,
    val customText: String,
    val audioTrack: Int,
    val language: String?,
    val transcript: CaptionMemo?,
    val overlays: List<CaptionCue> = emptyList()
)

internal data class CreatorPreset(
    val name: String, val layout: Layout, val camera: Crop, val game: Crop,
    val width: Int, val captions: CaptionPreset, val muted: Boolean, val audio: AudioFinish,
    val captionPosition: CaptionPosition = CaptionPosition.LOWER,
    val captionSize: Int = 100
)

internal data class StudioLibrary(
    val projects: List<StudioProject> = emptyList(), val currentId: String? = null,
    val presets: List<CreatorPreset> = emptyList()
)


internal class StudioProjectStore(context: Context) {
    private val base = File(context.filesDir, "last-project.json")
    private val file = AtomicFile(base)

    fun load(): StudioLibrary {
        val bytes = try {
            file.readFully()
        } catch (missing: FileNotFoundException) {
            if (base.exists() || File(base.path + ".bak").exists()) throw missing
            return StudioLibrary()
        }
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val saved = root.optJSONArray("projects")
        return if (saved == null) {
            val legacy = readProject(root)
            StudioLibrary(listOf(legacy), legacy.id)
        } else {
            val projects = (0 until saved.length()).map { index -> readProject(saved.getJSONObject(index)) }
            val presets = root.optJSONArray("presets") ?: JSONArray()
            StudioLibrary(projects, root.optString("current", "").takeIf { it.isNotEmpty() },
                (0 until presets.length()).map { index -> presets.getJSONObject(index).creatorPreset() })
        }
    }

    private fun readProject(root: JSONObject): StudioProject {
        val rows = root.getJSONArray("parts")
        val guided = root.optBoolean("guidedImport")
        val parts = (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val uri = Uri.parse(row.getString("uri"))
            val missing = uri.scheme == "file" && uri.path?.let { File(it).isFile } != true
            val released = row.optBoolean("released") || (guided && missing)
            if (missing && !released) return@mapNotNull null
            val offset = row.getLong("offset")
            val duration = row.getLong("duration")
            val part = SourcePart(uri, offset, duration, row.optLong("next", offset + duration),
                row.optString("format", "").takeIf { it.isNotEmpty() }, row.optBoolean("joined"))
            val highlights = row.optJSONArray("highlights")?.let { items ->
                (0 until items.length()).map { position ->
                    val item = items.getJSONObject(position)
                    Highlight(item.getLong("start"), item.getLong("end"), item.getInt("score"), item.getString("reason"))
                }
            }
            PartReview(part, if (released) null else highlights, released)
        }
        val sources = parts.filterNot { it.released }.associateBy { it.part.uri.toString() }
        val queue = root.optJSONArray("queue")?.let { items ->
            (0 until items.length()).mapNotNull { index ->
                runCatching restoreClip@{
                    val row = items.getJSONObject(index)
                    val part = sources[row.getString("uri")]?.part ?: return@restoreClip null
                    row.exportClip(part)
                }.getOrNull()
            }
        }.orEmpty()
        val drafts = root.optJSONArray("drafts")?.let { items ->
            (0 until items.length()).mapNotNull { index ->
                runCatching {
                    val row = items.getJSONObject(index)
                    val edit = row.getJSONObject("edit")
                    val part = sources[edit.getString("uri")]?.part ?: return@runCatching null
                    row.optString("id", part.uri.toString()) to row.draft(edit.exportClip(part))
                }.getOrNull()
            }.toMap()
        }.orEmpty()
        val exports = root.optJSONArray("exports") ?: JSONArray()
        val timings = root.optJSONObject("renderStats") ?: JSONObject()
        val renderStats = timings.keys().asSequence().mapNotNull { key ->
            val item = timings.optJSONObject(key) ?: return@mapNotNull null
            val encoder = item.optString("encoder").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Uri.parse(key) to RenderStats(encoder, item.optLong("prepare"), item.optLong("render"),
                item.optLong("save"), item.optBoolean("retry"))
        }.toMap()
        val exportedParts = root.optJSONArray("exportedParts")?.let { entries ->
            (0 until entries.length()).map { Uri.parse(entries.getString(it)) }.toSet()
        }.orEmpty()
        val framing = root.optJSONObject("framing")?.let { row ->
            runCatching { ProjectFraming(Layout.valueOf(row.getString("layout")),
                row.getJSONArray("camera").crop(), row.getJSONArray("gameplay").crop()) }.getOrNull()
        }
        val bookmarks = root.optJSONObject("bookmarks")?.let { rows ->
            rows.keys().asSequence().mapNotNull { key ->
                val part = sources[key]?.part ?: return@mapNotNull null
                val values = rows.optJSONArray(key) ?: return@mapNotNull null
                part.uri to (0 until values.length()).map { values.optLong(it, -1) }
                    .filter { it in 0 until part.durationMs }.distinct().sorted()
            }.toMap()
        }.orEmpty()
        return StudioProject(root.getString("name"), Layout.valueOf(root.getString("layout")), parts, queue,
            root.optString("id", UUID.randomUUID().toString()),
            root.optString("youtube", "").takeIf { it.isNotEmpty() },
            root.optBoolean("complete"), HighlightMode.valueOf(root.optString("mode", HighlightMode.BALANCED.name)),
            (0 until exports.length()).map { Uri.parse(exports.getString(it)) }, renderStats, guided, root.optLong("guidedStartMs"), drafts, exportedParts, framing, bookmarks, root.optBoolean("guidedFromFull"), root.optBoolean("fastGuidedParts"))
    }

    fun save(library: StudioLibrary) {
        val root = JSONObject()
            .put("current", library.currentId.orEmpty())
            .put("projects", JSONArray(library.projects.map(::projectJson)))
            .put("presets", JSONArray(library.presets.map { it.json() }))
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun projectJson(project: StudioProject): JSONObject {
        val rows = JSONArray()
        project.parts.forEach { review ->
            val part = review.part
            val row = JSONObject().put("uri", part.uri.toString()).put("offset", part.offsetMs)
                .put("duration", part.durationMs).put("next", part.nextOffsetMs)
                .put("format", part.formatId.orEmpty()).put("joined", part.joined).put("released", review.released)
            review.highlights?.let { highlights ->
                val items = JSONArray()
                highlights.forEach { items.put(JSONObject().put("start", it.startMs).put("end", it.endMs)
                    .put("score", it.score).put("reason", it.reason)) }
                row.put("highlights", items)
            }
            rows.put(row)
        }
        val queue = JSONArray()
        project.queue.forEach { queue.put(it.json()) }
        val timings = JSONObject()
        project.renderStats.forEach { (uri, stats) ->
            timings.put(uri.toString(), JSONObject().put("encoder", stats.encoder).put("prepare", stats.preparationMs)
                .put("render", stats.renderMs).put("save", stats.saveMs).put("retry", stats.retried))
        }
        return JSONObject().put("name", project.name).put("layout", project.layout.name)
            .put("id", project.id).put("youtube", project.youtubeLink.orEmpty()).put("complete", project.importComplete)
            .put("mode", project.mode.name).put("exports", JSONArray(project.exports.map(Uri::toString))).put("renderStats", timings)
            .put("parts", rows).put("queue", queue).put("guidedImport", project.guidedImport)
            .put("exportedParts", JSONArray(project.exportedParts.map(Uri::toString)))
            .put("guidedStartMs", project.guidedStartMs).put("guidedFromFull", project.guidedFromFull)
            .put("fastGuidedParts", project.fastGuidedParts)
            .put("drafts", JSONArray(project.drafts.map { (id, draft) -> draft.json().put("id", id) }))
            .put("framing", project.framing?.let {
                JSONObject().put("layout", it.layout.name).put("camera", it.camera.json()).put("gameplay", it.gameplay.json())
            })
            .put("bookmarks", JSONObject().apply {
                project.bookmarks.forEach { (uri, marks) -> put(uri.toString(), JSONArray(marks)) }
            })
    }

    private fun CreatorPreset.json() = JSONObject().put("name", name).put("layout", layout.name)
        .put("camera", camera.json()).put("game", game.json()).put("width", width)
        .put("captions", captions.name).put("muted", muted).put("audio", audio.json())
        .put("captionPosition", captionPosition.name).put("captionSize", captionSize)

    private fun JSONObject.creatorPreset() = CreatorPreset(getString("name"), Layout.valueOf(getString("layout")),
        getJSONArray("camera").crop(), getJSONArray("game").crop(), getInt("width"),
        CaptionPreset.valueOf(getString("captions")), getBoolean("muted"), audio(), captionPosition(), optInt("captionSize", 100))

    private fun AudioFinish.json() = JSONObject().put("normalize", normalize).put("fade", fade)
        .put("mix", mixTracks).put("voice", voice.toDouble()).put("game", game.toDouble())

    private fun JSONObject.audio(): AudioFinish {
        val audio = optJSONObject("audio") ?: return AudioFinish()
        return AudioFinish(audio.optBoolean("normalize"), audio.optBoolean("fade"), audio.optBoolean("mix"),
            audio.optDouble("voice", 1.0).toFloat(), audio.optDouble("game", .6).toFloat())
    }

    private fun ClipDraft.json(): JSONObject = JSONObject().put("edit", edit.json())
        .put("captionEnabled", captionEnabled).put("customCaptions", customCaptions).put("customText", customText)
        .put("audioTrack", audioTrack).put("language", language ?: JSONObject.NULL).put("overlays", overlays.json()).apply {
            transcript?.let { memo ->
                put("transcript", JSONObject().put("start", memo.start).put("end", memo.end)
                    .put("track", memo.audioTrack).put("language", memo.language ?: JSONObject.NULL)
                    .put("words", memo.words.map { CaptionCue(it.startMs, it.endMs, it.text, it.words) }.json()))
            }
        }

    private fun JSONObject.draft(edit: ExportClip): ClipDraft {
        val transcript = optJSONObject("transcript")?.let { row ->
            CaptionMemo(edit.part, row.getLong("start"), row.getLong("end"),
                row.optString("language").takeUnless { row.isNull("language") },
                row.getJSONArray("words").cues().map { TranscriptSegment(it.startMs, it.endMs, it.text, it.words) },
                row.getInt("track"))
        }
        return ClipDraft(edit, optBoolean("captionEnabled", true), optBoolean("customCaptions"),
            optString("customText"), optInt("audioTrack"),
            optString("language").takeUnless { isNull("language") }, transcript, optJSONArray("overlays")?.cues().orEmpty())
    }

    private fun List<CaptionCue>.json(): JSONArray {
        val rows = JSONArray()
        forEach { cue ->
            val words = JSONArray()
            cue.words.forEach { words.put(JSONArray(listOf(it.startMs, it.endMs, it.word))) }
            rows.put(JSONObject().put("start", cue.startMs).put("end", cue.endMs).put("text", cue.text).put("words", words))
        }
        return rows
    }

    private fun ExportClip.json(): JSONObject =
        JSONObject().put("uri", part.uri.toString()).put("start", start).put("end", end)
            .put("layout", layout.name).put("camera", camera.json()).put("gameplay", gameplay.json())
            .put("width", width).put("muted", muted).put("preset", preset.name).put("captions", captions.json())
            .put("audio", audio.json()).put("fast", fastExport).put("original", originalFraming).put("detail", highDetail)
            .put("enhanceFacecam", enhanceFacecam).put("captionPosition", captionPosition.name).put("captionSize", captionSize)
            .put("enhancement", enhancement.name)
            .put("tracking", JSONArray(facePath.map { JSONArray(listOf(it.timeMs, it.x.toDouble(), it.y.toDouble())) }))

    private fun Crop.json() = JSONArray(listOf(x.toDouble(), y.toDouble(), zoom.toDouble()))

    private fun JSONArray.crop() = Crop(getDouble(0).toFloat(), getDouble(1).toFloat(), getDouble(2).toFloat())

    private fun JSONArray.cues(): List<CaptionCue> =
        (0 until length()).map { index ->
            val row = getJSONObject(index)
            val words = row.getJSONArray("words")
            CaptionCue(row.getLong("start"), row.getLong("end"), row.getString("text"),
                (0 until words.length()).map { item ->
                    val word = words.getJSONArray(item)
                    TimedWord(word.getLong(0), word.getLong(1), word.getString(2))
                })
        }
    private fun JSONObject.exportClip(part: SourcePart): ExportClip {
        val captions = getJSONArray("captions").cues()
        val tracking = optJSONArray("tracking") ?: JSONArray()
        val path = (0 until tracking.length()).map { index ->
            val point = tracking.getJSONArray(index)
            FacePoint(point.getLong(0), point.getDouble(1).toFloat(), point.getDouble(2).toFloat())
        }
        return ExportClip(part, getLong("start"), getLong("end"), Layout.valueOf(getString("layout")),
            getJSONArray("camera").crop(), getJSONArray("gameplay").crop(), getInt("width"), getBoolean("muted"),
            captions, CaptionPreset.valueOf(getString("preset")), audio(), path, optBoolean("fast"), optBoolean("original"),
            optBoolean("detail"), optBoolean("enhanceFacecam"), captionPosition(), optInt("captionSize", 100),
            VideoEnhancement.entries.firstOrNull { it.name == optString("enhancement") } ?: VideoEnhancement.OFF)
    }

    private fun JSONObject.captionPosition(): CaptionPosition {
        val name = optString("captionPosition")
        return CaptionPosition.entries.firstOrNull { it.name == name } ?: CaptionPosition.LOWER
    }
}
