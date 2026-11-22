package com.toss.cardapp.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.toss.cardapp.core.Decision
import com.toss.cardapp.core.ReaderController
import com.toss.cardapp.core.ReaderStatus
import com.toss.cardapp.service.CardWatchService
import com.toss.cardapp.store.LogEntry
import com.toss.cardapp.store.Settings

open class ReaderActivity : Activity(), ReaderController.Listener {
    protected open val kiosk = false
    private lateinit var controller: ReaderController
    private lateinit var settings: Settings
    private lateinit var status: TextView
    private lateinit var result: FrameLayout
    private val main = Handler(Looper.getMainLooper())
    private val clearResult = Runnable { showIdle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        controller = ReaderController(this, this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = vertical().apply { setBackgroundColor(Palette.bg) }
        val header = vertical(20)
        val titleRow = horizontal()
        titleRow.add(label(if (kiosk) "카드를 대 주세요" else "카드 리더", 26f, bold = true), weight = 1f)
        if (!kiosk) titleRow.add(button("키오스크") { startKiosk() }, width = ViewGroup.LayoutParams.WRAP_CONTENT)
        header.add(titleRow)
        status = label("", 17f, Palette.secondary)
        header.add(status, top = 6)
        if (!kiosk) {
            val nav = horizontal()
            nav.add(button("기록") { open(LogActivity::class.java) }, weight = 1f, end = 8)
            nav.add(button("회원") { open(MembersActivity::class.java) }, weight = 1f, end = 8)
            nav.add(button("규칙") { open(RulesActivity::class.java) }, weight = 1f, end = 8)
            nav.add(button("설정") { open(SettingsActivity::class.java) }, weight = 1f)
            header.add(nav, top = 16)
        }
        root.add(header)

        result = FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), dp(20)) }
        root.addView(ScrollView(this).apply { addView(result) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (kiosk) {
            val footer = horizontal().apply { gravity = Gravity.END; setPadding(dp(12), dp(8), dp(12), dp(12)) }
            footer.add(button("관리자") { askPinThenExit() }, width = ViewGroup.LayoutParams.WRAP_CONTENT)
            root.add(footer)
        }
        setContentView(root)
        showIdle()
        if (!kiosk) {
            CardWatchService.sync(this)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (kiosk) enterKiosk()
        controller.start()
        takeAutoLaunch()
    }

    // Opened by the background watcher: wake the display, give the detected swipe to the controller (an IC card is
    // re-detected by the controller's own checkCard), and log detect-to-first-frame time (card kind and ms only).
    private fun takeAutoLaunch() {
        val detectedAt = intent.getLongExtra(EXTRA_DETECTED_AT, 0L)
        if (detectedAt == 0L) return
        intent.removeExtra(EXTRA_DETECTED_AT)
        val kind = intent.getStringExtra(EXTRA_DETECTED_KIND)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        CardWatchService.takeHandover()?.let { controller.inject(it) }
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                Log.i(TAG, "shown $kind +${SystemClock.elapsedRealtime() - detectedAt}ms")
                return true
            }
        })
    }

    override fun onPause() {
        controller.stop()
        super.onPause()
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    override fun onStatus(status: ReaderStatus, detail: String?) {
        this.status.text = when (status) {
            ReaderStatus.CONNECTING -> "리더 서비스에 연결하는 중이에요"
            ReaderStatus.WAITING -> "IC 카드를 꽂거나 마그네틱 카드를 긁어 주세요"
            ReaderStatus.READING -> "IC 카드를 읽는 중이에요. 카드를 빼지 마세요"
            ReaderStatus.REMOVE_CARD -> "다 읽었어요. 카드를 빼 주세요"
            ReaderStatus.ERROR -> "리더 오류 (${detail ?: "-"}). 다시 시도하고 있어요"
            ReaderStatus.SERVICE_MISSING -> "결제 하드웨어 서비스(com.sunmi.pay.hardware_v3)를 찾을 수 없어요"
        }
    }

    override fun onRead(entry: LogEntry, decision: Decision) {
        result.removeAllViews()
        result.addView(panel().apply { add(cardDetails(entry, settings.maskPan)) })
        main.removeCallbacks(clearResult)
        if (kiosk) main.postDelayed(clearResult, KIOSK_RESULT_MS)
        RuleRunner.run(this, decision, kiosk)
    }

    private fun showIdle() {
        result.removeAllViews()
        result.addView(panel(28).apply {
            add(label("대기 중", 15f, Palette.secondary, bold = true))
            add(label("카드를 읽으면 여기에 전체 정보가 표시돼요", 22f, bold = true), top = 6)
            add(label("IC: ATR, 카드번호, 유효기간, 소유자, AID, 앱 이름, EMV 공개 레코드\n마그네틱: 트랙 1·2·3 전체", 15f, Palette.secondary), top = 10)
        })
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun startKiosk() {
        if (!settings.hasAdminPin) {
            Toast.makeText(this, "먼저 설정에서 관리자 PIN을 정해 주세요", Toast.LENGTH_LONG).show()
            open(SettingsActivity::class.java)
            return
        }
        startActivity(Intent(this, KioskActivity::class.java))
    }

    private fun enterKiosk() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) {
            dpm.setLockTaskPackages(ComponentName(this, AdminReceiver::class.java), arrayOf(packageName))
        }
        val am = getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } ?: @Suppress("DEPRECATION") run {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun askPinThenExit() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "관리자 PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("키오스크 종료")
            .setView(FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) })
            .setPositiveButton("종료") { _, _ ->
                if (settings.checkAdminPin(input.text.toString())) {
                    stopLockTask()
                    finish()
                } else {
                    Toast.makeText(this, "PIN이 맞지 않아요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    @Deprecated("Back is disabled in kiosk mode")
    override fun onBackPressed() {
        if (!kiosk) @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        const val EXTRA_DETECTED_AT = "detected_at"
        const val EXTRA_DETECTED_KIND = "detected_kind"
        private const val TAG = "CardAutoLaunch"
        private const val KIOSK_RESULT_MS = 15_000L
    }
}

class KioskActivity : ReaderActivity() {
    override val kiosk = true
}

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = Unit
}
