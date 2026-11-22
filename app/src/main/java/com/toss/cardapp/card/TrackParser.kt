package com.toss.cardapp.card

import com.toss.cardreader.Tracks

object TrackParser {
    /** One MSR read from raw tracks: card number and expiry from track 2 when present, holder from track 1. */
    fun fromTracks(time: Long, t1: String?, t2: String?, t3: String?): CardRead {
        val a = Tracks.track1(t1)
        val b = Tracks.track2(t2)
        return CardRead(
            time = time,
            kind = CardKind.MSR,
            pan = b?.pan ?: a?.pan,
            expiry = b?.expiry ?: a?.expiry,
            holder = a?.holder,
            serviceCode = b?.serviceCode ?: a?.serviceCode,
            track1 = t1?.ifEmpty { null },
            track2 = t2?.ifEmpty { null },
            track3 = t3?.ifEmpty { null },
        )
    }
}
