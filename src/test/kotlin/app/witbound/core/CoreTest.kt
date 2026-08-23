package app.witbound.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {
    @Test fun rasmRoundTrip() {
        val m = SyncMap(
            sectionIndex = intArrayOf(0,0,1),
            paragraphIndex = intArrayOf(0,0,0),
            utf16Start = intArrayOf(0,5,0),
            utf16End = intArrayOf(4,9,3),
            startTime = doubleArrayOf(0.0,1.0,-1.0),
            endTime = doubleArrayOf(1.0,2.0,-1.0),
            matched = byteArrayOf(1,1,0),
            sectionNarrated = booleanArrayOf(true,false),
        )
        val back = SyncMap.parse(m.toBinary())
        assertEquals(m.count, back.count)
        assertEquals(3, back.count)
        assertTrue(back.sectionIndex.contentEquals(m.sectionIndex))
        assertTrue(back.startTime.contentEquals(m.startTime))
        assertTrue(back.sectionNarrated.contentEquals(m.sectionNarrated))
        assertEquals(1.0, back.narratedMatchRate(), 0.0001) // both words in narrated section 0 matched
    }

    @Test fun pairIdVectorMatchesOtherPlatforms() {
        // Fixed cross-platform vector: sha256(sha256("epub-bytes")+sha256("audio-bytes"))[:32]
        val epubSha = java.security.MessageDigest.getInstance("SHA-256").digest("epub-bytes".toByteArray())
        val audioSha = java.security.MessageDigest.getInstance("SHA-256").digest("audio-bytes".toByteArray())
        val pid = SyncNet.pairIdFromShas(epubSha, audioSha)
        assertEquals("647b428691214a4fde2a178c664602cb", pid) // matches iOS QASelfTest vector
    }

    @Test fun alignAndBuildProducesMap() {
        val book = Book("T","A", listOf(Section("One", listOf(
            Paragraph("The quick brown fox jumps", KIND_BODY)))))
        val tokens = Tokenizer.tokenize(book)
        val transcript = listOf(
            TimedWord("the",0.0,0.5), TimedWord("quick",0.5,1.0), TimedWord("brown",1.0,1.5),
            TimedWord("fox",1.5,2.0), TimedWord("jumps",2.0,2.5))
        val (alignment, _) = Aligner.align(tokens.map { it.normalized }, transcript.map { Tokenizer.normalize(it.text) })
        val map = SyncMapBuilder.build(tokens, transcript, alignment, book.sections.size)
        assertEquals(5, map.count)
        assertTrue(map.narratedMatchRate() > 0.9)
    }
}
