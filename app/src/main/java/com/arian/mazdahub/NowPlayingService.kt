package com.arian.mazdahub

import android.content.ComponentName
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

    private fun scanNav() {
        var found: StatusBarNotification? = null
        try { found = activeNotifications?.firstOrNull { it.packageName in navPkgs && it.isOngoing } } catch (e: Exception) { }
        navFrom(found)
    }

    private fun navFrom(sbn: StatusBarNotification?) {
        val o = JSONObject()
        if (sbn == null) o.put("has", false)
        else {
            val ex = sbn.notification.extras
            o.put("has", true)
            o.put("pkg", sbn.packageName)
            o.put("title", (ex.getCharSequence("android.title") ?: "").toString())
            o.put("text", (ex.getCharSequence("android.text") ?: "").toString())
            o.put("sub", (ex.getCharSequence("android.subText") ?: "").toString())
        }
        lastNav = o.toString()
        MainActivity.live?.pushJs("hub.setNav(" + lastNav + ")")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) { if (sbn.packageName in navPkgs && sbn.isOngoing) navFrom(sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (sbn.packageName in navPkgs) scanNav() }

    companion object {
        @Volatile var inst: NowPlayingService? = null
        @Volatile var controller: MediaController? = null
        @Volatile var lastNow: String = "{\"has\":false}"
        @Volatile var lastNav: String = "{\"has\":false}"
    }
}
