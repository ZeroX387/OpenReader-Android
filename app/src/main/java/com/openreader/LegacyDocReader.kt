package com.openreader

import android.content.Context
import android.net.Uri
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal fun readLegacyDocText(context: Context, uri: Uri): String {
    // HWPF/POIFS can read from a seekable file directly. Avoid feeding it a
    // generic ContentResolver stream, which forces POI to buffer the OLE2 file.
    val existingFile = if (uri.scheme == "file") {
        File(uri.path ?: error("Некорректный путь к DOC-файлу."))
    } else null
    if (existingFile != null && !existingFile.isFile) error("DOC-файл не найден.")

    val inputFile = existingFile ?: File.createTempFile("openreader_doc_", ".doc", context.cacheDir)
    try {
        if (existingFile == null) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(inputFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 35_000_000L) {
                            error("Документ DOC слишком большой (лимит 35 МБ).")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("Не удалось открыть DOC-файл.")
        }

        if (inputFile.length() > 35_000_000L) {
            error("Документ DOC слишком большой (лимит 35 МБ).")
        }
        require(inputFile.length() > 0L) { "DOC-файл пуст." }

        val rawText = POIFSFileSystem(inputFile).use { filesystem ->
            val document = HWPFDocument(filesystem)
            try {
                val extractor = WordExtractor(document)
                try {
                    extractor.text
                } finally {
                    extractor.close()
                }
            } finally {
                try { document.close() } catch (_: Exception) {}
            }
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
    } catch (e: Exception) {
        if (e.message?.contains("лимит 35 МБ") == true ||
            e.message?.contains("DOC-файл пуст") == true ||
            e.message?.contains("Не удалось открыть DOC-файл") == true) throw e
        throw IllegalArgumentException(
            "Не удалось прочитать старый документ Word. Возможно, файл повреждён, защищён паролем или имеет неподдерживаемую версию.",
            e
        )
    } finally {
        if (existingFile == null) inputFile.delete()
    }
}
