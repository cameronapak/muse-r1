/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under Apache-2.0. */
package dev.cameronpak.muser1.transport

import org.json.JSONObject

/** Bind subscription events to the acknowledged user turn, including replies that precede its ack. */
internal class ReplyTracker(
    private val onUserTranscript: (String) -> Unit = {},
    private val onReply: (String, String, Boolean) -> Unit,
) {
    private var active = false
    private var acked = false
    var lastSeq = 0L
        private set
    var userMessageId: String? = null
        private set
    private val userIds = mutableSetOf<String>()
    private val messageIds = mutableSetOf<String>()
    private val finished = mutableSetOf<String>()
    private val lengths = mutableMapOf<String, Int>()
    private val pending = mutableListOf<JSONObject>()
    private var userText: String? = null

    fun begin() { active = true; acked = false; userMessageId = null; userText = null; userIds.clear(); messageIds.clear(); finished.clear(); lengths.clear(); pending.clear() }
    fun cancel() { active = false; pending.clear() }
    fun ack(text: String) {
        val json = JSONObject(text)
        val payload = json.optJSONObject("result") ?: json
        userMessageId = payload.optString("message_id").takeIf { it.isNotBlank() }
        listOf("message_id", "reply_to_message_id").map { payload.optString(it) }.filter { it.isNotBlank() }.forEach(userIds::add)
        if (userIds.isEmpty()) throw ProtocolException("missing turn acknowledgement")
        acked = true
        pending.toList().forEach(::deliver); pending.clear()
    }
    fun event(text: String) {
        if (text.isBlank()) return
        val json = try { JSONObject(text) } catch (_: Exception) { throw ProtocolException("invalid subscription event") }
        if (json.optString("type") != "event") return
        val seq = json.optLong("seq", 0)
        // message.user uses the stored chat sequence, which can precede live status/snapshot sequences.
        // Bind it by the acknowledged ID and deduplicate its text instead of the subscription cursor.
        if (seq > 0 && seq <= lastSeq && json.optString("event") != "message.user") return
        lastSeq = maxOf(seq, lastSeq)
        if (!active) return
        if (!acked) {
            if (pending.size >= 128) throw ProtocolException("too many early events")
            pending.add(json)
        } else deliver(json)
    }
    private fun deliver(json: JSONObject) {
        val event = json.optString("event")
        if (event !in setOf("message.user", "delta.message_start", "delta.text_append", "delta.message_done", "message.assistant")) return
        val payload = json.optJSONObject("payload") ?: return
        val id = payload.optString("message_id").ifBlank { json.optString("message_id") }.ifBlank { payload.optString("id") }
        if (event == "message.user") {
            if (id == userMessageId) transcriptionText(payload)?.let { text ->
                if (text != userText) { userText = text; onUserTranscript(text) }
            }
            return
        }
        val parent = payload.optString("reply_to_message_id").ifBlank { payload.optString("parent_message_id") }
        if (id.isBlank() || id in finished) return
        if (id !in messageIds) {
            if (parent.isNotBlank() && parent !in userIds && parent !in messageIds) return
            if (messageIds.size >= 16) throw ProtocolException("too many reply messages")
            messageIds.add(id)
        }
        if (event == "delta.text_append") {
            val text = payload.optString("text")
            lengths[id] = lengths.getOrDefault(id, 0) + text.length
            onReply(id, text, false)
        }
        if (event == "delta.message_done" || (event == "message.assistant" && payload.opt("display_text_ready") != false)) {
            val text = replyText(payload)
            if (text.isEmpty() && lengths.getOrDefault(id, 0) == 0) return
            finished.add(id)
            onReply(id, text, true)
        }
    }

    private fun replyText(payload: JSONObject): String {
        for (key in listOf("display_text", "content")) {
            (payload.opt(key) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // Current servers return structured transcript messages instead of legacy display_text.
        val messages = payload.optJSONObject("transcript")?.optJSONArray("messages") ?: return ""
        return (0 until messages.length()).mapNotNull { index ->
            val message = messages.optJSONObject(index) ?: return@mapNotNull null
            if (message.optString("role") != "assistant") return@mapNotNull null
            val blocks = message.optJSONArray("content") ?: return@mapNotNull null
            (0 until blocks.length()).mapNotNull { blockIndex ->
                val block = blocks.optJSONObject(blockIndex) ?: return@mapNotNull null
                if (block.optString("type") == "text") block.opt("text") as? String else null
            }.joinToString("")
        }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }
}

/** The ESP32 history path exposes WAV transcription only on the exact acknowledged user message. */
internal fun userTranscript(history: JSONObject, messageId: String): String? {
    if (messageId.isBlank() || !history.optBoolean("ok")) return null
    val events = history.optJSONObject("result")?.optJSONArray("chat_events") ?: return null
    for (index in 0 until events.length()) {
        val event = events.optJSONObject(index) ?: continue
        if (event.optString("event_name") != "message.user" || event.optString("message_id") != messageId) continue
        transcriptionText(event)?.let { return it }
    }
    return null
}

private fun transcriptionText(payload: JSONObject): String? =
    (payload.opt("display_text") as? String)?.substringBefore("\n[file:")?.trim()
        ?.takeIf { it.isNotBlank() && it != "[Voice note]" }
