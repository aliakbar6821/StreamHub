package dev.lynx.streamhub

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import dev.lynx.streamhub.api.Stream

@UnstableApi
object PlaybackSources {
    private fun factory(context: Context, headers: Map<String,String>): DefaultMediaSourceFactory {
        val ua=headers.entries.firstOrNull { it.key.equals("User-Agent",true) }?.value ?: Downloader.UA
        val http=DefaultHttpDataSource.Factory().setUserAgent(ua).setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000).setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent",true) })
        val cache=androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(PlaybackCache.get(context)).setUpstreamDataSourceFactory(DefaultDataSource.Factory(context,http))
            .setCacheKeyFactory { spec ->
                java.security.MessageDigest.getInstance("SHA-256").digest(
                    (spec.uri.toString() + "\u0000" + headers.toSortedMap().toString()).toByteArray()
                ).joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultMediaSourceFactory(cache)
    }
    fun create(context: Context, item: MediaItem, stream: Stream): MediaSource {
        val info=ProviderPlayback.video(stream.url)
        val videoItem=if (item.localConfiguration?.mimeType==null && info.mime!=null)
            item.buildUpon().setMimeType(info.mime).build() else item
        val video=factory(context,stream.headers).createMediaSource(videoItem)
        if(info.audio.isEmpty()) return video
        val sources=mutableListOf(video)
        info.audio.forEach { audio ->
            val source=factory(context,audio.headers).createMediaSource(MediaItem.fromUri(audio.url))
            sources.add(FilteringMediaSource(source, C.TRACK_TYPE_AUDIO))
        }
        return MergingMediaSource(true, true, *sources.toTypedArray())
    }
}
