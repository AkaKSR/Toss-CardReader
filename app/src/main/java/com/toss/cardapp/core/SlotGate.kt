package com.toss.cardapp.core

/**
 * Remembers that the IC card in the slot was already read, so a screen that comes back while it is still inserted
 * asks for removal instead of reading it again. A card only detected (the watcher's hand-over) is not "read".
 */
object SlotGate {
    @Volatile private var consumed = false

    fun markRead() {
        consumed = true
    }

    fun mustWaitForRemoval(cardPresent: Boolean): Boolean {
        if (!cardPresent) consumed = false
        return cardPresent && consumed
    }
}
