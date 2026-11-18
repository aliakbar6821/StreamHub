package dev.lynx.streamhub

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object PlaybackCache {
    @Volatile private var instance: SimpleCache?=null
    @Synchronized fun get(context:Context):SimpleCache = instance ?: SimpleCache(
        File(context.applicationContext.cacheDir,"verified_playback_v1"),
        LeastRecentlyUsedCacheEvictor(128L*1024*1024),
        StandaloneDatabaseProvider(context.applicationContext)
    ).also { instance=it }
}
