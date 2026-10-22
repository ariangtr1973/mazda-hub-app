package com.arian.mazdahub

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires when any Bluetooth device connects or disconnects; acts only for the chosen car. */
class CarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val act = intent.action ?: return
        if (act != BluetoothDevice.ACTION_ACL_CONNECTED && act != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val p = Ops.prefs(context)
        @Suppress("DEPRECATION")
        val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val addr = dev?.address ?: "?"
        val car = p.getString("car_addr", "") ?: ""
        val short = if (act == BluetoothDevice.ACTION_ACL_CONNECTED) "connected" else "disconnected"
        if (!p.getBoolean("auto", false)) { Ops.log(context, "Bluetooth $short ($addr) ignored: auto start is off"); return }
        if (car.isEmpty()) { Ops.log(context, "Bluetooth $short ($addr) ignored: no car chosen"); return }
        if (!addr.equals(car, ignoreCase = true)) { Ops.log(context, "Bluetooth $short ($addr) is not your car"); return }
        val now = System.currentTimeMillis()
        synchronized(CarReceiver::class.java) {
            if (act == last && now - lastAt < 8000) return
            last = act; lastAt = now
        }
        Ops.log(context, "Car $short (hub was " + (if (MainActivity.live != null) "already running" else "asleep or closed, woken by Bluetooth") + ")")
        when (act) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> Ops.onCarConnected(context)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> Ops.onCarDisconnected(context)
        }
    }
    companion object { private var last = ""; private var lastAt = 0L }
}
