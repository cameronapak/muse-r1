/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under Apache-2.0. */
package dev.cameronpak.muser1.transport

import dev.cameronpak.muser1.DeviceCredentials
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MuseConnection(
    private var credentials: DeviceCredentials,
    private val sdkToken: String?,
    private val onCredentialsUpdated: (DeviceCredentials) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onReply: (messageId: String, text: String, done: Boolean) -> Unit,
    private val onUserTranscript: (String) -> Unit = {},
) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val ids = AtomicLong(1)
    private val lock = Any()
    private val streams = mutableMapOf<Long, Stream>()
    private val ready = CompletableDeferred<Unit>()
    private var ws: WebSocket? = null
    private var handshake: NoiseHandshake? = null
    private var noise: NoiseCipherPair? = null
    private var subscription = 0L
    private var closed = false
    private val replies = ReplyTracker(onUserTranscript, onReply)
    private var transcriptAfterSeq = 0L

    private class Vm(val token: String, val id: String)
    private class Stream(val path: String, val complete: CompletableDeferred<Unit>?, val limit: Int) {
        var status = 0
        val body = ByteArrayOutputStream()
        var total = 0
    }

    suspend fun connect() {
        onStatus("Connecting")
        try {
            var vm = fetchVm(credentials.accessToken)
            if (vm == null) { refresh(); vm = fetchVm(credentials.accessToken) }
            checkNotNull(vm) { "Muse is unavailable" }
            synchronized(lock) { check(!closed && ws == null); open(vm) }
            withTimeout(20_000) { ready.await() }
            onStatus("Connected")
        } catch (error: Exception) { close(); throw IllegalStateException("Muse connection failed", error) }
    }

    suspend fun sendText(text: String) {
        require(text.isNotBlank())
        sendChat(JSONObject().put("message", text).put("output_modality", "text").toString().toByteArray())
    }

    suspend fun sendVoice(wav: ByteArray): String? {
        require(wav.size in 45..640044)
        val item = JSONObject().put("type", "file").put("mime_type", "audio/wav")
            .put("filename", "voice_note.wav").put("data_base64", Base64.getEncoder().encodeToString(wav))
        // Mark the current end of history before upload, as the ESP32 client does.
        // If history is unavailable, still send the voice note and receive/speak the answer.
        val before = try {
            val history = readHistory(null)
            check(history.optBoolean("ok"))
            val rows = history.getJSONObject("result").getJSONArray("chat_events")
            if (rows.length() == 0) 0L else rows.getJSONObject(0).getLong("seq")
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive() // Preserve cancellation, but not a local history timeout.
            null
        }
        // Our voice-mode tests returned server errors; Android speaks the working text-mode reply.
        val messageId = sendChat(JSONObject().put("message", "").put("output_modality", "text")
            .put("items", JSONArray().put(item)).toString().toByteArray(), before)
        return messageId.takeIf { before != null }
    }

    suspend fun userTranscript(messageId: String): String? {
        val after = synchronized(lock) {
            check(!closed && messageId == replies.userMessageId)
            transcriptAfterSeq
        }
        val history = readHistory(after)
        synchronized(lock) {
            if (messageId != replies.userMessageId) return null
            val rows = history.optJSONObject("result")?.optJSONArray("chat_events")
            for (index in 0 until (rows?.length() ?: 0)) {
                val row = rows!!.optJSONObject(index) ?: continue
                transcriptAfterSeq = maxOf(transcriptAfterSeq, row.optLong("seq"))
            }
            return dev.cameronpak.muser1.transport.userTranscript(history, messageId)
        }
    }

    private suspend fun readHistory(after: Long?): JSONObject {
        val completed = CompletableDeferred<Unit>()
        val id: Long
        val stream: Stream
        synchronized(lock) {
            // Follow the SDK's bounded history cursor, rather than fetching unrelated conversation history.
            val path = "/chat/history?limit=1" + (after?.let { "&after_seq=$it" } ?: "")
            id = start("GET", path, emptyList(), completed)
            stream = streams.getValue(id)
        }
        try {
            withTimeout(3_000) { completed.await() }
            return JSONObject(stream.body.toString(Charsets.UTF_8.name()))
        } finally {
            synchronized(lock) {
                if (streams.remove(id) != null && !closed) sendFrame(ServiceFrame(id, Reset(1, "cancelled")))
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            ready.cancel()
            streams.values.forEach { it.complete?.cancel() }; streams.clear()
            ws?.cancel(); ws = null
            noise?.destroy(); noise = null
            handshake?.destroy(); handshake = null
        }
        client.dispatcher.cancelAll()
        onStatus("Disconnected")
    }

    private suspend fun sendChat(body: ByteArray, afterSeq: Long? = null): String? {
        val completed = CompletableDeferred<Unit>()
        val id: Long
        synchronized(lock) {
            check(!closed && noise != null && subscription != 0L && ready.isCompleted && !ready.isCancelled)
            transcriptAfterSeq = afterSeq ?: replies.lastSeq
            replies.begin()
            id = start("POST", "/chat/stream", listOf(Header("content-type", "application/json")), completed, end = false)
            var offset = 0
            while (offset < body.size) {
                val end = minOf(body.size, offset + 48 * 1024)
                sendFrame(ServiceFrame(id, BodyChunk(body.copyOfRange(offset, end), end == body.size)))
                offset = end
            }
        }
        try { withTimeout(30_000) { completed.await() } }
        catch (error: Exception) {
            synchronized(lock) {
                streams.remove(id)
                if (!closed) sendFrame(ServiceFrame(id, Reset(1, "cancelled")))
                replies.cancel()
            }
            throw error
        }
        return synchronized(lock) { replies.userMessageId }
    }

    private fun fetchVm(token: String): Vm? {
        val request = Request.Builder().url("${apiRoot()}/fetch_vms")
            .header("Authorization", "Bearer $token").header("X-API-Version", "1.0.0").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) return null
            check(response.isSuccessful) { "Muse account request failed" }
            val list = JSONObject(response.body?.string().orEmpty()).optJSONArray("vm_list") ?: return null
            var first: Vm? = null
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val tokenValue = item.optString("vm_auth_token")
                val id = item.optString("vm_id")
                if (tokenValue.isBlank() || id.isBlank() || item.optString("vm_ws_url", item.optString("vm_url")).isBlank()) continue
                val vm = Vm(tokenValue, id)
                if (first == null) first = vm
                if (item.optBoolean("default")) return vm
            }
            return first
        }
    }

    private fun refresh() {
        val raw = credentials.refreshToken.substringAfterLast(':')
        val body = JSONObject().put("device_id", credentials.deviceId)
        sdkToken?.let { body.put("sdk_token", it) }
        val request = Request.Builder().url("${apiRoot()}/device_token/refresh")
            .header("Authorization", "Bearer hatch_refresh:$raw")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pairing refresh failed" }
            var json = JSONObject(response.body?.string().orEmpty())
            json.optJSONObject("payload")?.let { json = it }
            val access = json.optString("access_token"); val refresh = json.optString("refresh_token")
            check(access.isNotBlank() && refresh.isNotBlank())
            synchronized(lock) {
                check(!closed)
                credentials = DeviceCredentials(credentials.deviceId, access, refresh, credentials.apiUrlV2, credentials.noiseHost)
                onCredentialsUpdated(credentials)
            }
        }
    }

    private fun apiRoot(): String {
        val url = credentials.apiUrlV2.trimEnd('/').toHttpUrlOrNull() ?: error("Invalid Muse endpoint")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.port == 443 &&
            (url.host == "api.muse.ai" || url.host.endsWith(".api.muse.ai")))
        return url.toString().trimEnd('/')
    }

    private fun open(vm: Vm) {
        val host = credentials.noiseHost.lowercase()
        require(host == "hatch.metaaivm.com" || host.endsWith(".metaaivm.com") || host.endsWith(".muse.ai"))
        val url = HttpUrl.Builder().scheme("https").host(host).addPathSegments("v1/noise")
            .addQueryParameter("vm_id", vm.id).build()
        handshake = NoiseHandshake()
        ws = client.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer ${vm.token}").build(), Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) { if (!closed) webSocket.send(ByteString.of(*handshake!!.message1())) }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                synchronized(lock) {
                    if (closed) return
                    val h = handshake
                    if (h != null) {
                        val (message3, pair) = h.message3(bytes.toByteArray())
                        noise = pair; handshake = null; h.destroy()
                        check(webSocket.send(ByteString.of(*message3)))
                        subscription = start("POST", "/chat/subscribe", listOf(Header("content-type", "application/json"),
                            Header("accept", "application/x-ndjson")), ready, "{}".toByteArray())
                    } else {
                        val plain = noise!!.decrypt(bytes.toByteArray()) ?: return
                        handle(WireCodec.decodeResponseEnvelope(plain))
                    }
                }
            } catch (_: Exception) { fail() }
        }
        override fun onMessage(webSocket: WebSocket, text: String) { fail() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail() }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail() }
    }

    private fun fail() {
        synchronized(lock) {
            if (closed) return
            val error = IllegalStateException("Muse connection failed")
            ready.completeExceptionally(error)
            streams.values.forEach { it.complete?.completeExceptionally(error) }
        }
        close()
        onStatus("Connection lost")
    }

    private fun start(method: String, path: String, headers: List<Header>, complete: CompletableDeferred<Unit>?,
                      body: ByteArray = byteArrayOf(), end: Boolean = true): Long {
        check(!closed)
        val id = ids.getAndIncrement()
        streams[id] = Stream(path, complete, 1024 * 1024)
        val commonHeaders = listOf(Header("x-app-id", "hatch-web"), Header("x-request-id", "muse-r1-${java.util.UUID.randomUUID()}"))
        sendFrame(ServiceFrame(id, ApplicationRequest(method, path, commonHeaders + headers, body, end)))
        return id
    }

    private fun sendFrame(frame: ServiceFrame) {
        val cipher = noise ?: error("Not connected")
        cipher.encrypt(WireCodec.requestEnvelope(frame)).forEach { check(ws?.send(ByteString.of(*it)) == true) }
    }

    private fun handle(frame: ServiceFrame) {
        val stream = streams[frame.streamId] ?: return
        when (val value = frame.value) {
            is ApplicationResponse -> {
                stream.status = value.status
                append(stream, value.body)
                if (value.status !in 200..299) { finish(frame.streamId, stream); return }
                if (stream.path == "/chat/subscribe") { consumeLines(stream); stream.complete?.complete(Unit) }
                if (value.end) finish(frame.streamId, stream)
            }
            is BodyChunk -> {
                append(stream, value.data)
                if (stream.path == "/chat/subscribe") consumeLines(stream)
                if (value.end) finish(frame.streamId, stream)
            }
            is Reset -> {
                streams.remove(frame.streamId)
                stream.complete?.completeExceptionally(IllegalStateException("Muse cancelled the request"))
                if (frame.streamId == subscription) fail()
            }
            else -> throw ProtocolException("unexpected response frame")
        }
    }

    private fun append(stream: Stream, bytes: ByteArray) {
        if (stream.body.size() + bytes.size > stream.limit) throw ProtocolException("response too large")
        stream.body.write(bytes)
    }
    private fun consumeLines(stream: Stream) {
        val all = stream.body.toByteArray()
        var start = 0
        for (i in all.indices) if (all[i] == 10.toByte()) {
            replies.event(all.copyOfRange(start, i).toString(Charsets.UTF_8)); start = i + 1
        }
        if (start > 0) { stream.body.reset(); stream.body.write(all, start, all.size - start) }
    }
    private fun finish(id: Long, stream: Stream) {
        streams.remove(id)
        if (stream.status !in 200..299) {
            stream.complete?.completeExceptionally(IllegalStateException("Muse request failed (HTTP ${stream.status})"))
            if (id == subscription) { fail(); return }
            return
        }
        val bytes = stream.body.toByteArray()
        when {
            stream.path == "/chat/stream" -> {
                replies.ack(bytes.toString(Charsets.UTF_8)); stream.complete?.complete(Unit)
            }
            stream.path.startsWith("/chat/history?") -> stream.complete?.complete(Unit)
            stream.path == "/chat/subscribe" -> fail()
        }
    }
}
