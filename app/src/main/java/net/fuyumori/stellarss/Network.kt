package net.fuyumori.stellarss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followSslRedirects(false).build()
    fun get(url: String, etag: String? = null, modified: String? = null): Response {
        val safe = webUrl(url) ?: throw IOException("HTTP(S) URLを指定してください")
        val request = Request.Builder().url(safe).header("User-Agent", "StellaRSS/0.1 (Android)")
        etag?.let { request.header("If-None-Match", it) }
        modified?.let { request.header("If-Modified-Since", it) }
        return client.newCall(request.build()).execute()
    }
    fun bytes(response: Response, limit: Int): ByteArray {
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException("空の応答です")
        if (body.contentLength() > limit) throw IOException("応答が大きすぎます")
        return body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > limit) throw IOException("応答が大きすぎます")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }
}

/** Bounded disk cache; decoding and eviction never execute on the main thread. */
class Images(context: Context) {
    private val directory = File(context.cacheDir, "article-images")
    private val lock = Mutex()
    private fun file(url: String) = File(directory, digest(url) + ".jpg")
    suspend fun cached(url: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (url == null) return@withContext null
        lock.withLock {
            file(url).takeIf { it.isFile }?.let { f ->
                BitmapFactory.decodeFile(f.path)?.also { f.setLastModified(System.currentTimeMillis()) }
            }
        }
    }
    suspend fun fetch(url: String): Bitmap? = withContext(Dispatchers.IO) {
        cached(url)?.let { return@withContext it }
        val raw = Http.get(url).use { Http.bytes(it, 6 * 1024 * 1024) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 960) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
        lock.withLock {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("画像キャッシュを作成できません")
            val temp = File.createTempFile("image-", ".tmp", directory)
            try {
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (!temp.renameTo(file(url))) throw IOException("画像キャッシュを保存できません")
                val all = directory.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.lastModified() }.orEmpty()
                var total = all.sumOf { it.length() }
                all.forEach { f -> if (total > 64L * 1024 * 1024) { total -= f.length(); f.delete() } }
            } finally { temp.delete() }
        }
        bitmap
    }
}
