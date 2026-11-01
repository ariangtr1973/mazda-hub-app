package com.arian.mazdahub

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Shared helpers used by the hub screen and the car Bluetooth receiver. */
object Ops {
    const val PREFS = "hub"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- media keys (play / pause / next / previous go to whatever music app is active) ----
    fun mediaKey(c: Context, code: Int) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Send the Play button straight to one app, even if it is not running (like a headset button). */
    fun mediaKeyTo(c: Context, pkg: String, code: Int) {
        try {
            for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val i = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(a, code))
                c.sendBroadcast(i)
            }
        } catch (e: Exception) { }
    }

    /** The same request Google Assistant sends for "play some music". */
    fun playFromSearch(c: Context, pkg: String): String {
        return try {
            val saved = try { org.json.JSONObject(prefs(c).getString("last_$pkg", "") ?: "") } catch (e: Exception) { null }
            val q = ((saved?.optString("title") ?: "") + " " + (saved?.optString("artist") ?: "")).trim()
            val i = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").setPackage(pkg)
                .putExtra(android.app.SearchManager.QUERY, q).putExtra("android.intent.extra.focus", "vnd.android.cursor.item/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(c.packageManager) == null) "app does not accept it" else { c.startActivity(i); "sent" + (if (q.isEmpty()) " (any music)" else " ($q)") }
        } catch (e: Exception) { "failed: " + e.javaClass.simpleName }
    }

    fun mediaKeyByName(c: Context, name: String) {
        val code = when (name) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        mediaKey(c, code)
    }

    /** Short running log of what the car auto-start did, shown in Settings > Advanced. */
    @Synchronized fun log(c: Context, msg: String) {
        try {
            val t = java.text.SimpleDateFormat("h:mm:ss", java.util.Locale.US).format(java.util.Date())
            val old = (prefs(c).getString("autolog", "") ?: "").split("\n").filter { it.isNotBlank() }
            prefs(c).edit().putString("autolog", (listOf("$t $msg") + old).take(60).joinToString("\n")).apply()
        } catch (e: Exception) { }
    }

    fun musicActive(c: Context): Boolean =
        (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive

    // ---- screen timeout ----
    /** Remember the screen timeout you normally use, so it can be put back when the car turns off. */
    fun rememberTimeout(c: Context) {
        try {
            val cur = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 120000)
            if (cur != 4 * 60 * 60 * 1000 && cur != 15000 && cur >= 30000) prefs(c).edit().putInt("orig_timeout", cur).apply()
        } catch (e: Exception) { }
    }

    /** Put the screen timeout back to what you normally use (2 minutes if it was never recorded). */
    fun restoreTimeout(c: Context) { setScreenTimeout(c, prefs(c).getInt("orig_timeout", 120000)) }

    /** Older versions left the tablet stuck at a 15 second timeout after the car turned off; undo that. */
    fun fixStuckTimeout(c: Context) {
        if (sleeping()) return   // the short timeout is on purpose for the next minute or so after the car turns off
        try {
            if (Settings.System.canWrite(c) && Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 0) == 15000) {
                restoreTimeout(c); log(c, "Screen timeout was stuck at 15 seconds; put back to normal")
            }
        } catch (e: Exception) { }
    }

    fun setScreenTimeout(c: Context, ms: Int) {
        try {
            if (Settings.System.canWrite(c)) {
                Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
            }
        } catch (e: Exception) { /* permission not granted yet */ }
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(c: Context, ms: Long = 15000) {
        try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "mazdahub:wake"
            )
            wl.acquire(ms)
        } catch (e: Exception) { }
    }

    // ---- installed music apps ----
    private val knownWords = listOf(
        "music", "spotify", "pandora", "tidal", "deezer", "soundcloud", "audible", "podcast",
        "radio", "napster", "qobuz", "iheart", "tunein", "audio", "player", "morphe", "revanced"
    )
    private val notMusic = setOf(
        "com.android.chrome", "com.sec.android.app.sbrowser", "com.google.android.googlequicksearchbox",
        "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.android.vending"
    )

    private fun pkgsFor(pm: PackageManager, intent: Intent, services: Boolean): Set<String> = try {
        if (services) pm.queryIntentServices(intent, 0).mapNotNull { it.serviceInfo?.packageName }.toSet()
        else pm.queryBroadcastReceivers(intent, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
    } catch (e: Exception) { emptySet() }

    fun musicApps(c: Context, all: Boolean): JSONArray {
        val pm = c.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val browsers = pkgsFor(pm, Intent("android.media.browse.MediaBrowserService"), true)
        val buttons = pkgsFor(pm, Intent(Intent.ACTION_MEDIA_BUTTON), false)
        val seen = HashSet<String>()
        val out = ArrayList<JSONObject>()
        for (ri in pm.queryIntentActivities(launch, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == c.packageName || !seen.add(pkg)) continue
            val label = ri.loadLabel(pm).toString()
            val lower = (pkg + " " + label).lowercase()
            val musicLike = pkg !in notMusic &&
                (pkg in browsers || pkg in buttons || knownWords.any { lower.contains(it) })
            if (!all && !musicLike) continue
            val o = JSONObject()
            o.put("id", pkg)
            o.put("name", label)
            o.put("icon", iconData(ri.loadIcon(pm)))
            o.put("music", musicLike)
            out.add(o)
        }
        out.sortWith(compareBy({ !it.getBoolean("music") }, { it.getString("name").lowercase() }))
        return JSONArray(out)
    }

    private fun iconData(d: Drawable): String = try {
        val s = 96
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        d.setBounds(0, 0, s, s)
        d.draw(cv)
        val bo = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bo)
        "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) { "" }

    /** Installed apps that can navigate (Google Maps, Waze, ...). */
    fun navApps(c: Context): JSONArray {
        val pm = c.packageManager
        val probes = listOf(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=Rochester")),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=Rochester")),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("waze://?q=Rochester"))
        )
        val seen = HashSet<String>()
        val out = ArrayList<JSONObject>()
        for (pr in probes) for (ri in pm.queryIntentActivities(pr, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == c.packageName || !seen.add(pkg)) continue
            if (pm.getLaunchIntentForPackage(pkg) == null) continue
            out.add(JSONObject().put("id", pkg).put("name", ri.loadLabel(pm).toString()).put("icon", iconData(ri.loadIcon(pm))))
        }
        out.sortBy { when (it.getString("id")) { "com.google.android.apps.maps" -> 0; "com.waze" -> 1; else -> 2 } }
        return JSONArray(out)
    }

    /** Name + icon for just these packages (used by the favorites strip, so the whole app list is not loaded at startup). */
    fun appInfo(c: Context, pkgs: List<String>): JSONArray {
        val pm = c.packageManager
        val out = JSONArray()
        for (pkg in pkgs) {
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                out.put(JSONObject().put("id", pkg).put("name", pm.getApplicationLabel(ai).toString()).put("icon", iconData(pm.getApplicationIcon(ai))))
            } catch (e: Exception) { }
        }
        return out
    }

    fun launchApp(c: Context, pkg: String): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { c.startActivity(i); true } catch (e: Exception) { false }
    }

    /** First installed music app that is not [skip], used when the chosen app is missing or fails. */
    /** Apps that have a media session but are never the car music app. */
    fun ignorable(pkg: String) = pkg.contains("anthropic") || pkg.contains("apps.maps") || pkg.contains("chrome") || pkg.startsWith("com.android.") || pkg.contains("assistant") || pkg.contains("com.google.android.googlequicksearchbox")

    fun fallbackApp(c: Context, skip: String?): String? {
        val arr = musicApps(c, false)
        for (n in 0 until arr.length()) {
            val id = arr.getJSONObject(n).getString("id")
            if (id != skip) return id
        }
        return null
    }

    // ---- safety net: if the car-disconnect message is ever missed, the long screen timeout is undone after 5 hours ----
    private fun resetIntent(c: Context): PendingIntent =
        PendingIntent.getBroadcast(c, 77, Intent(c, ResetReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun scheduleReset(c: Context, afterMs: Long = 5L * 3600 * 1000) {
        try { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + afterMs, resetIntent(c)) } catch (e: Exception) { }
    }

    fun cancelReset(c: Context) {
        try { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(resetIntent(c)) } catch (e: Exception) { }
    }

    // ---- car turned off: let the screen go dark ----
    /** For about two minutes after the car turns off nothing (keep-awake, Maps placing) may keep the screen lit. */
    @Volatile var sleepUntil = 0L
    fun sleeping(): Boolean = android.os.SystemClock.elapsedRealtime() < sleepUntil
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var screenOffRx: android.content.BroadcastReceiver? = null
    private var disconnectAt = 0L

    private fun dropScreenOffRx(c: Context) {
        val rx = screenOffRx ?: return
        screenOffRx = null
        try { c.applicationContext.unregisterReceiver(rx) } catch (e: Exception) { }
    }

    /** Stop the shut-down sequence without touching the timeout (used when the car comes back). */
    fun cancelSleep(c: Context) {
        sleepHandler.removeCallbacksAndMessages(null)
        dropScreenOffRx(c)
        sleepUntil = 0L
    }

    /** Shut-down sequence finished (or gave up): put the normal screen timeout back. */
    private fun endSleep(c: Context, why: String) {
        cancelSleep(c)
        cancelReset(c)
        restoreTimeout(c)
        log(c, why)
    }

    private fun lockScreenNow(c: Context) {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return
        val ok = MixService.inst?.lockScreen()
        log(c, "Screen still on " + ((android.os.SystemClock.elapsedRealtime() - disconnectAt) / 1000) + " s after the car turned off: asking Android to lock it" +
            (when (ok) { null -> " (Accessibility helper is not running, cannot)"; true -> ""; else -> " (Android refused)" }))
    }

    private fun startSleep(c: Context) {
        val app = c.applicationContext
        cancelSleep(app)
        disconnectAt = android.os.SystemClock.elapsedRealtime()
        sleepUntil = disconnectAt + 120_000
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) { endSleep(app, "Screen was already off; timeout put back to normal"); return }
        rememberTimeout(app)
        setScreenTimeout(app, 15000)          // screen goes dark about 15 s after the last touch
        scheduleReset(app, 4 * 60 * 1000L)    // if this app is killed meanwhile, Android puts the normal timeout back
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                endSleep(app, "Screen went off " + ((android.os.SystemClock.elapsedRealtime() - disconnectAt) / 1000) + " s after the car turned off; timeout put back to normal")
            }
        }
        screenOffRx = rx
        try { app.registerReceiver(rx, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)) } catch (e: Exception) { screenOffRx = null }
        sleepHandler.postDelayed({ lockScreenNow(app) }, 20_000)
        sleepHandler.postDelayed({ endSleep(app, "Screen still on 90 s after the car turned off; timeout put back to normal") }, 90_000)
    }

    // ---- car Bluetooth events ----
    fun onCarConnected(c: Context) {
        cancelSleep(c)
        log(c, "Waking screen, opening the hub" + (if (Settings.canDrawOverlays(c)) "" else " (Display over other apps is OFF: Android may block this)"))
        rememberTimeout(c)
        setScreenTimeout(c, 4 * 60 * 60 * 1000)
        scheduleReset(c)
        wakeScreen(c)
        val i = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("auto", true)
        try { c.startActivity(i) } catch (e: Exception) { log(c, "Could not open the hub: " + e.javaClass.simpleName) }
    }

    fun onCarDisconnected(c: Context) {
        log(c, "Pausing music, screen will sleep")
        cancelReset(c)
        startSleep(c)                         // marks the shut-down window first, so keep-awake and Maps placing stand down
        MainActivity.live?.autoStop()
        mediaKey(c, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }
}
