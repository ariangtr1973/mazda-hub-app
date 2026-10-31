package com.arian.mazdahub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires 5 hours after the car connected: puts the screen timeout back to normal in case the disconnect message never came. */
class ResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Ops.restoreTimeout(context)
        Ops.log(context, "Safety reset: screen timeout put back to normal")
    }
}
