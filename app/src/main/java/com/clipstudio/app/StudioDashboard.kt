package com.clipstudio.app

import android.media.MediaMetadataRetriever
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class StudioRecipe(
    val title: String, val layout: Layout, val captions: CaptionPreset, val detail: String,
    val normalizeAudio: Boolean = false
) {
    Reaction("Viral 50/50 gaming", Layout.Split, CaptionPreset.POP,
        "Facecam above gameplay · Pop captions · Normalized audio", normalizeAudio = true),
    Gameplay("Gameplay short", Layout.Fill, CaptionPreset.KARAOKE,
        "Vertical fill · Animated karaoke captions"),
    SquarePost("Square cut", Layout.Square, CaptionPreset.BOXED,
        "Square frame · Boxed captions"),
    Podcast("Podcast speaker", Layout.Fill, CaptionPreset.BOXED,
        "9:16 speaker framing · Boxed captions · Voice normalization", normalizeAudio = true),
    CleanLandscape("Clean 1080p raw", Layout.Landscape, CaptionPreset.CLEAN,
        "16:9 landscape · Clean captions · Source framing")
}

@Composable
internal fun StudioStorage() {
    val context = LocalContext.current
    val capacity by produceState<Pair<Long, Long>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { StatFs(context.filesDir.path).let { it.availableBytes to it.totalBytes } }.getOrNull()
        }
    }
    capacity?.let { (free, total) ->
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Device storage", style = MaterialTheme.typography.titleMedium)
                Text("${Formatter.formatShortFileSize(context, free)} free of ${Formatter.formatShortFileSize(context, total)}")
                if (total > 0) LinearProgressIndicator(progress = { (1f - free.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun StudioPageTitle(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun StudioOverview(
    project: StudioProject?, source: SourcePart?, highlightCount: Int, best: Pair<SourcePart, Highlight>?,
    editEnabled: Boolean, onImport: () -> Unit, onEdit: () -> Unit, onHighlights: () -> Unit,
    onProjects: () -> Unit, onWorkspace: () -> Unit,
    onHighlight: (SourcePart, Highlight) -> Unit, onRecipe: (StudioRecipe) -> Unit
) {
    StudioPageTitle("Workspace")

    val context = LocalContext.current
    val artwork by produceState<ImageBitmap?>(null, source?.uri) {
        value = null
        val part = source ?: return@produceState
        if (Build.VERSION.SDK_INT < 27) return@produceState
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, part.uri)
                val frameMs = minOf(5_000L, part.durationMs / 2)
                retriever.getScaledFrameAtTime(frameMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360)
                    ?.asImageBitmap()
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
    }
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().heightIn(min = 220.dp).clip(shape)
        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface)))) {
        artwork?.let {
            Image(it, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
                Color(0x33101821), Color(0xED0B1017), Color(0xFF0B1017)
            ))))
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            source?.let { Text("${project?.layout?.label.orEmpty()} · ${timestamp(it.durationMs)}",
                color = Color.White, style = MaterialTheme.typography.labelLarge) }
            if (project == null) {
                Icon(painterResource(R.drawable.nav_import), null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Start a project", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("Import a recording or a YouTube replay to find clips.",
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFFDFE7F1))
            } else {
                Text(project.name, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sections = project.availableParts.count { !it.part.joined }
                Text(if (sections == 0) "Choose footage to continue" else "$sections downloaded section${if (sections == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFFDFE7F1))
            }
            val action = when {
                source != null -> "Continue editing"
                project != null -> "Open import"
                else -> "Import recording"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = if (source != null) onEdit else onImport, enabled = source == null || editEnabled) { Text(action) }
                if (source != null) TextButton(onClick = onImport) { Text("Import part", color = Color.White) }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DashboardCounter(Modifier.weight(1f), "Highlights", highlightCount, R.drawable.nav_highlights, onHighlights)
        DashboardCounter(Modifier.weight(1f), "Exports", project?.exports?.size ?: 0, R.drawable.nav_projects, onProjects)
        DashboardCounter(Modifier.weight(1f), "Parts", project?.availableParts?.count { !it.part.joined } ?: 0,
            R.drawable.nav_import, onImport)
    }
    Text("One-tap recipes", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioRecipe.entries.forEach { recipe ->
            Card(onClick = { onRecipe(recipe) }, enabled = source != null && editEnabled, modifier = Modifier.width(172.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.nav_editor), null, tint = MaterialTheme.colorScheme.secondary)
                    Text(recipe.title, style = MaterialTheme.typography.titleMedium)
                    Text(recipe.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Card(
        onClick = onWorkspace,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.nav_workspace), null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("AI Hub & creator workspace", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text("Offline AI, YouTube skills, presets, audio tools and advanced workflows",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Open", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }

    if (best != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Top highlight", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onHighlights) { Text("View all") }
        }
        StudioHighlightCard(best.first, best.second, editEnabled) { onHighlight(best.first, best.second) }
    }

}

@Composable
private fun DashboardCounter(modifier: Modifier, label: String, count: Int, icon: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun StudioHighlightCard(part: SourcePart, highlight: Highlight, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(highlight.reason, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { (highlight.score / 100f).coerceIn(0f,1f) },
                        modifier = Modifier.size(42.dp), strokeWidth = 3.dp)
                    Text(highlight.score.toString(), style = MaterialTheme.typography.labelLarge)
                }
            }
            highlight.speechExcerpt?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("${timestamp(part.offsetMs + highlight.startMs)} – ${timestamp(part.offsetMs + highlight.endMs)}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
