package com.arian.mazdahub

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires when any Bluetooth device connects or disconnects; acts only for the chosen car. */
class CarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Ops.prefs(context)
        if (!p.getBoolean("auto", false)) return
        val car = p.getString("car_addr", "") ?: ""
        if (car.isEmpty()) return
        @Suppress("DEPRECATION")
        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (!dev.address.equals(car, ignoreCase = true)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> Ops.onCarConnected(context)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> Ops.onCarDisconnected(context)
        }
    }
}
