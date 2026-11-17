package dev.lynx.streamhub

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem as M3Item
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val Accent = Color(0xFFB388FF)
private val PanelBg = Color(0xF2141418)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private data class TrackOpt(
    val label: String,
    val group: Tracks.Group,
    val index: Int,
    val selected: Boolean,
    val lang: String?,
    val height: Int,
)

private fun langName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    val n = Locale.forLanguageTag(code).displayLanguage
    if (n.isNullOrBlank()) return code
    return n.replaceFirstChar { it.uppercase() }
}

private fun trackOpts(tracks: Tracks, type: Int): List<TrackOpt> {
    val out = mutableListOf<TrackOpt>()
    for (g in tracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val lab: String? = f.label
            val name = langName(f.language)
            val label = when {
                type == C.TRACK_TYPE_VIDEO ->
                    if (f.height > 0) "${f.height}p" else "Video ${out.size + 1}"
                !lab.isNullOrBlank() && name != null && !lab.contains(name, true) -> "$name ($lab)"
                !lab.isNullOrBlank() -> lab
                name != null -> name
                else -> "Track ${out.size + 1}"
            }
            out.add(TrackOpt(label, g, i, g.isTrackSelected(i), f.language, f.height))
        }
    }
    return out
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun RoundBtn(size: Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PauseIcon() {
    Row {
        Box(Modifier.size(7.dp, 26.dp).background(Color.White))
        Spacer(Modifier.width(7.dp))
        Box(Modifier.size(7.dp, 26.dp).background(Color.White))
    }
}

@Composable
private fun ChipBtn(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1) }
}

