package dev.lynx.streamhub

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import dev.lynx.streamhub.api.Stream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import kotlin.coroutines.resume

/** Source labels are discovery hints, never proof of audio, video or a language track. */
@UnstableApi
object VerifiedStreams {
    data class Audio(val language: String?, val codec: String?, val supported: Boolean)
    data class Result(val audio: List<Audio>, val heights: List<Int>, val videoSupported: Boolean,
        val reason: String = "") {
        val complete: Boolean get() = videoSupported && audio.any { it.supported }
    }
    private data class Entry(val at: Long, val result: Result)
    private val cache = linkedMapOf<String,Entry>()
    private val probeGate = kotlinx.coroutines.sync.Mutex()
    private fun key(stream: Stream): String = MessageDigest.getInstance("SHA-256")
        .digest((stream.url + "\u0000" + stream.headers.toSortedMap().entries.joinToString("\u0000")).toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    @Synchronized fun cached(stream: Stream): Result? {
        val entry=cache[key(stream)] ?: return null
        val ttl=if(entry.result.videoSupported || entry.result.audio.any { it.supported }) 300_000L else 30_000L
        return entry.result.takeIf { System.currentTimeMillis()-entry.at in 0..ttl }
    }
    @Synchronized fun invalidate(stream: Stream) { cache.remove(key(stream)) }
    @Synchronized private fun save(stream: Stream, result: Result) {
        cache[key(stream)]=Entry(System.currentTimeMillis(),result)
        while(cache.size>64) cache.remove(cache.keys.first())
    }
    suspend fun inspect(context: Context, stream: Stream): Result = probeGate.withLock {
        withContext(Dispatchers.Main.immediate) {
        cached(stream)?.let { return@withContext it }
        // Prepared but NEVER played: no focus request and zero player volume.
        val player=ExoPlayer.Builder(context.applicationContext)
            .setRenderersFactory(androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500,1500,250,250).build())
            .build()
        player.playWhenReady=false; player.volume=0f
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(),false)
        try {
            val result=withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine<Result> { continuation ->
                    val listener=object:Player.Listener {
                        override fun onPlaybackStateChanged(state:Int) {
                            if(state!=Player.STATE_READY || !continuation.isActive) return
                            val groups=player.currentTracks.groups
                            val audio=groups.filter { it.type==C.TRACK_TYPE_AUDIO }.flatMap { group ->
                                (0 until group.length).map { i -> val f=group.getTrackFormat(i)
                                    Audio(f.language?.takeUnless { it=="und" },f.sampleMimeType,group.isTrackSupported(i)) }
                            }.distinct()
                            val video=groups.filter { it.type==C.TRACK_TYPE_VIDEO }
                            val heights=video.flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }
                                .map { group.getTrackFormat(it).height }.filter { it>0 } }.distinct().sortedDescending()
                            val supported=video.any { group -> (0 until group.length).any { group.isTrackSupported(it) } }
                            val reason=when {
                                !supported -> "No supported video track"
                                audio.isEmpty() -> "Video-only link: no audio track. Use the complete/adaptive source."
                                audio.none { it.supported } -> "Audio exists but this device cannot decode the reported tracks."
                                else -> "Audio and video verified"
                            }
                            continuation.resume(Result(audio,heights,supported,reason))
                        }
                        override fun onPlayerError(error:PlaybackException) {
                            if(continuation.isActive) continuation.resume(Result(emptyList(),emptyList(),false,
                                "Could not verify source: ${error.errorCodeName}"))
                        }
                    }
                    player.addListener(listener)
                    continuation.invokeOnCancellation { /* player is released in finally on Main */ }
                    player.setMediaSource(PlaybackSources.create(context,MediaItem.fromUri(stream.url),stream))
                    player.prepare()
                }
            } ?: Result(emptyList(),emptyList(),false,"Source verification timed out; retry source lookup.")
            save(stream,result); result
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) {
            Result(emptyList(),emptyList(),false,"Source verification failed; retry source lookup.")
        } finally { player.release() }
    }
    }
}
