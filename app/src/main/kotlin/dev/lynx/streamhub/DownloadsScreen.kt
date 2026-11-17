package dev.lynx.streamhub
import android.app.DownloadManager
import android.content.Context
import androidx.activity.compose.BackHandler
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
import java.io.File

object DlInfo {
    fun label(c: Context, id: Long): String =
        c.getSharedPreferences("dl_meta", Context.MODE_PRIVATE).getString(id.toString(), "") ?: ""

    fun bytes(c: Context, id: Long): Long {
        if (id >= 1_000_000_000_000L) return Downloader.bytes(id)
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use {
            if (!it.moveToFirst()) return 0L
            return it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        }
    }
}

private data class DlState(val pct: Int, val done: Boolean, val failed: Boolean, val bytes: Long)

private fun stateOf(c: Context, d: DownloadEntry): DlState {
    val st = Downloader.status(c, d.downloadId)
    val f = File(d.path)
    val done = st.first == 8 || (st.first == -1 && f.exists() && f.length() > 0)
    return DlState(if (done) 100 else st.second, done, st.first == 16, if (done) f.length() else DlInfo.bytes(c, d.downloadId))
}

private fun mb(b: Long) = "${b / 1048576} MB"

private fun countLabel(item: dev.lynx.streamhub.api.MediaItem, n: Int) =
    if (item.type == MediaType.MOVIE) "Movie" else "$n episode${if (n == 1) "" else "s"}"

@Composable
private fun GroupRow(list: List<DownloadEntry>, edit: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val item = list.first().item
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = item.poster, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(128.dp).aspectRatio(3f / 2f).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(countLabel(item, list.size), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (edit) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
    }
}

@Composable
private fun DlRow(d: DownloadEntry, s: DlState, label: String, onPlay: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = s.done, onClick = onPlay).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(112.dp).aspectRatio(5f / 3f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model = d.item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Text(
                if (d.item.type == MediaType.MOVIE) "MOVIE" else "EP ${d.episodeNumber.toInt()}",
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(50))
                    .background(Color(0xAA000000)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                d.episodeTitle.ifBlank { epLabel(d.item, d.episodeNumber) },
                fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(epLabel(d.item, d.episodeNumber), label).filter { it.isNotBlank() }.joinToString(" \u2022 "),
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    s.failed -> "Failed"
                    s.done -> mb(s.bytes)
                    else -> "${s.pct}% \u2022 ${mb(s.bytes)}"
                },
                fontSize = 12.sp,
                color = if (s.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!s.done && !s.failed) {
                LinearProgressIndicator(progress = { s.pct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
    }
}

@Composable
fun DownloadsScreen(onPlay: (PlayRequest) -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var openId by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf(false) }
    var newest by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    val groups = Store.downloads.groupBy { it.item.id }
    val cur = openId?.let { groups[it] }
    BackHandler(enabled = cur != null) { openId = null }

    if (cur == null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("StreamHub", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { edit = !edit }) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
            }
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No downloads yet", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(groups.values.toList()) { list ->
                        GroupRow(list, edit, onOpen = { openId = list.first().item.id }, onDelete = { list.forEach { Downloader.remove(context, it) } })
                    }
                }
            }
        }
    } else {
        val item = cur.first().item
        val states = remember(tick, cur.size) { cur.associateWith { stateOf(context, it) } }
        val allDone = states.values.all { it.done }
        val total = states.values.sumOf { it.bytes }
        val sub = (if (allDone) "Downloaded" else "Downloading") + " \u2022 " + countLabel(item, cur.size) + " \u2022 " + mb(total)
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
                        enabled = states.values.any { it.done },
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
            items(sorted) { d ->
                val s = states[d] ?: stateOf(context, d)
                DlRow(
                    d, s, DlInfo.label(context, d.downloadId),
                    onPlay = { onPlay(PlayRequest(null, d.item, Episode(d.episodeId, d.episodeNumber, d.episodeTitle), 0L, d.path)) },
                    onRemove = { Downloader.remove(context, d) },
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
