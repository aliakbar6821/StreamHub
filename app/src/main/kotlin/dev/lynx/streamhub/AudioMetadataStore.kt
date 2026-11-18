package dev.lynx.streamhub

import android.content.Context
import android.content.SharedPreferences
import dev.lynx.streamhub.api.Stream
import java.security.MessageDigest

/** Bounded private metadata, keyed by provider + episode (+ video). No signed URLs are persisted. */
object AudioMetadataStore {
    private var prefs: SharedPreferences? = null
    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("audio_catalog_v1", Context.MODE_PRIVATE)
    }
    private fun key(provider: String, episode: String, url: String? = null): String {
        val parts = listOf(provider, episode, url.orEmpty())
        val input = parts.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Synchronized
    private fun put(key: String, code: String) {
        val p = prefs ?: return
        val value = "$code|${System.currentTimeMillis()}"
        val existing = p.all
        val stale = if (existing.size >= 512 && !existing.containsKey(key))
            existing.entries.sortedBy { (it.value as? String)?.substringAfter('|')?.toLongOrNull() ?: 0L }
                .take(64).map { it.key } else emptyList()
        val edit = p.edit().putString(key, value)
        stale.forEach { edit.remove(it) }
        edit.apply()
    }
    private fun get(key: String): String? {
        val value = prefs?.getString(key, null) ?: return null
        val stamp = value.substringAfter('|', "0").toLongOrNull() ?: return null
        if (System.currentTimeMillis() - stamp !in 0L..86_400_000L) return null
        return value.substringBefore('|').takeIf { it == "original" || audioLanguageCode(it) != null }
    }
    fun rememberEpisode(provider: String, episode: String, code: String) = put(key(provider, episode), code)
    fun enrich(providerId: String, providerName: String, episode: String, stream: Stream): Stream {
        val existing = streamChoice(stream).language
        val code = existing ?: providerAudioCode(providerName, stream)
            ?: get(key(providerId, episode, stream.url))
            ?: providerAudioCode(providerName, stream, get(key(providerId, episode))) ?: return stream
        put(key(providerId, episode, stream.url), code)
        if (existing != null) return stream
        val label = if (code == "original") "Original" else audioLanguageName(code) ?: return stream
        return stream.copy(quality = listOfNotNull(stream.quality, "($label Audio)").joinToString(" "))
    }
}
