package dev.lynx.streamhub

import dev.lynx.streamhub.api.Stream
import androidx.media3.common.util.UnstableApi

/** A playable file is an option only after audio AND video have been verified. */
@UnstableApi
object PlaybackChoices {
    // Alternative discovery must not reset a healthy source or override an explicit quality lock.
    fun adoptDiscovered(currentVerified:Boolean,candidateVerified:Boolean,retainsQuality:Boolean):Boolean =
        !currentVerified && candidateVerified && retainsQuality

    data class Choice(val index: Int, val language: String, val heights: List<Int>)
    fun catalog(streams: List<Stream>, verified: Map<String, VerifiedStreams.Result>): List<Choice> =
        streams.flatMapIndexed { index, stream ->
            val result=verified[stream.url]
            if(result?.complete != true) emptyList() else {
                val languages=result.audio.filter { it.supported }.map {
                    audioLanguageCode(it.language) ?: streamChoice(stream).language ?: ProviderPlayback.video(stream.url).audioVariant ?: "original"
                }.distinct()
                languages.map { Choice(index,it,result.heights) }
            }
        }
    fun choose(choices: List<Choice>, language: String, height: Int, current: Int): Choice? =
        choices.filter { it.language==language && (height<=0 || height in it.heights) }
            .sortedWith(compareBy<Choice> { if(it.index==current)0 else 1 }
                .thenByDescending { it.heights.size }).firstOrNull()
}
