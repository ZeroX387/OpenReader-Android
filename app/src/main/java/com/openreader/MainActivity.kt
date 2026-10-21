package com.openreader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import android.text.Html
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.Typeface
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OpenReaderApp() }
    }
}

private data class LocalBook(val uri: String, val title: String)

private fun loadBooks(context: Context): List<LocalBook> {
    val prefs = context.getSharedPreferences("openreader", Context.MODE_PRIVATE)
    val saved = prefs.getString("books", "[]") ?: "[]"
    return try {
        val array = org.json.JSONArray(saved)
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            LocalBook(obj.getString("uri"), obj.getString("title"))
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveBooks(context: Context, books: List<LocalBook>) {
    val array = org.json.JSONArray()
    books.forEach { book ->
        array.put(org.json.JSONObject().put("uri", book.uri).put("title", book.title))
    }
    context.getSharedPreferences("openreader", Context.MODE_PRIVATE)
        .edit().putString("books", array.toString()).apply()
}

private fun readTxt(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (output.size() + n > 5_000_000) error("Файл слишком большой для этой тестовой версии (максимум 5 МБ).")
            output.write(buffer, 0, n)
        }
        output.toByteArray()
    } ?: error("Не удалось открыть файл.")
    val offset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    } catch (_: Exception) {
        String(bytes, charset("windows-1251"))
    }
}

