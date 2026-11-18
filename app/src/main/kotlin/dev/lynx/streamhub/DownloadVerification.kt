package dev.lynx.streamhub

import android.content.Context
import androidx.media3.common.util.UnstableApi
import dev.lynx.streamhub.api.Stream

/** Offline options must be self-contained and verified, not merely labelled with a language. */
@UnstableApi
object DownloadVerification {
    suspend fun options(context:Context, stream:Stream):Pair<List<Stream>,String?> {
        if(ProviderPlayback.video(stream.url).audio.isNotEmpty()) return emptyList<Stream>() to
            "Separate-audio playback source: one offline file requires muxing. No silent video-only download will be queued."
        val (variants,warning)=downloadVariants(stream)
        val output=mutableListOf<Stream>()
        var failure=warning
        for(variant in variants) {
            val result=VerifiedStreams.inspect(context,variant)
            if(!result.complete) { failure=result.reason;continue }
            val heights=result.heights
            // Downloader follows a single rendition. Unexpanded adaptive masters are not a fixed-quality download.
            if(heights.size!=1) {failure="Choose a verified self-contained rendition for offline download.";continue}
            val labels=result.audio.filter { it.supported }.map { audioLanguageCode(it.language) ?: "original" }.distinct()
            labels.forEach { code ->
                val clean=variant.quality.orEmpty()
                    .replace(Regex("(?i)\\([^()]*audio\\)"),"")
                    .replace(Regex("(?i)\\b[0-9]{3,4}p\\b"),"").trim()
                val name=if(code=="original") "Original" else audioLanguageName(code) ?: "Original"
                output.add(variant.copy(quality="$clean ${heights.single()}p ($name Audio)".trim()))
            }
        }
        return output.distinctBy { it.url to streamChoice(it).audioKey } to failure
    }
}
