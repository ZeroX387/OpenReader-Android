package com.openreader

import android.content.Context
import android.net.Uri
import org.apache.poi.hwpf.extractor.WordExtractor
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

internal fun readLegacyDocText(context: Context, uri: Uri): String {
    // Imported books are stored in the app's private folder and represented by file:// URIs.
    // SAF/library documents use content:// URIs and are opened through ContentResolver.
    val localFile = if (uri.scheme == "file") {
        File(uri.path ?: error("Некорректный путь к DOC-файлу."))
    } else null
    if (localFile != null && !localFile.isFile) error("DOC-файл не найден.")
    if (localFile != null && localFile.length() > 35_000_000L) {
        error("Документ DOC слишком большой (лимит 35 МБ).")
    }

    val source = if (localFile != null) {
        FileInputStream(localFile)
    } else {
        context.contentResolver.openInputStream(uri)
            ?: error("Не удалось открыть DOC-файл.")
    }
    source.use { stream ->
        val input = BufferedInputStream(stream, 64 * 1024)
        val extractor = try {
            WordExtractor(input)
        } catch (e: Exception) {
            try { input.close() } catch (_: Exception) {}
            throw IllegalArgumentException(
                "Не удалось прочитать старый документ Word. Возможно, файл повреждён, защищён паролем или имеет неподдерживаемую версию.",
                e
            )
        }

        val rawText = try {
            extractor.text
        } finally {
            extractor.close()
        }

        val text = rawText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        require(text.isNotBlank()) { "В документе DOC не найден текст." }
        require(text.length <= 5_000_000) { "В документе DOC слишком много текста для этой версии." }
        return text
    }
}
