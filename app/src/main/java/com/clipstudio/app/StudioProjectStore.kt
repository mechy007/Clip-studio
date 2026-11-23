package com.clipstudio.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class PartReview(val part: SourcePart, val highlights: List<Highlight>? = null)
internal data class StudioProject(
    val name: String, val layout: Layout, val parts: List<PartReview>, val queue: List<ExportClip> = emptyList()
)

internal class StudioProjectStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "last-project.json"))

    fun load(): StudioProject? = runCatching {
        val root = JSONObject(String(file.readFully(), Charsets.UTF_8))
        val rows = root.getJSONArray("parts")
        val parts = (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val uri = Uri.parse(row.getString("uri"))
            if (uri.scheme == "file" && uri.path?.let { File(it).isFile } != true) return@mapNotNull null
            val part = SourcePart(uri, row.getLong("offset"), row.getLong("duration"))
            val highlights = row.optJSONArray("highlights")?.let { items ->
                (0 until items.length()).map { position ->
                    val item = items.getJSONObject(position)
                    Highlight(item.getLong("start"), item.getLong("end"), item.getInt("score"), item.getString("reason"))
                }
            }
            PartReview(part, highlights)
        }
        if (parts.isEmpty()) return@runCatching null
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
        StudioProject(root.getString("name"), Layout.valueOf(root.getString("layout")), parts, queue)
    }.getOrNull()

    fun save(project: StudioProject) {
        val rows = JSONArray()
        project.parts.forEach { review ->
            val part = review.part
            val row = JSONObject().put("uri", part.uri.toString()).put("offset", part.offsetMs)
                .put("duration", part.durationMs)
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
        val bytes = JSONObject().put("name", project.name).put("layout", project.layout.name)
            .put("parts", rows).put("queue", queue).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
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
        return ExportClip(part, getLong("start"), getLong("end"), Layout.valueOf(getString("layout")),
            getJSONArray("camera").crop(), getJSONArray("gameplay").crop(), getInt("width"), getBoolean("muted"),
            captions, CaptionPreset.valueOf(getString("preset")))
    }
}
