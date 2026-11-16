package dev.lynx.streamhub

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class RepoExt(val name: String, val pkg: String, val version: Long, val apk: String)

object Repos {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "StreamHub/0.1")
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun cs3(arr: JSONArray): List<RepoExt> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val internal = o.optString("internalName", o.optString("name"))
        RepoExt(o.optString("name", internal), "cs3:$internal", o.optLong("version", 1L), o.optString("url"))
    }

    suspend fun fetch(url: String): List<RepoExt> = withContext(Dispatchers.IO) {
        val text = get(url).trim()
        if (text.startsWith("[")) {
            val arr = JSONArray(text)
            if (arr.length() > 0 && arr.getJSONObject(0).has("internalName")) return@withContext cs3(arr)
            return@withContext parseNative(arr)
        }
        val o = JSONObject(text)
        if (o.has("extensions")) return@withContext parseNative(o.getJSONArray("extensions"))
        if (o.has("pluginLists")) {
            val lists = o.getJSONArray("pluginLists")
            val out = mutableListOf<RepoExt>()
            for (i in 0 until lists.length()) {
                try { out.addAll(cs3(JSONArray(get(lists.getString(i)).trim()))) } catch (_: Throwable) {}
            }
            return@withContext out
        }
        throw Exception("Unrecognized repository format")
    }

    private fun parseNative(arr: JSONArray): List<RepoExt> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        RepoExt(o.getString("name"), o.getString("pkg"), o.optLong("version", 1L), o.getString("apk"))
    }

    fun installedVersion(context: Context, pkg: String): Long? = try {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (t: Throwable) {
        null
    }

    suspend fun download(context: Context, e: RepoExt): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apks")
        dir.mkdirs()
        val f = File(dir, e.pkg + ".apk")
        val c = URL(e.apk).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "StreamHub/0.1")
        try {
            c.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        f
    }

    fun launchInstall(context: Context, f: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}
