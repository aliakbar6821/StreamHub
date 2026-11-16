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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Airing(val episode: String, val ts: Long, val title: String, val image: String, val studio: String)

private const val API = "https://ani-station-schedule-api.vercel.app"

private suspend fun fetchSchedule(): List<Airing> = withContext(Dispatchers.IO) {
    val c = Calendar.getInstance()
    val m = c.get(Calendar.MONTH)
    val season = when { m < 3 -> "winter"; m < 6 -> "spring"; m < 9 -> "summer"; else -> "fall" }
    val con = URL("$API/$season-${c.get(Calendar.YEAR)}/tv").openConnection() as HttpURLConnection
    con.connectTimeout = 15000
    con.readTimeout = 40000
    val arr = JSONArray(con.inputStream.bufferedReader().use { it.readText() })
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val ts = o.optString("timeStamp").toLongOrNull() ?: return@mapNotNull null
        Airing(o.optString("episode"), ts * 1000, o.optString("title"), o.optString("imageUrl"), o.optString("animeStudio"))
    }.sortedBy { it.ts }
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
