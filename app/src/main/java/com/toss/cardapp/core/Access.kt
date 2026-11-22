package com.toss.cardapp.core

import com.toss.cardapp.card.CardKind
import com.toss.cardapp.card.CardRead

enum class MatchField(val label: String) {
    PAN("카드번호"),
    HOLDER("소유자 이름"),
    TRACK1("트랙 1"),
    TRACK2("트랙 2"),
    TRACK3("트랙 3"),
    ATR("ATR"),
    AID("AID");

    fun valueOf(read: CardRead): String? = when (this) {
        PAN -> read.pan
        HOLDER -> read.holder?.trim()?.uppercase()
        TRACK1 -> read.track1
        TRACK2 -> read.track2
        TRACK3 -> read.track3
        ATR -> read.atr
        AID -> read.aid
    }?.ifEmpty { null }
}

data class Member(
    val id: Long,
    val name: String,
    val field: MatchField,
    val value: String,
    val allowed: Boolean = true,
)

enum class Verdict(val label: String) { ALLOWED("허용"), DENIED("차단"), UNKNOWN("미등록") }

enum class Trigger(val label: String) {
    ANY_CARD("모든 카드"),
    ALLOWED("허용된 회원"),
    DENIED("차단된 회원"),
    UNKNOWN("미등록 카드"),
    MEMBER("특정 회원"),
    IC("IC 카드"),
    MSR("마그네틱 카드"),
}

enum class Screen(val label: String) {
    GREETING("환영 화면"),
    LOG("기록"),
    MEMBERS("회원"),
}

sealed interface RuleAction {
    data class OpenScreen(val screen: Screen) : RuleAction
    data class OpenApp(val target: String) : RuleAction
}

data class Rule(
    val id: Long,
    val name: String,
    val trigger: Trigger,
    val memberId: Long?,
    val action: RuleAction,
    val enabled: Boolean = true,
)

data class Decision(val member: Member?, val verdict: Verdict, val rule: Rule?)

object Access {
    fun findMember(read: CardRead, members: List<Member>): Member? =
        members.firstOrNull { m -> m.field.valueOf(read)?.equals(m.value, ignoreCase = m.field == MatchField.HOLDER) == true }

    fun decide(read: CardRead, members: List<Member>, rules: List<Rule>): Decision {
        val member = findMember(read, members)
        val verdict = when {
            member == null -> Verdict.UNKNOWN
            member.allowed -> Verdict.ALLOWED
            else -> Verdict.DENIED
        }
        val rule = rules.firstOrNull { r ->
            r.enabled && when (r.trigger) {
                Trigger.ANY_CARD -> true
                Trigger.ALLOWED -> verdict == Verdict.ALLOWED
                Trigger.DENIED -> verdict == Verdict.DENIED
                Trigger.UNKNOWN -> verdict == Verdict.UNKNOWN
                Trigger.MEMBER -> member != null && member.id == r.memberId
                Trigger.IC -> read.kind == CardKind.IC
                Trigger.MSR -> read.kind == CardKind.MSR
            }
        }
        return Decision(member, verdict, rule)
    }
}
