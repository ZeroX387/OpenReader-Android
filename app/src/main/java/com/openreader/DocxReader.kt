package com.openreader

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

internal fun readDocxText(context: Context, uri: Uri): String {
    val input = context.contentResolver.openInputStream(uri)
        ?: error("Не удалось открыть DOCX-файл.")
    input.use { source ->
        ZipInputStream(source).use { zip ->
            var documentXml: String? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 10_000_000) {
                            error("Документ DOCX слишком большой.")
                        }
                        output.write(buffer, 0, count)
                    }
                    documentXml = output.toString("UTF-8")
                    break
                }
                zip.closeEntry()
            }
            val xml = documentXml ?: error("В DOCX не найден основной текст документа.")
            val parser = android.util.Xml.newPullParser()
            parser.setInput(xml.reader())
            val result = StringBuilder()
            var inText = false
            while (true) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "t" -> inText = true
                        "tab" -> result.append('\t')
                        "br", "cr" -> result.append('\n')
                        "p" -> if (result.isNotEmpty() && result.last() != '\n') result.append('\n')
                    }
                    XmlPullParser.TEXT -> if (inText) result.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "t") inText = false
                        if (parser.name == "p" && result.isNotEmpty() && result.last() != '\n') result.append('\n')
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
                parser.next()
            }
            val text = result.toString().trim()
            require(text.isNotBlank()) { "В DOCX не найден текст." }
            require(text.length <= 5_000_000) { "В DOCX слишком много текста для этой версии." }
            return text
        }
    }
}
