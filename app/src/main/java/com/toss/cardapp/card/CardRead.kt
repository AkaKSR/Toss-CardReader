package com.toss.cardapp.card

enum class CardKind { IC, MSR }

data class EmvField(val tag: String, val name: String, val value: String)

data class CardRead(
    val time: Long,
    val kind: CardKind,
    val pan: String? = null,
    val expiry: String? = null,
    val holder: String? = null,
    val serviceCode: String? = null,
    val atr: String? = null,
    val aid: String? = null,
    val appLabel: String? = null,
    val track1: String? = null,
    val track2: String? = null,
    val track3: String? = null,
    val emvFields: List<EmvField> = emptyList(),
    val note: String? = null,
)

object Pan {
    fun mask(pan: String): String =
        if (pan.length <= 4) pan else "*".repeat(pan.length - 4) + pan.takeLast(4)

    fun group(pan: String): String = pan.chunked(4).joinToString(" ")

    fun display(pan: String?, masked: Boolean): String =
        if (pan.isNullOrEmpty()) "-" else group(if (masked) mask(pan) else pan)

    fun expiryDisplay(yymm: String?): String =
        if (yymm == null || yymm.length < 4) "-" else "${yymm.substring(2, 4)}/${yymm.substring(0, 2)}"
}
