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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.HomeSection
import dev.lynx.streamhub.api.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LoadResult(
    val extCount: Int,
    val sections: List<HomeSection>,
    val error: String?,
)

@Composable
fun HomeScreen(onOpen: (MediaItem) -> Unit) {
    val context = LocalContext.current
    val genres = listOf("All", "Action", "Adventure", "Comedy", "Drama")
    var genre by remember { mutableStateOf("All") }
    var reloadKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var extCount by remember { mutableIntStateOf(0) }
    var sections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            val exts = ExtensionLoader.loadAll(context)
            val all = mutableListOf<HomeSection>()
            var err: String? = null
            for (e in exts) {
                try {
                    val s = e.getHome(); s.forEach { sec -> sec.items.forEach { Session.owner[it.id] = e } }; all.addAll(s)
                } catch (t: Throwable) {
                    err = "${e.name}: ${t.message}"
                }
            }
            LoadResult(exts.size, all, err)
        }
        extCount = result.extCount
        sections = result.sections
        error = result.error
        loading = false
    }

    val featured = sections.firstOrNull()?.items?.firstOrNull()

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("StreamHub", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { reloadKey++ }) { Text("Reload") }
            }
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(genres) { g ->
                    FilterChip(
                        selected = genre == g,
                        onClick = { genre = g },
                        label = { Text(g) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }

        if (loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (extCount == 0) {
            item {
                Text(
                    "No extensions installed. Install an extension APK, then tap Reload.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        error?.let { msg ->
            item {
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (featured != null) {
            item { FeaturedCard(featured, onOpen) }
            item { Spacer(Modifier.height(16.dp)) }
        }

        sections.forEach { section ->
            item {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(section.items) { PosterCard(it, onOpen) }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun FeaturedCard(item: MediaItem, onOpen: (MediaItem) -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(24.dp)).clickable { onOpen(item) }
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = item.poster,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        Text(
            item.title,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(12.dp),
        )
    }
}

@Composable
private fun PosterCard(item: MediaItem, onOpen: (MediaItem) -> Unit) {
    Column(Modifier.width(120.dp).clickable { onOpen(item) }) {
        AsyncImage(
            model = item.poster,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
