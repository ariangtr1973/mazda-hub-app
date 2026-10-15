package com.arian.mazdahub

import android.app.Notification
import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Reads what the music app is playing (title, artist, art, position) and the navigation app's
 * turn notification. Needs "Notification access" turned on once in Android settings.
 */
class NowPlayingService : NotificationListenerService() {

    private var msm: MediaSessionManager? = null
    private var cn: ComponentName? = null
    private var current: MediaController? = null
    private var lastArtKey = ""
    private var artB64 = ""

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { push() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { push() }
        override fun onSessionDestroyed() { pick(msm?.getActiveSessions(cn)) }
    }

    override fun onListenerConnected() {
        inst = this
        cn = ComponentName(this, NowPlayingService::class.java)
        msm = getSystemService(MediaSessionManager::class.java)
        try {
            msm?.addOnActiveSessionsChangedListener(sessionsListener, cn)
            pick(msm?.getActiveSessions(cn))
        } catch (e: SecurityException) { }
        scanNav()
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { }
        current?.unregisterCallback(cb)
        current = null
        if (inst === this) inst = null
    }

    private fun pick(list: List<MediaController>?) {
        val l = list ?: emptyList()
        val want = Ops.prefs(this).getString("music_pkg", "") ?: ""
        val best = l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: l.firstOrNull { it.packageName == want }
            ?: l.firstOrNull()
        if (best?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(cb)
            current = best
            current?.registerCallback(cb)
            lastArtKey = ""
        }
        controller = current
        push()
    }

    private fun push() {
        val c = current
        val o = JSONObject()
        if (c == null) {
            o.put("has", false)
        } else {
            val m = c.metadata
            val st = c.playbackState
            o.put("has", true)
            o.put("pkg", c.packageName)
            o.put("title", m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
            o.put("artist", m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "")
            o.put("album", m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
            o.put("dur", (m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000.0)
            val playing = st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING
            var pos = (st?.position ?: 0L).toDouble()
            if (st != null && playing) pos += (SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed
            o.put("pos", pos / 1000.0)
            o.put("playing", playing)
            val key = o.getString("title") + "|" + o.getString("artist")
            val bmp: Bitmap? = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (bmp != null && (key != lastArtKey || artB64.isEmpty())) {
                artB64 = encode(bmp)
                lastArtKey = key
            } else if (bmp == null && key != lastArtKey) {
                artB64 = ""; lastArtKey = key
            }
            o.put("art", artB64)
        }
        lastNow = o.toString()
        MainActivity.live?.pushJs("hub.setNow(" + lastNow + ")")
    }

    private fun encode(b: Bitmap): String = try {
        val s = 320
        val r = Bitmap.createScaledBitmap(b, s, (s * b.height.toFloat() / b.width).toInt().coerceAtLeast(1), true)
        val bo = ByteArrayOutputStream()
        r.compress(Bitmap.CompressFormat.JPEG, 82, bo)
        "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) { "" }

    // ---- navigation notification (turn, distance, ETA) ----
    private val navPkgs = setOf("com.google.android.apps.maps", "com.waze", "com.here.app.maps", "net.osmand", "net.osmand.plus", "app.organicmaps", "com.mapswithme.maps.pro")

    private fun isNavNotif(sbn: StatusBarNotification): Boolean =
        sbn.packageName in navPkgs && (sbn.isOngoing || sbn.notification.category == Notification.CATEGORY_NAVIGATION)

    private fun scanNav() {
        var found: StatusBarNotification? = null
        try { found = activeNotifications?.firstOrNull { isNavNotif(it) } } catch (e: Exception) { }
        navFrom(found)
    }

    private fun collect(v: View, out: MutableList<String>) {
        if (v is TextView) { val t = v.text?.toString()?.trim(); if (!t.isNullOrEmpty()) out.add(t) }
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    /** Navigation apps often draw their notification with a custom layout; read the text out of it. */
    @Suppress("DEPRECATION")
    private fun remoteTexts(n: Notification): List<String> {
        val out = ArrayList<String>()
        for (rv in listOfNotNull(n.bigContentView, n.contentView)) {
            try { collect(rv.apply(applicationContext, FrameLayout(this)), out) } catch (e: Exception) { }
            if (out.isNotEmpty()) break
        }
        return out.distinct()
    }

    private fun navFrom(sbn: StatusBarNotification?) {
        val o = JSONObject()
        if (sbn == null) o.put("has", false)
        else {
            val n = sbn.notification
            val ex = n.extras
            var title = (ex.getCharSequence("android.title") ?: "").toString()
            var text = (ex.getCharSequence("android.text") ?: "").toString()
            val sub = (ex.getCharSequence("android.subText") ?: "").toString()
            if (title.isBlank() || text.isBlank()) {
                val rt = remoteTexts(n)
                if (title.isBlank() && rt.isNotEmpty()) title = rt[0]
                if (text.isBlank() && rt.size > 1) text = rt.drop(1).take(3).joinToString(" \u00b7 ")
            }
            if (title.isBlank()) title = "Navigating"
            o.put("has", true)
            o.put("pkg", sbn.packageName)
            o.put("title", title)
            o.put("text", text)
            o.put("sub", sub)
        }
        lastNav = o.toString()
        MainActivity.live?.pushJs("hub.setNav(" + lastNav + ")")
    }

    private fun note(sbn: StatusBarNotification) {
        val n = sbn.notification
        val line = sbn.packageName.substringAfterLast('.') + " ongoing=" + sbn.isOngoing + " cat=" + n.category +
            " title=" + (n.extras.getCharSequence("android.title") ?: "") + " rv=" + (n.bigContentView != null || n.contentView != null)
        dbg = (listOf(line) + dbg).take(4)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in navPkgs) { note(sbn); if (isNavNotif(sbn)) navFrom(sbn) }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (sbn.packageName in navPkgs) scanNav() }

    companion object {
        @Volatile var inst: NowPlayingService? = null
        @Volatile var controller: MediaController? = null
        @Volatile var lastNow: String = "{\"has\":false}"
        @Volatile var dbg: List<String> = emptyList()
        @Volatile var lastNav: String = "{\"has\":false}"
    }
}
