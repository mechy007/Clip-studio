package com.clipstudio.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun CreatorSkills(project: StudioProject?, source: SourcePart?) {
    var opened by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { opened = true }, modifier = Modifier.fillMaxWidth()) { Text("YouTube skills") }
    if (opened) Dialog(onDismissRequest = { opened = false }) {
        Surface(shape = MaterialTheme.shapes.large) { CreatorWorkspace(project, source) { opened = false } }
    }
}

@Composable
private fun CreatorWorkspace(project: StudioProject?, source: SourcePart?, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("creator-profile", 0) }
    var skill by remember { mutableStateOf("shorts") }
    var menu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(prefs.getString("model", "claude-sonnet-4-6").orEmpty()) }
    var voice by remember { mutableStateOf(prefs.getString("voice", "").orEmpty()) }
    var research by remember { mutableStateOf(false) }
    var data by remember(skill) { mutableStateOf("") }
    var extra by remember(skill) { mutableStateOf("") }
    var seconds by remember(skill) { mutableStateOf("") }
    var output by remember(skill) { mutableStateOf("") }
    var history by remember(skill) { mutableStateOf(JSONArray()) }
    var busy by remember { mutableStateOf(false) }
    var importingExtra by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val value = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not open the selected file." }.use { stream ->
                        val buffer = ByteArray(512 * 1024 + 1)
                        var size = 0
                        while (size < buffer.size) {
                            val count = stream.read(buffer, size, buffer.size - size)
                            if (count <= 0) break
                            size += count
                        }
                        require(size < buffer.size) { "Choose a text file under 512 KB." }
                        String(buffer, 0, size, Charsets.UTF_8)
                    }
                }
                require(value.isNotBlank() && '\u0000' !in value && '\uFFFD' !in value) { "Choose a nonempty UTF-8 text file." }
                if (importingExtra) extra = value.removePrefix("\uFEFF") else data = value.removePrefix("\uFEFF")
            } catch (e: Exception) { output = e.localizedMessage.orEmpty() }
            finally { busy = false }
        }
    }
    var image by remember(skill) { mutableStateOf<JSONObject?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                image = withContext(Dispatchers.IO) {
                    val mime = context.contentResolver.getType(uri)
                    require(mime in listOf("image/jpeg", "image/png", "image/webp")) { "Choose a JPEG, PNG or WebP image." }
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not open the selected image." }.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(16384)
                        while (true) {
                            val n = stream.read(chunk)
                            if (n < 0) break
                            require(buffer.size() + n <= 4 * 1024 * 1024) { "Choose an image under 4 MB." }
                            buffer.write(chunk, 0, n)
                        }
                        buffer.toByteArray()
                    }
                    JSONObject().put("type", "base64").put("media_type", mime)
                        .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                }
            } catch (e: Exception) { output = e.localizedMessage.orEmpty() }
            finally { busy = false }
        }
    }
    val hint = when (skill) {
        "script" -> "Video idea, or one hook for the local scorer"
        "package" -> "Title or video brief"
        "edit", "chapters", "shorts" -> "Timestamped transcript"
        "comment" -> "Comments to review"
        "plan" -> "Channel goals and available hours"
        "viral" -> "Niche to research, or collected videos as JSON"
        "retention" -> "YouTube Studio retention CSV"
        "seo" -> "Video topic or transcript"
        else -> "Channel URL or channel data"
    }
    fun execute(local: Boolean) {
        scope.launch {
            busy = true
            try {
                val duration = seconds.takeIf { it.isNotBlank() }?.toDoubleOrNull() ?: 0.0
                require(seconds.isBlank() || (duration.isFinite() && duration > 0)) { "Enter a positive duration in seconds." }
                if (local) output = withContext(Dispatchers.IO) {
                    val result = CreatorAgent.analyze(context, skill, data, extra, duration)
                    if (skill == "chapters") {
                        val report = JSONObject(result)
                        val chapters = report.getJSONArray("chapters")
                        val lines = (0 until chapters.length()).map { i ->
                            val chapter = chapters.getJSONObject(i)
                            chapter.getString("label") + " " + chapter.getString("draft_title")
                        }
                        if (report.getBoolean("valid")) lines.joinToString("\n")
                        else "Draft only. This clip needs at least three chapters of ten seconds each.\n\n" +
                            lines.joinToString("\n")
                    } else result
                } else {
                    val (next, answer) = withContext(Dispatchers.IO) {
                        CreatorAgent.run(context, skill, voice, key.trim(), model.trim(), history, data, extra, duration, research, image)
                    }
                    image = null
                    history = next
                    output = answer
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { output = e.localizedMessage ?: "Skill failed" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { Text("YouTube skills", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = close) { Text("Close") } }
        Box {
            OutlinedButton(enabled = !busy, onClick = { menu = true }) { Text(creatorSkills.getValue(skill) + " ▾") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                creatorSkills.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { skill = id; menu = false })
                }
            }
        }
        TextButton(onClick = { settings = !settings }) { Text("Claude & creator voice") }
        if (settings) {
            Text("Running with Claude sends your input, creator voice and this conversation to Anthropic. API charges may apply.")
            OutlinedTextField(key, { key = it }, label = { Text("Anthropic API key for this session") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(model, { model = it; prefs.edit().putString("model", it).apply() },
                label = { Text("Claude model ID") }, singleLine = true)
            OutlinedTextField(voice, { voice = it; prefs.edit().putString("voice", it).apply() },
                label = { Text("Creator voice") }, maxLines = 6)
            if (voice.isBlank()) TextButton(onClick = {
                voice = CreatorAgent.asset(context, "templates/voice.md")
                prefs.edit().putString("voice", voice).apply()
            }) { Text("Use voice template") }
            Row { Checkbox(research, { research = it }); Text("Allow public web research") }
        }
        OutlinedTextField(data, { data = it }, enabled = !busy, label = { Text(hint) }, maxLines = 6)
        TextButton(enabled = !busy, onClick = { importingExtra = false; picker.launch(arrayOf("*/*")) }) { Text("Import text file") }
        val transcripts = remember(project?.drafts, source) {
            project?.drafts?.values.orEmpty().mapNotNull { it.transcript }
                .filter { it.part.uri == source?.uri && it.words.isNotEmpty() }
                .distinct().sortedBy { it.start }
        }
        var chooseTranscript by remember { mutableStateOf(false) }
        if (transcripts.isNotEmpty()) Box {
            TextButton(enabled = !busy, onClick = { chooseTranscript = true }) { Text("Choose saved transcript") }
            DropdownMenu(expanded = chooseTranscript, onDismissRequest = { chooseTranscript = false }) {
                transcripts.forEach { memo ->
                    DropdownMenuItem(text = {
                        Text("${timestamp(memo.part.offsetMs + memo.start)} – ${timestamp(memo.part.offsetMs + memo.end)} · Track ${memo.audioTrack + 1}")
                    }, onClick = {
                        val rows = JSONArray()
                        memo.words.forEach { segment ->
                            rows.put(JSONObject().put("start", (segment.startMs - memo.start).coerceAtLeast(0) / 1000.0)
                                .put("end", (segment.endMs - memo.start).coerceAtLeast(0) / 1000.0)
                                .put("text", segment.text))
                        }
                        val json = JSONObject().put("segments", rows).toString()
                        if (skill == "retention") {
                            extra = json
                            seconds = ((memo.end - memo.start) / 1000.0).toString()
                        } else data = json
                        chooseTranscript = false
                    })
                }
            }
        }
        OutlinedTextField(extra, { extra = it }, enabled = !busy, maxLines = 4,
            label = { Text(when (skill) { "package" -> "Thumbnail wording"; "retention" -> "Optional transcript"; else -> "Additional context" }) })
        if (skill in listOf("package", "audit")) {
            TextButton(enabled = !busy, onClick = {
                if (image != null) image = null else imagePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
            }) { Text(if (image == null) "Attach thumbnail for Claude" else "Remove attached thumbnail") }
        }
        if (skill == "retention") {
            TextButton(enabled = !busy, onClick = { importingExtra = true; picker.launch(arrayOf("*/*")) }) { Text("Import transcript") }
            OutlinedTextField(seconds, { seconds = it }, enabled = !busy, label = { Text("Video duration in seconds") })
        }
        if (skill in localCreatorTools) OutlinedButton(enabled = !busy && data.isNotBlank(), onClick = { execute(true) }) { Text("Run local tool") }
        Button(enabled = !busy && data.isNotBlank(), onClick = {
            if (key.isBlank() || model.isBlank()) settings = true else execute(false)
        }) { Text(if (key.isBlank() || model.isBlank()) "Set up Claude" else if (history.length() == 0) "Send to Claude" else "Send follow-up") }
        if (busy) LinearProgressIndicator()
        if (output.isNotEmpty()) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(output)) }) { Text("Copy result") }
            SelectionContainer { Text(output) }
        }
        if (history.length() > 0) TextButton(enabled = !busy, onClick = { history = JSONArray(); output = "" }) { Text("New conversation") }
    }
}
