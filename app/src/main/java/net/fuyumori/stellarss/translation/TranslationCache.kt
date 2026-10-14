package net.fuyumori.stellarss.translation

import net.fuyumori.stellarss.ArticleHtml
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Disposable derived data. Expiration is measured from creation, never from the last read. */
class TranslationCache(private val directory: File, private val maxEntries: Int = 32,
                       private val maxBytes: Long = 8L * 1024 * 1024,
                       private val clock: () -> Long = System::currentTimeMillis) {
    fun key(source: ArticleHtml.Source): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Engine version, destination and exact selected source prevent stale or wrong-language hits.
        listOf("mlkit-17.0.3-text-v1", "ja", source.title, source.html).forEach {
            digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun read(key: String): TranslatedArticle? = synchronized(LOCK) {
        require(key.matches(Regex("[a-f0-9]{64}")))
        val file = File(directory, "$key.json")
        if (!file.isFile) return@synchronized null
        try {
            require(file.length() <= MAX_ENTRY)
            val value = JSONObject(file.readText())
            require(value.getString("key") == key && value.getString("target") == "ja")
            require(fresh(value.optLong("createdAt", 0)))
            val result = TranslatedArticle(value.getString("title"), value.getString("html"), value.getString("source"), "ja")
            require(result.html.length <= ArticleHtml.MAX_HTML && result.title.length <= TranslationText.MAX_TEXT * 8)
            result
        } catch (_: Exception) { file.delete(); null }
    }
    fun write(key: String, value: TranslatedArticle) = synchronized(LOCK) {
        require(key.matches(Regex("[a-f0-9]{64}")))
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized
            val bytes = JSONObject().put("key", key).put("title", value.title).put("html", value.html)
                .put("source", value.sourceLanguage).put("target", value.targetLanguage)
                .put("createdAt", clock()).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_ENTRY) return@synchronized
            val pending = File(directory, "$key.tmp")
            try {
                pending.writeBytes(bytes)
                val target = File(directory, "$key.json")
                if (pending.renameTo(target)) target.setLastModified(clock())
            } finally { pending.delete() }
            // Metadata-only trimming here: don't reread up to 512 payloads after every headline.
            trim()
        } catch (_: Exception) { /* Disk pressure must not turn successful translation into a failure. */ }
    }
    /** Called on normal app/refresh activity; no timer, alarm or extra periodic worker. */
    fun prune() = synchronized(LOCK) {
        directory.listFiles { f -> f.extension == "json" }.orEmpty().forEach { file ->
            val valid = runCatching {
                file.length() <= MAX_ENTRY && fresh(JSONObject(file.readText()).optLong("createdAt", 0))
            }.getOrDefault(false)
            if (!valid) file.delete()
        }
        trim()
    }
    private fun trim() {
        val files = directory.listFiles { f -> f.extension == "json" }.orEmpty().filter { file ->
            val valid = fresh(file.lastModified())
            if (!valid) file.delete()
            valid
        }.sortedByDescending { it.lastModified() }
        var total = 0L
        files.forEachIndexed { index, file ->
            total += file.length()
            if (index >= maxEntries || total > maxBytes) file.delete()
        }
    }
    private fun fresh(createdAt: Long): Boolean {
        val now = clock()
        return createdAt > 0 && createdAt <= now && now - createdAt < MAX_AGE_MILLIS
    }
    companion object {
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private val LOCK = Any()
        private const val MAX_ENTRY = 1024 * 1024
    }
}
