package dev.cameronpak.muser1

import android.app.Activity
import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.TextView
import dev.cameronpak.muser1.transport.MuseConnection
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/** Local hardware checks. No Muse network calls or account credentials are fabricated. */
class DeviceChecks : Instrumentation() {
    private var cloud = false
    private var voice = false
    private var visual = false
    private var buttonCheck = false
    private var buttonRecording = false
    private var historyCheck = false
    private var restoreHistory = false
    private var expectedReply = "Muse on Rabbit is working"
    private var expectedTranscript = "Please say the words Muse on Rabbit is working and nothing else."
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        cloud = arguments?.getString("cloud") == "true"
        voice = arguments?.getString("voice") == "true"
        visual = arguments?.getString("visual") == "true"
        buttonCheck = arguments?.getString("button") == "true"
        buttonRecording = arguments?.getString("buttonRecording") == "true"
        historyCheck = arguments?.getString("history") == "true"
        restoreHistory = arguments?.getString("restore") == "true"
        expectedReply = arguments?.getString("expected") ?: expectedReply
        expectedTranscript = arguments?.getString("transcript") ?: expectedTranscript
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            if (buttonCheck) { sideButtonCheck(result); finish(Activity.RESULT_OK, result); return }
            if (historyCheck) { displayHistoryCheck(result); finish(Activity.RESULT_OK, result); return }
            if (visual) { visualCheck(result); finish(Activity.RESULT_OK, result); return }
            if (cloud) { cloudCheck(result); finish(Activity.RESULT_OK, result); return }
            if (voice) { voiceUiCheck(result); finish(Activity.RESULT_OK, result); return }
            val app = launchHome()
            waitForIdleSync()
            val connected = MainActivity::class.java.getDeclaredField("connected").apply { isAccessible = true }
            val statusField = MainActivity::class.java.getDeclaredField("status").apply { isAccessible = true }
            // Exercise the actual button dispatch and microphone, without sending a turn to Muse.
            runOnMainSync {
                connected.setBoolean(app, true)
            }
            val press = SystemClock.uptimeMillis()
            runOnMainSync { sideButtonKey(KeyEvent.ACTION_DOWN, press) }
            Thread.sleep(400)
            runOnMainSync {
                check((statusField.get(app) as TextView).text.toString() == "LISTENING")
            }
            Thread.sleep(1200)
            val recorderField = MainActivity::class.java.getDeclaredField("recorder").apply { isAccessible = true }
            val recorder = recorderField.get(app) as VoiceRecorder
            val wave = recorder.finish() ?: error("no microphone samples")
            check(wave.size > 16000)
            check(wave.drop(44).any { it != 0.toByte() })
            check(ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN).getInt(40) == wave.size - 44)
            runOnMainSync {
                // Cancel discards this local microphone test. It does not submit recorded household audio.
                val finish = MainActivity::class.java.getDeclaredMethod("finishRecording", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                finish.invoke(app, false)
                connected.setBoolean(app, false)
            }
            val pcm = ByteBuffer.allocate(16000).order(ByteOrder.LITTLE_ENDIAN)
            repeat(8000) { pcm.putShort((sin(it * 2.0 * Math.PI * 440 / 16000) * 1200).toInt().toShort()) }
            val file = File(targetContext.cacheDir, "hardware-check.wav").apply { writeBytes(Wave.encode(pcm.array())) }
            val done = CountDownLatch(1)
            val player = MediaPlayer()
            player.setDataSource(file.path); player.prepare()
            player.setOnCompletionListener { done.countDown() }; player.start()
            check(done.await(5, TimeUnit.SECONDS))
            player.release(); file.delete()
            result.putString("stream", "PASS: side-button service hold starts recording; nonzero 16kHz PCM captured; WAV lengths match; audio playback completed. No audio sent to Muse.")
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            val detail = if (visual || historyCheck || buttonCheck) "\n${error.stackTraceToString()}" else ""
            result.putString("stream", result.getString("stream", "") + "FAIL: " + error.javaClass.simpleName + detail)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun cloudCheck(result: Bundle) {
        val store = (targetContext.applicationContext as MuseApp).store
        val credentials = store.credentials() ?: error("not paired")
        val reply = CountDownLatch(1)
        val texts = java.util.concurrent.ConcurrentHashMap<String, String>()
        var lastStatus = ""
        val connection = MuseConnection(credentials, store.sdkToken(), store::save, { lastStatus = it }, { id, text, done ->
            texts.compute(id) { _, before -> if (done && text.isNotBlank()) text else before.orEmpty() + text }
            if (done) reply.countDown()
        })
        try {
            runBlocking {
                connection.connect()
                if (voice) {
                    val file = File(targetContext.cacheDir, "test-voice.wav")
                    val wav = file.readBytes(); file.delete()
                    connection.sendVoice(wav)
                } else connection.sendText("Please reply only with: Muse on Rabbit is working.")
            }
            check(reply.await(300, TimeUnit.SECONDS)) { "text reply timed out" }
            result.putString("stream", "Cloud reply: textChars=${texts.values.sumOf { it.length }}, voiceInput=$voice.\n")
            check(texts.values.joinToString(" ").contains("Muse on Rabbit is working", ignoreCase = true)) { "Muse did not answer the test prompt" }
            result.putString("stream", result.getString("stream") + "PASS: live Muse turn produced the requested answer. Speech is not checked by this transport test.")
        } finally {
            if (!result.containsKey("stream")) result.putString("stream", "Cloud outcome: textChars=${texts.values.sumOf { it.length }}, status=$lastStatus, answered=${texts.values.joinToString(" ").contains("Muse on Rabbit is working", ignoreCase = true)}.\n")
            connection.close()
        }
    }

    private fun voiceUiCheck(result: Bundle) {
        val activity = launchHome()
        val connected = MainActivity::class.java.getDeclaredField("connected").apply { isAccessible = true }
        val recorderField = MainActivity::class.java.getDeclaredField("recorder").apply { isAccessible = true }
        val messageField = MainActivity::class.java.getDeclaredField("message").apply { isAccessible = true }
        val statusField = MainActivity::class.java.getDeclaredField("status").apply { isAccessible = true }
        val activeTurnField = MainActivity::class.java.getDeclaredField("activeTurn").apply { isAccessible = true }
        var ready = false
        repeat(100) { if (!ready) { runOnMainSync { ready = connected.getBoolean(activity) }; Thread.sleep(200) } }
        var connectionStatus = ""
        runOnMainSync { connectionStatus = (statusField.get(activity) as TextView).text.toString() }
        result.putString("stream", "Voice UI stage: connected=$ready, state=$connectionStatus.\n")
        check(ready)
        val input = File(targetContext.cacheDir, "test-voice.wav")
        val wav = input.readBytes(); input.delete()
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        check(String(wav, 12, 4) == "fmt " && header.getShort(20).toInt() == 1 &&
            header.getShort(22).toInt() == 1 && header.getInt(24) == 16000 && header.getShort(34).toInt() == 16)
        var offset = 12
        var pcm: ByteArray? = null
        while (offset + 8 <= wav.size) {
            val size = header.getInt(offset + 4)
            check(size >= 0 && size <= wav.size - offset - 8)
            if (String(wav, offset, 4) == "data") { pcm = wav.copyOfRange(offset + 8, offset + 8 + size); break }
            offset += 8 + size + (size and 1)
        }
        check(pcm != null && pcm.size in 9600..640000 && pcm.size % 2 == 0)
        val press = SystemClock.uptimeMillis()
        runOnMainSync { sideButtonKey(KeyEvent.ACTION_DOWN, press) }
        Thread.sleep(400)
        runOnMainSync {
            check((statusField.get(activity) as TextView).text.toString() == "LISTENING")
            val recorder = recorderField.get(activity) as VoiceRecorder
            recorder.finish() // Stop the microphone before replacing every captured sample.
            val samples = VoiceRecorder::class.java.getDeclaredField("pcm").apply { isAccessible = true }
                .get(recorder) as java.io.ByteArrayOutputStream
            samples.reset(); samples.write(pcm!!)
            sideButtonKey(KeyEvent.ACTION_UP, press)
            check((statusField.get(activity) as TextView).text.toString() == "SENDING VOICE NOTE")
        }
        result.putString("stream", "Voice UI stage: button released with synthetic PCM only.\n")
        var completed = false
        var beganSpeaking = false
        var lastState = ""
        var textChars = 0
        var correct = false
        var transcriptCorrect = false
        // ASR can render spoken subtraction as "23-8" rather than "23 minus 8".
        fun words(text: String) = text.lowercase()
            .replace(Regex("(?<=\\d)\\s*-\\s*(?=\\d)"), " minus ")
            .replace(Regex("[^a-z0-9 ]"), "").trim().replace(Regex(" +"), " ")
        repeat(1500) {
            if (!completed) {
                runOnMainSync {
                    val state = (statusField.get(activity) as TextView).text.toString()
                    lastState = state
                    if (state == "MUSE IS SPEAKING") beganSpeaking = true
                    if (state == "REPLY RECEIVED" || state == "MUSE IS SPEAKING" || state == "READY") {
                        textChars = (messageField.get(activity) as TextView).text.length
                        val current = activeTurnField.get(activity) as? ConversationTurn
                        val answer = current?.answer.orEmpty()
                        correct = answer.contains(expectedReply, ignoreCase = true) &&
                            (messageField.get(activity) as TextView).text.contains(answer)
                        val transcript = current?.user
                        transcriptCorrect = transcript != null && words(transcript) == words(expectedTranscript) &&
                            (messageField.get(activity) as TextView).text.contains("You\n$transcript\n\nMuse\n")
                    }
                    completed = state == "READY" && beganSpeaking && textChars > 0
                }
                result.putString("stream", "Voice UI outcome: state=$lastState, textChars=$textChars, speechStarted=$beganSpeaking.\n")
                check(lastState !in setOf("SPEECH FAILED", "SPEECH UNAVAILABLE", "VOICE NEEDS DOWNLOAD", "AUDIO BUSY", "CONNECTION LOST")) { lastState }
                Thread.sleep(200)
            }
        }
        result.putString("stream", "Voice UI outcome: state=$lastState, textChars=$textChars, speechStarted=$beganSpeaking, replyCorrect=$correct, transcriptCorrect=$transcriptCorrect.\n")
        uiAutomation.takeScreenshot().let { bitmap ->
            File(targetContext.cacheDir, "voice-reply-screen.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle()
        }
        check(completed) { lastState }
        check(correct) { "Muse did not answer the voice test prompt" }
        check(transcriptCorrect) { "The spoken prompt did not appear as the user transcript" }
        result.putString("stream", "PASS: button down started recording; button up sent synthetic speech only; the expected user transcript and $textChars correct conversation characters rendered; Android speech started and completed; returned to READY.")
    }

    private fun sideButtonKey(action: Int, start: Long) {
        val service = SideButtonService.instance ?: error("Enable Side button controls before this device test")
        SideButtonService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java)
            .apply { isAccessible = true }
            .invoke(service, KeyEvent(start, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_PAIRING, 0))
    }

    /** Disposable emulator only. Tests real global key filtering without opening the microphone. */
    private fun sideButtonCheck(result: Bundle) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "button fixtures require an emulator" }
        val store = (targetContext.applicationContext as MuseApp).store
        check(store.credentials() == null && store.sdkToken() == null) { "button fixtures require an unpaired emulator" }
        val power = targetContext.getSystemService(PowerManager::class.java)
        val keyguard = targetContext.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceSecure) { "button fixtures require an emulator without an existing PIN" }
        val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8).trim() }
        }
        fun await(label: String, condition: () -> Boolean) {
            repeat(100) { if (condition()) return; Thread.sleep(50) }
            error(label)
        }
        // InputManager/UiAutomation injection bypasses the accessibility input filter.
        // This disposable, rooted emulator maps gpio-keys Linux 116 to PAIRING.
        check(shell("su 0 cat /data/system/devices/keylayout/gpio-keys.kl")
            .contains(Regex("key\\s+116\\s+PAIRING"))) { "map the emulator gpio-keys to PAIRING first" }
        fun kernelKey(action: Int, repeat: Int = 0) {
            val value = if (action == KeyEvent.ACTION_UP) 0 else if (repeat > 0) 2 else 1
            // UiAutomation uses Runtime.exec, not a shell that interprets quoting or semicolons.
            check(shell("su 0 sendevent /dev/input/event0 1 116 $value").isBlank())
            check(shell("su 0 sendevent /dev/input/event0 0 0 0").isBlank())
        }
        fun tap() {
            kernelKey(KeyEvent.ACTION_DOWN); Thread.sleep(65); kernelKey(KeyEvent.ACTION_UP)
        }
        fun capture(name: String) {
            Thread.sleep(250)
            automation.takeScreenshot().let { bitmap ->
                File(targetContext.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun unlockPin() {
            Thread.sleep(400) // Interactive power state precedes the keyguard's wake animation.
            shell("input keyevent 82")
            await("PIN entry did not appear") {
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("PIN")?.isNotEmpty() == true
            }
            shell("input text 2468"); shell("input keyevent 66")
            await("fixture PIN did not unlock") { !keyguard.isDeviceLocked }
        }
        val originalServices = shell("settings get secure enabled_accessibility_services")
        val originalEnabled = shell("settings get secure accessibility_enabled")
        fun restoreSetting(key: String, value: String) {
            shell(if (value == "null" || value.isBlank()) "settings delete secure $key" else "settings put secure $key $value")
        }
        var pinSet = false
        try {
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure accessibility_enabled 0")
            await("service did not unbind") { SideButtonService.instance == null }
            val activity = launchHome()
            Thread.sleep(1000) // A fresh emulator can show Android's immersive-mode tutorial.
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Got it")?.firstOrNull()
                ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(200)
            val recording = MainActivity::class.java.getDeclaredField("recording").apply { isAccessible = true }
            val screen = MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }.get(activity) as MuseScreen
            runOnMainSync {
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAIRING))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAIRING))
                check(!recording.getBoolean(activity) && screen.message.text.contains("Enable Side button controls"))
            }
            capture("side-button-disabled")
            runOnMainSync { activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)) }
            capture("side-button-controls")
            val settingsLink = automation.rootInActiveWindow.findAccessibilityNodeInfosByText("Side button settings").single()
            check(settingsLink.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            await("accessibility settings did not open") { automation.rootInActiveWindow?.packageName == "com.android.settings" }

            shell("settings put secure enabled_accessibility_services dev.cameronpak.muser1/.SideButtonService")
            shell("settings put secure accessibility_enabled 1")
            await("service did not bind") { SideButtonService.instance != null }
            val info = SideButtonService.instance!!.serviceInfo
            check(info.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0)
            check(info.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT == 0)
            Thread.sleep(200)
            kernelKey(KeyEvent.ACTION_DOWN)
            await("settings hold did not return to Muse") { MainActivity.foreground?.hasWindowFocus() == true }
            Thread.sleep(150)
            kernelKey(KeyEvent.ACTION_DOWN, 1)
            kernelKey(KeyEvent.ACTION_UP)
            val home = MainActivity.foreground!!
            val homeScreen = MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }.get(home) as MuseScreen
            runOnMainSync { check(!recording.getBoolean(home)) }

            Thread.sleep(100)
            kernelKey(KeyEvent.ACTION_DOWN)
            Thread.sleep(450)
            kernelKey(KeyEvent.ACTION_UP)
            runOnMainSync {
                check(!recording.getBoolean(home))
                check(homeScreen.message.text.contains("Pair this r1")) { "hold was not routed to recording guard" }
            }
            check(power.isInteractive) { "failed hold locked the display" }

            if (buttonRecording) {
                // Use an audio-disabled emulator. Exercise AudioRecord locally and always cancel.
                val connected = MainActivity::class.java.getDeclaredField("connected").apply { isAccessible = true }
                val recorder = MainActivity::class.java.getDeclaredField("recorder").apply { isAccessible = true }
                val history = MainActivity::class.java.getDeclaredField("history").apply { isAccessible = true }
                val gestureField = SideButtonService::class.java.getDeclaredField("gesture").apply { isAccessible = true }
                if (targetContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    runOnMainSync { connected.setBoolean(home, true) }
                    kernelKey(KeyEvent.ACTION_DOWN)
                    await("microphone permission prompt did not appear") {
                        automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("allow")
                            ?.any { it.text.toString().startsWith("Don") } == true
                    }
                    runOnMainSync { check(!recording.getBoolean(home) && recorder.get(home) == null) }
                    kernelKey(KeyEvent.ACTION_UP)
                    check(automation.rootInActiveWindow.findAccessibilityNodeInfosByText("allow")
                        .single { it.text.toString().startsWith("Don") }
                        .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    await("Muse did not resume after permission denial") { MainActivity.foreground?.hasWindowFocus() == true }
                    check(power.isInteractive)
                }
                shell("pm grant dev.cameronpak.muser1 android.permission.RECORD_AUDIO")
                for (cancellation in listOf("release", "pause", "unbind")) {
                    if (MainActivity.foreground !== home) {
                        shell("am start -n dev.cameronpak.muser1/.MainActivity")
                        await("Muse did not return for recording check") { MainActivity.foreground === home && home.hasWindowFocus() }
                    }
                    var size = 0
                    runOnMainSync { size = (history.get(home) as List<*>).size; connected.setBoolean(home, true) }
                    Thread.sleep(150)
                    kernelKey(KeyEvent.ACTION_DOWN)
                    await("hold did not start local emulator recording") {
                        var active = false
                        runOnMainSync { active = recording.getBoolean(home) }
                        active
                    }
                    runOnMainSync {
                        val owner = SideButtonService::class.java.getDeclaredField("recordingActivity").apply { isAccessible = true }
                        check(owner.get(SideButtonService.instance) === home && recorder.get(home) != null)
                        check(homeScreen.status.text == "LISTENING")
                    }
                    when (cancellation) {
                        "release" -> runOnMainSync {
                            val service = SideButtonService.instance!!
                            val start = (gestureField.get(service) as SideButtonGesture).downTime!!
                            SideButtonService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
                                .invoke(service, KeyEvent(start, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                                    KeyEvent.KEYCODE_PAIRING, 0, 0, 0, KeyEvent.FLAG_CANCELED))
                        }
                        "pause" -> {
                            shell("am start -a android.settings.SETTINGS")
                            await("Muse did not pause") { MainActivity.foreground == null }
                        }
                        "unbind" -> {
                            shell("settings delete secure enabled_accessibility_services")
                            await("service did not unbind during recording") { SideButtonService.instance == null }
                        }
                    }
                    kernelKey(KeyEvent.ACTION_UP)
                    runOnMainSync {
                        check(!recording.getBoolean(home) && recorder.get(home) == null)
                        check((history.get(home) as List<*>).size == size) { "cancellation created a voice turn" }
                        connected.setBoolean(home, false)
                    }
                    if (cancellation == "unbind") {
                        shell("settings put secure enabled_accessibility_services dev.cameronpak.muser1/.SideButtonService")
                        await("service did not recover after recording cancellation") { SideButtonService.instance != null }
                    }
                    check(power.isInteractive) { "canceled recording locked the display" }
                }
                // No transport exists on this unpaired emulator: verify release reaches send,
                // then reports the expected local failure instead of silently canceling the WAV.
                var before = 0
                runOnMainSync {
                    check(MainActivity::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(home) == null)
                    before = (history.get(home) as List<*>).size
                    connected.setBoolean(home, true)
                }
                Thread.sleep(150)
                kernelKey(KeyEvent.ACTION_DOWN)
                Thread.sleep(950)
                kernelKey(KeyEvent.ACTION_UP)
                await("normal release did not reach send") {
                    var sent = false
                    runOnMainSync { sent = (history.get(home) as List<*>).size == before + 1 && homeScreen.status.text == "SEND FAILED" }
                    sent
                }
                runOnMainSync {
                    check(!recording.getBoolean(home) && recorder.get(home) == null)
                    connected.setBoolean(home, false)
                }
            }

            // A PIN fixture lets us distinguish sleeping from a genuine secure lock.
            check(shell("locksettings set-pin 2468").contains("set", ignoreCase = true))
            pinSet = true
            tap()
            await("Muse tap did not lock") { !power.isInteractive && keyguard.isDeviceLocked }
            tap()
            await("PAIRING tap did not wake") { power.isInteractive }
            check(keyguard.isDeviceLocked) { "wake bypassed PIN" }
            Thread.sleep(200)
            kernelKey(KeyEvent.ACTION_DOWN)
            Thread.sleep(400)
            unlockPin()
            Thread.sleep(150)
            kernelKey(KeyEvent.ACTION_DOWN, 1)
            kernelKey(KeyEvent.ACTION_UP)
            check(power.isInteractive) { "unlock press became a lock tap" }
            val gestureField = SideButtonService::class.java.getDeclaredField("gesture").apply { isAccessible = true }
            runOnMainSync {
                check((gestureField.get(SideButtonService.instance) as SideButtonGesture).downTime == null)
                MainActivity.foreground?.let { check(!recording.getBoolean(it)) }
            }

            shell("am start -a android.settings.SETTINGS")
            await("Android settings did not gain foreground") { MainActivity.foreground == null }
            Thread.sleep(200)
            tap()
            await("settings tap did not lock") { !power.isInteractive && keyguard.isDeviceLocked }
            shell("input keyevent 224")
            await("display did not wake for cleanup") { power.isInteractive }
            unlockPin()
            // Disable and re-enable without killing Muse: binding must recover.
            shell("settings delete secure enabled_accessibility_services")
            await("disabled service remained connected") { SideButtonService.instance == null }
            shell("settings put secure enabled_accessibility_services dev.cameronpak.muser1/.SideButtonService")
            await("service did not reconnect") { SideButtonService.instance != null }
            runOnMainSync {
                val service = SideButtonService.instance!!
                val method = SideButtonService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
                check(method.invoke(service, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)) == false)
            }
            result.putString("stream", "PASS: real global filtering; disabled-service guidance and settings link; no window-content capability; settings hold returns Home without recording; failed hold stays awake; taps lock from Muse and settings; PAIRING wakes without bypassing PIN; unlock press cannot record or lock; service disable/re-enable recovers; volume key not consumed. " +
                if (buttonRecording) "Local emulator recording starts on hold and cancels on canceled release, foreground loss, and service unbind without adding a turn; normal release reaches the send path and the expected failure without a transport. No audio sent to Muse."
                else "No microphone or Muse turn used.")
        } finally {
            kernelKey(KeyEvent.ACTION_UP)
            if (pinSet) shell("locksettings clear --old 2468")
            restoreSetting("enabled_accessibility_services", originalServices)
            restoreSetting("accessibility_enabled", originalEnabled)
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
        }
    }

    /** Emulator-only fixtures: render real Android views without recording or submitting a Muse turn. */
    private fun visualCheck(result: Bundle) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "visual fixtures require an emulator" }
        targetContext.getSharedPreferences("gadget_ui", android.content.Context.MODE_PRIVATE)
            .edit().remove("used_voice").commit()
        val activity = launchHome()
        val screen = MainActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }
            .get(activity) as MuseScreen
        val avatar = MuseScreen::class.java.getDeclaredField("avatar").apply { isAccessible = true }
            .get(screen) as View
        val scroll = MuseScreen::class.java.getDeclaredField("scroll").apply { isAccessible = true }
            .get(screen) as ScrollView
        fun capture(name: String): Bitmap {
            Thread.sleep(450)
            runOnMainSync {} // Animated indicators keep scheduling frames, so the UI need not become idle.
            return uiAutomation.takeScreenshot().also { bitmap ->
                check(bitmap.width == 480 && bitmap.height == 640)
                File(targetContext.filesDir, "$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        fun checkIconAnimation(first: Bitmap, second: Bitmap) {
            val position = IntArray(2)
            var top = 0
            var bottom = 0
            runOnMainSync {
                screen.message.getLocationOnScreen(position)
                top = position[1] + screen.message.layout.getLineTop(1)
                bottom = position[1] + screen.message.layout.getLineBottom(1)
            }
            check((top until bottom).any { y ->
                (position[0] until position[0] + 40).any { x -> first.getPixel(x, y) != second.getPixel(x, y) }
            }) { "Voice indicator did not animate" }
            first.recycle(); second.recycle()
        }
        runOnMainSync { screen.setState("READY"); screen.showIdle() }
        val idle = capture("ui-idle")
        var idleSize = 0
        runOnMainSync {
            check(screen.status.visibility == View.GONE && scroll.visibility == View.GONE)
            check(!screen.rootWindowInsets.isVisible(WindowInsets.Type.statusBars()))
            check(!screen.rootWindowInsets.isVisible(WindowInsets.Type.navigationBars()))
            check(avatar.left + avatar.width / 2 == screen.width / 2)
            val hint = MuseScreen::class.java.getDeclaredField("hint").apply { isAccessible = true }.get(screen) as TextView
            check(hint.isShown && hint.top >= avatar.bottom && hint.bottom <= screen.height)
            idleSize = avatar.width
            screen.showRecording()
            screen.setState("LISTENING")
        }
        checkIconAnimation(capture("ui-listening"), capture("ui-listening-next"))
        runOnMainSync {
            check(avatar.width < idleSize / 2)
            check(scroll.visibility == View.VISIBLE && screen.status.visibility == View.GONE)
            check(screen.message.text.toString() == "You\n\uFFFC")
            check(screen.message.contentDescription.contains("Recording"))
            check(avatar.contentDescription.contains("listening"))
            screen.showConversation(null, "", transcriptPending = true)
            screen.setState("SENDING VOICE NOTE")
        }
        checkIconAnimation(capture("ui-thinking"), capture("ui-thinking-next"))
        runOnMainSync {
            check(screen.message.text.toString() == "You\n\uFFFC\n\nMuse\n…")
            check(screen.message.contentDescription == "You\nTranscribing voice note.\n\nMuse\n…")
            check((screen.message.text as Spanned).getSpans(0, screen.message.length(), ReplacementSpan::class.java).size == 1)
            screen.showConversation(" ", "15.", transcriptPending = true)
            screen.setState("MUSE IS SPEAKING")
        }
        capture("ui-transcribing-reply").recycle()
        runOnMainSync {
            check(screen.message.text.toString() == "You\n\uFFFC\n\nMuse\n15.")
            check(avatar.contentDescription.contains("speaking"))
            screen.showConversation(null, "15.")
        }
        capture("ui-transcript-unavailable").recycle()
        runOnMainSync {
            check(screen.message.text.toString() == "You\nTranscript unavailable\n\nMuse\n15.")
            check(screen.message.contentDescription == null)
            check((screen.message.text as Spanned).getSpans(0, screen.message.length(), ReplacementSpan::class.java).isEmpty())
            screen.showConversation("What is 23 minus 8?", "15.", transcriptPending = true)
            check(screen.message.text.toString() == "You\nWhat is 23 minus 8?\n\nMuse\n15.")
            check(screen.message.contentDescription == null)
            screen.setState("MUSE IS SPEAKING")
        }
        capture("ui-speaking").recycle()
        runOnMainSync { screen.setState("READY") }
        val reply = capture("ui-reply")
        val comparison = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
        Canvas(comparison).apply {
            // Explicit pixel rectangles prevent Android's density scaling from distorting screenshots.
            drawBitmap(idle, null, android.graphics.Rect(0, 0, 480, 640), null)
            drawBitmap(reply, null, android.graphics.Rect(480, 0, 960, 640), null)
        }
        File(targetContext.cacheDir, "ui-idle-and-reply.png").outputStream().use {
            comparison.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        idle.recycle(); reply.recycle(); comparison.recycle()
        runOnMainSync {
            screen.showConversation("Can I read a longer response on this screen?",
                "Yes. The conversation scrolls while Muse stays above it.\n\n".repeat(12) + "End of reply.")
        }
        Thread.sleep(400)
        runOnMainSync {
            for (line in 0 until screen.message.layout.lineCount)
                check(screen.message.layout.getLineRight(line) <= screen.message.width)
            scroll.fullScroll(View.FOCUS_DOWN)
        }
        val longReply = capture("ui-long-reply")
        for (y in scroll.top until scroll.top + 3) {
            for (x in scroll.paddingLeft until scroll.width - scroll.paddingRight) {
                check(longReply.getPixel(x, y) == android.graphics.Color.rgb(10, 11, 10)) {
                    "Clipped text leaks into the scroll edge at $x,$y"
                }
            }
        }
        longReply.recycle()
        runOnMainSync {
            check(scroll.scrollY > 0)
            check(scroll.scrollY >= screen.message.height - scroll.height)
            screen.setState("CONNECTION FAILED")
            screen.showNotice("Couldn't connect to Muse.\n\nHold the empty background to check Wi-Fi or reconnect.")
        }
        capture("ui-error").recycle()
        val sending = MainActivity::class.java.getDeclaredField("sending").apply { isAccessible = true }
        runOnMainSync { sending.setBoolean(activity, true) } // Recovery controls must also work while awaiting a reply.
        // Hold the blank area inside the scroll view, not just the root's exposed top corner.
        val downTime = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 50f, 520f, 0).let {
            sendPointerSync(it); it.recycle()
        }
        Thread.sleep(750)
        MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 50f, 520f, 0).let {
            sendPointerSync(it); it.recycle()
        }
        capture("ui-controls").recycle()
        runOnMainSync {
            val controls = MainActivity::class.java.getDeclaredField("controls").apply { isAccessible = true }
                .get(activity) as android.app.AlertDialog
            check(controls.isShowing)
            check(controls.listView.adapter.getItem(1) == "Android settings")
            controls.dismiss()
            sending.setBoolean(activity, false)
            screen.markVoiceUsed(); screen.showIdle()
            val hint = MuseScreen::class.java.getDeclaredField("hint").apply { isAccessible = true }.get(screen) as TextView
            check(hint.visibility == View.GONE)
            // Test touch routing separately so the visual fixture never opens the microphone.
            var down = 0
            val releases = mutableListOf<Boolean>()
            var tapControls = 0
            val touch = MuseScreen(targetContext, { down++ }, { releases.add(it) }, { tapControls++ })
            val touchAvatar = MuseScreen::class.java.getDeclaredField("avatar").apply { isAccessible = true }.get(touch) as View
            fun pointer(action: Int, started: Long, time: Long) {
                MotionEvent.obtain(started, time, action, 10f, 10f, 0).let {
                    touchAvatar.dispatchTouchEvent(it); it.recycle()
                }
            }
            pointer(MotionEvent.ACTION_DOWN, 0, 0); pointer(MotionEvent.ACTION_UP, 0, 300)
            pointer(MotionEvent.ACTION_DOWN, 500, 500); pointer(MotionEvent.ACTION_CANCEL, 500, 950)
            pointer(MotionEvent.ACTION_DOWN, 1000, 1000); pointer(MotionEvent.ACTION_UP, 1000, 1299)
            check(down == 3 && releases == listOf(true, false, false) && tapControls == 1)
            touchAvatar.performClick() // Accessibility click also reaches device controls.
            check(tapControls == 2)
        }
        val asset = BitmapFactory.decodeResource(targetContext.resources, R.drawable.muse_character)
        val pixels = IntArray(asset.width * asset.height)
        asset.getPixels(pixels, 0, asset.width, 0, 0, asset.width, asset.height)
        val backgrounds = Bitmap.createBitmap(asset.width * 2, asset.height, Bitmap.Config.ARGB_8888)
        Canvas(backgrounds).apply {
            drawColor(Color.WHITE)
            drawRect(asset.width.toFloat(), 0f, backgrounds.width.toFloat(), backgrounds.height.toFloat(),
                android.graphics.Paint().apply { color = Color.rgb(10, 11, 10) })
            drawBitmap(asset, 0f, 0f, null); drawBitmap(asset, asset.width.toFloat(), 0f, null)
        }
        File(targetContext.cacheDir, "ui-asset-backgrounds.png").outputStream().use {
            backgrounds.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        check(Color.alpha(asset.getPixel(0, 0)) == 0 &&
            Color.alpha(asset.getPixel(asset.width / 2, asset.height / 2)) > 0) {
            "asset alpha=${pixels.minOf { Color.alpha(it) }}..${pixels.maxOf { Color.alpha(it) }}, hasAlpha=${asset.hasAlpha()}"
        }
        asset.recycle(); backgrounds.recycle()
        transcriptWaitCheck(activity, screen)
        result.putString("stream", "PASS: 480x640 immersive native views; animated recording waveform and transcription spinner; accessible loading descriptions; reply/speech independent of STT; unavailable fallback and late transcript replacement; bounded STT wait, history failure, and stale-turn guard; long reply scrolls; controls and touch routes work; first-use hint disappears; transparent character asset. No microphone or Muse turn used.")
    }

    private fun transcriptWaitCheck(activity: MainActivity, screen: MuseScreen) {
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val pending = field("transcriptPending")
        val job = field("transcriptFetch")
        val turn = field("turn")
        val fetch = MainActivity::class.java.getDeclaredMethod("fetchTranscript", MuseConnection::class.java,
            String::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
        // Empty credentials and an unopened connection: these fixtures cannot send a network request.
        val offline = MuseConnection(DeviceCredentials("offline-fixture", "", ""), null, {}, {}, { _, _, _ -> })
        runOnMainSync {
            field("connection").set(activity, offline)
            @Suppress("UNCHECKED_CAST")
            val history = field("history").get(activity) as MutableList<ConversationTurn>
            val current = ConversationTurn(null, linkedMapOf("fixture" to "15."))
            history.clear(); history.add(current)
            field("activeTurn").set(activity, current)
            pending.setBoolean(activity, true)
            screen.setState("READY")
            screen.showConversation(null, "15.", transcriptPending = true)
            fetch.invoke(activity, offline, "missing-ack", turn.getLong(activity))
        }
        Thread.sleep(500)
        runOnMainSync {
            // A failed history lookup must keep waiting for live STT, not immediately show failure.
            check(pending.getBoolean(activity) && (job.get(activity) as kotlinx.coroutines.Job).isActive)
            (job.get(activity) as kotlinx.coroutines.Job).cancel()
            fetch.invoke(activity, offline, null, turn.getLong(activity))
            turn.setLong(activity, turn.getLong(activity) + 1)
        }
        Thread.sleep(2500)
        runOnMainSync {
            check((job.get(activity) as kotlinx.coroutines.Job).isCompleted && pending.getBoolean(activity))
            fetch.invoke(activity, offline, null, turn.getLong(activity))
        }
        val waiting = job.get(activity) as kotlinx.coroutines.Job
        runBlocking { kotlinx.coroutines.withTimeout(35_000) { waiting.join() } }
        runOnMainSync {
            check(!pending.getBoolean(activity))
            check(screen.message.text.toString() == "You\nTranscript unavailable\n\nMuse\n15.")
            check(screen.status.text.toString() == "READY")
            MainActivity::class.java.getDeclaredMethod("disconnect").apply { isAccessible = true }.invoke(activity)
        }
    }

    /** Disposable emulator only. Exercise the activity's turn updates without microphone or transport. */
    private fun displayHistoryCheck(result: Bundle) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "history fixtures require an emulator" }
        val app = targetContext.applicationContext as MuseApp
        val activity = launchHome()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String, vararg args: Any) {
            val types = args.map { if (it is Boolean) Boolean::class.javaPrimitiveType!! else it.javaClass }.toTypedArray()
            MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(activity, *args)
        }
        var loaded = false
        repeat(100) { if (!loaded) { runOnMainSync { loaded = field("historyLoaded").getBoolean(activity) }; Thread.sleep(20) } }
        check(loaded)
        @Suppress("UNCHECKED_CAST")
        val history = field("history").get(activity) as MutableList<ConversationTurn>
        val screen = field("screen").get(activity) as MuseScreen
        fun view(name: String) = MuseScreen::class.java.getDeclaredField(name).apply { isAccessible = true }.get(screen) as View
        val scroll = view("scroll") as ScrollView
        val latest = view("latest")
        val clear = view("clearHistory")
        fun settle() { Thread.sleep(350); waitForIdleSync() }
        fun capture(name: String) {
            settle()
            uiAutomation.takeScreenshot().let { bitmap ->
                File(targetContext.filesDir, "$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        val disk = File(targetContext.noBackupFilesDir, "display-history.json")
        fun awaitDisk(expected: String) {
            repeat(100) {
                if (disk.exists() && disk.readText() == expected) return
                Thread.sleep(20)
            }
            error("History did not reach disk")
        }
        val finalTurns = listOf(
            ConversationTurn("23 minus 8?", linkedMapOf("first" to "15.")),
            ConversationTurn("And plus 4?", linkedMapOf("second" to "19.")),
        )
        if (restoreHistory) {
            runOnMainSync {
                check(history == finalTurns) { "Process restart did not restore both turns" }
                check(field("activeTurn").get(activity) == null)
                check(screen.message.text.contains("You\n23 minus 8?\n\nMuse\n15.\n\nYou\nAnd plus 4?\n\nMuse\n19."))
                check(clear.isShown && clear.isEnabled)
            }
            capture("history-restored")
            result.putString("stream", "PASS: both saved turns restored from disk in a new app process; text order and clear control correct. No microphone or Muse turn used.")
            return
        }
        runOnMainSync {
            history.clear(); field("activeTurn").set(activity, null)
            invoke("startVoiceTurn")
            invoke("receiveTranscript", "23 minus 8?")
            invoke("receiveReply", "first", "15.", false)
            field("sending").setBoolean(activity, false)
            invoke("startVoiceTurn")
            check(history.size == 2 && history[0] == finalTurns[0]) { "Second note replaced first turn" }
            check(screen.message.text.toString().startsWith("You\n23 minus 8?\n\nMuse\n15.\n\nYou\n"))
            check(!clear.isEnabled)
            invoke("confirmClearHistory")
            check(field("clearDialog").get(activity) == null) { "Clearing is allowed while awaiting a reply" }
            invoke("receiveReply", "second", "A longer reply. ".repeat(50), false)
            screen.jumpToLatest()
        }
        settle()
        runOnMainSync {
            check(scroll.scrollY > 0 && latest.visibility == View.GONE) { "Did not follow the new turn" }
            scroll.scrollTo(0, 40)
        }
        settle()
        var position = 0
        runOnMainSync {
            check(latest.isShown)
            position = scroll.scrollY
            invoke("receiveReply", "second", " More streaming text.".repeat(10), false)
            invoke("receiveTranscript", "And plus 4?") // Late STT must update only this turn.
        }
        capture("history-reading-earlier")
        runOnMainSync {
            check(scroll.scrollY == position && latest.isShown) { "Incoming text moved the reading position" }
            check(history[0] == finalTurns[0] && history[1].user == "And plus 4?")
            latest.performClick()
        }
        settle()
        runOnMainSync {
            check(!latest.isShown)
            invoke("receiveReply", "second", " Still following.".repeat(12), false)
        }
        settle()
        runOnMainSync {
            check(scroll.scrollY >= screen.message.height + scroll.paddingBottom - scroll.height - 2)
            check(!latest.isShown)
            // Canceling a recording must not append a phantom turn.
            field("recording").setBoolean(activity, true)
            screen.showRecording(history)
            invoke("confirmClearHistory")
            check(field("clearDialog").get(activity) == null)
            invoke("finishRecording", false)
            check(history.size == 2)
            field("sending").setBoolean(activity, false)
            invoke("updateStatus", "READY")
            clear.performClick()
        }
        capture("history-clear-confirmation")
        runOnMainSync {
            val dialog = field("clearDialog").get(activity) as android.app.AlertDialog
            check(dialog.isShowing && history.size == 2)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            check(history.size == 2)
        }
        settle() // AlertDialog posts button actions and dismissal to the main looper.
        runOnMainSync {
            // Feed acceleration samples through the actual sensor listener, not only the detector.
            val listener = field("shakeListener").get(activity) as android.hardware.SensorEventListener
            val constructor = android.hardware.SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            fun acceleration(time: Long, x: Float) {
                val event = constructor.newInstance(3)
                event.timestamp = time * 1_000_000
                event.values[0] = x; event.values[1] = 0f; event.values[2] = 9.81f
                listener.onSensorChanged(event)
            }
            (field("shake").get(activity) as ShakeDetector).reset()
            acceleration(980, 0f); acceleration(1000, 28f)
            acceleration(1180, 0f); acceleration(1200, -28f)
            check(!(field("clearDialog").get(activity) as android.app.AlertDialog).isShowing)
            acceleration(1380, 0f); acceleration(1400, 28f)
            val shakeDialog = field("clearDialog").get(activity) as android.app.AlertDialog
            check(shakeDialog.isShowing && history.size == 2) { "Shake bypassed confirmation" }
            shakeDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        }
        settle()
        runOnMainSync {
            check(history.isEmpty() && !clear.isShown && !scroll.isShown)
            // Late events must not resurrect cleared history.
            invoke("receiveTranscript", "late transcript")
            invoke("receiveReply", "second", "late delta", false)
            check(history.isEmpty())
        }
        awaitDisk("[]")
        runOnMainSync {
            // Small two-turn fixture for restart verification and the representative screenshot.
            invoke("startVoiceTurn"); invoke("receiveTranscript", "23 minus 8?")
            invoke("receiveReply", "first", "15.", false)
            field("sending").setBoolean(activity, false)
            invoke("startVoiceTurn"); invoke("receiveTranscript", "And plus 4?")
            invoke("receiveReply", "second", "19.", false)
            field("sending").setBoolean(activity, false)
            invoke("updateStatus", "READY")
            screen.markVoiceUsed()
            screen.jumpToLatest()
        }
        awaitDisk(encodeHistory(finalTurns))
        check(runBlocking { app.displayHistory.load() } == finalTurns)
        capture("history-two-turns")
        runOnMainSync {
            screen.showIdle()
            check(history == finalTurns && !scroll.isShown && clear.isShown)
            invoke("renderConversation")
            check(screen.message.text.contains("Muse\n15.\n\nYou\nAnd plus 4?"))
        }
        result.putString("stream", "PASS: successive turns retained; streaming and late STT preserve reading position; jump-to-latest resumes follow; canceled recordings add no turn; clear disabled while busy; icon and shake require confirmation; cancel retains history; confirmed clear reaches disk; late events cannot resurrect history; idle hides without clearing. Two-turn fixture saved for process-restart verification. No microphone or Muse turn used.")
    }

    private fun launchHome(): MainActivity {
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        try {
            runOnMainSync {
                targetContext.startActivity(Intent(targetContext, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            repeat(150) {
                val activity = monitor.lastActivity as? MainActivity
                var foreground = false
                runOnMainSync { foreground = activity?.hasWindowFocus() == true && !activity.isDestroyed }
                if (foreground) return activity!!
                Thread.sleep(100)
            }
            error("Muse HOME did not gain focus")
        } finally { removeMonitor(monitor) }
    }
}
