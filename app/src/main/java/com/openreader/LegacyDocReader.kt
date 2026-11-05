package com.openreader

import android.content.Context
import android.net.Uri
import org.apache.poi.hwpf.extractor.WordExtractor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal fun readLegacyDocText(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > 35_000_000) {
                error("Документ DOC слишком большой (лимит 35 МБ).")
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Не удалось открыть DOC-файл.")

    require(bytes.size >= 8) { "Файл DOC пустой или повреждён." }

    val extractor = try {
        WordExtractor(ByteArrayInputStream(bytes))
    } catch (e: Exception) {
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
        .replace("\\r\\n", "\\n")
        .replace('\\r', '\\n')
        .replace(Regex("[ \\t]+\\n"), "\\n")
        .replace(Regex("\\n{3,}"), "\\n\\n")
        .trim()

    require(text.isNotBlank()) { "В документе DOC не найден текст." }
    require(text.length <= 5_000_000) { "В документе DOC слишком много текста для этой версии." }
    return text
}
