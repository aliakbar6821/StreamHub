package dev.lynx.streamhub

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

object PlayerWindowState {
    var inPip by mutableStateOf(false)
}

@Composable
private fun SkeletonBlock(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "loading")
    val alpha by transition.animateFloat(0.06f, 0.16f,
        infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "pulse")
    Box(modifier.clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)))
}

@Composable
fun LoadingEpisodeCard() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SkeletonBlock(Modifier.width(132.dp).aspectRatio(16f / 9f))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SkeletonBlock(Modifier.fillMaxWidth().height(14.dp))
            SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(14.dp))
            SkeletonBlock(Modifier.width(50.dp).height(10.dp))
        }
    }
}

@Composable
fun PlayerPageSkeleton(modifier: Modifier, title: String) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(cleanMediaText(title), fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(16.dp))
            SkeletonBlock(Modifier.padding(horizontal = 16.dp).fillMaxWidth(0.72f).height(10.dp))
        }
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(Modifier.size(46.dp).clip(CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.7f).height(12.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(10.dp))
                }
                SkeletonBlock(Modifier.width(96.dp).height(38.dp))
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { SkeletonBlock(Modifier.weight(1f).height(38.dp)) }
            }
        }
        items(4) { LoadingEpisodeCard() }
    }
}
