package com.example.tvvideocaster

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var searchButton: Button
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var listView: ListView
    private lateinit var webView: WebView
    private lateinit var adBlocker: AdBlocker
    private lateinit var adCheck: CheckBox

    private val sources = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private val handler = Handler(Looper.getMainLooper())

    private var pageReferer: String = ""

    // Extensiones / patrones que consideramos "fuente de vídeo"
    private val videoRegex = Regex(
        // Sin ".ts": los segmentos HLS llenarían la lista con cientos de entradas
        """\.(m3u8|mp4|mkv|webm|mpd|mov|avi|flv)(\?|$|#)""",
        RegexOption.IGNORE_CASE
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        searchButton = findViewById(R.id.searchButton)
        statusText = findViewById(R.id.statusText)
        progress = findViewById(R.id.progress)
        listView = findViewById(R.id.sourcesList)
        webView = findViewById(R.id.hiddenWebView)

        // Bloqueador de publicidad
        adBlocker = AdBlocker(this)
        adCheck = findViewById(R.id.adBlockCheck)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        adBlocker.enabled = prefs.getBoolean("adblock_enabled", true)
        adCheck.isChecked = adBlocker.enabled
        adCheck.setOnCheckedChangeListener { _, checked ->
            adBlocker.enabled = checked
            prefs.edit().putBoolean("adblock_enabled", checked).apply()
        }
        findViewById<Button>(R.id.updateFiltersButton).setOnClickListener {
            statusText.setText(R.string.filters_loading)
            adBlocker.updateNow { ok, count ->
                statusText.text = if (ok) getString(R.string.filters_ok, count)
                else getString(R.string.filters_fail, count)
            }
        }
        adBlocker.init { /* lista cargada en segundo plano */ }

        adapter = ArrayAdapter(this, R.layout.item_source, R.id.sourceLabel, sources)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            openInVlc(sources[position])
        }

        setupWebView()

        searchButton.setOnClickListener { startSearch() }
        findViewById<Button>(R.id.playButton).setOnClickListener { simulatePlay() }
        urlInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                startSearch()
                true
            } else false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            // Captura todas las peticiones de red que hace la página (m3u8, mp4...)
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()
                if (url != null) {
                    if (adBlocker.isBlocked(url)) {
                        // Petición de publicidad: respuesta vacía y no se añade a la lista
                        return WebResourceResponse(
                            "text/plain", "utf-8", 403, "Blocked", emptyMap(),
                            java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    checkUrl(url)
                }
                return super.shouldInterceptRequest(view, request)
            }

            // Evita redirecciones y pop-ups hacia dominios de publicidad
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return adBlocker.isBlocked(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Escanea el DOM varias veces: muchos reproductores cargan tarde
                for (delay in longArrayOf(500, 2500, 5000, 8000)) {
                    handler.postDelayed({ scanDom() }, delay)
                }
                handler.postDelayed({ finishSearch() }, 9000)
            }
        }
    }

    private fun startSearch() {
        var url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.enter_url, Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        pageReferer = url
        adBlocker.blockedCount.set(0)
        handler.removeCallbacksAndMessages(null)
        sources.clear()
        adapter.notifyDataSetChanged()
        progress.visibility = android.view.View.VISIBLE
        statusText.setText(R.string.searching)
        webView.stopLoading()
        webView.loadUrl(url)
    }

    private fun finishSearch() {
        progress.visibility = android.view.View.GONE
        var message = if (sources.isEmpty()) {
            getString(R.string.no_sources)
        } else {
            getString(R.string.sources_found, sources.size)
        }
        val blockedAds = adBlocker.blockedCount.get()
        if (adBlocker.enabled && blockedAds > 0) {
            message += getString(R.string.ads_blocked_suffix, blockedAds)
        }
        statusText.text = message
        if (sources.isNotEmpty()) listView.requestFocus()
    }

    /**
     * Intenta arrancar el reproductor de la página para que pida el vídeo:
     * 1) JS: video.play() y clic en botones de play habituales (también en iframes del mismo dominio).
     * 2) Toque táctil simulado en el centro del WebView (sirve para iframes de otros dominios).
     * Después vuelve a escanear para capturar las nuevas peticiones.
     */
    private fun simulatePlay() {
        if (webView.url.isNullOrEmpty()) {
            Toast.makeText(this, R.string.load_page_first, Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility = android.view.View.VISIBLE
        statusText.setText(R.string.play_tried)

        val js = """
            (function() {
                var selectors = [
                    '.vjs-big-play-button', '.jw-icon-display', '.jw-display-icon-container',
                    '.plyr__control--overlaid', '.ytp-large-play-button', '.play-button',
                    '.btn-play', '.play', '[class*="play-btn"]', '[class*="playbutton"]',
                    '[aria-label*="lay" i]', '[title*="lay" i]', 'button[class*="play"]'
                ];
                function tryDoc(doc) {
                    try {
                        doc.querySelectorAll('video').forEach(function(v) {
                            v.muted = true;
                            var p = v.play(); if (p && p.catch) p.catch(function(){});
                        });
                        selectors.forEach(function(s) {
                            doc.querySelectorAll(s).forEach(function(el) { try { el.click(); } catch(e) {} });
                        });
                        doc.querySelectorAll('iframe').forEach(function(f) {
                            try { if (f.contentDocument) tryDoc(f.contentDocument); } catch(e) {}
                        });
                    } catch(e) {}
                }
                tryDoc(document);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        // Toques simulados en el centro (y algo por encima) del WebView
        val w = webView.width.toFloat()
        val h = webView.height.toFloat()
        val points = listOf(w / 2 to h / 2, w / 2 to h / 3)
        points.forEachIndexed { i, (x, y) ->
            handler.postDelayed({ tap(x, y) }, 300L + i * 600L)
        }

        for (delay in longArrayOf(1500, 3000, 5000, 8000)) {
            handler.postDelayed({ scanDom() }, delay)
        }
        handler.postDelayed({ finishSearch() }, 8500)
    }

    private fun tap(x: Float, y: Float) {
        val down = android.os.SystemClock.uptimeMillis()
        val d = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val u = android.view.MotionEvent.obtain(down, down + 80, android.view.MotionEvent.ACTION_UP, x, y, 0)
        webView.dispatchTouchEvent(d)
        webView.dispatchTouchEvent(u)
        d.recycle()
        u.recycle()
    }

    private fun scanDom() {
        val js = """
            (function() {
                var found = [];
                document.querySelectorAll('video, video source, source, audio').forEach(function(e) {
                    if (e.src) found.push(e.src);
                    if (e.currentSrc) found.push(e.currentSrc);
                });
                document.querySelectorAll('iframe').forEach(function(f) {
                    if (f.src) found.push(f.src);
                });
                found.forEach(function(u) { AndroidBridge.onUrl(u); });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun checkUrl(url: String) {
        if (url.startsWith("blob:") || url.startsWith("data:")) return
        if (!videoRegex.containsMatchIn(url)) return
        if (adBlocker.isBlocked(url)) return // vídeo de anuncio: no se muestra
        runOnUiThread {
            if (!sources.contains(url)) {
                sources.add(url)
                adapter.notifyDataSetChanged()
                statusText.text = getString(R.string.sources_found, sources.size)
            }
        }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onUrl(url: String) {
            checkUrl(url)
        }
    }

    private fun openInVlc(url: String) {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndTypeAndNormalize(uri, "video/*")
            setPackage(VLC_PACKAGE)
            putExtra("title", uri.lastPathSegment ?: "Vídeo")
            // VLC admite cabeceras personalizadas para sitios que exigen Referer
            putExtra(
                "headers",
                arrayOf("Referer", pageReferer, "User-Agent", webView.settings.userAgentString)
            )
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.vlc_missing, Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$VLC_PACKAGE"))
                )
            } catch (e2: ActivityNotFoundException) {
                // sin Play Store: no hacemos nada más
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val VLC_PACKAGE = "org.videolan.vlc"
    }
}
