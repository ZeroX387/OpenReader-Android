package com.openreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared visual chrome for every OpenReader format.
 * Format-specific readers provide actions; the placement and visual hierarchy stay the same.
 */
@Composable
fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onQuotes: () -> Unit,
    onDecreaseText: () -> Unit,
    onSettings: () -> Unit,
    onIncreaseText: () -> Unit,
    modifier: Modifier = Modifier,
    showTextSizeControls: Boolean = true,
    showQuotesAction: Boolean = true,
    onSearch: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 4.dp
    ) {
        androidx.compose.foundation.layout.Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("‹") }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                IconButton(onClick = onBookmark) { Text("🔖") }
                if (showQuotesAction) IconButton(onClick = onQuotes) { Text("❝") }
                if (onSearch != null) IconButton(onClick = onSearch) { Text("⌕") }
                IconButton(onClick = onSettings) { Text("⚙") }
            }
            if (showTextSizeControls) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDecreaseText) { Text("A−") }
                    Text("Размер текста", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onIncreaseText) { Text("A+") }
                }
            }
        }
    }
}
