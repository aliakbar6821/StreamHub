package dev.lynx.streamhub
import android.app.DownloadManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.Episode
import dev.lynx.streamhub.api.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DlInfo {
    fun label(c: Context, id: Long): String =
        c.getSharedPreferences("dl_meta", Context.MODE_PRIVATE).getString(id.toString(), "") ?: ""

    fun bytes(c: Context, id: Long): Long {
        if (id >= 1_000_000_000_000L) return Downloader.savedBytes(c, id)
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use {
            if (!it.moveToFirst()) return 0L
            return it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        }
    }
}

private data class DlState(val pct: Int = 0, val done: Boolean = false, val failed: Boolean = false,
    val bytes: Long = 0, val total: Long = -1, val paused: Boolean = false, val queued: Boolean = false)

private fun stateOf(c: Context, d: DownloadEntry): DlState {
    val st = Downloader.status(c, d.downloadId)
    val f = File(d.path)
    val done = st.first == 8 || (st.first == -1 && f.exists() && f.length() > 0)
    val total = if (done) f.length() else if (d.downloadId >= 1_000_000_000_000L) Downloader.total(c, d.downloadId) else -1L
    return DlState(if (done) 100 else st.second, done, st.first == 16,
        if (done) f.length() else DlInfo.bytes(c, d.downloadId), total, st.first == 4, st.first == 1)
}

private fun mb(b: Long): String = when {
    b < 0 -> "Size unknown"
    b >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.2f GB", b / 1_073_741_824.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", b / 1_048_576.0)
}

private fun countLabel(item: dev.lynx.streamhub.api.MediaItem, n: Int) =
    if (item.type == MediaType.MOVIE) "Movie" else "$n episode${if (n == 1) "" else "s"}"

@Composable
private fun GroupRow(list: List<DownloadEntry>, summary: String, edit: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val item = list.first().item
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
        .clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = item.poster, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(128.dp).aspectRatio(3f / 2f).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(summary, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (edit) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
    }
}

@Composable
private fun DlRow(d: DownloadEntry, s: DlState, label: String, onPlay: () -> Unit,
    onRemove: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val source = remember(d.downloadId) {
        context.getSharedPreferences("dl_source", Context.MODE_PRIVATE)
            .getString(d.downloadId.toString(), null) ?: "Source not recorded"
    }
    val resumable = remember(d.downloadId) { Downloader.canResume(context, d.downloadId) }
    val status = when {
        s.done -> "Downloaded"
        s.failed -> "Failed"
        s.paused -> "Paused"
        s.queued -> "Waiting / retrying"
        else -> "Downloading"
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
        .clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainer)
        .border(1.dp, colors.outlineVariant, RoundedCornerShape(18.dp))
        .clickable(enabled = s.done, onClick = onPlay).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = d.item.poster, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(84.dp).height(86.dp).clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(epLabel(d.item, d.episodeNumber), d.episodeTitle).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove download") }
        }
        Spacer(Modifier.height(10.dp))
        Text("Source: $source", fontSize = 12.sp, color = colors.onSurfaceVariant)
        Text(label.ifBlank { "Audio / quality not recorded" }, fontSize = 12.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = if (s.failed) colors.error else colors.onSurface)
                Text(if (s.done) mb(s.bytes) else mb(s.bytes) + " / " + mb(s.total) +
                    (if (s.total > 0 || s.pct > 0) " · ${s.pct}%" else ""),
                    fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            if (s.done) IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, "Play download") }
            else if (resumable) {
                if (s.paused || s.failed) IconButton(onClick = onResume) {
                    Icon(if (s.failed) Icons.Filled.Refresh else Icons.Filled.PlayArrow,
                        if (s.failed) "Retry download" else "Resume download")
                } else IconButton(onClick = onPause) { Icon(Icons.Filled.Pause, "Pause download") }
            }
        }
        if (!s.done) {
            if (s.total > 0 || s.pct > 0 || s.paused || s.failed)
                LinearProgressIndicator(progress = { (s.pct / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (s.failed) Text(Downloader.failure(context, d.downloadId), fontSize = 12.sp,
            color = colors.error, modifier = Modifier.padding(top = 8.dp))
        if (!s.done && !resumable) Text("Older transfer: remove it and download again to enable pause/resume.",
            fontSize = 12.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
fun DownloadsScreen(onPlay: (PlayRequest) -> Unit) {
    val context = LocalContext.current
    var states by remember { mutableStateOf<Map<DownloadEntry, DlState>>(emptyMap()) }
    val entries = Store.downloads.toList()
    var openId by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf(false) }
    var newest by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(entries) {
        while (true) {
            states = withContext(Dispatchers.IO) { entries.associateWith { stateOf(context, it) } }
            delay(1000L)
        }
    }

    val groups = Store.downloads.groupBy { it.item.id }
    val cur = openId?.let { groups[it] }
    BackHandler(enabled = cur != null) { openId = null }

    if (cur == null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("streamHub", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { edit = !edit }) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
            }
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No downloads yet", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(groups.values.toList(), key = { it.first().item.id }) { list ->
                        val done = list.count { states[it]?.done == true }
                        val paused = list.count { states[it]?.paused == true }
                        val downloadedBytes = list.sumOf { states[it]?.bytes ?: 0L }
                        val summary = "${countLabel(list.first().item, list.size)} · $done saved · ${mb(downloadedBytes)}" +
                            if (paused > 0) " · $paused paused" else ""
                        GroupRow(list, summary, edit, onOpen = { openId = list.first().item.id }, onDelete = { list.forEach { Downloader.remove(context, it) } })
                    }
                }
            }
        }
    } else {
        val item = cur.first().item
        val groupStates = cur.mapNotNull { states[it] }
        val allDone = groupStates.size == cur.size && groupStates.all { it.done }
        val total = groupStates.sumOf { it.bytes }
        val sub = (if (allDone) "Downloaded" else if (groupStates.any { it.paused }) "Paused / in progress" else "In progress") + " \u2022 " + countLabel(item, cur.size) + " \u2022 " + mb(total)
        val sorted = cur.sortedBy { it.episodeNumber }.let { if (newest) it.reversed() else it }
        fun playFirst() {
            val d = cur.sortedBy { it.episodeNumber }.firstOrNull { states[it]?.done == true } ?: return
            onPlay(PlayRequest(null, d.item, Episode(d.episodeId, d.episodeNumber, d.episodeTitle), 0L, d.path))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { IconButton(onClick = { openId = null }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            item {
                AsyncImage(
                    model = item.poster, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(item.title, fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(6.dp))
                    Text(sub, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { playFirst() },
                        enabled = groupStates.any { it.done },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Play all", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    FilledTonalIconButton(onClick = { confirm = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete all")
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillChip("Oldest", !newest) { newest = false }
                    PillChip("Newest", newest) { newest = true }
                }
            }
            items(sorted, key = { it.downloadId }) { d ->
                val s = states[d] ?: DlState()
                DlRow(
                    d, s, DlInfo.label(context, d.downloadId),
                    onPlay = { onPlay(PlayRequest(null, d.item, Episode(d.episodeId, d.episodeNumber, d.episodeTitle), 0L, d.path)) },
                    onRemove = { Downloader.remove(context, d) },
                    onPause = { Downloader.pause(context, d.downloadId) },
                    onResume = { Downloader.resume(context, d) },
                )
            }
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Delete downloads") },
                text = { Text("Remove all downloads for ${item.title}?") },
                confirmButton = { TextButton(onClick = { confirm = false; cur.forEach { Downloader.remove(context, it) } }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            )
        }
    }
}
