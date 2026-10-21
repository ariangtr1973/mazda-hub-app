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

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { if (inst === this) inst = null; return super.onUnbind(intent) }
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

    /** When the user leaves the hub, tuck away the floating navigation window so it does not cover other apps. */
    fun minimizeFloating(pkg: String) {
        floatLog = "looking for the $pkg window"
        h.postDelayed({ try { tuck(pkg, 0) } catch (e: Exception) { floatLog = "failed: " + e.javaClass.simpleName } }, 700)
    }

    private fun tuck(pkg: String, round: Int) {
        val ws = windows ?: run { floatLog = "no window access"; return }
        val mine = ws.firstOrNull { it.root?.packageName?.toString() == pkg } ?: run { floatLog = "round $round: $pkg window not on screen (already tucked away?)"; return }
        val b = Rect(); mine.getBoundsInScreen(b)
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
                if (hit == null && w.id != mine.id && Regex("(?i)(minimi[sz]e|close|dismiss|pop-?up|window)").containsMatchIn(t)) {
                    val nb = Rect(); n.getBoundsInScreen(nb)
                    if (Rect.intersects(nb, zone)) hit = n
                }
            }
            val pk = (r.packageName?.toString() ?: "?").substringAfterLast('.')
            if (pk != "clockpackage") seen.add(pk + "[" + wb.left + "," + wb.top + "," + wb.right + "," + wb.bottom + "]" + labs.joinToString("|"))
        }
        val node = hit
        if (node != null && clickUp(node)) { floatLog = "round $round tapped " + label(node); return }
        if (round < 2) {
            // Samsung's pop-up handle sits just above the window: tap it to reveal its buttons, then look again
            val path = android.graphics.Path(); path.moveTo(b.centerX().toFloat(), (b.top - 4).toFloat())
            dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
            h.postDelayed({ try { tuck(pkg, round + 1) } catch (e: Exception) { } }, 800)
        } else floatLog = "no minimize button. maps window [" + b.left + "," + b.top + "," + b.right + "," + b.bottom + "]. Windows: " + seen.joinToString(" / ")
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
