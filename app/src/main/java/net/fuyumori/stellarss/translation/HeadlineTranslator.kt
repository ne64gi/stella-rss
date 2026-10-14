package net.fuyumori.stellarss.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import net.fuyumori.stellarss.ArticleHtml
import kotlin.coroutines.coroutineContext

data class HeadlineSource(val id: String, val title: String, val sample: String = title)
data class HeadlineResult(val titles: Map<String, String>, val missingModels: Set<String>)

/** A bounded viewport request, not a subscription-wide queue. One translator at a time. */
class HeadlineTranslator(private val cache: TranslationCache, private val factory: () -> TranslationProvider) {
    fun cached(title: String): String? = cache.read(key(title))?.title
    private fun key(title: String) = cache.key(ArticleHtml.Source(title, "", ""))

    suspend fun translate(visible: List<HeadlineSource>, download: ModelDownload = ModelDownload.NONE,
                          onTitle: suspend (String, String) -> Unit = { _, _ -> }): HeadlineResult {
        val titles = linkedMapOf<String, String>()
        val missing = mutableSetOf<String>()
        var provider: TranslationProvider? = null
        var preparedLanguage: String? = null
        val unavailable = mutableSetOf<String>()
        try {
            for (source in visible.take(MAX_VISIBLE)) {
                coroutineContext.ensureActive()
                if (source.title.isBlank() || source.title.length > 1000) continue
                try {
                    var title = cached(source.title)
                    if (title == null) {
                        val client = provider ?: factory().also { provider = it }
                        val language = client.identify(source.sample.take(2000))
                        if (language.isBlank() || language == "und" || language in unavailable) continue
                        if (language == "ja") title = source.title
                        else {
                            if (preparedLanguage != language) {
                                preparedLanguage = null
                                try { client.prepare(language, "ja", download) }
                                catch (e: MissingModels) { missing.addAll(e.languages); unavailable.add(language); continue }
                                preparedLanguage = language
                            }
                            title = client.translate(source.title)
                            if (title.isBlank() || title.length > source.title.length * 8 + 1024) continue
                        }
                        coroutineContext.ensureActive()
                        cache.write(key(source.title), TranslatedArticle(title, "", language, "ja"))
                    }
                    coroutineContext.ensureActive()
                    titles[source.id] = title
                    onTitle(source.id, title)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep the original headline; one failure must not hide the list. */ }
            }
        } finally { provider?.close() }
        return HeadlineResult(titles, missing)
    }
    companion object { const val MAX_VISIBLE = 20 }
}
