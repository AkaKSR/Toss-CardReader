package com.toss.cardapp.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SlotGateTest {
    @Before
    fun emptySlot() {
        SlotGate.mustWaitForRemoval(cardPresent = false)
    }

    @Test
    fun readCardStillInsertedMustBeRemovedFirst() {
        SlotGate.markRead()
        assertTrue(SlotGate.mustWaitForRemoval(cardPresent = true))
        assertTrue(SlotGate.mustWaitForRemoval(cardPresent = true))
    }

    @Test
    fun removalAllowsTheNextRead() {
        SlotGate.markRead()
        assertFalse(SlotGate.mustWaitForRemoval(cardPresent = false))
        assertFalse(SlotGate.mustWaitForRemoval(cardPresent = true))
    }

    @Test
    fun handedOverCardThatWasNeverReadIsReadOnce() {
        assertFalse(SlotGate.mustWaitForRemoval(cardPresent = true))
    }
}
