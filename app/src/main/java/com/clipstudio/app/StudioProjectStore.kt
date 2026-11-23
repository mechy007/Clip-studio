package com.clipstudio.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

internal data class PartReview(val part: SourcePart, val highlights: List<Highlight>? = null)
internal data class StudioProject(
    val name: String, val layout: Layout, val parts: List<PartReview>, val queue: List<ExportClip> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
    val youtubeLink: String? = null, val importComplete: Boolean = false,
    val mode: HighlightMode = HighlightMode.BALANCED, val exports: List<Uri> = emptyList(),
    val renderStats: Map<Uri, RenderStats> = emptyMap()
)

internal data class CreatorPreset(
    val name: String, val layout: Layout, val camera: Crop, val game: Crop,
    val width: Int, val captions: CaptionPreset, val muted: Boolean, val audio: AudioFinish
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
        val parts = (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val uri = Uri.parse(row.getString("uri"))
            if (uri.scheme == "file" && uri.path?.let { File(it).isFile } != true) return@mapNotNull null
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
            PartReview(part, highlights)
        }
        val sources = parts.associateBy { it.part.uri.toString() }
        val queue = root.optJSONArray("queue")?.let { items ->
            (0 until items.length()).mapNotNull { index ->
                runCatching restoreClip@{
                    val row = items.getJSONObject(index)
                    val part = sources[row.getString("uri")]?.part ?: return@restoreClip null
                    row.exportClip(part)
                }.getOrNull()
            }
        }.orEmpty()
        val exports = root.optJSONArray("exports") ?: JSONArray()
        val timings = root.optJSONObject("renderStats") ?: JSONObject()
        val renderStats = timings.keys().asSequence().mapNotNull { key ->
            val item = timings.optJSONObject(key) ?: return@mapNotNull null
            val encoder = item.optString("encoder").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Uri.parse(key) to RenderStats(encoder, item.optLong("prepare"), item.optLong("render"),
                item.optLong("save"), item.optBoolean("retry"))
        }.toMap()
        return StudioProject(root.getString("name"), Layout.valueOf(root.getString("layout")), parts, queue,
            root.optString("id", UUID.randomUUID().toString()),
            root.optString("youtube", "").takeIf { it.isNotEmpty() },
            root.optBoolean("complete"), HighlightMode.valueOf(root.optString("mode", HighlightMode.BALANCED.name)),
            (0 until exports.length()).map { Uri.parse(exports.getString(it)) }, renderStats)
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
                .put("format", part.formatId.orEmpty()).put("joined", part.joined)
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
            .put("parts", rows).put("queue", queue)
    }

    private fun CreatorPreset.json() = JSONObject().put("name", name).put("layout", layout.name)
        .put("camera", camera.json()).put("game", game.json()).put("width", width)
        .put("captions", captions.name).put("muted", muted).put("audio", audio.json())

    private fun JSONObject.creatorPreset() = CreatorPreset(getString("name"), Layout.valueOf(getString("layout")),
        getJSONArray("camera").crop(), getJSONArray("game").crop(), getInt("width"),
        CaptionPreset.valueOf(getString("captions")), getBoolean("muted"), audio())

    private fun AudioFinish.json() = JSONObject().put("normalize", normalize).put("fade", fade)
        .put("mix", mixTracks).put("voice", voice.toDouble()).put("game", game.toDouble())

    private fun JSONObject.audio(): AudioFinish {
        val audio = optJSONObject("audio") ?: return AudioFinish()
        return AudioFinish(audio.optBoolean("normalize"), audio.optBoolean("fade"), audio.optBoolean("mix"),
            audio.optDouble("voice", 1.0).toFloat(), audio.optDouble("game", .6).toFloat())
    }

    private fun ExportClip.json(): JSONObject {
        val rows = JSONArray()
        captions.forEach { cue ->
            val words = JSONArray()
            cue.words.forEach { words.put(JSONArray(listOf(it.startMs, it.endMs, it.word))) }
            rows.put(JSONObject().put("start", cue.startMs).put("end", cue.endMs).put("text", cue.text).put("words", words))
        }
        return JSONObject().put("uri", part.uri.toString()).put("start", start).put("end", end)
            .put("layout", layout.name).put("camera", camera.json()).put("gameplay", gameplay.json())
            .put("width", width).put("muted", muted).put("preset", preset.name).put("captions", rows)
            .put("audio", audio.json()).put("fast", fastExport).put("original", originalFraming)
            .put("tracking", JSONArray(facePath.map { JSONArray(listOf(it.timeMs, it.x.toDouble(), it.y.toDouble())) }))
    }

    private fun Crop.json() = JSONArray(listOf(x.toDouble(), y.toDouble(), zoom.toDouble()))

    private fun JSONArray.crop() = Crop(getDouble(0).toFloat(), getDouble(1).toFloat(), getDouble(2).toFloat())

    private fun JSONObject.exportClip(part: SourcePart): ExportClip {
        val rows = getJSONArray("captions")
        val captions = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val words = row.getJSONArray("words")
            CaptionCue(row.getLong("start"), row.getLong("end"), row.getString("text"),
                (0 until words.length()).map { item ->
                    val word = words.getJSONArray(item)
                    TimedWord(word.getLong(0), word.getLong(1), word.getString(2))
                })
        }
        val tracking = optJSONArray("tracking") ?: JSONArray()
        val path = (0 until tracking.length()).map { index ->
            val point = tracking.getJSONArray(index)
            FacePoint(point.getLong(0), point.getDouble(1).toFloat(), point.getDouble(2).toFloat())
        }
        return ExportClip(part, getLong("start"), getLong("end"), Layout.valueOf(getString("layout")),
            getJSONArray("camera").crop(), getJSONArray("gameplay").crop(), getInt("width"), getBoolean("muted"),
            captions, CaptionPreset.valueOf(getString("preset")), audio(), path, optBoolean("fast"), optBoolean("original"))
    }
}
