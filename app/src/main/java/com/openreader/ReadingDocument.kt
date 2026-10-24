package com.openreader

/** Shared text representation used by format importers and the reading engine. */
internal data class ReadingDocument(
    val text: String,
    val title: String = "",
    val author: String = "",
    val sourceFormat: String = "txt"
)
