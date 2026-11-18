package dev.lynx.streamhub

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable
fun DiagnosticsSettings(onDismiss: () -> Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var enabled by remember { mutableStateOf(Diagnostics.enabled()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Diagnostics")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Collect logs",Modifier.weight(1f))
                Switch(enabled,{value->Diagnostics.setEnabled(value);enabled=Diagnostics.enabled()})
            }
            Text("Starts with the app when enabled. Records app events, playback/download state and error types. No passwords, cookies, search text, video contents or full source URLs. Older events are trimmed at 512 KiB. Disabled means no new events are collected.")
            Text("Downloads use a foreground service and notification while active. DNS errors can still require a network or provider fix; force-stop and Android background limits cannot be bypassed.")
            Text("Export replaces your previous Downloads/streamHub/log/log.txt. Review it before sharing; it can still reveal usage times and technical metadata.")
            OutlinedButton(enabled=!busy,onClick={
                DownloadAccess.requestLogExport(context) {
                    busy=true
                    scope.launch {
                        try { status=withContext(Dispatchers.IO){Diagnostics.export(context)}
                        } catch(cancel:CancellationException){throw cancel
                        } catch(error:Exception){status="Export failed. Check storage access and free space.";Diagnostics.failure("diagnostics.export.failed",error)
                        } finally {busy=false}
                    }
                }
            }) {Text(if(busy)"Exporting…" else "Export logs")}
            if(status.isNotBlank())Text(status)
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("Close")}})
}
