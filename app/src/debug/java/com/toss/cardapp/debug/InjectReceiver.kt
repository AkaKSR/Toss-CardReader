package com.toss.cardapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.toss.cardapp.card.CardKind
import com.toss.cardapp.card.CardRead
import com.toss.cardapp.card.TrackParser
import com.toss.cardapp.core.ReaderController
import com.toss.cardapp.service.CardWatchService

// Debug build only: lets QA push a synthetic card through the same log/member/rule path as a real read.
// adb shell am broadcast -n com.toss.cardapp.debug/com.toss.cardapp.debug.InjectReceiver --es kind msr --es track2 '<SYNTHETIC_TRACK2>'
// Add --es target service to run it through the background watcher's detection hand-over instead (app closed);
// with kind ic the watcher only opens the screen, as for a real insert.
class InjectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("target") == "service") {
            val read = if (intent.getStringExtra("kind") == "ic") null else TrackParser.fromTracks(
                System.currentTimeMillis(), intent.getStringExtra("track1"), intent.getStringExtra("track2"), intent.getStringExtra("track3"),
            ).copy(note = "debug inject")
            resultCode = if (CardWatchService.simulateDetection(read)) 1 else 0
            return
        }
        val now = System.currentTimeMillis()
        val read = if (intent.getStringExtra("kind") == "ic") {
            CardRead(
                time = now, kind = CardKind.IC, atr = intent.getStringExtra("atr"), pan = intent.getStringExtra("pan"),
                expiry = intent.getStringExtra("expiry"), holder = intent.getStringExtra("holder"),
                aid = intent.getStringExtra("aid"), appLabel = intent.getStringExtra("label"), note = "debug inject",
            )
        } else {
            TrackParser.fromTracks(now, intent.getStringExtra("track1"), intent.getStringExtra("track2"), intent.getStringExtra("track3"))
                .copy(note = "debug inject")
        }
        val controller = ReaderController.active
        resultCode = if (controller != null) { controller.inject(read); 1 } else 0
    }
}
