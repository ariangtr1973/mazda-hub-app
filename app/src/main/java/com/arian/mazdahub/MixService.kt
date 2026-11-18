package com.arian.mazdahub

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Does what you would do by hand: opens the player's menu in the music app and taps "Start mix".
 * It only acts for a few seconds after you tap Start mix in the hub.
 */
class MixService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var phase = 0          // 0 idle, 1 look for menu button, 2 menu opened, look for Start mix
    private var pkg = ""
    private var deadline = 0L
    private var menuAt = 0L
    private var menuTry = 0

    // v0.85: write every connect / disconnect of this helper to the hub log, to find out why it keeps going off
    override fun onServiceConnected() { inst = this; Ops.log(this, "[helper] connected by Android") }
    override fun onUnbind(intent: Intent?): Boolean { if (inst === this) inst = null; Ops.log(this, "[helper] disconnected by Android"); return super.onUnbind(intent) }
    override fun onDestroy() { if (inst === this) inst = null; Ops.log(this, "[helper] stopped"); super.onDestroy() }
    override fun onInterrupt() { }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    fun begin(targetPkg: String) {
        pkg = targetPkg; phase = 1; menuTry = 0
        deadline = SystemClock.elapsedRealtime() + 18_000
        status = "waiting for $targetPkg to come forward"
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ tick() }, 900)
    }

    private fun finish(msg: String, ok: Boolean) {
        phase = 0; status = msg
        MainActivity.live?.pushJs("hub.mixDone(" + ok + "," + org.json.JSONObject.quote(msg) + ")")
        h.postDelayed({
            try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (e: Exception) { }
        }, if (ok) 1300 else 400)
    }

    /** Where the floating window for this app currently is, or null if it is not on screen. */
    fun floatBounds(pkg: String): Rect? = try {
        windows?.filter { it.root?.packageName?.toString() == pkg }?.map { val r = Rect(); it.getBoundsInScreen(r); r }?.maxByOrNull { it.width() * it.height() }
    } catch (e: Exception) { null }

    /** When the user leaves the hub, tuck away the floating navigation window so it does not cover other apps. */
    fun minimizeFloating(pkg: String) {
        floatLog = "looking for the $pkg window"
        h.postDelayed({ try { tuck(pkg, 0) } catch (e: Exception) { floatLog = "failed: " + e.javaClass.simpleName } }, 700)
    }

    private fun tuck(pkg: String, round: Int) {
        val ws = windows ?: run { floatLog = "no window access"; return }
        val mine = ws.filter { it.root?.packageName?.toString() == pkg }.maxByOrNull { val q = Rect(); it.getBoundsInScreen(q); q.width() * q.height() } ?: run { floatLog = "round $round: $pkg window not on screen (already tucked away?)"; return }
        val b = Rect(); mine.getBoundsInScreen(b)
        if (b.width() < 300 || b.height() < 150) { floatLog = "round $round: $pkg is already folded into its bubble"; return }
        val zone = Rect(b.left - 120, b.top - 160, b.right + 120, b.bottom + 60)
        val seen = ArrayList<String>()
        var hit: AccessibilityNodeInfo? = null
        for (w in ws) {
            val r = w.root ?: continue
            val wb = Rect(); w.getBoundsInScreen(wb)
            val labs = ArrayList<String>()
            walk(r) { n ->
                val t = label(n)
                if (t.isNotEmpty() && labs.size < 6) labs.add(t.take(16))
                if (hit == null && w.id != mine.id && Regex("(?i)(minimi[sz]e|close|dismiss|hide|collapse|pop-?up|window)").containsMatchIn(t + "|" + (n.viewIdResourceName ?: ""))) {
                    val nb = Rect(); n.getBoundsInScreen(nb)
                    if (Rect.intersects(nb, zone)) hit = n
                }
            }
            val pk = (r.packageName?.toString() ?: "?").substringAfterLast('.')
            if (pk != "clockpackage") seen.add(pk + "[" + wb.left + "," + wb.top + "," + wb.right + "," + wb.bottom + "]" + labs.joinToString("|"))
        }
        val node = hit
        if (node != null && clickUp(node)) { floatLog = "round $round tapped " + label(node); return }
        if (round < 4) {
            // Samsung's pop-up handle sits just above the window: tap it (at a different height each round) to reveal its buttons, then look again
            val off = intArrayOf(22, 34, 12, 28)[round]
            val path = android.graphics.Path(); path.moveTo(b.centerX().toFloat(), Math.max(4, b.top - off).toFloat())
            dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
            h.postDelayed({ try { tuck(pkg, round + 1) } catch (e: Exception) { } }, 800)
        } else {
            floatLog = "no minimize button. maps window [" + b.left + "," + b.top + "," + b.right + "," + b.bottom + "]. Windows: " + seen.joinToString(" / ") + " | closing it from Recents"
            closeViaRecents(pkg)
        }
    }

    /** Same as pressing the Home button: Samsung folds floating pop-up windows into their bubbles. */
    fun goBack() { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun goHome() { performGlobalAction(GLOBAL_ACTION_HOME) }

    /** Same as pressing the power button: the screen goes dark. Used after the car turns off if the screen stays lit. */
    fun lockScreen(): Boolean = if (android.os.Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false

    /** Last resort, same as doing it by hand: open Recents, swipe the Maps card away, go back to the app you were in. */
    private fun closeViaRecents(pkg: String) {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        h.postDelayed({
            try {
                var card: Rect? = null
                for (w in windows ?: emptyList()) {
                    val r = w.root ?: continue
                    if (r.packageName?.toString() == pkg) continue
                    walk(r) { n ->
                        if (card == null && label(n).contains("maps", true)) {
                            val nb = Rect(); n.getBoundsInScreen(nb)
                            if (nb.width() > 200 && nb.height() > 150) card = nb
                        }
                    }
                }
                val c = card
                if (c == null) { floatLog += " | no Maps card in Recents"; performGlobalAction(GLOBAL_ACTION_BACK); return@postDelayed }
                val path = android.graphics.Path(); path.moveTo(c.centerX().toFloat(), c.centerY().toFloat()); path.lineTo(c.centerX().toFloat(), 0f)
                dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 260)).build(), null, null)
                floatLog += " | swiped Maps away"
                h.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 900)
            } catch (e: Exception) { floatLog += " | recents failed" }
        }, 1500)
    }

    /** What the app shows right now (clickable things only), so we can see what to tap. */
    fun dumpLabels(pkg: String): String {
        val out = ArrayList<String>()
        try {
            for (w in windows ?: return "no windows") {
                val root = w.root ?: continue
                if (root.packageName?.toString() != pkg) continue
                walk(root) { n ->
                    if (out.size < 40 && n.isClickable) { val l = label(n).take(22); if (l.isNotEmpty()) { val b = Rect(); n.getBoundsInScreen(b); out.add(l + "@" + b.centerX() + "," + b.centerY()) } }
                }
            }
        } catch (e: Exception) { }
        return if (out.isEmpty()) "nothing" else out.joinToString(" | ")
    }

    private var seededAt = 0L
    /** Taps what you would tap by hand: "Try again" on a loading error; if the player says "Nothing is playing", starts your first Speed dial tile; otherwise its Play button. Returns what was tapped. */
    fun tapPlay(pkg: String): String {
        var tapped = ""
        try {
            val roots = ArrayList<AccessibilityNodeInfo>()
            for (w in windows ?: return "") { val r = w.root ?: continue; if (r.packageName?.toString() == pkg) roots.add(r) }
            var nothing = false
            for (r in roots) walk(r) { n -> if (label(n).contains("nothing is playing", true)) nothing = true }
            val fresh = SystemClock.elapsedRealtime() - seededAt < 25_000
            for (pass in 0..2) {
                if (tapped.isNotEmpty()) break
                if (pass == 1 && !(nothing && !fresh)) continue
                for (r in roots) {
                    walk(r) { n ->
                        if (tapped.isEmpty()) {
                            val l = label(n).lowercase().trim('|', ' ')
                            val nb = Rect(); n.getBoundsInScreen(nb)
                            val hit = when (pass) {
                                0 -> l == "try again"
                                1 -> l.startsWith("liked music") && nb.centerY() > 200
                                else -> l == "play" || l == "play video" || l == "resume" || l == "play|play" || l.startsWith("play song") ||
                                    (fresh && (l == "play all" || l == "shuffle play" || l == "shuffle all" || l == "shuffle"))
                            }
                            if (hit && (pass != 2 || fresh || nb.centerY() > 480) && clickUp(n)) { tapped = l; if (pass == 1) seededAt = SystemClock.elapsedRealtime() }
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        return tapped
    }

    // ---- v0.82: "Shuffle Liked music" button ----
    private var shufOn = false
    private var shufDeadline = 0L
    /** The hub has opened the Liked music playlist in [target]: find its Shuffle button and tap it, then go back to the hub. */
    fun shuffleLiked(target: String) {
        phase = 0                       // stop a Start mix that may still be looking for its menu
        pkg = target; shufOn = true; shufPlayAt = 0L; shufMenuTries = 0; shufMenuAt = 0L
        shufDeadline = SystemClock.elapsedRealtime() + 20_000
        status = "opening Liked music in $target"
        h.postDelayed({ shufTick() }, 1500)
    }
    private fun shufDone(ok: Boolean, msg: String) {
        shufOn = false; status = msg
        Ops.log(this, "Shuffle Liked music: " + msg)
        MainActivity.live?.pushJs("hub.shuffleDone&&hub.shuffleDone(" + ok + "," + org.json.JSONObject.quote(msg) + ")")
        h.postDelayed({
            try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (e: Exception) { }
        }, if (ok) 1500 else 400)
    }
    private var shufPlayAt = 0L     // v0.86: when Play was tapped on Liked Music (0 = not yet)
    private var shufMenuTries = 0   // v0.87: times the playlist's three-dot menu was opened to find "Shuffle play"
    private var shufMenuAt = 0L
    private fun shufTick() {
        if (!shufOn) return
        val now = SystemClock.elapsedRealtime()
        if (shufPlayAt > 0L) { shufAfterPlay(now); return }
        if (now > shufDeadline) {
            // record what was on the screen (labels and internal names), so the right button can be matched next time
            val seen = ArrayList<String>()
            try { for (w in windows ?: emptyList()) { val r = w.root ?: continue; if (r.packageName?.toString() == pkg) walk(r) { n -> val t = label(n).ifEmpty { (n.viewIdResourceName ?: "").substringAfter('/') }; if (t.isNotEmpty() && seen.size < 60) seen.add(t.take(28)) } } } catch (e: Exception) { }
            Ops.log(this, "Shuffle Liked music: " + status + ". Saw: " + (if (seen.isEmpty()) "no " + pkg + " window" else seen.joinToString(" / ")))
            shufDone(false, "Liked music opened, but neither Shuffle nor Play could be tapped. Tap Shuffle there."); return
        }
        try {
            val roots = ArrayList<AccessibilityNodeInfo>()
            for (w in windows ?: emptyList()) { val r = w.root ?: continue; if (r.packageName?.toString() == pkg) roots.add(r) }
            var onLiked = false
            var shuf: AccessibilityNodeInfo? = null
            var play: AccessibilityNodeInfo? = null
            val menus = ArrayList<AccessibilityNodeInfo>()
            for (r in roots) walk(r) { n ->
                val parts = label(n).lowercase().split('|').map { it.trim() }
                val vid = (n.viewIdResourceName ?: "").lowercase()
                if (parts.any { it.startsWith("liked music") || it.startsWith("your likes") }) onLiked = true
                if (shuf == null && (parts.any { it == "shuffle" || it == "shuffle play" || it == "shuffle all" || it == "shuffle playlist" } || (vid.contains("shuffle") && !vid.contains("toggle")))) shuf = n
                if (play == null && parts.any { it == "play" || it == "play all" }) play = n
                if (parts.any { it == "more options" || it == "more actions" || it == "action menu" || it == "menu" || it == "more" }) { val rb = Rect(); n.getBoundsInScreen(rb); if (rb.width() > 0) menus.add(n) }
            }
            val s = shuf; val p = play
            if (s != null && (onLiked || shufMenuAt > 0L) && clickUp(s)) { shufDone(true, "Shuffling Liked music"); return }
            // v0.87: "Shuffle play" lives in the playlist's three-dot menu, next to the Play button. Open that menu (not a song's menu).
            if (onLiked && shufMenuTries < 3 && now - shufMenuAt > 2500 && menus.isNotEmpty()) {
                val pb = Rect(); p0(play)?.getBoundsInScreen(pb)
                val pick = if (play != null) menus.minByOrNull { val q = Rect(); it.getBoundsInScreen(q); Math.abs(q.centerY() - pb.centerY()) }
                           else menus.minByOrNull { val q = Rect(); it.getBoundsInScreen(q); q.top }
                if (pick != null && clickUp(pick)) { shufMenuTries++; shufMenuAt = now; status = "opened the playlist menu, looking for Shuffle play"; h.postDelayed({ shufTick() }, 900); return }
            }
            // v0.86: Morphe's Liked Music page has Play but no Shuffle button: tap Play, then turn shuffle on in the player
            if (onLiked && p != null && (shufMenuTries >= 3 || (menus.isEmpty() && now > shufDeadline - 12_000)) && clickUp(p)) {
                shufPlayAt = now; status = "tapped Play on Liked Music, turning shuffle on"
                Ops.log(this, "Shuffle Liked music: no Shuffle button on the page, tapped Play")
                h.postDelayed({ shufTick() }, 2500); return
            }
            status = if (roots.isEmpty()) "waiting for $pkg to come forward" else if (!onLiked) "waiting for the Liked music page" else "looking for the Shuffle or Play button"
        } catch (e: Exception) { }
        h.postDelayed({ shufTick() }, 600)
    }

    /** After Play: switch the player's shuffle on, through the app's own player controls, or by tapping its Shuffle switch on screen. */
    private fun p0(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? = n
    private fun shufAfterPlay(now: Long) {
        try {
            val c = NowPlayingService.controller
            if (c != null && c.packageName == pkg) {
                val a = c.playbackState?.customActions?.firstOrNull { (it.action + " " + it.name).lowercase().contains("shuffle") }
                if (a != null) {
                    c.transportControls.sendCustomAction(a, null)
                    shufPlayAt = 0L; shufDone(true, "Playing Liked music, shuffle switched (" + a.name + ")"); return
                }
            }
            // on-screen shuffle switch in the player, only if it says it is off
            var sw: AccessibilityNodeInfo? = null
            for (w in windows ?: emptyList()) { val r = w.root ?: continue; if (r.packageName?.toString() != pkg) continue
                walk(r) { n -> val l = label(n).lowercase(); if (sw == null && l.contains("shuffle") && (l.contains("off") || l.contains("disabled") || (n.isCheckable && !n.isChecked))) sw = n } }
            val x = sw
            if (x != null && clickUp(x)) { shufPlayAt = 0L; shufDone(true, "Playing Liked music, shuffle turned on"); return }
        } catch (e: Exception) { }
        if (now - shufPlayAt > 8000) { shufPlayAt = 0L; shufDone(true, "Playing Liked music (the app did not offer a shuffle switch)"); return }
        h.postDelayed({ shufTick() }, 700)
    }

    private fun walk(n: AccessibilityNodeInfo?, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null) return
        f(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), f)
    }

    private fun clickUp(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n; var d = 0
        while (x != null && d < 6) { if (x.isClickable && x.isEnabled) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK); x = x.parent; d++ }
        return false
    }

    private fun label(n: AccessibilityNodeInfo): String = ((n.text?.toString() ?: "") + "|" + (n.contentDescription?.toString() ?: "")).trim('|')

    private fun tick() {
        if (phase == 0) return
        if (SystemClock.elapsedRealtime() > deadline) { finish("Timed out. $status", false); return }
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != pkg) { h.postDelayed({ tick() }, 500); return }

        // Is the menu with "Start mix" showing?
        var startNode: AccessibilityNodeInfo? = null
        val seen = ArrayList<String>()
        val menuCands = ArrayList<AccessibilityNodeInfo>()
        val mini = ArrayList<AccessibilityNodeInfo>()
        val screen = Rect(); root.getBoundsInScreen(screen)
        val topLimit = screen.top + (screen.height() * 0.30).toInt()
        walk(root) { n ->
            val t = label(n)
            if (t.isNotEmpty() && seen.size < 40) seen.add(t.take(24))
            if (Regex("(?i)^(start (mix|radio))(\\|.*)?$").matches(t) || Regex("(?i)^.*\\|(start (mix|radio))$").matches(t)) { if (startNode == null) startNode = n }
            val r = Rect(); n.getBoundsInScreen(r)
            if (Regex("(?i)(more options|more actions|action menu|^more$|^menu$|more\\|)").containsMatchIn(t) && r.top < topLimit && r.width() > 0) menuCands.add(n)
            val id = n.viewIdResourceName ?: ""
            if ((id.contains("mini_player", true) || id.contains("miniplayer", true) || Regex("(?i)(expand|maximize|open player)").containsMatchIn(t)) && r.width() > 0) mini.add(n)
        }
        val sn = startNode
        if (sn != null) {
            if (clickUp(sn)) { finish("Tapped Start mix in the music app.", true); return }
        }
        if (phase == 1 || SystemClock.elapsedRealtime() - menuAt > 1800) {
            if (menuCands.isNotEmpty()) {
                val c = menuCands[menuTry % menuCands.size]; menuTry++
                if (clickUp(c)) { phase = 2; menuAt = SystemClock.elapsedRealtime(); status = "opened the menu, looking for Start mix" }
            } else if (mini.isNotEmpty() && menuTry < 3) {
                menuTry++; clickUp(mini[0]); status = "expanding the player"
            } else {
                status = "could not find the menu button. Saw: " + seen.joinToString(" / ")
            }
        }
        h.postDelayed({ tick() }, 600)
    }

    companion object {
        @Volatile var inst: MixService? = null
        @Volatile var status: String = "not used yet"
        @Volatile var floatLog: String = "not used yet"
        @Volatile var events: List<String> = emptyList()
    }
}
