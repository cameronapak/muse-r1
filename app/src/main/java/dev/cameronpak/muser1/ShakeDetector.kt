package dev.cameronpak.muser1

/** Three separate acceleration peaks in a short burst, not a tilt or a single bump. */
internal class ShakeDetector {
    private var peaks = 0
    private var firstPeak = 0L
    private var lastPeak = 0L
    private var armed = true
    private var cooldownUntil = 0L

    fun reset() { peaks = 0; armed = true; cooldownUntil = 0 }

    fun sample(timeMs: Long, x: Float, y: Float, z: Float): Boolean {
        val gravitySquared = (x * x + y * y + z * z) / (9.81f * 9.81f)
        if (gravitySquared < 1.5f * 1.5f) armed = true
        if (timeMs < cooldownUntil || !armed || gravitySquared < 2.2f * 2.2f) return false
        armed = false
        if (peaks > 0 && timeMs - lastPeak < 100) return false
        if (peaks == 0 || timeMs - firstPeak > 1200) { peaks = 0; firstPeak = timeMs }
        lastPeak = timeMs
        peaks++
        if (peaks < 3) return false
        peaks = 0
        cooldownUntil = timeMs + 2000
        return true
    }
}
