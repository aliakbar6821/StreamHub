package dev.lynx.streamhub
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.Extension
import dev.lynx.streamhub.api.MediaDetails
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.MediaStatus
import dev.lynx.streamhub.api.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun statusOf(s: MediaStatus) = when (s) {
    MediaStatus.AIRING -> "Airing"
    MediaStatus.COMPLETED -> "Completed"
    MediaStatus.UPCOMING -> "Upcoming"
    MediaStatus.UNKNOWN -> "\u2014"
}

private fun typeName(t: MediaType) = when (t) {
    MediaType.MOVIE -> "Movie"
    MediaType.ANIME -> "Anime"
    MediaType.SERIES -> "TV"
}

private fun rangeLabel(l: List<Episode>): String =
    if (l.isEmpty()) "\u2014" else "${l.minOf { it.number }.toInt()}-${l.maxOf { it.number }.toInt()}"

@Composable
private fun AniEmpty(done: Boolean, none: String, err: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(if (!done) "Loading\u2026" else none, fontSize = 18.sp)
        if (done) {
            if (!err.isNullOrBlank()) {
                Text(err, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun InfoChip(text: String, dark: Boolean = true) {
    val bg = if (dark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val fg = if (dark) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Text(
        text, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TabBar(tabs: List<String>, sel: Int, onSel: (Int) -> Unit) {
    val line = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        tabs.forEachIndexed { i, t ->
            Text(
                t, fontSize = 21.sp, maxLines = 1,
                fontWeight = if (i == sel) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clickable { onSel(i) }
                    .drawBehind {
                        if (i == sel) drawRect(line, Offset(0f, size.height - 4.dp.toPx()), Size(size.width, 4.dp.toPx()))
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(label, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AiringCard(ep: Int?, at: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val left = (at - now).coerceAtLeast(0L) / 1000
    val f = remember { SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (ep != null) "Episode $ep airs" else "Next episode airs", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text(f.format(Date(at)), fontSize = 19.sp, color = Color(0xFF2E9E4F))
        }
        Text(
            "%dd %02d:%02d:%02d".format(left / 86400, left / 3600 % 24, left / 60 % 60, left % 60),
            fontSize = 24.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EpisodeRow(
    media: MediaItem, ep: Episode, isMovie: Boolean, resumeMs: Long,
    onPlay: () -> Unit, onDownload: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val sub = when {
        resumeMs > 0L -> "Continue"
        Store.isWatched(ep.id) -> "Watched"
        Store.hasDownload(ep.id) -> "Downloaded"
        else -> ""
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.width(160.dp).aspectRatio(5f / 3f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(
                model = ep.thumbnail ?: media.poster, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            Text(
                if (isMovie) "MOVIE" else "EP ${ep.number.toInt()}", color = Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(50))
                    .background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ep.title ?: epLabel(media, ep.number), fontSize = 20.sp, fontWeight = FontWeight.Medium,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(sub, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; onPlay() })
                DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; onDownload() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderRow(n: Int, r: AniRel, current: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (current) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Box(Modifier.width(112.dp).aspectRatio(0.72f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model = r.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Text(
                "#$n", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(50))
                    .background(Color(0xAA000000)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("${r.start} - ${r.end}", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(r.format)
                r.eps?.let { InfoChip("$it Ep", false) }
                r.dur?.let { InfoChip("${it}m", false) }
            }
        }
    }
}

@Composable
private fun SimilarCard(r: AniRel, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(0.6f).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        AsyncImage(model = r.image, contentDescription = r.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(r.title, color = Color.White, fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.format, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 16.dp, vertical = 7.dp))
                r.year?.let {
                    Text(it.toString(), color = Color.White, fontSize = 16.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xAA333333)).padding(horizontal = 16.dp, vertical = 7.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(
    media: MediaItem,
    onBack: () -> Unit,
    onPlay: (Extension?, Episode, Long) -> Unit,
    onSearch: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<MediaDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ani by remember { mutableStateOf<AniInfo?>(null) }
    var aniDone by remember { mutableStateOf(false) }
    var aniTry by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var sheetEp by remember { mutableStateOf<Episode?>(null) }
    var tab by remember { mutableStateOf(0) }
    var newest by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(0) }
    var rangeOpen by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val ext = Session.owner[media.id]?.takeIf { ExtensionLoader.isEnabled(it) } ?: Store.ownerOf(media.id)
    val last = Store.historyFor(media.id)

    LaunchedEffect(media.id) {
        if (ext == null) {
            error = "Extension not found. Go back and reload."
            return@LaunchedEffect
        }
        try {
            details = withContext(Dispatchers.IO) { ext.getDetails(media.id) }
        } catch (t: Throwable) {
            error = t.message ?: "Failed to load"
        }
    }
    LaunchedEffect(media.title, aniTry) {
        aniDone = false
        ani = AniMeta.fetch(media.title)
        aniDone = true
    }

    val info = details?.item
    val type = info?.type ?: media.type
    val isMovie = type == MediaType.MOVIE
    var dub by remember { mutableStateOf(PlayerPrefs.preferDub) }
    val dubEps = (ext as? CsExtension)?.dubEpisodes(media.id) ?: emptyList()
    val hasDub = dubEps.isNotEmpty()
    val eps = (if (dub && hasDub) dubEps else details?.episodes) ?: emptyList()
    val ordered = if (newest) eps.reversed() else eps
    val chunks = ordered.chunked(100)
    val chunkIdx = range.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
    val shown = chunks.getOrNull(chunkIdx) ?: emptyList()
    val desc = info?.description ?: media.description
    val inList = Store.inList(media.id)
    val title = info?.title ?: media.title
    val epCount = ani?.episodes ?: eps.size.takeIf { it > 0 }
    val meta = listOfNotNull(
        ani?.score?.let { "$it% score" },
        if (isMovie) "Movie" else epCount?.let { "$it episodes" },
    ).joinToString(" \u2022 ")
    val tabs = listOf("Home", if (isMovie) "Movie" else "Episodes", "Watch Order", "More like this")
    val sv = MaterialTheme.colorScheme.surfaceVariant

    fun watch() {
        if (last != null) {
            onPlay(ext, Episode(id = last.episodeId, number = last.episodeNumber, title = last.episodeTitle), last.positionMs)
        } else {
            eps.firstOrNull()?.let { onPlay(ext, it, 0L) }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = ani?.cover ?: media.poster, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(104.dp).clip(CircleShape).background(sv),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                    ani?.studio?.let { Text(it, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
                    if (meta.isNotEmpty()) Text(meta, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!desc.isNullOrBlank()) {
            item {
                Text(
                    desc, fontSize = 18.sp, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { Store.toggleList(media, ext?.id ?: "") },
                        modifier = Modifier.weight(1f).height(60.dp),
                    ) { Text(if (inList) "In your list" else "Add to list", fontSize = 20.sp, fontWeight = FontWeight.Medium) }
                    FilledTonalButton(
                        onClick = { watch() },
                        enabled = eps.isNotEmpty() || last != null,
                        modifier = Modifier.weight(1f).height(60.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (last != null) "Resume" else "Watch", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    }
                }
                FilledTonalButton(
                    onClick = { confirmAll = true },
                    enabled = eps.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download all", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item { TabBar(tabs, tab) { tab = it } }

        when (tab) {
            0 -> {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(ani?.status ?: statusOf(media.status), "Status", Modifier.weight(1f))
                        StatCard(ani?.format ?: typeName(type), "Type", Modifier.weight(1f))
                        StatCard((ani?.year ?: info?.year ?: media.year)?.toString() ?: "\u2014", "Year", Modifier.weight(1f))
                    }
                }
                val at = ani?.nextAt
                if (at != null && at > System.currentTimeMillis()) item { AiringCard(ani?.nextEp, at) }
                val genres = listOf(ani?.genres, info?.genres, media.genres).firstOrNull { !it.isNullOrEmpty() } ?: emptyList()
                if (genres.isNotEmpty()) {
                    item {
                        FlowRow(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) { genres.forEach { InfoChip(it, false) } }
                    }
                }
            }
            1 -> {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!isMovie) {
                            PillChip("Oldest", !newest) { newest = false; range = 0 }
                            Spacer(Modifier.width(8.dp))
                            PillChip("Newest", newest) { newest = true; range = 0 }
                        }
                        Spacer(Modifier.weight(1f))
                        if (chunks.size > 1) {
                            Box {
                                Row(
                                    Modifier.clip(RoundedCornerShape(26.dp)).background(sv).clickable { rangeOpen = true }
                                        .padding(horizontal = 20.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(rangeLabel(shown), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                    Icon(if (rangeOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                                }
                                DropdownMenu(expanded = rangeOpen, onDismissRequest = { rangeOpen = false }) {
                                    chunks.forEachIndexed { i, c ->
                                        DropdownMenuItem(text = { Text(rangeLabel(c)) }, onClick = { range = i; rangeOpen = false })
                                    }
                                }
                            }
                        }
                    }
                }
                if (details == null && error == null) {
                    item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                items(shown) { ep ->
                    val resume = if (last != null && last.episodeId == ep.id) last.positionMs else 0L
                    EpisodeRow(media, ep, isMovie, resume, onPlay = { onPlay(ext, ep, resume) }, onDownload = { sheetEp = ep })
                }
            }
            2 -> {
                val order = ani?.order ?: emptyList()
                if (order.isEmpty()) {
                    item { AniEmpty(aniDone, "No watch order found for this title.", null) { aniTry++ } }
                }
                itemsIndexed(order) { i, r -> OrderRow(i + 1, r, r.image == ani?.cover) }
            }
            else -> {
                val sim = ani?.similar ?: emptyList()
                if (sim.isEmpty()) {
                    item { AniEmpty(aniDone, "No recommendations found.", null) { aniTry++ } }
                }
                items(sim.chunked(2)) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { r -> SimilarCard(r, Modifier.weight(1f)) { onSearch(r.title) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    sheetEp?.let { DownloadSheet(media, it, ext) { sheetEp = null } }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Download all") },
            text = { Text("Queues the next 10 episodes that aren't downloaded yet, using the first direct (non-HLS) source for each. Sources are found one by one, so keep this screen open.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    DownloadAccess.request(context) {
                    scope.launch {
                        val targets = eps.filter { !Store.hasDownload(it.id) }.take(10)
                        var ok = 0
                        Toast.makeText(context, "Finding sources for ${targets.size} episodes\u2026", Toast.LENGTH_SHORT).show()
                        for (e in targets) {
                            try {
                                val s = withContext(Dispatchers.IO) { ext?.getStreams(e.id)?.let { l -> l.firstOrNull { !isHls(it) } ?: l.firstOrNull() } }
                                if (s != null && Downloader.enqueue(context, media, e, s, s.quality ?: "", sourceName = ext?.name)) ok++
                            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
                            } catch (_: Exception) { }
                        }
                        Toast.makeText(context, "Queued $ok of ${targets.size}", Toast.LENGTH_LONG).show()
                    }
                    }
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}
