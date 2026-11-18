package dev.lynx.streamhub

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*

object AppPrefs {
    private var prefs: SharedPreferences? = null
    var appearance by mutableStateOf("system")
    var enabledExtensions by mutableStateOf<Set<String>>(emptySet())
    var extraExtensions by mutableStateOf(false)
    var setupStatus by mutableStateOf("")
    fun init(context: Context) {
        AudioMetadataStore.init(context)
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("streamhub_ui", Context.MODE_PRIVATE)
        prefs = p
        appearance = p.getString("appearance", "system") ?: "system"
        // Explicit opt-in. Legacy auto-enabled defaults are intentionally not migrated.
        enabledExtensions = p.getStringSet("enabled_extensions_v2", emptySet()).orEmpty().toSet()
        extraExtensions = p.getBoolean("extra", false)
    }
    fun save() {
        prefs?.edit()?.putString("appearance", appearance)
            ?.putStringSet("enabled_extensions_v2", enabledExtensions)?.putBoolean("extra", extraExtensions)
            ?.apply()
    }
    fun extensionEnabled(id: String): Boolean = id in enabledExtensions
    fun setExtensionEnabled(id: String, enabled: Boolean) {
        enabledExtensions = if (enabled) enabledExtensions + id else enabledExtensions - id
        save()
    }
    private fun key(title: String, field: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(title.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$hash:$field"
    }
    fun audio(title: String) = prefs?.getString(key(title, "audio"), null) ?: PlayerPrefs.audioLang
    fun subtitle(title: String) = prefs?.getString(key(title, "subtitle"), null) ?: PlayerPrefs.subLang
    fun setAudio(title: String, value: String) { prefs?.edit()?.putString(key(title, "audio"), value)?.apply() }
    fun setSubtitle(title: String, value: String) { prefs?.edit()?.putString(key(title, "subtitle"), value)?.apply() }
}
