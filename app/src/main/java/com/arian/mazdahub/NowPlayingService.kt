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

    private var all: List<MediaController> = emptyList()
    private val watchers = HashMap<String, MediaController.Callback>()
    private val startedAt = HashMap<String, Long>()
    private var forcePkg = ""
    private var lastSave = 0L

    private fun isPlaying(c: MediaController) = c.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun pick(list: List<MediaController>?) {
        val l = list ?: emptyList()
        all = l
        // watch every session so an app that starts playing takes over the hub
        val live = l.map { it.packageName }.toSet()
        watchers.keys.filter { it !in live }.forEach { watchers.remove(it) }
        l.forEach { c ->
            if (!watchers.containsKey(c.packageName)) {
                val w = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (state?.state == PlaybackState.STATE_PLAYING) { startedAt[c.packageName] = SystemClock.elapsedRealtime(); if (c.packageName != current?.packageName) pick(all) }
                    }
                }
                watchers[c.packageName] = w; try { c.registerCallback(w) } catch (e: Exception) { }
            }
        }
        val prefs = Ops.prefs(this)
        val want = prefs.getString("music_pkg", "") ?: ""
        val forced = if (forcePkg.isNotEmpty()) l.firstOrNull { it.packageName == forcePkg } else null
        val best = forced
            ?: l.filter { isPlaying(it) }.maxByOrNull { startedAt[it.packageName] ?: 0L }
            ?: l.firstOrNull { it.packageName == want }
            ?: l.firstOrNull()
        if (best != null && isPlaying(best) && best.packageName != want) prefs.edit().putString("music_pkg", best.packageName).apply()
        if (best?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(cb)
            current = best
            current?.registerCallback(cb)
            lastArtKey = ""
        }
        controller = current
        push()
    }

    /** Makes this app's session the one the hub shows and controls. */
    fun select(pkg: String) { forcePkg = pkg; pick(all.ifEmpty { try { msm?.getActiveSessions(cn) } catch (e: Exception) { null } }) ; forcePkg = "" }

    private fun saveState(c: MediaController, m: MediaMetadata?, st: PlaybackState?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSave < 4000) return
        if (m == null) return
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return
        if (title.isBlank()) return
        lastSave = now
        val o = JSONObject()
        o.put("title", title)
        o.put("artist", m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
        o.put("id", m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "")
        o.put("dur", m.getLong(MediaMetadata.METADATA_KEY_DURATION))
        var p = st?.position ?: 0L
        if (st != null && st.state == PlaybackState.STATE_PLAYING) p += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        o.put("pos", p)
        Ops.prefs(this).edit().putString("last_" + c.packageName, o.toString()).apply()
    }

    /** Resume this app where it left off. Returns false when the app has no session yet (caller should open the app). */
    fun resume(pkg: String): Boolean {
        val sessions = try { msm?.getActiveSessions(cn) } catch (e: Exception) { null } ?: all
        val c = sessions.firstOrNull { it.packageName == pkg } ?: return false
        sessions.filter { it.packageName != pkg && isPlaying(it) }.forEach { try { it.transportControls.pause() } catch (e: Exception) { } }
        select(pkg)
        if (isPlaying(c)) return true
        val t = c.transportControls
        val hasTrack = !(c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "").isBlank()
        val saved = try { JSONObject(Ops.prefs(this).getString("last_$pkg", "") ?: "") } catch (e: Exception) { null }
        val id = saved?.optString("id") ?: ""
        if (hasTrack || id.isEmpty() || ((c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID) == 0L) t.play()
        else {
            t.playFromMediaId(id, null)
            val pos = saved?.optLong("pos") ?: 0L; val dur = saved?.optLong("dur") ?: 0L; val title = saved?.optString("title") ?: ""
            if (dur > 0 && pos > 5000 && pos < dur - 5000) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val cur = controller
                    if (cur != null && cur.packageName == pkg && (cur.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "") == title) cur.transportControls.seekTo(pos)
                }, 2500)
            }
        }
        return true
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
            try { saveState(c, m, st) } catch (e: Exception) { }
            val ri = ratingInfo(c, m, st)
            o.put("liked", ri.liked ?: JSONObject.NULL)
            o.put("canLike", ri.canLike); o.put("canDislike", ri.canDislike)
        }
        lastNow = o.toString()
        MainActivity.live?.pushJs("hub.setNow(" + lastNow + ")")
    }

    class RateInfo(val liked: Int?, val canLike: Boolean, val canDislike: Boolean)

    private fun iconName(pkg: String, id: Int): String = try {
        if (id == 0) "" else createPackageContext(pkg, 0).resources.getResourceEntryName(id)
    } catch (e: Exception) { "" }

    private val upWords = listOf("thumb_up", "thumbs_up", "thumbup", "thumbsup", "like", "love", "heart", "favorite", "favourite")
    private val downWords = listOf("thumb_down", "thumbs_down", "thumbdown", "thumbsdown", "dislike")
    private val onWords = listOf("unlike", "undislike", "remove", "undo", "filled", "selected", "active", "checked", "_on", "_fill", "liked", "added", "saved")

    /** Works out whether the current song is liked / disliked in the music app, from its rating or its thumbs actions. */
    private fun ratingInfo(c: MediaController, m: MediaMetadata?, st: PlaybackState?): RateInfo {
        var liked: Int? = null
        var canUp = false; var canDown = false
        val dbg = ArrayList<String>()
        try {
            val r = m?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
            if (r != null) {
                dbg.add("rating style=" + r.ratingStyle + " rated=" + r.isRated)
                if (r.ratingStyle == android.media.Rating.RATING_THUMB_UP_DOWN) { canUp = true; canDown = true; liked = if (!r.isRated) 0 else if (r.isThumbUp) 1 else -1 }
                else if (r.ratingStyle == android.media.Rating.RATING_HEART) { canUp = true; liked = if (r.hasHeart()) 1 else 0 }
            }
        } catch (e: Exception) { }
        var upOn = false; var downOn = false
        st?.customActions?.forEach { a ->
            val ic = iconName(c.packageName, a.icon)
            val k = (a.action + " " + a.name + " " + ic).lowercase()
            dbg.add(a.action + "/" + a.name + "/" + ic)
            val isDown = downWords.any { k.contains(it) }
            val isUp = !isDown && upWords.any { k.contains(it) }
            val on = onWords.any { k.contains(it) }
            if (isUp) { canUp = true; if (on) upOn = true }
            if (isDown) { canDown = true; if (on) downOn = true }
        }
        if (liked == null && (canUp || canDown)) liked = if (upOn) 1 else if (downOn) -1 else 0
        rateDbg = dbg.joinToString(" ; ")
        return RateInfo(liked, canUp, canDown)
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
        if (sbn.packageName != packageName) {
            val tag = sbn.packageName.substringAfterLast('.') + (if (sbn.isOngoing) "*" else "")
            seen = (listOf(tag) + seen).distinct().take(8)
        }
        if (sbn.packageName in navPkgs) { note(sbn); if (isNavNotif(sbn)) navFrom(sbn) }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (sbn.packageName in navPkgs) scanNav() }

    companion object {
        @Volatile var inst: NowPlayingService? = null
        @Volatile var controller: MediaController? = null
        @Volatile var lastNow: String = "{\"has\":false}"
        @Volatile var dbg: List<String> = emptyList()
        @Volatile var seen: List<String> = emptyList()
        @Volatile var lastNav: String = "{\"has\":false}"
        @Volatile var rateDbg: String = ""
    }
}
