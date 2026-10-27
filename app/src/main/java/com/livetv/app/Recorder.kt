package com.livetv.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class Recorder(private val ctx: Context, private val onDone: (String) -> Unit) {

    @Volatile
    private var running = false
    @Volatile
    private var conn: HttpURLConnection? = null
    @Volatile
    var bytes: Long = 0
    private var isRadio = false

    val active: Boolean get() = running

    fun start(url: String, name: String, radio: Boolean) {
        if (running) return
        isRadio = radio
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        dir.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val ext = if (radio) "mp3" else "ts"
        val f = File(dir, "rec_${safe}_$stamp.$ext")
        bytes = 0
        running = true
        Thread {
            val msg = try {
                FileOutputStream(f).use { out -> record(url, out) }
                finish(f)
            } catch (e: Exception) {
                if (bytes > 0) finish(f) else "ریکارڈنگ نہیں ہو سکی (چینل محفوظ یا بند ہو سکتا ہے)"
            }
            running = false
            onDone(msg)
        }.start()
    }

    fun stop() {
        running = false
        val c = conn
        Thread {
            try {
                c?.disconnect()
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun open(u: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) LiveTV")
        conn = c
        return c
    }

    private fun readText(u: String): String =
        open(u).inputStream.bufferedReader().use { it.readText() }

    private fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref).toString()
    } catch (e: Exception) {
        ref
    }

    private fun copyStream(c: HttpURLConnection, out: OutputStream) {
        val buf = ByteArray(16384)
        c.inputStream.use { ins ->
            while (running) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                bytes += n
            }
        }
    }

    private fun record(url: String, out: OutputStream) {
        val c = open(url)
        val ct = (c.contentType ?: "").lowercase()
        val isPlaylist = url.lowercase().contains(".m3u8") || ct.contains("mpegurl")
        if (!isPlaylist) {
            copyStream(c, out)
            return
        }
        val first = c.inputStream.bufferedReader().use { it.readText() }
        var mediaUrl = url
        var text = first
        if (first.contains("#EXT-X-STREAM-INF")) {
            mediaUrl = pickVariant(first, url)
            text = readText(mediaUrl)
        }
        val seen = HashSet<String>()
        var empty = 0
        while (running) {
            if (text.contains("#EXT-X-MAP")) throw Exception("unsupported")
            if (text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")) {
                throw Exception("encrypted")
            }
            var added = false
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val seg = resolve(mediaUrl, line)
                if (!seen.add(seg)) continue
                added = true
                copyStream(open(seg), out)
                if (!running) break
            }
            if (text.contains("#EXT-X-ENDLIST")) break
            empty = if (added) 0 else empty + 1
            if (empty > 20) break
            Thread.sleep(2000)
            if (!running) break
            text = readText(mediaUrl)
        }
    }

    private fun pickVariant(text: String, base: String): String {
        val lines = text.lines().map { it.trim() }
        var best: String? = null
        var bestBw = -1L
        var low: String? = null
        var lowBw = Long.MAX_VALUE
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.size) {
                val m = Regex("BANDWIDTH=(\\d+)").find(lines[i])
                val bw = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val u = lines[i + 1]
                if (u.isEmpty() || u.startsWith("#")) continue
                if (bw <= 3000000L && bw > bestBw) {
                    bestBw = bw
                    best = u
                }
                if (bw < lowBw) {
                    lowBw = bw
                    low = u
                }
            }
        }
        val pick = best ?: low ?: lines.first { it.isNotEmpty() && !it.startsWith("#") }
        return resolve(base, pick)
    }

    private fun finish(f: File): String {
        if (!f.exists() || f.length() == 0L) {
            return "ریکارڈنگ خالی رہی، یہ چینل ریکارڈ نہیں ہو سکتا"
        }
        return try {
            val mime = if (isRadio) "audio/mpeg" else "video/mp2t"
            val folder = if (isRadio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/LiveTV")
                val coll = if (isRadio) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val uri = ctx.contentResolver.insert(coll, v)
                    ?: return "فائل ایپ فولڈر میں ہے: ${f.absolutePath}"
                ctx.contentResolver.openOutputStream(uri)!!.use { o ->
                    f.inputStream().use { it.copyTo(o) }
                }
                f.delete()
                "محفوظ ہو گئی: $folder/LiveTV"
            } else {
                val pub = File(Environment.getExternalStoragePublicDirectory(folder), "LiveTV")
                pub.mkdirs()
                val dest = File(pub, f.name)
                f.copyTo(dest, true)
                f.delete()
                MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
                "محفوظ ہو گئی: $folder/LiveTV"
            }
        } catch (e: Exception) {
            "فائل ایپ فولڈر میں ہے: ${f.absolutePath}"
        }
    }
}
