package dev.lynx.streamhub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User markers belong to one episode and video URL, never blindly copied to other episodes. */
object SkipMarkerStore {
    private fun key(episode: String, source: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$episode\u0000$source".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun load(context: Context, episode: String, source: String, duration: Long): List<SkipSegment> = runCatching {
        val text = context.getSharedPreferences("skip_markers", Context.MODE_PRIVATE).getString(key(episode, source), null)
            ?: return@runCatching emptyList()
        val rows = JSONArray(text)
        (0 until rows.length()).mapNotNull { i -> rows.getJSONObject(i).let {
            val savedDuration = it.optLong("duration", -1L)
            if (savedDuration <= 0 || kotlin.math.abs(savedDuration - duration) > 3000L) return@let null
            SkipSegment(it.getString("kind"), it.getLong("start"), it.getLong("end"))
                .takeIf { segment -> SkipSegments.valid(segment, duration) }
        } }
    }.getOrDefault(emptyList())
    fun save(context: Context, episode: String, source: String, segments: List<SkipSegment>, duration: Long) {
        val rows = JSONArray()
        segments.forEach { rows.put(JSONObject().put("kind", it.kind).put("start", it.startMs).put("end", it.endMs).put("duration", duration)) }
        context.getSharedPreferences("skip_markers", Context.MODE_PRIVATE).edit().putString(key(episode, source), rows.toString()).apply()
    }
}
