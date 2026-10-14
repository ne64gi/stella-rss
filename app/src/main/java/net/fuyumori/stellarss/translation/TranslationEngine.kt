package net.fuyumori.stellarss.translation

import kotlinx.coroutines.ensureActive
import net.fuyumori.stellarss.ArticleHtml
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.io.Closeable
import kotlin.coroutines.coroutineContext

data class TranslatedArticle(val title: String, val html: String, val sourceLanguage: String, val targetLanguage: String)
enum class ModelDownload { NONE, WIFI, ANY_NETWORK }
class MissingModels(val languages: Set<String>) : Exception("翻訳用の言語データが必要です")
class TranslationUnavailable(message: String) : Exception(message)

/** All text processing is local. A provider exists only during a foreground request. */
interface TranslationProvider : Closeable {
    suspend fun identify(sample: String): String
    suspend fun prepare(source: String, target: String, download: ModelDownload)
    suspend fun translate(text: String): String
}

class TranslationEngine(private val cache: TranslationCache, private val factory: () -> TranslationProvider) {
    suspend fun translate(source: ArticleHtml.Source, download: ModelDownload = ModelDownload.NONE): TranslatedArticle {
        val key = cache.key(source)
        cache.read(key)?.let { return it }
        val plan = TranslationText(source)
        return factory().use { provider ->
            val language = provider.identify(plan.sample)
            if (language == "ja") throw TranslationUnavailable("この記事は日本語です")
            if (language.isBlank() || language == "und") throw TranslationUnavailable("記事の言語を判別できませんでした")
            provider.prepare(language, "ja", download)
            val result = plan.render(language) { provider.translate(it) }
            coroutineContext.ensureActive()
            cache.write(key, result)
            result
        }
    }
}

/** Translate text nodes, never markup. Links, images and code retain their original structure. */
internal class TranslationText(private val source: ArticleHtml.Source) {
    private val document = Jsoup.parseBodyFragment(source.html)
    private val nodes: List<TextNode> = document.body().nodeStream().iterator().asSequence().filter { it is TextNode }.map { it as TextNode }
        .filter { node ->
            generateSequence(node.parent()) { it.parent() }.none { it.nodeName() in setOf("pre", "code") } &&
                node.wholeText.any { it.isLetter() } && !node.wholeText.trim().startsWith("https://") && !node.wholeText.trim().startsWith("http://")
        }.toList()
    val sample: String = (source.title + "\n" + nodes.joinToString(" ") { it.wholeText }).take(2000)
    init {
        if (source.title.length + nodes.sumOf { it.wholeText.length } > MAX_TEXT || nodes.size > 512)
            throw TranslationUnavailable("この記事は長いため、概要を優先してから翻訳してください")
        if (sample.none { it.isLetter() }) throw TranslationUnavailable("翻訳できる文章がありません")
    }
    suspend fun render(language: String, translate: suspend (String) -> String): TranslatedArticle {
        suspend fun text(input: String): String {
            val pieces = split(input)
            return pieces.joinToStringSuspending { part ->
                coroutineContext.ensureActive()
                if (part.isBlank()) part else {
                    val prefix = part.takeWhile(Char::isWhitespace)
                    val suffix = part.takeLastWhile(Char::isWhitespace)
                    val value = translate(part.trim())
                    require(value.length <= part.length * 8 + 1024) { "翻訳結果が大きすぎます" }
                    prefix + value + suffix
                }
            }
        }
        val title = text(source.title)
        nodes.forEach { it.text(text(it.wholeText)) }
        document.outputSettings().prettyPrint(false)
        val html = document.body().html()
        if (html.length > ArticleHtml.MAX_HTML) throw TranslationUnavailable("翻訳結果が大きすぎます")
        return TranslatedArticle(title, html, language, "ja")
    }
    companion object {
        const val MAX_TEXT = 32_000
        internal fun split(text: String): List<String> {
            val result = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = (start + 2000).coerceAtMost(text.length)
                if (end < text.length) {
                    val boundary = text.lastIndexOfAny(charArrayOf('\n', ' ', '。'), end - 1)
                    if (boundary > start + 1000) end = boundary + 1
                    if (Character.isHighSurrogate(text[end - 1])) end--
                }
                result.add(text.substring(start, end)); start = end
            }
            return result
        }
    }
}

private suspend fun List<String>.joinToStringSuspending(transform: suspend (String) -> String): String {
    val result = StringBuilder()
    for (part in this) result.append(transform(part))
    return result.toString()
}
