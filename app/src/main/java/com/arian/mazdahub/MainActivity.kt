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
import android.os.Build
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(assetHost)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        setContentView(web)

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
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

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
    }

    // ---------------- car auto start: wait for internet, open the music app, press play ----------------
    private fun js(code: String) { runOnUiThread { web.evaluateJavascript(code, null) } }
    private fun say(msg: String) { js("window.hub&&hub.autoStatus&&hub.autoStatus(" + JSONObject.quote(msg) + ")") }

    fun autoStart() {
        autoStop(quiet = true)
        val id = ++runId
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        js("window.hub&&hub.setAwake&&hub.setAwake(true)")
        say("Car connected. Waiting for the hotspot\u2026")
        waitOnline(id, 180_000) {
            if (id != runId) return@waitOnline
            say("Starting music\u2026")
            val prefs = Ops.prefs(this)
            val want = prefs.getString("music_pkg", "") ?: ""
            var used = if (want.isNotEmpty() && Ops.launchApp(this, want)) want else null
            if (used == null) {
                val alt = Ops.fallbackApp(this, want)
                if (alt != null && Ops.launchApp(this, alt)) {
                    used = alt
                    say("Your chosen app would not open. Using another music app.")
                }
            }
            if (used == null) { say("No music app found. Pick one in the music picker."); return@waitOnline }
            val delay = prefs.getInt("delay_s", 8) * 1000L
            main.postDelayed({
                if (id != runId) return@postDelayed
                if (!Ops.musicActive(this)) Ops.mediaKey(this, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                main.postDelayed({
                    if (id != runId) return@postDelayed
                    if (!Ops.musicActive(this)) Ops.mediaKey(this, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                    // Back to the hub so the music keeps playing underneath it.
                    bringHubToFront(id)
                    say(if (Ops.musicActive(this)) "Playing" else "Music app opened. Press play if it is quiet.")
                }, 5000)
            }, delay)
        }
    }

    private var visible = false
    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { visible = false; super.onPause() }

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
        fun go() { if (!done) { done = true; clearNet(); then() } }
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) { go(); return }
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
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
        if (live === this) live = null
        clearNet()
        web.destroy()
        super.onDestroy()
    }

    /** Called from the hub page. */
    inner class Bridge {
        @JavascriptInterface
        fun setKeepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface fun getApps(all: Boolean): String = try { Ops.musicApps(this@MainActivity, all).toString() } catch (e: Exception) { "[]" }

        @JavascriptInterface fun launchApp(pkg: String): Boolean = Ops.launchApp(this@MainActivity, pkg)

        @JavascriptInterface fun mediaKey(name: String) { Ops.mediaKeyByName(this@MainActivity, name) }

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
        @Volatile var live: MainActivity? = null
    }
}
