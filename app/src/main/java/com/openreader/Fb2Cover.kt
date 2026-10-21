package com.openreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

// Read the embedded FB2 cover only; do not decode the entire book into a Bitmap.
internal fun loadFb2Cover(context: Context, uri: Uri): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, null)
            var coverId: String? = null
            var insideCoverPage = false
            var binaryMatches = false
            val encoded = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase()) {
                            "coverpage" -> insideCoverPage = true
                            "image" -> if (insideCoverPage) {
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).equals("href", true)) {
                                        coverId = parser.getAttributeValue(i).removePrefix("#")
                                    }
                                }
                            }
                            "binary" -> {
                                binaryMatches = coverId != null &&
                                    (0 until parser.attributeCount).any {
                                        parser.getAttributeName(it) == "id" &&
                                            parser.getAttributeValue(it) == coverId
                                    }
                                encoded.setLength(0)
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (binaryMatches) {
                        if (encoded.length + parser.text.length > 8_000_000) return null
                        encoded.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name.lowercase()) {
                            "coverpage" -> insideCoverPage = false
                            "binary" -> if (binaryMatches) {
                                val bytes = Base64.decode(encoded.toString(), Base64.DEFAULT)
                                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                var sample = 1
                                while (options.outWidth / sample > 360 || options.outHeight / sample > 520) {
                                    sample *= 2
                                }
                                options.inJustDecodeBounds = false
                                options.inSampleSize = sample
                                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            }
                        }
                    }
                }
                event = parser.next()
            }
            null
        }
    } catch (_: Exception) {
        null
    }
}
