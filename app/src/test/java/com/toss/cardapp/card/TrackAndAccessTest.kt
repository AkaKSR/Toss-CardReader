package com.toss.cardapp.card

import com.toss.cardapp.core.Access
import com.toss.cardapp.core.MatchField
import com.toss.cardapp.core.Member
import com.toss.cardapp.core.Rule
import com.toss.cardapp.core.RuleAction
import com.toss.cardapp.core.Screen
import com.toss.cardapp.core.Trigger
import com.toss.cardapp.core.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Synthetic fixtures use public test numbers, never data captured from a card.
class TrackParserTest {
    @Test
    fun parsesTrack1WithSentinels() {
        val t = TrackParser.track1("%B4242424242424242^TEST/ONE                  ^4912101000000000?7")!!
        assertEquals("4242424242424242", t.pan)
        assertEquals("TEST/ONE", t.holder)
        assertEquals("4912", t.expiry)
        assertEquals("101", t.serviceCode)
    }

    @Test
    fun parsesTrack2WithEqualsOrDSeparator() {
        assertEquals("6011111111111117", TrackParser.track2(";6011111111111117=49122010000000000000?")!!.pan)
        val d = TrackParser.track2("6011111111111117D4912201")!!
        assertEquals("4912", d.expiry)
        assertEquals("201", d.serviceCode)
        assertNull(TrackParser.track2("1234567890"))
    }

    @Test
    fun msrReadPrefersTrack2PanAndTakesNameFromTrack1() {
        val r = TrackParser.fromTracks(1L, "B5105105105105100^TEST/TWO^4901", "4242424242424242=4912101", null)
        assertEquals("4242424242424242", r.pan)
        assertEquals("TEST/TWO", r.holder)
        assertEquals(CardKind.MSR, r.kind)
        assertNull(r.track3)
    }
}

class AccessTest {
    private val card = CardRead(time = 1L, kind = CardKind.IC, pan = "4242424242424242", holder = "Test/One")
    private val alice = Member(1, "Alice", MatchField.PAN, "4242424242424242")
    private val bob = Member(2, "Bob", MatchField.HOLDER, "TEST/ONE", allowed = false)

    @Test
    fun verdictFollowsMatchedMember() {
        assertEquals(Verdict.ALLOWED, Access.decide(card, listOf(alice), emptyList()).verdict)
        assertEquals(Verdict.DENIED, Access.decide(card, listOf(bob), emptyList()).verdict)
        assertEquals(Verdict.UNKNOWN, Access.decide(card.copy(pan = "2223003122003222", holder = null), listOf(alice, bob), emptyList()).verdict)
    }

    @Test
    fun firstMatchingEnabledRuleWins() {
        val rules = listOf(
            Rule(10, "off", Trigger.ANY_CARD, null, RuleAction.OpenApp("x"), enabled = false),
            Rule(11, "msr", Trigger.MSR, null, RuleAction.OpenScreen(Screen.LOG)),
            Rule(12, "alice", Trigger.MEMBER, 1, RuleAction.OpenScreen(Screen.GREETING)),
            Rule(13, "any", Trigger.ANY_CARD, null, RuleAction.OpenScreen(Screen.MEMBERS)),
        )
        assertEquals("alice", Access.decide(card, listOf(alice), rules).rule?.name)
        assertEquals("any", Access.decide(card, emptyList(), rules).rule?.name)
    }
}
