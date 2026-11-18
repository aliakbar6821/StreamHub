package dev.lynx.streamhub

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.HomeSection
import dev.lynx.streamhub.api.MediaItem
import dev.lynx.streamhub.api.MediaType
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(onOpen: (MediaItem) -> Unit, onSearch: () -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("All") }

    LaunchedEffect(Unit) {
        if (!Session.loaded) Session.reload(context)
    }

    val base = remember(Session.sections) { Session.sections
        .map { s -> HomeSection(s.title, s.items.filter { Safety.allowed(it) }) }
        .filter { it.items.isNotEmpty() } }
    val chips = listOf("All", "Movies", "Series", "Anime") +
        base.flatMap { it.items }.flatMap { it.genres }.distinct().take(12) +
        base.map { it.title }.distinct()
    val active = if (filter in chips) filter else "All"
    val sections = when (active) {
        "All" -> base
        "Movies" -> base
            .map { s -> HomeSection(s.title, s.items.filter { it.type == MediaType.MOVIE }) }
            .filter { it.items.isNotEmpty() }
        "Series" -> base
            .map { s -> HomeSection(s.title, s.items.filter { it.type == MediaType.SERIES }) }
            .filter { it.items.isNotEmpty() }
        "Anime" -> base.map { s -> HomeSection(s.title, s.items.filter { it.type == MediaType.ANIME }) }
            .filter { it.items.isNotEmpty() }
        else -> base.map { s -> HomeSection(s.title,
            if (s.title == active) s.items else s.items.filter { active in it.genres }) }
            .filter { it.items.isNotEmpty() }
    }
    val hero = base.flatMap { it.items }.distinctBy { it.id }.take(5)
    val grid = active != "All"

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "streamHub",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = onSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
            }
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chips) { c -> PillChip(c, active == c) { filter = c } }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }

        if (Session.loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(AppPrefs.setupStatus.ifBlank { "Loading your sources…" },
                            modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        } else if (Session.loaded && Session.extensions.isEmpty()) {
            item {
                Text(
                    "No sources available. Open You > Sources & extensions to check default setup.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Session.error?.let { msg ->
            item {
                Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
        }

        if (!grid && hero.isNotEmpty()) {
            item { Hero(hero, onOpen) }
            item { Spacer(Modifier.height(20.dp)) }
        }

        if (!grid && Store.history.isNotEmpty()) {
            item { SectionTitle("Watch History") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(Store.history.toList(), key = { it.item.id + ":" + it.episodeId }) { h -> HistoryCard(h, onOpen) }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }

        sections.forEach { section ->
            item { SectionTitle(section.title) }
            if (grid) {
                items(section.items.chunked(3)) { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { PosterCard(it, onOpen, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(section.items, key = { it.id }) { PosterCard(it, onOpen) }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(items: List<MediaItem>, onOpen: (MediaItem) -> Unit) {
    val pager = rememberPagerState(pageCount = { items.size })
    LaunchedEffect(items.size) {
        while (items.size > 1) {
            delay(5000)
            pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    Column {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
        ) { page -> HeroCard(items[page], onOpen) }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(items.size) { i ->
                val on = i == pager.currentPage
                Box(
                    Modifier
                        .padding(3.dp)
                        .size(if (on) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        ),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(item: MediaItem, onOpen: (MediaItem) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(20.dp))
            .clickable { onOpen(item) }
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = item.poster,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD0F0F14)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(
                item.title,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onOpen(item) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Watch now")
            }
        }
    }
}

@Composable
private fun HistoryCard(h: HistoryEntry, onOpen: (MediaItem) -> Unit) {
    val frac = if (h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.width(200.dp).clickable { onOpen(h.item) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = h.item.poster,
                contentDescription = h.item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))),
            )
            Text(
if (frac >= 0.95f) "Watched" else "${((h.durationMs - h.positionMs) / 60000).coerceAtLeast(1)}m left",
color = Color.White,
style = MaterialTheme.typography.labelSmall,
modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
.clip(RoundedCornerShape(10.dp)).background(Color(0xAA000000))
.padding(horizontal = 8.dp, vertical = 3.dp),
)
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color(0x66FFFFFF),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            h.item.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Episode ${h.episodeNumber.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
