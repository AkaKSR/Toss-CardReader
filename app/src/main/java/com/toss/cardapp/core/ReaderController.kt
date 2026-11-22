package com.toss.cardapp.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.toss.cardapp.card.CardKind
import com.toss.cardapp.card.CardRead
import com.toss.cardapp.card.EmvField
import com.toss.cardapp.card.TrackParser
import com.toss.cardapp.store.AppStore
import com.toss.cardapp.store.LogEntry
import com.toss.cardreader.CardReaderApi
import com.toss.cardreader.CardReaderError
import com.toss.cardreader.CardReaderException
import com.toss.cardreader.CardTypes
import com.toss.cardreader.IcCardData
import com.toss.cardreader.ServiceStatus
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class ReaderStatus { CONNECTING, WAITING, READING, REMOVE_CARD, ERROR, SERVICE_MISSING }

/**
 * Card-reading loop shared by the normal and kiosk screens: wait for IC | MSR through [Readers], read the card,
 * write the encrypted log entry, decide member/rule, then re-arm. Card data never goes to logcat.
 */
class ReaderController(context: Context, private val listener: Listener) : CardReaderApi.ConnectionListener {

    interface Listener {
        fun onStatus(status: ReaderStatus, detail: String? = null)
        fun onRead(entry: LogEntry, decision: Decision)
    }

    private val app = context.applicationContext
    private val reader = Readers.open(app)
    private val store = AppStore.get(app)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private val session = AtomicInteger(0)
    private val armed = AtomicBoolean(false)

    fun start() {
        running = true
        active = this
        listener.onStatus(ReaderStatus.CONNECTING)
        if (!reader.connect(this)) listener.onStatus(ReaderStatus.SERVICE_MISSING)
    }

    fun stop() {
        running = false
        if (active === this) active = null
        main.removeCallbacksAndMessages(null)
        session.incrementAndGet()
        // A failed cancel means the reader is already gone; disconnecting covers the rest.
        if (armed.getAndSet(false)) try { reader.cancel() } catch (_: CardReaderException) {}
        reader.disconnect()
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    override fun onConnected() = arm()

    override fun onDisconnected() {
        if (running) post { listener.onStatus(ReaderStatus.CONNECTING) }
    }

    // The reader allows one card wait at a time (BUSY for another app, a replacement for ours). Exactly one wait is
    // outstanding per session id, and only the first callback of the current session is handled.
    private fun arm(delayMs: Long = 0) {
        main.postDelayed({
            if (!running || !reader.connected || !armed.compareAndSet(false, true)) return@postDelayed
            val id = session.incrementAndGet()
            worker.execute {
                try {
                    if (SlotGate.mustWaitForRemoval(icPresent())) {
                        armed.set(false)
                        waitForRemoval()
                        return@execute
                    }
                    reader.waitForCard(CardTypes.IC or CardTypes.MSR, CHECK_TIMEOUT_SEC, Session(id))
                    post { listener.onStatus(ReaderStatus.WAITING) }
                } catch (e: CardReaderException) {
                    armed.set(false)
                    if (e.code == CardReaderError.NOT_READY) post { listener.onStatus(ReaderStatus.CONNECTING) }
                    else post { listener.onStatus(ReaderStatus.ERROR, CardReaderError.name(e.code)) }
                    arm(RETRY_MS)
                }
            }
        }, delayMs)
    }

    private inner class Session(private val id: Int) : CardReaderApi.CardListener {
        private fun claim(): Boolean = session.get() == id && armed.compareAndSet(true, false)

        override fun onCardDetected(type: Int) {
            if (!claim()) return
            if (type == CardTypes.IC) handleIc() else handleMag()
        }

        override fun onError(code: Int, message: String?) {
            if (claim()) handleError(code)
        }
    }

    private fun handleMag() {
        worker.execute {
            try {
                val t = reader.readMsr()
                deliver(TrackParser.fromTracks(System.currentTimeMillis(), t.track1, t.track2, t.track3))
                arm(REARM_MS)
            } catch (e: CardReaderException) {
                post { listener.onStatus(ReaderStatus.ERROR, CardReaderError.name(e.code)) }
                arm(RETRY_MS)
            }
        }
    }

    private fun handleIc() {
        worker.execute {
            post { listener.onStatus(ReaderStatus.READING) }
            val ic = try {
                reader.readIc()
            } catch (e: CardReaderException) {
                IcCardData(atr = null, note = "IC read failed: ${CardReaderError.name(e.code)}")
            }
            SlotGate.markRead()
            deliver(
                CardRead(
                    time = System.currentTimeMillis(), kind = CardKind.IC, atr = ic.atr,
                    pan = ic.pan, expiry = ic.expiry, holder = ic.holder, serviceCode = ic.serviceCode,
                    aid = ic.aid, appLabel = ic.appLabel, emvFields = ic.fields.map { EmvField(it.tag, it.name, it.value) },
                    note = ic.note,
                )
            )
            waitForRemoval()
        }
    }

    private fun handleError(code: Int) {
        if (code != CardReaderError.TIMEOUT) post { listener.onStatus(ReaderStatus.ERROR, CardReaderError.name(code)) }
        arm(if (code == CardReaderError.TIMEOUT) 0 else RETRY_MS)
    }

    private fun icPresent(): Boolean = reader.status().icCard == ServiceStatus.CARD_PRESENT

    // The reader has no removal event, so poll the slot until it reports the card gone before re-arming;
    // otherwise the wait would fire again immediately for the same inserted card. A failed query ends the wait.
    private fun waitForRemoval() {
        post { listener.onStatus(ReaderStatus.REMOVE_CARD) }
        var waited = 0L
        while (running && waited < REMOVAL_MAX_MS && runCatching { icPresent() }.getOrDefault(false)) {
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
        private const val REARM_MS = 1500L
        private const val RETRY_MS = 3000L
        private const val REMOVAL_POLL_MS = 400L
        private const val REMOVAL_MAX_MS = 120_000L
    }
}
