package com.toss.cardapp.core

import android.content.Context
import com.toss.cardreader.CardReaderApi
import com.toss.cardreader.CardReaderClient
import com.toss.cardreader.reader.LocalCardReader

/**
 * Picks the card reader for this app: the Toss Card Reader Service when it is installed, otherwise the app's own
 * driver (stock Sunmi payment service when installed, else the direct link over /dev/ttyS3).
 */
object Readers {
    fun open(context: Context): CardReaderApi =
        if (CardReaderClient.installed(context)) CardReaderClient(context) else LocalCardReader(context)
}
