package dev.lynx.streamhub

import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.TextFields

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
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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

private data class AudioAction(val label: String, val selected: Boolean, val run: () -> Unit)

private fun trackOpts(tracks: Tracks, type: Int): List<TrackOpt> {
    val out = mutableListOf<TrackOpt>()
    for (g in tracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val lab: String? = f.label?.trim()?.takeUnless {
                it.isBlank() ||
                    Regex("(?i)track\\s*\\d+").matches(it)
            }
            val name = langName(f.language)
            val label = when {
                type == C.TRACK_TYPE_VIDEO ->
                    if (f.height > 0) "${f.height}p" else "Video ${out.size + 1}"
                !lab.isNullOrBlank() && name != null && !lab.contains(name, true) -> "$name ($lab)"
                !lab.isNullOrBlank() -> lab
                name != null -> name
                else -> if (type == C.TRACK_TYPE_TEXT)
                    "Subtitle ${out.size + 1} · language not provided"
                else "Audio ${out.size + 1} · language not provided"
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
private fun OptionRow(
    title: String,
    value: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // SH_PATCH_02_OPTION_ROW
    // SH_PATCH_03_OPTION_THEME
    val colors = MaterialTheme.colorScheme
    val fg = if (selected) colors.onPrimary else colors.onSurface
    val icon = when (title) {
        "Quality" -> Icons.Filled.Tune
        "Audio track" -> Icons.Filled.Mic
        "Subtitles" -> Icons.Filled.ClosedCaption
        "Source" -> Icons.Filled.Storage
        "Download" -> Icons.Filled.Download
        "Playback speed" -> Icons.Filled.Speed
        "Audio output" -> Icons.Filled.Headphones
        "Episodes" -> Icons.Filled.PlaylistPlay
        "Subtitle style" -> Icons.Filled.TextFields
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.primary else colors.surface)
            .border(1.dp, if (selected) colors.primary else colors.outlineVariant,
                RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg,
                modifier = Modifier.size(25.dp))
        }
        Text(
            title,
            color = fg,
            fontSize = 17.sp,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Text(
                value,
                color = if (selected) colors.onPrimary.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                fontSize = 15.sp,
                modifier = Modifier.weight(0.85f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Selected",
                tint = colors.onPrimary, modifier = Modifier.size(22.dp))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    req: PlayRequest,
    onBack: () -> Unit,
    onOpen: (dev.lynx.streamhub.api.MediaItem) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    PlayerPrefs.init(context)

    var ep by remember { mutableStateOf(req.episode) }
    var pendingStart by remember { mutableLongStateOf(req.startMs) }
    var sourceIdx by remember { mutableIntStateOf(0) }
    var streams by remember { mutableStateOf<List<Stream>>(emptyList()) }
    var streamsFor by remember { mutableStateOf("") }
    var remoteSubs by remember {
        mutableStateOf<List<Pair<String, String>>>(emptyList())
    }
    val subtitleScope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var hlsIdx by remember { mutableIntStateOf(-1) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var srcInfo by remember { mutableStateOf("") }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var titleGenres by remember(req.item.id) { mutableStateOf(req.item.genres) }
    var related by remember(req.item.id) { mutableStateOf<List<dev.lynx.streamhub.api.MediaItem>>(emptyList()) }
    var relatedLoading by remember(req.item.id) { mutableStateOf(false) }
    var relatedLoaded by remember(req.item.id) { mutableStateOf(false) }
    var relatedError by remember(req.item.id) { mutableStateOf<String?>(null) }
    var relatedRefresh by remember(req.item.id) { mutableIntStateOf(0) }
    // The requested episode already identifies its source version.
    var dubOn by remember {
        mutableStateOf((req.ext as? CsExtension)?.dubEpisodes(req.item.id)
            ?.any { it.id == req.episode.id } == true)
    }
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
    var autoSkip by remember { mutableStateOf(PlayerPrefs.autoSkip) }
    var subFont by remember { mutableStateOf(PlayerPrefs.subFont) }
    var subBold by remember { mutableStateOf(PlayerPrefs.subBold) }
    var subPos by remember { mutableFloatStateOf(PlayerPrefs.subPos) }
    var skippedFor by remember { mutableStateOf("") }
    var showSkip by remember(ep.id) { mutableStateOf(false) }
    var skipShown by remember(ep.id) { mutableStateOf(false) }
    var dlSheet by remember { mutableStateOf(false) }
    var episodeDownload by remember { mutableStateOf<Episode?>(null) }
    var newestEpisodes by remember(req.item.id) { mutableStateOf(false) }
    var episodeRange by remember(req.item.id) { mutableIntStateOf(0) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var preferredAudioApplied by remember(ep.id, reloadKey) { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    var failedSourceUrls by remember(ep.id, reloadKey) { mutableStateOf<Set<String>>(emptySet()) }

    val lifecycle = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle
    val playbackIntent = remember(activity) {
        PlaybackIntent(lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true)
    }

    val http = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent(Downloader.UA)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
    }
    val exo = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
                .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(3)))
            .build()
            .also { p ->
                p.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                p.setHandleAudioBecomingNoisy(true)
                var tp = p.trackSelectionParameters.buildUpon()
                val audio = AppPrefs.audio(req.item.id)
                if (audio.isNotEmpty()) tp = tp.setPreferredAudioLanguage(audio)
                val sl = AppPrefs.subtitle(req.item.id)
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

    fun syncPlayback() {
        playbackIntent.pictureInPicture = PlayerWindowState.inPip
        exo.playWhenReady = playbackIntent.shouldPlay
    }
    fun requestPlayback(wantsPlay: Boolean) {
        playbackIntent.wantsPlay = wantsPlay
        syncPlayback()
    }

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

    val dubOf = { s: Stream -> sourceAudioVersion(s) }
    val mixedAudio = streams.any { dubOf(it) == true } && streams.any { dubOf(it) == false }
    val hasDubChoice = dubList.isNotEmpty() || mixedAudio

    fun setDub(want: Boolean) {
        if (want == dubOn && (dubList.isNotEmpty() ||
                streams.getOrNull(sourceIdx)?.let { dubOf(it) == want } == true)) {
            page = ""
            return
        }
        val pos = exo.currentPosition.coerceAtLeast(0L)
        if (dubList.isNotEmpty()) {
            val to = if (want) dubList else episodes
            val season = ep.title?.substringBefore(" · ")?.takeIf { it.matches(Regex("S[0-9]+")) }
            val target = to.firstOrNull { candidate ->
                candidate.number == ep.number &&
                    (season == null || candidate.title?.startsWith("$season · ") == true)
            }
            if (target == null) {
                showHud("No matching episode in that source version")
                return
            }
            dubOn = want
            PlayerPrefs.preferDub = want
            switchTo(target)
            pendingStart = pos
        } else {
            val i = streams.indexOfFirst { dubOf(it) == want }
            if (i < 0) {
                showHud("No matching source version available")
                return
            }
            dubOn = want
            PlayerPrefs.preferDub = want
            pendingStart = pos
            sourceIdx = i
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

    fun pickVariant(index: Int, requestedHeight: Int? = null) {
        if (index !in streams.indices) return
        preferredAudioApplied = true
        failedSourceUrls = emptySet()
        if (sourceIdx != index) {
            pendingStart = exo.currentPosition.coerceAtLeast(0L)
            tracks = Tracks.EMPTY
            sourceIdx = index
        }
        qualityH = requestedHeight ?: -1
        tweak { it.clearVideoSizeConstraints() }
        if (requestedHeight != null) setQuality(requestedHeight)
        page = ""
    }

    fun pickLanguage(code: String) {
        val current = streams.getOrNull(sourceIdx)?.let { streamChoice(it) }
        val index = bestStreamIndex(streams, code, current?.height, current?.codec) ?: return
        AppPrefs.setAudio(req.item.id, code.ifBlank { "original" })
        if (code != "original" && code.isNotBlank()) tweak { it.setPreferredAudioLanguage(code) }
        else tweak { it.setPreferredAudioLanguage(null) }
        pickVariant(index)
    }

    fun pickResolution(height: Int) {
        val current = streams.getOrNull(sourceIdx)?.let { streamChoice(it) }
        val actual = trackOpts(tracks, C.TRACK_TYPE_VIDEO).map { it.height }.filter { it > 0 }.distinct()
        val adaptive = actual.size > 1
        if ((adaptive && (height < 0 || height in actual)) ||
            (height in actual && current?.height == null)
        ) {
            setQuality(height)
            page = ""
            return
        }
        val language = current?.audioKey ?: ""
        val index = bestStreamIndex(streams, language, if (height < 0) null else height, current?.codec)
        if (index != null) pickVariant(index, height.takeIf { it > 0 })
        else { setQuality(height); page = "" }
    }

    fun tryCompatibleVariant(): Boolean {
        val current = streams.getOrNull(sourceIdx) ?: return false
        failedSourceUrls = failedSourceUrls + current.url
        val meta = streamChoice(current)
        val remaining = streams.indices.filter {
            streams[it].url !in failedSourceUrls && streamChoice(streams[it]).audioKey == meta.audioKey
        }
        val next = remaining.minByOrNull {
            kotlin.math.abs((streamChoice(streams[it]).height ?: 0) - (meta.height ?: 0))
        } ?: return false
        pendingStart = exo.currentPosition.coerceAtLeast(0L)
        tracks = Tracks.EMPTY
        qualityH = -1
        tweak { it.clearVideoSizeConstraints() }
        sourceIdx = next
        return true
    }

    fun pickAudio(o: TrackOpt) {
        tweak {
            var b = it.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setOverrideForType(
                    TrackSelectionOverride(o.group.mediaTrackGroup, o.index)
                )
            if (!o.lang.isNullOrBlank()) b = b.setPreferredAudioLanguage(o.lang)
            b
        }
        AppPrefs.setAudio(req.item.id, o.lang ?: "")
        showHud("Audio: ${o.label}")
    }

    fun pickSub(o: TrackOpt?) {
        if (o == null) {
            tweak {
                it.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
            AppPrefs.setSubtitle(req.item.id, "off")
            showHud("Subtitles off")
        } else {
            tweak {
                var b = it.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setSelectUndeterminedTextLanguage(true)
                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                if (!o.lang.isNullOrBlank()) b = b.setPreferredTextLanguage(o.lang)
                b
            }
            AppPrefs.setSubtitle(req.item.id, o.lang ?: "")
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
            exo.setMediaItem(cur.buildUpon().setSubtitleConfigurations(
                (cur.localConfiguration?.subtitleConfigurations.orEmpty() +
                    listOf(sub)).distinctBy { it.uri.toString() }
            ).build(), pos)
            exo.prepare()
            syncPlayback()
            showHud("Subtitle loaded")
        }
    }

    // ---------- effects ----------
    DisposableEffect(lifecycle, exo) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    playbackIntent.foreground = true; syncPlayback()
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP,
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> {
                    playbackIntent.foreground = false; syncPlayback(); saveProgress()
                }
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        syncPlayback()
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(PlayerWindowState.inPip) { syncPlayback() }

    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) { isPlaying = isPlayingNow }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                    playbackIntent.wantsPlay = false
                }
                if (playWhenReady && !playbackIntent.shouldPlay) exo.pause()
            }
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
                } else if (tryCompatibleVariant()) {
                    srcInfo = "Trying another file for the same audio language…"
                } else {
                    error = "Playback failed for this language/quality. Retry or choose another available option. " + m.take(180)
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

    LaunchedEffect(full, PlayerWindowState.inPip) {
        val act = activity
        if (act != null) {
            val ctl = WindowCompat.getInsetsController(act.window, act.window.decorView)
            if (full || PlayerWindowState.inPip) ctl.hide(WindowInsetsCompat.Type.systemBars()) else ctl.show(WindowInsetsCompat.Type.systemBars())
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
                withContext(Dispatchers.IO) { e.getDetails(req.item.id) }.let { details ->
                    titleGenres = details.item.genres
                    details.episodes
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { emptyList() }
        } else {
            Store.downloads
                .filter { it.item.id == req.item.id }
                .sortedBy { it.episodeNumber }
                .map { Episode(id = it.episodeId, number = it.episodeNumber, title = it.episodeTitle) }
        }
    }

    // 1) collect sources for this episode (first one arrives early, others keep coming)
    LaunchedEffect(ep.id, reloadKey) {
        if (req.localPath == null) {
            exo.stop()
            exo.clearMediaItems()
            tracks = Tracks.EMPTY
            isPlaying = false
            buffering = true
            qualityH = -1
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build()
        }
        sourceIdx = 0
        durMs = 0L
        posMs = 0L
        discovering = req.localPath == null
        error = null
        streams = emptyList()
        remoteSubs = emptyList()
        streamsFor = ep.id
        hlsIdx = -1
        if (req.localPath != null) return@LaunchedEffect
        val e = req.ext
        if (e == null) {
            error = "Enable this title’s extension in You > Sources & extensions."
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
                    try {
                        val requestedEpisode = ep.id
                        e.streamsLive(
                            requestedEpisode,
                            onSubtitle = { label, url ->
                                subtitleScope.launch {
                                    if (ep.id == requestedEpisode &&
                                        remoteSubs.none { it.second == url }
                                    ) {
                                        remoteSubs = remoteSubs + (label to url)
                                    }
                                }
                            },
                        ) { ch.trySend(it) }
                    } finally { ch.close() }
                }
                for (s in ch) {
                    if (streams.none { it.url == s.url }) streams = streams + s
                }
            } else {
                streams = withContext(Dispatchers.IO) { e.getStreams(ep.id) }
            }
            if (streams.isEmpty()) error = (e as? CsExtension)?.lastError
                ?: "This source returned no playable links. Try another version or extension."
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (streams.isEmpty()) error = "Source lookup failed: " +
                (t.message ?: t.javaClass.simpleName).take(240)
        } finally {
            discovering = false
            ticker.cancel()
        }
    }

    // SH_PATCH_02: preserve external subtitles, including late callbacks.
    fun remoteSubtitleConfigs(): List<M3Item.SubtitleConfiguration> =
        remoteSubs.distinctBy { it.second }.map { (label, url) ->
            val path = Uri.parse(url).path.orEmpty().lowercase(Locale.ROOT)
            val mime = when {
                path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                path.endsWith(".ass") || path.endsWith(".ssa") ->
                    MimeTypes.TEXT_SSA
                path.endsWith(".ttml") || path.endsWith(".xml") ->
                    MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            val language = label.trim().takeIf {
                Regex("[a-zA-Z]{2,3}([-_][a-zA-Z]{2,4})?").matches(it)
            }?.replace('_', '-')
            val builder = M3Item.SubtitleConfiguration.Builder(Uri.parse(url))
                .setId(url)
                .setMimeType(mime)
                .setLabel(label.ifBlank { "External subtitles" })
            if (language != null) builder.setLanguage(language)
            builder.build()
        }

    LaunchedEffect(remoteSubs, streamsFor) {
        if (req.localPath == null && streamsFor == ep.id &&
            remoteSubs.isNotEmpty()
        ) {
            val current = exo.currentMediaItem
            if (current != null && streams.getOrNull(sourceIdx)?.url ==
                current.localConfiguration?.uri?.toString()
            ) {
                val existing = current.localConfiguration
                    ?.subtitleConfigurations.orEmpty()
                val merged = (existing + remoteSubtitleConfigs())
                    .distinctBy { it.uri.toString() }
                if (merged != existing) {
                    val position = exo.currentPosition.coerceAtLeast(0L)
                    exo.setMediaItem(
                        current.buildUpon()
                            .setSubtitleConfigurations(merged).build(),
                        position,
                    )
                    exo.prepare()
                    syncPlayback()
                }
            }
        }
    }

    // 2) play the selected source; fall back to the next one on error or stall
    LaunchedEffect(ep.id, sourceIdx, attempt, streamsFor, streams.isEmpty()) {
        if (req.localPath == null && (streamsFor != ep.id || streams.isEmpty())) {
            return@LaunchedEffect
        }
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
                    .setSubtitleConfigurations(remoteSubtitleConfigs())
                if (hlsIdx == sourceIdx || s.url.contains("m3u8", true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
                val provider = providerDisplayName((req.ext ?: Store.ownerOf(req.item.id))?.name)
                srcInfo = "$provider · Video ${sourceIdx + 1} of ${streams.size}" +
                    (streamChoice(s).height?.let { " · ${it}p" } ?: "")
                tracks = Tracks.EMPTY
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .build()
                exo.setMediaItem(b.build())
            }
            if (pendingStart > 0L) exo.seekTo(pendingStart)
            pendingStart = 0L
            exo.prepare()
            syncPlayback()
            if (lp == null) {
                delay(30000)
                if (exo.playbackState != Player.STATE_READY && tryCompatibleVariant()) {
                    showHud("Trying another file for the same audio language")
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

    LaunchedEffect(posMs, autoSkip) {
        if (autoSkip && isPlaying && skippedFor != ep.id &&
            req.item.type != dev.lynx.streamhub.api.MediaType.MOVIE &&
            durMs > 600_000L && posMs in 2000L..25000L
        ) {
            skippedFor = ep.id
            exo.seekTo(posMs + 85_000L)
            showHud("Intro skipped")
        }
    }

    LaunchedEffect(ep.id, posMs, controls, locked, page) {
        if (!skipShown && controls && !locked && page.isEmpty() &&
            req.item.type != dev.lynx.streamhub.api.MediaType.MOVIE &&
            durMs > 600_000L && posMs in 3000L..240000L) {
            skipShown = true
            showSkip = true
        }
    }
    LaunchedEffect(showSkip) {
        if (showSkip) { delay(3000L); showSkip = false }
    }

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

    LaunchedEffect(streams.map { it.url }, ep.id) {
        val source = req.ext?.id
        if (source != null && streamsFor == ep.id) StreamChoiceCache.put(source, ep.id, streams)
        val wanted = preferredAudioKey(AppPrefs.audio(req.item.id))
        if (!preferredAudioApplied && wanted != null) {
            val index = bestStreamIndex(streams, wanted, null)
            if (index != null) {
                preferredAudioApplied = true
                if (index != sourceIdx) {
                    pendingStart = exo.currentPosition.coerceAtLeast(0L)
                    tracks = Tracks.EMPTY
                    sourceIdx = index
                }
            }
        }
    }

    LaunchedEffect(page, req.item.id, relatedRefresh) {
        if (page != "related" || relatedLoaded) return@LaunchedEffect
        relatedLoading = true
        relatedError = null
        try {
            val owner = req.ext ?: Store.ownerOf(req.item.id)
            if (owner == null) {
                relatedError = "No source is available for suggestions."
            } else {
                val fetched = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                        val cached = Session.sections.flatMap { it.items }
                            .filter { Session.owner[it.id]?.id == owner.id }
                        val feed = if (cached.isNotEmpty()) cached else owner.getHome().flatMap { it.items }
                        feed.forEach { Session.owner[it.id] = owner }
                        val genres = titleGenres.map { it.lowercase() }.toSet()
                        feed.distinctBy { it.id }.filter { it.id != req.item.id && it.type == req.item.type }
                            .sortedByDescending { item -> item.genres.count { it.lowercase() in genres } }
                            .take(24)
                    }
                }
                if (fetched == null) relatedError = "Suggestions timed out."
                else { related = fetched; relatedLoaded = true }
            }
        } catch (cancel: CancellationException) { throw cancel } catch (t: Exception) { relatedError = t.message ?: "Suggestions unavailable" } finally { relatedLoading = false }
    }

    val activeTracks = if (req.localPath != null ||
        (streamsFor == ep.id && streams.isNotEmpty() && error == null)) tracks else Tracks.EMPTY
    val drawerEpisodes = episodeWindow(shownEps, newestEpisodes, episodeRange)
    val audioOpts = trackOpts(activeTracks, C.TRACK_TYPE_AUDIO)
    val textOpts = trackOpts(activeTracks, C.TRACK_TYPE_TEXT)
    val videoOpts = trackOpts(activeTracks, C.TRACK_TYPE_VIDEO)
    val currentChoice = streams.getOrNull(sourceIdx)?.let { streamChoice(it) }
    val providerName = if (req.localPath != null) "Downloaded file"
        else providerDisplayName((req.ext ?: Store.ownerOf(req.item.id))?.name)
    val externalAudio = streams.map { streamChoice(it) }.filter { it.language != null }
        .distinctBy { it.audioKey }
    val embeddedCodes = audioOpts.mapNotNull { audioLanguageCode(it.lang) }.toSet()
    val externalRows = externalAudio.filter {
        it.audioKey !in embeddedCodes && !(it.audioKey == currentChoice?.audioKey && audioOpts.size == 1)
    }
    val selectedVersion = when {
        currentChoice?.language == "original" -> false
        isAnilightProvider(providerName) && currentChoice?.language == "en" -> true
        else -> sourceAudioVersion(streams.getOrNull(sourceIdx))
            ?: if (dubList.isNotEmpty()) dubOn else null
    }
    fun audioOptionLabel(option: TrackOpt): String {
        if (audioOpts.size == 1 && (selectedVersion == false || currentChoice?.language == "original")) return "Original"
        audioLanguageName(option.lang)?.let { return it }
        audioLanguageName(option.label)?.let { return it }
        if (audioOpts.size == 1) {
            currentChoice?.takeIf { it.language != null }?.let { return it.audioLabel }
            if (hasDubChoice && selectedVersion == true) return if (isAnilightProvider(providerName)) "English" else "Other"
        }
        return untaggedAudioLabel(selectedVersion, audioOpts.size, audioOpts.indexOf(option))
    }
    val audioActionsRaw = buildList<AudioAction> {
        audioOpts.forEach { option ->
            val label = if (audioOpts.size == 1 &&
                (selectedVersion == false || currentChoice?.language == "original")) "Original"
                else audioOptionLabel(option)
            add(AudioAction(label, option.selected) { pickAudio(option); page = "" })
        }
        externalRows.forEach { choice ->
            add(AudioAction(choice.audioLabel, choice.audioKey == currentChoice?.audioKey) { pickLanguage(choice.audioKey) })
        }
        if (hasDubChoice) {
            add(AudioAction("Original", selectedVersion == false) {
                AppPrefs.setAudio(req.item.id, "original"); setDub(false)
            })
            val alternate = if (isAnilightProvider(providerName)) "English" else "Other"
            add(AudioAction(alternate, selectedVersion == true) {
                if (alternate == "English") AppPrefs.setAudio(req.item.id, "en")
                setDub(true)
            })
        }
    }.groupBy { it.label }.values.map { rows -> rows.firstOrNull { it.selected } ?: rows.first() }
    val audioActions = if (audioActionsRaw.size == 1)
        listOf(audioActionsRaw.first().copy(label = "Original")) else audioActionsRaw
    val currentAudioLabel = audioActions.firstOrNull { it.selected }?.label ?: "Original"
    val adaptiveHeights = videoOpts.map { it.height }.filter { it > 0 }.distinct()
    val externalHeights = streams.filter { streamChoice(it).audioKey == currentChoice?.audioKey.orEmpty() }
        .mapNotNull { streamChoice(it).height }
    val qualities = (adaptiveHeights + externalHeights).distinct().sortedDescending()
    val qualityLabel = when {
        qualityH > 0 -> "${qualityH}p"
        adaptiveHeights.size > 1 -> "Auto"
        currentChoice?.height != null -> "${currentChoice.height}p"
        adaptiveHeights.size == 1 -> "${adaptiveHeights.first()}p"
        else -> "Auto"
    }
    val textOn = textOpts.any { it.selected }

    val epLabel = "E${ep.number.toInt()}" +
        (ep.title?.takeIf { it.isNotBlank() }?.let { " \u2022 $it" } ?: "")

    val inPip = PlayerWindowState.inPip
    val isMovie = req.item.type == dev.lynx.streamhub.api.MediaType.MOVIE
    val queuePage = if (isMovie) "related" else "episodes"
    val sidePanel = full && !inPip && (page == "episodes" || page == "related")
    val canPip = android.os.Build.VERSION.SDK_INT >= 26 && context.packageManager.hasSystemFeature(
        android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    // ---------- UI ----------
    Box(Modifier.fillMaxSize().background(if (full) Color.Black else MaterialTheme.colorScheme.background)) {
    Column(Modifier.fillMaxSize()) {
    Box(
        modifier = (if (inPip) Modifier.fillMaxSize()
            else if (full) Modifier.fillMaxHeight().fillMaxWidth(if (sidePanel) 0.58f else 1f)
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
                v.subtitleView?.let { applySubtitleStyle(it, subFont, subBold, subScale, subPos) }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // gesture layer
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked, inPip, sidePanel) {
                    if (!inPip) detectTapGestures(
                        onTap = {
                            controls = !controls
                            if (!sidePanel) page = ""
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
                .pointerInput(locked, inPip) {
                    if (!locked && !inPip) {
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
        FadeVis(
            visible = controls && !locked && !inPip && (page.isEmpty() || sidePanel),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x55000000))) {
                // Icon-first top bar.
                Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (full) { full = false; orient = 1; page = "" } else onBack() }) {
                        Icon(Icons.Outlined.KeyboardArrowDown, "Back / collapse", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(req.item.title, color = Color.White, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(epLabel, color = Color.LightGray, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { autoNext = !autoNext; PlayerPrefs.autoNext = autoNext }) {
                        Icon(Icons.Outlined.PlayCircle, "Toggle auto next",
                            tint = if (autoNext) Color.Black else Color.White,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                .background(if (autoNext) Color.White else Color.Transparent).padding(3.dp))
                    }
                    if (full) IconButton(onClick = { locked = true; controls = false }) {
                        Icon(Icons.Outlined.Lock, "Lock controls", tint = Color.White)
                    }
                    IconButton(onClick = { page = "subs" }) {
                        Icon(Icons.Outlined.ClosedCaption, "Subtitles", tint = if (textOn) Color.White else Color.LightGray)
                    }
                    IconButton(onClick = { page = "main" }) {
                        Icon(Icons.Outlined.Settings, "Settings", tint = Color.White)
                    }
                }

                if (!buffering && (streams.isNotEmpty() || req.localPath != null)) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        RoundBtn(48.dp, { if (prevEp != null) switchTo(prevEp) else seekBy(-10_000L); poke++ }) {
                            Icon(if (prevEp != null) Icons.Outlined.SkipPrevious else Icons.Outlined.Replay10,
                                if (prevEp != null) "Previous episode" else "Back ten seconds", tint = Color.White)
                        }
                        RoundBtn(68.dp, {
                            if (ended) exo.seekTo(0L)
                            requestPlayback(!exo.isPlaying)
                            poke++
                        }) {
                            Icon(if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                if (isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(42.dp))
                        }
                        RoundBtn(48.dp, { if (nextEp != null) switchTo(nextEp) else seekBy(10_000L); poke++ }) {
                            Icon(if (nextEp != null) Icons.Outlined.SkipNext else Icons.Outlined.Forward10,
                                if (nextEp != null) "Next episode" else "Forward ten seconds", tint = Color.White)
                        }
                    }
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${fmt((scrub ?: posMs.toFloat()).toLong())} / ${fmt(durMs)}",
                            color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                .background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 6.dp))
                        Spacer(Modifier.weight(1f))
                        if (canPip) IconButton(onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= 26) {
                                val entered = runCatching {
                                    activity?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder()
                                        .setAspectRatio(android.util.Rational(16, 9)).build()) == true
                                }.getOrDefault(false)
                                if (entered) { controls = false; page = "" }
                                else showHud("Picture-in-picture is unavailable")
                            }
                        }) { Icon(Icons.Outlined.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                        IconButton(onClick = {
                            resize = if (resize == AspectRatioFrameLayout.RESIZE_MODE_FIT)
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                            PlayerPrefs.resize = resize; poke++
                        }) { Icon(Icons.Outlined.AspectRatio, "Toggle video fit / zoom", tint = Color.White) }
                        IconButton(onClick = {
                            full = !full; orient = if (full) 0 else 1
                            if (!full) page = ""
                            poke++
                        }) { Icon(if (full) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                            if (full) "Exit fullscreen" else "Fullscreen", tint = Color.White) }
                    }
                    val maxV = durMs.coerceAtLeast(1L).toFloat()
                    Slider(value = (scrub ?: posMs.toFloat()).coerceIn(0f, maxV),
                        onValueChange = { scrub = it; poke++ },
                        onValueChangeFinished = {
                            scrub?.let { exo.seekTo(it.toLong()); posMs = it.toLong() }; scrub = null
                        }, valueRange = 0f..maxV,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFFF1744),
                            activeTrackColor = Color(0xFFFF1744), inactiveTrackColor = Color(0x66FFFFFF)),
                        modifier = Modifier.fillMaxWidth().height(26.dp))
                    if (full) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { page = "speed" }) {
                            Icon(Icons.Outlined.Speed, "Playback speed", tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            orient = (orient + 1) % 3; poke++
                        }) { Icon(Icons.Outlined.ScreenRotation, "Rotate", tint = Color.White) }
                        IconButton(onClick = { page = if (page == queuePage) "" else queuePage; controls = true }) {
                            Icon(if (isMovie) Icons.Outlined.VideoLibrary else Icons.Outlined.PlaylistPlay,
                                if (isMovie) "Related movies" else "Episodes", tint = Color.White)
                        }
                    }
                }
            }
        }

        // Manual +85-second jump, not detected intro metadata. Show once per episode.
        if (showSkip && controls && !locked && !inPip && page.isEmpty()) {
            Surface(color = Color(0xCC000000), shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = if (full) 150.dp else 108.dp)) {
                Row(Modifier.clickable {
                    seekBy(85_000L); showSkip = false; showHud("+85s"); poke++
                }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("+85s", color = Color.White, fontSize = 13.sp)
                    Icon(Icons.Outlined.FastForward, "Skip forward 85 seconds", tint = Color.White)
                }
            }
        }

        // unlock button
        FadeVis(
            visible = locked && controls && !inPip,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 88.dp, top = 24.dp, bottom = 24.dp),
        ) {
            RoundBtn(52.dp, {
                locked = false
                controls = true
            }) { Icon(Icons.Filled.Lock, contentDescription = "Unlock", tint = Color.White) }
        }

        if (!inPip && (buffering || (streams.isEmpty() && req.localPath == null)) && error == null) {
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
                Modifier.align(Alignment.Center).padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xEE111111)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(it, color = Color(0xFFFF6B6B))
                TextButton(onClick = {
                    pendingStart = exo.currentPosition
                    error = null
                    failedSourceUrls = emptySet()
                    if (req.localPath == null) reloadKey++ else attempt++
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
        if (!full && !inPip) {
            WatchInfo(
                modifier = Modifier.weight(1f),
                media = req.item,
                extId = req.ext?.id ?: Store.extIdFor(req.item.id) ?: "",
                ext = req.ext ?: Store.ownerOf(req.item.id),
                ep = ep,
                episodes = shownEps,
                durMs = durMs,
                audioLabel = currentAudioLabel,
                serverLabel = providerName,
                qualityLabel = qualityLabel,
                loading = req.localPath == null && durMs <= 0L && error == null,
                subsOn = textOn,
                speedLabel = if (speed == 1f) "Speed" else "${speed}x",
                onPage = { page = it },
                onEpisode = { switchTo(it) },
                onOpen = onOpen,
            )
        }
        }

        episodeDownload?.let { selected ->
            DownloadSheet(req.item, selected, req.ext ?: Store.ownerOf(req.item.id)) {
                episodeDownload = null
            }
        }
        if (dlSheet) {
            DownloadSheet(req.item, ep, req.ext ?: Store.ownerOf(req.item.id), initialStreams = streams) { dlSheet = false }
        }

        // settings panel
        if (page.isNotEmpty() && !inPip) {
            val none = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (sidePanel) Modifier else Modifier.clickable(
                        interactionSource = none, indication = null) { page = "" }),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    // SH_PATCH_03_SHEET_THEME
                    shape = if (full) RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    modifier = (if (sidePanel) Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.42f)
                        else if (full) Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.6f).widthIn(max = 360.dp)
                        else Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().fillMaxHeight(0.6f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (page != "main") {
                                IconButton(onClick = { page = "main" }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
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
                                    "related" -> "More like this"
                                    "substyle" -> "Subtitle style"
                                    "output" -> "Audio output"
                                    else -> "Settings"
                                },
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            IconButton(onClick = { page = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        val listState = rememberLazyListState()
                        LaunchedEffect(page, episodeRange, newestEpisodes) {
                            if (page == "episodes") {
                                val index = drawerEpisodes.indexOfFirst { it.id == ep.id }
                                if (index >= 0) listState.scrollToItem(index + 1)
                            }
                        }
                        LaunchedEffect(page, shownEps.size) {
                            if (page == "episodes") {
                                val index = chronologicalEpisodes(shownEps).indexOfFirst { it.id == ep.id }
                                episodeRange = if (index < 0) 0 else index / 100
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            when (page) {
                                "main" -> {
                                    item { OptionRow(if (isMovie) "Related movies" else "Episodes") {
                                        full = true; orient = 0; page = queuePage; controls = true
                                    } }
                                    item {
                                        OptionRow("Quality", qualityLabel) { page = "quality" }
                                    }
                                    item {
                                        OptionRow(
                                            "Audio track",
                                            currentAudioLabel,
                                        ) { page = "audio" }
                                    }
                                    item {
                                        OptionRow(
                                            "Subtitles",
                                            textOpts.firstOrNull { it.selected }?.label
                                                ?: if (textOpts.isEmpty()) "None in stream" else "Off",
                                        ) { page = "subs" }
                                    }
                                    item {
                                        OptionRow("Source", providerName, enabled = false) {}
                                    }
                                    item {
                                        val has = Store.hasDownload(ep.id)
                                        OptionRow("Download", if (has) "Downloaded" else "Available") {
                                            if (!has) {
                                                page = ""
                                                dlSheet = true
                                            }
                                        }
                                    }
                                    item { OptionRow("Playback speed", if (speed == 1f) "1.0x" else "${speed}x") { page = "speed" } }
                                    item {
                                        OptionRow("Audio output", "System device") {
                                            page = "output"
                                        }
                                    }
                                    item {
                                        SheetSwitchRow("Auto Skip", autoSkip) {
                                            autoSkip = it
                                            PlayerPrefs.autoSkip = it
                                        }
                                    }
                                    item {
                                        SheetSwitchRow("Auto Next", autoNext) {
                                            autoNext = it
                                            PlayerPrefs.autoNext = it
                                        }
                                    }
                                    item { OptionRow("Subtitle style", "Font, size, position") { page = "substyle" } }
                                    item {
                                        OptionRow("Load subtitle file", "srt, vtt, ass") {
                                            page = ""
                                            subPicker.launch(arrayOf("*/*"))
                                        }
                                    }
                                    if (episodes.isNotEmpty()) {
                                        item {
                                            OptionRow("Episodes", "${episodes.size} total") { page = "episodes" }
                                        }
                                    }
                                }

                                "substyle" -> {
                                    item {
                                        SubtitleStyleEditor(
                                            dark = AppPrefs.appearance == "dark" || (AppPrefs.appearance == "system" && androidx.compose.foundation.isSystemInDarkTheme()),
                                            font = subFont,
                                            bold = subBold,
                                            scale = subScale,
                                            pos = subPos,
                                            onFont = { subFont = it; PlayerPrefs.subFont = it },
                                            onBold = { subBold = it; PlayerPrefs.subBold = it },
                                            onScale = { subScale = it; PlayerPrefs.subScale = it },
                                            onPos = { subPos = it; PlayerPrefs.subPos = it },
                                        )
                                    }
                                }

                                "audio" -> {
                                    items(audioActions, key = { it.label }) { option ->
                                        OptionRow(option.label, selected = option.selected) { option.run() }
                                    }
                                    if (audioActions.isEmpty() && discovering) {
                                        item { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp)) }
                                    }
                                }

                                "subs" -> {
                                    item { OptionRow("Off", selected = !textOn) { pickSub(null) } }
                                    items(textOpts) { o ->
                                        OptionRow(o.label, selected = o.selected) {
                                            pickSub(o)
                                            page = ""
                                        }
                                    }
                                    if (textOpts.isEmpty()) {
                                        item {
                                            Text(
                                                "No supported subtitle tracks reported. " +
                                                    "Hardcoded subtitles cannot be toggled.",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(16.dp),
                                            )
                                        }
                                    }
                                    item {
                                        OptionRow("Load from file\u2026") {
                                            page = ""
                                            subPicker.launch(arrayOf("*/*"))
                                        }
                                    }
                                }

                                "quality" -> {
                                    item { OptionRow("Auto", selected = qualityH < 0 && qualityLabel == "Auto") {
                                        pickResolution(-1)
                                    } }
                                    items(qualities) { h ->
                                        OptionRow("${h}p", selected = qualityLabel == "${h}p") { pickResolution(h) }
                                    }
                                    if (qualities.isEmpty()) {
                                        item { Text("No alternate resolutions reported yet.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp)) }
                                    }
                                    if (discovering) {
                                        item { Text("Finding more options in the background…",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp)) }
                                    }
                                }

                                "output" -> {
                                    item {
                                        Text(
                                            "Playback uses Android's current media output. " +
                                                "Use system controls to choose connected " +
                                                "Bluetooth or wired devices.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                    item {
                                        OptionRow("Open device settings") {
                                            val opened = runCatching {
                                                context.startActivity(
                                                    android.content.Intent(
                                                        android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                                                    ).addFlags(
                                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                                    )
                                                )
                                            }.isSuccess
                                            if (!opened) {
                                                runCatching {
                                                    context.startActivity(
                                                        android.content.Intent(
                                                            android.provider.Settings.ACTION_SETTINGS
                                                        ).addFlags(
                                                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
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
                                    item { OptionRow("Source", providerName, enabled = false) {} }
                                    item { Text("The provider stays fixed to your Home/Search selection. " +
                                        "MegaPlay/MegaDub are video hosts returned by that provider, not extra providers or audio languages.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(20.dp)) }
                                }

                                "related" -> {
                                    if (relatedLoading) {
                                        items(4) { LoadingEpisodeCard() }
                                    } else if (related.isEmpty()) {
                                        item { Text(relatedError ?: "No suggestions returned by this source.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(20.dp)) }
                                        item { OptionRow("Retry") { relatedLoaded = false; relatedRefresh++ } }
                                    } else {
                                        items(related, key = { it.id }) { item ->
                                            RelatedWatchCard(item,
                                                item.genres.any { genre -> titleGenres.any { it.equals(genre, true) } }) { onOpen(item) }
                                        }
                                    }
                                }

                                "episodes" -> {
                                    item {
                                        EpisodeControls(shownEps.size, newestEpisodes, episodeRange,
                                            { newestEpisodes = it }, { episodeRange = it })
                                    }
                                    items(drawerEpisodes, key = { it.id }) { e ->
                                        WatchEpisodeRow(req.item, e, e.id == ep.id,
                                            onDownload = { episodeDownload = e }) {
                                            if (e.id != ep.id) switchTo(e)
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
}