private fun cleanBookText(raw: String): String {
    if (!Regex("<(?:p|br|div|html|body)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return raw
    return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        .replace(Regex("\\n{3,}"), "\\n\\n").trim()
}

private fun pageStarts(text: String, widthPx: Int, heightPx: Int, fontPx: Float): List<Int> {
    if (text.isEmpty() || widthPx <= 0 || heightPx <= 0) return listOf(0)
    val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontPx
        typeface = Typeface.DEFAULT
    }
    // Lay out the book only once. Rebuilding a layout for every page caused
    // quadratic work and could trigger Android's "app isn't responding" dialog.
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
        .setIncludePad(false)
        .setLineSpacing(fontPx * 0.5f, 1f)
        .build()
    val result = mutableListOf(0)
    var pageTop = 0
    var pageStartLine = 0
    for (line in 0 until layout.lineCount) {
        if (layout.getLineBottom(line) - pageTop > heightPx && line > pageStartLine) {
            val nextLine = line
            val nextOffset = layout.getLineStart(nextLine)
            if (nextOffset > result.last()) result.add(nextOffset)
            pageStartLine = nextLine
            pageTop = layout.getLineTop(line)
        }
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenReaderApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("openreader", Context.MODE_PRIVATE) }
    val books = remember { mutableStateListOf<LocalBook>().apply { addAll(loadBooks(context)) } }
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentBook by remember { mutableStateOf<LocalBook?>(null) }
    var content by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableFloatStateOf(prefs.getFloat("font_size", 19f)) }

    fun openBook(book: LocalBook) {
        try {
            content = cleanBookText(readTxt(context, Uri.parse(book.uri)))
            currentBook = book
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Не удалось открыть книгу. Попробуйте добавить её заново."
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "Книга TXT"
                val book = LocalBook(uri.toString(), name)
                if (books.none { it.uri == book.uri }) {
                    books.add(0, book)
                    saveBooks(context, books)
                }
                openBook(book)
            } catch (e: Exception) {
                error = e.message ?: "Не удалось добавить книгу."
            }
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            currentBook?.title ?: "OpenReader",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        if (currentBook != null) {
                            TextButton(onClick = { currentBook = null; content = "" }) {
                                Text("← Назад")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (currentBook == null) {
                    NavigationBar {
                        val tabs = listOf("Библиотека", "Избранное", "Папки", "Прочитано")
                        tabs.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = { Text(listOf("📚", "★", "▤", "✓")[index]) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (currentBook != null) {
                    val book = currentBook!!
                    val positionKey = "page_char_" + book.uri.hashCode()
                    var charPosition by remember(book.uri) {
                        mutableIntStateOf(prefs.getInt(positionKey, 0).coerceAtLeast(0))
                    }
                    var showControls by remember(book.uri) { mutableStateOf(false) }
                    val density = LocalDensity.current
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val widthPx = with(density) { (maxWidth - 36.dp).roundToPx() }
                        // Reserve fixed space for controls and page counter so pagination
                        // doesn't change when the controls are shown or hidden.
                        val heightPx = with(density) { (maxHeight - 78.dp).roundToPx() }
                        val fontPx = with(density) { fontSize.sp.toPx() }
                        val starts by produceState(listOf(0), content, widthPx, heightPx, fontPx) {
                            value = withContext(Dispatchers.Default) {
                                pageStarts(content, widthPx, heightPx, fontPx)
                            }
                        }
                        val safePosition = charPosition.coerceIn(0, content.length)
                        val page = (starts.indexOfLast { it <= safePosition }).coerceAtLeast(0)
                        fun moveTo(index: Int) {
                            val target = index.coerceIn(0, starts.lastIndex)
                            charPosition = starts[target]
                            // commit() ensures the last page is persisted before Android
                            // kills the process after the user leaves the app.
                            prefs.edit().putInt(positionKey, charPosition).commit()
                        }
                        DisposableEffect(book.uri, charPosition) {
                            onDispose {
                                prefs.edit().putInt(positionKey, charPosition).commit()
                            }
                        }
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().height(48.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (showControls) {
                                    TextButton(onClick = {
                                        fontSize = (fontSize - 1).coerceAtLeast(12f)
                                        prefs.edit().putFloat("font_size", fontSize).commit()
                                    }) { Text("A−") }
                                    Text("Шрифт: ${fontSize.toInt()}")
                                    TextButton(onClick = {
                                        fontSize = (fontSize + 1).coerceAtMost(32f)
                                        prefs.edit().putFloat("font_size", fontSize).commit()
                                    }) { Text("A+") }
                                }
                            }
                            Box(
                                Modifier.weight(1f).fillMaxWidth()
                                    .pointerInput(starts, page, showControls) {
                                        detectTapGestures { tap ->
                                            when {
                                                tap.x < size.width * 0.25f -> moveTo(page - 1)
                                                tap.x > size.width * 0.75f -> moveTo(page + 1)
                                                else -> showControls = !showControls
                                            }
                                        }
                                    }
                            ) {
                                if (content.isNotEmpty()) {
                                    val endChar = if (page + 1 < starts.size) starts[page + 1] else content.length
                                    Text(
                                        content.substring(starts[page].coerceIn(0, content.length), endChar.coerceIn(starts[page].coerceIn(0, content.length), content.length)),
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * 1.5f).sp,
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)
                                    )
                                }
                            }
                            Text(
                                "${page + 1} / ${starts.size}",
                                modifier = Modifier.fillMaxWidth().height(30.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            listOf("Моя библиотека", "Избранное", "Папки и коллекции", "Прочитанные книги")[selectedTab],
                            style = MaterialTheme.typography.headlineMedium
                        )
                        if (selectedTab == 0) {
                            Button(onClick = { picker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }) {
                                Text("+ Добавить TXT-книгу")
                            }
                            Text("Версия 0.3: постраничное чтение TXT. EPUB и другие форматы добавим позже.", style = MaterialTheme.typography.bodySmall)
                            if (books.isEmpty()) {
                                Text("Книг пока нет. Нажмите кнопку выше, чтобы выбрать TXT-файл.")
                            }
                            books.forEach { book ->
                                Card(onClick = { openBook(book) }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(book.title, style = MaterialTheme.typography.titleMedium)
                                        Text("Нажмите, чтобы читать", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        } else {
                            Text("Этот раздел появится в следующих версиях.")
                        }
                    }
                }
            }
        }
        if (error != null) {
            AlertDialog(
                onDismissRequest = { error = null },
                title = { Text("Не удалось открыть файл") },
                text = { Text(error ?: "") },
                confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } }
            )
        }
    }
}
