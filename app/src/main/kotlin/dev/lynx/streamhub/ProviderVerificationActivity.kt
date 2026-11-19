package dev.lynx.streamhub

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import java.net.URI
import okhttp3.Interceptor
import okhttp3.Response

/** Records the actual solver UA, scoped to its origin, not an invented browser identity. */
object ProviderVerification {
    private var application: Context? = null

    fun init(c: Context) {
        application = c.applicationContext
    }

    internal fun origin(url: String): String? =
        runCatching {
                val u = URI(url)
                if (u.scheme != "https" || u.host == null || u.userInfo != null) null
                else "https://${u.host.lowercase()}:${if(u.port<0)443 else u.port}"
            }
            .getOrNull()

    fun record(url: String?, ua: String?) {
        if (url == null || ua.isNullOrBlank() || '\n' in ua || '\r' in ua) return
        val key = origin(url) ?: return
        application
            ?.getSharedPreferences("provider_verification", Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(key, ua)
            ?.apply()
    }

    fun userAgent(url: String): String? {
        val key = origin(url) ?: return null
        return application
            ?.getSharedPreferences("provider_verification", Context.MODE_PRIVATE)
            ?.getString(key, null)
    }

    fun observe(f: Fragment) {
        fun walk(v: android.view.View) {
            if (v is WebView) record(v.url, v.settings.userAgentString)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        f.view?.let(::walk)
    }

    val fragmentObserver =
        object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                observe(f)
            }

            override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
                observe(f)
            }

            override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                observe(f)
            }
        }
}

/** Scoped per-hop observer; never logs paths, signed queries, cookies or bodies. */
class ProviderNetworkObserver : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val ua = ProviderVerification.userAgent(original.url.toString())
        val cookie = original.header("Cookie").orEmpty()
        val hasClearance = cookie.split(';').any { it.trim().startsWith("cf_clearance=") }
        val request =
            if (hasClearance && ua != null) original.newBuilder().header("User-Agent", ua).build()
            else original
        try {
            val response = chain.proceed(request)
            if (Diagnostics.enabled())
                Diagnostics.event(
                    "provider.http",
                    "host=${request.url.host} status=${response.code} type=${response.header("Content-Type").orEmpty().substringBefore(';').take(60)} challenge=${response.header("cf-mitigated")=="challenge"}",
                )
            return response
        } catch (t: java.io.IOException) {
            if (Diagnostics.enabled())
                Diagnostics.event(
                    "provider.http.failed",
                    "host=${request.url.host} type=${t.javaClass.simpleName}",
                )
            throw t
        }
    }
}

/** User-initiated foreground verification. No SSL bypass, hidden solving or automatic playback. */
class ProviderVerificationActivity : AppCompatActivity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val target = intent.getStringExtra("website")
        if (StremioProtocol.http(target) == null || !target!!.startsWith("https://")) {
            finish()
            return
        }
        val density = resources.displayMetrics.density
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            }
        val status =
            TextView(this).apply {
                text =
                    "Complete the provider's verification, then tap Done and reload sources. This is a website check, not media playback."
            }
        val buttons = LinearLayout(this)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        val browser = WebView(this)
        web = browser
        val settings = browser.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        // Use a normal WebView UA; retain this exact value for clearance-bound HTTP requests.
        settings.userAgentString =
            WebSettings.getDefaultUserAgent(this)
                .replace("; wv", "")
                .replace(Regex("Version/[0-9.]+\\s*"), "")
        browser.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) {
                    progress.progress = value
                }
            }
        browser.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = request.url.scheme != "https"

                override fun onPageFinished(view: WebView, url: String) {
                    ProviderVerification.record(url, view.settings.userAgentString)
                    CookieManager.getInstance().flush()
                    status.text =
                        "Verification page loaded. Complete any challenge, tap Done, then reload sources. If the page stays blank, update Android System WebView/Chrome and retry."
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        status.text =
                            "Website could not load (WebView code ${error.errorCode}). Check the network, update Android System WebView/Chrome, or retry."
                        Diagnostics.event("provider.webview.error", "code=${error.errorCode}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    response: WebResourceResponse,
                ) {
                    if (request.isForMainFrame)
                        status.text =
                            "Website returned HTTP ${response.statusCode}. A site challenge or network restriction may still need attention."
                }
            }
        buttons.addView(
            Button(this).apply {
                text = "Retry"
                setOnClickListener { browser.reload() }
            }
        )
        buttons.addView(
            Button(this).apply {
                text = "Done"
                setOnClickListener {
                    ProviderVerification.record(browser.url, settings.userAgentString)
                    CookieManager.getInstance().flush()
                    finish()
                }
            }
        )
        root.addView(status)
        root.addView(buttons)
        root.addView(progress)
        root.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        browser.loadUrl(target)
        if (Diagnostics.enabled() && android.os.Build.VERSION.SDK_INT >= 26)
            Diagnostics.event(
                "provider.webview.version",
                "version=${WebView.getCurrentWebViewPackage()?.versionName.orEmpty()}",
            )
    }

    override fun onPause() {
        web?.let {
            ProviderVerification.record(it.url, it.settings.userAgentString)
            it.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        web?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        web = null
        super.onDestroy()
    }
}
