package dev.lynx.streamhub

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.lynx.streamhub.api.MediaItem

fun shareText(context: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** YouTube-style pill: active chip is filled, others are muted. */
@Composable
fun PillChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Text(
        label,
        color = fg,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The three-dot menu from the YouTube cards: My List + Share. */
@Composable
fun ItemMenu(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "More",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (Store.inList(item.id)) "Remove from My List" else "Add to My List") },
                onClick = {
                    Store.toggleList(item, Session.owner[item.id]?.id ?: Store.extIdFor(item.id) ?: "")
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Share") },
                onClick = {
                    shareText(context, item.title)
                    open = false
                },
            )
        }
    }
}

@Composable
fun PosterCard(
    item: MediaItem,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier.width(120.dp),
) {
    Column(modifier.clickable { onOpen(item) }) {
        Box {
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
            Text(
                item.type.name,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            ItemMenu(item, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        item.year?.let {
            Text(
                it.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
