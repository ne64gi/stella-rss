package net.fuyumori.stellarss

import kotlinx.coroutines.*
import net.fuyumori.stellarss.translation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import androidx.recyclerview.widget.RecyclerView
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HeadlineTranslationTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = ArticleHtml.Source("Original headline", "<p>Original body</p>", "")
    private val translated = TranslatedArticle("日本語の見出し", "<p>本文</p>", "en", "ja")

    @Test fun expirationIsSevenDaysFromCreationNotFromRepeatedReads() {
        var now = 1_800_000_000_000L
        val dir = temp.newFolder(); val cache = TranslationCache(dir, clock = { now }); val key = cache.key(source)
        cache.write(key, translated)
        now += TranslationCache.MAX_AGE_MILLIS - 1
        assertEquals(translated, cache.read(key)); assertEquals(translated, cache.read(key))
        now++
        assertNull(cache.read(key)); assertFalse(File(dir, "$key.json").exists())
    }
    @Test fun cleanupRemovesUnvisitedExpiredLegacyAndFutureEntriesButKeepsFreshOnes() {
        var now = 1_800_000_000_000L
        val dir = temp.newFolder(); val cache = TranslationCache(dir, clock = { now }); val old = cache.key(source)
        cache.write(old, translated); now += TranslationCache.MAX_AGE_MILLIS
        val fresh = cache.key(source.copy(title = "New")); cache.write(fresh, translated)
        val legacy = File(dir, "legacy.json").apply { writeText("{}") }
        val future = File(dir, "future.json").apply { writeText(JSONObject().put("createdAt", now + 1).toString()) }
        cache.prune()
        assertFalse(File(dir, "$old.json").exists()); assertFalse(legacy.exists()); assertFalse(future.exists())
        assertEquals(translated, cache.read(fresh))
    }
    private class Provider : TranslationProvider {
        val calls = mutableListOf<String>(); val downloads = mutableListOf<ModelDownload>()
        var closes = 0; var prepares = 0
        var missing = false; var wait: CompletableDeferred<Unit>? = null
        override suspend fun identify(sample: String) = if (sample.startsWith("日本語")) "ja" else "en"
        override suspend fun prepare(source: String, target: String, download: ModelDownload) {
            prepares++; downloads.add(download)
            if (missing && download == ModelDownload.NONE) throw MissingModels(setOf("ja"))
        }
        override suspend fun translate(text: String): String { calls.add(text); wait?.await(); return "訳:$text" }
        override fun close() { closes++ }
    }
    @Test fun onlyBoundedVisibleInputIsTranslatedSequentiallyAndCacheAvoidsSdkAllocation() = runBlocking {
        val provider = Provider(); val cache = TranslationCache(temp.newFolder(), 512)
        val engine = HeadlineTranslator(cache) { provider }
        val visible = (0 until 4).map { HeadlineSource("id:$it", "headline $it") }
        val result = engine.translate(visible)
        assertEquals(4, provider.calls.size); assertEquals(1, provider.prepares); assertEquals(1, provider.closes)
        assertEquals(listOf(ModelDownload.NONE), provider.downloads); assertEquals(4, result.titles.size)
        assertEquals(result, HeadlineTranslator(cache) { error("cache must avoid model loading") }.translate(visible))
        val bounded = Provider()
        HeadlineTranslator(TranslationCache(temp.newFolder(), 512)) { bounded }
            .translate((0 until 266).map { HeadlineSource("$it", "headline $it") })
        assertEquals(HeadlineTranslator.MAX_VISIBLE, bounded.calls.size)
    }
    @Test fun cancellationStopsRemainingViewportAndDoesNotCacheHalfTranslatedTitle() = runBlocking {
        val p = Provider().apply { wait = CompletableDeferred() }; val engine = HeadlineTranslator(TranslationCache(temp.newFolder())) { p }
        val job = launch { engine.translate(listOf(HeadlineSource("1", "first"), HeadlineSource("2", "second"))) }
        yield(); job.cancelAndJoin()
        assertEquals(listOf("first"), p.calls); assertEquals(1, p.closes); assertNull(engine.cached("first"))
    }
    @Test fun missingModelsNeverDownloadAutomaticallyAndJapaneseIsPreserved() = runBlocking {
        val p = Provider().apply { missing = true }; val cache = TranslationCache(temp.newFolder())
        val engine = HeadlineTranslator(cache) { p }
        val result = engine.translate(listOf(HeadlineSource("a", "foreign"), HeadlineSource("b", "another"), HeadlineSource("c", "日本語の記事")))
        assertEquals(setOf("ja"), result.missingModels); assertEquals(mapOf("c" to "日本語の記事"), result.titles)
        assertTrue(p.calls.isEmpty()); assertEquals(listOf(ModelDownload.NONE), p.downloads); assertEquals(1, p.closes)
        assertEquals("日本語の記事", HeadlineTranslator(cache) { error("no repeat detection") }.cached("日本語の記事"))
    }
    @Test fun explicitFeedOverrideWinsAndContainerPolicyIsInactiveWhenExtensionIsOff() {
        val containers = listOf(NewsContainer(1, "Local"), NewsContainer(2, "Overseas", "ja"))
        val feeds = listOf(Feed(1, url = "https://example.com/1", containerId = 2),
            Feed(2, url = "https://example.com/2", containerId = 2, translationTarget = ""),
            Feed(3, url = "https://example.com/3", translationTarget = "ja"))
        assertEquals(setOf(1L, 3L), ReadingSpaces.automaticFeedIds(feeds, containers, true))
        assertEquals(setOf(3L), ReadingSpaces.automaticFeedIds(feeds, containers, false))
    }
    @Test fun viewportSelectionNeverSchedulesUnreadRowsOutsideVisibleRange() {
        val rows = (0 until 266).map { i -> ArticleRow(Article("$i", if (i == 51) 2 else 1,
            "Headline $i", "https://example.com/$i", "Summary", 1), "Feed", false, true) }
        assertEquals(listOf("50", "52", "53"), HeadlineTranslation.visibleSources(rows, 50, 53, setOf(1)).map { it.id })
        assertTrue(HeadlineTranslation.visibleSources(rows, -1, -1, setOf(1)).isEmpty())
        assertTrue(HeadlineTranslation.visibleSources(rows, 50, 53, emptySet()).isEmpty())
    }
    @Test fun titleUpdatesOnlyNotifyChangedRowsAndRestoringOriginalsDoesNotRefreshTheWholeList() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
        val adapter = ArticleAdapter(activity.get())
        adapter.submitList((0 until 266).map { i -> ArticleRow(Article("$i", 1,
            "Headline $i", "https://example.com/$i", "Summary", 1), "Feed", false, true) })
        val changed = mutableListOf<Int>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { fail("No full refresh for a headline") }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                assertEquals("headline", payload); assertEquals(1, itemCount); changed.add(positionStart)
            }
        })
        adapter.showTitles(mapOf("50" to "翻訳済み", "51" to "Headline 51"))
        assertEquals(listOf(50), changed); changed.clear()
        adapter.showTitles(mapOf("50" to "翻訳済み", "51" to "Headline 51", "52" to "次の見出し"))
        assertEquals(listOf(52), changed); changed.clear()
        adapter.showTitles(mapOf("50" to "翻訳済み", "52" to "次の見出し", "not-in-list" to "別の記事"))
        assertTrue(changed.isEmpty())
        adapter.showTitles(emptyMap())
        assertEquals(listOf(50, 52), changed)
    }
}
