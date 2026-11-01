package com.arian.mazdahub

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.media.audiofx.Visualizer
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * Mazda Hub: shows the bundled hub page full screen and can act as the tablet's home screen.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val assetHost = "appassets.androidplatform.net"
    private val main = Handler(Looper.getMainLooper())
    private var runId = 0
    private var netCb: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadFloat()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(assetHost)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        setContentView(web)
        goImmersive()
        houseHandler.postDelayed(houseTick, 25_000)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        web.addJavascriptInterface(Bridge(), "HubNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url
                if (u.host == "tile.openstreetmap.org") return mapTile(u.toString())
                return loader.shouldInterceptRequest(u)
            }

            // Links in the hub (Open app, etc.) open outside the hub, so a service's own app can take them.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == assetHost) return false
                try {
                    val intent = if (url.scheme == "intent") {
                        Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
                    } else {
                        Intent(Intent.ACTION_VIEW, url)
                    }
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    // No app can open this link
                } catch (e: Exception) {
                    // Ignore malformed links
                }
                return true
            }
        }

        // Lets the hub's "Add songs" button open the file picker.
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }
        }

        if (savedInstanceState == null) {
            web.loadUrl("https://$assetHost/assets/index.html")
        } else {
            web.restoreState(savedInstanceState)
        }
        live = this
        if (intent?.getBooleanExtra("auto", false) == true) autoStart()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("auto", false) == true) autoStart()
        // Home button = same as the X: close any hub screen that is open and put Maps back on its pane
        else if (intent?.hasCategory(Intent.CATEGORY_HOME) == true && SystemClock.elapsedRealtime() > ignoreHomeUntil) {
            ev("Home pressed")
            pushJs("document.querySelectorAll('.picker.open').forEach(function(p){p.classList.remove('open')})")
            if (sheetOpen) { sheetOpen = false; if (tuckedPkg.isEmpty() && floatPkg.isNotEmpty()) tuckedPkg = floatPkg; restoreMaps() }
            else main.postDelayed({ try { recoverMaps() } catch (e: Exception) { } }, 900)
        }
    }

    // ---------------- car auto start: wait for internet, open the music app, press play ----------------
    fun pushJs(code: String) { runOnUiThread { try { web.evaluateJavascript("try{" + code + "}catch(e){}", null) } catch (e: Exception) { } } }

    // ---- live spectrum for the visualizer (needs the microphone permission; nothing is recorded) ----
    private var viz: Visualizer? = null
    private var vizAt = 0L

    private fun startViz() {
        if (viz != null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, w: ByteArray?, r: Int) {}
                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, r: Int) {
                    if (fft == null) return
                    val now = SystemClock.uptimeMillis()
                    if (now - vizAt < 45) return
                    vizAt = now
                    val nb = 48
                    val half = fft.size / 2
                    val sb = StringBuilder("[")
                    var peak = 0f
                    for (i in 0 until nb) {
                        val k0 = Math.pow((i / nb.toDouble()), 2.0) * (half * 0.55)
                        val k1 = Math.pow(((i + 1) / nb.toDouble()), 2.0) * (half * 0.55)
                        var m = 0f
                        var k = Math.max(1, k0.toInt())
                        val kEnd = Math.max(k + 1, k1.toInt())
                        while (k < kEnd && 2 * k + 1 < fft.size) {
                            val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                            m = Math.max(m, Math.sqrt((re * re + im * im).toDouble()).toFloat())
                            k++
                        }
                        val v2 = Math.min(1f, Math.sqrt((m / 128f).toDouble()).toFloat() * 1.1f)
                        if (v2 > peak) peak = v2
                        if (i > 0) sb.append(',')
                        sb.append(String.format(java.util.Locale.US, "%.2f", v2))
                    }
                    sb.append(']')
                    pushJs("hub.setSpectrum(" + sb + ")")
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            v.enabled = true
            viz = v
        } catch (e: Exception) { viz = null }
    }

    // ---- live location for the hub map ----
    private var lm: LocationManager? = null
    private var locOn = false
    private var gpsAt = 0L
    private val locL = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (l.provider == LocationManager.GPS_PROVIDER) gpsAt = SystemClock.elapsedRealtime()
            else if (SystemClock.elapsedRealtime() - gpsAt < 15000) return   // GPS fix is fresher and better
            pushLoc(l)
        }
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private fun pushLoc(l: Location) {
        pushJs("hub.setLoc({lat:" + l.latitude + ",lon:" + l.longitude + ",acc:" + l.accuracy +
            ",bearing:" + (if (l.hasBearing()) l.bearing else -1f) + ",speed:" + (if (l.hasSpeed()) l.speed else 0f) +
            ",prov:'" + l.provider + "'})")
    }

    private fun startLoc() {
        if (locOn || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm = m
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (m.isProviderEnabled(p)) m.requestLocationUpdates(p, 1000L, 0f, locL, Looper.getMainLooper())
            }
            for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                m.getLastKnownLocation(p)?.let { pushLoc(it) }
            }
            locOn = true
        } catch (e: Exception) { locOn = false }
    }

    private fun stopLoc() {
        try { lm?.removeUpdates(locL) } catch (e: Exception) { }
        locOn = false
    }

    private fun stopViz() {
        try { viz?.enabled = false; viz?.release() } catch (e: Exception) { }
        viz = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) { startViz(); pushJs("renderSetup()") }
        if (requestCode == REQ_LOC) { startLoc(); pushJs("renderSetup()") }
    }

    private fun js(code: String) { runOnUiThread { web.evaluateJavascript(code, null) } }
    private fun say(msg: String) { js("window.hub&&hub.autoStatus&&hub.autoStatus(" + JSONObject.quote(msg) + ")") }

    fun autoStart() {
        autoStop(quiet = true)
        val id = ++runId
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        js("window.hub&&hub.setAwake&&hub.setAwake(true)")
        say("Car connected. Waiting for the hotspot\u2026")
        rebindListener()
        Ops.log(this, "Waiting for the hotspot / internet. Now-playing listener " + (if (NowPlayingService.inst != null) "connected" else "NOT connected"))
        // Warm-up: the music app needs about 20 s to get going on this tablet, so if the hotspot is not up yet, open it now instead of after the wait.
        try {
            val cm0 = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val up = cm0.getNetworkCapabilities(cm0.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val w0 = (Ops.prefs(this).getString("music_pkg", "") ?: "").let { if (Ops.ignorable(it)) "" else it }
            if (!up && w0.isNotEmpty() && Ops.launchApp(this, w0)) Ops.log(this, "Hotspot not up yet: opened the music app early so it is ready")
        } catch (e: Exception) { }
        waitOnline(id, 180_000) {
            if (id != runId) return@waitOnline
            say("Starting music\u2026")
            Ops.log(this, "Opening your music app")
            val prefs = Ops.prefs(this)
            val want = (prefs.getString("music_pkg", "") ?: "").let { if (Ops.ignorable(it)) "" else it }
            var used = if (want.isNotEmpty() && Ops.launchApp(this, want)) want else null
            if (used == null) {
                val alt = Ops.fallbackApp(this, want)
                if (alt != null && Ops.launchApp(this, alt)) {
                    used = alt
                    say("Your chosen app would not open. Using another music app.")
                }
            }
            if (used == null) { Ops.log(this, "No music app could be opened"); say("No music app found. Pick one in the music picker."); return@waitOnline }
            Ops.log(this, "Opened " + used + ", starting music in 1 s")
            val delay = 1000L
            // Keep trying for ~60 s: a freshly opened music app needs a while before it has a player to press play on.
            val usedPkg: String = used
            val startedAt = SystemClock.elapsedRealtime()
            var lastTapAt = 0L      // when a button in the music app was last tapped (never tap twice in a row: the second tap would pause it again)
            var busySince = 0L      // when the app began loading / playing without sound yet
            fun attempt(n: Int) {
                if (id != runId) return
                val secs = (SystemClock.elapsedRealtime() - startedAt) / 1000
                if (Ops.musicActive(this)) {
                    bringHubToFront(id); Ops.log(this, "Music is playing (try " + n + ", " + secs + " s after opening). Back to the hub"); say("Playing"); return
                }
                if (n >= 50) {
                    bringHubToFront(id); Ops.log(this, "Music still silent after 50 tries. Players: " + (NowPlayingService.inst?.sessionsInfo() ?: "no listener") + ". Back to the hub"); say("Music app opened. Press play if it is quiet."); return
                }
                val svc = NowPlayingService.inst
                val now = SystemClock.elapsedRealtime()
                var how: String
                if (svc == null) { Ops.mediaKey(this, android.view.KeyEvent.KEYCODE_MEDIA_PLAY); how = "no listener, media key" }
                else {
                    if (n % 6 == 0) Ops.log(this, "Try " + n + " (" + secs + " s): players: " + svc.sessionsInfo())
                    // The app is already loading or starting the song: give it time instead of pressing more buttons (a second tap would pause it).
                    val busy = svc.busy(usedPkg)
                    if (busy) { if (busySince == 0L) busySince = now } else busySince = 0L
                    if (busy && now - busySince < 10_000) {
                        if (n % 4 == 0) Ops.log(this, "Try " + n + ": app is starting the music, waiting")
                        main.postDelayed({ attempt(n + 1) }, 1200); return
                    }
                    // (The player-service wake-up was removed: Morphe refuses that connection.)
                    if (n == 1) { Ops.log(this, "Try " + n + ": asked the app to play music (voice-search request): " + Ops.playFromSearch(this, usedPkg)) }
                    how = when {
                        n == 0 -> if (svc.resume(usedPkg)) "resume" else "no player yet"
                        n == 3 || n == 5 -> if (svc.playSaved(usedPkg)) "play last track by name" else "no player yet"
                        n % 2 == 0 -> {
                            if (now - lastTapAt < 7000) "tap skipped (just tapped)"
                            else {
                                val t = MixService.inst?.tapPlay(usedPkg) ?: ""
                                if (t.isNotEmpty()) lastTapAt = now
                                if (t.isNotEmpty() || n % 8 == 0) Ops.log(this, "Try " + n + ": tapped " + (if (t.isEmpty()) "nothing" else t))
                                "tap"
                            }
                        }
                        else -> if (svc.resume(usedPkg)) "resume" else "no player yet"
                    }
                }
                if (n % 8 == 0) Ops.log(this, "Try " + n + ": " + how)
                main.postDelayed({ attempt(n + 1) }, 1200)
            }
            main.postDelayed({ attempt(0) }, delay)
        }
    }

    private var visible = false
    override fun onResume() { super.onResume(); visible = true; Ops.fixStuckTimeout(this); startViz(); startLoc(); NowPlayingService.inst?.let { pushJs("hub.setNow(" + NowPlayingService.lastNow + ")") } }
    @Volatile var floatPkg = ""
    private var floatRect: android.graphics.Rect? = null
    private val adj = android.graphics.Rect(0, 0, 0, 0)
    private var calib = 0
    private var hubStarted = false
    private val lockHandler = Handler(Looper.getMainLooper())
    private var drift = 0
    private var lastRelaunchAt = 0L
    @Volatile private var paneRect: android.graphics.Rect? = null
    @Volatile private var sheetOpen = false
    private var nudges = 0
    private var gaveUp = false
    private var lastNudgeFrom: android.graphics.Rect? = null
    private val shieldViews = ArrayList<android.view.View>()
    @Volatile private var ringHue = 205
    private var ringView: android.view.View? = null
    @Volatile var calLog = ""

    /** Samsung keeps a handle bar above the window, so the window itself can never start above ~44 px. */
    private fun target(r: android.graphics.Rect): android.graphics.Rect = android.graphics.Rect(r.left + 7, Math.max(r.top, 24) + 2, r.right - 10, r.bottom - 8)
    private fun adjusted(r0: android.graphics.Rect): android.graphics.Rect { val r = target(r0); return android.graphics.Rect(r.left + adj.left, r.top + adj.top, r.right + adj.right, r.bottom + adj.bottom) }

    private fun ringDrawable(): android.graphics.drawable.Drawable {
        val accent = android.graphics.Color.HSVToColor(floatArrayOf(ringHue.toFloat(), 0.72f, 1f))
        val dm = resources.displayMetrics.density
        val outer = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT); setStroke((9 * dm).toInt(), android.graphics.Color.parseColor("#F2070B12")); cornerRadius = 16 * dm }
        val inner = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT); setStroke((3 * dm).toInt().coerceAtLeast(3), accent); cornerRadius = 12 * dm }
        return android.graphics.drawable.LayerDrawable(arrayOf(outer, inner)).also { it.setLayerInset(1, (3 * dm).toInt(), (3 * dm).toInt(), (3 * dm).toInt(), (3 * dm).toInt()) }
    }

    private fun removeShield() {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            for (v in shieldViews) { try { wm.removeView(v) } catch (e: Exception) { } }
        } catch (e: Exception) { }
        shieldViews.clear()
    }

    private fun shieldParams(r: android.graphics.Rect): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(r.width(), r.height(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT)
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.x = r.left; lp.y = r.top
        return lp
    }

    /** Invisible touch shield over the Maps window's edges and its top handle, so it cannot be dragged, resized or maximised by accident. */
    private fun updateShield(win: android.graphics.Rect?) {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (win == null || !hubStarted || sheetOpen || !Settings.canDrawOverlays(this)) { removeShield(); return }
            val d = resources.displayMetrics.density
            val out = (8 * d).toInt(); val inn = (10 * d).toInt(); val top = (20 * d).toInt()
            val rects = listOf(
                android.graphics.Rect(win.left - out, win.top - out, win.right + out, win.top + top),
                android.graphics.Rect(win.left - out, win.bottom - inn, win.right + out, win.bottom + out),
                android.graphics.Rect(win.left - out, win.top + top, win.left + inn, win.bottom - inn),
                android.graphics.Rect(win.right - inn, win.top + top, win.right + out, win.bottom - inn))
            if (shieldViews.size != rects.size) {
                removeShield()
                for (r in rects) {
                    val v = android.view.View(this)
                    v.setOnTouchListener { _, _ -> true }
                    wm.addView(v, shieldParams(r)); shieldViews.add(v)
                }
            } else {
                for (n in rects.indices) wm.updateViewLayout(shieldViews[n], shieldParams(rects[n]))
            }
        } catch (e: Exception) { }
    }

    /** A thin theme-coloured frame drawn over the Maps window's own white edge. Needs Display over other apps. */
    private fun updateRing(win: android.graphics.Rect?) {
        updateShield(win)
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (win == null || !hubStarted || !Settings.canDrawOverlays(this)) { ringView?.let { wm.removeView(it) }; ringView = null; return }
            val grow = (7 * resources.displayMetrics.density).toInt()
            val lp = WindowManager.LayoutParams(win.width() + 2 * grow, win.height() + 2 * grow, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT)
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.x = win.left - grow; lp.y = win.top - grow
            val v = ringView
            if (v == null) { val nv = android.view.View(this); nv.background = ringDrawable(); wm.addView(nv, lp); ringView = nv }
            else { v.background = ringDrawable(); wm.updateViewLayout(v, lp) }
        } catch (e: Exception) { }
    }

    /** Every moment the hub is on screen: draw the frame, and nudge the Maps window onto the map pane exactly. */
    private val lockTick = object : Runnable {
        override fun run() {
            try {
                val pk = floatPkg; val want0 = paneRect ?: floatRect; val want = if (want0 != null) target(want0) else null
                val b0 = if (pk.isNotEmpty()) MixService.inst?.floatBounds(pk) else null
                val b = if (b0 != null && want != null && b0.width() > want.width() * 0.6 && b0.height() > want.height() * 0.6) b0 else null
                updateRing(b)
                if (hubStarted && b != null && want != null) {
                    val dtop = Math.abs(b.top - want.top); val ex = Math.abs(b.left - want.left) + (if (dtop <= 40) 0 else dtop) + Math.abs(b.right - want.right) + Math.abs(b.bottom - want.bottom)
                    calLog = "want [" + want.left + "," + want.top + "," + want.right + "," + want.bottom + "] actual [" + b.left + "," + b.top + "," + b.right + "," + b.bottom + "] adj [" + adj.left + "," + adj.top + "," + adj.right + "," + adj.bottom + "]"
                    if (ex > 10) {
                        if (++drift >= 2 && visible && !sheetOpen && tuckedPkg.isEmpty() && !handingOver()) {
                            drift = 0
                            val nowMs = SystemClock.elapsedRealtime()
                            if (nowMs - lastRelaunchAt > 2500) {
                                if (nudges >= 3) {
                                    if (!gaveUp) { gaveUp = true; adj.set(0, 0, 0, 0); ev("Maps will not move any closer, leaving it at [" + b.left + "," + b.top + "," + b.right + "," + b.bottom + "] (wanted [" + want.left + "," + want.top + "," + want.right + "," + want.bottom + "])") }
                                } else nudgeMaps(pk, b, want0!!)
                            }
                        }
                    } else { drift = 0; if (nudges > 0) { ev("Maps is on its pane, remembering the correction [" + adj.left + "," + adj.top + "," + adj.right + "," + adj.bottom + "]"); saveAdj() }; nudges = 0; gaveUp = false; lastNudgeFrom = null }
                }
            } catch (e: Exception) { }
            lockHandler.postDelayed(this, 600)
        }
    }
    /** Ask Maps for the pane again; when it landed somewhere else, learn the constant difference (window frame) and correct for it. */
    private fun nudgeMaps(pk: String, b: android.graphics.Rect?, want0: android.graphics.Rect) {
        val tg = target(want0)
        if (b != null) {
            val dl = tg.left - b.left; val dt = tg.top - b.top; val dr = tg.right - b.right; val db = tg.bottom - b.bottom
            if (Math.abs(dl) < 45 && Math.abs(dt) < 45 && Math.abs(dr) < 45 && Math.abs(db) < 45) {
                adj.left = (adj.left + dl).coerceIn(-15, 15)
                adj.right = (adj.right + dr).coerceIn(-15, 15); adj.bottom = (adj.bottom + db).coerceIn(-15, 15)
            }
        }
        nudges++; lastNudgeFrom = b?.let { android.graphics.Rect(it) }; lastRelaunchAt = SystemClock.elapsedRealtime()
        relaunchFloat(pk, adjusted(want0), b != null)
    }
    private fun relaunchFloat(pk: String, r: android.graphics.Rect, fresh: Boolean = false) {
        ev("nudging Maps to [" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "], it was at " + (MixService.inst?.floatBounds(pk)?.let { "[" + it.left + "," + it.top + "," + it.right + "," + it.bottom + "]" } ?: "not showing"))
        val i = packageManager.getLaunchIntentForPackage(pk) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); if (fresh) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val o = android.app.ActivityOptions.makeBasic(); o.setLaunchBounds(r)
        try { startActivity(i, o.toBundle()) } catch (e: Exception) { }
    }
    private var tuckedPkg = ""
    /** Leaving the hub: the floating Maps window steps back with it. */
    @Volatile var lastOutLaunch = 0L
    private var lastRestoreAt = 0L
    @Volatile private var ignoreHomeUntil = 0L
    private fun handingOver(): Boolean = folding || SystemClock.elapsedRealtime() - lastOutLaunch < 12000
    private fun ev(msg: String) { Ops.log(this, "[hub] " + msg); MixService.events = (listOf(java.text.SimpleDateFormat("h:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + msg) + MixService.events).take(6) }
    override fun onStop() { super.onStop(); hubStarted = false; paneRect = null; lockHandler.removeCallbacks(lockTick); updateRing(null); val np = floatingNavPkg().ifEmpty { floatPkg }; ev("hub left view" + (if (np.isNotEmpty()) ", tucking Maps" else "")); if (np.isNotEmpty()) { floatPkg = np; tuckedPkg = np; MixService.inst?.minimizeFloating(np) } }
    private fun saveFloat() { try { val r = floatRect ?: return; Ops.prefs(this).edit().putString("float_pkg", floatPkg).putString("float_rect", r.left.toString() + "," + r.top + "," + r.right + "," + r.bottom).apply() } catch (e: Exception) { } }
    private fun saveAdj() { try { Ops.prefs(this).edit().putString("float_adj3", adj.left.toString() + "," + adj.top + "," + adj.right + "," + adj.bottom).apply() } catch (e: Exception) { } }
    private fun loadAdj() { try { val q = (Ops.prefs(this).getString("float_adj3", "") ?: "").split(","); if (q.size == 4) adj.set(q[0].toInt(), q[1].toInt(), q[2].toInt(), q[3].toInt()) } catch (e: Exception) { } }
    private fun loadFloat() { loadAdj(); try {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        if (Ops.prefs(this).getString("log_ver", "") != ver) { Ops.prefs(this).edit().remove("autolog").putString("log_ver", ver).apply(); MixService.events = emptyList() }
    } catch (e: Exception) { }
    try {
        val p = Ops.prefs(this); val pk = p.getString("float_pkg", "") ?: ""; val q = (p.getString("float_rect", "") ?: "").split(",")
        if (pk.isNotEmpty() && q.size == 4) { floatPkg = pk; floatRect = android.graphics.Rect(q[0].toInt(), q[1].toInt(), q[2].toInt(), q[3].toInt()) }
    } catch (e: Exception) { } }

    private var folding = false
    /** Any app the hub opens: first fold the Maps pop-up (what Home does) so it cannot sit on top of that app. */
    override fun startActivity(intent: Intent?, options: Bundle?) {
        val i = intent
        val target = i?.component?.packageName ?: i?.`package`
        val np = if (!folding) floatingNavPkg() else ""
        if (i != null && !folding && np.isNotEmpty() && target != np && target != packageName) {
            folding = true
            floatPkg = np
            lastOutLaunch = SystemClock.elapsedRealtime()
            MixService.inst?.goHome(); ev("Folded Maps, then opening an app")
            main.postDelayed({
                folding = false
                try { super@MainActivity.startActivity(i, options) } catch (e: Exception) { }
                // If Maps is still showing after that, fold it the slower way (find its minimize button).
                main.postDelayed({ if (floatingNavPkg().isNotEmpty()) { ev("Maps still showing, folding it again"); tuckedPkg = np; MixService.inst?.minimizeFloating(np) } }, 2200)
            }, 700)
            return
        }
        super.startActivity(i, options)
    }

    private fun mapsOnTop(): Boolean = try { floatPkg.isNotEmpty() && (MixService.inst?.floatBounds(floatPkg)?.let { it.width() > 300 && it.height() > 150 } == true) } catch (e: Exception) { false }

    /** After an update Android sometimes leaves the now-playing listener unbound; ask it to reconnect. */
    private fun rebindListener() {
        try { if (NowPlayingService.inst == null) android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(this, NowPlayingService::class.java)) } catch (e: Exception) { }
    }
    /** Back in the hub (Home pressed): bring the Maps window back into its pane. */
    override fun onStart() {
        super.onStart()
        rebindListener()
        pushJs("window.hubReportPane && window.hubReportPane()")
        hubStarted = true; lockHandler.removeCallbacks(lockTick); lockHandler.postDelayed(lockTick, 1500)
        val pk = tuckedPkg; val r = paneRect ?: floatRect
        val justLaunched = SystemClock.elapsedRealtime() - lastOutLaunch < 5000
        ev("hub back in view" + (if (pk.isNotEmpty()) (if (justLaunched) ", skipped Maps (app just opened)" else ", bringing Maps back") else ""))
        if (pk.isNotEmpty() && r != null && !justLaunched && !sheetOpen) restoreMaps()
    }

    /** The hub is on screen and in front (Home, or back from Recents) but Maps is not on its pane: bring it back. */
    private fun recoverMaps() {
        val now = SystemClock.elapsedRealtime()
        if (!hubStarted || sheetOpen || handingOver() || floatPkg.isEmpty() || tuckedPkg.isNotEmpty()) return
        if ( now - lastRestoreAt < 4000) return
        if (floatingNavPkg().isNotEmpty()) return
        ev("hub in front but Maps not on its pane, bringing it back")
        tuckedPkg = floatPkg; restoreMaps()
    }

    /** Puts the Maps window back on the map pane (the pane's current position, measured live). */
    private fun restoreMaps() {
        val pk = tuckedPkg; val r = paneRect ?: floatRect
        if (pk.isEmpty() || r == null) return
        tuckedPkg = ""; lastRestoreAt = SystemClock.elapsedRealtime(); nudges = 0; gaveUp = false; lastNudgeFrom = null
        ev("placing Maps on pane [" + adjusted(r).left + "," + adjusted(r).top + "," + adjusted(r).right + "," + adjusted(r).bottom + "]")
        // wait for the page to measure the pane afresh (the saved rectangle can be stale after the hub was closed and reopened)
        main.postDelayed({ try {
            val i = packageManager.getLaunchIntentForPackage(pk)
            val rr = paneRect ?: floatRect ?: r
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); val o = android.app.ActivityOptions.makeBasic(); o.setLaunchBounds(adjusted(rr)); startActivity(i, o.toBundle()) }
        } catch (e: Exception) { } }, if (paneRect == null) 1500L else 500L)
        // Make sure it really landed on the pane: check a few times and put it back if it is off
        for (d in longArrayOf(2200, 3800, 5600, 8000)) main.postDelayed({ try {
            if (!hubStarted || sheetOpen || tuckedPkg.isNotEmpty() || handingOver()) return@postDelayed
            val want = paneRect ?: floatRect ?: return@postDelayed
            val tg = target(want); val b = MixService.inst?.floatBounds(pk)
            val dtp = if (b == null) 0 else Math.abs(b.top - tg.top)
            if (b == null || b.width() < 300 || Math.abs(b.left - tg.left) + (if (dtp <= 40) 0 else dtp) + Math.abs(b.right - tg.right) + Math.abs(b.bottom - tg.bottom) > 10) {
                if (nudges >= 3) return@postDelayed
                ev("Maps not on its pane, putting it back"); nudgeMaps(pk, if (b != null && b.width() > 300) b else null, want)
            }
        } catch (e: Exception) { } }, d)
    }

    /** The navigation app that is floating over the hub right now (the one the hub opened, or Maps/Waze opened by any other way). */
    private fun floatingNavPkg(): String {
        for (p in listOf(floatPkg, "com.google.android.apps.maps", "com.waze")) {
            if (p.isNotEmpty() && (MixService.inst?.floatBounds(p)?.let { it.width() > 300 && it.height() > 150 } == true)) return p
        }
        return ""
    }
    override fun onPause() { visible = false; stopViz(); stopLoc(); super.onPause() }

    /** Tries three ways to get the hub back in front of the music app. */
    private fun bringHubToFront(id: Int) {
        val me = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { startActivity(me) } catch (e: Exception) { }
        main.postDelayed({
            if (id != runId || visible) return@postDelayed
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_WITH_HOME)
            } catch (e: Exception) { }
        }, 1200)
        main.postDelayed({
            if (id != runId || visible) return@postDelayed
            try {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(home)   // the hub is the home app, so this lands on it
            } catch (e: Exception) { }
        }, 2600)
        main.postDelayed({
            if (id != runId || visible) return@postDelayed
            say("Could not return to the hub. Turn on Display over other apps in Setup.")
        }, 4000)
    }

    private fun waitOnline(id: Int, maxMs: Long, then: () -> Unit) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var done = false
        val t0 = SystemClock.elapsedRealtime()
        fun go() {
            if (done) return
            done = true; clearNet()
            val s = (SystemClock.elapsedRealtime() - t0) / 1000
            val online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            Ops.log(this, if (online) "Internet ready after " + s + " s" else "Gave up waiting after " + s + " s, opening music anyway (offline)")
            if (online) main.postDelayed({ if (id == runId) then() }, 2000) else then()
        }
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) { go(); return }
        // Countdown on screen and a progress line in the log while the phone's routine brings the hotspot up.
        val tick = object : Runnable {
            override fun run() {
                if (done || id != runId) return
                val s = (SystemClock.elapsedRealtime() - t0) / 1000
                val wifiOn = try { (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled } catch (e: Exception) { true }
                val c2 = cm.getNetworkCapabilities(cm.activeNetwork)
                val state = if (!wifiOn) "Wi-Fi is OFF on the tablet" else if (c2 == null) "Wi-Fi on, no network yet" else "network found, no internet yet"
                say(if (!wifiOn) "Turn Wi-Fi on. Waiting for the hotspot (" + s + " s)" else "Waiting for the hotspot (" + s + " s)")
                if (s % 15L < 5L) Ops.log(this@MainActivity, "Still waiting at " + s + " s: " + state)
                // Nudge Android to look for the hotspot again (it can sit on an old, failed scan for minutes).
                if (wifiOn && s % 20L < 5L) { try { @Suppress("DEPRECATION") val ok = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).startScan(); if (s % 40L < 5L) Ops.log(this@MainActivity, "Asked Wi-Fi to scan for the hotspot (" + (if (ok) "started" else "Android declined") + ")") } catch (e: Exception) { } }
                main.postDelayed(this, 5000)
            }
        }
        main.postDelayed(tick, 5000)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) main.post { if (id == runId) go() }
            }
        }
        netCb = cb
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
        } catch (e: Exception) { go(); return }
        main.postDelayed({ if (id == runId) go() }, maxMs)   // give up waiting; open the music app anyway
    }

    private fun clearNet() {
        val cb = netCb ?: return
        netCb = null
        try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } catch (e: Exception) { }
    }

    /** Car disconnected: cancel any pending start and let the screen sleep normally. */
    fun autoStop(quiet: Boolean = false) {
        runId++
        main.removeCallbacksAndMessages(null)
        clearNet()
        if (!quiet) {
            runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            js("window.hub&&hub.setAwake&&hub.setAwake(false)")
            say("Car disconnected")
        }
    }

    /** Map tiles: fetched with an identifying User-Agent and kept on disk so the map is light on data and works for places you have seen. */
    private fun mapTile(url: String): WebResourceResponse? {
        return try {
            val dir = java.io.File(cacheDir, "tiles"); dir.mkdirs()
            val file = java.io.File(dir, url.removePrefix("https://tile.openstreetmap.org/").replace('/', '_'))
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 14L * 24 * 3600 * 1000
            if (!fresh) {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.setRequestProperty("User-Agent", "MazdaHub/0.7 (personal Android car dashboard)")
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode == 200) {
                    val tmp = java.io.File(dir, file.name + ".tmp")
                    c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    tmp.renameTo(file)
                } else if (!file.exists()) return null
                c.disconnect()
            }
            WebResourceResponse("image/png", null, file.inputStream())
        } catch (e: Exception) { null }
    }

    // ---- Song mix ----
    @Volatile private var mixing = false
    @Volatile private var resolvedSeed = ""
    @Volatile private var mixLog = "no mix tried yet"
    private val mixPool = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** When the music app does not reveal the video id, look the song up on YouTube search and take the first hit. */
    private fun resolveVideoId(artist: String, title: String): String = try {
        val q = java.net.URLEncoder.encode(("$artist $title").trim(), "UTF-8")
        val c = java.net.URL("https://www.youtube.com/results?search_query=$q").openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; SM-T290) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        c.connectTimeout = 8000; c.readTimeout = 10000
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(body)?.groupValues?.get(1) ?: ""
    } catch (e: Exception) { mixLog = "search failed: " + e.javaClass.simpleName; "" }

    private fun pushMix(on: Boolean) { js("window.hub&&hub.mixState&&hub.mixState(" + on + ")") }

    /** The YouTube video id of the playing song, taken from the session's media id or uri. Empty when unknown. */
    private fun trackId(c: android.media.session.MediaController): String {
        val m = c.metadata ?: return ""
        val plain = Regex("^[A-Za-z0-9_-]{11}$")
        for (x in listOf(m.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID), m.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_URI))) {
            if (x.isNullOrBlank()) continue
            if (plain.matches(x)) return x
            Regex("[?&]v=([A-Za-z0-9_-]{11})").find(x)?.let { return it.groupValues[1] }
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(x)?.let { return it.groupValues[1] }
        }
        return ""
    }

    private fun armMix(seedId: String, artist: String, title: String, seedTitle: String) {
        val c0 = NowPlayingService.controller ?: return
        val t0 = SystemClock.elapsedRealtime()
        mixing = true; pushMix(true)
        resolvedSeed = ""
        if (seedId.isEmpty()) mixPool.execute { val v = resolveVideoId(artist, title); if (v.isNotEmpty()) { resolvedSeed = v; mixLog = "looked up id $v" } }
        val r = object : Runnable {
            override fun run() {
                if (!mixing) return
                val c = NowPlayingService.controller
                if (c == null || SystemClock.elapsedRealtime() - t0 > 25 * 60_000L) { mixing = false; pushMix(false); return }
                val st = c.playbackState
                val dur = c.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                val playing = st?.state == android.media.session.PlaybackState.STATE_PLAYING
                var pos = st?.position ?: 0L
                if (st != null && playing) pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                val idNow = trackId(c)
                val titleNow = c.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
                val changed = if (seedId.isNotEmpty() && idNow.isNotEmpty()) idNow != seedId else (seedTitle.isNotEmpty() && titleNow.isNotEmpty() && titleNow != seedTitle)
                val ending = playing && dur > 0 && dur - pos < 2500
                if (changed || ending) { mixing = false; pushMix(false); fireMix(c, seedId, artist, title); return }
                main.postDelayed(this, 700)
            }
        }
        main.postDelayed(r, 700)
    }

    private fun fireMix(c: android.media.session.MediaController, id0: String, artist: String, title: String) {
        val pkg = c.packageName
        val id = if (id0.isNotEmpty()) id0 else resolvedSeed
        try {
            if (id.isNotEmpty() && pkg.contains("youtube")) {
                val uri = Uri.parse("https://music.youtube.com/watch?v=$id&list=RDAMVM$id")
                val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(flags))
                    mixLog = "opened mix for $id in $pkg"
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(flags))
                    mixLog = "app would not take the link; opened mix for $id with the system chooser"
                }
            } else {
                val q = (if (artist.isNotBlank()) artist else title).trim() + " radio"
                val focus = Bundle().apply { putString(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*") }
                if ((c.playbackState?.actions ?: 0L) and android.media.session.PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L) { c.transportControls.playFromSearch(q, focus); mixLog = "asked $pkg to play \"$q\"" }
                else { startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
                    .putExtra(android.app.SearchManager.QUERY, q).putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); mixLog = "sent play-from-search \"$q\" to $pkg" }
            }
        } catch (e: Exception) {
            mixLog = "failed: " + e.javaClass.simpleName + " " + (e.message ?: "")
            js("window.hub&&hub.autoStatus&&hub.autoStatus(" + JSONObject.quote("Could not start the mix (" + e.javaClass.simpleName + ")") + ")")
        }
        main.postDelayed({ try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (e: Exception) { } }, 3500)
    }

    /** Start the mix right now (long press). */
    private fun mixNow(artist: String, title: String): String {
        val c = NowPlayingService.controller ?: return "none"
        mixing = false; pushMix(false)
        val seed = trackId(c)
        if (seed.isNotEmpty() || !pkgIsYt(c)) { fireMix(c, seed, artist, title); return "now" }
        mixPool.execute { val v = resolveVideoId(artist, title); if (v.isNotEmpty()) resolvedSeed = v; runOnUiThread { fireMix(c, "", artist, title) } }
        return "now"
    }
    private fun pkgIsYt(c: android.media.session.MediaController) = c.packageName.contains("youtube")

    /** Hides the status bar. The Android Back / Home / Recents bar stays visible unless "Full screen" is turned on in Settings. */
    private fun goImmersive() {
        val hideNav = Ops.prefs(this).getBoolean("hide_bars", false)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(!hideNav)
                window.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.statusBars())
                    if (hideNav) it.hide(android.view.WindowInsets.Type.navigationBars()) else it.show(android.view.WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (hideNav) (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                else (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN)
            }
        } catch (e: Exception) { }
    }

    // ---- housekeeping: keeps the hub light between drives ----
    private val houseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cachePool = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var lastClean = "not run yet"

    /** Trims the hub's own cache. [force] empties it; otherwise only files older than a day, or everything if over ~40 MB. */
    fun cleanCaches(force: Boolean) {
        try { if (force) web.clearCache(false) } catch (e: Exception) { }
        cachePool.execute {
            try {
                val files = ArrayList<java.io.File>()
                fun walk(f: java.io.File) { if (f.isDirectory) f.listFiles()?.forEach { walk(it) } else files.add(f) }
                walk(cacheDir); externalCacheDir?.let { walk(it) }
                var total = 0L
                for (f in files) total += f.length()
                val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
                var freed = 0L
                for (f in files.sortedBy { it.lastModified() }) {
                    if (force || total > 40L * 1024 * 1024 || f.lastModified() < cutoff) { freed += f.length(); f.delete() }
                }
                lastClean = "freed " + (freed / 1024) + " KB at " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date())
            } catch (e: Exception) { lastClean = "clean failed" }
        }
    }
    private val houseTick = object : Runnable { override fun run() { cleanCaches(false); houseHandler.postDelayed(this, 6L * 3600 * 1000) } }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { try { web.clearCache(false) } catch (e: Exception) { }; cleanCaches(false) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
        if (hasFocus) main.postDelayed({ try { recoverMaps() } catch (e: Exception) { } }, 1200)
    }

    private fun audio() = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    // ---- Navigation helpers: allowlisted web requests, voice input, spoken guidance ----
    private val netPool = java.util.concurrent.Executors.newFixedThreadPool(2)
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var ttsMuted = false
    private var focusReq: android.media.AudioFocusRequest? = null

    private fun allowedHost(u: String): Boolean = try {
        val x = Uri.parse(u)
        x.scheme == "https" && (x.host == "nominatim.openstreetmap.org" || x.host == "router.project-osrm.org" || x.host == "api.rainviewer.com")
    } catch (e: Exception) { false }

    private fun httpGet(id: Int, url: String) {
        if (!allowedHost(url)) { js("window.hub&&hub.httpDone&&hub.httpDone($id,0,'')"); return }
        netPool.execute {
            var status = 0; var body = ""
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.setRequestProperty("User-Agent", "MazdaHub/0.11 (personal Android car dashboard; ariangtr1973@gmail.com)")
                c.setRequestProperty("Accept-Language", "en")
                c.connectTimeout = 10000; c.readTimeout = 15000
                status = c.responseCode
                val st = if (status in 200..299) c.inputStream else c.errorStream
                body = st?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
            } catch (e: Exception) { status = 0 }
            js("window.hub&&hub.httpDone&&hub.httpDone($id,$status," + JSONObject.quote(body) + ")")
        }
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = android.speech.tts.TextToSpeech(applicationContext) { st ->
            ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS
            if (ttsReady) {
                try {
                    tts?.language = java.util.Locale.US
                    tts?.setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(u: String?) {}
                        override fun onDone(u: String?) { dropFocus() }
                        @Deprecated("Deprecated in Java") override fun onError(u: String?) { dropFocus() }
                    })
                } catch (e: Exception) { }
            }
        }
    }

    private fun takeFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val r = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            focusReq = r; am.requestAudioFocus(r)
        } catch (e: Exception) { }
    }

    private fun dropFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            focusReq?.let { am.abandonAudioFocusRequest(it) }; focusReq = null
        } catch (e: Exception) { }
    }

    private var pendingBackup: String? = null
    private var recognizer: android.speech.SpeechRecognizer? = null

    /** Tells the page exactly why voice did not work, and keeps a line in Settings > Advanced for troubleshooting. */
    private fun voiceErr(code: String) {
        Ops.log(this, "Voice: " + code)
        js("window.hub&&hub.voiceErr&&hub.voiceErr(" + JSONObject.quote(code) + ")")
    }

    /** The Google app provides the tablet's speech recognizer; report whether it is there and turned on. */
    private fun googleSpeechState(): String = try {
        if (packageManager.getApplicationInfo("com.google.android.googlequicksearchbox", 0).enabled) "on" else "DISABLED"
    } catch (e: Exception) { "not installed" }

    private var voiceCands: List<android.content.ComponentName?> = emptyList()
    private var voiceIdx = -1
    private var voiceReady = false
    private var voiceWd: Runnable? = null

    /** Starts listening with the next speech service in the list; if one fails, the next is tried. */
    private fun startNextRecognizer(lastErr: String?) {
        voiceIdx++
        voiceReady = false
        voiceWd?.let { main.removeCallbacks(it) }
        if (voiceIdx >= voiceCands.size) { voiceErr(if (googleSpeechState() != "on") "nogoogle" else (lastErr ?: "e5")); return }
        val comp = voiceCands[voiceIdx]
        Ops.log(this, "Voice: trying " + (comp?.flattenToShortString() ?: "system default"))
        try { recognizer?.destroy() } catch (e: Exception) { }
        val r: android.speech.SpeechRecognizer
        try {
            r = if (comp != null) android.speech.SpeechRecognizer.createSpeechRecognizer(this, comp) else android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) { startNextRecognizer("x:" + e.javaClass.simpleName); return }
        recognizer = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { voiceReady = true; voiceWd?.let { main.removeCallbacks(it) }; js("window.hub&&hub.voiceState&&hub.voiceState()") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) {
                if (recognizer !== r) return
                voiceWd?.let { main.removeCallbacks(it) }
                if (e == 5 || e == 9) startNextRecognizer("e" + e) else voiceErr("e" + e)
            }
            override fun onResults(res: Bundle?) {
                if (recognizer !== r) return
                val said = res?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                Ops.log(this@MainActivity, "Voice heard: " + said)
                if (said.isEmpty()) voiceErr("e7") else js("window.hub&&hub.voiceCmd&&hub.voiceCmd(" + JSONObject.quote(said) + ")")
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        val wd = Runnable { if (recognizer === r && !voiceReady) { Ops.log(this, "Voice: no answer from " + (comp?.packageName ?: "default") + ", trying the next"); startNextRecognizer("e5") } }
        voiceWd = wd
        main.postDelayed(wd, 3500)
        try { r.startListening(i) } catch (e: Exception) { Ops.log(this, "Voice: " + e.javaClass.simpleName + " from " + (comp?.packageName ?: "default")); startNextRecognizer("x:" + e.javaClass.simpleName) }
    }

    /** Listens inside the hub (no pop-up) when the tablet has a speech service and the hub may use the microphone. */
    private fun startCommandListening() {
        val haveService = android.speech.SpeechRecognizer.isRecognitionAvailable(this)
        val legacy = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val haveLegacy = legacy.resolveActivity(packageManager) != null
        Ops.log(this, "Voice: tapped. speech service " + (if (haveService) "yes" else "no") + ", pop-up listener " + (if (haveLegacy) "yes" else "no") +
            ", hub microphone " + (if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "allowed" else "NOT allowed"))
        if (!haveService && !haveLegacy) { voiceErr("nospeech"); return }
        if (haveService) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                voiceErr("permission"); return
            }
            // Try every speech service on the tablet (Google first, the system default last): some tablets refuse the default one.
            val cands = ArrayList<android.content.ComponentName?>()
            try {
                for (ri in packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)) {
                    val si = ri.serviceInfo ?: continue
                    cands.add(android.content.ComponentName(si.packageName, si.name))
                }
            } catch (e: Exception) { }
            cands.sortBy { if (it?.packageName?.contains("google") == true) 0 else 1 }
            cands.add(0, null)
            Ops.log(this, "Voice: Google app is " + googleSpeechState())
            Ops.log(this, "Voice: speech services: " + cands.joinToString(", ") { it?.flattenToShortString() ?: "system default" })
            voiceCands = cands
            voiceIdx = -1
            startNextRecognizer(null)
            return
        }
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a command")
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_CMD)
    }

    /** Everything the hub remembers (app settings plus the page's own saved settings) as one JSON text. */
    private fun buildBackup(local: String): String {
        val root = JSONObject()
        root.put("app", "mazda-hub")
        root.put("version", try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" })
        val prefs = JSONObject()
        for ((key, v) in Ops.prefs(this).all) {
            if (key == "autolog") continue
            val o = JSONObject()
            var ok = true
            when (v) {
                is Boolean -> { o.put("t", "b"); o.put("v", v) }
                is Int -> { o.put("t", "i"); o.put("v", v) }
                is Long -> { o.put("t", "l"); o.put("v", v) }
                is Float -> { o.put("t", "f"); o.put("v", v.toDouble()) }
                is String -> { o.put("t", "s"); o.put("v", v) }
                else -> ok = false
            }
            if (ok) prefs.put(key, o)
        }
        root.put("prefs", prefs)
        root.put("local", try { JSONObject(local) } catch (e: Exception) { JSONObject() })
        return root.toString(2)
    }

    private fun applyBackup(text: String): String {
        val root = JSONObject(text)
        if (root.optString("app") != "mazda-hub") return "That file is not a Mazda Hub backup"
        val ed = Ops.prefs(this).edit()
        var n = 0
        val pr = root.optJSONObject("prefs")
        if (pr != null) {
            val keys = pr.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val o = pr.getJSONObject(key)
                when (o.optString("t")) {
                    "b" -> ed.putBoolean(key, o.getBoolean("v"))
                    "i" -> ed.putInt(key, o.getInt("v"))
                    "l" -> ed.putLong(key, o.getLong("v"))
                    "f" -> ed.putFloat(key, o.getDouble("v").toFloat())
                    "s" -> ed.putString(key, o.getString("v"))
                    else -> n--
                }
                n++
            }
        }
        ed.apply()
        val loc = root.optJSONObject("local")
        js("window.hub&&hub.restoreLocal&&hub.restoreLocal(" + (loc?.toString() ?: "{}") + ")")
        return "Restored " + n + " settings"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_VOICE) {
            val t = if (resultCode == Activity.RESULT_OK)
                data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: "" else ""
            js("window.hub&&hub.voiceResult&&hub.voiceResult(" + JSONObject.quote(t) + ")")
        } else if (requestCode == REQ_CMD) {
            val t = if (resultCode == Activity.RESULT_OK)
                data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: "" else ""
            Ops.log(this, "Voice pop-up result " + resultCode + ": " + t)
            if (t.isEmpty()) voiceErr(if (resultCode == Activity.RESULT_OK) "e7" else "cancelled")
            else js("window.hub&&hub.voiceCmd&&hub.voiceCmd(" + JSONObject.quote(t) + ")")
        } else if (requestCode == REQ_BACKUP) {
            val uri = data?.data
            val text = pendingBackup
            pendingBackup = null
            if (resultCode == Activity.RESULT_OK && uri != null && text != null) {
                Thread {
                    val msg = try {
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(buildBackup(text).toByteArray(Charsets.UTF_8)) }
                        "Settings saved. Keep this file somewhere safe."
                    } catch (e: Exception) { "Backup failed: " + e.javaClass.simpleName }
                    js("window.hub&&hub.backupDone&&hub.backupDone(" + JSONObject.quote(msg) + ")")
                }.start()
            }
        } else if (requestCode == REQ_RESTORE) {
            val uri = data?.data
            if (resultCode == Activity.RESULT_OK && uri != null) {
                Thread {
                    val msg = try {
                        val txt = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                        applyBackup(txt)
                    } catch (e: Exception) { "Restore failed: " + e.javaClass.simpleName }
                    js("window.hub&&hub.backupDone&&hub.backupDone(" + JSONObject.quote(msg) + ")")
                }.start()
            }
        } else if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // As a home screen, the Back button should not close the hub.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Intentionally empty
    }

    override fun onDestroy() {
        try { recognizer?.destroy() } catch (e: Exception) { }
        if (live === this) live = null
        clearNet()
        try { tts?.shutdown() } catch (e: Exception) { }
        netPool.shutdown()
        web.destroy()
        super.onDestroy()
    }

    /** Called from the hub page. */
    inner class Bridge {
        @JavascriptInterface fun http(id: Int, url: String) { httpGet(id, url) }

        /** Media volume as 0..100 */
        @JavascriptInterface fun getVolume(): Int = try {
            val a = audio(); val mx = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (mx <= 0) 0 else Math.round(a.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100f / mx)
        } catch (e: Exception) { 0 }

        @JavascriptInterface fun setVolume(pct: Int): Int {
            try {
                val a = audio(); val mx = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                a.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(pct.coerceIn(0, 100) * mx / 100f), 0)
            } catch (e: Exception) { }
            return getVolume()
        }

        @JavascriptInterface fun stepVolume(dir: Int): Int {
            try {
                audio().adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    if (dir > 0) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, 0)
            } catch (e: Exception) { }
            return getVolume()
        }

        @JavascriptInterface fun toggleMute(): Boolean = try {
            val a = audio(); val m = a.isStreamMute(android.media.AudioManager.STREAM_MUSIC)
            a.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (m) android.media.AudioManager.ADJUST_UNMUTE else android.media.AudioManager.ADJUST_MUTE, 0)
            !m
        } catch (e: Exception) { false }

        @JavascriptInterface fun isMuted(): Boolean = try { audio().isStreamMute(android.media.AudioManager.STREAM_MUSIC) } catch (e: Exception) { false }

        /** Voice commands ("next", "pause", "navigate home" ...): the page decides what to do with the words. */
        @JavascriptInterface fun listenCmd(): Boolean {
            runOnUiThread { try { startCommandListening() } catch (e: Exception) { voiceErr("x:" + e.javaClass.simpleName) } }
            return true
        }

        @JavascriptInterface fun backup(local: String) {
            pendingBackup = local
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").putExtra(Intent.EXTRA_TITLE, "mazda-hub-settings.json")
                    @Suppress("DEPRECATION") startActivityForResult(i, REQ_BACKUP)
                } catch (e: Exception) { js("window.hub&&hub.backupDone&&hub.backupDone('Backup is not available on this tablet')") }
            }
        }

        @JavascriptInterface fun restore() {
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    @Suppress("DEPRECATION") startActivityForResult(i, REQ_RESTORE)
                } catch (e: Exception) { js("window.hub&&hub.backupDone&&hub.backupDone('Restore is not available on this tablet')") }
            }
        }

        @JavascriptInterface fun listen(): Boolean {
            return try {
                val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Where to?")
                runOnUiThread {
                    try { @Suppress("DEPRECATION") startActivityForResult(i, REQ_VOICE) }
                    catch (e: Exception) { js("window.hub&&hub.voiceResult&&hub.voiceResult('')") }
                }
                true
            } catch (e: Exception) { false }
        }

        @JavascriptInterface fun speak(text: String) {
            if (ttsMuted) return
            runOnUiThread {
                ensureTts()
                if (ttsReady) { takeFocus(); tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "nav" + System.nanoTime()) }
            }
        }
        @JavascriptInterface fun stopSpeak() { runOnUiThread { try { tts?.stop() } catch (e: Exception) { }; dropFocus() } }
        @JavascriptInterface fun setMute(m: Boolean) { ttsMuted = m; if (m) stopSpeak() }

        @JavascriptInterface
        fun setKeepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface fun getApps(all: Boolean): String = try { Ops.musicApps(this@MainActivity, all).toString() } catch (e: Exception) { "[]" }

        @JavascriptInterface fun launchApp(pkg: String): Boolean { lastOutLaunch = SystemClock.elapsedRealtime(); return Ops.launchApp(this@MainActivity, pkg) }
        /** Opens an app asking Android to size its window to this box (screen pixels). Honoured only when freeform windows are enabled. */
        @JavascriptInterface fun launchAppIn(pkg: String, l: Int, t: Int, r: Int, b: Int): Boolean {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                val o = android.app.ActivityOptions.makeBasic()
                val want = paneRect ?: android.graphics.Rect(l, t, r, b); if (floatRect != want) { calib = 0 }
                o.setLaunchBounds(adjusted(want))
                startActivity(i, o.toBundle()); floatPkg = pkg; floatRect = want; saveFloat(); true
            } catch (e: Exception) { Ops.launchApp(this@MainActivity, pkg) }
        }
        @JavascriptInterface fun getAppInfo(json: String): String = try {
            val a = JSONArray(json); Ops.appInfo(this@MainActivity, (0 until a.length()).map { a.getString(it) }).toString()
        } catch (e: Exception) { "[]" }
        /** The user picked another music app: make the hub follow it and start it where it left off. */
        @JavascriptInterface fun switchApp(pkg: String) {
            Ops.prefs(this@MainActivity).edit().putString("music_pkg", pkg).apply()
            val svc = NowPlayingService.inst
            if (svc != null && svc.resume(pkg)) { say("Playing " + pkg.substringAfterLast('.')); return }
            if (!Ops.launchApp(this@MainActivity, pkg)) return
            val id = ++runId
            for (d in longArrayOf(2500, 6000)) main.postDelayed({
                if (id != runId || Ops.musicActive(this@MainActivity)) return@postDelayed
                if (NowPlayingService.inst?.resume(pkg) != true) Ops.mediaKey(this@MainActivity, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            }, d)
            main.postDelayed({ if (id == runId) bringHubToFront(id) }, 7500)
        }
        @JavascriptInterface fun getVersion(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
        /** Starts turn-by-turn to a place in the navigation app, in the hub's map pane. */
        /** Spoken destination (address or place name): Maps/Waze find it themselves, which works far better for businesses than our own search. */
        /** The page tells us where the map pane is (in pixels inside the page); we add where the page sits on the screen. */
        @JavascriptInterface fun setPane(l: Int, t: Int, r: Int, b: Int) {
            runOnUiThread {
                try {
                    val loc = IntArray(2); web.getLocationOnScreen(loc)
                    val nr = android.graphics.Rect(l + loc[0], t + loc[1], r + loc[0], b + loc[1])
                    paneRect = nr
                    if (floatPkg.isNotEmpty()) {
                        val fr = floatRect
                        val moved = if (fr == null) 999 else Math.abs(fr.left - nr.left) + Math.abs(fr.top - nr.top) + Math.abs(fr.right - nr.right) + Math.abs(fr.bottom - nr.bottom)
                        if (moved > 6) {
                            if (fr == null || moved > 120) { calib = 0; adj.set(0, 0, 0, 0) }
                            floatRect = nr; saveFloat()
                        }
                    }
                } catch (e: Exception) { }
            }
        }

        /** A hub screen (Settings, apps, themes, music picker) opened: fold the Maps window so it cannot cover it. */
        @JavascriptInterface fun foldMaps() {
            runOnUiThread {
                sheetOpen = true
                val np = floatingNavPkg()
                if (np.isNotEmpty()) {
                    floatPkg = np; tuckedPkg = np
                    ignoreHomeUntil = SystemClock.elapsedRealtime() + 3000
                    MixService.inst?.goHome(); ev("Folded Maps for a hub screen (same as Home)")
                    // if it is still showing a moment later, use the slower way
                    main.postDelayed({ if (sheetOpen && floatingNavPkg().isNotEmpty()) { ev("Maps still showing over the hub screen, folding it again"); MixService.inst?.minimizeFloating(np) } }, 1800)
                }
            }
        }

        /** The hub screen closed: put Maps back on the pane. */
        @JavascriptInterface fun unfoldMaps() { runOnUiThread { sheetOpen = false; restoreMaps() } }

        @JavascriptInterface fun navigateQuery(pkg: String, query: String, l: Int, t: Int, r: Int, b: Int): Boolean {
            return try {
                val q = Uri.encode(query)
                val uri = if (pkg == "com.waze") Uri.parse("waze://?q=$q&navigate=yes") else Uri.parse("google.navigation:q=$q&mode=d")
                val i = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val o = android.app.ActivityOptions.makeBasic(); val want = paneRect ?: android.graphics.Rect(l, t, r, b); if (floatRect != want) { calib = 0 }; o.setLaunchBounds(adjusted(want))
                startActivity(i, o.toBundle()); floatPkg = pkg; floatRect = want; saveFloat(); true
            } catch (e: Exception) { false }
        }

        @JavascriptInterface fun navigateIn(pkg: String, lat: Double, lon: Double, name: String, l: Int, t: Int, r: Int, b: Int): Boolean {
            return try {
                val uri = if (pkg == "com.waze") Uri.parse("waze://?ll=$lat,$lon&navigate=yes") else Uri.parse("google.navigation:q=$lat,$lon&mode=d")
                val i = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val o = android.app.ActivityOptions.makeBasic(); val want = paneRect ?: android.graphics.Rect(l, t, r, b); if (floatRect != want) { calib = 0 }; o.setLaunchBounds(adjusted(want))
                startActivity(i, o.toBundle()); floatPkg = pkg; floatRect = want; saveFloat(); true
            } catch (e: Exception) { false }
        }
        @JavascriptInterface fun setThemeHue(h: Int) { ringHue = h; }
        @JavascriptInterface fun copyText(s: String) { runOnUiThread { try { (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Mazda Hub report", s)) } catch (e: Exception) { } } }
        @JavascriptInterface fun clearLog() { Ops.prefs(this@MainActivity).edit().remove("autolog").apply(); MixService.events = emptyList() }
        @JavascriptInterface fun getAutoLog(): String = Ops.prefs(this@MainActivity).getString("autolog", "") ?: ""
        @JavascriptInterface fun getFloatCal(): String = calLog
        @JavascriptInterface fun getHideBars(): Boolean = Ops.prefs(this@MainActivity).getBoolean("hide_bars", false)
        @JavascriptInterface fun setHideBars(on: Boolean) { Ops.prefs(this@MainActivity).edit().putBoolean("hide_bars", on).apply(); runOnUiThread { goImmersive() } }
        @JavascriptInterface fun cleanNow(): String { cleanCaches(true); return "Cleaning" }
        @JavascriptInterface fun getCleanStatus(): String = lastClean
        @JavascriptInterface fun getFavs(): String = Ops.prefs(this@MainActivity).getString("favs", "") ?: ""
        @JavascriptInterface fun setFavs(json: String) { Ops.prefs(this@MainActivity).edit().putString("favs", json).apply() }

        @JavascriptInterface fun mediaKey(name: String) {
            val c = NowPlayingService.controller
            if (c != null) {
                val t = c.transportControls
                val playing = c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                when (name) {
                    "play" -> t.play(); "pause" -> t.pause()
                    "toggle" -> if (playing) t.pause() else t.play()
                    "next" -> t.skipToNext(); "prev" -> t.skipToPrevious()
                }
            } else Ops.mediaKeyByName(this@MainActivity, name)
        }

        @JavascriptInterface fun seekTo(ms: Long): Boolean {
            val c = NowPlayingService.controller ?: return false
            c.transportControls.seekTo(ms); return true
        }

        /** Thumbs up / down: uses the app's rating if it has one, otherwise its thumbs custom actions. */
        @JavascriptInterface fun rate(kind: String): Boolean {
            val c = NowPlayingService.controller ?: return false
            try {
                val r = c.metadata?.getRating(android.media.MediaMetadata.METADATA_KEY_USER_RATING)
                if (r != null && r.ratingStyle == android.media.Rating.RATING_THUMB_UP_DOWN) {
                    val up = kind == "like"
                    val cur = if (!r.isRated) 0 else if (r.isThumbUp) 1 else -1
                    val next = if (up && cur == 1 || !up && cur == -1) android.media.Rating.newUnratedRating(android.media.Rating.RATING_THUMB_UP_DOWN) else android.media.Rating.newThumbRating(up)
                    c.transportControls.setRating(next); return true
                }
            } catch (e: Exception) { }
            val acts = c.playbackState?.customActions ?: return false
            val want = if (kind == "like") listOf("thumb_up", "thumbs_up", "thumbup", "thumbsup", "like", "love", "heart", "favorite", "favourite") else listOf("thumb_down", "thumbs_down", "thumbdown", "thumbsdown", "dislike")
            val hit = acts.firstOrNull { a ->
                val ic = try { createPackageContext(c.packageName, 0).resources.getResourceEntryName(a.icon) } catch (e: Exception) { "" }
                val k = (a.action + " " + a.name + " " + ic).lowercase()
                want.any { k.contains(it) } && (kind == "dislike" || !k.contains("dislike"))
            } ?: return false
            c.transportControls.sendCustomAction(hit, null); return true
        }
        /** Start a mix: the accessibility helper opens the player menu in the music app and taps "Start mix". */
        @JavascriptInterface fun startMix(artist: String, title: String): String {
            val c = NowPlayingService.controller ?: return "none"
            val svc = MixService.inst
            if (svc == null) { openSetup("a11y"); return "needs-access" }
            val pkg = c.packageName
            Ops.launchApp(this@MainActivity, pkg)
            svc.begin(pkg)
            return "started"
        }
        @JavascriptInterface fun mixNow(artist: String, title: String): String = startMix(artist, title)
        @JavascriptInterface fun cancelMix() { }
        @JavascriptInterface fun mixPending(): Boolean = false
        @JavascriptInterface fun getStatus(): String {
            val c = this@MainActivity
            val o = JSONObject()
            try {
                val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                o.put("net", if (caps == null) "off" else if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "online" else "limited")
            } catch (e: Exception) { o.put("net", "off") }
            try {
                val i = c.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val lv = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val sc = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                val st = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                o.put("bat", if (lv < 0) 0 else lv * 100 / sc)
                o.put("chg", st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL)
            } catch (e: Exception) { o.put("bat", 0); o.put("chg", false) }
            return o.toString()
        }
        @JavascriptInterface fun getMixStatus(): String = (if (MixService.inst == null) "helper off" else "helper on") + ": " + MixService.status + " | Maps window: " + MixService.floatLog + " | Events: " + MixService.events.joinToString(" ; ")
        @JavascriptInterface fun getTrackDebug(): String {
            val c = NowPlayingService.controller ?: return "no player"
            val m = c.metadata
            return c.packageName + " id=" + (m?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "-") + " uri=" + (m?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_URI) ?: "-") + " video=" + trackId(c).ifEmpty { "none" } + " | mix: " + MixService.status
        }

        @JavascriptInterface fun getRateDebug(): String = NowPlayingService.rateDbg

        @JavascriptInterface fun getNow(): String = NowPlayingService.lastNow
        @JavascriptInterface fun getNav(): String = NowPlayingService.lastNav
        @JavascriptInterface fun getNavDebug(): String {
            if (NowPlayingService.inst == null) return "Notification access is OFF for Mazda Hub"
            val seen = if (NowPlayingService.seen.isEmpty()) "no notifications yet" else "apps seen: " + NowPlayingService.seen.joinToString(", ") + " (* = ongoing)"
            val nav = if (NowPlayingService.dbg.isEmpty()) "no Maps/Waze notification" else NowPlayingService.dbg.joinToString(" | ")
            return "Access is on. $seen. Navigation: $nav"
        }
        @JavascriptInterface fun getNavApps(): String = try { Ops.navApps(this@MainActivity).toString() } catch (e: Exception) { "[]" }

        @JavascriptInterface fun setMusic(pkg: String) { Ops.prefs(this@MainActivity).edit().putString("music_pkg", pkg).apply() }

        @JavascriptInterface fun getSetup(): String {
            val c = this@MainActivity
            val p = Ops.prefs(c)
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            val mem = android.app.ActivityManager.MemoryInfo()
            (c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mem)
            val o = JSONObject()
            o.put("overlay", Settings.canDrawOverlays(c))
            o.put("write", Settings.System.canWrite(c))
            o.put("battery", pm.isIgnoringBatteryOptimizations(c.packageName))
            o.put("notif", NowPlayingService.inst != null || android.provider.Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")?.contains(c.packageName) == true)
            o.put("a11y", MixService.inst != null)
            o.put("loc", checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            o.put("gps", packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS))
            o.put("mic", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            o.put("btPerm", Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            o.put("carAddr", p.getString("car_addr", ""))
            o.put("carName", p.getString("car_name", ""))
            o.put("auto", p.getBoolean("auto", false))
            o.put("music", p.getString("music_pkg", ""))
            o.put("delay", p.getInt("delay_s", 8))
            o.put("lowRam", mem.totalMem < 3_500_000_000L)
            o.put("model", Build.MODEL)
            return o.toString()
        }

        @JavascriptInterface fun openSetup(which: String) {
            runOnUiThread {
                val c = this@MainActivity
                val pkg = Uri.parse("package:" + c.packageName)
                try {
                    when (which) {
                        "overlay" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
                        "write" -> startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg))
                        "battery" -> startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
                        "notif" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        "a11y" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        "android" -> startActivity(Intent(Settings.ACTION_SETTINGS))
                        "wifi" -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        "pip" -> startActivity(Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS"))
                        "loc" -> requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC)
                        "mic" -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                        "bt" -> if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
                        "home" -> startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                    }
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) } catch (e2: Exception) { }
                }
            }
        }

        @JavascriptInterface fun getBtDevices(): String {
            val arr = JSONArray()
            try {
                val ad: BluetoothAdapter? = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ad?.bondedDevices?.forEach { d ->
                    arr.put(JSONObject().put("name", d.name ?: d.address).put("addr", d.address))
                }
            } catch (e: SecurityException) { }
            return arr.toString()
        }

        @JavascriptInterface fun setCar(addr: String, name: String) {
            Ops.prefs(this@MainActivity).edit().putString("car_addr", addr).putString("car_name", name).apply()
        }

        @JavascriptInterface fun setAuto(on: Boolean) { Ops.prefs(this@MainActivity).edit().putBoolean("auto", on).apply() }

        @JavascriptInterface fun setDelay(sec: Int) { Ops.prefs(this@MainActivity).edit().putInt("delay_s", sec.coerceIn(3, 30)).apply() }

        @JavascriptInterface fun testAuto() { runOnUiThread { autoStart() } }
    }

    companion object {
        private const val REQ_FILE = 1001
        private const val REQ_BT = 1002
        private const val REQ_MIC = 1003
        private const val REQ_LOC = 1004
        private const val REQ_VOICE = 1005
        private const val REQ_CMD = 1006
        private const val REQ_BACKUP = 1007
        private const val REQ_RESTORE = 1008
        @Volatile var live: MainActivity? = null
    }
}
