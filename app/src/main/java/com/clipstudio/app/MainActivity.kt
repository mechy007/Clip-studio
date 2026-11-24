package com.clipstudio.app

import android.Manifest
import android.provider.Settings
import android.text.format.Formatter
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.UUID
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val StudioColors = darkColorScheme(
    primary = Color(0xFFA6F0CD), onPrimary = Color(0xFF10291F),
    secondary = Color(0xFFF5C991), background = Color(0xFF0B1017),
    surface = Color(0xFF111924), surfaceVariant = Color(0xFF1A2635),
    primaryContainer = Color(0xFF214535), onPrimaryContainer = Color(0xFFC0F8DB),
    secondaryContainer = Color(0xFF3D3125), onSecondaryContainer = Color(0xFFFFDEB3),
    onSurface = Color(0xFFF0F4F9), onSurfaceVariant = Color(0xFFB8C4D3),
    outline = Color(0xFF687B90), outlineVariant = Color(0xFF2A394A)
)

private enum class StudioTab(val title: String, val icon: Int) {
    Home("Home", R.drawable.nav_home),
    Import("Import", R.drawable.nav_import),
    Highlights("Highlights", R.drawable.nav_highlights),
    Editor("Editor", R.drawable.nav_editor),
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
        var tab by rememberSaveable { mutableStateOf(StudioTab.Home) }
        var exportRequest by rememberSaveable { mutableStateOf(0) }
        val pages = rememberSaveableStateHolder()
        var link by rememberSaveable { mutableStateOf("") }
        var guidedImport by rememberSaveable { mutableStateOf(false) }
        var analyzeFirst by rememberSaveable { mutableStateOf(false) }
        var fastParts by rememberSaveable { mutableStateOf(false) }
        var downloadFullFirst by rememberSaveable { mutableStateOf(false) }
        var recordingStart by rememberSaveable { mutableStateOf("") }
        val startMs = recordingStartMs(recordingStart)
        var seenGuidedPart by rememberSaveable { mutableStateOf<String?>(null) }
        var removePartUri by rememberSaveable { mutableStateOf<String?>(null) }
        var nextPartDialog by rememberSaveable { mutableStateOf(false) }
        var seenGuidedExport by remember { mutableStateOf(state.guidedExportRevision) }
        var incomingLayout by rememberSaveable { mutableStateOf(Layout.Split) }
        var draftProject by rememberSaveable { mutableStateOf<String?>(null) }
        var draftPart by rememberSaveable { mutableStateOf<String?>(null) }
        var draftChoice by rememberSaveable { mutableStateOf<String?>(null) }
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
        val latestGuidedPart = project?.takeIf { it.guidedImport }?.latestSourcePart
        val guidedPending = project?.guidedImport == true && project.scannedParts == null && !project.importComplete
        val nextPartNumber = (project?.parts?.count { !it.part.joined } ?: 0) + 1
        val canDownloadPart = guidedPending && !exporting && Work.IMPORT !in state.jobs &&
            Work.JOIN !in state.jobs && Work.CAPTIONS !in state.jobs && Work.PREVIEW !in state.jobs
        fun downloadNextPart() {
            nextPartDialog = false
            StudioSession.resume(this@MainActivity)
            showNotificationPermission()
        }
        LaunchedEffect(state.guidedPart) {
            state.guidedPart?.uri?.toString()?.takeIf { it != seenGuidedPart }?.let {
                seenGuidedPart = it
                selectedPart = it
                chosenMoment = -1L
                tab = StudioTab.Editor
            }
        }
        LaunchedEffect(state.guidedExportRevision, canDownloadPart) {
            if (canDownloadPart && state.guidedExportRevision != seenGuidedExport) {
                seenGuidedExport = state.guidedExportRevision
                nextPartDialog = true
            }
        }
        LaunchedEffect(project?.id) {
            seenGuidedExport = state.guidedExportRevision
            nextPartDialog = false
        }
        val availableParts = project?.availableParts.orEmpty()
        val selected = availableParts.find { it.part.uri.toString() == selectedPart }
            ?: availableParts.find { it.part == latestGuidedPart }
            ?: availableParts.firstOrNull()
        val releaseCandidate = availableParts.firstOrNull { it.part.uri.toString() == removePartUri }?.part
        if (releaseCandidate != null && project?.canRelease(releaseCandidate) == true) {
            AlertDialog(
                onDismissRequest = { removePartUri = null },
                title = { Text("Remove downloaded footage?") },
                text = { Text("This removes the source footage, drafts, and bookmarks for this part. Your exported MP4s stay in Movies/Clip Studio.") },
                confirmButton = { TextButton(enabled = !occupied, onClick = {
                    removePartUri = null
                    StudioSession.releasePart(this@MainActivity, releaseCandidate)
                }) { Text("Remove footage") } },
                dismissButton = { TextButton(onClick = { removePartUri = null }) { Text("Keep footage") } }
            )
        }
        fun createDraft(part: SourcePart, start: Long = -1L, end: Long = -1L) {
            draftProject = project?.id
            draftPart = part.uri.toString()
            draftChoice = UUID.randomUUID().toString()
            chosenMoment = start
            chosenEnd = end
            momentSelection++
        }
        if (nextPartDialog && canDownloadPart) {
            AlertDialog(
                onDismissRequest = { nextPartDialog = false },
                title = { Text("Download part $nextPartNumber?") },
                text = { Text("You can download the next 30 minutes now or keep clipping this part.") },
                confirmButton = { TextButton(onClick = ::downloadNextPart) { Text("Download next part") } },
                dismissButton = { TextButton(onClick = { nextPartDialog = false }) { Text("Keep editing") } }
            )
        }
        fun editHighlight(part: SourcePart, highlight: Highlight) {
            selectedPart = part.uri.toString()
            createDraft(part, highlight.startMs, highlight.endMs)
            tab = StudioTab.Editor
        }
        val suggestions = remember(project?.parts) {
            availableParts.flatMap { review ->
                review.highlights.orEmpty().map { review.part to it }
            }.sortedWith(compareByDescending<Pair<SourcePart, Highlight>> { it.second.speechExcerpt != null }
                .thenByDescending { it.second.score }).take(10)
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
                            Text("Clip Studio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            project?.takeIf { tab != StudioTab.Home }?.let {
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
                        if (tab == StudioTab.Editor) Button(enabled = selected != null, onClick = {
                            tab = StudioTab.Editor
                            exportRequest++
                        }) { Text("Export") }
                    }
                    HorizontalDivider()
                    val progress = state.guidedDownload
                    val activeWork = if (progress != null) Work.IMPORT else state.jobs.keys.firstOrNull()
                    val label = activeWork?.let { state.jobs[it] } ?: state.status
                    if (label.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 5.dp), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.labelSmall)
                        activeWork?.takeUnless { it == Work.STORAGE }?.let { work ->
                            TextButton(onClick = { StudioSession.cancel(work) }) {
                                Text(if (work == Work.IMPORT && project?.guidedImport == true) "Pause" else "Cancel")
                            }
                        }
                    }
                    if (progress != null && progress.percent > 0 && !progress.assembling) {
                        LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    } else if (occupied) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                    Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    state.storageWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    when (tab) {
                        StudioTab.Home -> StudioOverview(project, selected?.part,
                            availableParts.sumOf { it.highlights.orEmpty().size }, suggestions.firstOrNull(), !exporting,
                            onImport = { tab = StudioTab.Import }, onEdit = { tab = StudioTab.Editor },
                            onHighlights = { tab = StudioTab.Highlights }, onProjects = { tab = StudioTab.Projects },
                            onHighlight = ::editHighlight)
                        StudioTab.Import -> {
                            StudioPageTitle("Import footage", "Open a local recording or pick parts from a YouTube replay.")
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("From your phone", style = MaterialTheme.typography.titleMedium)
                                    OutlinedButton(enabled = !occupied, modifier = Modifier.fillMaxWidth(),
                                        onClick = { picker.launch(arrayOf("video/*")) }) { Text("Choose video") }
                                }
                            }
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("From YouTube", style = MaterialTheme.typography.titleMedium)
                                    OutlinedTextField(
                                        value = link, onValueChange = { link = it }, singleLine = true,
                                        enabled = !occupied, label = { Text("YouTube recording URL") }, modifier = Modifier.fillMaxWidth()
                                    )
                                    FilterChip(selected = guidedImport, enabled = !occupied,
                                        onClick = { guidedImport = !guidedImport }, label = { Text("30-minute parts") })
                                    if (guidedImport) {
                                        FilterChip(selected = analyzeFirst, enabled = !occupied,
                                            onClick = { analyzeFirst = !analyzeFirst }, label = { Text("Analyze first") })
                                        if (!analyzeFirst && !downloadFullFirst) {
                                            FilterChip(selected = fastParts, enabled = !occupied,
                                                onClick = { fastParts = !fastParts }, label = { Text("Fast part download") })
                                        }
                                        if (!analyzeFirst) FilterChip(selected = downloadFullFirst, enabled = !occupied,
                                            onClick = { downloadFullFirst = !downloadFullFirst },
                                            label = { Text("Download full video first") })
                                        if (analyzeFirst) Text("Use a recording or a finished livestream replay.",
                                            style = MaterialTheme.typography.bodySmall)
                                        if (!analyzeFirst && downloadFullFirst) Text("Downloads the whole recording once, then prepares each part on this phone.",
                                            style = MaterialTheme.typography.bodySmall)
                                        OutlinedTextField(
                                            value = recordingStart, onValueChange = { recordingStart = it.take(16) },
                                            enabled = !occupied, singleLine = true, isError = startMs == null,
                                            label = { Text("Start time (optional)") },
                                            supportingText = { Text(if (startMs == null) "Use seconds, MM:SS, or HH:MM:SS."
                                                else "Blank starts at the beginning. Example: 1:24:00") },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Button(
                                        enabled = !occupied && YouTubeLinkClassifier.isYouTubeUrl(link) && (!guidedImport || startMs != null),
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            selectedPart = null
                                            chosenMoment = -1L
                                            if (guidedImport && analyzeFirst) {
                                                StudioSession.analyzeYouTubeParts(this@MainActivity, link.trim(), incomingLayout, startMs ?: 0L)
                                            } else {
                                                StudioSession.importYouTube(this@MainActivity, link.trim(), incomingLayout, guidedImport,
                                                    if (guidedImport) startMs ?: 0L else 0L, fromFull = downloadFullFirst, fastParts = fastParts)
                                            }
                                            showNotificationPermission()
                                        }
                                    ) { Text(when {
                                        guidedImport && analyzeFirst -> "Analyze parts"
                                        guidedImport && downloadFullFirst -> "Download full video for parts"
                                        guidedImport -> "Download part 1"
                                        else -> "Import & find highlights"
                                    }) }
                                }
                            }
                            var importSettings by rememberSaveable { mutableStateOf(false) }
                            SettingsDisclosure("Starting layout · ${incomingLayout.label}", importSettings,
                                { importSettings = !importSettings }) {
                                LayoutPicker(incomingLayout, !occupied) { incomingLayout = it }
                            }
                            if (occupied) {
                                state.jobs.forEach { (work, label) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary)
                                        if (work != Work.STORAGE) TextButton(onClick = { StudioSession.cancel(work) }) { Text("Cancel") }
                                    }
                                }
                            }
                            val scannedParts = project?.scannedParts
                            if (project != null && scannedParts != null) {
                                if (scannedParts.isEmpty() && !occupied) {
                                    OutlinedButton(onClick = {
                                        StudioSession.retryPartAnalysis(this@MainActivity)
                                        showNotificationPermission()
                                    }) { Text("Retry analysis") }
                                } else if (scannedParts.isNotEmpty()) {
                                    Text("Analyzed parts", style = MaterialTheme.typography.titleMedium)
                                    Text("Audio activity uses short samples. Check timestamps for quiet or visual highlights.",
                                        style = MaterialTheme.typography.bodySmall)
                                    if (scannedParts.any { it.needsSampling }) {
                                        OutlinedButton(enabled = !occupied, onClick = {
                                            StudioSession.retryPartAnalysis(this@MainActivity)
                                            showNotificationPermission()
                                        }) { Text("Continue audio scan") }
                                    }
                                    var qualityHeight by rememberSaveable { mutableIntStateOf(720) }
                                    val quality = DownloadQuality.fromHeight(qualityHeight) ?: DownloadQuality.HIGH
                                    Text("Download quality", style = MaterialTheme.typography.titleSmall)
                                    DownloadQuality.entries.chunked(2).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            row.forEach { option ->
                                                FilterChip(selected = quality == option, enabled = !occupied,
                                                    onClick = { qualityHeight = option.maxHeight },
                                                    label = { Text(option.label) })
                                            }
                                        }
                                    }
                                    Text("Download resolution can be lower when the source lacks the selected quality.",
                                        style = MaterialTheme.typography.bodySmall)
                                    if (project.downloadQueue.isNotEmpty()) {
                                        Text("Download queue", style = MaterialTheme.typography.titleMedium)
                                        project.downloadQueue.forEach { item ->
                                            val number = scannedParts.indexOfFirst { it.range.startMs == item.startMs } + 1
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Part $number · ${item.quality.label}", Modifier.weight(1f))
                                                TextButton(enabled = !occupied,
                                                    onClick = { StudioSession.removeScannedDownload(item) }) { Text("Remove") }
                                            }
                                        }
                                        Button(enabled = !occupied, onClick = {
                                            StudioSession.downloadScannedQueue(this@MainActivity)
                                            showNotificationPermission()
                                        }) { Text("Download queue · ${project.downloadQueue.size}") }
                                    }
                                    var sortByAudio by rememberSaveable { mutableStateOf(true) }
                                    FilterChip(selected = sortByAudio, onClick = { sortByAudio = !sortByAudio },
                                        label = { Text(if (sortByAudio) "Audio activity order" else "Timestamp order") })
                                    val ranked = if (sortByAudio) scannedParts.withIndex().sortedByDescending { it.value.score ?: -1 }
                                        else scannedParts.withIndex().toList()
                                    ranked.forEach { (index, result) ->
                                        val range = result.range
                                        val item = RecordingDownload(range.startMs, quality)
                                        val saved = project.parts.firstOrNull { it.part.matches(item) }
                                        val queued = item in project.downloadQueue
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("Part ${index + 1} · ${timestamp(range.startMs)}–${timestamp(range.endMs)}")
                                                Text(result.score?.let { "Audio activity $it/100 · ${result.samples} samples" }
                                                    ?: "Audio ranking unavailable", style = MaterialTheme.typography.bodySmall)
                                            }
                                            when {
                                                queued -> Text("Queued", style = MaterialTheme.typography.labelSmall)
                                                saved == null || saved.released -> OutlinedButton(enabled = !occupied, onClick = {
                                                    StudioSession.queueScannedPart(range.startMs, quality)
                                                }) { Text(if (saved?.released == true) "Queue again" else "Add to queue") }
                                                else -> TextButton(onClick = {
                                                    selectedPart = saved.part.uri.toString()
                                                    chosenMoment = -1L
                                                    tab = StudioTab.Editor
                                                }) { Text("Open editor") }
                                            }
                                        }
                                    }
                                }
                            }
                            if (guidedPending) {
                                OutlinedButton(enabled = canDownloadPart, onClick = ::downloadNextPart) {
                                    Text("Download part $nextPartNumber")
                                }
                                if (project?.fastGuidedParts == false || project?.guidedFromFull == true) {
                                    OutlinedButton(enabled = canDownloadPart, onClick = {
                                        StudioSession.resume(this@MainActivity, useFastParts = true)
                                        showNotificationPermission()
                                    }) { Text("Try fast part download") }
                                }
                                if (project?.guidedFromFull == false) {
                                    OutlinedButton(enabled = canDownloadPart, onClick = {
                                        StudioSession.resume(this@MainActivity, useFullDownload = true)
                                        showNotificationPermission()
                                    }) { Text("Download full video first") }
                                    Text("If a part stalls, this downloads the full recording once and prepares the remaining parts locally.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            } else if (project?.youtubeLink != null && project.scannedParts == null && !project.importComplete && Work.IMPORT !in state.jobs) {
                                OutlinedButton(onClick = {
                                    StudioSession.resume(this@MainActivity)
                                    showNotificationPermission()
                                }) { Text("Resume download") }
                            }
                            if (selected != null && project != null) {
                                Text("${availableParts.size} sections ready", style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(onClick = { tab = StudioTab.Editor }) { Text("Open editor") }
                            }
                        }
                        StudioTab.Highlights -> {
                            StudioPageTitle("Highlights", "Whisper finds spoken moments. Motion and audio cues help find other clips.")
                            if (project == null || availableParts.isEmpty()) {
                                Text("Import or download a recording to find highlights.")
                                Button(onClick = { tab = StudioTab.Import }) { Text("Open import") }
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
                                val pending = availableParts.any { !it.part.joined && !it.speechAnalyzed }
                                if (pending && Work.ANALYZE !in state.jobs) {
                                    Button(onClick = {
                                        StudioSession.analyze(this@MainActivity)
                                        showNotificationPermission()
                                    }) { Text(if (suggestions.isEmpty()) "Find highlights" else "Refresh with Whisper") }
                                }
                                if (suggestions.isNotEmpty()) {
                                    Text("Suggested clips", style = MaterialTheme.typography.titleLarge)
                                    suggestions.forEach { (part, highlight) ->
                                        StudioHighlightCard(part, highlight, !exporting) { editHighlight(part, highlight) }
                                    }
                                } else if (!pending) {
                                    Text("No strong moments found. You can still trim a clip manually.")
                                    TextButton(onClick = { tab = StudioTab.Editor }) { Text("Open editor") }
                                }
                            }
                        }
                        StudioTab.Editor -> if (project == null || selected == null) {
                            if (project?.guidedImport == true && project.parts.isNotEmpty()) {
                                Text("No downloaded footage available", style = MaterialTheme.typography.titleLarge)
                                if (guidedPending) Button(enabled = canDownloadPart, onClick = ::downloadNextPart) {
                                    Text("Download part $nextPartNumber")
                                }
                                if (project.scannedParts != null) OutlinedButton(onClick = { tab = StudioTab.Import }) { Text("Choose parts") }
                                OutlinedButton(onClick = { tab = StudioTab.Projects }) { Text("Open exports") }
                            } else {
                                Text("Open a video to start editing", style = MaterialTheme.typography.titleLarge)
                                Button(onClick = { tab = StudioTab.Import }) { Text("Import video") }
                            }
                        } else {
                            if (project.guidedImport) {
                                val sourceParts = project.parts.filterNot { it.part.joined }
                                val partNumber = (project.scannedParts?.indexOfFirst { it.range.startMs == selected.part.offsetMs }
                                    ?: sourceParts.indexOf(selected)) + 1
                                if (project.scannedParts != null) OutlinedButton(onClick = { tab = StudioTab.Import }) { Text("Choose parts") }
                                if (partNumber > 0) {
                                    val cap = selected.part.downloadQuality?.let { " · ${it.label} max" }.orEmpty()
                                    Text("Part $partNumber · ${timestamp(selected.part.offsetMs)}–${timestamp(selected.part.nextOffsetMs)}$cap",
                                        style = MaterialTheme.typography.titleMedium)
                                }
                                val queuedCount = project.queue.count { it.part == selected.part }
                                if (queuedCount > 0 || selected.part.uri in project.exportedParts) {
                                    Button(enabled = !exporting && Work.IMPORT !in state.jobs && Work.STORAGE !in state.jobs,
                                        onClick = {
                                            StudioSession.finishPart(this@MainActivity, selected.part)
                                            if (queuedCount > 0) showNotificationPermission()
                                        }) {
                                        Text(if (queuedCount == 0) "Finish this part" else "Finish this part · $queuedCount queued")
                                    }
                                }
                                if (guidedPending) OutlinedButton(
                                    enabled = canDownloadPart, onClick = { nextPartDialog = true }
                                ) { Text("Download part $nextPartNumber") }
                            }
                            if (availableParts.size > 1) {
                                var expanded by rememberSaveable { mutableStateOf(false) }
                                TextButton(onClick = { expanded = !expanded }) {
                                    Text("${availableParts.size} ready sections · ${timestamp(selected.part.offsetMs)} selected")
                                }
                                if (expanded) availableParts.forEach { review ->
                                    val part = review.part
                                    FilterChip(
                                        selected = review == selected, onClick = {
                                            chosenMoment = -1L
                                            selectedPart = part.uri.toString()
                                        },
                                        enabled = !exporting, label = {
                                            val time = "${timestamp(part.offsetMs)} – ${timestamp(part.offsetMs + part.durationMs)}"
                                            Text(part.downloadQuality?.let { "$time · ${it.label} max" } ?: time)
                                        }
                                    )
                                }
                            }
                            val next = availableParts.map { it.part }.firstOrNull { adjacent(selected.part, it) }
                            if (next != null) {
                                OutlinedButton(enabled = Work.JOIN !in state.jobs, onClick = {
                                    StudioSession.join(this@MainActivity, selected.part, next)
                                    showNotificationPermission()
                                }) { Text("Edit across next section") }
                            }
                            val part = selected.part
                            val primaryId = part.uri.toString()
                            val drafts = project.drafts.filterValues { it.edit.part == part }
                            val activeDraft = draftChoice?.takeIf { draftProject == project.id && draftPart == primaryId }
                                ?: drafts.keys.lastOrNull() ?: primaryId
                            var draftsOpen by rememberSaveable(project.id, primaryId) { mutableStateOf(false) }
                            SettingsDisclosure("Clip drafts · ${drafts.size}", draftsOpen, { draftsOpen = !draftsOpen }) {
                                drafts.entries.forEachIndexed { index, (id, draft) ->
                                    FilterChip(selected = id == activeDraft, enabled = !exporting, onClick = {
                                        draftProject = project.id
                                        draftPart = primaryId
                                        draftChoice = id
                                        chosenMoment = -1L
                                    }, label = {
                                        Text("Clip ${index + 1} · ${timestamp(part.offsetMs + draft.edit.start)}–${timestamp(part.offsetMs + draft.edit.end)}")
                                    })
                                }
                                OutlinedButton(enabled = !exporting, onClick = { createDraft(part) }) { Text("New clip draft") }
                            }
                            key(project.id, part.uri, activeDraft) {
                                ClipEditor(part, project, state, activeDraft, chosenMoment, chosenEnd, momentSelection,
                                    exportRequest, ::showNotificationPermission, onBookmarkDraft = { time ->
                                        createDraft(part, time, minOf(part.durationMs, time + 15_000))
                                    })
                            }
                        }
                        StudioTab.Projects -> {
                            StudioPageTitle("Projects", "Reopen a recording or manage its exports and storage.")
                            var savedProjectsOpen by rememberSaveable { mutableStateOf(true) }
                            SettingsDisclosure("Saved projects · ${state.library.projects.size}", savedProjectsOpen,
                                { savedProjectsOpen = !savedProjectsOpen }) {
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
                            }
                            if (project?.guidedImport == true && project.parts.isNotEmpty()) {
                                var storageOpen by rememberSaveable { mutableStateOf(false) }
                                SettingsDisclosure("Free up storage", storageOpen, { storageOpen = !storageOpen }) {
                                    val downloaded = project.parts.filterNot { it.part.joined }
                                    var sizes by remember(downloaded) { mutableStateOf<Map<Uri, Long>>(emptyMap()) }
                                    LaunchedEffect(downloaded) {
                                        sizes = withContext(Dispatchers.IO) {
                                            downloaded.filterNot { it.released }.associate { review ->
                                                review.part.uri to (review.part.uri.path?.let { File(it).length() } ?: 0L)
                                            }
                                        }
                                    }
                                    downloaded.forEachIndexed { index, review ->
                                        val part = review.part
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                val number = (project.scannedParts?.indexOfFirst { it.range.startMs == part.offsetMs } ?: index) + 1
                                                val time = "${timestamp(part.offsetMs)}–${timestamp(part.nextOffsetMs)}"
                                                Text("Part $number · $time" + (part.downloadQuality?.let { " · ${it.label} max" }.orEmpty()))
                                                val storage = if (review.released) "Footage removed" else
                                                    sizes[part.uri]?.let { Formatter.formatFileSize(this@MainActivity, it) }
                                                storage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                            }
                                            if (project.canRelease(part)) {
                                                TextButton(enabled = !occupied, onClick = { removePartUri = part.uri.toString() }) {
                                                    Text("Remove")
                                                }
                                            }
                                        }
                                    }
                                    Text("Export a clip from a part and finish its queued exports to remove the footage.",
                                        style = MaterialTheme.typography.bodySmall)
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
                                        if (clip != state.exportingClip && !state.finishingPart) TextButton(onClick = {
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
