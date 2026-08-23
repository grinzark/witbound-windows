package app.witbound.core

import java.text.Normalizer

/**
 * A word occurrence in the book, addressable for both alignment and display
 * highlighting. Kotlin port of the iOS ReadAlongKit BookToken.
 */
class BookToken(
    /** Normalized form for alignment (lowercased, punctuation stripped, diacritics folded). */
    val normalized: String,
    val sectionIndex: Int,
    val paragraphIndex: Int,
    /** UTF-16 range of the original word within its paragraph's text (for highlighting). */
    val utf16Start: Int,
    val utf16End: Int,
)

/**
 * Splits book text into word tokens with UTF-16 ranges, and normalizes words for
 * alignment against the transcript. Kotlin port of the iOS Tokenizer — must stay
 * byte-for-byte compatible so the same book aligns identically on both platforms.
 */
object Tokenizer {

    /** "Hobb," -> "hobb", "don't" -> "dont", "café" -> "cafe". */
    fun normalize(word: String): String {
        val decomposed = Normalizer.normalize(word, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            // Combining diacritics (category Mn) and punctuation are not
            // letters/digits, so they drop out — leaving the folded base word.
            if (Character.isLetterOrDigit(cp)) out.appendCodePoint(Character.toLowerCase(cp))
        }
        return out.toString()
    }

    /**
     * Splits a paragraph into word tokens. Hyphens/em-dashes/slashes act as
     * separators (so "King-in-Waiting" yields three tokens, matching how ASR
     * emits words); apostrophes stay inside words.
     */
    fun tokenize(paragraph: String, sectionIndex: Int, paragraphIndex: Int): List<BookToken> {
        val tokens = ArrayList<BookToken>()
        var wordStart = -1
        val current = StringBuilder()
        var utf16Pos = 0

        fun flush(end: Int) {
            if (wordStart >= 0) {
                val norm = normalize(current.toString())
                if (norm.isNotEmpty()) {
                    tokens.add(BookToken(norm, sectionIndex, paragraphIndex, wordStart, end))
                }
            }
            wordStart = -1
            current.setLength(0)
        }

        var i = 0
        while (i < paragraph.length) {
            val cp = paragraph.codePointAt(i)
            val width = Character.charCount(cp)   // UTF-16 units: 1 (BMP) or 2 (surrogate pair)
            val isWordChar = Character.isLetterOrDigit(cp) || cp == '\''.code || cp == 0x2019
            if (isWordChar) {
                if (wordStart < 0) wordStart = utf16Pos
                current.appendCodePoint(cp)
            } else {
                flush(utf16Pos)
            }
            utf16Pos += width
            i += width
        }
        flush(utf16Pos)
        return tokens
    }

    /** Tokenizes the whole book in reading order (headings kept — they're often narrated). */
    fun tokenize(book: Book): List<BookToken> {
        val tokens = ArrayList<BookToken>()
        book.sections.forEachIndexed { s, section ->
            section.paragraphs.forEachIndexed { p, para ->
                tokens.addAll(tokenize(para.text, s, p))
            }
        }
        return tokens
    }
}
