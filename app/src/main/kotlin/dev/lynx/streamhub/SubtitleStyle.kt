package dev.lynx.streamhub

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

val SUB_FONTS = listOf(
    "default" to "Default",
    "sans" to "Sans",
    "serif" to "Serif",
    "mono" to "Mono",
    "casual" to "Casual",
)

fun subtitleTypeface(font: String, bold: Boolean): Typeface {
    val style = if (bold) Typeface.BOLD else Typeface.NORMAL
    return when (font) {
        "sans" -> Typeface.create("sans-serif", style)
        "serif" -> Typeface.create("serif", style)
        "mono" -> Typeface.create("monospace", style)
        "casual" -> Typeface.create("casual", style)
        else -> Typeface.create(Typeface.DEFAULT, style)
    }
}

fun applySubtitleStyle(v: SubtitleView, font: String, bold: Boolean, scale: Float, pos: Float) {
    v.setFractionalTextSize(0.0533f * scale)
    v.setBottomPaddingFraction(((100f - pos) / 100f).coerceIn(0.02f, 0.5f))
    v.setStyle(
        CaptionStyleCompat(
            android.graphics.Color.WHITE,
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            subtitleTypeface(font, bold),
        )
    )
}

@Composable
private fun StyleChip(label: String, selected: Boolean, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = when {
        selected && dark -> Color.White
        selected -> Color.Black
        dark -> Color(0x33FFFFFF)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        selected && dark -> Color.Black
        selected -> Color.White
        dark -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        label,
        color = fg,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
fun SubtitleStyleEditor(
    dark: Boolean,
    font: String,
    bold: Boolean,
    scale: Float,
    pos: Float,
    onFont: (String) -> Unit,
    onBold: (Boolean) -> Unit,
    onScale: (Float) -> Unit,
    onPos: (Float) -> Unit,
) {
    val fg = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val dim = if (dark) Color(0xFFAAAAAA) else MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth().padding(horizontal = if (dark) 16.dp else 0.dp, vertical = 8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF3A3A44)),
        ) {
            AndroidView(
                factory = { ctx -> SubtitleView(ctx) },
                update = { v ->
                    applySubtitleStyle(v, font, bold, scale, pos)
                    v.setCues(listOf(Cue.Builder().setText("I'm going to be the King of the Pirates!").build()))
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("Font family", color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        SUB_FONTS.chunked(3).forEach { rowFonts ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowFonts.forEach { (key, label) ->
                    StyleChip(label, font == key, dark, Modifier.weight(1f)) { onFont(key) }
                }
                repeat(3 - rowFonts.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Font weight", color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StyleChip("Normal", !bold, dark, Modifier.weight(1f)) { onBold(false) }
            StyleChip("Bold", bold, dark, Modifier.weight(1f)) { onBold(true) }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Font size", color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${(scale * 100).toInt()}%", color = dim, fontSize = 14.sp)
        }
        Slider(
            value = scale * 100f,
            onValueChange = { onScale(Math.round(it / 5f) * 5 / 100f) },
            valueRange = 50f..200f,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vertical position", color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${pos.toInt()}%", color = dim, fontSize = 14.sp)
        }
        Slider(
            value = pos,
            onValueChange = { onPos(Math.round(it).toFloat()) },
            valueRange = 50f..98f,
        )
    }
}

@Composable
fun SheetSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
