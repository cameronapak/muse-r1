package dev.cameronpak.muser1

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** Android speech, including initialization, long replies, interruption, and completion. Call on main. */
internal class SpeechOutput(context: Context, private val onState: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(AudioManager::class.java)
    private val pending = ArrayDeque<String>()
    private val active = mutableSetOf<String>()
    private var ready = false
    private var failure: String? = null
    private var closed = false
    val hasPlayback get() = active.isNotEmpty() || pending.isNotEmpty()
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            if (change < 0 && active.isNotEmpty()) { stop(); onState("AUDIO INTERRUPTED") }
        }, handler).build()
    private val engine = TextToSpeech(context) { result -> handler.post { initialize(result) } }

    private fun initialize(result: Int) {
        if (closed) return
        if (result != TextToSpeech.SUCCESS) { fail("SPEECH UNAVAILABLE"); return }
        if (engine.setLanguage(Locale.US) < 0) { fail("VOICE NEEDS DOWNLOAD"); return }
        engine.voices?.firstOrNull { it.locale == Locale.US && !it.isNetworkConnectionRequired }?.let { engine.voice = it }
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { handler.post { if (id in active) onState("MUSE IS SPEAKING") } }
            override fun onDone(id: String?) { handler.post {
                if (active.remove(id) && active.isEmpty() && pending.isEmpty()) {
                    manager.abandonAudioFocusRequest(focus); onState("READY")
                }
            } }
            override fun onError(id: String?) { handler.post {
                if (id in active) { stop(); onState("SPEECH FAILED") }
            } }
        })
        ready = true
        drain()
    }

    fun speak(text: String) {
        if (closed || text.isBlank()) return
        failure?.let { onState(it); return }
        pending.addLast(text)
        if (ready) drain()
    }

    private fun drain() {
        if (pending.isEmpty()) return
        if (active.isEmpty() && manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            pending.clear(); onState("AUDIO BUSY"); return
        }
        while (pending.isNotEmpty()) {
            for (part in pending.removeFirst().chunked(TextToSpeech.getMaxSpeechInputLength() - 1)) {
                val id = UUID.randomUUID().toString()
                active.add(id)
                if (engine.speak(part, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
                    stop(); onState("SPEECH FAILED"); return
                }
            }
        }
    }

    private fun fail(state: String) {
        failure = state
        if (pending.isNotEmpty()) { pending.clear(); onState(state) }
    }

    fun stop() {
        pending.clear(); active.clear(); engine.stop()
        manager.abandonAudioFocusRequest(focus)
    }

    fun close() { closed = true; stop(); engine.shutdown() }
}
