package com.clipstudio.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

@Composable
internal fun TimeRangeDialog(
    title: String, start: Long, end: Long, duration: Long, onDismiss: () -> Unit,
    captionText: String? = null, apply: (Long, Long, String?) -> Unit
) {
    var firstText by rememberSaveable { mutableStateOf(editTime(start)) }
    var lastText by rememberSaveable { mutableStateOf(editTime(end)) }
    var text by rememberSaveable { mutableStateOf(captionText.orEmpty()) }
    val first = parseEditTime(firstText)
    val last = parseEditTime(lastText)
    val valid = first != null && last != null && first < last && last <= duration &&
        (captionText == null || text.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Times are relative to this part. Use MM:SS.mmm.")
                if (captionText != null) {
                    OutlinedTextField(text, { text = it.take(140) }, label = { Text("Caption") }, maxLines = 3)
                }
                OutlinedTextField(firstText, { firstText = it.take(20) }, singleLine = true, label = { Text("Start") })
                OutlinedTextField(lastText, { lastText = it.take(20) }, singleLine = true, label = { Text("End") })
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                apply(requireNotNull(first), requireNotNull(last), text.takeIf { captionText != null })
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
