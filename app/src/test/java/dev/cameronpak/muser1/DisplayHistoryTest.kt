package dev.cameronpak.muser1

import org.junit.Assert.*
import org.junit.Test

class DisplayHistoryTest {
    @Test fun restoresOrderedTurnsRepliesMissingTranscriptAndExactText() {
        // An independent disk fixture catches reversed turns, lost reply IDs, and null becoming "null".
        val restored = decodeHistory("""[
            {"user":"First\n\"question\"","replies":[{"id":"z","text":"Earlier answer"},{"id":"a","text":"Second part"}]},
            {"user":null,"replies":[{"id":"other","text":"Newest answer café"}]}
        ]""")
        assertEquals(listOf("First\n\"question\"", null), restored.map { it.user })
        assertEquals(listOf("z", "a"), restored[0].replies.keys.toList())
        assertEquals("Earlier answer\n\nSecond part", restored[0].answer)
        assertEquals("Newest answer café", restored[1].answer)
        assertEquals(restored, decodeHistory(encodeHistory(restored)))
    }

    @Test fun snapshotsDoNotChangeWhenLaterTurnsOrRepliesChangeAndClearIsEmpty() {
        val first = ConversationTurn("First", linkedMapOf("one" to "Answer"))
        val turns = mutableListOf(first, ConversationTurn("Next", linkedMapOf("two" to "Partial")))
        val saved = encodeHistory(turns)
        turns[1].replies["two"] = "Completed"
        assertEquals("Partial", decodeHistory(saved)[1].answer)
        assertEquals("Completed", decodeHistory(encodeHistory(turns))[1].answer)
        assertEquals("Answer", decodeHistory(encodeHistory(turns))[0].answer)
        turns.clear()
        assertEquals("[]", encodeHistory(turns))
        assertTrue(decodeHistory("[]").isEmpty())
    }
}
