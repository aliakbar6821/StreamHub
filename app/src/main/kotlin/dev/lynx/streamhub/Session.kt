package dev.lynx.streamhub

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lynx.streamhub.api.Extension
import dev.lynx.streamhub.api.HomeSection
import dev.lynx.streamhub.api.MediaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /** Installation must not wait for every source's Home requests. */
    suspend fun refreshRegistry(context: Context) {
        val cs = withContext(Dispatchers.IO) { CsHost.extensions(context.applicationContext) }
        extensions = extensions.filterNot { it.id.startsWith("cs3:") } + cs
        loaded = false
    }

    suspend fun reload(context: Context) {
        if (loading) return
        loading = true
        try {
            val app = context.applicationContext
            val result = withContext(Dispatchers.IO) {
                val exts = ExtensionLoader.loadAll(app)
                val all = mutableListOf<HomeSection>()
                var err: String? = null
                val gate = Semaphore(3)
                val feeds = coroutineScope {
                    exts.map { e -> async {
                        gate.withPermit {
                            try {
                                val sections = withTimeoutOrNull(25_000L) { e.getHome() }
                                if (sections == null) e to Result.failure<List<HomeSection>>(
                                    IllegalStateException("Home request timed out")
                                ) else e to Result.success(sections)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (t: Exception) {
                                e to Result.failure<List<HomeSection>>(t)
                            }
                        }
                    } }.awaitAll()
                }
                for ((e, feed) in feeds) {
                    feed.onSuccess { sections ->
                        sections.forEach { sec -> sec.items.forEach { owner[it.id] = e } }
                        all.addAll(sections)
                    }.onFailure { err = "${e.name}: ${it.message}" }
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
