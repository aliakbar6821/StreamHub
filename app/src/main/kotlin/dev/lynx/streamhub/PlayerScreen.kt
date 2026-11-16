package dev.lynx.streamhub

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(req: PlayRequest, onBack: () -> Unit) {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<ExoPlayer>(1) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun saveProgress() {
        val p = holder[0] ?: return
        val pos = p.currentPosition
        val dur = if (p.duration > 0) p.duration else 0L
        if (pos <= 0L) return
        val extId = req.ext?.id ?: Store.extIdFor(req.item.id) ?: ""
        Store.record(req.item, extId, req.episode, pos, dur)
    }

    LaunchedEffect(req.episode.id) {
        try {
            val local = req.localPath
            val p: ExoPlayer
            if (local != null) {
                p = ExoPlayer.Builder(context).build()
                p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(local))))
            } else {
                val ext = req.ext ?: throw IllegalStateException("Extension not found. Go back and reload.")
                val streams = withContext(Dispatchers.IO) { ext.getStreams(req.episode.id) }
                val s = streams.firstOrNull() ?: throw IllegalStateException("No streams found")
                val factory = DefaultHttpDataSource.Factory()
                    .setUserAgent("StreamHub/0.1")
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(s.headers)
                p = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                    .build()
                p.setMediaItem(MediaItem.fromUri(s.url))
            }
            if (req.startMs > 5000L) p.seekTo(req.startMs - 3000L)
            p.prepare()
            p.playWhenReady = true
            holder[0] = p
            player = p
        } catch (t: Throwable) {
            error = t.message ?: "Playback failed"
        }
    }

    LaunchedEffect(player) {
        if (player != null) {
            while (true) {
                delay(5000)
                saveProgress()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            saveProgress()
            holder[0]?.release()
            holder[0] = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it) },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) { Text("< Back", color = Color.White) }
        error?.let {
            Text(it, color = Color.Red, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
    }
}
