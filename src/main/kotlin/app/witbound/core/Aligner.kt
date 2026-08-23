package app.witbound.core

import kotlin.math.abs

/**
 * Aligns the book's word sequence to the transcript's word sequence.
 * Kotlin port of the iOS ReadAlongKit Aligner — kept faithful so a book aligns
 * identically on both platforms.
 *
 * Strategy (patience-diff cascade): find n-grams unique on both sides as monotonic
 * anchors (largest n first), recurse into the gaps with smaller n, and once a gap
 * is small enough close it with fuzzy Needleman-Wunsch DP. Content on only one
 * side (intros, front matter, unnarrated TOC) simply stays unmatched.
 *
 * Returns, per book token, the matched transcript index, or -1 if unmatched.
 */
object Aligner {

    class Stats(
        var bookTokens: Int = 0,
        var transcriptTokens: Int = 0,
        var matched: Int = 0,
        var longestUnmatchedBookRun: Int = 0,
    ) {
        val matchRate: Double get() = if (bookTokens == 0) 0.0 else matched.toDouble() / bookTokens
    }

    // Half-open ranges [bLo,bHi) x [tLo,tHi), plus the n-gram-size index to try.
    private class Frame(val bLo: Int, val bHi: Int, val tLo: Int, val tHi: Int, val nIdx: Int)
    private class Anchor(val bookPos: Int, val transcriptPos: Int)

    private val nSizes = intArrayOf(8, 5, 3, 2)
    private const val MAX_DP_SIDE = 3000

    fun align(book: List<String>, transcript: List<String>): Pair<IntArray, Stats> {
        val result = IntArray(book.size) { -1 }
        val stack = ArrayList<Frame>()
        stack.add(Frame(0, book.size, 0, transcript.size, 0))

        while (stack.isNotEmpty()) {
            val f = stack.removeAt(stack.size - 1)
            val bCount = f.bHi - f.bLo
            val tCount = f.tHi - f.tLo
            if (bCount <= 0 || tCount <= 0) continue

            if (bCount <= MAX_DP_SIDE && tCount <= MAX_DP_SIDE) {
                closeGapDP(book, transcript, f.bLo, f.bHi, f.tLo, f.tHi, result)
                continue
            }
            if (f.nIdx >= nSizes.size) continue   // too big to anchor or DP: leave unmatched

            val n = nSizes[f.nIdx]
            val anchors = uniqueNgramAnchors(book, transcript, f.bLo, f.bHi, f.tLo, f.tHi, n)
            if (anchors.isEmpty()) {
                stack.add(Frame(f.bLo, f.bHi, f.tLo, f.tHi, f.nIdx + 1))
                continue
            }
            var prevB = f.bLo
            var prevT = f.tLo
            for (a in anchors) {
                for (k in 0 until n) result[a.bookPos + k] = a.transcriptPos + k
                if (a.bookPos > prevB && a.transcriptPos > prevT) {
                    stack.add(Frame(prevB, a.bookPos, prevT, a.transcriptPos, f.nIdx + 1))
                }
                prevB = a.bookPos + n
                prevT = a.transcriptPos + n
            }
            if (prevB < f.bHi && prevT < f.tHi) {
                stack.add(Frame(prevB, f.bHi, prevT, f.tHi, f.nIdx + 1))
            }
        }

        val stats = Stats(bookTokens = book.size, transcriptTokens = transcript.size)
        var run = 0
        for (m in result) {
            if (m >= 0) { stats.matched++; run = 0 }
            else { run++; if (run > stats.longestUnmatchedBookRun) stats.longestUnmatchedBookRun = run }
        }
        return result to stats
    }

    // MARK: - Anchoring

    private fun uniqueNgramAnchors(
        book: List<String>, transcript: List<String>,
        bLo: Int, bHi: Int, tLo: Int, tHi: Int, n: Int,
    ): List<Anchor> {
        if (bHi - bLo < n || tHi - tLo < n) return emptyList()

        fun ngramPositions(arr: List<String>, lo: Int, hi: Int): HashMap<String, Int> {
            val positions = HashMap<String, Int>(hi - lo)   // gram -> position, or -1 if duplicate
            var i = lo
            while (i + n <= hi) {
                val sb = StringBuilder()
                for (k in 0 until n) {
                    if (k > 0) sb.append('\u0001')
                    sb.append(arr[i + k])
                }
                val gram = sb.toString()
                val existing = positions[gram]
                if (existing == null) positions[gram] = i
                else if (existing != -1) positions[gram] = -1
                i++
            }
            return positions
        }

        val bookGrams = ngramPositions(book, bLo, bHi)
        val transcriptGrams = ngramPositions(transcript, tLo, tHi)

        val candidates = ArrayList<Anchor>()
        for ((gram, bPos) in bookGrams) {
            if (bPos == -1) continue
            val tPos = transcriptGrams[gram]
            if (tPos != null && tPos != -1) candidates.add(Anchor(bPos, tPos))
        }
        candidates.sortBy { it.bookPos }

        // Longest increasing subsequence on transcript positions -> monotonic anchors.
        val lis = longestIncreasingSubsequence(IntArray(candidates.size) { candidates[it].transcriptPos })
        val anchors = lis.map { candidates[it] }

        // Drop anchors that overlap their predecessor (must be n apart on both axes).
        val filtered = ArrayList<Anchor>()
        for (a in anchors) {
            val last = filtered.lastOrNull()
            if (last != null && (a.bookPos < last.bookPos + n || a.transcriptPos < last.transcriptPos + n)) continue
            filtered.add(a)
        }
        return filtered
    }

