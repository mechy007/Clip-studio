package com.clipstudio.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.util.Locale

private val StudioColors = darkColorScheme(
    primary = Color(0xFFFF826C), onPrimary = Color(0xFF31110D),
    secondary = Color(0xFF78EACB), background = Color(0xFF101318),
    surface = Color(0xFF101318), surfaceVariant = Color(0xFF232A32),
    onSurface = Color(0xFFF4F2EF), onSurfaceVariant = Color(0xFFBDC6CF),
    outline = Color(0xFF6F7983)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StudioSession.initialize(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = StudioColors) { Dashboard() }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Dashboard() {
        val state by StudioSession.state.collectAsState()
        val project = state.project
        var link by rememberSaveable { mutableStateOf("") }
        var incomingLayout by rememberSaveable { mutableStateOf(Layout.Split) }
        var selectedPart by rememberSaveable { mutableStateOf<String?>(null) }
        var chosenMoment by rememberSaveable { mutableStateOf(-1L) }
        var chosenEnd by rememberSaveable { mutableStateOf(-1L) }
        var momentSelection by rememberSaveable { mutableStateOf(0) }
        val editorAnchor = remember { BringIntoViewRequester() }
        var notificationAsked by rememberSaveable { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        fun showNotificationPermission() {
            if (!notificationAsked && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationAsked = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                selectedPart = null
                chosenMoment = -1L
                StudioSession.importLocal(this, uri, displayName(uri), incomingLayout)
                showNotificationPermission()
            }
        }
        val occupied = state.jobs.isNotEmpty()
        var projectsOpen by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(state.joined?.uri) {
            state.joined?.let {
                selectedPart = it.uri.toString()
                chosenMoment = -1L
            }
        }
        val exporting = Work.EXPORT in state.jobs
        val selected = project?.parts?.find { it.part.uri.toString() == selectedPart }
            ?: project?.parts?.firstOrNull()
        Scaffold { inset ->
            Column(
                Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.studio_mark), null, Modifier.size(52.dp))
                    Text("Clip Studio", style = MaterialTheme.typography.headlineMedium)
                }
                if (state.library.projects.isNotEmpty()) {
                    OutlinedButton(onClick = { projectsOpen = !projectsOpen }) {
                        Text(if (projectsOpen) "Hide projects" else "Projects · ${state.library.projects.size}")
                    }
                    if (projectsOpen) state.library.projects.forEach { item ->
                        FilterChip(selected = project?.id == item.id, enabled = !occupied, onClick = {
                            selectedPart = null
                            chosenMoment = -1L
                            incomingLayout = item.layout
                            StudioSession.openProject(item.id)
                            projectsOpen = false
                        }, label = { Text("${item.name} · ${item.exports.size} exports") })
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Import a recording", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = link, onValueChange = { link = it }, singleLine = true,
                            enabled = !occupied, label = { Text("YouTube recording URL") }, modifier = Modifier.fillMaxWidth()
                        )
                        Text("Starting layout", style = MaterialTheme.typography.labelLarge)
                        LayoutPicker(incomingLayout, !occupied) { incomingLayout = it }
                        Button(
                            enabled = !occupied && YouTubeLinkClassifier.isYouTubeUrl(link),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                selectedPart = null
                                chosenMoment = -1L
                                StudioSession.importYouTube(this@MainActivity, link.trim(), incomingLayout)
                                showNotificationPermission()
                            }
                        ) { Text("Import & find highlights") }
                        OutlinedButton(enabled = !occupied, modifier = Modifier.fillMaxWidth(),
                            onClick = { picker.launch(arrayOf("video/*")) }) { Text("Open a video from your phone") }
                    }
                }
                if (occupied) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    state.jobs.forEach { (work, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                            TextButton(onClick = { StudioSession.cancel(work) }) { Text("Cancel") }
                        }
                    }
                }
                if (state.status.isNotBlank()) Text(state.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (project?.youtubeLink != null && !project.importComplete && Work.IMPORT !in state.jobs) {
                    OutlinedButton(onClick = {
                        StudioSession.resume(this@MainActivity)
                        showNotificationPermission()
                    }) { Text("Resume download") }
                }
                if (project != null && selected != null) {
                    Text(project.name, style = MaterialTheme.typography.titleLarge)
                    if (project.parts.size > 1) {
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) {
                            Text("${project.parts.size} ready sections · ${timestamp(selected.part.offsetMs)} selected")
                        }
                        if (expanded) project.parts.forEach { review ->
                            val part = review.part
                            FilterChip(
                                selected = review == selected, onClick = {
                                    chosenMoment = -1L
                                    selectedPart = part.uri.toString()
                                },
                                enabled = !exporting, label = {
                                    Text("${timestamp(part.offsetMs)} – ${timestamp(part.offsetMs + part.durationMs)}")
                                }
                            )
                        }
                    }
                    LayoutPicker(project.layout, !exporting, StudioSession::changeLayout)
                    val next = project.parts.map { it.part }.firstOrNull { adjacent(selected.part, it) }
                    if (next != null) {
                        OutlinedButton(enabled = Work.JOIN !in state.jobs, onClick = {
                            StudioSession.join(this@MainActivity, selected.part, next)
                            showNotificationPermission()
                        }) { Text("Edit across next section") }
                    }
                    Text("Highlight focus", style = MaterialTheme.typography.titleSmall)
                    HighlightMode.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { mode ->
                                FilterChip(selected = project.mode == mode, enabled = Work.ANALYZE !in state.jobs,
                                    onClick = {
                                        StudioSession.setMode(this@MainActivity, mode)
                                        showNotificationPermission()
                                    }, label = { Text(mode.label) })
                            }
                        }
                    }
                    if (project.parts.any { it.highlights == null } && Work.ANALYZE !in state.jobs) {
                        OutlinedButton(onClick = { StudioSession.analyze(this@MainActivity) }) { Text("Find highlights") }
                    }
                    val suggestions = project.parts.flatMap { review ->
                        review.highlights.orEmpty().map { review.part to it }
                    }.sortedByDescending { it.second.score }.take(10)
                    if (suggestions.isNotEmpty()) {
                        Text("Suggested highlights", style = MaterialTheme.typography.titleLarge)
                        suggestions.forEach { (part, highlight) ->
                            Card(onClick = {
                                selectedPart = part.uri.toString()
                                chosenMoment = highlight.startMs
                                chosenEnd = highlight.endMs
                                momentSelection++
                            }, enabled = !exporting) {
                                Column(Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${timestamp(part.offsetMs + highlight.startMs)} – ${timestamp(part.offsetMs + highlight.endMs)}",
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(highlight.reason, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    } else if (project.parts.all { it.highlights != null }) {
                        Text("No strong highlight signals found. You can still trim a section manually.")
                    }
                    Text("Edit clip", Modifier.bringIntoViewRequester(editorAnchor),
                        style = MaterialTheme.typography.titleLarge)
                    LaunchedEffect(momentSelection) {
                        if (momentSelection > 0 && chosenMoment >= 0) editorAnchor.bringIntoView()
                    }
                    key(selected.part.uri) {
                        ClipEditor(selected.part, project.layout, state, chosenMoment, chosenEnd, momentSelection,
                            ::showNotificationPermission)
                    }
                }
                if (state.queue.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Export queue", style = MaterialTheme.typography.titleLarge)
                    state.queue.forEachIndexed { index, clip ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${timestamp(clip.part.offsetMs + clip.start)} · ${clip.layout.label}", Modifier.weight(1f))
                            TextButton(enabled = !exporting, onClick = { StudioSession.removeQueued(index) }) { Text("Remove") }
                        }
                    }
                    Button(
                        enabled = !exporting,
                        onClick = {
                            StudioSession.export(this@MainActivity, state.queue, fromQueue = true)
                            showNotificationPermission()
                        }
                    ) { Text("Export ${state.queue.size} clips") }
                }
                if (state.exports.isNotEmpty()) {
                    Text("${state.exports.size} MP4 ${if (state.exports.size == 1) "ready" else "files ready"}",
                        style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val play = Intent(Intent.ACTION_VIEW).setDataAndType(state.exports.last(), "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { startActivity(Intent.createChooser(play, "Open exported clip")) }
                        }) { Text("Open MP4") }
                        OutlinedButton(onClick = { share(state.exports) }) {
                            Text("Share ${if (state.exports.size == 1) "MP4" else "clips"}")
                        }
                    }
                }
            }
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { rows ->
            if (rows.moveToFirst()) rows.getString(0) else null
        }
    }.getOrNull() ?: "Imported recording"

    private fun share(uris: List<Uri>) {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = "video/mp4"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share from Clip Studio"))
    }
}

@Composable
private fun LayoutPicker(layout: Layout, enabled: Boolean, select: (Layout) -> Unit) {
    Layout.entries.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { option ->
                FilterChip(selected = layout == option, onClick = { select(option) },
                    enabled = enabled, label = { Text(option.label) })
            }
        }
    }
}

internal fun timestamp(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
