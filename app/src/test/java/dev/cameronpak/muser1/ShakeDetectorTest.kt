package dev.cameronpak.muser1

import org.junit.Assert.*
import org.junit.Test

class ShakeDetectorTest {
    private fun peak(detector: ShakeDetector, time: Long): Boolean {
        detector.sample(time - 20, 0f, 0f, 9.81f)
        return detector.sample(time, 28f, 0f, 9.81f)
    }

    @Test fun deliberateBurstTriggersOnceAndCooldownRequiresAnotherBurst() {
        val detector = ShakeDetector()
        assertFalse(peak(detector, 1000))
        assertFalse(peak(detector, 1200))
        assertTrue(peak(detector, 1400))
        assertFalse(peak(detector, 1600))
        assertFalse(peak(detector, 1800))
        assertFalse(peak(detector, 3400))
        assertFalse(peak(detector, 3600))
        assertTrue(peak(detector, 3800))
    }

    @Test fun tiltOneBumpSustainedAccelerationAndSeparatedBumpsDoNotTrigger() {
        val detector = ShakeDetector()
        repeat(200) { assertFalse(detector.sample(it * 20L, 9.81f, 0f, 0f)) }
        assertFalse(peak(detector, 5000))
        repeat(100) { assertFalse(detector.sample(5020 + it * 20L, 28f, 0f, 9.81f)) }
        assertFalse(peak(detector, 7100))
        assertFalse(peak(detector, 8400))
        assertFalse(peak(detector, 9700))
    }

    @Test fun windowBoundaryAndResetPreventAnOldShakeFromCompleting() {
        val within = ShakeDetector()
        assertFalse(peak(within, 1000)); assertFalse(peak(within, 1600))
        assertTrue(peak(within, 2200))
        val outside = ShakeDetector()
        assertFalse(peak(outside, 1000)); assertFalse(peak(outside, 1600))
        assertFalse(peak(outside, 2201))
        outside.reset()
        assertFalse(peak(outside, 2400)); assertFalse(peak(outside, 2600))
        assertTrue(peak(outside, 2800))
    }
}
