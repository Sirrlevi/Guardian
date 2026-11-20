package com.sirrlevi.guardian.child

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sirrlevi.guardian.core.DomainRules
import com.sirrlevi.guardian.core.GuardianApi
import java.util.concurrent.Executors

class GuardianBrowserActivity : AppCompatActivity() {
    private val api by lazy { GuardianApi(BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_DATABASE_URL) }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var address: EditText
    private lateinit var web: WebView
    private var activeUrl = ""
    private var activeTitle = ""
    private var startedAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 24, 39)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 18, 30))
            setPadding(dp(10), dp(10), dp(10), dp(8))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        address = EditText(this).apply {
            hint = "Search or enter website"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.rgb(29, 39, 57))
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigate(text.toString())
                    true
                } else false
            }
        }
        val go = MaterialButton(this).apply {
            text = "Go"
            setOnClickListener { navigate(address.text.toString()) }
        }
        bar.addView(address)
        bar.addView(go)
        root.addView(bar)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = BrowserClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(Color.WHITE)
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val note = TextView(this).apply {
            text = "Guardian Browser · Visited URLs and approximate time on each page are shared with the paired parent."
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        root.addView(note)
        setContentView(root)
        intent.getStringExtra("url")?.let { navigate(it) }
    }

    private fun navigate(raw: String) {
        var value = raw.trim()
        if (value.isBlank()) return
        if (!value.contains("://")) {
            value = if (value.contains(" ") || !value.contains(".")) {
                "https://www.google.com/search?q=" + Uri.encode(value)
            } else "https://$value"
        }
        if (isBlocked(value)) {
            recordBlocked(value)
            showBlockedPage(value)
            return
        }
        address.setText(value)
        web.loadUrl(value)
    }

    private inner class BrowserClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (isBlocked(url)) {
                recordBlocked(url)
                showBlockedPage(url)
                return true
            }
            return false
        }

        @Deprecated("Legacy WebView callback for older Android versions")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean {
            if (isBlocked(url)) {
                recordBlocked(url)
                showBlockedPage(url)
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            val next = url.orEmpty()
            if (next.startsWith("http")) {
                if (activeUrl.isNotBlank() && activeUrl != next) finishVisit()
                activeUrl = next
                activeTitle = ""
                startedAt = System.currentTimeMillis()
                address.setText(next)
            }
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            activeTitle = view?.title.orEmpty()
            super.onPageFinished(view, url)
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
            handler?.cancel()
        }
    }

    private fun isBlocked(url: String): Boolean {
        val prefs = getSharedPreferences("guardian_child", MODE_PRIVATE)
        val domains = try { Gson().fromJson(prefs.getString("blocked_domains", "[]"), Array<String>::class.java)?.toList().orEmpty() }
            catch (_: Exception) { emptyList() }
        val urlRules = try { Gson().fromJson(prefs.getString("blocked_urls", "[]"), Array<String>::class.java)?.toList().orEmpty() }
            catch (_: Exception) { emptyList() }
        return DomainRules.isBlockedUrl(url, domains, urlRules)
    }

    private fun showBlockedPage(url: String) {
        activeUrl = ""
        startedAt = 0L
        web.loadDataWithBaseURL(
            "https://guardian.blocked/",
            "<!doctype html><html><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<body style='font-family:sans-serif;background:#101827;color:#eef3ff;padding:28px'>" +
                "<h2>Website blocked</h2><p>This page is not available under the family safety settings.</p>" +
                "<small>" + url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") +
                "</small></body></html>",
            "text/html", "UTF-8", null
        )
    }

    private fun recordBlocked(url: String) {
        val now = System.currentTimeMillis()
        val record = JsonObject().apply {
            addProperty("url", url)
            addProperty("startedAt", now)
            addProperty("endedAt", now)
            addProperty("durationMs", 0L)
            addProperty("event", "blocked_attempt")
        }
        writeHistory(record)
    }

    private fun finishVisit() {
        val url = activeUrl
        val start = startedAt
        if (url.isBlank() || start <= 0L) return
        val end = System.currentTimeMillis()
        val record = JsonObject().apply {
            addProperty("url", url)
            addProperty("title", activeTitle)
            addProperty("domain", DomainRules.hostOf(url).orEmpty())
            addProperty("startedAt", start)
            addProperty("endedAt", end)
            addProperty("durationMs", (end - start).coerceAtLeast(0L))
            addProperty("event", "page_visit")
        }
        activeUrl = ""
        startedAt = 0L
        writeHistory(record)
    }

    private fun writeHistory(record: JsonObject) {
        io.execute {
            try {
                val vault = SessionVault(this)
                var session = vault.load() ?: return@execute
                if (session.expiresAtMs < System.currentTimeMillis() + 60_000L) {
                    session = api.refresh(session)
                    vault.save(session)
                }
                val family = getSharedPreferences("guardian_child", MODE_PRIVATE).getString("family_id", "").orEmpty()
                if (family.isNotBlank() && api.isConfigured()) {
                    api.post("families/$family/devices/${session.uid}/browserHistory", record, session)
                }
            } catch (_: Exception) { }
        }
    }

    override fun onPause() {
        finishVisit()
        super.onPause()
    }

    override fun onDestroy() {
        finishVisit()
        web.stopLoading()
        web.destroy()
        io.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
