package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.cloudstream3.CloudStreamApp

class Event<T> {
    private val observers = mutableSetOf<(T) -> Unit>()
    val size: Int get() = observers.size

    operator fun plusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers.add(observer) }
    }

    operator fun minusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers.remove(observer) }
    }

    operator fun invoke(value: T) {
        synchronized(observers) { for (o in observers) o(value) }
    }
}

class EmptyEvent {
    private val observers = mutableSetOf<Runnable>()
    val size: Int get() = observers.size

    operator fun plusAssign(observer: Runnable) {
        synchronized(observers) { observers.add(observer) }
    }

    operator fun minusAssign(observer: Runnable) {
        synchronized(observers) { observers.remove(observer) }
    }

    operator fun invoke() {
        synchronized(observers) { for (o in observers) o.run() }
    }
}

object DataStore {
    private const val PREFS = "cs3_datastore"

    val mapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    fun getSharedPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getFolderName(folder: String, path: String): String = "$folder/$path"

    fun <T> setKey(path: String, value: T) {
        val ctx = CloudStreamApp.getContext() ?: return
        try {
            getSharedPrefs(ctx).edit().putString(path, mapper.writeValueAsString(value)).apply()
        } catch (_: Exception) {
        }
    }

    fun <T> getKey(path: String, valueType: Class<T>): T? {
        val ctx = CloudStreamApp.getContext() ?: return null
        val json = getSharedPrefs(ctx).getString(path, null) ?: return null
        return try { mapper.readValue(json, valueType) } catch (_: Exception) { null }
    }

    fun <T> Context.setKey(path: String, value: T) {
        try {
            getSharedPrefs(this).edit().putString(path, mapper.writeValueAsString(value)).apply()
        } catch (_: Exception) {
        }
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) =
        setKey(getFolderName(folder, path), value)

    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? {
        val json = getSharedPrefs(this).getString(path, null) ?: return null
        return try { mapper.readValue(json, valueType) } catch (_: Exception) { null }
    }

    fun <T : Any> Context.getKey(folder: String, path: String, valueType: Class<T>): T? =
        getKey(getFolderName(folder, path), valueType)

    fun Context.getKeys(folder: String): List<String> {
        val fixed = folder.trimEnd('/') + "/"
        return try { getSharedPrefs(this).all.keys.filter { it.startsWith(fixed) } } catch (_: Exception) { emptyList() }
    }

    fun Context.containsKey(path: String): Boolean =
        try { getSharedPrefs(this).contains(path) } catch (_: Exception) { false }

    fun Context.containsKey(folder: String, path: String): Boolean =
        containsKey(getFolderName(folder, path))

    fun Context.removeKey(path: String) {
        try {
            val p = getSharedPrefs(this)
            if (p.contains(path)) p.edit().remove(path).apply()
        } catch (_: Exception) {
        }
    }

    fun Context.removeKey(folder: String, path: String) = removeKey(getFolderName(folder, path))

    fun Context.removeKeys(folder: String): Int = try {
        val keys = getKeys("$folder/")
        val ed = getSharedPrefs(this).edit()
        keys.forEach { ed.remove(it) }
        ed.apply()
        keys.size
    } catch (_: Exception) {
        0
    }
}
