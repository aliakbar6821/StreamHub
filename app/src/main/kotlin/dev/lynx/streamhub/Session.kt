package dev.lynx.streamhub

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lynx.streamhub.api.Extension
import dev.lynx.streamhub.api.HomeSection
import dev.lynx.streamhub.api.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object Session {
    // item id -> extension that provided it
    val owner = ConcurrentHashMap<String, Extension>()

    var extensions by mutableStateOf<List<Extension>>(emptyList())
    var sections by mutableStateOf<List<HomeSection>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var loaded by mutableStateOf(false)

    // Search state (kept here so it survives opening a result and coming back)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<MediaItem>>(emptyList())
    var searched by mutableStateOf(false)
    val recent = mutableStateListOf<String>()

    suspend fun reload(context: Context) {
        if (loading) return
        loading = true
        try {
            val app = context.applicationContext
            val result = withContext(Dispatchers.IO) {
                val exts = ExtensionLoader.loadAll(app)
                val all = mutableListOf<HomeSection>()
                var err: String? = null
                for (e in exts) {
                    try {
                        val s = e.getHome()
                        s.forEach { sec -> sec.items.forEach { owner[it.id] = e } }
                        all.addAll(s)
                    } catch (t: Throwable) {
                        err = "${e.name}: ${t.message}"
                    }
                }
                Triple(exts, all, err)
            }
            extensions = result.first
            sections = result.second
            error = result.third
            loaded = true
        } finally {
            loading = false
        }
    }
}
