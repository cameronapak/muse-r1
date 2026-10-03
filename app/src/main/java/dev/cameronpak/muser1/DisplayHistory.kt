package dev.cameronpak.muser1

import android.content.Context
import android.util.AtomicFile
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class ConversationTurn(
    var user: String? = null,
    val replies: LinkedHashMap<String, String> = linkedMapOf(),
) {
    val answer get() = replies.values.joinToString("\n\n")
}

internal fun encodeHistory(turns: List<ConversationTurn>): String = JSONArray().apply {
    turns.forEach { turn ->
        put(JSONObject().put("user", turn.user ?: JSONObject.NULL).put("replies", JSONArray().apply {
            turn.replies.forEach { (id, text) -> put(JSONObject().put("id", id).put("text", text)) }
        }))
    }
}.toString()

internal fun decodeHistory(text: String): List<ConversationTurn> {
    val rows = JSONArray(text)
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        ConversationTurn(if (row.isNull("user")) null else row.getString("user"), linkedMapOf<String, String>().apply {
            val replies = row.getJSONArray("replies")
            for (replyIndex in 0 until replies.length()) {
                val reply = replies.getJSONObject(replyIndex)
                put(reply.getString("id"), reply.getString("text"))
            }
        })
    }
}

/** Local text only. The app-lifetime writer survives activity recreation and orders clear after updates. */
internal class DisplayHistory(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "display-history.json"))
    private val writes = Channel<String>(Channel.CONFLATED)
    private val lock = Any()
    private var latest: String? = null

    init {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (text in writes) {
                try {
                    val output = file.startWrite()
                    try {
                        output.write(text.toByteArray(Charsets.UTF_8))
                        file.finishWrite(output)
                    } catch (error: Exception) { file.failWrite(output); throw error }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, "Couldn't save display history.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    suspend fun load(): List<ConversationTurn> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val text = latest ?: if (file.baseFile.exists()) file.readFully().toString(Charsets.UTF_8) else "[]"
            decodeHistory(text).also { latest = text }
        }
    }

    fun save(turns: List<ConversationTurn>) {
        synchronized(lock) {
            val text = encodeHistory(turns)
            latest = text
            check(writes.trySend(text).isSuccess)
        }
    }
}
