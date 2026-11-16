package dev.lynx.streamhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!Session.searched) requester.requestFocus()
    }

    fun doSearch(q: String) {
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
            try {
                if (Session.extensions.isEmpty()) Session.reload(context)
                val exts = Session.extensions
                val out = withContext(Dispatchers.IO) {
                    val list = mutableListOf<MediaItem>()
                    var err: String? = null
                    for (e in exts) {
                        try {
                            val r = e.search(term, 1)
                            r.forEach { Session.owner[it.id] = e }
                            list.addAll(r)
                        } catch (t: Throwable) {
                            err = "${e.name}: ${t.message}"
                        }
                    }
                    list to err
                }
                Session.results = out.first
                error = out.second
                Session.searched = true
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("<") }
            OutlinedTextField(
                value = Session.query,
                onValueChange = { Session.query = it },
                placeholder = { Text("Search movies, series, anime") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch(Session.query) }),
                modifier = Modifier.weight(1f).focusRequester(requester),
            )
        }

        when {
            loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            !Session.searched -> LazyColumn(Modifier.fillMaxSize()) {
                if (Session.recent.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Recent searches", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { Session.recent.clear(); Store.saveRecent() }) { Text("Clear") }
                        }
                    }
                    items(Session.recent.toList()) { term ->
                        Text(
                            term,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { doSearch(term) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                error?.let {
                    item {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    }
                }
                if (Session.results.isEmpty()) {
                    item { Text("No results", modifier = Modifier.padding(16.dp)) }
                }
                items(Session.results.filter { Safety.allowed(it) }) { item -> ResultRow(item, onOpen) }
            }
        }
    }
}

@Composable
private fun ResultRow(item: MediaItem, onOpen: (MediaItem) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(item) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = item.poster,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(72.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                listOfNotNull(item.type.name, item.year?.toString()).joinToString("  •  "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
