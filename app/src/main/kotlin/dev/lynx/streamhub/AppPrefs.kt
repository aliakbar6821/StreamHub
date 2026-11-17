package dev.lynx.streamhub

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import org.json.JSONArray

object AppPrefs {
    private var prefs: SharedPreferences? = null
    var appearance by mutableStateOf("system")
    var defaultsEnabled by mutableStateOf(true)
    var extraExtensions by mutableStateOf(false)
    var defaultServer by mutableStateOf("")
    var knownServers by mutableStateOf<List<String>>(emptyList())
    var setupStatus by mutableStateOf("")
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("streamhub_ui", Context.MODE_PRIVATE)
        prefs = p
        appearance = p.getString("appearance", "system") ?: "system"
        defaultsEnabled = p.getBoolean("defaults", true)
        extraExtensions = p.getBoolean("extra", false)
        defaultServer = p.getString("server", "") ?: ""
        knownServers = runCatching {
            val a = JSONArray(p.getString("servers", "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }
    fun save() {
        prefs?.edit()?.putString("appearance", appearance)
            ?.putBoolean("defaults", defaultsEnabled)?.putBoolean("extra", extraExtensions)
            ?.putString("server", defaultServer)?.putString("servers", JSONArray(knownServers).toString())?.apply()
    }
    private fun key(title: String, field: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(title.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$hash:$field"
    }
    fun audio(title: String) = prefs?.getString(key(title, "audio"), null) ?: PlayerPrefs.audioLang
    fun subtitle(title: String) = prefs?.getString(key(title, "subtitle"), null) ?: PlayerPrefs.subLang
    fun server(title: String) = prefs?.getString(key(title, "server"), null) ?: defaultServer
    fun setAudio(title: String, value: String) { prefs?.edit()?.putString(key(title, "audio"), value)?.apply() }
    fun setSubtitle(title: String, value: String) { prefs?.edit()?.putString(key(title, "subtitle"), value)?.apply() }
    fun setServer(title: String, value: String) { prefs?.edit()?.putString(key(title, "server"), value)?.apply() }
    fun recordServers(names: List<String>) {
        val merged = (names.filter { it.isNotBlank() } + knownServers).distinct().take(40)
        if (merged != knownServers) { knownServers = merged; save() }
    }
}
