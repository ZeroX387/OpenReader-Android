package com.openreader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.openreader.mobi.KF6Book
import com.openreader.mobi.KF8Book
import com.openreader.mobi.MobiReader

internal data class MobiTextDocument(val title: String, val author: String, val text: String)

internal fun readMobiText(context: Context, uri: Uri): MobiTextDocument {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Не удалось открыть MOBI/AZW3-файл.")
    val book = try {
        MobiReader().readMobi(descriptor)
    } catch (e: Exception) {
        try { descriptor.close() } catch (_: Exception) {}
        throw IllegalArgumentException("Не удалось разобрать MOBI/AZW3. Книга может быть защищена DRM или использовать неподдерживаемую структуру.", e)
    }
    try {
        val chunks = when (book) {
            is KF8Book -> book.sections.map { book.getSectionText(it) }
            is KF6Book -> book.sections.map { book.getSectionText(it) }
            else -> emptyList()
        }
        val text = chunks.joinToString("\n\n") { cleanBookText(it) }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        require(text.isNotBlank()) { "В MOBI/AZW3 не удалось извлечь текст. Возможно, файл защищён DRM." }
        require(text.length <= 5_000_000) { "В MOBI/AZW3 слишком много текста для этой версии." }
        return MobiTextDocument(book.metadata.title, book.metadata.author.joinToString(", "), text)
    } finally {
        book.close()
    }
}
