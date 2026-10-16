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
    }
}
