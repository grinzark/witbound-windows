package app.witbound.core

// Book model — byte-for-byte the same shape the Android app and the iOS/Mac
// engines use, so an aligned map is portable across every platform.

const val KIND_BODY = 0
const val KIND_HEADING = 1
const val KIND_EPIGRAPH = 2

/** Inline style run: UTF-16 [start, end) + flags (1 italic, 2 bold, 4 small caps). */
class StyleRun(val start: Int, val end: Int, val flags: Int)

class Paragraph(
    val text: String,
    val kind: Int,
    val imageFile: String? = null,
    val runs: List<StyleRun>? = null,
)
class Section(val title: String, val paragraphs: List<Paragraph>)
class Book(val title: String, val author: String, val sections: List<Section>)
