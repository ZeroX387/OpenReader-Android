package com.openreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.sp

/** Shared visual chrome for every OpenReader format. */
@Composable
fun ReaderTopBar(
    title: String,
    fontSize: Float,
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        shadowElevation = 4.dp
    ) {
        androidx.compose.foundation.layout.Column {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onBookmark) {
                    Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Закладка")
                }
                if (showQuotesAction) IconButton(onClick = onQuotes) {
                    Icon(Icons.Outlined.FormatQuote, contentDescription = "Цитаты")
                }
                if (onSearch != null) IconButton(onClick = onSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = "Поиск")
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                }
            }
            if (showTextSizeControls) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDecreaseText) { Text("A−") }
                    Text(
                        text = fontSize.toInt().toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        shadowElevation = 4.dp
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onPrevious, enabled = safeCount > 0 && safeIndex > 0) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = null)
                    Text("Назад")
                }
                Text(
                    if (safeCount > 0) "${safeIndex + 1} / $safeCount" else "Загрузка книги…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onNext, enabled = safeCount > 0 && safeIndex < safeCount - 1) {
                    Text("Вперёд")
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null)
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

/** Shared appearance controls used by every reader format. */
@Composable
fun ReaderAppearanceSettings(
    readerTheme: String,
    fontSize: Float,
    readerSpacing: Float,
    readerMargin: Float,
    defaultMargin: Float = 52f,
    onThemeChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onSpacingChange: (Float) -> Unit,
    onMarginChange: (Float) -> Unit,
    showTypography: Boolean = true
) {
    val pageBackground = when (readerTheme) {
        "dark" -> androidx.compose.ui.graphics.Color(0xFF181818)
        "sepia" -> androidx.compose.ui.graphics.Color(0xFFF1E3C8)
        "light" -> androidx.compose.ui.graphics.Color.White
        else -> androidx.compose.ui.graphics.Color(0xFFFAF7F0)
    }
    val pageInk = if (readerTheme == "dark") androidx.compose.ui.graphics.Color.White
        else androidx.compose.ui.graphics.Color(0xFF242424)

    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Цвет страниц", style = MaterialTheme.typography.titleSmall)
        listOf("paper" to "Бумажный", "light" to "Белый", "sepia" to "Сепия", "dark" to "Тёмный").forEach { (value, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(
                    selected = readerTheme == value,
                    onClick = { onThemeChange(value) }
                )
                Text(label, modifier = Modifier.weight(1f).then(Modifier.padding(vertical = 4.dp))
                    .background(pageBackground.copy(alpha = if (readerTheme == value) 0.18f else 0f))
                    .padding(4.dp)
                    .clickable { onThemeChange(value) })
            }
        }
        if (showTypography) {
            Text("Размер шрифта: ${fontSize.toInt()} sp")
            androidx.compose.material3.Slider(value = fontSize, onValueChange = onFontSizeChange, valueRange = 12f..32f, steps = 19)
            Text("Межстрочный интервал: ${String.format(java.util.Locale.US, "%.2f", readerSpacing)}")
            androidx.compose.material3.Slider(value = readerSpacing, onValueChange = onSpacingChange, valueRange = 1.1f..2.0f)
            Text("Предпросмотр", style = MaterialTheme.typography.titleSmall)
            Text(
                "Однажды в студёную зимнюю пору\nЯ из лесу вышел; был сильный мороз.\nИдёт себе лесом, глядит — поднимается солнце.",
                fontSize = fontSize.sp, lineHeight = (fontSize * readerSpacing).sp, color = pageInk,
                modifier = Modifier.fillMaxWidth().background(pageBackground).padding(12.dp)
            )
            androidx.compose.material3.TextButton(onClick = { onFontSizeChange(19f); onSpacingChange(1.5f) }) {
                Text("Вернуть стандарт: 19 sp / 1,5")
            }
            Text("Отступы страницы: 12 dp сверху и по бокам, 4 dp снизу")
            Text("Чуть больше пространства сверху и по бокам, меньше пустоты снизу.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().background(pageBackground).padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)
            ) {
                Text(
                    "Это пример страницы книги с едиными компактными отступами.",
                    fontSize = 14.sp, color = pageInk, lineHeight = 21.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Justify
                )
            }
        } else {
            Text(
                "Для PDF размер и расположение текста определяются самим документом. Его масштаб меняется жестом увеличения.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
