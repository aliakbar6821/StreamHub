package dev.lynx.streamhub

import org.json.JSONObject
import kotlin.math.abs

data class SkipSegment(val kind: String, val startMs: Long, val endMs: Long) {
    val label: String get() = if (kind == "op") "intro" else "outro"
    val key: String get() = "$kind:$startMs:$endMs"
    fun contains(position: Long): Boolean = position >= startMs && position < endMs
}

/** No proportional stretching: a different edit must not inherit another cut's timings. */
object SkipSegments {
    fun valid(segment: SkipSegment, durationMs: Long): Boolean =
        segment.kind in setOf("op", "ed") && durationMs > 0 && segment.startMs >= 0 &&
            segment.endMs > segment.startMs && segment.endMs <= durationMs &&
            segment.endMs - segment.startMs >= 1000L

    fun parse(response: JSONObject, durationMs: Long): List<SkipSegment> {
        if (!response.optBoolean("found", false)) return emptyList()
        val rows = response.optJSONArray("results") ?: return emptyList()
        val result = mutableListOf<SkipSegment>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val interval = row.optJSONObject("interval") ?: continue
            val length = row.optDouble("episodeLength", Double.NaN)
            val start = interval.optDouble("startTime", Double.NaN)
            val end = interval.optDouble("endTime", Double.NaN)
            // Conservative compatibility check; never shift credits over unseen scenes.
            if (!length.isFinite() || !start.isFinite() || !end.isFinite() || start < 0 || end <= start ||
                abs(length * 1000.0 - durationMs) > 3000.0) continue
            val segment = SkipSegment(row.optString("skipType"), (start * 1000).toLong(), (end * 1000).toLong())
            if (valid(segment, durationMs)) result.add(segment)
        }
        // Conflicting community timings are not a safe match.
        return result.distinct().groupBy { it.kind }.values.mapNotNull { choices ->
            choices.singleOrNull()
        }.sortedBy { it.startMs }
    }

    fun active(segments: List<SkipSegment>, positionMs: Long): SkipSegment? =
        segments.filter { it.contains(positionMs) }.singleOrNull()
}

/** One prompt per segment per playback/source; elapsed real time, not video seconds. */
class SkipPromptGate {
    private val seen = mutableSetOf<String>()
    private var current: SkipSegment? = null
    private var deadline = 0L
    fun update(segments: List<SkipSegment>, position: Long, eligible: Boolean, nowMs: Long): SkipSegment? {
        val active = if (eligible) SkipSegments.active(segments, position) else null
        if (active == null) { current = null; return null }
        if (current?.key != active.key) {
            current = null
            if (seen.add(active.key)) { current = active; deadline = nowMs + 3000L }
        }
        if (nowMs >= deadline) current = null
        return current
    }
    fun allowPreview(key: String) { seen.remove(key); current = null }
    fun dismiss() { current = null }
}
