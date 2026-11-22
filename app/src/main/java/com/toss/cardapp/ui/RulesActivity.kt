package com.toss.cardapp.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.toss.cardapp.core.Rule
import com.toss.cardapp.core.RuleAction
import com.toss.cardapp.core.Screen
import com.toss.cardapp.core.Trigger
import com.toss.cardapp.store.AppStore

class RulesActivity : Activity() {
    private lateinit var store: AppStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore.get(this)
        screen("규칙", onBack = { finish() }) {
            add(button("규칙 추가", primary = true) { edit(null) })
            add(label("카드를 읽을 때 위에서부터 처음 맞는 규칙 하나만 실행해요.", 14f, Palette.secondary), top = 10)
            list = vertical()
            add(list, top = 12)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun describe(r: Rule): String {
        val who = if (r.trigger == Trigger.MEMBER) store.members().firstOrNull { it.id == r.memberId }?.name ?: "(삭제된 회원)" else r.trigger.label
        val what = when (val a = r.action) {
            is RuleAction.OpenScreen -> "${a.screen.label} 열기"
            is RuleAction.OpenApp -> "앱 열기: ${appName(a.target)}"
        }
        return "$who → $what"
    }

    private fun appName(target: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(target.substringBefore('/'), 0)).toString()
    }.getOrDefault(target)

    private fun render() {
        list.removeAllViews()
        val rules = store.rules()
        if (rules.isEmpty()) list.add(label("규칙이 없어요", 18f))
        for (r in rules) list.add(panel(16).apply {
            add(label(r.name + if (r.enabled) "" else " (꺼짐)", 19f, bold = true))
            add(label(describe(r), 15f, Palette.secondary), top = 4)
            setOnClickListener { edit(r) }
        }, top = 8)
    }

    private fun edit(existing: Rule?) {
        val triggers = Trigger.entries
        val members = store.members().sortedBy { it.name }
        val actions = Screen.entries.map { it.label } + "다른 앱 열기"
        var trigger = existing?.trigger ?: Trigger.ALLOWED
        var memberId = existing?.memberId ?: members.firstOrNull()?.id
        var actionIdx = when (val a = existing?.action) {
            is RuleAction.OpenScreen -> Screen.entries.indexOf(a.screen)
            is RuleAction.OpenApp -> Screen.entries.size
            null -> 0
        }
        var appTarget = (existing?.action as? RuleAction.OpenApp)?.target

        val name = EditText(this).apply { hint = "규칙 이름"; setText(existing?.name ?: "") }
        val memberPicker = spinner(members.map { it.name }, members.indexOfFirst { it.id == memberId }.coerceAtLeast(0)) { memberId = members[it].id }
        memberPicker.visibility = if (trigger == Trigger.MEMBER) View.VISIBLE else View.GONE
        val appLabel = label(appTarget?.let { appName(it) } ?: "앱을 골라 주세요", 15f, Palette.secondary)
        val appButton = button("앱 선택") {
            pickApp { pkg -> appTarget = pkg; appLabel.text = appName(pkg) }
        }
        fun syncApp() {
            val show = actionIdx == Screen.entries.size
            appButton.visibility = if (show) View.VISIBLE else View.GONE
            appLabel.visibility = appButton.visibility
        }
        val enabled = CheckBox(this).apply { text = "사용"; isChecked = existing?.enabled ?: true }
        val form = vertical(20).apply {
            add(name)
            add(label("언제", 13f, Palette.secondary), top = 12)
            add(spinner(triggers.map { it.label }, triggers.indexOf(trigger)) { i ->
                trigger = triggers[i]
                memberPicker.visibility = if (trigger == Trigger.MEMBER) View.VISIBLE else View.GONE
            })
            add(memberPicker)
            add(label("무엇을", 13f, Palette.secondary), top = 12)
            add(spinner(actions, actionIdx) { i -> actionIdx = i; syncApp() })
            add(appButton, top = 4)
            add(appLabel, top = 4)
            add(enabled, top = 8)
        }
        syncApp()
        val b = AlertDialog.Builder(this).setTitle(if (existing == null) "규칙 추가" else "규칙 수정").setView(form)
            .setPositiveButton("저장") { _, _ ->
                val action = if (actionIdx < Screen.entries.size) RuleAction.OpenScreen(Screen.entries[actionIdx]) else appTarget?.let { RuleAction.OpenApp(it) }
                when {
                    action == null -> toast("열 앱을 골라 주세요")
                    trigger == Trigger.MEMBER && memberId == null -> toast("먼저 회원을 등록해 주세요")
                    else -> {
                        val n = name.text.toString().trim().ifEmpty { trigger.label }
                        store.upsertRule(Rule(existing?.id ?: AppStore.newId(), n, trigger, if (trigger == Trigger.MEMBER) memberId else null, action, enabled.isChecked))
                        render()
                    }
                }
            }
            .setNegativeButton("취소", null)
        if (existing != null) b.setNeutralButton("삭제") { _, _ -> store.deleteRule(existing.id); render() }
        b.show()
    }

    private fun pickApp(onPick: (String) -> Unit) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second }
        AlertDialog.Builder(this).setTitle("열 앱")
            .setItems(apps.map { "${it.second}  (${it.first})" }.toTypedArray()) { _, i -> onPick(apps[i].first) }
            .setNegativeButton("취소", null).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
