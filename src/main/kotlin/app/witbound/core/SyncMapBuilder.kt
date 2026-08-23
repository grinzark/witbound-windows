package app.witbound.core

import kotlin.math.max

/** A recognized word with its position on the audio timeline (seconds). */
class TimedWord(val text: String, val start: Double, val end: Double)

/**
 * Assembles the word-level sync map from book tokens, the transcript, and the
 * alignment ([Aligner] output; -1 = unmatched). Kotlin port of the iOS
 * SyncMapBuilder.build — produces the same map the reader (and the RASM binary
 * format) expect, so standalone processing plugs straight into the existing reader.
 */
object SyncMapBuilder {

    fun build(
        bookTokens: List<BookToken>,
        transcript: List<TimedWord>,
        alignment: IntArray,
        sectionCount: Int,
    ): SyncMap {
        val n = bookTokens.size
        val sectionIndex = IntArray(n) { bookTokens[it].sectionIndex }
        val paragraphIndex = IntArray(n) { bookTokens[it].paragraphIndex }
        val utf16Start = IntArray(n) { bookTokens[it].utf16Start }
        val utf16End = IntArray(n) { bookTokens[it].utf16End }
        val startTime = DoubleArray(n) { -1.0 }
        val endTime = DoubleArray(n) { -1.0 }
        val matched = ByteArray(n)

        // 1. Narrated-section detection (per-section match rate >= 0.3).
        val sectionTotal = IntArray(sectionCount)
        val sectionMatched = IntArray(sectionCount)
        for (i in 0 until n) {
            val s = bookTokens[i].sectionIndex
            sectionTotal[s]++
            if (alignment[i] >= 0) sectionMatched[s]++
        }
        val sectionNarrated = BooleanArray(sectionCount) { s ->
            sectionTotal[s] > 0 && sectionMatched[s].toDouble() / sectionTotal[s] >= 0.3
        }

        // 2. Matched words take transcript times directly.
        for (i in 0 until n) {
            val t = alignment[i]
            if (t < 0) continue
            matched[i] = 1
            startTime[i] = transcript[t].start
            endTime[i] = transcript[t].end
        }

        // 3. Interpolate unmatched words inside narrated sections.
        var prevTimed = -1
        var i = 0
        while (i < n) {
            if (matched[i].toInt() != 0) { prevTimed = i; i++; continue }
            var j = i
            while (j < n && matched[j].toInt() == 0) j++
            val nextTimed = if (j < n) j else -1
            for (k in i until j) {
                val section = sectionIndex[k]
                if (!sectionNarrated[section]) continue
                val lo = when {
                    prevTimed >= 0 -> endTime[prevTimed]
                    nextTimed >= 0 -> max(0.0, startTime[nextTimed] - 1)
                    else -> 0.0
                }
                val hi = if (nextTimed >= 0) startTime[nextTimed] else lo + 1
                val span = max(0.0, hi - lo)
                val frac0 = (k - i).toDouble() / (j - i)
                val frac1 = (k - i + 1).toDouble() / (j - i)
                startTime[k] = lo + span * frac0
                endTime[k] = lo + span * frac1
            }
            i = j
        }

        // 4. Enforce global monotonicity over timed words.
        var maxTime = 0.0
        for (k in 0 until n) {
            if (startTime[k] < 0) continue
            if (startTime[k] < maxTime) startTime[k] = maxTime
            if (endTime[k] < startTime[k]) endTime[k] = startTime[k]
            maxTime = startTime[k]
        }

        return SyncMap(sectionIndex, paragraphIndex, utf16Start, utf16End,
            startTime, endTime, matched, sectionNarrated)
    }
}
