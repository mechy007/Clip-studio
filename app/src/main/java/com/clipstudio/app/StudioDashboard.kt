package com.clipstudio.app

import android.media.MediaMetadataRetriever
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
    onProjects: () -> Unit, onHighlight: (SourcePart, Highlight) -> Unit
) {
    StudioPageTitle("Workspace")
    CreatorSkills(project, source)
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
        .background(Brush.linearGradient(listOf(Color(0xFF20324B), Color(0xFF101821))))) {
        artwork?.let {
            Image(it, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
                Color(0x33101821), Color(0xED0B1017), Color(0xFF0B1017)
            ))))
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Button(onClick = if (source != null) onEdit else onImport, enabled = source == null || editEnabled) { Text(action) }
        }
    }
    if (project != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashboardCounter(Modifier.weight(1f), "Highlights", highlightCount, R.drawable.nav_highlights, onHighlights)
            DashboardCounter(Modifier.weight(1f), "Exports", project.exports.size, R.drawable.nav_projects, onProjects)
        }
        if (best != null) {
            Text("Top highlight", style = MaterialTheme.typography.titleLarge)
            StudioHighlightCard(best.first, best.second, editEnabled) { onHighlight(best.first, best.second) }
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import another recording") }
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
                Text("${highlight.score}/100", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            highlight.speechExcerpt?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("${timestamp(part.offsetMs + highlight.startMs)} – ${timestamp(part.offsetMs + highlight.endMs)}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
