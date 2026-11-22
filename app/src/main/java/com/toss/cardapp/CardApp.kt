package com.toss.cardapp

import android.app.Application
import com.toss.cardapp.core.AppForeground

class CardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppForeground.install(this)
    }
}
