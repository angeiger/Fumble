package com.fumble.app.ui.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashPromptPolicyTest {

    private val policy = TrashPromptPolicy(threshold = 100, declineGrace = 20)

    @Test
    fun `does not prompt below the threshold`() {
        assertFalse(policy.shouldPrompt(0))
        assertFalse(policy.shouldPrompt(99))
    }

    @Test
    fun `prompts at the threshold`() {
        assertTrue(policy.shouldPrompt(100))
    }

    /**
     * The regression this class exists for. The old implementation counted swipes in a
     * ViewModel field, so a queue carried in from previous sessions was invisible to
     * the trigger and could grow without limit.
     */
    @Test
    fun `a backlog inherited from an earlier session prompts immediately`() {
        val freshSession = TrashPromptPolicy(threshold = 100, declineGrace = 20)
        assertTrue(freshSession.shouldPrompt(380))
    }

    @Test
    fun `declining buys exactly the grace and no more`() {
        policy.onDeclined(pendingCount = 100)

        assertEquals(120, policy.promptAt)
        assertFalse(policy.shouldPrompt(119))
        assertTrue(policy.shouldPrompt(120))
    }

    @Test
    fun `grace is measured from the queue, not from the threshold`() {
        // Declining a dialog that had grown past the threshold must not hand out a
        // second grace period on top of the overshoot.
        policy.onDeclined(pendingCount = 140)

        assertEquals(160, policy.promptAt)
    }

    @Test
    fun `undoing after a decline does not bring the prompt back early`() {
        policy.onDeclined(pendingCount = 100)
        // The user takes ten swipes back, then swipes ten more. Still short of 120.
        assertFalse(policy.shouldPrompt(90))
        assertFalse(policy.shouldPrompt(100))
    }

    @Test
    fun `emptying the queue restores the normal threshold`() {
        policy.onDeclined(pendingCount = 140)
        policy.onSettled()

        assertEquals(100, policy.promptAt)
        assertTrue(policy.shouldPrompt(100))
    }

    @Test
    fun `lowering the threshold in settings applies at once`() {
        policy.onDeclined(pendingCount = 100)
        assertEquals(120, policy.promptAt)

        policy.threshold = 50

        // The user has just restated what they want; honouring the old grace period
        // would ignore that.
        assertEquals(50, policy.promptAt)
        assertTrue(policy.shouldPrompt(50))
    }

    /**
     * The ViewModel writes the threshold on every emission of the settings flow, so
     * re-writing the same value must not quietly cancel a grace period.
     */
    @Test
    fun `rewriting the same threshold leaves a grace period intact`() {
        policy.onDeclined(pendingCount = 100)
        policy.threshold = 100

        assertEquals(120, policy.promptAt)
        assertFalse(policy.shouldPrompt(110))
    }

    @Test
    fun `repeated failures back off instead of retrying every swipe`() {
        policy.onFailed()
        assertEquals(120, policy.promptAt)

        policy.onFailed()
        assertEquals(140, policy.promptAt)
    }
}
