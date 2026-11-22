package com.toss.cardapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.toss.cardapp.R
import com.toss.cardapp.card.CardRead
import com.toss.cardapp.card.TrackParser
import com.toss.cardapp.core.AppForeground
import com.toss.cardapp.core.ReaderController
import com.toss.cardapp.core.Readers
import com.toss.cardapp.store.AppStore
import com.toss.cardapp.store.Settings
import com.toss.cardapp.ui.ReaderActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.toss.cardreader.CardReaderApi
import com.toss.cardreader.CardReaderError
import com.toss.cardreader.CardReaderException
import com.toss.cardreader.CardTypes
import com.toss.cardreader.ServiceStatus

/**
 * Waits for an IC insert or MSR swipe while no app screen is showing and then opens the reader screen.
 *
 * The SP pushes nothing, so the watcher keeps exactly one card wait (IC | MSR) outstanding through [Readers],
 * re-armed on its 60 s timeout. It lets go of the reader (cancel) the moment an app screen resumes and takes it back
 * 1 s after the last one pauses, so the reader never sees two waits from this app.
 *
 * Hand-over, so each card is read once: an MSR swipe cannot be repeated, so the detected tracks are passed to the
 * screen in-process (never through Intent extras). For IC the screen's own checkCard fires at once for the card
 * still in the slot and the screen does the single EMV read. Logcat gets card kind and timings only.
 */
class CardWatchService : Service(), CardReaderApi.ConnectionListener {
    private lateinit var reader: CardReaderApi
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val session = AtomicInteger(0)
    private val armed = AtomicBoolean(false)
    @Volatile private var paused = false
    @Volatile private var announce = true
    private val resume = Runnable {
        paused = false
        announce = true
        arm()
    }
    private val onForeground: (Boolean) -> Unit = { visible -> if (visible) pause() else main.postDelayed(resume, RESUME_DELAY_MS) }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        instance = this
        paused = AppForeground.visible
        AppForeground.addListener(onForeground)
        reader = Readers.open(this)
        if (!reader.connect(this)) Log.w(TAG, "card reader not found")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        AppForeground.removeListener(onForeground)
        main.removeCallbacksAndMessages(null)
        synchronized(lock) {
            paused = true
            session.incrementAndGet()
            if (armed.getAndSet(false)) cancelWait()
            reader.disconnect()
        }
        worker.shutdownNow()
        Log.i(TAG, "stopped")
        super.onDestroy()
    }

    override fun onConnected() = arm()

    override fun onDisconnected() {
        armed.set(false)
        announce = true
    }

    // Main thread, from AppForeground. Synchronous so the screen's checkCard can only follow our cancel.
    private fun pause() {
        main.removeCallbacks(resume)
        synchronized(lock) {
            paused = true
            session.incrementAndGet()
            if (armed.getAndSet(false)) cancelWait()
        }
        Log.i(TAG, "paused: app screen visible")
    }

    private fun arm(delayMs: Long = 0) {
        main.postDelayed({ if (!worker.isShutdown) worker.execute { armNow() } }, delayMs)
    }

    private fun armNow() {
        if (paused || !reader.connected || armed.get()) return
        recordUntakenHandover()
        try {
            // Never arm over a card left in the slot (e.g. after leaving the reader screen with the card in),
            // or the reader would reopen for the same card straight away.
            if (reader.status().icCard == ServiceStatus.CARD_PRESENT) {
                arm(SLOT_POLL_MS)
                return
            }
            synchronized(lock) {
                if (paused || !armed.compareAndSet(false, true)) return
                val id = session.incrementAndGet()
                try {
                    reader.waitForCard(CardTypes.IC or CardTypes.MSR, ReaderController.CHECK_TIMEOUT_SEC, Session(id))
                } catch (e: CardReaderException) {
                    armed.set(false)
                    throw e
                }
                if (announce) Log.i(TAG, "armed: waiting for IC insert or MSR swipe")
                announce = false
            }
        } catch (e: CardReaderException) {
            Log.w(TAG, "card wait failed: ${CardReaderError.name(e.code)}")
            arm(RETRY_MS)
        }
    }

    private inner class Session(private val id: Int) : CardReaderApi.CardListener {
        private fun claim(): Boolean = session.get() == id && armed.compareAndSet(true, false)

        override fun onCardDetected(type: Int) {
            if (!claim()) return
            if (type == CardTypes.IC) {
                cancelWait() // before the launch, so the screen's wait can only come after it
                detected(null, "IC")
                return
            }
            val t = try {
                reader.readMsr()
            } catch (e: CardReaderException) {
                Log.w(TAG, "MSR read failed: ${CardReaderError.name(e.code)}")
                arm(RETRY_MS)
                return
            }
            detected(TrackParser.fromTracks(System.currentTimeMillis(), t.track1, t.track2, t.track3), "MSR")
        }

        override fun onError(code: Int, message: String?) {
            if (!claim()) return
            if (code != CardReaderError.TIMEOUT) Log.w(TAG, "card wait error ${CardReaderError.name(code)}")
            arm(if (code == CardReaderError.TIMEOUT) 0 else RETRY_MS)
        }
    }

    // A failed cancel means the reader (or the reader service) is gone; there is no wait left to stop.
    private fun cancelWait() {
        try {
            reader.cancel()
        } catch (e: CardReaderException) {
            Log.w(TAG, "cancel failed: ${CardReaderError.name(e.code)}")
        }
    }

    private fun detected(read: CardRead?, kind: String) {
        val at = SystemClock.elapsedRealtime()
        pending = read
        Log.i(TAG, "detected $kind")
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ReaderActivity.EXTRA_DETECTED_AT, at)
                .putExtra(ReaderActivity.EXTRA_DETECTED_KIND, kind)
        )
        // Normally the screen resumes (pausing us) well before this; if the launch was blocked, keep watching.
        arm(HANDOVER_TIMEOUT_MS)
    }

    // A swipe whose screen never came up (background start blocked) still goes into the encrypted log.
    private fun recordUntakenHandover() {
        val read = takeHandover() ?: return
        ReaderController.record(AppStore.get(this), read)
        Log.w(TAG, "reader screen did not open; MSR read saved to the log only")
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "카드 자동 실행", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, ReaderActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("카드 자동 실행 켜짐")
            .setContentText("IC 카드를 꽂거나 마그네틱 카드를 긁으면 카드 리더가 열려요")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CardAutoLaunch"
        private const val CHANNEL = "auto_launch"
        private const val NOTIFICATION_ID = 1
        private const val RESUME_DELAY_MS = 1000L
        private const val SLOT_POLL_MS = 500L
        private const val RETRY_MS = 3000L
        private const val HANDOVER_TIMEOUT_MS = 5000L

        @Volatile private var instance: CardWatchService? = null
        @Volatile private var pending: CardRead? = null

        @Synchronized fun takeHandover(): CardRead? = pending.also { pending = null }

        fun sync(context: Context) {
            val intent = Intent(context, CardWatchService::class.java)
            if (Settings(context).autoLaunch) context.startForegroundService(intent) else context.stopService(intent)
        }

        /** Debug builds only: run the detection hand-over as if the service had reported [read] (null = IC). */
        fun simulateDetection(read: CardRead?): Boolean {
            val svc = instance ?: return false
            svc.main.post {
                synchronized(svc.lock) {
                    svc.session.incrementAndGet()
                    if (svc.armed.getAndSet(false)) svc.cancelWait()
                }
                svc.detected(read, if (read == null) "IC" else "MSR")
            }
            return true
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            CardWatchService.sync(context)
        }
    }
}
