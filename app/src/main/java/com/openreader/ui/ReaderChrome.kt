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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
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


/** Shared page navigation for reflowable and fixed-layout readers. */
@Composable
fun ReaderBottomBar(
    pageIndex: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeCount = pageCount.coerceAtLeast(0)
    val safeIndex = if (safeCount > 0) pageIndex.coerceIn(0, safeCount - 1) else 0
    val sliderValue = remember(safeIndex, safeCount) { mutableFloatStateOf(safeIndex.toFloat()) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 4.dp
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onPrevious, enabled = safeCount > 0 && safeIndex > 0) {
                    Text("‹ Назад")
                }
                Text(
                    if (safeCount > 0) "${safeIndex + 1} / $safeCount" else "Загрузка книги…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onNext, enabled = safeCount > 0 && safeIndex < safeCount - 1) {
                    Text("Вперёд ›")
                }
            }
            if (safeCount > 1) {
                androidx.compose.material3.Slider(
                    value = sliderValue.floatValue,
                    onValueChange = { sliderValue.floatValue = it },
                    onValueChangeFinished = { onSelectPage(sliderValue.floatValue.toInt().coerceIn(0, safeCount - 1)) },
                    valueRange = 0f..(safeCount - 1).toFloat()
                )
            }
        }
    }
}