    private fun longestIncreasingSubsequence(values: IntArray): IntArray {
        if (values.isEmpty()) return IntArray(0)
        val tailIndices = ArrayList<Int>()               // index of smallest tail per length
        val predecessors = IntArray(values.size) { -1 }
        for (i in values.indices) {
            val v = values[i]
            var lo = 0; var hi = tailIndices.size
            while (lo < hi) {                             // first tail with value >= v (strict LIS)
                val mid = (lo + hi) / 2
                if (values[tailIndices[mid]] < v) lo = mid + 1 else hi = mid
            }
            if (lo > 0) predecessors[i] = tailIndices[lo - 1]
            if (lo == tailIndices.size) tailIndices.add(i) else tailIndices[lo] = i
        }
        val out = ArrayList<Int>()
        var k = tailIndices.last()
        while (k >= 0) { out.add(k); k = predecessors[k] }
        out.reverse()
        return out.toIntArray()
    }

    // MARK: - Gap closing (fuzzy DP)

    fun fuzzyEqual(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length; val lb = b.length
        if (la >= 4 && lb >= 4) {
            if (abs(la - lb) <= 1 && levenshteinAtMostOne(a, b)) return true
            if (la >= 5 && lb >= 5 && a.substring(0, 4) == b.substring(0, 4)) return true
        }
        return false
    }

    private fun levenshteinAtMostOne(a: String, b: String): Boolean {
        val ax = a.toByteArray(Charsets.UTF_8)
        val bx = b.toByteArray(Charsets.UTF_8)
        if (ax.size == bx.size) {
            var diff = 0
            for (i in ax.indices) if (ax[i] != bx[i]) { diff++; if (diff > 1) return false }
            return true
        }
        val short: ByteArray; val long: ByteArray
        if (ax.size < bx.size) { short = ax; long = bx } else { short = bx; long = ax }
        if (long.size - short.size != 1) return false
        var i = 0; var j = 0; var skipped = false
        while (i < short.size && j < long.size) {
            if (short[i] == long[j]) { i++; j++ }
            else { if (skipped) return false; skipped = true; j++ }
        }
        return true
    }

    private fun closeGapDP(
        book: List<String>, transcript: List<String>,
        bLo: Int, bHi: Int, tLo: Int, tHi: Int, result: IntArray,
    ) {
        val nB = bHi - bLo; val nT = tHi - tLo
        if (nB <= 0 || nT <= 0) return

        // Scores: exact +3, fuzzy +2, mismatch -1, gap -1.
        val matchExact = 3; val matchFuzzy = 2; val mismatch = -1; val gapPenalty = -1
        val width = nT + 1
        val score = IntArray((nB + 1) * (nT + 1))
        val trace = ByteArray((nB + 1) * (nT + 1))       // 0 diag, 1 up (book gap), 2 left (transcript gap)

        for (i in 0..nB) score[i * width] = i * gapPenalty
        for (j in 0..nT) score[j] = j * gapPenalty
        for (i in 1..nB) trace[i * width] = 1
        for (j in 1..nT) trace[j] = 2

        for (i in 1..nB) {
            val bWord = book[bLo + i - 1]
            val rowBase = i * width
            val prevBase = (i - 1) * width
            for (j in 1..nT) {
                val tWord = transcript[tLo + j - 1]
                val sub = when {
                    bWord == tWord -> matchExact
                    fuzzyEqual(bWord, tWord) -> matchFuzzy
                    else -> mismatch
                }
                val diag = score[prevBase + j - 1] + sub
                val up = score[prevBase + j] + gapPenalty
                val left = score[rowBase + j - 1] + gapPenalty
                if (diag >= up && diag >= left) { score[rowBase + j] = diag; trace[rowBase + j] = 0 }
                else if (up >= left) { score[rowBase + j] = up; trace[rowBase + j] = 1 }
                else { score[rowBase + j] = left; trace[rowBase + j] = 2 }
            }
        }

        // Traceback: record only exact/fuzzy diagonal matches.
        var i = nB; var j = nT
        while (i > 0 || j > 0) {
            val t = trace[i * width + j].toInt()
            if (t == 0 && i > 0 && j > 0) {
                val bWord = book[bLo + i - 1]
                val tWord = transcript[tLo + j - 1]
                if (bWord == tWord || fuzzyEqual(bWord, tWord)) result[bLo + i - 1] = tLo + j - 1
                i--; j--
            } else if (t == 1 && i > 0) { i-- }
            else if (j > 0) { j-- }
            else { i-- }
        }
    }
}
