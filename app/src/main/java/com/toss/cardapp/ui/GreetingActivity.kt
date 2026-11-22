package com.toss.cardapp.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import com.toss.cardapp.core.Verdict

class GreetingActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val verdict = Verdict.valueOf(intent.getStringExtra(EXTRA_VERDICT) ?: Verdict.UNKNOWN.name)
        val name = intent.getStringExtra(EXTRA_NAME)?.let { if (it.endsWith("님")) it.dropLast(1) else it }
        val (fg, bg) = Palette.verdict(verdict)
        val root = vertical(32).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            add(label(verdict.label, 24f, fg, bold = true).apply { gravity = Gravity.CENTER })
            add(label(when (verdict) {
                Verdict.ALLOWED -> "${name ?: ""}님, 환영해요"
                Verdict.DENIED -> "${name ?: ""}님은 출입할 수 없어요"
                Verdict.UNKNOWN -> "등록되지 않은 카드예요"
            }, 40f, fg, bold = true).apply { gravity = Gravity.CENTER }, top = 16)
            setOnClickListener { finish() }
        }
        setContentView(root)
        main.postDelayed({ finish() }, SHOW_MS)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_VERDICT = "verdict"
        private const val SHOW_MS = 5000L
    }
}
