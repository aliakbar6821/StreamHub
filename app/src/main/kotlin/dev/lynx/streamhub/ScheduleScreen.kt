package dev.lynx.streamhub
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Airing(val episode: String, val ts: Long, val title: String, val image: String, val studio: String)

private const val ANILIST = "https://graphql.anilist.co"
private const val GQL = "query(\$from:Int,\$to:Int,\$page:Int){Page(page:\$page,perPage:50){pageInfo{hasNextPage}airingSchedules(airingAt_greater:\$from,airingAt_lesser:\$to,sort:TIME){episode airingAt media{title{romaji english}coverImage{large}studios(isMain:true){nodes{name}}isAdult}}}}"

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

private suspend fun fetchSchedule(): List<Airing> = withContext(Dispatchers.IO) {
    val c = Calendar.getInstance()
    c.firstDayOfWeek = Calendar.MONDAY
    c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    val from = c.timeInMillis / 1000 - 1
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
            val studio = m.optJSONObject("studios")?.optJSONArray("nodes")?.optJSONObject(0)?.optString("name") ?: ""
            out.add(Airing("EP " + o.optInt("episode"), o.getLong("airingAt") * 1000, title, img, studio))
        }
        if (!pg.getJSONObject("pageInfo").optBoolean("hasNextPage", false)) break
        page++
    }
    out.sortedBy { it.ts }
}

private fun dow(ts: Long): Int = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.DAY_OF_WEEK)

@Composable
fun ScheduleScreen() {
    var items by remember { mutableStateOf<List<Airing>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var day by remember { mutableStateOf(Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) }
    val days = listOf(2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat", 1 to "Sun")
    val fmt = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        try { items = fetchSchedule() } catch (t: Throwable) { error = t.message ?: "Failed to load schedule" }
        loading = false
    }
    LaunchedEffect(Unit) { while (true) { delay(30000); now = System.currentTimeMillis() } }

    Column(Modifier.fillMaxSize()) {
        Text("Schedule", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(days) { (d, label) -> PillChip(label, day == d) { day = d } }
        }
        val list = items.filter { dow(it.ts) == day }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            list.isEmpty() -> Text("Nothing airing this day.", modifier = Modifier.padding(16.dp))
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list) { a ->
                    val left = a.ts - now
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = a.image, contentDescription = a.title, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(64.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(a.episode, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(a.studio.lineSequence().firstOrNull() ?: "", maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmt.format(Date(a.ts)), fontWeight = FontWeight.Bold)
                            Text(
                                if (left <= 0) "Aired" else "${left / 86400000}d ${left / 3600000 % 24}h ${left / 60000 % 60}m",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (left <= 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
