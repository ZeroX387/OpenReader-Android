package com.openreader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

internal data class ScannedBook(
    val uri: String,
    val title: String,
    val format: String
)

internal fun scanBookFolders(context: Context, treeUris: Set<String>): List<ScannedBook> {
    val resolver = context.contentResolver
    val found = linkedMapOf<String, ScannedBook>()
    val visited = HashSet<String>()
    val supported = mapOf(
        "txt" to "txt", "fb2" to "fb2", "epub" to "epub",
        "pdf" to "pdf", "doc" to "doc", "docx" to "docx",
        "mobi" to "mobi", "prc" to "mobi", "azw" to "azw3", "azw3" to "azw3"
    )

    fun walk(treeUri: Uri, parentId: String, depth: Int) {
        if (depth > 12 || found.size >= 10000) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext() && found.size < 10000) {
                if (idColumn < 0 || nameColumn < 0) continue
                val documentId = cursor.getString(idColumn) ?: continue
                val name = cursor.getString(nameColumn)?.trim().orEmpty()
                if (name.isBlank()) continue
                val mime = if (mimeColumn >= 0) cursor.getString(mimeColumn).orEmpty() else ""
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (visited.add(documentId)) walk(treeUri, documentId, depth + 1)
                } else {
                    val extension = name.substringAfterLast('.', "").lowercase()
                    val format = supported[extension] ?: continue
                    val key = documentUri.toString()
                    found.putIfAbsent(key, ScannedBook(key, name.substringBeforeLast('.').ifBlank { name }, format))
                }
            }
        }
    }

    treeUris.forEach { raw ->
        try {
            val treeUri = Uri.parse(raw)
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            walk(treeUri, rootId, 0)
        } catch (_: Exception) {
            // A folder may have been moved or its permission revoked; keep scanning the others.
        }
    }
    return found.values.toList()
}
