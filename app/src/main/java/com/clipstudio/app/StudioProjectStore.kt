package com.clipstudio.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class PartReview(val part: SourcePart, val highlights: List<Highlight>? = null)
internal data class StudioProject(val name: String, val layout: Layout, val parts: List<PartReview>)

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
        if (parts.isEmpty()) null else StudioProject(root.getString("name"), Layout.valueOf(root.getString("layout")), parts)
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
        val bytes = JSONObject().put("name", project.name).put("layout", project.layout.name)
            .put("parts", rows).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
