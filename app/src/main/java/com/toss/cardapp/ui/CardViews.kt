package com.toss.cardapp.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.toss.cardapp.card.CardKind
import com.toss.cardapp.card.CardRead
import com.toss.cardapp.card.Pan
import com.toss.cardapp.core.Verdict
import com.toss.cardapp.store.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

fun formatTime(t: Long): String = timeFmt.format(Date(t))

fun kindLabel(k: CardKind) = when (k) {
    CardKind.IC -> "IC 카드"
    CardKind.MSR -> "마그네틱"
}

fun Context.verdictBand(verdict: Verdict, memberName: String?): View {
    val (fg, bg) = Palette.verdict(verdict)
    return vertical(16).apply {
        background = rounded(bg, dp(12))
        add(label(verdict.label, 15f, fg, bold = true))
        add(label(memberName ?: if (verdict == Verdict.UNKNOWN) "등록되지 않은 카드" else "-", 28f, fg, bold = true), top = 4)
    }
}

fun Context.cardDetails(entry: LogEntry, masked: Boolean, showVerdict: Boolean = true): LinearLayout {
    val r = entry.read
    return vertical().apply {
        if (showVerdict) add(verdictBand(entry.verdict, entry.memberName))
        add(label("${kindLabel(r.kind)} · ${formatTime(r.time)}", 14f, Palette.secondary), top = 16)
        add(label(Pan.display(r.pan, masked), 30f, mono = true, bold = true).apply { setTextIsSelectable(true) }, top = 6)
        val grid = horizontal().apply { gravity = Gravity.TOP }
        val left = vertical()
        val right = vertical()
        left.add(fieldRow("유효기간", Pan.expiryDisplay(r.expiry)))
        left.add(fieldRow("소유자", r.holder ?: "-"), top = 12)
        left.add(fieldRow("서비스 코드", r.serviceCode ?: "-", mono = true), top = 12)
        right.add(fieldRow("앱 이름", r.appLabel ?: "-"))
        right.add(fieldRow("AID", r.aid ?: "-", mono = true), top = 12)
        if (entry.ruleName != null) right.add(fieldRow("적용된 규칙", entry.ruleName), top = 12)
        grid.add(left, weight = 1f, end = 12)
        grid.add(right, weight = 1f)
        add(grid, top = 16)
        if (r.atr != null) add(fieldRow("ATR", r.atr, mono = true), top = 12)
        if (r.kind == CardKind.MSR) {
            add(fieldRow("트랙 1", trackText(r.track1, masked, r.pan), mono = true), top = 12)
            add(fieldRow("트랙 2", trackText(r.track2, masked, r.pan), mono = true), top = 12)
            add(fieldRow("트랙 3", trackText(r.track3, masked, r.pan), mono = true), top = 12)
        }
        if (r.note != null) add(label(r.note, 14f, Palette.secondary), top = 12)
        if (r.emvFields.isNotEmpty()) {
            add(label("EMV 공개 레코드 (${r.emvFields.size})", 15f, bold = true), top = 20)
            for (f in r.emvFields) {
                val v = if (masked && r.pan != null) f.value.replace(r.pan, Pan.mask(r.pan)) else f.value
                add(fieldRow("${f.tag} ${f.name}", v, mono = true), top = 8)
            }
        }
    }
}

private fun trackText(t: String?, masked: Boolean, pan: String?): String {
    if (t.isNullOrEmpty()) return "없음"
    val shown = if (masked && pan != null) t.replace(pan, Pan.mask(pan)) else t
    return if (shown.all { it.code in 0x20..0x7E }) shown
    else "HEX " + shown.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
