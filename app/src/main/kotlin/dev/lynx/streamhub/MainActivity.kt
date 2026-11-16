package dev.lynx.streamhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import dev.lynx.streamhub.api.MediaItem

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContent {
            StreamHubTheme {
                App()
            }
        }
    }
}

private data class NavTab(val label: String, val icon: ImageVector)

@Composable
fun App() {
    val tabs = listOf(
        NavTab("Home", Icons.Filled.Home),
        NavTab("Schedule", Icons.Filled.DateRange),
        NavTab("My List", Icons.Filled.Favorite),
        NavTab("Downloads", Icons.Filled.KeyboardArrowDown),
        NavTab("You", Icons.Filled.Person),
    )
    var selected by remember { mutableIntStateOf(0) }
    var openItem by remember { mutableStateOf<MediaItem?>(null) }
    var playing by remember { mutableStateOf<PlayRequest?>(null) }
    var showSearch by remember { mutableStateOf(false) }

    val p = playing
    val item = openItem
    if (p != null) {
        BackHandler { playing = null }
        PlayerScreen(p, onBack = { playing = null })
    } else if (item != null) {
        BackHandler { openItem = null }
        Surface(Modifier.fillMaxSize()) {
            DetailsScreen(
                media = item,
                onBack = { openItem = null },
                onPlay = { ext, ep, start -> playing = PlayRequest(ext, item, ep, start, null) },
            )
        }
    } else if (showSearch) {
        BackHandler { showSearch = false }
        Surface(Modifier.fillMaxSize().statusBarsPadding()) {
            SearchScreen(
                onBack = { showSearch = false },
                onOpen = { openItem = it },
            )
        }
    } else {
        val primary = MaterialTheme.colorScheme.primary
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = NavBg) {
                    tabs.forEachIndexed { i, tab ->
                        NavigationBarItem(
                            selected = selected == i,
                            onClick = { selected = i },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = {
                                Text(tab.label, fontSize = 11.sp, maxLines = 1, softWrap = false)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = primary,
                                selectedTextColor = primary,
                                indicatorColor = primary.copy(alpha = 0.18f),
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize().statusBarsPadding()) {
                when (selected) {
                    0 -> HomeScreen(
                        onOpen = { openItem = it },
                        onSearch = { showSearch = true },
                    )
                    2 -> MyListScreen(onOpen = { openItem = it })
                    3 -> DownloadsScreen(onPlay = { playing = it })
                    4 -> YouScreen()
                    else -> Placeholder(tabs[selected].label)
                }
            }
        }
    }
}

@Composable
fun Placeholder(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(name)
    }
}
