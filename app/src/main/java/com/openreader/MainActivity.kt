package com.openreader

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import android.text.Html
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.Typeface
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
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

private data class LocalBook(val uri: String, val title: String, val format: String = "txt")

private fun loadBooks(context: Context): List<LocalBook> {
    val prefs = context.getSharedPreferences("openreader", Context.MODE_PRIVATE)
    val saved = prefs.getString("books", "[]") ?: "[]"
    return try {
        val array = org.json.JSONArray(saved)
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            LocalBook(obj.getString("uri"), obj.getString("title"), obj.optString("format", "txt"))
        }
    } catch (_: Exception) { emptyList() }
}

private fun suggestedTitle(raw: String, fallback: String): String {
    val lines = raw.lineSequence().take(45).map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val labeled = lines.firstNotNullOfOrNull { line ->
        Regex("""^(?:название|название книги|заглавие|title)\s*[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.length in 2..100 }
    }
    return labeled ?: fallback
}

private fun saveBooks(context: Context, books: List<LocalBook>) {
    val array = org.json.JSONArray()
    books.forEach { book ->
        array.put(org.json.JSONObject().put("uri", book.uri).put("title", book.title).put("format", book.format))
    }
    context.getSharedPreferences("openreader", Context.MODE_PRIVATE)
        .edit().putString("books", array.toString()).apply()
}

private fun nameHintDisplayName(context: Context, uri: Uri): String {
    return context.contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: uri.lastPathSegment.orEmpty()
}
private fun nameHintFb2(context: Context, uri: Uri): Boolean {
    val displayName = context.contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    return (displayName ?: uri.lastPathSegment.orEmpty()).endsWith(".fb2", ignoreCase = true)
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

private data class Fb2Document(val title: String, val authors: String, val annotation: String, val body: String)

private fun readFb2(context: Context, uri: Uri): Fb2Document {
    val xml = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?: error("Не удалось открыть FB2-файл.")
    require(xml.length <= 5_000_000) { "Файл слишком большой для этой тестовой версии (максимум 5 МБ)." }
    val parser = android.util.Xml.newPullParser()
    parser.setInput(xml.reader())
    var title = ""
    val authors = mutableListOf<String>()
    var annotation = ""
    val body = StringBuilder()
    val stack = ArrayDeque<String>()
    var capture: String? = null
    var captured = StringBuilder()
    var inMainBody = false
    var mainBodySeen = false
    var inAnnotation = false
    var titleInfoDepth = 0
    var paragraphDepth = 0
    while (true) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                val tag = parser.name.lowercase()
                stack.addLast(tag)
                if (tag == "body" && !mainBodySeen) { inMainBody = true; mainBodySeen = true }
                if (tag == "title-info") titleInfoDepth = stack.size
                if (titleInfoDepth > 0 && tag == "author") {
                    val first = parser.getAttributeValue(null, "first-name")
                    val middle = parser.getAttributeValue(null, "middle-name")
                    val last = parser.getAttributeValue(null, "last-name")
                    val full = listOfNotNull(first, middle, last).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                    if (full.isNotBlank()) authors.add(full)
                }
                if (titleInfoDepth > 0 && tag == "book-title") { capture = "title"; captured = StringBuilder() }
                if (titleInfoDepth > 0 && tag == "annotation") { inAnnotation = true; capture = "annotation"; captured = StringBuilder() }
                if (inMainBody && tag in setOf("title", "p", "subtitle", "text-author", "empty-line", "section")) {
                    when (tag) {
                        "title" -> { if (body.isNotEmpty()) body.append("\n\n"); body.append("\n") ; paragraphDepth++ }
                        "p", "subtitle", "text-author" -> { if (body.isNotEmpty()) body.append("\n"); paragraphDepth++ }
                        "empty-line" -> body.append("\n")
                    }
                }
            }
            XmlPullParser.TEXT -> {
                val t = parser.text
                if (capture != null) captured.append(t)
                if (inMainBody) body.append(t)
            }
            XmlPullParser.END_TAG -> {
                val tag = parser.name.lowercase()
                if (capture == "title" && tag == "book-title") { title = captured.toString().trim(); capture = null }
                if (capture == "annotation" && tag == "annotation") { annotation = captured.toString().trim(); capture = null; inAnnotation = false }
                if (inMainBody && tag in setOf("title", "p", "subtitle", "text-author", "empty-line")) body.append("\n")
                if (tag == "body" && inMainBody) inMainBody = false
                if (tag == "title-info") titleInfoDepth = 0
                if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        if (parser.next() == XmlPullParser.END_DOCUMENT) break
    }
    val text = body.toString().replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    val normalizedText = if (Regex("</?(?:p|div|br|strong|em|html|body)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
        Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
    } else text
    val cleanedText = normalizedText
        .replace(Regex("\\\\n"), "\n")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    require(cleanedText.isNotBlank()) { "В FB2 не найден основной текст книги." }
    return Fb2Document(title, authors.distinct().joinToString(", "), annotation, cleanedText)
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

// Style chapter titles without modifying the source string or saved character offsets.
private val chapterTitlePattern = Regex("(?im)^\\s*(?:глава\\b[^\\n]{0,65}|chapter\\b[^\\n]{0,65})\\s*$")

private fun styledPage(pageText: String) = buildAnnotatedString {
    var cursor = 0
    chapterTitlePattern.findAll(pageText).forEach { match ->
        append(pageText.substring(cursor, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(match.value)
        }
        cursor = match.range.last + 1
    }
    append(pageText.substring(cursor))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenReaderApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("openreader", Context.MODE_PRIVATE) }
    val books = remember { mutableStateListOf<LocalBook>().apply { addAll(loadBooks(context)) } }
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentBookUri by rememberSaveable { mutableStateOf<String?>(null) }
    val currentBook = books.firstOrNull { it.uri == currentBookUri }
    var content by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var editingBook by remember { mutableStateOf<LocalBook?>(null) }
    var editedTitle by remember { mutableStateOf("") }
    var fontSize by remember { mutableFloatStateOf(prefs.getFloat("font_size", 19f)) }

    fun openBook(book: LocalBook) {
        currentBookUri = book.uri
        error = null
    }

    LaunchedEffect(currentBookUri) {
        val book = books.firstOrNull { it.uri == currentBookUri }
        if (book == null) {
            content = ""
        } else {
            content = ""
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (book.format == "fb2") {
                        val doc = readFb2(context, Uri.parse(book.uri))
                        val text = (if (doc.annotation.isNotBlank()) "Аннотация\n\n${doc.annotation}\n\n" else "") + doc.body
                        Pair(text, doc.title)
                    } else Pair(cleanBookText(readTxt(context, Uri.parse(book.uri))), "")
                }
                content = loaded.first
                if (book.format == "fb2" && loaded.second.isNotBlank()) {
                    val sourceName = nameHintDisplayName(context, Uri.parse(book.uri)).substringBeforeLast(".")
                    if (book.title == sourceName) {
                        val index = books.indexOfFirst { it.uri == book.uri }
                        if (index >= 0) {
                            books[index] = book.copy(title = loaded.second)
                            saveBooks(context, books)
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Не удалось открыть книгу."
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val mime = context.contentResolver.getType(uri) ?: ""
                val isFb2 = mime.contains("fb2", true) || nameHintFb2(context, uri)
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "Книга TXT"
                val existing = books.firstOrNull { it.uri == uri.toString() }
                val proposed = existing?.title ?: name.substringBeforeLast(".").ifBlank { name }
                val book = LocalBook(uri.toString(), proposed, if (isFb2) "fb2" else "txt")
                if (existing != null) {
                    books[books.indexOf(existing)] = book
                    saveBooks(context, books)
                } else {
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
                            TextButton(onClick = { currentBookUri = null; content = "" }) {
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
                        val heightPx = with(density) { (maxHeight - 30.dp).roundToPx() }
                        val fontPx = with(density) { fontSize.sp.toPx() }
                        val starts by produceState<List<Int>?>(initialValue = null, content, widthPx, heightPx, fontPx) {
                            value = withContext(Dispatchers.Default) {
                                pageStarts(content, widthPx, heightPx, fontPx)
                            }
                        }
                        val safePosition = charPosition.coerceIn(0, content.length)
                        val readyStarts = starts
                        val page = (readyStarts?.indexOfLast { it <= safePosition } ?: 0).coerceAtLeast(0)
                        fun moveTo(index: Int) {
                            val pages = readyStarts ?: return
                            val target = index.coerceIn(0, pages.lastIndex)
                            charPosition = pages[target]
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
                            Box(
                                Modifier.weight(1f).fillMaxWidth()
                                    .pointerInput(readyStarts, page) {
                                        var dragDistance = 0f
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { _, delta -> dragDistance += delta },
                                            onDragEnd = {
                                                if (dragDistance < -60f) moveTo(page + 1)
                                                else if (dragDistance > 60f) moveTo(page - 1)
                                                dragDistance = 0f
                                            }
                                        )
                                    }
                                    .pointerInput(readyStarts, page, showControls) {
                                        detectTapGestures { tap ->
                                            when {
                                                tap.x < size.width * 0.25f -> moveTo(page - 1)
                                                tap.x > size.width * 0.75f -> moveTo(page + 1)
                                                else -> showControls = !showControls
                                            }
                                        }
                                    }
                            ) {
                                if (content.isNotEmpty() && readyStarts != null) {
                                    val endChar = if (page + 1 < readyStarts.size) readyStarts[page + 1] else content.length
                                    Text(
                                        styledPage(content.substring(readyStarts[page].coerceIn(0, content.length), endChar.coerceIn(readyStarts[page].coerceIn(0, content.length), content.length))),
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * 1.5f).sp,
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)
                                    )
                                } else {
                                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                                }
                            }
                            Text(
                                if (content.isEmpty() || readyStarts == null) "Загрузка книги…" else "${page + 1} / ${readyStarts.size}",
                                modifier = Modifier.fillMaxWidth().height(30.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (showControls) {
                            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surface), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(12f); prefs.edit().putFloat("font_size", fontSize).apply() }) { Text("A−") }
                                Text("Шрифт: ${fontSize.toInt()}")
                                TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(32f); prefs.edit().putFloat("font_size", fontSize).apply() }) { Text("A+") }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            listOf("Моя библиотека", "Избранное", "Папки и коллекции", "Прочитанные книги")[selectedTab],
                            style = MaterialTheme.typography.headlineMedium
                        )
                        if (selectedTab == 0) {
                            Button(onClick = { picker.launch(arrayOf("text/plain", "text/*", "application/octet-stream", "application/x-fictionbook+xml", "application/xml", "text/xml")) }) {
                                Text("+ Добавить TXT / FB2")
                            }
                            Text("TXT и FB2: постраничное чтение. EPUB добавим позже.", style = MaterialTheme.typography.bodySmall)
                            if (books.isEmpty()) {
                                Text("Книг пока нет. Нажмите кнопку выше, чтобы выбрать TXT или FB2.")
                            }
                            books.forEach { book ->
                                Card(onClick = { openBook(book) }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            val cover by produceState<android.graphics.Bitmap?>(initialValue = null, book.uri, book.format) {
                                                value = if (book.format == "fb2") withContext(Dispatchers.IO) {
                                                    loadFb2Cover(context, Uri.parse(book.uri))
                                                } else null
                                            }
                                            Box(
                                                Modifier.width(74.dp).height(104.dp)
                                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (cover != null) {
                                                    Image(
                                                        bitmap = cover!!.asImageBitmap(),
                                                        contentDescription = "Обложка книги",
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                } else {
                                                    Text("📖", style = MaterialTheme.typography.headlineMedium)
                                                }
                                            }
                                            Column {
                                                Text(book.title, style = MaterialTheme.typography.titleMedium)
                                                Text(book.format.uppercase(), style = MaterialTheme.typography.labelSmall)
                                                TextButton(onClick = { editingBook = book; editedTitle = book.title }) { Text("Переименовать") }
                                            }
                                        }
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
        if (editingBook != null) {
            AlertDialog(
                onDismissRequest = { editingBook = null },
                title = { Text("Название книги") },
                text = {
                    OutlinedTextField(
                        value = editedTitle,
                        onValueChange = { editedTitle = it },
                        label = { Text("Название") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val original = editingBook
                        val newTitle = editedTitle.trim()
                        if (original != null && newTitle.isNotEmpty()) {
                            val index = books.indexOfFirst { it.uri == original.uri }
                            if (index >= 0) books[index] = original.copy(title = newTitle)
                            // The selected book is derived from the updated library list.
                            saveBooks(context, books)
                            editingBook = null
                        }
                    }) { Text("Сохранить") }
                },
                dismissButton = { TextButton(onClick = { editingBook = null }) { Text("Отмена") } }
            )
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
