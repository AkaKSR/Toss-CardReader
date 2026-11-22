package com.toss.cardapp.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.toss.cardapp.card.CardKind
import com.toss.cardapp.card.CardRead
import com.toss.cardapp.card.TrackParser
import com.toss.cardapp.emv.EmvReader
import com.toss.cardapp.emv.EmvResult
import com.toss.cardapp.hw.CardEvents
import com.toss.cardapp.hw.PayHardware
import com.toss.cardapp.store.AppStore
import com.toss.cardapp.store.LogEntry
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class ReaderStatus { CONNECTING, WAITING, READING, REMOVE_CARD, ERROR, SERVICE_MISSING }

/**
 * Card-reading loop shared by the normal and kiosk screens: arm checkCard(IC | MSR), read the card,
 * write the encrypted log entry, decide member/rule, then re-arm. Card data never goes to logcat.
 */
class ReaderController(context: Context, private val listener: Listener) : PayHardware.ConnectionListener {

    interface Listener {
        fun onStatus(status: ReaderStatus, detail: String? = null)
        fun onRead(entry: LogEntry, decision: Decision)
    }

    private val app = context.applicationContext
    private val hw = PayHardware(app)
    private val store = AppStore.get(app)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private val session = AtomicInteger(0)
    private val armed = AtomicBoolean(false)

    fun start() {
        running = true
        active = this
        hw.listener = this
        listener.onStatus(ReaderStatus.CONNECTING)
        if (!hw.bind()) listener.onStatus(ReaderStatus.SERVICE_MISSING)
    }

    fun stop() {
        running = false
        if (active === this) active = null
        main.removeCallbacksAndMessages(null)
        session.incrementAndGet()
        armed.set(false)
        hw.unbind()
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    override fun onConnected() = arm()

    override fun onDisconnected() {
        if (running) post { listener.onStatus(ReaderStatus.CONNECTING) }
    }

    // The service allows one detection at a time (INVOKING_REPEAT otherwise) and may report one event through
    // both the plain and the Ex callback. Exactly one checkCard is outstanding per session id, and only the
    // first callback of the current session is handled; anything else is dropped.
    private fun arm(delayMs: Long = 0) {
        main.postDelayed({
            if (!running || !hw.connected || !armed.compareAndSet(false, true)) return@postDelayed
            val id = session.incrementAndGet()
            worker.execute {
                try {
                    hw.checkCard(PayHardware.CARD_IC or PayHardware.CARD_MAGNETIC, CHECK_TIMEOUT_SEC, Session(id))
                    post { listener.onStatus(ReaderStatus.WAITING) }
                } catch (e: Exception) {
                    armed.set(false)
                    post { listener.onStatus(ReaderStatus.ERROR, e.javaClass.simpleName) }
                    arm(RETRY_MS)
                }
            }
        }, delayMs)
    }

    private inner class Session(private val id: Int) : CardEvents {
        private fun claim(): Boolean = session.get() == id && armed.compareAndSet(true, false)

        override fun onMagCard(track1: String?, track2: String?, track3: String?) {
            if (claim()) handleMag(track1, track2, track3)
        }

        override fun onIcCard(atr: String?) {
            if (claim()) handleIc(atr)
        }

        override fun onError(code: Int, message: String?) {
            if (claim()) handleError(code)
        }
    }

    private fun handleMag(track1: String?, track2: String?, track3: String?) {
        worker.execute {
            deliver(TrackParser.fromTracks(System.currentTimeMillis(), track1, track2, track3))
            arm(REARM_MS)
        }
    }

    private fun handleIc(atr: String?) {
        worker.execute {
            post { listener.onStatus(ReaderStatus.READING) }
            hw.cancelCheckCard()
            val emv = try {
                EmvReader({ apdu -> hw.transmitApdu(PayHardware.CARD_IC, apdu) }).read()
            } catch (e: Exception) {
                EmvResult(note = "EMV read failed: ${e.javaClass.simpleName}")
            }
            hw.cardOff(PayHardware.CARD_IC)
            deliver(
                CardRead(
                    time = System.currentTimeMillis(), kind = CardKind.IC, atr = atr,
                    pan = emv.pan, expiry = emv.expiry, holder = emv.holder, serviceCode = emv.serviceCode,
                    aid = emv.aid, appLabel = emv.appLabel, emvFields = emv.fields, note = emv.note,
                )
            )
            waitForRemoval()
        }
    }

    private fun handleError(code: Int) {
        if (code == ERR_INVOKING_REPEAT) hw.cancelCheckCard()
        if (code != ERR_TIMEOUT) post { listener.onStatus(ReaderStatus.ERROR, "code $code") }
        arm(if (code == ERR_TIMEOUT) 0 else RETRY_MS)
    }

    // The service has no removal event, so poll the slot until it reports the card gone before re-arming;
    // otherwise checkCard would fire again immediately for the same inserted card.
    private fun waitForRemoval() {
        post { listener.onStatus(ReaderStatus.REMOVE_CARD) }
        var waited = 0L
        while (running && waited < REMOVAL_MAX_MS && hw.cardExistStatus(PayHardware.CARD_IC) == CARD_PRESENT) {
            Thread.sleep(REMOVAL_POLL_MS)
            waited += REMOVAL_POLL_MS
        }
        arm()
    }

    // A read detected elsewhere: the background watcher's swipe, or the debug build's synthetic card.
    fun inject(read: CardRead) {
        worker.execute { deliver(read) }
    }

    private fun deliver(read: CardRead) {
        val (entry, decision) = record(store, read)
        post { listener.onRead(entry, decision) }
    }

    private fun post(block: () -> Unit) {
        main.post { if (running) block() }
    }

    companion object {
        // The debug build's QA receiver feeds synthetic reads into the visible screen through this.
        @Volatile var active: ReaderController? = null
            private set

        fun record(store: AppStore, read: CardRead): Pair<LogEntry, Decision> {
            val decision = Access.decide(read, store.members(), store.rules())
            val entry = LogEntry(AppStore.newId(), read, decision.verdict, decision.member?.name, decision.rule?.name)
            store.appendLog(entry)
            return entry to decision
        }

        const val CHECK_TIMEOUT_SEC = 60
        const val CARD_PRESENT = 2
        private const val ERR_TIMEOUT = -30005
        private const val ERR_INVOKING_REPEAT = -20001
        private const val REARM_MS = 1500L
        private const val RETRY_MS = 3000L
        private const val REMOVAL_POLL_MS = 400L
        private const val REMOVAL_MAX_MS = 120_000L
    }
}
