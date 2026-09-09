package com.fumble.app.ui.swipe

/**
 * Decides when to ask the system to empty the trash queue.
 *
 * The single most important property here is what the decision is made *from*: the
 * size of the durable queue, never a count of swipes.
 *
 * An earlier version counted left swipes in a ViewModel field. That field died with
 * the process, while the queue in Room did not, so a queue built up across several
 * background-and-return cycles never reached the threshold and grew without bound —
 * the user could swipe hundreds of photos away and never once be asked. Taking the
 * queue size as input makes the policy stateless with respect to sessions: a backlog
 * inherited from last week is treated exactly like one built up in the last minute.
 *
 * The only state it does keep is the raised threshold after a refusal, which is
 * deliberately allowed to evaporate when the process dies. Worst case that means being
 * asked once more than strictly necessary, which is the harmless direction to fail in.
 */
class TrashPromptPolicy(
    threshold: Int = DEFAULT_THRESHOLD,
    private val declineGrace: Int = DEFAULT_DECLINE_GRACE,
) {

    /**
     * How many queued photos trigger a prompt. A user setting, so it can change at any
     * time; doing so clears any grace period, because the user has just restated what
     * they want and honouring the old number would ignore that.
     */
    var threshold: Int = threshold
        set(value) {
            if (field == value) return
            field = value
            promptAt = value
        }

    /** Queue size at which the next automatic prompt is allowed. */
    var promptAt: Int = threshold
        private set

    fun shouldPrompt(pendingCount: Int): Boolean = pendingCount >= promptAt

    /** The queue was emptied, or there was nothing to empty. */
    fun onSettled() {
        promptAt = threshold
    }

    /**
     * The user dismissed the dialog. Nothing was written, so give them room to keep
     * swiping instead of asking again on the very next left swipe.
     */
    fun onDeclined(pendingCount: Int) {
        promptAt = pendingCount + declineGrace
    }

    /** The queue could not be reached. Back off rather than retry on every swipe. */
    fun onFailed() {
        promptAt += declineGrace
    }

    companion object {
        /**
         * Queued left swipes to collect before asking the system to empty them in one go.
         *
         * This is the whole cost/benefit of batching: higher means fewer interruptions
         * but a longer stretch where the photos still occupy storage, and a bigger pile
         * to lose track of if you never confirm. The pending pill keeps that honest by
         * always showing the count and the bytes, and tapping it flushes immediately.
         */
        const val DEFAULT_THRESHOLD = 100

        /** Extra photos allowed after the user dismisses the dialog. */
        const val DEFAULT_DECLINE_GRACE = 20
    }
}
