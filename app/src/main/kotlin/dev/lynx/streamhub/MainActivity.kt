package dev.lynx.streamhub

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VideoLibrary
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

object DownloadNavigation {
    var request by mutableIntStateOf(0)
}

class MainActivity : ComponentActivity() {
    private var afterDownloadPermission: (() -> Unit)? = null
    private val downloadPermissions = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        val ready = afterDownloadPermission
        afterDownloadPermission = null
        if (DownloadAccess.canSavePublic(this)) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.widget.Toast.makeText(this, "Notifications are off. Downloads still appear inside the app.", android.widget.Toast.LENGTH_LONG).show()
            }
            ready?.invoke()
        } else android.widget.Toast.makeText(this, "Storage permission is required to save to Downloads.", android.widget.Toast.LENGTH_LONG).show()
    }
    fun requestDownloadAccess(ready: () -> Unit) {
        if (afterDownloadPermission != null) return
        val missing = mutableListOf<String>()
        if (!DownloadAccess.canSavePublic(this)) missing.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val prefs = getSharedPreferences("download_access", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notifications_asked", false) &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            missing.add(android.Manifest.permission.POST_NOTIFICATIONS)
            prefs.edit().putBoolean("notifications_asked", true).apply()
        }
        if (missing.isEmpty()) ready()
        else { afterDownloadPermission = ready; downloadPermissions.launch(missing.toTypedArray()) }
    }
    private fun handleDownloadIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("open_downloads", false) == true) {
            intent.removeExtra("open_downloads"); DownloadNavigation.request++
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent); handleDownloadIntent(intent)
    }
    override fun onDestroy() { afterDownloadPermission = null; super.onDestroy() }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerWindowState.inPip = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppPrefs.init(this)
        Store.init(this)
        PlayerPrefs.init(this)
        UiScale.value = PlayerPrefs.uiScale
        handleDownloadIntent(intent)
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
        NavTab("My List", Icons.Filled.VideoLibrary),
        NavTab("Downloads", Icons.Filled.Download),
        NavTab("You", Icons.Filled.Person),
    )
    var selected by remember { mutableIntStateOf(0) }
    var manageExtensions by remember { mutableStateOf(false) }
    var openItem by remember { mutableStateOf<MediaItem?>(null) }
    var playing by remember { mutableStateOf<PlayRequest?>(null) }
    var showSearch by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(DownloadNavigation.request) {
        if (DownloadNavigation.request > 0) {
            selected = 3; playing = null; openItem = null; showSearch = false
            DownloadNavigation.request = 0
        }
    }

    val p = playing
    val item = openItem
    if (p != null) {
        BackHandler { playing = null }
        PlayerScreen(
            p,
            onBack = { playing = null },
            onOpen = {
                playing = null
                openItem = it
            },
        )
    } else if (item != null) {
        BackHandler { openItem = null }
        Surface(Modifier.fillMaxSize()) {
            DetailsScreen(
                media = item,
                onBack = { openItem = null },
                onPlay = { ext, ep, start -> playing = PlayRequest(ext, item, ep, start, null) },
                onSearch = { q -> Session.query = q; Session.searched = false; Session.results = emptyList(); openItem = null; showSearch = true },
            )
        }
    } else if (showSearch) {
        BackHandler { showSearch = false }
        Surface(Modifier.fillMaxSize()) {
            SearchScreen(
                onBack = { showSearch = false },
                onOpen = { openItem = it },
            )
        }
    } else {
        val primary = MaterialTheme.colorScheme.primary
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                    tabs.forEachIndexed { i, tab ->
                        NavigationBarItem(
                            selected = selected == i,
                            onClick = { selected = i; manageExtensions = false },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = {
                                Text(tab.label, fontSize = 11.sp, maxLines = 1, softWrap = false)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = primary,
                                selectedTextColor = primary,
                                indicatorColor = primary.copy(alpha = 0.12f),
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (selected) {
                    0 -> HomeScreen(
                        onOpen = { openItem = it },
                        onSearch = { showSearch = true },
                        onExtensions = { manageExtensions = true; selected = 4 },
                    )
                     1 -> ScheduleScreen()
                     2 -> MyListScreen(onOpen = { openItem = it })
                    3 -> DownloadsScreen(onPlay = { playing = it })
                    4 -> YouScreen(startAtExtensions = manageExtensions)
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
