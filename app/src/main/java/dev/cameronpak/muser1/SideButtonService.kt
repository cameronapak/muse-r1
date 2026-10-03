package dev.cameronpak.muser1

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Global side-button routing. No window content or Muse credentials are requested. */
class SideButtonService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val gesture = SideButtonGesture()
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private var acceptAfter = 0L
    private var pressedActivity: MainActivity? = null
    private var recordingActivity: MainActivity? = null
    private val locked get() = keyguard.isKeyguardLocked || keyguard.isDeviceLocked
    private val hold = Runnable {
        handle(gesture.hold(SystemClock.uptimeMillis(), power.isInteractive && !locked))
    }
    private val screenChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Reject the rest of a wake/unlock press, including repeated downs and orphaned ups.
            acceptAfter = SystemClock.uptimeMillis()
            cancelGesture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenChanges, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onServiceConnected() {
        acceptAfter = SystemClock.uptimeMillis()
        instance = this
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_PAIRING) return false
        if (!power.isInteractive) { cancelGesture(); return true }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && event.downTime > acceptAfter) {
                val activity = MainActivity.foreground
                handle(gesture.down(event.downTime, !locked, activity?.hasPlayback == true))
                pressedActivity = activity
                handler.removeCallbacks(hold)
                handler.postAtTime(hold, event.downTime + SideButtonGesture.HOLD_MS)
            }
            KeyEvent.ACTION_UP -> if (gesture.downTime == event.downTime) {
                handle(gesture.up(event.downTime, event.eventTime, event.isCanceled))
                handler.removeCallbacks(hold)
                pressedActivity = null
            }
        }
        return true
    }

    internal fun onWheel(activity: MainActivity, direction: Int): Boolean {
        if (!gesture.canAdjustVolume || pressedActivity !== activity || MainActivity.foreground !== activity ||
            !activity.hasWindowFocus() || !power.isInteractive || locked) return false
        handler.removeCallbacks(hold)
        handle(gesture.wheel())
        activity.adjustMediaVolume(direction)
        return true
    }

    private fun handle(action: SideButtonGesture.Action) {
        when (action) {
            SideButtonGesture.Action.LOCK -> {
                MainActivity.foreground?.prepareForLock()
                if (!performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN))
                    MainActivity.foreground?.sideButtonNotice("Couldn't lock Android. Check Side button controls in accessibility settings.")
            }
            SideButtonGesture.Action.HOLD -> {
                val activity = pressedActivity
                if (activity != null && MainActivity.foreground === activity) {
                    if (activity.beginSideButtonRecording()) recordingActivity = activity
                } else {
                    // Returning Home never opens the microphone on this same press.
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        this.action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
            SideButtonGesture.Action.FINISH, SideButtonGesture.Action.CANCEL -> {
                val activity = recordingActivity
                recordingActivity = null
                activity?.finishSideButtonRecording(action == SideButtonGesture.Action.FINISH &&
                    power.isInteractive && !locked && MainActivity.foreground === activity)
            }
            SideButtonGesture.Action.NONE -> Unit
        }
    }

    internal fun activityPaused(activity: MainActivity) {
        if (pressedActivity === activity || recordingActivity === activity) cancelGesture()
    }

    private fun cancelGesture() {
        handler.removeCallbacks(hold)
        handle(gesture.cancel())
        pressedActivity = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { cancelGesture() }
    override fun onUnbind(intent: Intent?): Boolean {
        cancelGesture()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        cancelGesture()
        if (instance === this) instance = null
        unregisterReceiver(screenChanges)
        super.onDestroy()
    }

    companion object {
        internal var instance: SideButtonService? = null
            private set
    }
}
