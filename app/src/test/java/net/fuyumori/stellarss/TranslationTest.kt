package net.fuyumori.stellarss

import kotlinx.coroutines.*
import net.fuyumori.stellarss.translation.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TranslationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = ArticleHtml.Source("A title", "<p>English <b>story</b>.</p><a href='https://example.com'>Link</a><img src='https://example.com/a.jpg'><pre>raw code</pre>", "")
    private class FakeProvider : TranslationProvider {
        var closed = false
        var prepared = false
        var calls = 0
        var language = "en"
        var missing = false
        var wait: CompletableDeferred<Unit>? = null
        override suspend fun identify(sample: String): String { wait?.await(); return language }
        override suspend fun prepare(source: String, target: String, download: ModelDownload) {
            if (missing && download == ModelDownload.NONE) throw MissingModels(setOf("ja"))
            prepared = true
        }
        override suspend fun translate(text: String): String { check(prepared); calls++; return "訳<$text>" }
        override fun close() { closed = true }
    }
    @Test fun textOnlyTranslationPreservesStructureEscapesOutputAndCachesWithoutAnotherProvider() = runBlocking {
        val cache = TranslationCache(temporary.newFolder())
        val fake = FakeProvider()
        val result = TranslationEngine(cache) { fake }.translate(source)
        assertTrue(fake.closed); assertTrue(fake.calls >= 3)
        val doc = Jsoup.parseBodyFragment(result.html)
        assertEquals("raw code", doc.selectFirst("pre")!!.text())
        assertEquals("https://example.com", doc.selectFirst("a")!!.attr("href"))
        assertEquals("https://example.com/a.jpg", doc.selectFirst("img")!!.attr("src"))
        assertTrue(doc.text().contains("訳<English>"))
        assertFalse(doc.html().contains("<English>"))
        assertEquals(source.title, "A title")
        assertEquals(result, TranslationEngine(cache) { error("cached request must not allocate SDK resources") }.translate(source))
    }
    @Test fun exactSelectedContentAndTitleChangeCacheIdentity() {
        val cache = TranslationCache(temporary.newFolder())
        assertNotEquals(cache.key(source), cache.key(source.copy(title = "New title")))
        assertNotEquals(cache.key(source), cache.key(source.copy(html = "<p>Other summary</p>")))
        assertEquals(cache.key(source), cache.key(source.copy(notice = "Presentation-only notice")))
    }
    @Test fun missingModelsRequireConsentAndAlwaysReleaseResources() = runBlocking {
        val cache = TranslationCache(temporary.newFolder())
        val denied = FakeProvider().apply { missing = true }
        assertTrue(runCatching { TranslationEngine(cache) { denied }.translate(source) }.exceptionOrNull() is MissingModels)
        assertTrue(denied.closed); assertFalse(denied.prepared); assertEquals(0, denied.calls)
        val allowed = FakeProvider().apply { missing = true }
        TranslationEngine(cache) { allowed }.translate(source, ModelDownload.WIFI)
        assertTrue(allowed.prepared); assertTrue(allowed.closed)
    }
    @Test fun cancellationClosesProviderAndNeverCachesIncompleteResult() = runBlocking {
        val cache = TranslationCache(temporary.newFolder())
        val fake = FakeProvider().apply { wait = CompletableDeferred() }
        val job = launch { TranslationEngine(cache) { fake }.translate(source) }
        yield(); job.cancelAndJoin()
        assertTrue(fake.closed); assertNull(cache.read(cache.key(source)))
    }
    @Test fun nativeLanguageAndUnrecognizedLanguageDoNotLoadTranslationModels() = runBlocking {
        for (language in listOf("ja", "und")) {
            val fake = FakeProvider().apply { this.language = language }
            assertTrue(runCatching { TranslationEngine(TranslationCache(temporary.newFolder())) { fake }.translate(source) }.exceptionOrNull() is TranslationUnavailable)
            assertTrue(fake.closed); assertFalse(fake.prepared)
        }
    }
    @Test fun boundedInputRejectsBeforeAllocatingProviderAndChunksPreserveUnicode() = runBlocking {
        val big = source.copy(html = "<p>" + "English ".repeat(5000) + "</p>")
        assertTrue(runCatching { TranslationEngine(TranslationCache(temporary.newFolder())) { error("must not allocate") }.translate(big) }.exceptionOrNull() is TranslationUnavailable)
        val unicode = "a".repeat(1999) + "😀" + "世界".repeat(3000)
        val parts = TranslationText.split(unicode)
        assertEquals(unicode, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 2000 && !Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first()) })
    }
    @Test fun cacheIsBoundedAndCorruptEntriesAreDisposable() {
        val directory = temporary.newFolder()
        val cache = TranslationCache(directory)
        for (i in 0 until 40) cache.write(cache.key(source.copy(title = "$i")), TranslatedArticle("訳", "<p>文章</p>", "en", "ja"))
        assertEquals(32, directory.listFiles()!!.size)
        val key = cache.key(source)
        java.io.File(directory, "$key.json").writeText("broken")
        assertNull(cache.read(key)); assertFalse(java.io.File(directory, "$key.json").exists())
        assertTrue(runCatching { cache.read("../outside") }.isFailure)
    }
}
