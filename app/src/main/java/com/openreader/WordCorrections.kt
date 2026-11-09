package com.openreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A per-book overlay. The imported/cached source text is never modified. */
internal data class WordCorrection(val original: String, val replacement: String)

private fun correctionFile(context: Context, bookUri: String): File {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(bookUri.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val directory = File(context.filesDir, "word_corrections").apply { mkdirs() }
    return File(directory, "$digest.json")
}

internal fun loadWordCorrections(context: Context, bookUri: String): List<WordCorrection> = try {
    val file = correctionFile(context, bookUri)
    if (!file.isFile) emptyList() else {
        val array = JSONArray(file.readText(Charsets.UTF_8))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val original = item.optString("original")
            val replacement = item.optString("replacement")
            if (original.isBlank() || replacement.isBlank()) null
            else WordCorrection(original, replacement)
        }
    }
} catch (_: Exception) {
    emptyList()
}

internal fun saveWordCorrections(
    context: Context,
    bookUri: String,
    corrections: List<WordCorrection>
) {
    val array = JSONArray()
    corrections.forEach { correction ->
        array.put(JSONObject()
            .put("original", correction.original)
            .put("replacement", correction.replacement))
    }
    val target = correctionFile(context, bookUri)
    val temporary = File(target.parentFile, target.name + ".tmp")
    try {
        temporary.writeText(array.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.writeText(array.toString(), Charsets.UTF_8)
            temporary.delete()
        }
    } catch (_: Exception) {
        temporary.delete()
        throw IllegalStateException("Не удалось сохранить исправления книги.")
    }
}

internal fun deleteWordCorrections(context: Context, bookUri: String) {
    try {
        correctionFile(context, bookUri).delete()
    } catch (_: Exception) {
        // Removing a book from the library must not fail because cleanup failed.
    }
}

private fun wholeWordRegex(word: String): Regex = Regex(
    "(?<![\\p{L}\\p{N}_])${Regex.escape(word)}(?![\\p{L}\\p{N}_])",
    RegexOption.IGNORE_CASE
)

internal fun countWholeWordOccurrences(text: String, word: String): Int =
    if (word.isBlank()) 0 else wholeWordRegex(word).findAll(text).count()

private fun matchCase(source: String, replacement: String): String = when {
    source.isNotEmpty() && source == source.uppercase() -> replacement.uppercase()
    source.isNotEmpty() && source == source.lowercase() -> replacement.lowercase()
    source.firstOrNull()?.isUpperCase() == true ->
        replacement.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    else -> replacement
}

internal fun replaceWholeWord(text: String, oldWord: String, newWord: String): String {
    if (oldWord.isBlank() || newWord.isBlank()) return text
    return wholeWordRegex(oldWord).replace(text) { match ->
        matchCase(match.value, newWord)
    }
}

internal fun applyWordCorrections(context: Context, bookUri: String, sourceText: String): String =
    loadWordCorrections(context, bookUri).fold(sourceText) { text, correction ->
        replaceWholeWord(text, correction.original, correction.replacement)
    }

internal fun wordAt(text: String, offset: Int): String? {
    if (text.isEmpty() || offset !in text.indices) return null
    fun isWordCharacter(char: Char) = char.isLetterOrDigit() || char == '_'
    if (!isWordCharacter(text[offset])) return null
    var start = offset
    var end = offset + 1
    while (start > 0 && isWordCharacter(text[start - 1])) start--
    while (end < text.length && isWordCharacter(text[end])) end++
    return text.substring(start, end).takeIf { it.isNotBlank() }
}
