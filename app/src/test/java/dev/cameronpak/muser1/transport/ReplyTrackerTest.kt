package dev.cameronpak.muser1.transport

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReplyTrackerTest {
    @Test fun liveTranscriptionWaitsForAckAndMatchesOnlyItsExactMessageId() {
        val transcripts = mutableListOf<String>()
        val replies = mutableListOf<String>()
        val tracker = ReplyTracker({ transcripts.add(it) }) { _, text, _ -> replies.add(text) }
        tracker.event("""{"type":"event","seq":99,"event":"agent.status","payload":{}}""")
        tracker.begin()
        tracker.event("""{"type":"event","seq":1,"event":"message.user","payload":{"message_id":"previous-turn","display_text":"Wrong parent"}}""")
        tracker.event("""{"type":"event","seq":2,"event":"message.user","payload":{"message_id":"voice-now","display_text":"What is 23 minus 8?\n[file:audio/wav voice_note.wav]","display_text_ready":false}}""")
        assertTrue(transcripts.isEmpty())
        tracker.ack("""{"result":{"message_id":"voice-now","reply_to_message_id":"previous-turn"}}""")
        assertEquals(listOf("What is 23 minus 8?"), transcripts)
        assertTrue(replies.isEmpty())
        assertEquals(99L, tracker.lastSeq)
        tracker.event("""{"type":"event","seq":100,"event":"delta.message_done","payload":{"id":"reply","parent_message_id":"voice-now","content":"15."}}""")
        assertEquals(listOf("15."), replies)
    }

    @Test fun liveTranscriptionSkipsPlaceholdersReplaysAndCanceledOrPreviousTurns() {
        val transcripts = mutableListOf<String>()
        val tracker = ReplyTracker({ transcripts.add(it) }) { _, _, _ -> }
        tracker.begin(); tracker.ack("""{"message_id":"first"}""")
        tracker.event("""{"type":"event","seq":1,"event":"message.user","payload":{"message_id":"first","display_text":"[Voice note]"}}""")
        tracker.event("""{"type":"event","seq":2,"event":"message.user","payload":{"message_id":"first","display_text":" \n[file:audio/wav voice_note.wav]"}}""")
        assertTrue(transcripts.isEmpty())
        tracker.event("""{"type":"event","seq":3,"event":"message.user","payload":{"message_id":"first","display_text":"First words"}}""")
        tracker.event("""{"type":"event","seq":3,"event":"message.user","payload":{"message_id":"first","display_text":"First words"}}""")
        tracker.cancel()
        tracker.event("""{"type":"event","seq":4,"event":"message.user","payload":{"message_id":"first","display_text":"Canceled"}}""")
        tracker.begin(); tracker.ack("""{"message_id":"second"}""")
        tracker.event("""{"type":"event","seq":5,"event":"message.user","payload":{"message_id":"first","display_text":"Late first words"}}""")
        tracker.event("""{"type":"event","seq":6,"event":"message.user","payload":{"message_id":"second","display_text":"Second words"}}""")
        assertEquals(listOf("First words", "Second words"), transcripts)
    }

    @Test fun transcriptionUsesTheAcknowledgedMessageNotItsParentAndResetsBetweenTurns() {
        val tracker = ReplyTracker { _, _, _ -> }
        tracker.event("""{"type":"event","seq":19,"event":"message.user","payload":{}}""")
        tracker.begin()
        assertEquals(19L, tracker.lastSeq)
        tracker.ack("""{"result":{"message_id":"voice-now","reply_to_message_id":"previous-turn"}}""")
        assertEquals("voice-now", tracker.userMessageId)
        tracker.begin()
        assertNull(tracker.userMessageId)
        tracker.ack("""{"reply_to_message_id":"parent-only"}""")
        assertNull(tracker.userMessageId)
    }

    @Test fun historyTranscriptionRequiresExactUserIdAndSkipsVoicePlaceholders() {
        val history = JSONObject("""{"ok":true,"result":{"chat_events":[
            {"event_name":"message.user","message_id":"old","display_text_ready":true,"display_text":"Earlier words"},
            {"event_name":"message.assistant","message_id":"now","display_text_ready":true,"display_text":"Assistant, not user"},
            {"event_name":"message.user","message_id":"now","display_text_ready":false,"display_text":"[Voice note]"},
            {"event_name":"message.user","message_id":"now","display_text_ready":true,"display_text":"[Voice note]"},
            {"event_name":"message.user","message_id":"now","display_text_ready":false,"display_text":"What is 23 minus 8?\n[file:audio/wav voice_note.wav]"}
        ]}}""")
        assertEquals("What is 23 minus 8?", userTranscript(history, "now"))
        assertNull(userTranscript(history, "unrelated"))
        assertNull(userTranscript(history, ""))
    }

    @Test fun absentOrPendingTranscriptionIsNotFabricated() {
        assertNull(userTranscript(JSONObject("""{"ok":false,"result":{"chat_events":[]}}"""), "now"))
        assertNull(userTranscript(JSONObject("""{"ok":true,"result":{"chat_events":[
            {"event_name":"message.user","message_id":"now","display_text_ready":true,"display_text":"\n[file:audio/wav voice_note.wav]"},
            {"event_name":"message.user","message_id":"now","display_text":"[Voice note]"}
        ]}}"""), "now"))
        // The SDK requires readiness for assistant rows, not for the user's transcribed words.
        assertEquals("User words", userTranscript(JSONObject("""{"ok":true,"result":{"chat_events":[
            {"event_name":"message.user","message_id":"now","display_text":"User words"}
        ]}}"""), "now"))
    }

    @Test fun earlyRepliesAreBoundToAckAndSpeechOnlyStartsOnce() {
        val replies = mutableListOf<Triple<String, String, Boolean>>()
        val spoken = mutableListOf<String>()
        val tracker = ReplyTracker { id, text, done -> replies.add(Triple(id, text, done)); if (done) spoken.add(id) }
        tracker.event("""{"type":"event","seq":1,"event":"delta.message_done","payload":{"message_id":"history","content":"Old"}}""")
        tracker.begin()
        tracker.event("""{"type":"event","seq":2,"event":"delta.message_done","payload":{"message_id":"other","reply_to_message_id":"not-ours","content":"Wrong"}}""")
        tracker.event("""{"type":"event","seq":3,"event":"delta.text_append","message_id":"reply","payload":{"reply_to_message_id":"ours","text":"Hello π"}}""")
        assertTrue(replies.isEmpty())
        tracker.ack("""{"result":{"message_id":"ours"}}""")
        assertEquals(listOf(Triple("reply", "Hello π", false)), replies)
        tracker.event("""{"type":"event","seq":4,"event":"message.assistant","payload":{"id":"reply","display_text_ready":false,"content":"Not ready"}}""")
        assertTrue(spoken.isEmpty())
        tracker.event("""{"type":"event","seq":5,"event":"delta.message_done","payload":{"id":"reply","display_text":"Hello π"}}""")
        tracker.event("""{"type":"event","seq":6,"event":"message.assistant","payload":{"id":"reply","content":"Hello π"}}""")
        assertEquals(listOf("reply"), spoken)
        assertEquals(Triple("reply", "Hello π", true), replies.last())
        assertEquals(2, replies.size)
    }
    @Test fun oldTurnAndReplayCannotLeakIntoNextTurn() {
        val spoken = mutableListOf<String>()
        val tracker = ReplyTracker { id, _, done -> if (done) spoken.add(id) }
        tracker.begin(); tracker.ack("""{"message_id":"first"}""")
        tracker.event("""{"type":"event","seq":1,"event":"delta.message_done","payload":{"id":"r1","parent_message_id":"first","content":"First"}}""")
        tracker.begin(); tracker.ack("""{"message_id":"second"}""")
        tracker.event("""{"type":"event","seq":2,"event":"delta.message_done","payload":{"id":"late","parent_message_id":"first","content":"Late"}}""")
        tracker.event("""{"type":"event","seq":3,"event":"delta.message_done","payload":{"id":"r2","parent_message_id":"second","content":"Second"}}""")
        tracker.event("""{"type":"event","seq":3,"event":"delta.message_done","payload":{"id":"duplicate","parent_message_id":"second","content":"Duplicate"}}""")
        assertEquals(listOf("r1", "r2"), spoken)
    }

    @Test fun structuredTranscriptExtractsAssistantTextAndIgnoresEmptyToolEvents() {
        val replies = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val tracker = ReplyTracker { id, text, done -> replies.add(text); if (done) spoken.add(id) }
        tracker.begin(); tracker.ack("""{"message_id":"user"}""")
        tracker.event("""{"type":"event","seq":1,"event":"delta.message_done","payload":{"id":"empty","transcript":{"messages":[]}}}""")
        tracker.event("""{"type":"event","seq":2,"event":"delta.message_done","payload":{"id":"reply","transcript":{"messages":[{"role":"user","content":[{"type":"text","text":"Do not read me"}]},{"role":"assistant","content":[{"type":"text","text":"Muse on "},{"type":"image","text":"Ignore image"},{"type":"text","text":"Rabbit is working."}]}]}}}""")
        assertEquals(listOf("Muse on Rabbit is working."), replies)
        assertEquals(listOf("reply"), spoken)
    }
}
