package dev.lynx.streamhub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Airing(
    val episode: String,
    val ts: Long,
    val title: String,
    val image: String,
    val format: String,
    val year: Int?,
)

private data class Day(val key: Int, val name: String, val date: String)

private const val ANILIST = "https://graphql.anilist.co"
private const val GQL = "query(\$from:Int,\$to:Int,\$page:Int){Page(page:\$page,perPage:50){pageInfo{hasNextPage}airingSchedules(airingAt_greater:\$from,airingAt_lesser:\$to,sort:TIME){episode airingAt media{title{romaji english}coverImage{large}format seasonYear isAdult}}}}"

private fun weekStart(): Calendar {
    val c = Calendar.getInstance()
    c.firstDayOfWeek = Calendar.MONDAY
    c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c
}

private fun dayKey(ts: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

private fun hourOf(ts: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun weekDays(): List<Day> {
    val c = weekStart()
    val n = SimpleDateFormat("EEE", Locale.getDefault())
    val d = SimpleDateFormat("MMM d", Locale.getDefault())
    return (0 until 7).map {
        val day = Day(dayKey(c.timeInMillis), n.format(c.time), d.format(c.time))
        c.add(Calendar.DAY_OF_YEAR, 1)
        day
    }
}

private fun gqlPage(from: Long, to: Long, page: Int): JSONObject {
    val con = URL(ANILIST).openConnection() as HttpURLConnection
    con.requestMethod = "POST"
    con.doOutput = true
    con.connectTimeout = 15000
    con.readTimeout = 30000
    con.setRequestProperty("Content-Type", "application/json")
    con.setRequestProperty("Accept", "application/json")
    con.setRequestProperty("User-Agent", "StreamHub/0.1")
    val body = JSONObject().put("query", GQL)
        .put("variables", JSONObject().put("from", from).put("to", to).put("page", page))
    con.outputStream.use { it.write(body.toString().toByteArray()) }
    return try {
        if (con.responseCode != 200) throw Exception("Schedule server error ${con.responseCode}")
        JSONObject(con.inputStream.bufferedReader().use { it.readText() })
    } finally {
        con.disconnect()
    }
}

private fun formatLabel(f: String): String = when {
    f.isBlank() || f == "null" -> "TV"
    f == "TV_SHORT" -> "TV Short"
    f.length <= 3 -> f
    else -> f.lowercase().replaceFirstChar { it.uppercase() }
}

private suspend fun fetchSchedule(): List<Airing> = withContext(Dispatchers.IO) {
    val from = weekStart().timeInMillis / 1000 - 1
    val to = from + 7 * 86400 + 2
    val out = ArrayList<Airing>()
    var page = 1
    while (page <= 8) {
        val pg = gqlPage(from, to, page).getJSONObject("data").getJSONObject("Page")
        val arr = pg.getJSONArray("airingSchedules")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val m = o.getJSONObject("media")
            if (m.optBoolean("isAdult", false)) continue
            val t = m.getJSONObject("title")
            val title = t.optString("english", "").takeIf { it.isNotBlank() && it != "null" } ?: t.optString("romaji")
            val img = m.optJSONObject("coverImage")?.optString("large") ?: ""
            val year = if (m.isNull("seasonYear")) null else m.optInt("seasonYear")
            out.add(Airing("Episode " + o.optInt("episode"), o.getLong("airingAt") * 1000, title, img, formatLabel(m.optString("format", "")), year))
        }
        if (!pg.getJSONObject("pageInfo").optBoolean("hasNextPage", false)) break
        page++
    }
    out.sortedBy { it.ts }
}

@Composable
fun ScheduleScreen() {
    var items by remember { mutableStateOf<List<Airing>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val week = remember { weekDays() }
    var sel by remember { mutableStateOf(week.indexOfFirst { it.key == dayKey(System.currentTimeMillis()) }.coerceAtLeast(0)) }
    val rowState = rememberLazyListState()

    LaunchedEffect(Unit) {
        try { items = fetchSchedule() } catch (t: Throwable) { error = t.message ?: "Failed to load schedule" }
        loading = false
    }
    LaunchedEffect(Unit) { while (true) { delay(30000); now = System.currentTimeMillis() } }
    LaunchedEffect(Unit) { rowState.scrollToItem(sel) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Schedule",
            fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 14.dp),
        ) {
            itemsIndexed(week) { i, d -> DayChip(d, i == sel) { sel = i } }
        }
        val list = items.filter { dayKey(it.ts) == week[sel].key }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            list.isEmpty() -> Text("Nothing airing this day.", modifier = Modifier.padding(16.dp), fontSize = 18.sp)
            else -> {
                val groups = list.groupBy { hourOf(it.ts) }.toSortedMap()
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    groups.forEach { (hour, airs) -> item(key = hour) { TimeGroup(hour, airs, now) } }
                }
            }
        }
    }
}

@Composable
private fun DayChip(d: Day, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(d.name, color = fg, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text(d.date, color = fg.copy(alpha = 0.7f), fontSize = 17.sp)
    }
}

@Composable
private fun TimeGroup(hour: Long, airs: List<Airing>, now: Long) {
    val lineColor = MaterialTheme.colorScheme.outline
    val hf = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val tf = remember { SimpleDateFormat("h:mm", Locale.getDefault()) }
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 6.dp.toPx()
                drawLine(lineColor, Offset(x, 22.dp.toPx()), Offset(x, size.height), 2.dp.toPx())
            },
    ) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground))
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Filled.KeyboardArrowRight, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(6.dp))
            Text(hf.format(Date(hour)), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        airs.forEach { a -> AiringCard(a, now, tf) }
    }
}

@Composable
private fun AiringCard(a: Airing, now: Long, tf: SimpleDateFormat) {
    val aired = a.ts <= now
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 30.dp, top = 12.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
    ) {
        AsyncImage(
            model = a.image,
            contentDescription = a.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(100.dp)
                .height(146.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.background),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).height(146.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    a.title,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 21.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(tf.format(Date(a.ts)), fontSize = 30.sp, fontWeight = FontWeight.Medium)
            }
            Text(a.episode, fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(a.format)
                    a.year?.let { Tag(it.toString(), dark = false) }
                }
                Text(
                    if (aired) "AIRED" else "AIRING",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (aired) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF2E9E4F),
                )
            }
        }
    }
}
