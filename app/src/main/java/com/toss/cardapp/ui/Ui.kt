package com.toss.cardapp.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.toss.cardapp.core.Verdict

object Palette {
    val bg = Color.parseColor("#F6F6F3")
    val surface = Color.WHITE
    val line = Color.parseColor("#E2E2DC")
    val text = Color.parseColor("#1C1C1A")
    val secondary = Color.parseColor("#5A5A55")
    val accent = Color.parseColor("#0F5F5A")
    val allowed = Color.parseColor("#1D6B35")
    val denied = Color.parseColor("#A4262C")
    val unknown = Color.parseColor("#4A4A46")
    val allowedBg = Color.parseColor("#E5F2E8")
    val deniedBg = Color.parseColor("#F8E5E5")
    val unknownBg = Color.parseColor("#ECECE8")

    fun verdict(v: Verdict) = when (v) {
        Verdict.ALLOWED -> allowed to allowedBg
        Verdict.DENIED -> denied to deniedBg
        Verdict.UNKNOWN -> unknown to unknownBg
    }
}

fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun rounded(color: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius.toFloat()
    if (stroke != null) setStroke(strokeWidth, stroke)
}

fun Context.label(text: CharSequence, sizeSp: Float = 16f, color: Int = Palette.text, bold: Boolean = false, mono: Boolean = false) =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        fontFeatureSettings = "tnum"
    }

fun Context.vertical(padding: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.horizontal() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.panel(padding: Int = 20) = vertical(padding).apply {
    background = rounded(Palette.surface, dp(16), Palette.line, dp(1))
}

fun Context.button(text: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setTextColor(if (primary) Color.WHITE else Palette.text)
    val base = rounded(if (primary) Palette.accent else Palette.surface, dp(12), if (primary) null else Palette.line, dp(1))
    background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22000000")), base, null)
    stateListAnimator = null
    minHeight = dp(52)
    setPadding(dp(18), 0, dp(18), 0)
    setOnClickListener { onClick() }
}

fun LinearLayout.lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, top: Int = 0, end: Int = 0) =
    LinearLayout.LayoutParams(width, height, weight).apply {
        topMargin = context.dp(top)
        marginEnd = context.dp(end)
    }

fun LinearLayout.add(v: View, top: Int = 0, weight: Float = 0f, end: Int = 0, width: Int? = null): View {
    val w = width ?: if (weight > 0f && orientation == LinearLayout.HORIZONTAL) 0 else ViewGroup.LayoutParams.MATCH_PARENT
    addView(v, lp(width = w, weight = weight, top = top, end = end))
    return v
}

fun Context.fieldRow(name: String, value: String, mono: Boolean = false) = vertical().apply {
    add(label(name, 13f, Palette.secondary))
    add(label(value, 17f, mono = mono).apply { setTextIsSelectable(true) }, top = 2)
}

fun Activity.screen(title: String, onBack: (() -> Unit)? = null, build: LinearLayout.() -> Unit): LinearLayout {
    val root = vertical().apply { setBackgroundColor(Palette.bg) }
    val bar = horizontal().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
    if (onBack != null) bar.add(button("뒤로") { onBack() }, end = 12, width = ViewGroup.LayoutParams.WRAP_CONTENT)
    bar.add(label(title, 22f, bold = true), weight = 1f)
    root.add(bar)
    val content = vertical(16)
    content.build()
    root.addView(ScrollView(this).apply { addView(content); isFillViewport = true }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(root)
    return content
}
