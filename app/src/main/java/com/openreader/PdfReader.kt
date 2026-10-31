package com.openreader

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

internal fun readPdfText(context: Context, uri: Uri): String {
    PDFBoxResourceLoader.init(context.applicationContext)
    val input = context.contentResolver.openInputStream(uri)
        ?: error("Не удалось открыть PDF-файл.")
    input.use { stream ->
        PDDocument.load(stream).use { document ->
            require(!document.isEncrypted) { "PDF защищён паролем и пока не поддерживается." }
            require(document.numberOfPages > 0) { "В PDF нет страниц." }
            val text = PDFTextStripper().getText(document).trim()
            require(text.isNotBlank()) {
                "В PDF не найден текст. Возможно, это скан: распознавание текста (OCR) пока не поддерживается."
            }
            require(text.length <= 5_000_000) { "В PDF слишком много текста для этой версии." }
            return text
        }
    }
}
