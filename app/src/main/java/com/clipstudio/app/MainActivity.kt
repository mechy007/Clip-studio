package com.clipstudio.app

import android.Manifest
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.text.style.TextOverflow
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
    primary = Color(0xFFA78BFA), onPrimary = Color(0xFF19102D),
    secondary = Color(0xFF6EE7B7), background = Color(0xFF0B0C10),
    surface = Color(0xFF12141C), surfaceVariant = Color(0xFF191C27),
    primaryContainer = Color(0xFF2E1F5E), onPrimaryContainer = Color(0xFFDDD6FE),
    secondaryContainer = Color(0xFF2E1F5E), onSecondaryContainer = Color(0xFFDDD6FE),
    onSurface = Color(0xFFF1F5F9), onSurfaceVariant = Color(0xFFA9B4C8),
    outline = Color(0xFF606A85), outlineVariant = Color(0xFF2D3348)
)

private enum class StudioTab(val title: String, val icon: Int) {
    Editor("Editor", R.drawable.nav_editor),
    Import("Import", R.drawable.nav_import),
    Highlights("Highlights", R.drawable.nav_highlights),
    Projects("Projects", R.drawable.nav_projects)
}

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
        var tab by rememberSaveable { mutableStateOf(StudioTab.Editor) }
        var exportRequest by rememberSaveable { mutableStateOf(0) }
        val pages = rememberSaveableStateHolder()
        var link by rememberSaveable { mutableStateOf("") }
        var incomingLayout by rememberSaveable { mutableStateOf(Layout.Split) }
        var selectedPart by rememberSaveable { mutableStateOf<String?>(null) }
        var chosenMoment by rememberSaveable { mutableStateOf(-1L) }
        var chosenEnd by rememberSaveable { mutableStateOf(-1L) }
        var momentSelection by rememberSaveable { mutableStateOf(0) }
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
                tab = StudioTab.Import
                StudioSession.importLocal(this, uri, displayName(uri), incomingLayout)
                showNotificationPermission()
            }
        }
        val occupied = state.jobs.isNotEmpty()
        LaunchedEffect(state.joinRevision) {
            state.joined?.let {
                selectedPart = it.uri.toString()
                chosenMoment = -1L
            }
        }
        val exporting = Work.EXPORT in state.jobs
        val selected = project?.parts?.find { it.part.uri.toString() == selectedPart }
            ?: project?.parts?.firstOrNull()
        val suggestions = remember(project?.parts) {
            project?.parts.orEmpty().flatMap { review ->
                review.highlights.orEmpty().map { review.part to it }
            }.sortedByDescending { it.second.score }.take(10)
        }
        Scaffold(
            topBar = {
                Column(Modifier.background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.statusBars)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(painterResource(R.drawable.studio_mark), null, Modifier.size(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Clip Studio", style = MaterialTheme.typography.titleMedium)
                            project?.let {
                                Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = {
                            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        }) {
                            Icon(painterResource(R.drawable.nav_bell), "Notification settings")
                        }
                        Button(enabled = selected != null, onClick = {
                            tab = StudioTab.Editor
                            exportRequest++
                        }) { Text("Export") }
                    }
                    HorizontalDivider()
                    val label = state.jobs.values.firstOrNull() ?: state.status.ifBlank {
                        if (project == null) "Ready to import" else "Editing on device"
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 5.dp), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.labelSmall)
                        state.jobs.keys.firstOrNull()?.let { work ->
                            TextButton(onClick = { StudioSession.cancel(work) }) { Text("Cancel") }
                        }
                    }
                    if (occupied) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            bottomBar = {
                Column {
                    HorizontalDivider()
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        StudioTab.entries.forEach { page ->
                            val count = when (page) {
                                StudioTab.Highlights -> suggestions.size
                                StudioTab.Projects -> state.queue.size
                                else -> 0
                            }
                            NavigationBarItem(
                                selected = tab == page, onClick = { tab = page },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                                icon = {
                                    BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                                        Icon(painterResource(page.icon), null, Modifier.size(20.dp))
                                    }
                                },
                                label = { Text(page.title, maxLines = 1) }
                            )
                        }
                    }
                }
            }
        ) { inset ->
            pages.SaveableStateProvider(tab.name) {
                Column(
                    Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    state.storageWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    when (tab) {
                        StudioTab.Import -> {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("YouTube recording link", style = MaterialTheme.typography.titleLarge)
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
                                state.jobs.forEach { (work, label) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                                        TextButton(onClick = { StudioSession.cancel(work) }) { Text("Cancel") }
                                    }
                                }
                            }
                            if (project?.youtubeLink != null && !project.importComplete && Work.IMPORT !in state.jobs) {
                                OutlinedButton(onClick = {
                                    StudioSession.resume(this@MainActivity)
                                    showNotificationPermission()
                                }) { Text("Resume download") }
                            }
                            if (selected != null && project != null) {
                                Text("${project.parts.size} sections ready", style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(onClick = { tab = StudioTab.Editor }) { Text("Open editor") }
                            }
                        }
                        StudioTab.Highlights -> if (project == null) {
                            Text("Import a recording to find highlights.")
                            Button(onClick = { tab = StudioTab.Import }) { Text("Import video") }
                        } else {
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
                            if (suggestions.isNotEmpty()) {
                                Text("Suggested highlights", style = MaterialTheme.typography.titleLarge)
                                suggestions.forEach { (part, highlight) ->
                                    Card(onClick = {
                                        selectedPart = part.uri.toString()
                                        chosenMoment = highlight.startMs
                                        chosenEnd = highlight.endMs
                                        momentSelection++
                                        tab = StudioTab.Editor
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
                                TextButton(onClick = { tab = StudioTab.Editor }) { Text("Open editor") }
                            }
                        }
                        StudioTab.Editor -> if (project == null || selected == null) {
                            Text("Open a video to start editing", style = MaterialTheme.typography.titleLarge)
                            Button(onClick = { tab = StudioTab.Import }) { Text("Import video") }
                        } else {
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
                            val next = project.parts.map { it.part }.firstOrNull { adjacent(selected.part, it) }
                            if (next != null) {
                                OutlinedButton(enabled = Work.JOIN !in state.jobs, onClick = {
                                    StudioSession.join(this@MainActivity, selected.part, next)
                                    showNotificationPermission()
                                }) { Text("Edit across next section") }
                            }
                            key(project.id, selected.part.uri) {
                                ClipEditor(selected.part, project.layout, state, chosenMoment, chosenEnd, momentSelection,
                                    exportRequest, ::showNotificationPermission)
                            }
                        }
                        StudioTab.Projects -> {
                            Text("Saved projects", style = MaterialTheme.typography.titleLarge)
                            if (state.library.projects.isEmpty()) Text("Imported recordings will appear here.")
                            state.library.projects.forEach { item ->
                                Card(
                                    onClick = {
                                        if (project?.id != item.id) {
                                            selectedPart = null
                                            chosenMoment = -1L
                                            incomingLayout = item.layout
                                            StudioSession.openProject(item.id)
                                        }
                                        tab = StudioTab.Editor
                                    },
                                    enabled = !occupied, modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                                        Text("${item.exports.size} exports", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            if (state.queue.isNotEmpty()) {
                                HorizontalDivider()
                                Text("Export queue", style = MaterialTheme.typography.titleLarge)
                                state.queue.forEachIndexed { index, clip ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${timestamp(clip.part.offsetMs + clip.start)} · ${clip.layout.label}")
                                            Text(if (clip.captions.isEmpty()) "No captions" else "${clip.preset.label} captions",
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (clip != state.exportingClip) TextButton(onClick = {
                                            StudioSession.exportFirst(this@MainActivity, clip)
                                            showNotificationPermission()
                                        }) { Text(if (state.queueExportActive) "Next" else if (exporting) "Move first" else "Export first") }
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
                                val timings = state.exports.mapIndexedNotNull { index, uri ->
                                    state.exportStats[uri]?.let { index + 1 to it }
                                }
                                if (timings.isNotEmpty()) {
                                    var history by rememberSaveable { mutableStateOf(false) }
                                    TextButton(onClick = { history = !history }) { Text(if (history) "Latest timing" else "Export timings") }
                                    (if (history) timings.takeLast(6) else timings.takeLast(1)).forEach { (index, stats) ->
                                        val total = stats.preparationMs + stats.renderMs + stats.saveMs
                                        Text("Clip $index · ${stats.encoder} · ${elapsed(total)} total", style = MaterialTheme.typography.titleSmall)
                                        Text("Setup ${elapsed(stats.preparationMs)} · Render and encode ${elapsed(stats.renderMs)} · Save ${elapsed(stats.saveMs)}",
                                            style = MaterialTheme.typography.bodySmall)
                                        if (stats.retried) Text("Includes time spent retrying", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
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
internal fun LayoutPicker(layout: Layout, enabled: Boolean, select: (Layout) -> Unit) {
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

private fun elapsed(ms: Long): String = String.format(Locale.US, "%.1f s", ms / 1000.0)
