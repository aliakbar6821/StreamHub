package dev.lynx.streamhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.MediaItem

@Composable
fun MyListScreen(onOpen: (MediaItem) -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (!Session.loaded) Session.reload(context)
    }
    val shown: List<MediaItem> =
        if (tab == 0) Store.myList.map { it.item } else Store.history.map { it.item }

    Column(Modifier.fillMaxSize()) {
        Text(
            "My List",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Saved") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("History") })
            Spacer(Modifier.weight(1f))
            if (tab == 1 && Store.history.isNotEmpty()) {
                TextButton(onClick = { Store.clearHistory() }) { Text("Clear") }
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (tab == 0) "Nothing saved yet. Open a title and tap + My List."
                    else "No watch history yet.",
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown) { m -> PosterTile(m, onOpen) }
            }
        }
    }
}

@Composable
fun PosterTile(m: MediaItem, onOpen: (MediaItem) -> Unit) {
    Column(Modifier.clickable { onOpen(m) }) {
        AsyncImage(
            model = m.poster,
            contentDescription = m.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            m.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
