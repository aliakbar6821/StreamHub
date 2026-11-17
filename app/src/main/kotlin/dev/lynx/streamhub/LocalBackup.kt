package dev.lynx.streamhub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Preference backups, not downloaded video files or plugin binaries. */
object LocalBackup {
    private val names = setOf("streamhub", "streamhub_player", "profile", "streamhub_ui")
    fun export(context: Context, listOnly: Boolean): String {
        val root = JSONObject().put("format", "streamhub-backup").put("version", 1)
            .put("listOnly", listOnly)
        val stores = JSONObject()
        for (name in if (listOnly) setOf("streamhub") else names) {
            val entries = JSONObject()
            for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (listOnly && key != "mylist") continue
                val type = when (value) {
                    is String -> "string"
                    is Boolean -> "boolean"
                    is Int -> "int"
                    is Long -> "long"
                    is Float -> "float"
                    is Set<*> -> "set"
                    else -> continue
                }
                entries.put(key, JSONObject().put("type", type).put("value",
                    if (value is Set<*>) JSONArray(value.toList()) else value))
            }
            stores.put(name, entries)
        }
        return root.put("stores", stores).toString(2)
    }
    fun restore(context: Context, text: String) {
        require(text.length <= 5_000_000) { "Backup exceeds 5 MB" }
        val root = JSONObject(text)
        require(root.getString("format") == "streamhub-backup" && root.getInt("version") == 1) {
            "Unsupported backup format"
        }
        val stores = root.getJSONObject("stores")
        val listOnly = root.optBoolean("listOnly", false)
        // Validate every field before changing any preference store.
        val parsed = mutableMapOf<String, Map<String, Any>>()
        for (name in stores.keys()) {
            require(name in names && (!listOnly || name == "streamhub")) { "Unsupported preference store" }
            val entries = stores.getJSONObject(name)
            val result = mutableMapOf<String, Any>()
            for (key in entries.keys()) {
                require(!listOnly || key == "mylist") { "Unexpected list entry" }
                val e = entries.getJSONObject(key)
                val value: Any = when (e.getString("type")) {
                    "string" -> e.getString("value")
                    "boolean" -> e.getBoolean("value")
                    "int" -> e.getInt("value")
                    "long" -> e.getLong("value")
                    "float" -> e.getDouble("value").toFloat()
                    "set" -> e.getJSONArray("value").let { a ->
                        (0 until a.length()).map { a.getString(it) }.toSet()
                    }
                    else -> error("Unsupported preference type")
                }
                if (name == "streamhub" && key == "mylist") {
                    val list = JSONArray(value as String)
                    require(list.length() <= 10000) { "List is too large" }
                    for (i in 0 until list.length()) list.getJSONObject(i).getJSONObject("item").getString("id")
                }
                result[key] = value
            }
            parsed[name] = result
        }
        for ((name, entries) in parsed) {
            val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            if (!listOnly) edit.clear()
            for ((key, value) in entries) {
                when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            require(edit.commit()) { "Could not save restored preferences" }
        }
    }
}