@Composable
private fun OptionRow(
    title: String,
    value: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (selected) Accent else Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null) Text(value, color = Color(0xFFAAAAAA), fontSize = 12.sp)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Accent)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(req: PlayRequest, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    PlayerPrefs.init(context)

    var ep by remember { mutableStateOf(req.episode) }
    var pendingStart by remember { mutableLongStateOf(req.startMs) }
    var sourceIdx by remember { mutableIntStateOf(0) }
    var streams by remember { mutableStateOf<List<Stream>>(emptyList()) }
    var streamsFor by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var hlsIdx by remember { mutableIntStateOf(-1) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var srcInfo by remember { mutableStateOf("") }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var dubOn by remember { mutableStateOf(PlayerPrefs.preferDub) }
    val dubList = (req.ext as? CsExtension)?.dubEpisodes(req.item.id) ?: emptyList()
    val shownEps = if (dubOn && dubList.isNotEmpty()) dubList else episodes
    var full by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("") }
    var poke by remember { mutableIntStateOf(0) }
    var orient by remember { mutableIntStateOf(1) }
    var hud by remember { mutableStateOf<String?>(null) }
    var hudStamp by remember { mutableIntStateOf(0) }

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var qualityH by remember { mutableIntStateOf(-1) }
    var speed by remember { mutableFloatStateOf(PlayerPrefs.speed) }
    var resize by remember { mutableIntStateOf(PlayerPrefs.resize) }
    var subScale by remember { mutableFloatStateOf(PlayerPrefs.subScale) }
    var autoNext by remember { mutableStateOf(PlayerPrefs.autoNext) }
    var scrub by remember { mutableStateOf<Float?>(null) }

    val http = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent(Downloader.UA)
            .setConnectTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)
    }
    val exo = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
            .also { p ->
                var tp = p.trackSelectionParameters.buildUpon()
                if (PlayerPrefs.audioLang.isNotEmpty()) {
                    tp = tp.setPreferredAudioLanguage(PlayerPrefs.audioLang)
                }
                val sl = PlayerPrefs.subLang
                if (sl == "off") {
                    tp = tp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else if (sl.isNotEmpty()) {
                    tp = tp.setPreferredTextLanguage(sl)
                }
                p.trackSelectionParameters = tp.build()
                p.setPlaybackSpeed(PlayerPrefs.speed)
            }
    }
    val audioMgr = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val volAcc = remember { floatArrayOf(-1f) }

    // ---------- helpers ----------
    fun showHud(t: String) {
        hud = t
        hudStamp++
    }

    fun saveProgress() {
        if (exo.currentMediaItem == null) return
        val pos = exo.currentPosition
        val dur = if (exo.duration > 0) exo.duration else 0L
        if (pos <= 0L) return
        val extId = req.ext?.id ?: Store.extIdFor(req.item.id) ?: ""
        Store.record(req.item, extId, ep, pos, dur)
    }

    fun switchTo(e: Episode) {
        saveProgress()
        ep = e
        sourceIdx = 0
        pendingStart = 0L
        ended = false
        page = ""
        error = null
        posMs = 0L
    }

    val dubOf = { s: Stream -> s.quality?.contains("dub", true) == true }
    val mixedAudio = streams.any { dubOf(it) } && streams.any { !dubOf(it) }
    val hasDubChoice = dubList.isNotEmpty() || mixedAudio

    fun setDub(want: Boolean) {
        if (want == dubOn) {
            page = ""
            return
        }
        val idx = shownEps.indexOfFirst { it.id == ep.id }
        val pos = exo.currentPosition
        dubOn = want
        PlayerPrefs.preferDub = want
        if (dubList.isNotEmpty()) {
            val to = if (want) dubList else episodes
            val target = to.getOrNull(if (idx >= 0) idx else 0)
            if (target != null) {
                switchTo(target)
                pendingStart = pos
            }
        } else {
            val i = streams.indexOfFirst { dubOf(it) == want }
            if (i >= 0) {
                pendingStart = pos
                sourceIdx = i
            }
        }
        page = ""
    }

    fun seekBy(d: Long) {
        var t = (exo.currentPosition + d).coerceAtLeast(0L)
        if (exo.duration > 0) t = t.coerceAtMost(exo.duration)
        exo.seekTo(t)
        posMs = t
    }

    fun tweak(f: (TrackSelectionParameters.Builder) -> TrackSelectionParameters.Builder) {
        exo.trackSelectionParameters = f(exo.trackSelectionParameters.buildUpon()).build()
    }

    fun setQuality(h: Int) {
        qualityH = h
        if (h < 0) tweak { it.clearVideoSizeConstraints() }
        else tweak { it.setMaxVideoSize(Int.MAX_VALUE, h) }
    }

    fun pickAudio(o: TrackOpt) {
        tweak {
            var b = it.setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
            if (!o.lang.isNullOrBlank()) b = b.setPreferredAudioLanguage(o.lang)
            b
        }
        PlayerPrefs.audioLang = o.lang ?: ""
        showHud("Audio: ${o.label}")
    }

    fun pickSub(o: TrackOpt?) {
        if (o == null) {
            tweak { it.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) }
            PlayerPrefs.subLang = "off"
            showHud("Subtitles off")
        } else {
            tweak {
                var b = it.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                if (!o.lang.isNullOrBlank()) b = b.setPreferredTextLanguage(o.lang)
                b
            }
            PlayerPrefs.subLang = o.lang ?: ""
            showHud("Subtitles: ${o.label}")
        }
    }

    fun adjustBrightness(frac: Float) {
        val w = activity?.window ?: return
        val lp = w.attributes
        var b = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
        b = (b + frac).coerceIn(0.02f, 1f)
        lp.screenBrightness = b
        w.attributes = lp
        showHud("Brightness ${(b * 100).toInt()}%")
    }

    fun adjustVolume(frac: Float) {
        val max = audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (volAcc[0] < 0f) volAcc[0] = audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        volAcc[0] = (volAcc[0] + frac * max * 1.2f).coerceIn(0f, max.toFloat())
        audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, volAcc[0].toInt(), 0)
        showHud("Volume ${(volAcc[0] * 100 / max).toInt()}%")
    }

    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val cur = exo.currentMediaItem
        if (uri != null && cur != null) {
            var name = ""
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) name = c.getString(i) ?: ""
                    }
                }
            }
            val lower = name.lowercase()
            val mime = when {
                lower.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                lower.endsWith(".ass") || lower.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                lower.endsWith(".ttml") || lower.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            val sub = M3Item.SubtitleConfiguration.Builder(uri)
                .setMimeType(mime)
                .setLabel(name.ifBlank { "External" })
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            val pos = exo.currentPosition
            tweak { it.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false) }
            exo.setMediaItem(cur.buildUpon().setSubtitleConfigurations(listOf(sub)).build(), pos)
            exo.prepare()
            exo.playWhenReady = true
            showHud("Subtitle loaded")
        }
    }

    // ---------- effects ----------
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) { isPlaying = isPlayingNow }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_READY && exo.duration > 0) durMs = exo.duration
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlayerError(e: PlaybackException) {
                val m = (e.errorCodeName + ": " + (e.message ?: "")).trim()
                val u = streams.getOrNull(sourceIdx)?.url ?: ""
                val parse = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                if (parse && hlsIdx != sourceIdx && !u.contains("m3u8", true)) {
                    hlsIdx = sourceIdx
                    attempt++
                } else if (sourceIdx + 1 < streams.size) {
                    srcInfo = "Server ${sourceIdx + 1} failed. Trying next..."
                    sourceIdx++
                } else {
                    error = "Playback failed. Tap Retry or pick another server."
                }
            }
        }
        exo.addListener(l)
        onDispose {
            saveProgress()
            exo.removeListener(l)
            exo.release()
        }
    }

    DisposableEffect(Unit) {
        val act = activity
        if (act != null) {
            val w = act.window
            val c = WindowCompat.getInsetsController(w, w.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (act != null) {
                val w = act.window
                WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val lp = w.attributes
                lp.screenBrightness = -1f
                w.attributes = lp
            }
        }
    }

    LaunchedEffect(full) {
        val act = activity
        if (act != null) {
            val ctl = WindowCompat.getInsetsController(act.window, act.window.decorView)
            if (full) ctl.hide(WindowInsetsCompat.Type.systemBars()) else ctl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(orient) {
        activity?.requestedOrientation = when (orient) {
            0 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    LaunchedEffect(Unit) {
        val e = req.ext
        episodes = if (e != null) {
            try {
                withContext(Dispatchers.IO) { e.getDetails(req.item.id).episodes }
            } catch (t: Throwable) {
                emptyList()
            }
        } else {
            Store.downloads
                .filter { it.item.id == req.item.id }
                .sortedBy { it.episodeNumber }
                .map { Episode(id = it.episodeId, number = it.episodeNumber, title = it.episodeTitle) }
        }
    }

    // 1) collect sources for this episode (first one arrives early, others keep coming)
    LaunchedEffect(ep.id, reloadKey) {
        streams = emptyList()
        streamsFor = ep.id
        hlsIdx = -1
        if (req.localPath != null) return@LaunchedEffect
        val e = req.ext
        if (e == null) {
            error = "Extension not found. Go back and reload."
            return@LaunchedEffect
        }
        val t0 = System.currentTimeMillis()
        val ticker = launch {
            while (streams.isEmpty()) {
                val secs = (System.currentTimeMillis() - t0) / 1000
                srcInfo = "Finding sources... ${secs}s" + (if (secs > 20) "\nSlow plugin. Go back and try another one if this keeps going." else "")
                delay(1000)
            }
        }
        try {
            if (e is CsExtension) {
                val ch = Channel<Stream>(Channel.UNLIMITED)
                launch {
                    try { e.streamsLive(ep.id) { ch.trySend(it) } } finally { ch.close() }
                }
                for (s in ch) {
                    if (streams.none { it.url == s.url }) streams = streams + s
                }
            } else {
                streams = withContext(Dispatchers.IO) { e.getStreams(ep.id) }
            }
            if (streams.isEmpty()) error = "Couldn't find a source for this episode. Tap Retry or try another episode."
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (streams.isEmpty()) error = "Couldn't load sources. Tap Retry."
        } finally {
            ticker.cancel()
        }
    }

    // 2) play the selected source; fall back to the next one on error or stall
    LaunchedEffect(ep.id, sourceIdx, attempt, streamsFor, streams.isEmpty()) {
        error = null
        try {
            val lp = req.localPath
            if (lp != null) {
                val path = Store.downloads.firstOrNull { it.episodeId == ep.id }?.path ?: lp
                exo.setMediaItem(M3Item.fromUri(Uri.fromFile(File(path))))
            } else {
                if (streamsFor != ep.id || streams.isEmpty()) return@LaunchedEffect
                val s = streams.getOrNull(sourceIdx) ?: throw IllegalStateException("No streams found")
                val ua = s.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                http.setUserAgent(ua ?: Downloader.UA)
                http.setDefaultRequestProperties(s.headers.filterKeys { !it.equals("User-Agent", true) })
                val b = M3Item.Builder().setUri(s.url)
                if (hlsIdx == sourceIdx || s.url.contains("m3u8", true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
                srcInfo = "Source ${sourceIdx + 1} of ${streams.size}" +
                    (s.quality?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "")
                exo.setMediaItem(b.build())
            }
            if (pendingStart > 5000L) exo.seekTo(pendingStart - 3000L)
            pendingStart = 0L
            exo.prepare()
            exo.playWhenReady = true
            if (lp == null) {
                delay(30000)
                if (exo.playbackState != Player.STATE_READY && sourceIdx + 1 < streams.size) {
                    showHud("Source too slow, trying next")
                    sourceIdx++
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            error = "Playback failed. Tap Retry."
        }
    }

    LaunchedEffect(exo) {
        var n = 0
        while (true) {
            delay(500)
            posMs = exo.currentPosition.coerceAtLeast(0L)
            val d = exo.duration
            if (d > 0) durMs = d
            n++
            if (n % 10 == 0) saveProgress()
        }
    }

    LaunchedEffect(speed) { exo.setPlaybackSpeed(speed) }

    LaunchedEffect(hudStamp) {
        if (hud != null) {
            delay(900)
            hud = null
        }
    }

    LaunchedEffect(controls, isPlaying, page, poke, locked) {
        if (controls && isPlaying && page.isEmpty()) {
            delay(3500)
            controls = false
        }
    }

    val curIdx = shownEps.indexOfFirst { it.id == ep.id }
    val prevEp = if (curIdx > 0) shownEps[curIdx - 1] else null
    val nextEp = if (curIdx >= 0 && curIdx < shownEps.size - 1) shownEps[curIdx + 1] else null

    LaunchedEffect(ended) {
        if (ended && autoNext && nextEp != null) {
            delay(3000)
            switchTo(nextEp)
        }
    }

    BackHandler(enabled = full && page.isEmpty()) {
        full = false
        orient = 1
    }
    BackHandler(enabled = page.isNotEmpty()) { page = "" }

    val audioOpts = trackOpts(tracks, C.TRACK_TYPE_AUDIO)
    val textOpts = trackOpts(tracks, C.TRACK_TYPE_TEXT)
    val videoOpts = trackOpts(tracks, C.TRACK_TYPE_VIDEO)
    val qualities = videoOpts.map { it.height }.filter { it > 0 }.distinct().sortedDescending()
    val textOn = textOpts.any { it.selected }

    val epLabel = "E${ep.number.toInt()}" +
        (ep.title?.takeIf { it.isNotBlank() }?.let { " \u2022 $it" } ?: "")

    // ---------- UI ----------
    Box(Modifier.fillMaxSize().background(if (full) Color.Black else MaterialTheme.colorScheme.background)) {
    Column(Modifier.fillMaxSize()) {
    Box(
        modifier = (if (full) Modifier.fillMaxSize()
            else Modifier.fillMaxWidth().statusBarsPadding().background(Color.Black).aspectRatio(16f / 9f)),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    player = exo
                }
            },
            update = { v ->
                v.resizeMode = resize
                v.subtitleView?.setFractionalTextSize(0.0533f * subScale)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // gesture layer
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = {
                            controls = !controls
                            page = ""
                        },
                        onDoubleTap = { o ->
                            if (!locked) {
                                val left = o.x < size.width / 2
                                seekBy(if (left) -10_000L else 10_000L)
                                showHud(if (left) "-10s" else "+10s")
                            }
                        },
                    )
                }
                .pointerInput(locked) {
                    if (!locked) {
                        var leftSide = true
                        detectVerticalDragGestures(
                            onDragStart = { o ->
                                leftSide = o.x < size.width / 2
                                volAcc[0] = -1f
                            },
                            onVerticalDrag = { _, dy ->
                                val frac = -dy / size.height
                                if (leftSide) adjustBrightness(frac) else adjustVolume(frac)
                            },
                        )
                    }
                },
        )

        // controls
        AnimatedVisibility(
            visible = controls && !locked && page.isEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x55000000))) {
                // top bar
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (full) { full = false; orient = 1 } else onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            req.item.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            epLabel,
                            color = Color(0xFFCCCCCC),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ChipBtn(if (full) "Exit" else "Full") {
                        full = !full
                        orient = if (full) 0 else 1
                        poke++
                    }
                    if (full) ChipBtn("Rotate") {
                        orient = (orient + 1) % 3
                        showHud(listOf("Landscape", "Portrait", "Auto rotate")[orient])
                        poke++
                    }
                    IconButton(onClick = {
                        locked = true
                        controls = false
                    }) { Icon(Icons.Filled.Lock, contentDescription = "Lock", tint = Color.White) }
                    IconButton(onClick = { page = "main" }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                }

                // center
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    RoundBtn(52.dp, { seekBy(-10_000L); poke++ }) {
                        Text("-10", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    RoundBtn(72.dp, {
                        if (ended) exo.seekTo(0L)
                        if (exo.isPlaying) exo.pause() else exo.play()
                        poke++
                    }) {
                        if (isPlaying) PauseIcon()
                        else Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                    RoundBtn(52.dp, { seekBy(10_000L); poke++ }) {
                        Text("+10", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                // bottom
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt((scrub ?: posMs.toFloat()).toLong()), color = Color.White, fontSize = 12.sp)
                        val maxV = durMs.coerceAtLeast(1L).toFloat()
                        Slider(
                            value = (scrub ?: posMs.toFloat()).coerceIn(0f, maxV),
                            onValueChange = {
                                scrub = it
                                poke++
                            },
                            onValueChangeFinished = {
                                scrub?.let {
                                    exo.seekTo(it.toLong())
                                    posMs = it.toLong()
                                }
                                scrub = null
                            },
                            valueRange = 0f..maxV,
                            colors = SliderDefaults.colors(
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                                inactiveTrackColor = Color(0x55FFFFFF),
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(fmt(durMs), color = Color.White, fontSize = 12.sp)
                    }
                    if (full) Row(Modifier.horizontalScroll(rememberScrollState())) {
                        if (prevEp != null) ChipBtn("Prev") { switchTo(prevEp) }
                        if (nextEp != null) ChipBtn("Next") { switchTo(nextEp) }
                        ChipBtn("Skip +85s") { seekBy(85_000L); showHud("+85s"); poke++ }
                        ChipBtn(if (speed == 1f) "Speed" else "${speed}x") { page = "speed" }
                        ChipBtn("Audio") { page = "audio" }
                        ChipBtn(if (textOn) "Subs \u2713" else "Subs") { page = "subs" }
                        if (episodes.isNotEmpty()) ChipBtn("Episodes") { page = "episodes" }
                        ChipBtn(listOf("Fit", "Fill", "Zoom")[when (resize) {
                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> 1
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> 2
                            else -> 0
                        }]) {
                            resize = when (resize) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                            PlayerPrefs.resize = resize
                            poke++
                        }
                    }
                }
            }
        }

        // unlock button
        AnimatedVisibility(
            visible = locked && controls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).padding(24.dp),
        ) {
            RoundBtn(52.dp, {
                locked = false
                controls = true
            }) { Icon(Icons.Filled.Lock, contentDescription = "Unlock", tint = Accent) }
        }

        if ((buffering || (streams.isEmpty() && req.localPath == null)) && error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                if (srcInfo.isNotEmpty() && req.localPath == null) {
                    Spacer(Modifier.height(12.dp))
                    Text(srcInfo, color = Color.White, fontSize = 13.sp)
                }
            }
        }

        hud?.let {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(it, color = Color.White) }
        }

        error?.let {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(it, color = Color(0xFFFF6B6B))
                TextButton(onClick = {
                    pendingStart = exo.currentPosition
                    error = null
                    if (streams.isEmpty()) {
                        reloadKey++
                    } else {
                        sourceIdx = if (sourceIdx + 1 < streams.size) sourceIdx + 1 else 0
                        attempt++
                    }
                }) { Text("Retry", color = Accent) }
            }
        }

        // up-next card
        if (ended && autoNext && nextEp != null) {
            Surface(
                color = Color(0xE6141418),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Up next: E${nextEp.number.toInt()}", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Starting in 3s", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    Row {
                        TextButton(onClick = { switchTo(nextEp) }) { Text("Play now", color = Accent) }
                        TextButton(onClick = { ended = false }) { Text("Cancel", color = Color.White) }
                    }
                }
            }
        }

        }
        if (!full) {
            WatchInfo(
                modifier = Modifier.weight(1f),
                media = req.item,
                extId = req.ext?.id ?: Store.extIdFor(req.item.id) ?: "",
                ep = ep,
                episodes = shownEps,
                durMs = durMs,
                audioLabel = if (hasDubChoice) (if (dubOn) "English" else "Native")
                    else (audioOpts.firstOrNull { it.selected }?.label ?: "Default"),
                serverLabel = if (streams.isEmpty()) "Server" else "Server ${sourceIdx + 1}",
                subsOn = textOn,
                speedLabel = if (speed == 1f) "Speed" else "${speed}x",
                onPage = { page = it },
                onEpisode = { switchTo(it) },
            )
        }
        }

        // settings panel
        if (page.isNotEmpty()) {
            val none = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = none, indication = null) { page = "" },
            ) {
                Surface(
                    color = PanelBg,
                    shape = if (full) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    modifier = (if (full) Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.6f).widthIn(max = 360.dp)
                        else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().fillMaxHeight(0.6f))
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (page != "main") {
                                IconButton(onClick = { page = "main" }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                                }
                            }
                            Text(
                                when (page) {
                                    "audio" -> "Audio"
                                    "subs" -> "Subtitles"
                                    "quality" -> "Quality"
                                    "speed" -> "Playback speed"
                                    "source" -> "Source"
                                    "episodes" -> "Episodes"
                                    else -> "Settings"
                                },
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            IconButton(onClick = { page = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }
                        val listState = rememberLazyListState()
                        LaunchedEffect(page) {
                            if (page == "episodes" && curIdx > 0) listState.scrollToItem(curIdx)
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            when (page) {
                                "main" -> {
                                    item {
                                        OptionRow(
                                            "Audio",
                                            audioOpts.firstOrNull { it.selected }?.label
                                                ?: if (audioOpts.isEmpty()) "Single track" else "Default",
                                        ) { page = "audio" }
                                    }
                                    item {
                                        OptionRow(
                                            "Subtitles",
                                            textOpts.firstOrNull { it.selected }?.label
                                                ?: if (textOpts.isEmpty()) "None in stream" else "Off",
                                        ) { page = "subs" }
                                    }
                                    if (qualities.size > 1) {
                                        item {
                                            OptionRow(
                                                "Quality",
                                                if (qualityH < 0) "Auto" else "${qualityH}p",
                                            ) { page = "quality" }
                                        }
                                    }
                                    item {
                                        OptionRow("Playback speed", if (speed == 1f) "Normal" else "${speed}x") {
                                            page = "speed"
                                        }
                                    }
                                    if (streams.size > 1) {
                                        item {
                                            OptionRow("Source", "Source ${sourceIdx + 1} of ${streams.size}") {
                                                page = "source"
                                            }
                                        }
                                    }
                                    if (episodes.isNotEmpty()) {
                                        item {
                                            OptionRow("Episodes", "${episodes.size} total") { page = "episodes" }
                                        }
                                    }
                                    item {
                                        OptionRow("Load subtitle file", "srt, vtt, ass") {
                                            page = ""
                                            subPicker.launch(arrayOf("*/*"))
                                        }
                                    }
                                    item {
                                        OptionRow("Subtitle size", "${(subScale * 100).toInt()}%") {
                                            val opts = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                                            val i = opts.indexOfFirst { it == subScale }
                                            subScale = opts[(i + 1) % opts.size]
                                            PlayerPrefs.subScale = subScale
                                        }
                                    }
                                    item {
                                        OptionRow("Autoplay next episode", if (autoNext) "On" else "Off") {
                                            autoNext = !autoNext
                                            PlayerPrefs.autoNext = autoNext
                                        }
                                    }
                                }

                                "audio" -> {
                                    if (hasDubChoice) {
                                        item { OptionRow("Native (Sub)", selected = !dubOn) { setDub(false) } }
                                        item { OptionRow("English (Dub)", selected = dubOn) { setDub(true) } }
                                    }
                                    if (audioOpts.isEmpty() && !hasDubChoice) {
                                        item { Text("This stream has a single audio track.", color = Color(0xFFAAAAAA), modifier = Modifier.padding(16.dp)) }
                                    }
                                    items(audioOpts) { o -> OptionRow(o.label, selected = o.selected) { pickAudio(o) } }
                                }

                                "subs" -> {
                                    item { OptionRow("Off", selected = !textOn) { pickSub(null) } }
                                    items(textOpts) { o -> OptionRow(o.label, selected = o.selected) { pickSub(o) } }
                                    item {
                                        OptionRow("Load from file\u2026") {
                                            page = ""
                                            subPicker.launch(arrayOf("*/*"))
                                        }
                                    }
                                }

                                "quality" -> {
                                    item { OptionRow("Auto", selected = qualityH < 0) { setQuality(-1) } }
                                    items(qualities) { h -> OptionRow("${h}p", selected = qualityH == h) { setQuality(h) } }
                                }

                                "speed" -> {
                                    items(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) { s ->
                                        OptionRow(if (s == 1f) "Normal" else "${s}x", selected = speed == s) {
                                            speed = s
                                            PlayerPrefs.speed = s
                                        }
                                    }
                                }

                                "source" -> {
                                    val vis = streams.indices.filter { !mixedAudio || dubOf(streams[it]) == dubOn }
                                    items(vis) { i ->
                                        OptionRow("Server ${i + 1}", value = streams.getOrNull(i)?.quality?.takeIf { it.isNotBlank() }, selected = sourceIdx == i) {
                                            pendingStart = exo.currentPosition
                                            sourceIdx = i
                                            page = ""
                                        }
                                    }
                                }

                                "episodes" -> {
                                    items(shownEps) { e ->
                                        OptionRow(
                                            "${e.number.toInt()}. ${e.title?.takeIf { it.isNotBlank() } ?: "Episode ${e.number.toInt()}"}",
                                            value = if (Store.isWatched(e.id)) "Watched" else null,
                                            selected = e.id == ep.id,
                                        ) { if (e.id != ep.id) switchTo(e) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
