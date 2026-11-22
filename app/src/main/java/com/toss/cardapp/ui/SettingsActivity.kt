package com.toss.cardapp.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.toss.cardapp.core.MatchField
import com.toss.cardapp.service.CardWatchService
import com.toss.cardapp.store.Settings

class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var pinState: TextView
    private lateinit var overlayState: TextView
    private lateinit var overlayButton: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        screen("설정", onBack = { finish() }) {
            add(panel().apply {
                add(label("화면 표시", 15f, Palette.secondary, bold = true))
                add(Switch(context).apply {
                    text = "카드번호 가리기 (끝 4자리만 표시)"
                    textSize = 17f
                    isChecked = settings.maskPan
                    setOnCheckedChangeListener { _, on -> settings.maskPan = on }
                }, top = 8)
                add(label("기본값은 전체 표시예요. 저장되는 기록에는 항상 전체 값이 암호화돼서 들어가요.", 14f, Palette.secondary), top = 4)
            })
            add(panel().apply {
                add(label("자동 실행", 15f, Palette.secondary, bold = true))
                add(Switch(context).apply {
                    text = "카드 넣으면 자동 실행"
                    textSize = 17f
                    isChecked = settings.autoLaunch
                    setOnCheckedChangeListener { _, on ->
                        settings.autoLaunch = on
                        CardWatchService.sync(context)
                    }
                }, top = 8)
                add(label("앱이 꺼져 있을 때 IC 카드를 꽂거나 마그네틱 카드를 긁으면 카드 리더가 열려서 바로 읽어요. 기기를 켜면 자동으로 대기해요.", 14f, Palette.secondary), top = 4)
                overlayState = label("", 15f)
                add(overlayState, top = 8)
                overlayButton = button("다른 앱 위에 표시 허용하기") {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                add(overlayButton, top = 8)
            }, top = 12)
            add(panel().apply {
                add(label("회원 비교 기준", 15f, Palette.secondary, bold = true))
                val fields = MatchField.entries
                add(spinner(fields.map { it.label }, fields.indexOf(settings.matchField)) { settings.matchField = fields[it] }, top = 8)
                add(label("새로 등록하는 회원에 쓰여요. 이미 등록한 회원은 등록할 때의 기준을 그대로 써요.", 14f, Palette.secondary), top = 4)
            }, top = 12)
            add(panel().apply {
                add(label("키오스크", 15f, Palette.secondary, bold = true))
                pinState = label("", 17f)
                add(pinState, top = 8)
                add(button("관리자 PIN 정하기") { setPin() }, top = 8)
                add(button("키오스크 시작", primary = true) {
                    if (settings.hasAdminPin) startActivity(Intent(context, KioskActivity::class.java)) else setPin()
                }, top = 8)
            }, top = 12)
            add(panel().apply {
                add(label("데이터", 15f, Palette.secondary, bold = true))
                add(label("기록·회원·규칙은 앱 전용 저장소에 Android Keystore 키(AES-256-GCM)로 암호화해서 저장해요. 인터넷 권한이 없어 기기 밖으로 나가지 않아요.", 15f), top = 8)
            }, top = 12)
        }
        renderPin()
    }

    override fun onResume() {
        super.onResume()
        renderOverlay()
    }

    // Android only lets a background service open a screen when the app may draw over other apps.
    private fun renderOverlay() {
        val allowed = android.provider.Settings.canDrawOverlays(this)
        overlayState.text = if (allowed) "다른 앱 위에 표시: 허용됨" else "다른 앱 위에 표시가 꺼져 있어서 자동 실행이 화면을 열 수 없어요 (긁은 카드는 기록에만 남아요)"
        overlayState.setTextColor(if (allowed) Palette.secondary else Palette.denied)
        overlayButton.visibility = if (allowed) View.GONE else View.VISIBLE
    }

    private fun renderPin() {
        pinState.text = if (settings.hasAdminPin) "관리자 PIN이 설정돼 있어요" else "관리자 PIN이 아직 없어요 (키오스크를 쓰려면 필요해요)"
    }

    private fun setPin() {
        val pin = EditText(this).apply { hint = "새 PIN (4~8자리 숫자)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val again = EditText(this).apply { hint = "한 번 더"; inputType = pin.inputType }
        val form = vertical(20).apply { add(pin); add(again, top = 8) }
        AlertDialog.Builder(this).setTitle("관리자 PIN").setView(form)
            .setPositiveButton("저장") { _, _ ->
                val p = pin.text.toString()
                when {
                    p.length !in 4..8 -> toast("4~8자리로 정해 주세요")
                    p != again.text.toString() -> toast("두 PIN이 달라요")
                    else -> { settings.setAdminPin(p); renderPin(); toast("저장했어요") }
                }
            }
            .setNegativeButton("취소", null).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
