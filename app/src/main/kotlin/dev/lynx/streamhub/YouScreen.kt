package dev.lynx.streamhub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun YouScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("You", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clickable { scope.launch { Session.reload(context) } }
                .padding(vertical = 12.dp),
        ) {
            Text("Reload extensions", style = MaterialTheme.typography.titleMedium)
            Text(
                if (Session.loading) "Reloading..." else "${Session.extensions.size} installed",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Installed extensions", style = MaterialTheme.typography.titleSmall)
        if (Session.extensions.isEmpty()) {
            Text("None", modifier = Modifier.padding(vertical = 8.dp))
        }
        Session.extensions.forEach { e ->
            Text(
                "${e.name}  (v${e.version})",
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}
