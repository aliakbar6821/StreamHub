package dev.lynx.streamhub

import android.content.Context
import android.webkit.CookieManager
import com.lagradost.cloudstream3.app
import java.lang.ref.WeakReference
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Initialize both context APIs used by the pinned Cloudstream 4.8 library. */
object CsNetworkCompat {
    @Volatile private var configured = false

    @Synchronized
    fun initialize(context: Context) {
        val application = context.applicationContext
        com.lagradost.cloudstream3.CloudStreamApp.setContext(application)
        // The pinned 4.8 API takes WeakReference<Any>, unlike newer upstream versions.
        com.lagradost.api.setContext(WeakReference<Any>(application))
        if (configured) return
        try {
            val previous = app.baseClient.cookieJar
            app.baseClient =
                app.baseClient.newBuilder().cookieJar(ProviderCookieJar(previous)).build()
            configured = true
            Diagnostics.event("provider.network.ready", "context=true webviewCookies=true")
        } catch (t: Exception) {
            Diagnostics.event("provider.network.failed", "type=${t.javaClass.simpleName}")
        }
    }
}

/** Scope/filter using OkHttp and WebView rules; never use a process-wide raw Cookie header. */
internal class ProviderCookieJar(private val previous: CookieJar) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        previous.saveFromResponse(url, cookies)
        runCatching {
            val manager = CookieManager.getInstance()
            cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
            manager.flush()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val merged = previous.loadForRequest(url).filter { it.matches(url) }.toMutableList()
        runCatching {
            CookieManager.getInstance().getCookie(url.toString())?.split(';')?.forEach { pair ->
                val cookie = Cookie.parse(url, pair.trim())
                if (cookie != null && cookie.matches(url)) {
                    // Only override the same browser-visible name; never send another origin's
                    // cookies.
                    merged.removeAll { it.name == cookie.name }
                    merged.add(cookie)
                }
            }
        }
        return merged
    }
}
