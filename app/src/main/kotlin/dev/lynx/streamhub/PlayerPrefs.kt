package dev.lynx.streamhub

import android.content.Context
import android.content.SharedPreferences

object PlayerPrefs {
    private var p: SharedPreferences? = null

    fun init(c: Context) {
        if (p == null) {
            p = c.applicationContext.getSharedPreferences("streamhub_player", Context.MODE_PRIVATE)
        }
    }

    /** "" = default, otherwise a language code like "ja" or "en". */
    var audioLang: String
        get() = p?.getString("audio", "") ?: ""
        set(v) { p?.edit()?.putString("audio", v)?.apply() }

    /** "" = default, "off" = disabled, otherwise a language code. */
    var subLang: String
        get() = p?.getString("sub", "") ?: ""
        set(v) { p?.edit()?.putString("sub", v)?.apply() }

    var speed: Float
        get() = p?.getFloat("speed", 1f) ?: 1f
        set(v) { p?.edit()?.putFloat("speed", v)?.apply() }

    var resize: Int
        get() = p?.getInt("resize", 0) ?: 0
        set(v) { p?.edit()?.putInt("resize", v)?.apply() }

    var autoNext: Boolean
        get() = p?.getBoolean("autoNext", true) ?: true
        set(v) { p?.edit()?.putBoolean("autoNext", v)?.apply() }

    var uiScale: Float
        get() = p?.getFloat("uiScale", 0.82f) ?: 0.82f
        set(v) { p?.edit()?.putFloat("uiScale", v)?.apply() }

    var subScale: Float
        get() = p?.getFloat("subScale", 1f) ?: 1f
        set(v) { p?.edit()?.putFloat("subScale", v)?.apply() }
}
