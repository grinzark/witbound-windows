package app.witbound.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Word-level sync map in the shared "RASM" binary layout. Identical bytes on
 * macOS (AndroidExport.binarySyncMap), Android (this file's origin) and now the
 * Windows/Linux desktop — so any producer's map is read by any reader and the
 * sync-map network stores one artifact for all platforms.
 */
class SyncMap(
    val sectionIndex: IntArray,
    val paragraphIndex: IntArray,
    val utf16Start: IntArray,
    val utf16End: IntArray,
    val startTime: DoubleArray,
    val endTime: DoubleArray,
    val matched: ByteArray,
    val sectionNarrated: BooleanArray,
) {
    val count: Int get() = sectionIndex.size

    /** Serializes to the little-endian "RASM" layout [parse] reads. */
    fun toBinary(): ByteArray {
        val buf = ByteBuffer.allocate(16 + count * 33 + sectionNarrated.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put('R'.code.toByte()); buf.put('A'.code.toByte())
        buf.put('S'.code.toByte()); buf.put('M'.code.toByte())
        buf.putInt(1)                        // version
        buf.putInt(count)
        buf.putInt(sectionNarrated.size)
        for (v in sectionIndex) buf.putInt(v)
        for (v in paragraphIndex) buf.putInt(v)
        for (v in utf16Start) buf.putInt(v)
        for (v in utf16End) buf.putInt(v)
        for (v in startTime) buf.putDouble(v)
        for (v in endTime) buf.putDouble(v)
        buf.put(matched)
        for (b in sectionNarrated) buf.put(if (b) 1 else 0)
        return buf.array()
    }

    /** Matched words within NARRATED sections, 0..1 — how the phones report quality. */
    fun narratedMatchRate(): Double {
        var total = 0; var hit = 0
        for (i in 0 until count) {
            val s = sectionIndex[i]
            if (s in sectionNarrated.indices && sectionNarrated[s]) {
                total++
                if (matched[i].toInt() != 0) hit++
            }
        }
        return if (total > 0) hit.toDouble() / total else 0.0
    }

    companion object {
        fun parse(bytes: ByteArray): SyncMap {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic) == "RASM") { "bad syncmap magic" }
            val version = buf.int
            require(version == 1) { "unsupported syncmap version $version" }
            val wordCount = buf.int
            val sectionCount = buf.int
            fun intArray() = IntArray(wordCount) { buf.int }
            fun doubleArray() = DoubleArray(wordCount) { buf.double }
            val sectionIndex = intArray()
            val paragraphIndex = intArray()
            val utf16Start = intArray()
            val utf16End = intArray()
            val startTime = doubleArray()
            val endTime = doubleArray()
            val matched = ByteArray(wordCount).also { buf.get(it) }
            val narrated = BooleanArray(sectionCount) { buf.get().toInt() != 0 }
            return SyncMap(sectionIndex, paragraphIndex, utf16Start, utf16End, startTime, endTime, matched, narrated)
        }
    }
}
