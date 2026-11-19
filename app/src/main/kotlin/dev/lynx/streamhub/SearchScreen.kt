package dev.lynx.streamhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.MediaStatus
import dev.lynx.streamhub.api.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Rounded label used on cards: dark = filled primary, otherwise soft grey. */
@Composable
fun Tag(text: String, dark: Boolean = true, color: Color? = null) {
    val bg = color ?: if (dark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val fg = if (color != null) Color.White else if (dark) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Text(
        text,
        color = fg,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
fun RatingTag(rating: Float) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            String.format("%.1f", rating),
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!Session.searched) runCatching { requester.requestFocus() }
    }

    fun doSearch(q: String) {
        if(loading)return
        val term = q.trim()
        if (term.isEmpty()) return
        Session.query = term
        focus.clearFocus()
        Session.recent.remove(term)
        Session.recent.add(0, term)
        while (Session.recent.size > 8) Session.recent.removeAt(Session.recent.size - 1)
        Store.saveRecent()
        scope.launch {
            loading = true
            error = null
            Session.results = emptyList()
            Session.searched = true
            Diagnostics.event("search.submit")
            try {
                if (Session.extensions.isEmpty()) Session.reload(context)
                val requestGeneration = Session.generation
                val exts = Session.extensions.filter { ExtensionLoader.isEnabled(it) }
                val acc = mutableListOf<MediaItem>()
                val errs = mutableListOf<String>()
                val rep = mutableListOf<String>()
                report = ""
                coroutineScope {
                    exts.map { e ->
                        async(Dispatchers.IO) {
                            try {
                                val r = e.search(term, 1)
                                withContext(Dispatchers.Main) {
                                    if (requestGeneration == Session.generation && ExtensionLoader.isEnabled(e)) {
                                        r.forEach { Session.owner[it.id] = e }
                                        acc.addAll(r)
                                        Session.results = acc.toList()
                                        val sourceFailure=(e as? CsExtension)?.lastSearchFailure
                                        if(r.isEmpty() && sourceFailure!=null)errs.add("${e.name}: $sourceFailure")
                                        rep.add(e.name + ": " + (if (r.isEmpty()) ((e as? CsExtension)?.lastSearchNote?.ifEmpty { null } ?: "no results") else r.size.toString()))
                                        report = rep.joinToString("\n")
                                    }
                                }
                            } catch (cancel: CancellationException) { throw cancel
                            } catch (t: Exception) {
                                Diagnostics.failure("search.source.error",t)
                                synchronized(errs) { errs.add("${e.name}: ${t.message}") }
                                synchronized(rep) { rep.add(e.name + ": error " + (t.message ?: "")); report = rep.joinToString("\n") }
                            }
                        }
                    }.awaitAll()
                }
                Diagnostics.event("search.finished","results=${Session.results.size} errors=${errs.size}")
                error = errs.firstOrNull()
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (Session.query.isEmpty()) {
                    Text(
                        "Search",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 20.sp,
                    )
                }
                BasicTextField(
                    value = Session.query,
                    onValueChange = { Session.query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch(Session.query) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester),
                )
            }
            if (Session.query.isNotEmpty()) {
                IconButton(onClick = {
                    Session.query = ""
                    Session.searched = false
                    Session.results = emptyList()
                    runCatching { requester.requestFocus() }
                }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
            }
        }

        if (!Session.searched) {
            val trending = Session.sections.firstOrNull()?.items?.filter { Safety.allowed(it) }?.take(8) ?: emptyList()
            LazyColumn(Modifier.fillMaxSize()) {
                if (Session.recent.isNotEmpty()) {
                    items(Session.recent.toList()) { term ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { Session.query = term; doSearch(term) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(18.dp))
                            Text(term, fontSize = 20.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(
                                Icons.Filled.ArrowBack, null,
                                modifier = Modifier.size(24.dp).rotate(45f).clickable { Session.query = term },
                            )
                        }
                    }
                    item {
                        TextButton(
                            onClick = { Session.recent.clear(); Store.saveRecent() },
                            modifier = Modifier.padding(start = 12.dp),
                        ) { Text("Clear history") }
                    }
                }
                if (trending.isNotEmpty()) {
                    item {
                        Text(
                            "Trending",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                    items(trending) { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(t) }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Star, null, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(18.dp))
                            Text(t.title, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(12.dp))
                            AsyncImage(
                                model = t.poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(96.dp)
                                    .height(60.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                if (loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                    }
                }
                val shown = Session.results.filter { Safety.allowed(it) }
                if (!loading && shown.isEmpty()) {
                    item { Text(if(error!=null)"Search could not complete" else "No results", modifier = Modifier.padding(20.dp), fontSize = 18.sp) }
                    if(report.isNotBlank())item { Text(report,modifier=Modifier.padding(horizontal=20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    if(error!=null)item { TextButton(onClick={doSearch(Session.query)}){Text("Retry search")} }
                }
                items(shown) { item -> ResultCard(item, onOpen) }
            }
        }
    }
}

private fun typeLabel(t: MediaType) = when (t) {
    MediaType.MOVIE -> "Movie"
    MediaType.SERIES -> "TV"
    MediaType.ANIME -> "Anime"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(item: MediaItem, onOpen: (MediaItem) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(item) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        AsyncImage(
            model = item.poster,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(112.dp)
                .height(160.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Medium,
            )
            if (item.genres.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.genres.take(3).joinToString(", "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.status == MediaStatus.AIRING) Tag("Airing", color = Color(0xFF2E9E4F))
                item.rating?.let { RatingTag(it) }
                Tag(typeLabel(item.type))
                Session.owner[item.id]?.name?.let { Tag(it, dark = false) }
                item.year?.let { Tag(it.toString(), dark = false) }
            }
        }
    }
}
