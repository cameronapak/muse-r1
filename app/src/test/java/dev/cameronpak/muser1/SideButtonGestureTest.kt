package dev.cameronpak.muser1

import dev.cameronpak.muser1.SideButtonGesture.Action.*
import org.junit.Assert.*
import org.junit.Test

class SideButtonGestureTest {
    @Test fun tapLocksButBothSidesOfHoldBoundaryNeverLock() {
        val gesture = SideButtonGesture()
        gesture.down(1000, true)
        assertEquals(NONE, gesture.hold(1299, true))
        assertEquals(LOCK, gesture.up(1000, 1299, false))
        assertEquals(NONE, gesture.hold(1300, true)) // Stale timer after release.
        for (release in listOf(2300L, 2301L)) {
            gesture.down(2000, true)
            assertEquals(NONE, gesture.up(2000, release, false)) // Timer did not run.
        }
        gesture.down(3000, true)
        assertEquals(HOLD, gesture.hold(3300, true))
        assertEquals(FINISH, gesture.up(3000, 3675, false))
        assertEquals(NONE, gesture.up(3000, 3676, false))
    }

    @Test fun duplicateDownAndStaleReleaseCannotRestartOrFinishAHold() {
        val gesture = SideButtonGesture()
        gesture.down(1000, true)
        assertEquals(HOLD, gesture.hold(1300, true))
        assertEquals(NONE, gesture.down(1000, true))
        assertEquals(NONE, gesture.hold(1800, true))
        assertEquals(NONE, gesture.up(999, 1850, false))
        assertEquals(1000L, gesture.downTime)
        assertEquals(FINISH, gesture.up(1000, 1900, false))
    }

    @Test fun unlockDuringPressAndFailedHoldNeverBecomeRecordingOrLock() {
        val gesture = SideButtonGesture()
        gesture.down(1000, false) // Began PIN-locked.
        assertEquals(NONE, gesture.hold(1300, true)) // Now unlocked, but same press.
        assertEquals(NONE, gesture.up(1000, 1900, false))
        gesture.down(2000, true)
        assertEquals(NONE, gesture.hold(2300, false)) // Locked or stopped before threshold.
        assertEquals(NONE, gesture.hold(2400, true)) // Cannot retry this press.
        assertEquals(NONE, gesture.up(2000, 2500, false))
        gesture.down(3000, true)
        assertEquals(HOLD, gesture.hold(3300, true))
        // Delivery is a hold even when the caller cannot start recording.
        assertEquals(FINISH, gesture.up(3000, 3500, false))
    }

    @Test fun cancellationDiscardsPendingAndActiveHoldsWithoutSending() {
        val gesture = SideButtonGesture()
        gesture.down(1000, true)
        assertEquals(NONE, gesture.cancel())
        assertEquals(NONE, gesture.hold(1300, true))
        assertEquals(NONE, gesture.up(1000, 1400, false))
        gesture.down(2000, true)
        gesture.hold(2300, true)
        assertEquals(CANCEL, gesture.cancel())
        assertEquals(NONE, gesture.up(2000, 2600, false))
        assertEquals(NONE, gesture.cancel())
    }

    @Test fun canceledReleaseDoesNotLockOrSendAndReplacementDownCancelsOldHold() {
        val gesture = SideButtonGesture()
        gesture.down(1000, true)
        assertEquals(NONE, gesture.up(1000, 1125, true))
        gesture.down(2000, true)
        gesture.hold(2300, true)
        assertEquals(CANCEL, gesture.up(2000, 2400, true))
        gesture.down(3000, true)
        gesture.hold(3300, true)
        assertEquals(CANCEL, gesture.down(4000, true))
        assertEquals(NONE, gesture.up(3000, 4100, false))
        assertEquals(LOCK, gesture.up(4000, 4125, false))
    }
}
