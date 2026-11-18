package dev.lynx.streamhub

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Staging stays private. Finished files are published to user-visible Downloads. */
object DownloadFiles {
    fun uri(path: String): Uri = if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))
    fun size(context: Context, path: String): Long = runCatching {
        if (!path.startsWith("content://")) return@runCatching File(path).takeIf { it.isFile }?.length() ?: -1L
        context.contentResolver.query(Uri.parse(path), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)
    fun delete(context: Context, path: String) {
        if (path.startsWith("content://")) context.contentResolver.delete(Uri.parse(path), null, null)
        else File(path).let { file -> if (file.exists() && !file.delete()) error("Could not delete file") }
    }
    fun abortPending(context: Context, id: Long) {
        val prefs = context.getSharedPreferences("dl_publication", Context.MODE_PRIVATE)
        prefs.getString(id.toString(), null)?.let { runCatching { delete(context, it) } }
        prefs.edit().remove(id.toString()).commit()
    }
    fun committed(context: Context, id: Long) {
        context.getSharedPreferences("dl_publication", Context.MODE_PRIVATE).edit().remove(id.toString()).commit()
    }
    private fun mediaType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "ts" -> "video/mp2t"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "mov" -> "video/quicktime"; else -> "video/mp4"
    }
    suspend fun publish(context: Context, id: Long, staging: File, folder: String, name: String, checkpoint: () -> Unit): String {
        check(staging.isFile && staging.length() > 0L) { "Download staging file is empty or missing" }
        val expectedBytes=staging.length()
        abortPending(context, id)
        val pending = context.getSharedPreferences("dl_publication", Context.MODE_PRIVATE)
        var target: String? = null
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mediaType(name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/streamHub/$folder/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(collection, values) ?: error("Cannot create file in Downloads")
                target = uri.toString()
                check(pending.edit().putString(id.toString(), target).commit()) { "Could not save publication checkpoint" }
                val output = context.contentResolver.openOutputStream(uri, "w") ?: error("Cannot open Downloads file")
                output.use { out ->
                    staging.inputStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive(); checkpoint()
                            val count = input.read(buffer); if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                    }
                }
                currentCoroutineContext().ensureActive(); checkpoint()
                val actualBytes=size(context, target)
                check(actualBytes < 0L || actualBytes == expectedBytes) { "Downloads copy size differs from completed transfer" }
                check(context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) > 0) { "Could not finish Downloads file" }
                return target
            }
            @Suppress("DEPRECATION")
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "streamHub/$folder")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create Downloads folder" }
            val file = reserveLegacyName(directory, name)
            target = file.absolutePath
            check(pending.edit().putString(id.toString(), target).commit()) { "Could not save publication checkpoint" }
            FileOutputStream(file).use { out ->
                staging.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive(); checkpoint()
                        val count = input.read(buffer); if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
            }
            currentCoroutineContext().ensureActive(); checkpoint()
            check(file.length() == expectedBytes) { "Downloads copy size differs from completed transfer" }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(target), arrayOf(mediaType(name)), null)
            return target
        } catch (t: Throwable) {
            target?.let { runCatching { delete(context, it) } }
            pending.edit().remove(id.toString()).commit()
            throw t
        }
    }
    @Synchronized
    private fun reserveLegacyName(directory: File, name: String): File {
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.')
        for (suffix in 1..10_000) {
            val candidate = File(directory, if (suffix == 1) name else "${stem}_$suffix.$extension")
            if (candidate.createNewFile()) return candidate
        }
        error("Too many files with this name")
    }
}
