package com.arian.mazdahub

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

    fun musicActive(c: Context): Boolean =
        (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive

    // ---- screen timeout ----
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

    fun launchApp(c: Context, pkg: String): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { c.startActivity(i); true } catch (e: Exception) { false }
    }

    /** First installed music app that is not [skip], used when the chosen app is missing or fails. */
    fun fallbackApp(c: Context, skip: String?): String? {
        val arr = musicApps(c, false)
        for (n in 0 until arr.length()) {
            val id = arr.getJSONObject(n).getString("id")
            if (id != skip) return id
        }
        return null
    }

    // ---- car Bluetooth events ----
    fun onCarConnected(c: Context) {
        setScreenTimeout(c, 4 * 60 * 60 * 1000)
        wakeScreen(c)
        val i = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("auto", true)
        try { c.startActivity(i) } catch (e: Exception) { }
    }

    fun onCarDisconnected(c: Context) {
        MainActivity.live?.autoStop()
        mediaKey(c, KeyEvent.KEYCODE_MEDIA_PAUSE)
        setScreenTimeout(c, 15000)
    }
}
