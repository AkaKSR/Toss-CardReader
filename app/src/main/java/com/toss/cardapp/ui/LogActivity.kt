package com.toss.cardapp.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.toss.cardapp.card.Pan
import com.toss.cardapp.store.AppStore
import com.toss.cardapp.store.CsvExport
import com.toss.cardapp.store.LogEntry
import com.toss.cardapp.store.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : Activity() {
    private lateinit var store: AppStore
    private lateinit var settings: Settings
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore.get(this)
        settings = Settings(this)
        screen("기록", onBack = { finish() }) {
            val actions = horizontal()
            actions.add(button("파일로 내보내기", primary = true) { export() }, weight = 1f, end = 8)
            actions.add(button("전체 삭제") { confirmClear() }, weight = 1f)
            add(actions)
            add(label("기록은 기기 안에 암호화해서 저장돼요. 내보내기는 직접 고른 위치에 CSV(평문)로 저장해요.", 14f, Palette.secondary), top = 10)
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
        val logs = store.logs()
        list.add(label("${logs.size}건", 15f, Palette.secondary, bold = true))
        if (logs.isEmpty()) list.add(label("아직 기록이 없어요", 18f), top = 12)
        for (e in logs) list.add(row(e), top = 8)
    }

    private fun row(e: LogEntry) = panel(16).apply {
        val (fg, _) = Palette.verdict(e.verdict)
        val top = horizontal()
        top.add(label(formatTime(e.read.time), 14f, Palette.secondary), weight = 1f)
        top.add(label("${kindLabel(e.read.kind)} · ${e.verdict.label}", 14f, fg, bold = true), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        add(top)
        add(label(Pan.display(e.read.pan, settings.maskPan), 19f, mono = true, bold = true), top = 4)
        val who = listOfNotNull(e.memberName, e.read.holder, e.read.appLabel).joinToString(" · ")
        if (who.isNotEmpty()) add(label(who, 15f), top = 2)
        setOnClickListener { detail(e) }
    }

    private fun detail(e: LogEntry) {
        val content = cardDetails(e, settings.maskPan).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(content) }).setPositiveButton("닫기", null).show()
    }

    private fun export() {
        val name = "card-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv").putExtra(Intent.EXTRA_TITLE, name),
            REQ_EXPORT,
        )
    }

    @Deprecated("framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || uri == null) return
        val logs = store.logs()
        val ok = runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(CsvExport.render(logs).toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        Toast.makeText(this, if (ok) "${logs.size}건을 내보냈어요" else "내보내기에 실패했어요", Toast.LENGTH_LONG).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("기록 전체 삭제")
            .setMessage("기기에 저장된 카드 기록 ${store.logs().size}건을 모두 지워요. 되돌릴 수 없어요.")
            .setPositiveButton("삭제") { _, _ -> store.clearLogs(); render() }
            .setNegativeButton("취소", null)
            .show()
    }

    companion object {
        private const val REQ_EXPORT = 41
    }
}
