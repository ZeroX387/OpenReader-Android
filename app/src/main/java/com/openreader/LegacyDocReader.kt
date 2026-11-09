package com.openreader

import android.content.Context
import android.net.Uri
import org.apache.poi.hwpf.extractor.WordExtractor
import java.io.BufferedInputStream

internal fun readLegacyDocText(context: Context, uri: Uri): String {
    val asset = context.contentResolver.openAssetFileDescriptor(uri, "r")
        ?: error("Не удалось открыть DOC-файл.")

    asset.use { descriptor ->
        // Check the known file size without first copying the entire document into a byte array.
        if (descriptor.length > 35_000_000L) {
            error("Документ DOC слишком большой (лимит 35 МБ).")
        }

        val input = BufferedInputStream(descriptor.createInputStream(), 64 * 1024)
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
