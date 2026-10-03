package dev.cameronpak.muser1

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal object Wave {
    const val RATE = 16000
    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }
}

internal class VoiceRecorder(private val onLimit: () -> Unit, private val onError: () -> Unit) {
    private val active = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    fun start() {
        check(!active.get())
        val size = maxOf(4096, AudioRecord.getMinBufferSize(Wave.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wave.RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); error("Microphone unavailable") }
        pcm.reset(); recorder = audio
        audio.startRecording(); active.set(true)
        thread = Thread({
            val buffer = ByteArray(size)
            try {
                while (active.get()) {
                    val read = audio.read(buffer, 0, buffer.size)
                    if (read <= 0) { if (active.get()) onError(); break }
                    val remaining = Wave.RATE * 2 * 20 - pcm.size()
                    pcm.write(buffer, 0, minOf(read, remaining) and -2)
                    if (pcm.size() >= Wave.RATE * 2 * 20) { onLimit(); break }
                }
            } catch (_: Exception) { if (active.get()) onError() }
        }, "voice-capture").also { it.start() }
    }

    fun finish(): ByteArray? {
        active.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        thread?.join(2000)
        recorder?.release(); recorder = null; thread = null
        return pcm.toByteArray().takeIf { it.size >= Wave.RATE * 2 * 3 / 10 }?.let(Wave::encode)
    }
}
