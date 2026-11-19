package dev.lynx.streamhub

import android.content.Context
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object StremioHost {
    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("stremio_addons", Context.MODE_PRIVATE)

    @Synchronized
    private fun saved(c: Context): JSONArray =
        runCatching { JSONArray(prefs(c).getString("installed", "[]")) }.getOrElse { JSONArray() }

    fun addons(c: Context): List<StremioAddon> =
        StremioProtocol.objects(saved(c)).mapNotNull { o ->
            runCatching {
                    StremioAddon(
                        StremioProtocol.manifestUrl(o.getString("url")),
                        o.getJSONObject("manifest"),
                    )
                }
                .getOrNull()
        }

    fun installed(c: Context) =
        addons(c).map { InstalledExtension(it.key, "${it.name} · Stremio", 1L) }

    fun extensions(c: Context): List<StremioExtension> =
        addons(c)
            .filter { AppPrefs.extensionEnabled(it.key) }
            .map { addon ->
                StremioExtension(addon, { addons(c) }, ::fetch, AppPrefs::extensionEnabled)
            }

    suspend fun install(c: Context, input: String): String {
        val url = StremioProtocol.manifestUrl(input)
        val manifest = fetch(url)
        require(
            !StremioProtocol.text(manifest, "id").isNullOrBlank() &&
                !StremioProtocol.text(manifest, "name").isNullOrBlank() &&
                manifest.optJSONArray("resources") != null &&
                manifest.optJSONArray("types") != null
        ) {
            "Not a Stremio addon manifest"
        }
        require(
            manifest.optJSONObject("behaviorHints")?.optBoolean("configurationRequired") != true
        ) {
            "Configure this addon on its website first, then paste its configured manifest URL"
        }
        val a = StremioAddon(url, manifest)
        save(c, addons(c).filter { it.key != a.key } + a)
        AppPrefs.setExtensionEnabled(a.key, true)
        return a.name
    }

    @Synchronized
    private fun save(c: Context, values: List<StremioAddon>) {
        val array = JSONArray()
        values.forEach { array.put(JSONObject().put("url", it.url).put("manifest", it.manifest)) }
        check(prefs(c).edit().putString("installed", array.toString()).commit()) {
            "Could not save addon"
        }
    }

    @Synchronized
    fun remove(c: Context, key: String) {
        save(c, addons(c).filter { it.key != key })
        AppPrefs.setExtensionEnabled(key, false)
    }

    internal suspend fun fetch(url: String): JSONObject =
        withContext(Dispatchers.IO) {
            // No logging URLs: configured addon paths may contain private tokens.
            try {
                var current = url
                var result: JSONObject? = null
                for (hop in 0..4) {
                    val u = URI(current)
                    require(u.scheme == "https" && u.host != null && u.rawUserInfo == null) {
                        "Addon endpoints must use HTTPS"
                    }
                    val con = URL(current).openConnection() as HttpURLConnection
                    con.connectTimeout = 12_000
                    con.readTimeout = 15_000
                    con.instanceFollowRedirects = false
                    con.setRequestProperty("Accept", "application/json")
                    con.setRequestProperty("User-Agent", "streamHub/0.2.24")
                    try {
                        val code = con.responseCode
                        if (code in listOf(301, 302, 303, 307, 308)) {
                            require(hop < 4) { "Too many addon redirects" }
                            val location =
                                con.getHeaderField("Location") ?: error("Invalid addon redirect")
                            current = u.resolve(location).toASCIIString()
                            continue
                        }
                        check(code in 200..299) { "Addon HTTP $code" }
                        val bytes = con.inputStream.use { it.readBytesLimited(2 * 1024 * 1024) }
                        result = JSONObject(String(bytes, Charsets.UTF_8))
                        break
                    } finally {
                        con.disconnect()
                    }
                }
                result ?: error("No addon response")
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (failure: Exception) {
                error("Addon request failed. Check its availability and configured manifest URL.")
            }
        }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            require(out.size() + n <= limit) { "Addon response too large" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
