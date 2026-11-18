package dev.lynx.streamhub

import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded app-event journal, not a system logcat, keylogger or network-body recorder. */
class DiagnosticJournal(private val file: File, initiallyEnabled: Boolean) {
    @Volatile var enabled=initiallyEnabled
        private set
    @Synchronized fun setEnabled(value: Boolean) { enabled=value }
    companion object {
        const val LIMIT=512*1024
        fun redact(text: String): String {
            val urls=Regex("https?://[^\\s<>\"]+",RegexOption.IGNORE_CASE).replace(text) { match ->
                val host=runCatching { URI(match.value).host }.getOrNull() ?: "host"
                "https://$host/[redacted]"
            }
            return Regex("(?i)(authorization|proxy-authorization|cookie|set-cookie|token|password|secret|api[_-]?key)\\s*[:=]\\s*[^\\r\\n]*")
                .replace(urls,"$1=[redacted]").take(4096)
        }
    }
    @Synchronized fun append(event: String, detail: String = "") {
        if(!enabled)return
        runCatching {
            file.parentFile?.mkdirs()
            val stamp=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ",Locale.ROOT).format(Date())
            val line="$stamp ${redact(event)} ${redact(detail).replace('\n',' ').replace('\r',' ')}\n"
            if(file.length()+line.toByteArray(Charsets.UTF_8).size>LIMIT){
                val bytes=file.readBytes();val start=(bytes.size-LIMIT/2).coerceAtLeast(0)
                val tail=bytes.copyOfRange(start,bytes.size).toString(Charsets.UTF_8).substringAfter('\n',"")
                file.writeText("[older diagnostic events trimmed]\n"+tail)
            }
            file.appendText(line)
        }
    }
    @Synchronized fun snapshot(): String = runCatching {
        if(file.isFile)file.readText() else "No diagnostic events recorded.\n"
    }.getOrDefault("Diagnostic journal is unavailable.\n")
}
