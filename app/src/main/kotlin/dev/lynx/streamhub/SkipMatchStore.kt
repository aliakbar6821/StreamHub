package dev.lynx.streamhub
import android.content.Context

object SkipMatchStore {
    fun key(provider: String, titleId: String, season: Int): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$provider\u0000$titleId\u0000$season".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun load(context: Context, key: String): Pair<Int,String>? {
        val value=context.getSharedPreferences("skip_matches",Context.MODE_PRIVATE).getString(key,null) ?: return null
        val id=value.substringBefore('|').toIntOrNull()?.takeIf { it>0 } ?: return null
        return id to value.substringAfter('|',"")
    }
    fun save(context: Context, key: String, id: Int, title: String) {
        require(id>0)
        context.getSharedPreferences("skip_matches",Context.MODE_PRIVATE).edit().putString(key,"$id|$title").apply()
    }
    fun clear(context: Context, key: String) { context.getSharedPreferences("skip_matches",Context.MODE_PRIVATE).edit().remove(key).apply() }
}
