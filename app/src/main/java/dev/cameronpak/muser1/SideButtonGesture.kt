package dev.cameronpak.muser1

/** One physical press owns its hold, release, and cancellation, even across unlock. */
internal class SideButtonGesture {
    enum class Action { NONE, LOCK, HOLD, FINISH, CANCEL }

    var downTime: Long? = null
        private set
    private var holdAllowed = false
    private var holdHandled = false
    private var holdDelivered = false

    fun down(time: Long, canHold: Boolean): Action {
        if (downTime == time) return Action.NONE
        val previous = cancel()
        downTime = time
        holdAllowed = canHold
        return previous
    }

    fun hold(now: Long, canHold: Boolean): Action {
        val start = downTime ?: return Action.NONE
        if (holdHandled || now - start < HOLD_MS) return Action.NONE
        holdHandled = true
        holdDelivered = holdAllowed && canHold
        return if (holdDelivered) Action.HOLD else Action.NONE
    }

    fun up(start: Long, now: Long, canceled: Boolean): Action {
        if (downTime != start) return Action.NONE
        val action = when {
            canceled -> if (holdDelivered) Action.CANCEL else Action.NONE
            holdDelivered -> Action.FINISH
            !holdHandled && now - start in 0 until HOLD_MS -> Action.LOCK
            else -> Action.NONE // A hold whose timer was delayed must never become a lock tap.
        }
        cancel()
        return action
    }

    fun cancel(): Action {
        val action = if (holdDelivered) Action.CANCEL else Action.NONE
        downTime = null
        holdAllowed = false
        holdHandled = false
        holdDelivered = false
        return action
    }

    companion object { const val HOLD_MS = 300L }
}
