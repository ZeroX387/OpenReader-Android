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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
    private var externalBook by mutableStateOf<Uri?>(null)
    private fun acceptIntent(incoming: android.content.Intent?) {
        externalBook = when (incoming?.action) {
            android.content.Intent.ACTION_VIEW -> incoming.data
            android.content.Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    incoming.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
                else incoming.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
            }
            else -> null
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptIntent(intent)
        setContent { OpenReaderApp(externalBook) }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }
}

private data class LocalBook(val uri: String, val title: String, val format: String = "txt", val author: String = "", val coverUri: String = "", val favorite: Boolean = false, val finished: Boolean = false, val genre: String = "", val collection: String = "", val addedAt: Long = 0L, val length: Int = 0)

private fun loadBooks(context: Context): List<LocalBook> {
    val prefs = context.getSharedPreferences("openreader", Context.MODE_PRIVATE)
    val saved = prefs.getString("books", "[]") ?: "[]"
    return try {
        val array = org.json.JSONArray(saved)
        (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            LocalBook(obj.getString("uri"), obj.getString("title"), obj.optString("format", "txt"), obj.optString("author", ""), obj.optString("coverUri", ""), obj.optBoolean("favorite"), obj.optBoolean("finished"), obj.optString("genre", ""), obj.optString("collection", ""), obj.optLong("addedAt"), obj.optInt("length"))
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
        array.put(org.json.JSONObject().put("uri", book.uri).put("title", book.title).put("format", book.format).put("author", book.author).put("coverUri", book.coverUri).put("favorite", book.favorite).put("finished", book.finished).put("genre", book.genre).put("collection", book.collection).put("addedAt", book.addedAt).put("length", book.length))
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
    var inAuthor = false
    var authorPart: String? = null
    val authorNames = mutableListOf<String>()
    while (true) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                val tag = parser.name.lowercase()
                stack.addLast(tag)
                if (tag == "body" && !mainBodySeen) { inMainBody = true; mainBodySeen = true }
                if (tag == "title-info") titleInfoDepth = stack.size
                if (titleInfoDepth > 0 && tag == "author") {
                    inAuthor = true
                    authorNames.clear()
                    val first = parser.getAttributeValue(null, "first-name")
                    val middle = parser.getAttributeValue(null, "middle-name")
                    val last = parser.getAttributeValue(null, "last-name")
                    val full = listOfNotNull(first, middle, last).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                    if (full.isNotBlank()) authors.add(full)
                }
                if (inAuthor && tag in setOf("first-name", "middle-name", "last-name")) authorPart = tag
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
                if (inAuthor && authorPart != null && t.isNotBlank()) authorNames.add(t.trim())
                if (inMainBody) body.append(t)
            }
            XmlPullParser.END_TAG -> {
                val tag = parser.name.lowercase()
                if (tag == authorPart) authorPart = null
                if (tag == "author" && inAuthor) {
                    if (authorNames.isNotEmpty()) authors.add(authorNames.joinToString(" "))
                    inAuthor = false
                }
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
    if (!Regex("</?(?:p|br|div|html|body|a)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return raw
    return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        .replace(Regex("\\n{3,}"), "\n\n").trim()
}

private fun pageStarts(text: String, widthPx: Int, heightPx: Int, fontPx: Float, anchor: Int, spacing: Float): List<Int> {
    if (text.isEmpty() || widthPx <= 0 || heightPx <= 0) return listOf(0)
    val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontPx
        typeface = Typeface.DEFAULT
    }
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
        .setIncludePad(false)
        .setLineSpacing((fontPx * (spacing - 1f)).coerceAtLeast(0f), 1f)
        .build()
    if (layout.lineCount == 0) return listOf(0)
    val anchorLine = layout.getLineForOffset(anchor.coerceIn(0, text.lastIndex))
    val lineStarts = mutableListOf(anchorLine)
    var firstLine = anchorLine
    while (firstLine > 0) {
        var previous = firstLine - 1
        while (previous > 0 && layout.getLineBottom(firstLine - 1) - layout.getLineTop(previous - 1) <= heightPx) {
            previous--
        }
        firstLine = previous
        lineStarts.add(0, firstLine)
    }
    var current = anchorLine
    while (current < layout.lineCount - 1) {
        var next = current + 1
        while (next < layout.lineCount && layout.getLineBottom(next) - layout.getLineTop(current) <= heightPx) {
            next++
        }
        if (next >= layout.lineCount) break
        lineStarts.add(next)
        current = next
    }
    // Keep the exact saved character offset as the first visible character.
    // Snapping to a reflowed line boundary can skip text on rotation.
    val exactAnchor = anchor.coerceIn(0, text.lastIndex)
    return (lineStarts.map { layout.getLineStart(it) } + exactAnchor)
        .distinct().sorted()
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
private fun OpenReaderApp(externalBook: Uri? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("openreader", Context.MODE_PRIVATE) }
    var languageChoice by remember { mutableStateOf(prefs.getString("language", "system") ?: "system") }
    val systemLanguage = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val englishUi = (if (languageChoice == "system") systemLanguage else languageChoice) != "ru"
    fun tr(ru: String, en: String) = if (englishUi) en else ru
    val books = remember { mutableStateListOf<LocalBook>().apply { addAll(loadBooks(context)) } }
    var notesBook by remember { mutableStateOf<LocalBook?>(null) }
    var notesMode by remember { mutableStateOf("Закладки") }
    var quoteDraft by remember { mutableStateOf("") }
    var quotePosition by remember { mutableIntStateOf(0) }
    var bookmarkPosition by remember { mutableIntStateOf(0) }
    var quoteEditor by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var sortMode by remember { mutableStateOf(prefs.getString("sort_mode", "Добавление") ?: "Добавление") }
    var sortExpanded by remember { mutableStateOf(false) }
    var newCollection by remember { mutableStateOf("") }
    var collectionDialog by remember { mutableStateOf(false) }
    var collectionTarget by remember { mutableStateOf<LocalBook?>(null) }
    var collectionName by remember { mutableStateOf("") }
    var currentBookUri by rememberSaveable { mutableStateOf<String?>(null) }
    val currentBook = books.firstOrNull { it.uri == currentBookUri }
    var content by remember { mutableStateOf("") }
    var loadedBookUri by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editingBook by remember { mutableStateOf<LocalBook?>(null) }
    var editedTitle by remember { mutableStateOf("") }
    var editedAuthor by remember { mutableStateOf("") }
    var menuBookUri by remember { mutableStateOf<String?>(null) }
    var coverTargetUri by remember { mutableStateOf<String?>(null) }
    var bookToRemove by remember { mutableStateOf<LocalBook?>(null) }
    var fontSize by remember { mutableFloatStateOf(prefs.getFloat("font_size", 19f)) }
    var showSettings by remember { mutableStateOf(false) }
    var showReaderSettings by remember { mutableStateOf(false) }
    var readerTheme by remember { mutableStateOf(prefs.getString("reader_theme", "paper") ?: "paper") }
    var readerSpacing by remember { mutableFloatStateOf(prefs.getFloat("reader_spacing", 1.5f)) }
    var readerMargin by remember { mutableFloatStateOf(prefs.getFloat("reader_margin", 18f)) }
    val readerBackground = when (readerTheme) {
        "dark" -> androidx.compose.ui.graphics.Color(0xFF181818)
        "sepia" -> androidx.compose.ui.graphics.Color(0xFFF1E3C8)
        "light" -> androidx.compose.ui.graphics.Color.White
        else -> androidx.compose.ui.graphics.Color(0xFFFAF7F0)
    }
    val readerInk = if (readerTheme == "dark") androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF242424)
    var themeChoice by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    val isDark = when (themeChoice) {
        "dark" -> true
        "light" -> false
        else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    val appVersion = "0.4.6"
    var availableUpdate by remember { mutableStateOf<String?>(null) }
    var updateStatus by remember { mutableStateOf("Ещё не проверяли") }
    var updateCheck by remember { mutableIntStateOf(0) }
    LaunchedEffect(updateCheck) {
        updateStatus = "Проверяем..."
        try {
            val latest = withContext(Dispatchers.IO) {
                val connection = java.net.URL("https://api.github.com/repos/ZeroX387/OpenReader-Android/releases?per_page=10").openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "OpenReader-Android")
                connection.setRequestProperty("Cache-Control", "no-cache")
                try {
                    if (connection.responseCode != 200) error("GitHub HTTP " + connection.responseCode)
                    org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                        .let { releases -> (0 until releases.length()).map { releases.getJSONObject(it) }.firstOrNull { !it.optBoolean("draft") }?.optString("tag_name") ?: "" }.removePrefix("v")
                } finally { connection.disconnect() }
            }
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
            fun numbers(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
            val a = numbers(latest)
            val b = numbers(installed)
            val firstDifference = (0..2).firstOrNull { a.getOrElse(it) { 0 } != b.getOrElse(it) { 0 } }
            if (latest.isBlank()) {
                updateStatus = "Не удалось получить номер последней версии"
            } else if (firstDifference != null && a.getOrElse(firstDifference) { 0 } > b.getOrElse(firstDifference) { 0 }) {
                updateStatus = "Доступна версия $latest (установлена $installed)"
                if (prefs.getString("update_notice_seen", "") != latest) availableUpdate = latest
            } else {
                updateStatus = "Установлена актуальная версия $installed"
            }
        } catch (e: Exception) {
            updateStatus = "Ошибка проверки (" + e.javaClass.simpleName + "): " + (e.message ?: "нет подробностей")
        }
    }
    // Release notes belong to the installed version, never to a fixed old announcement.
    val releaseNotes = mapOf(
        "0.4.3" to "Улучшены названия и авторы EPUB, импортированных из других приложений. Исправлено отображение переносов строк. Добавлена проверка обновлений.",
        "0.4.4" to "В настройках появился выбор языка: как в системе, русский или английский. Начат перевод интерфейса.",
        "0.4.5" to "Улучшен расчёт страниц и межстрочных интервалов. Добавлен запас пространства, чтобы последняя строка не обрезалась.",
        "0.4.6" to "Исправлено окно «Что нового»: теперь описание соответствует установленной версии, а не повторяет старое обновление."
    )
    val currentReleaseNotes = releaseNotes[appVersion]
        ?: "Обновление OpenReader $appVersion. Подробности доступны на странице выпуска GitHub."
    var showWhatsNew by remember { mutableStateOf(prefs.getString("last_seen_version", null) != appVersion) }

    fun openBook(book: LocalBook) {
        currentBookUri = book.uri
        error = null
    }

    LaunchedEffect(currentBookUri) {
        val book = books.firstOrNull { it.uri == currentBookUri }
        loadedBookUri = null
        if (book == null) {
            content = ""
        } else {
            content = ""
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (book.format == "epub") {
                        val doc = readEpub(context, Uri.parse(book.uri))
                        Triple(doc.text, doc.title, doc.author)
                    } else if (book.format == "fb2") {
                        val doc = readFb2(context, Uri.parse(book.uri))
                        val text = (if (doc.annotation.isNotBlank()) "Аннотация\n\n${cleanBookText(doc.annotation)}\n\n" else "") + doc.body
                        Triple(text, doc.title, doc.authors)
                    } else Triple(cleanBookText(readTxt(context, Uri.parse(book.uri))), "", "")
                }
                content = loaded.first
                val loadedIndex = books.indexOfFirst { it.uri == book.uri }
                if (loadedIndex >= 0 && books[loadedIndex].length != loaded.first.length) {
                    books[loadedIndex] = books[loadedIndex].copy(length = loaded.first.length)
                    saveBooks(context, books)
                }
                loadedBookUri = book.uri
                if (book.format == "fb2" || book.format == "epub") {
                    val sourceName = nameHintDisplayName(context, Uri.parse(book.uri)).substringBeforeLast(".")
                    if (book.title == sourceName || book.title.startsWith("openreader_") || book.author.isBlank()) {
                        val index = books.indexOfFirst { it.uri == book.uri }
                        if (index >= 0) {
                            books[index] = book.copy(title = if ((book.title == sourceName || book.title.startsWith("openreader_")) && loaded.second.isNotBlank()) loaded.second else book.title, author = loaded.third.ifBlank { book.author })
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
                val isEpub = mime.contains("epub", true) || nameHintDisplayName(context, uri).endsWith(".epub", ignoreCase = true)
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "Книга TXT"
                val existing = books.firstOrNull { it.uri == uri.toString() }
                val proposed = existing?.title ?: name.substringBeforeLast(".").ifBlank { name }
                val book = LocalBook(uri.toString(), proposed, if (isEpub) "epub" else if (isFb2) "fb2" else "txt", existing?.author.orEmpty(), existing?.coverUri.orEmpty(), existing?.favorite ?: false, existing?.finished ?: false, existing?.genre.orEmpty(), existing?.collection.orEmpty(), existing?.addedAt ?: System.currentTimeMillis(), existing?.length ?: 0)
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

    LaunchedEffect(externalBook) {
        if (externalBook != null) {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val filename = nameHintDisplayName(context, externalBook).ifBlank { "Книга" }
                    val format = when {
                        filename.endsWith(".epub", true) || context.contentResolver.getType(externalBook)?.contains("epub", true) == true -> "epub"
                        filename.endsWith(".fb2", true) || context.contentResolver.getType(externalBook)?.contains("fb2", true) == true -> "fb2"
                        else -> {
                            val signature = context.contentResolver.openInputStream(externalBook)?.use { input ->
                                ByteArray(4).also { input.read(it) }
                            }
                            if (signature != null && signature.size == 4 &&
                                signature[0] == 80.toByte() && signature[1] == 75.toByte() &&
                                signature[2] == 3.toByte() && signature[3] == 4.toByte()) "epub" else "txt"
                        }
                    }
                    val folder = java.io.File(context.filesDir, "imported_books").apply { mkdirs() }
                    val suffix = when (format) { "epub" -> ".epub"; "fb2" -> ".fb2"; else -> ".txt" }
                    val file = java.io.File.createTempFile("openreader_", suffix, folder)
                    try {
                        context.contentResolver.openInputStream(externalBook)?.use { source ->
                            file.outputStream().use { target ->
                                val buffer = ByteArray(8192)
                                var total = 0L
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > 35_000_000) error("Книга слишком большая (лимит 35 МБ).")
                                    target.write(buffer, 0, count)
                                }
                            }
                        } ?: error("Не удалось прочитать выбранный файл.")
                    } catch (e: Exception) { file.delete(); throw e }
                    LocalBook(Uri.fromFile(file).toString(), if (format == "epub") "openreader_" + file.nameWithoutExtension else filename.substringBeforeLast('.').ifBlank { filename }, format, addedAt = System.currentTimeMillis())
                }
                books.add(0, imported)
                saveBooks(context, books)
                openBook(imported)
            } catch (e: Exception) {
                error = e.message ?: "Не удалось импортировать книгу."
            }
        }
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = coverTargetUri
        if (uri != null && target != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val index = books.indexOfFirst { it.uri == target }
                if (index >= 0) { books[index] = books[index].copy(coverUri = uri.toString()); saveBooks(context, books) }
            } catch (e: Exception) { error = e.message ?: "Не удалось выбрать обложку." }
        }
        coverTargetUri = null
    }

    MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
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
                    actions = {
                        if (currentBook == null) {
                            IconButton(onClick = { showSettings = true }) {
                                Text("⚙️", style = MaterialTheme.typography.titleLarge)
                            }
                        }
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
                    var charPosition by rememberSaveable(book.uri) {
                        mutableIntStateOf(prefs.getInt(positionKey, 0).coerceAtLeast(0))
                    }
                    var showControls by remember(book.uri) { mutableStateOf(false) }
                    LaunchedEffect(book.uri) { bookmarkPosition = prefs.getInt("jump_" + book.uri.hashCode(), -1) }
                    LaunchedEffect(book.uri, bookmarkPosition) {
                        if (bookmarkPosition >= 0) {
                            charPosition = bookmarkPosition.coerceAtLeast(0)
                            bookmarkPosition = -1
                            prefs.edit().putInt("jump_" + book.uri.hashCode(), -1).apply()
                        }
                    }

                    val density = LocalDensity.current
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val widthPx = with(density) { (maxWidth - (readerMargin * 2).dp).roundToPx() }
                        // Reserve fixed space for controls and page counter so pagination
                        // doesn't change when the controls are shown or hidden.
                        val heightPx = with(density) { (maxHeight - 54.dp).roundToPx() }
                        val fontPx = with(density) { fontSize.sp.toPx() }
                        val starts by key(content, widthPx, heightPx, fontPx, readerSpacing) {
                            produceState<List<Int>?>(initialValue = null, content, widthPx, heightPx, fontPx, charPosition) {
                            value = withContext(Dispatchers.Default) {
                                pageStarts(content, widthPx, heightPx, fontPx, charPosition, readerSpacing)
                            }
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
                                Modifier.weight(1f).fillMaxWidth().background(readerBackground)
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
                                if (loadedBookUri == book.uri && content.isNotEmpty() && readyStarts != null && readyStarts.firstOrNull() != null) {
                                    val endChar = if (page + 1 < readyStarts.size) readyStarts[page + 1] else content.length
                                    Text(
                                        styledPage(content.substring(readyStarts[page].coerceIn(0, content.length), endChar.coerceIn(readyStarts[page].coerceIn(0, content.length), content.length))),
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * readerSpacing).sp,
                                        color = readerInk,
                                        modifier = Modifier.fillMaxSize().padding(horizontal = readerMargin.dp, vertical = 8.dp)
                                    )
                                } else {
                                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text("📖", style = MaterialTheme.typography.displayLarge)
                                        Text("OpenReader", style = MaterialTheme.typography.headlineMedium)
                                        Text("Подготавливаем книгу…", style = MaterialTheme.typography.bodyMedium)
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                            Text(
                                if (loadedBookUri != book.uri || content.isEmpty() || readyStarts == null) "Загрузка книги…" else "${page + 1} / ${readyStarts.size}",
                                modifier = Modifier.fillMaxWidth().height(30.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (showControls) {
                            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surface), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    val key = "bookmarks_" + book.uri.hashCode()
                                    val items = org.json.JSONArray(prefs.getString(key, "[]") ?: "[]")
                                    if ((0 until items.length()).none { items.optInt(it) == charPosition }) {
                                        items.put(charPosition); prefs.edit().putString(key, items.toString()).apply()
                                    }
                                    notesBook = book; notesMode = "Закладки"
                                }) { Text("🔖") }
                                TextButton(onClick = {
                                    quotePosition = charPosition
                                    quoteDraft = ""
                                    quoteEditor = true
                                }) { Text("❝") }
                                TextButton(onClick = { notesBook = book; notesMode = "Цитаты" }) { Text("☷") }
                                TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(12f); prefs.edit().putFloat("font_size", fontSize).apply() }) { Text("A−") }
                                IconButton(onClick = { showReaderSettings = true }) { Text("⚙️", style = MaterialTheme.typography.titleLarge) }
                                TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(32f); prefs.edit().putFloat("font_size", fontSize).apply() }) { Text("A+") }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            listOf("Моя библиотека", "Избранное", "Папки и коллекции", "Прочитанные книги")[selectedTab],
                            style = MaterialTheme.typography.headlineMedium
                        )
                        if (selectedTab == 0) {
                            Button(onClick = { picker.launch(arrayOf("text/plain", "text/*", "application/octet-stream", "application/x-fictionbook+xml", "application/xml", "text/xml", "application/epub+zip")) }) {
                                Text("+ Добавить TXT / FB2 / EPUB")
                            }
                            Text("TXT, FB2 и EPUB: постраничное чтение.", style = MaterialTheme.typography.bodySmall)
                            if (books.isEmpty()) {
                                Text("Книг пока нет. Нажмите кнопку выше, чтобы выбрать TXT или FB2.")
                            }
                        }
                        if (selectedTab == 2) {
                            Text("Коллекции: " + ((prefs.getStringSet("collections", emptySet()) ?: emptySet()) + books.map { it.collection }.filter { it.isNotBlank() }).distinct().joinToString(", ").ifBlank { "пока нет" })
                            OutlinedButton(onClick = { collectionDialog = true }) { Text("+ Создать коллекцию") }
                        }
                        if (selectedTab != 0 || books.isNotEmpty()) {
                            val sortedBooks = when (sortMode) {
                                "Название" -> books.sortedBy { it.title.lowercase() }
                                "Автор" -> books.sortedWith(compareBy<LocalBook> { it.author.lowercase() }.thenBy { it.title.lowercase() })
                                "Формат" -> books.sortedWith(compareBy<LocalBook> { it.format }.thenBy { it.title.lowercase() })
                                "Прогресс" -> books.sortedByDescending { b -> if (b.finished) 100 else if (b.length > 0) (prefs.getInt("page_char_" + b.uri.hashCode(), 0).toLong() * 100 / b.length).toInt() else 0 }
                                "Жанр" -> books.sortedBy { it.genre.lowercase() }
                                else -> books.sortedByDescending { it.addedAt }
                            }
                            if (selectedTab == 0) {
                                Box {
                                    OutlinedButton(onClick = { sortExpanded = true }) { Text("Сортировка: " + sortMode + " ▾") }
                                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                                        listOf("Добавление", "Название", "Автор", "Жанр", "Формат", "Прогресс").forEach { option ->
                                            DropdownMenuItem(text = { Text(option) }, onClick = {
                                                sortMode = option; prefs.edit().putString("sort_mode", option).apply(); sortExpanded = false
                                            })
                                        }
                                    }
                                }
                            }
                            sortedBooks.filter { (selectedTab == 0 && !it.finished) || (selectedTab == 1 && it.favorite) || (selectedTab == 3 && it.finished) || (selectedTab == 2 && it.collection.isNotBlank()) }.forEach { book ->
                                Card(onClick = { openBook(book) }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            val cover by produceState<android.graphics.Bitmap?>(initialValue = null, book.uri, book.format, book.coverUri) {
                                                value = withContext(Dispatchers.IO) {
                                                    if (book.coverUri.isNotBlank()) {
                                                        try { context.contentResolver.openInputStream(Uri.parse(book.coverUri))?.use { android.graphics.BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
                                                    } else if (book.format == "fb2") loadFb2Cover(context, Uri.parse(book.uri)) else null
                                                }
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
                                                if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall)
                                                Text(book.format.uppercase() + (if (book.collection.isNotBlank()) " · " + book.collection else ""), style = MaterialTheme.typography.labelSmall)
                                                val percent = if (book.finished) 100 else if (book.length > 0) (prefs.getInt("page_char_" + book.uri.hashCode(), 0).toLong() * 100 / book.length).toInt().coerceIn(0, 99) else 0
                                                Text("Прочитано: $percent%", style = MaterialTheme.typography.bodySmall)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    IconButton(onClick = {
                                                        val i = books.indexOfFirst { it.uri == book.uri }
                                                        if (i >= 0) { books[i] = books[i].copy(favorite = !books[i].favorite); saveBooks(context, books) }
                                                    }) { Text(if (book.favorite) "★" else "☆", color = if (book.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                                                    IconButton(onClick = {
                                                        val i = books.indexOfFirst { it.uri == book.uri }
                                                        if (i >= 0) { books[i] = books[i].copy(finished = !books[i].finished); saveBooks(context, books) }
                                                    }) { Text(if (book.finished) "☑" else "☐", color = if (book.finished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                                                }
                                                Box {
                                                    IconButton(onClick = { menuBookUri = book.uri }) { Text("⋮", style = MaterialTheme.typography.headlineMedium) }
                                                    DropdownMenu(expanded = menuBookUri == book.uri, onDismissRequest = { menuBookUri = null }) {
                                                        DropdownMenuItem(text = { Text("Изменить название и автора") }, onClick = {
                                                            editingBook = book; editedTitle = book.title; editedAuthor = book.author; menuBookUri = null
                                                        })
                                                        DropdownMenuItem(text = { Text("Сменить обложку") }, onClick = {
                                                            menuBookUri = null; coverTargetUri = book.uri; coverPicker.launch(arrayOf("image/*"))
                                                        })
                                                        DropdownMenuItem(text = { Text("Закладки и цитаты") }, onClick = {
                                                            notesBook = book; notesMode = "Закладки"; menuBookUri = null
                                                        })
                                                        DropdownMenuItem(text = { Text("Добавить в коллекцию") }, onClick = {
                                                            collectionTarget = book; collectionName = book.collection; menuBookUri = null
                                                        })
                                                        DropdownMenuItem(text = { Text("Убрать из библиотеки") }, onClick = {
                                                            menuBookUri = null; bookToRemove = book
                                                        })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (collectionDialog) {
            AlertDialog(
                onDismissRequest = { collectionDialog = false },
                title = { Text("Новая коллекция") },
                text = { OutlinedTextField(value = newCollection, onValueChange = { newCollection = it }, label = { Text("Название") }) },
                confirmButton = { TextButton(onClick = {
                    if (newCollection.isNotBlank()) {
                        prefs.edit().putStringSet("collections", (prefs.getStringSet("collections", emptySet()) ?: emptySet()) + newCollection.trim()).apply()
                    }
                    collectionDialog = false; newCollection = ""
                }) { Text("Создать") } },
                dismissButton = { TextButton(onClick = { collectionDialog = false }) { Text("Отмена") } }
            )
        }
        if (collectionTarget != null) {
            val target = collectionTarget!!
            AlertDialog(
                onDismissRequest = { collectionTarget = null },
                title = { Text("Коллекция для «" + target.title + "»") },
                text = {
                    Column {
                        OutlinedTextField(value = collectionName, onValueChange = { collectionName = it }, label = { Text("Название коллекции") })
                        (prefs.getStringSet("collections", emptySet()) ?: emptySet()).forEach { name ->
                            TextButton(onClick = { collectionName = name }) { Text(name) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = {
                    val i = books.indexOfFirst { it.uri == target.uri }
                    if (i >= 0) {
                        books[i] = books[i].copy(collection = collectionName.trim())
                        saveBooks(context, books)
                    }
                    collectionTarget = null
                }) { Text("Сохранить") } },
                dismissButton = { TextButton(onClick = { collectionTarget = null }) { Text("Отмена") } }
            )
        }
        if (quoteEditor && currentBook != null) {
            val book = currentBook!!
            AlertDialog(
                onDismissRequest = { quoteEditor = false },
                title = { Text("Сохранить цитату") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Выдели и скопируй нужный фрагмент из текста ниже, затем вставь его в поле цитаты.")
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(content.drop(quotePosition.coerceIn(0, content.length)).take(1500), modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
                        }
                        OutlinedTextField(value = quoteDraft, onValueChange = { quoteDraft = it }, label = { Text("Текст цитаты") }, minLines = 2, maxLines = 5)
                    }
                },
                confirmButton = { TextButton(enabled = quoteDraft.isNotBlank(), onClick = {
                    val key = "quotes_" + book.uri.hashCode()
                    val items = org.json.JSONArray(prefs.getString(key, "[]") ?: "[]")
                    items.put(org.json.JSONObject().put("position", quotePosition).put("text", quoteDraft.trim()))
                    prefs.edit().putString(key, items.toString()).apply()
                    quoteEditor = false
                }) { Text("Сохранить") } },
                dismissButton = { TextButton(onClick = { quoteEditor = false }) { Text("Отмена") } }
            )
        }
        if (notesBook != null) {
            val target = notesBook!!
            val bookmarks = org.json.JSONArray(prefs.getString("bookmarks_" + target.uri.hashCode(), "[]") ?: "[]")
            val quotes = org.json.JSONArray(prefs.getString("quotes_" + target.uri.hashCode(), "[]") ?: "[]")
            AlertDialog(
                onDismissRequest = { notesBook = null },
                title = { Text("Закладки и цитаты · " + target.title) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Row {
                            TextButton(onClick = { notesMode = "Закладки" }) { Text("🔖 Закладки") }
                            TextButton(onClick = { notesMode = "Цитаты" }) { Text("❝ Цитаты") }
                        }
                        if (notesMode == "Закладки") {
                            if (bookmarks.length() == 0) Text("Закладок пока нет")
                            for (i in 0 until bookmarks.length()) {
                                val pos = bookmarks.optInt(i)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(modifier = Modifier.weight(1f), onClick = {
                                        prefs.edit().putInt("page_char_" + target.uri.hashCode(), pos).putInt("jump_" + target.uri.hashCode(), pos).apply()
                                        currentBookUri = target.uri
                                        bookmarkPosition = pos
                                        notesBook = null
                                    }) { Text("Место " + (i + 1) + " · " + pos + " символ") }
                                    TextButton(onClick = {
                                        val updated = org.json.JSONArray()
                                        for (j in 0 until bookmarks.length()) if (j != i) updated.put(bookmarks.optInt(j))
                                        prefs.edit().putString("bookmarks_" + target.uri.hashCode(), updated.toString()).apply()
                                        notesBook = null
                                    }) { Text("✕") }
                                }
                            }
                        } else {
                            if (quotes.length() == 0) Text("Цитат пока нет")
                            for (i in 0 until quotes.length()) {
                                val entry = quotes.optJSONObject(i) ?: continue
                                Text(entry.optString("text"))
                                Row {
                                    TextButton(onClick = {
                                        val pos = entry.optInt("position")
                                        prefs.edit().putInt("page_char_" + target.uri.hashCode(), pos).putInt("jump_" + target.uri.hashCode(), pos).apply()
                                        currentBookUri = target.uri; bookmarkPosition = pos; notesBook = null
                                    }) { Text("Перейти") }
                                    TextButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Цитата", entry.optString("text")))
                                    }) { Text("Копировать") }
                                    TextButton(onClick = {
                                        val updated = org.json.JSONArray()
                                        for (j in 0 until quotes.length()) if (j != i) updated.put(quotes.getJSONObject(j))
                                        prefs.edit().putString("quotes_" + target.uri.hashCode(), updated.toString()).apply()
                                        notesBook = null
                                    }) { Text("✕") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { notesBook = null }) { Text("Закрыть") } }
            )
        }
        if (bookToRemove != null) {
            val target = bookToRemove!!
            AlertDialog(
                onDismissRequest = { bookToRemove = null },
                title = { Text("Убрать книгу?") },
                text = { Text("«" + target.title + "» исчезнет из библиотеки OpenReader. Сам файл книги останется на устройстве.") },
                confirmButton = {
                    TextButton(onClick = {
                        books.removeAll { it.uri == target.uri }
                        saveBooks(context, books)
                        if (currentBookUri == target.uri) currentBookUri = null
                        bookToRemove = null
                    }) { Text("Убрать") }
                },
                dismissButton = { TextButton(onClick = { bookToRemove = null }) { Text("Отмена") } }
            )
        }
        if (availableUpdate != null && !showWhatsNew) {
            AlertDialog(
                onDismissRequest = { prefs.edit().putString("update_notice_seen", availableUpdate).apply(); availableUpdate = null },
                title = { Text("Доступно обновление") },
                text = { Text("Вышла новая версия OpenReader. Скачать APK?") },
                confirmButton = {
                    TextButton(onClick = {
                        val version = availableUpdate
                        prefs.edit().putString("update_notice_seen", version).apply()
                        availableUpdate = null
                        if (version != null) {
                            val url = "https://github.com/ZeroX387/OpenReader-Android/releases/tag/v" + version
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }) { Text("Скачать") }
                },
                dismissButton = { TextButton(onClick = { prefs.edit().putString("update_notice_seen", availableUpdate).apply(); availableUpdate = null }) { Text("Позже") } }
            )
        }
        if (showWhatsNew) {
            AlertDialog(
                onDismissRequest = {
                    prefs.edit().putString("last_seen_version", appVersion).apply()
                    showWhatsNew = false
                },
                title = { Text("Что нового в OpenReader $appVersion") },
                text = { Text(currentReleaseNotes) },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putString("last_seen_version", appVersion).apply()
                        showWhatsNew = false
                    }) { Text("Понятно") }
                }
            )
        }
        if (showReaderSettings) {
            AlertDialog(
                onDismissRequest = { showReaderSettings = false },
                title = { Text("Оформление книги") },
                text = {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Цвет страниц")
                        listOf("paper" to "Бумажный", "light" to "Белый", "sepia" to "Сепия", "dark" to "Тёмный").forEach { (v, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = readerTheme == v, onClick = {
                                    readerTheme = v
                                    prefs.edit().putString("reader_theme", v).apply()
                                })
                                Text(label, modifier = Modifier.clickable {
                                    readerTheme = v
                                    prefs.edit().putString("reader_theme", v).apply()
                                })
                            }
                        }
                        Text("Размер шрифта")
                        Slider(value = fontSize, onValueChange = {
                            fontSize = it
                            prefs.edit().putFloat("font_size", it).apply()
                        }, valueRange = 12f..32f, steps = 19)
                        Text("Межстрочный интервал")
                        Slider(value = readerSpacing, onValueChange = {
                            readerSpacing = it
                            prefs.edit().putFloat("reader_spacing", it).apply()
                        }, valueRange = 1.1f..2.0f)
                        Text("Поля страницы")
                        Slider(value = readerMargin, onValueChange = {
                            readerMargin = it
                            prefs.edit().putFloat("reader_margin", it).apply()
                        }, valueRange = 8f..40f)
                    }
                },
                confirmButton = { TextButton(onClick = { showReaderSettings = false }) { Text("Готово") } }
            )
        }
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text(tr("Настройки", "Settings")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tr("Тема оформления", "Appearance"))
                        listOf("system" to tr("Как в телефоне", "Follow system"), "light" to tr("Светлая", "Light"), "dark" to tr("Тёмная", "Dark")).forEach { (value, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = themeChoice == value, onClick = {
                                    themeChoice = value
                                    prefs.edit().putString("theme", value).apply()
                                })
                                Text(label, modifier = Modifier.clickable {
                                    themeChoice = value
                                    prefs.edit().putString("theme", value).apply()
                                })
                            }
                        }
                        HorizontalDivider()
                        Text(tr("Язык приложения", "App language"))
                        var languageExpanded by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { languageExpanded = true }) {
                            Text(when (languageChoice) {
                                "ru" -> "Русский"
                                "en" -> "English"
                                else -> tr("Как в системе", "Follow system")
                            } + " ▾")
                        }
                        DropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                            listOf("system" to tr("Как в системе", "Follow system"), "ru" to "Русский", "en" to "English").forEach { (code, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    languageChoice = code
                                    prefs.edit().putString("language", code).apply()
                                    languageExpanded = false
                                })
                            }
                        }
                        HorizontalDivider()
                        Text(tr("Обновления OpenReader", "OpenReader updates"))
                        Text(updateStatus, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { updateCheck++ }) { Text(tr("Проверить обновления", "Check for updates")) }
                    }
                },
                confirmButton = { TextButton(onClick = { showSettings = false }) { Text(tr("Готово", "Done")) } }
            )
        }
        if (editingBook != null) {
            AlertDialog(
                onDismissRequest = { editingBook = null },
                title = { Text("Сведения о книге") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = editedTitle, onValueChange = { editedTitle = it }, label = { Text("Название") }, singleLine = true)
                        OutlinedTextField(value = editedAuthor, onValueChange = { editedAuthor = it }, label = { Text("Автор") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val original = editingBook
                        val newTitle = editedTitle.trim()
                        if (original != null && newTitle.isNotEmpty()) {
                            val index = books.indexOfFirst { it.uri == original.uri }
                            if (index >= 0) books[index] = original.copy(title = newTitle, author = editedAuthor.trim())
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
