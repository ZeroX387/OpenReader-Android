package com.openreader

import android.content.Context
import android.net.Uri
import android.text.Html
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

internal data class EpubDocument(val title: String, val author: String, val text: String)

internal fun readEpub(context: Context, uri: Uri): EpubDocument {
    val entries = mutableMapOf<String, ByteArray>()
    var total = 0L
    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/').removePrefix("/")
                if (name.split('/').any { it == ".." }) continue
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > 35_000_000) error("EPUB слишком большой (лимит 35 МБ распакованных данных).")
                    output.write(buffer, 0, n)
                }
                entries[name] = output.toByteArray()
            }
        }
    } ?: error("Не удалось открыть EPUB.")
    fun xml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) { }
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) { }
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) { }
        try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) { }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        return builder.parse(ByteArrayInputStream(bytes))
    }
    fun elements(parent: org.w3c.dom.Document, name: String): List<Element> {
        val nodes = parent.getElementsByTagNameNS("*", name)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
    val container = entries["META-INF/container.xml"] ?: error("В EPUB отсутствует container.xml.")
    val rootFile = elements(xml(container), "rootfile").firstOrNull()?.getAttribute("full-path")
        ?.takeIf { it.isNotBlank() } ?: error("В EPUB не найден файл описания.")
    val opf = xml(entries[rootFile] ?: error("Не найден файл описания EPUB."))
    val title = elements(opf, "title").firstOrNull()?.textContent?.trim().orEmpty()
    val author = elements(opf, "creator").firstOrNull()?.textContent?.trim().orEmpty()
    val manifest = elements(opf, "item").associate { it.getAttribute("id") to it.getAttribute("href") }
    val base = rootFile.substringBeforeLast('/', "")
    fun resolve(href: String): String {
        val parts = mutableListOf<String>()
        for (part in (if (base.isBlank()) href else "$base/$href").substringBefore('#').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }
    val sections = elements(opf, "itemref").mapNotNull { ref ->
        val href = manifest[ref.getAttribute("idref")] ?: return@mapNotNull null
        val data = entries[resolve(href)] ?: return@mapNotNull null
        val html = data.toString(Charsets.UTF_8)
        val body = Regex("<body\\b[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: html
        Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\n{3,}"), "\n\n").trim().takeIf { it.isNotBlank() }
    }
    if (sections.isEmpty()) error("Не найдены читаемые главы EPUB.")
    return EpubDocument(title, author, sections.joinToString("\n\n"))
}
