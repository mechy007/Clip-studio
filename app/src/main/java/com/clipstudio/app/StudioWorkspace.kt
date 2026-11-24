package com.clipstudio.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A dedicated home for Clip Studio capabilities that do not fit in the five-tab
 * editing flow. This screen only exposes features backed by the Android app;
 * roadmap items are clearly marked as planned.
 */
@Composable
internal fun StudioWorkspace(
    project: StudioProject?,
    source: SourcePart?,
    state: StudioState,
    onImport: () -> Unit,
    onHighlights: () -> Unit,
    onEditor: () -> Unit,
    onProjects: () -> Unit
) {
    val encoder by produceState(initialValue = "Checking hardware encoder") {
        value = withContext(Dispatchers.Default) {
            runCatching { FastMedia.hardwareAvc(1080, 1920) }.getOrNull()
                ?.let { "MediaCodec hardware encoder · $it" }
                ?: "Software encoding fallback available"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StudioPageTitle(
            "AI Hub & creator workspace",
            "Advanced tools, offline AI, saved workflows and room for future modules."
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("On-device AI & render engine", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                WorkspaceStatusRow("Whisper tiny", "Bundled multilingual speech recognition · offline")
                WorkspaceStatusRow("YAMNet ONNX", "Bundled reaction-sound detection · offline")
                WorkspaceStatusRow("Video encoder", encoder)
                Text(
                    "Hardware acceleration is checked for the requested export size. Clip Studio keeps its software render path as a fallback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("YouTube creator skills", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "Eleven creator workflows, including six local Python tools. Claude-powered writing and follow-ups are optional and require your own Anthropic API key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CreatorSkills(project, source)
            }
        }

        Text("Available workflows", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WorkspaceFeatureCard(
                "AI highlight discovery",
                "Whisper transcripts, reaction sounds, audio energy and visual-motion cues.",
                "Open Highlights", R.drawable.nav_highlights, onHighlights, Modifier.weight(1f)
            )
            WorkspaceFeatureCard(
                "Long-form import",
                "30-minute parts, ranked scanning, quality choices and resumable queues.",
                "Open Import", R.drawable.nav_import, onImport, Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WorkspaceFeatureCard(
                "Precision editor",
                "Waveform scrubbing, frame stepping, bookmarks, trim and framing.",
                "Open Editor", R.drawable.nav_editor, onEditor, Modifier.weight(1f)
            )
            WorkspaceFeatureCard(
                "Captions & SRT",
                "Offline speech-to-text, timed caption editing, styles and subtitle export.",
                "Open Editor", R.drawable.nav_editor, onEditor, Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WorkspaceFeatureCard(
                "Facecam tools",
                "Tracking, crop rescue, enhancement previews and manual positioning.",
                "Open Editor", R.drawable.nav_editor, onEditor, Modifier.weight(1f)
            )
            WorkspaceFeatureCard(
                "Audio finishing",
                "Noise cleanup, normalization, fades, track levels and mixing.",
                "Open Editor", R.drawable.nav_editor, onEditor, Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WorkspaceFeatureCard(
                "Fast export",
                "Hardware H.264 when available, software fallback and render timings.",
                "Open Projects", R.drawable.nav_projects, onProjects, Modifier.weight(1f)
            )
            WorkspaceFeatureCard(
                "Project library",
                "Drafts, export queue, project history and storage management.",
                "Open Projects", R.drawable.nav_projects, onProjects, Modifier.weight(1f)
            )
        }

        Text("Future feature slots", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        Text(
            "These are intentionally shown as roadmap items, not switches for features that are not implemented yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WorkspaceRoadmapCard(
            "Cover-frame export",
            "Generate and save a branded Shorts thumbnail from the selected video frame."
        )
        WorkspaceRoadmapCard(
            "Custom subtitle font packs",
            "Import and manage creator-owned TTF/OTF fonts for caption styling."
        )
        WorkspaceRoadmapCard(
            "Encrypted cross-device project sync",
            "Synchronize project metadata, editable drafts and creator presets across Android devices."
        )
        WorkspaceRoadmapCard(
            "Multi-track timeline",
            "Arrange video, voice, game audio, overlays and captions on independent tracks."
        )
        WorkspaceRoadmapCard(
            "Expanded color grading",
            "Reusable color looks and LUT-style controls beyond the current enhancement filters."
        )

        Text("Device storage", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        StudioStorage()
        if (state.jobs.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Background work", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    state.jobs.values.forEach { job ->
                        Text(job, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (project == null || source == null) {
            Text(
                "Import a recording to unlock project-specific analysis, editing and saved transcript workflows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.size(2.dp))
    }
}

@Composable
private fun WorkspaceStatusRow(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkspaceFeatureCard(
    title: String,
    detail: String,
    action: String,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    painter = painterResource(icon), contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                minLines = 2)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 3)
            Text(action, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WorkspaceRoadmapCard(title: String, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Planned", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        }
    }
}
