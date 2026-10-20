package com.vowi.vowidroid

import android.content.Context
import android.app.Activity
import android.os.Bundle

/**
 * Android starts this when a USB SIM reader is plugged in and the user picks
 * VowiDroid for it, which also grants VowiDroid the reader. It only asks the core to
 * look for cards again; there is nothing to show.
 */
class UsbReaderActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CoreService.start(this, CoreService.ACTION_RESCAN)
        finish()
    }
}
