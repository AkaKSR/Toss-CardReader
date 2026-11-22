package com.toss.cardapp.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import com.toss.cardapp.card.Pan
import com.toss.cardapp.core.MatchField
import com.toss.cardapp.core.Member
import com.toss.cardapp.store.AppStore
import com.toss.cardapp.store.LogEntry
import com.toss.cardapp.store.Settings

fun Context.spinner(items: List<String>, selected: Int = 0, onSelect: (Int) -> Unit = {}) = Spinner(this).apply {
    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
    setSelection(selected)
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = onSelect(pos)
        override fun onNothingSelected(p: AdapterView<*>?) = Unit
    }
}

class MembersActivity : Activity() {
    private lateinit var store: AppStore
    private lateinit var settings: Settings
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore.get(this)
        settings = Settings(this)
        screen("회원 · 직원 카드", onBack = { finish() }) {
            val actions = horizontal()
            actions.add(button("최근 카드로 등록", primary = true) { pickRecent() }, weight = 1f, end = 8)
            actions.add(button("직접 입력") { edit(null, null) }, weight = 1f)
            add(actions)
            add(label("카드를 읽으면 등록된 값과 비교해서 허용·차단·미등록을 보여줘요. 새 등록의 기본 비교 기준: ${settings.matchField.label} (설정에서 변경)", 14f, Palette.secondary), top = 10)
            list = vertical()
            add(list, top = 12)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val members = store.members().sortedBy { it.name }
        list.add(label("${members.size}명", 15f, Palette.secondary, bold = true))
        if (members.isEmpty()) list.add(label("등록된 카드가 없어요", 18f), top = 12)
        for (m in members) list.add(row(m), top = 8)
    }

    private fun row(m: Member) = panel(16).apply {
        val head = horizontal()
        head.add(label(m.name, 19f, bold = true), weight = 1f)
        val (fg, _) = Palette.verdict(if (m.allowed) com.toss.cardapp.core.Verdict.ALLOWED else com.toss.cardapp.core.Verdict.DENIED)
        head.add(label(if (m.allowed) "허용" else "차단", 15f, fg, bold = true), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        add(head)
        val shown = if (m.field == MatchField.PAN) Pan.display(m.value, settings.maskPan) else m.value
        add(label("${m.field.label}: $shown", 15f, Palette.secondary, mono = true), top = 4)
        setOnClickListener { edit(m, null) }
    }

    private fun pickRecent() {
        val recent = store.logs().take(20)
        if (recent.isEmpty()) {
            Toast.makeText(this, "먼저 카드를 한 번 읽어 주세요", Toast.LENGTH_LONG).show()
            return
        }
        val labels = recent.map { "${formatTime(it.read.time)}  ${kindLabel(it.read.kind)}  ${Pan.display(it.read.pan, settings.maskPan)}" }
        AlertDialog.Builder(this).setTitle("등록할 카드 선택")
            .setItems(labels.toTypedArray()) { _, i -> edit(null, recent[i]) }
            .setNegativeButton("취소", null).show()
    }

    private fun edit(existing: Member?, from: LogEntry?) {
        val fields = MatchField.entries
        var field = existing?.field ?: settings.matchField
        val name = EditText(this).apply { hint = "이름"; setText(existing?.name ?: "") }
        val value = EditText(this).apply { hint = "비교할 값"; setText(existing?.value ?: from?.let { field.valueOf(it.read) } ?: "") }
        val allowed = CheckBox(this).apply { text = "출입 허용"; isChecked = existing?.allowed ?: true }
        val fieldPicker = spinner(fields.map { it.label }, fields.indexOf(field)) { i ->
            field = fields[i]
            if (from != null) value.setText(field.valueOf(from.read) ?: "")
        }
        val form = vertical(20).apply {
            add(name)
            add(label("비교 기준", 13f, Palette.secondary), top = 12)
            add(fieldPicker)
            add(value, top = 8)
            add(allowed, top = 8)
        }
        val b = AlertDialog.Builder(this).setTitle(if (existing == null) "카드 등록" else "회원 수정").setView(form)
            .setPositiveButton("저장") { _, _ ->
                val n = name.text.toString().trim()
                val v = value.text.toString().trim()
                if (n.isEmpty() || v.isEmpty()) {
                    Toast.makeText(this, "이름과 값을 모두 입력해 주세요", Toast.LENGTH_LONG).show()
                } else {
                    store.upsertMember(Member(existing?.id ?: AppStore.newId(), n, field, if (field == MatchField.HOLDER) v.uppercase() else v, allowed.isChecked))
                    render()
                }
            }
            .setNegativeButton("취소", null)
        if (existing != null) b.setNeutralButton("삭제") { _, _ -> store.deleteMember(existing.id); render() }
        b.show()
    }
}
