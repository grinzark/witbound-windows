package app.witbound.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Book text plus raw image/cover bytes; the import flow writes the bytes into
 *  the book directory and the reader loads them from there. */
class EpubResult(val book: Book, val cover: ByteArray?, val images: Map<String, ByteArray>)

class EpubException(message: String) : Exception(message)

/**
 * Parses an epub into a [Book] on-device. Kotlin port of the iOS ReadAlongKit
 * EpubParser. Uses jsoup: its NodeVisitor mirrors the iOS SAX delegate almost
 * 1:1, and it decodes HTML entities natively, so accented text (á í ó ú ñ …) is
 * handled without the manual entity table the iOS XMLParser needed.
 */
object EpubParser {
    const val ITALIC = 1
    const val BOLD = 2
    const val SMALL_CAPS = 4

    private val fontAlgorithms = setOf(
        "http://www.idpf.org/2008/embedding",
        "http://ns.adobe.com/pdf/enc#RC",
    )

    fun parse(epubFile: File): EpubResult {
        ZipFile(epubFile).use { zip ->
            rejectDRM(zip)

            // 1. container.xml -> OPF path
            val containerXml = readText(zip, "META-INF/container.xml")
                ?: throw EpubException("missing container.xml")
            val opfPath = Jsoup.parse(containerXml, "", Parser.xmlParser())
                .selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotEmpty() }
                ?: throw EpubException("no OPF path in container.xml")
            val opfBytes = readBytes(zip, opfPath) ?: throw EpubException("missing OPF: $opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")

            fun resolve(href: String): String {
                val decoded = decodePercent(href)
                return if (opfDir.isEmpty()) normalizePath(decoded) else normalizePath("$opfDir/$decoded")
            }

            // 2. OPF metadata / manifest / spine
            val opf = Opf.parse(String(opfBytes, Charsets.UTF_8))
            if (opf.spineIdrefs.isEmpty()) throw EpubException("empty spine")

            // 3. TOC titles (NCX first, then EPUB 3 nav)
            val titleByFile = HashMap<String, String>()
            fun recordToc(entries: List<Pair<String, String>>, tocPath: String) {
                val base = tocPath.substringBeforeLast('/', "")
                for ((src, label) in entries) {
                    val file = src.substringBefore('#')
                    val full = normalizePath(if (base.isEmpty()) decodePercent(file) else "$base/${decodePercent(file)}")
                    titleByFile.putIfAbsent(full, label)
                }
            }
            val ncxId = opf.spineTocId ?: opf.manifest.entries
                .firstOrNull { it.value.mediaType == "application/x-dtbncx+xml" }?.key
            opf.manifest[ncxId]?.href?.let { href ->
                val ncxPath = resolve(href)
                readText(zip, ncxPath)?.let { recordToc(parseTocNcx(it), ncxPath) }
            }
            opf.manifest.values.firstOrNull { it.properties.contains("nav") }?.let { navItem ->
                val navPath = resolve(navItem.href)
                readText(zip, navPath)?.let { recordToc(parseTocNav(it), navPath) }
            }

            // 4. CSS class -> style flags
            val classFlags = HashMap<String, Int>()
            for (item in opf.manifest.values) {
                if (!item.mediaType.contains("css")) continue
                readText(zip, resolve(item.href))?.let { css ->
                    for ((name, flags) in classStyleFlags(css)) classFlags[name] = (classFlags[name] ?: 0) or flags
                }
            }

            // 5. Extract each spine document
            val sections = ArrayList<Section>()
            val images = LinkedHashMap<String, ByteArray>()
            val keyByZipPath = HashMap<String, String>()
            for (idref in opf.spineIdrefs) {
                val item = opf.manifest[idref] ?: continue
                if (!item.mediaType.contains("xhtml") && !item.mediaType.contains("html")) continue
                val path = resolve(item.href)
                val xhtml = readText(zip, path) ?: continue
                val docDir = path.substringBeforeLast('/', "")

                val paragraphs = ArrayList<Paragraph>()
                for (p in extractParagraphs(xhtml, classFlags)) {
                    val rawSrc = p.imageFile
                    if (rawSrc == null) { paragraphs.add(p); continue }
                    val zipPath = normalizePath(if (docDir.isEmpty()) decodePercent(rawSrc) else "$docDir/${decodePercent(rawSrc)}")
                    var key = keyByZipPath[zipPath]
                    if (key == null) {
                        val data = readBytes(zip, zipPath)
                        if (data != null && data.isNotEmpty()) {
                            var candidate = zipPath.substringAfterLast('/')
                            if (images.containsKey(candidate)) candidate = zipPath.replace('/', '_')
                            images[candidate] = data
                            keyByZipPath[zipPath] = candidate
                            key = candidate
                        }
                    }
                    if (key != null) paragraphs.add(Paragraph("", p.kind, key, null))
                    else if (p.text.isNotEmpty()) paragraphs.add(Paragraph(p.text, p.kind, null, p.runs))
                }
                if (paragraphs.isEmpty()) continue
                markHeuristicHeadings(paragraphs)
                val title = titleByFile[path]
                    ?: paragraphs.firstOrNull { it.kind == KIND_HEADING && it.text.isNotEmpty() }?.text
                sections.add(Section(title ?: "", paragraphs))
            }

            // 6. Cover
            var cover: ByteArray? = null
            val coverCandidates = ArrayList<String>()
            opf.metaCoverId?.let { opf.manifest[it]?.let { item -> coverCandidates.add(resolve(item.href)) } }
            opf.manifest.values.firstOrNull { it.properties.contains("cover-image") }
                ?.let { coverCandidates.add(resolve(it.href)) }
            for (item in opf.manifest.values) {
                if (item.mediaType.startsWith("image/") && item.href.lowercase().contains("cover"))
                    coverCandidates.add(resolve(item.href))
            }
            for (candidate in coverCandidates) {
                val d = readBytes(zip, candidate)
                if (d != null && d.isNotEmpty()) { cover = d; break }
            }

            return EpubResult(
                Book(opf.title ?: epubFile.nameWithoutExtension, opf.author ?: "Unknown", sections),
                cover, images)
        }
    }

    // MARK: - DRM

    private fun rejectDRM(zip: ZipFile) {
        if (zip.getEntry("META-INF/rights.xml") != null) throw EpubException("DRM protected")
        val enc = readText(zip, "META-INF/encryption.xml") ?: return
        for (m in Regex("Algorithm=\"([^\"]*)\"").findAll(enc)) {
            if (m.groupValues[1] !in fontAlgorithms) throw EpubException("DRM protected")
        }
    }

    // MARK: - OPF

    private class Opf(
        val title: String?, val author: String?,
        val manifest: Map<String, ManifestItem>,
        val spineIdrefs: List<String>,
        val spineTocId: String?, val metaCoverId: String?,
    ) {
        class ManifestItem(val href: String, val mediaType: String, val properties: String)

        companion object {
            fun parse(xml: String): Opf {
                val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                var title: String? = null
                var author: String? = null
                val manifest = LinkedHashMap<String, ManifestItem>()
                val spine = ArrayList<String>()
                var spineToc: String? = null
                var coverId: String? = null
                for (e in doc.allElements) {
                    when (e.tagName().substringAfterLast(':').lowercase()) {
                        "title" -> if (title == null) e.text().trim().takeIf { it.isNotEmpty() }?.let { title = it }
                        "creator" -> if (author == null) e.text().trim().takeIf { it.isNotEmpty() }?.let { author = it }
                        "item" -> {
                            val id = e.attr("id"); val href = e.attr("href")
                            if (id.isNotEmpty() && href.isNotEmpty())
                                manifest[id] = ManifestItem(href, e.attr("media-type"), e.attr("properties"))
                        }
                        "itemref" -> e.attr("idref").takeIf { it.isNotEmpty() }?.let { spine.add(it) }
                        "spine" -> spineToc = e.attr("toc").takeIf { it.isNotEmpty() }
                        "meta" -> if (e.attr("name") == "cover")
                            e.attr("content").takeIf { it.isNotEmpty() }?.let { coverId = it }
                    }
                }
                return Opf(title, author, manifest, spine, spineToc, coverId)
            }
        }
    }

    private fun parseTocNcx(xml: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val out = ArrayList<Pair<String, String>>()
        for (np in doc.getElementsByTag("navPoint")) {
            val label = np.selectFirst("> navLabel > text")?.text()?.trim() ?: continue
            val src = np.selectFirst("> content")?.attr("src") ?: continue
            if (label.isNotEmpty() && src.isNotEmpty()) out.add(src to label)
        }
        return out
    }

    private fun parseTocNav(xml: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val navs = doc.getElementsByTag("nav")
        val toc = navs.firstOrNull {
            it.attr("epub:type").lowercase().contains("toc") || it.attr("type").lowercase().contains("toc")
        } ?: navs.firstOrNull() ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (a in toc.getElementsByTag("a")) {
            val href = a.attr("href")
            val label = a.text().replace(Regex("\\s+"), " ").trim()
            if (href.isNotEmpty() && label.isNotEmpty()) out.add(href to label)
        }
        return out
    }

    /** Minimal CSS scan: which class names imply italic / bold / small-caps. */
    fun classStyleFlags(css: String): Map<String, Int> {
        val result = HashMap<String, Int>()
        val stripped = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        val classPattern = Regex("\\.([A-Za-z0-9_-]+)")
        // The closing brace MUST be escaped: Android's ICU regex engine rejects a
        // bare `}` (desktop JVM tolerates it, so unit tests can't catch this).
        for (rule in Regex("([^{}]+)\\{([^}]*)\\}").findAll(stripped)) {
            val selectors = rule.groupValues[1]
            val body = rule.groupValues[2].lowercase()
            var flags = 0
            if (Regex("font-style\\s*:\\s*(italic|oblique)").containsMatchIn(body)) flags = flags or ITALIC
            if (Regex("font-weight\\s*:\\s*(bold|[6-9]00)").containsMatchIn(body)) flags = flags or BOLD
            if (Regex("font-variant(-caps)?\\s*:\\s*small-caps").containsMatchIn(body)) flags = flags or SMALL_CAPS
            if (flags == 0) continue
            for (cm in classPattern.findAll(selectors)) {
                val name = cm.groupValues[1].lowercase()
                result[name] = (result[name] ?: 0) or flags
            }
        }
        return result
    }

    /** Calibre-style plain-bold chapter titles: flag a leading "CHAPTER 23" line
     *  (and a short subtitle after it) as headings when the section has none. */
    private fun markHeuristicHeadings(paragraphs: MutableList<Paragraph>) {
        if (paragraphs.take(4).any { it.kind == KIND_HEADING }) return
        val pattern = Regex("^(chapter|prologue|epilogue|part|interlude)\\b[\\s.:]*[0-9ivxlc]*$", RegexOption.IGNORE_CASE)
        for (index in paragraphs.take(3).indices) {
            val p = paragraphs[index]
            if (p.imageFile != null || p.text.isEmpty()) continue
            if (p.text.length > 30 || !pattern.containsMatchIn(p.text)) return
            paragraphs[index] = Paragraph(p.text, KIND_HEADING, null, null)
            val next = index + 1
            if (next < paragraphs.size) {
                val np = paragraphs[next]
                if (np.imageFile == null && np.text.length in 1..42 && !Regex("[.!?…]$").containsMatchIn(np.text)) {
                    paragraphs[next] = Paragraph(np.text, KIND_HEADING, null, null)
                }
            }
            return
        }
    }

    // MARK: - XHTML extraction (jsoup NodeVisitor mirrors the iOS SAX delegate)

    private fun extractParagraphs(xhtml: String, classFlags: Map<String, Int>): List<Paragraph> {
        val extractor = XhtmlExtractor(classFlags)
        NodeTraversor.traverse(extractor, Jsoup.parse(xhtml).body())
        extractor.flush()
        return extractor.paragraphs
    }

    private class XhtmlExtractor(private val classFlags: Map<String, Int>) : NodeVisitor {
        val paragraphs = ArrayList<Paragraph>()

        private val buffer = StringBuilder()
        private var bufferUtf16 = 0
        private var pendingSpace = false
        private val runs = ArrayList<StyleRun>()
        private var paragraphClassFlags = 0
        private var skipDepth = 0
        private var svgDepth = 0
        private var headingDepth = 0
        private var titleClassDepth = 0
        private var epigraphDepth = 0
        private var paragraphIsEpigraph = false
        private var italicDepth = 0
        private var boldDepth = 0
        private var smallCapsDepth = 0
        private val spanFlagsStack = ArrayList<Int>()

        private val isHeadingContext get() = headingDepth > 0 || titleClassDepth > 0
        private val activeFlags get() =
            (if (italicDepth > 0) ITALIC else 0) or
                (if (boldDepth > 0) BOLD else 0) or
                (if (smallCapsDepth > 0) SMALL_CAPS else 0)

        override fun head(node: Node, depth: Int) {
            when (node) {
                is Element -> startElement(node)
                is TextNode -> if (skipDepth == 0) appendCharacters(node.wholeText)
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node is Element) endElement(node)
        }

        private fun startElement(e: Element) {
            val tag = e.tagName().lowercase()
            val cls = e.attr("class").lowercase()
            if (tag == "svg") svgDepth++
            if ((tag == "img" || tag == "image") && skipDepth - svgDepth == 0) {
                val src = e.attr("src").ifEmpty { e.attr("href").ifEmpty { e.attr("xlink:href") } }
                if (src.isNotEmpty()) {
                    flush()
                    paragraphs.add(Paragraph(
                        e.attr("alt").trim(), if (isHeadingContext) KIND_HEADING else KIND_BODY, src, null))
                }
            }
            if (tag in SKIP) { skipDepth++; return }
            if (tag in BLOCK) flush()
            if (tag in HEADINGS) headingDepth++
            if (tag == "div" && cls.contains("title")) titleClassDepth++
            if (tag == "blockquote" || ((tag == "div" || tag == "section") && isEpigraphClass(cls))) epigraphDepth++
            if (tag == "p" && isEpigraphClass(cls)) paragraphIsEpigraph = true
            if (tag in ITALICS) italicDepth++
            if (tag in BOLDS) boldDepth++
            if (tag == "span") {
                val flags = flagsForClasses(cls)
                spanFlagsStack.add(flags)
                if (flags and ITALIC != 0) italicDepth++
                if (flags and BOLD != 0) boldDepth++
                if (flags and SMALL_CAPS != 0) smallCapsDepth++
            }
            if (tag == "p" || tag in HEADINGS) paragraphClassFlags = flagsForClasses(cls)
        }

        private fun endElement(e: Element) {
            val tag = e.tagName().lowercase()
            if (tag == "svg") svgDepth = maxOf(0, svgDepth - 1)
            if (tag in SKIP) { skipDepth = maxOf(0, skipDepth - 1); return }
            if (tag in BLOCK) flush()
            if (tag in ITALICS) italicDepth = maxOf(0, italicDepth - 1)
            if (tag in BOLDS) boldDepth = maxOf(0, boldDepth - 1)
            if (tag == "span" && spanFlagsStack.isNotEmpty()) {
                val flags = spanFlagsStack.removeAt(spanFlagsStack.size - 1)
                if (flags and ITALIC != 0) italicDepth = maxOf(0, italicDepth - 1)
                if (flags and BOLD != 0) boldDepth = maxOf(0, boldDepth - 1)
                if (flags and SMALL_CAPS != 0) smallCapsDepth = maxOf(0, smallCapsDepth - 1)
            }
            if (tag in HEADINGS) headingDepth = maxOf(0, headingDepth - 1)
            if (tag == "div" && titleClassDepth > 0) titleClassDepth--
            if ((tag == "blockquote" || tag == "div" || tag == "section") && epigraphDepth > 0) epigraphDepth--
        }

        private fun appendCharacters(s: String) {
            val flags = activeFlags
            for (ch in s) {
                if (ch.isWhitespace()) { if (buffer.isNotEmpty()) pendingSpace = true; continue }
                if (pendingSpace) { buffer.append(' '); bufferUtf16 += 1; pendingSpace = false }
                if (flags != 0) {
                    val last = runs.lastOrNull()
                    if (last != null && last.flags == flags && last.end == bufferUtf16) {
                        runs[runs.size - 1] = StyleRun(last.start, bufferUtf16 + 1, flags)
                    } else {
                        runs.add(StyleRun(bufferUtf16, bufferUtf16 + 1, flags))
                    }
                }
                buffer.append(ch); bufferUtf16 += 1
            }
        }

        fun flush() {
            val text = buffer.toString()
            buffer.setLength(0); bufferUtf16 = 0; pendingSpace = false
            val paragraphRuns = ArrayList(runs); runs.clear()
            val blockFlags = paragraphClassFlags; paragraphClassFlags = 0
            val wasEpigraph = paragraphIsEpigraph; paragraphIsEpigraph = false
            if (text.isEmpty()) return
            val heading = isHeadingContext
            val epigraph = !heading && (epigraphDepth > 0 || wasEpigraph)
            val merged = ArrayList<StyleRun>()
            if (blockFlags != 0 && !heading && !epigraph) merged.add(StyleRun(0, text.length, blockFlags))
            merged.addAll(paragraphRuns)
            val kind = if (heading) KIND_HEADING else if (epigraph) KIND_EPIGRAPH else KIND_BODY
            paragraphs.add(Paragraph(text, kind, null, if (merged.isEmpty()) null else merged))
        }

        private fun isEpigraphClass(cls: String) =
            cls.contains("cite") || cls.contains("epigraph") || cls.contains("para-alt")

        private fun flagsForClasses(cls: String): Int {
            if (cls.isEmpty() || classFlags.isEmpty()) return 0
            var flags = 0
            for (name in cls.split(' ')) if (name.isNotEmpty()) flags = flags or (classFlags[name] ?: 0)
            return flags
        }

        companion object {
            private val BLOCK = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote",
                "td", "th", "tr", "dt", "dd", "figcaption", "section", "article",
                "aside", "header", "footer", "pre", "br", "hr", "ul", "ol", "table")
            private val SKIP = setOf("script", "style", "head", "title", "svg", "img", "image")
            private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
            private val ITALICS = setOf("em", "i", "cite", "dfn")
            private val BOLDS = setOf("strong", "b")
        }
    }

    // MARK: - helpers

    private fun normalizePath(path: String): String {
        val parts = ArrayList<String>()
        for (c in path.split('/')) {
            if (c == "." || c.isEmpty()) continue
            if (c == "..") { if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); continue }
            parts.add(c)
        }
        return parts.joinToString("/")
    }

    private fun decodePercent(s: String): String {
        if (!s.contains('%')) return s
        return try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }
    }

    private fun readBytes(zip: ZipFile, name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun readText(zip: ZipFile, name: String): String? =
        readBytes(zip, name)?.toString(Charsets.UTF_8)
}
