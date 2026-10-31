package com.openreader.mobi.entities

data class TOC(
    val label: String,
    val href: String,
    val subitems: List<TOC>? = null
)
