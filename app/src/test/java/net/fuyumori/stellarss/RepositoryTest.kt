package net.fuyumori.stellarss

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {
    private lateinit var db: RssDatabase
    private lateinit var repo: Repository
    private lateinit var server: MockWebServer
    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RssDatabase::class.java).build()
        repo = Repository(context, db)
        server = MockWebServer(); server.start()
    }
    @After fun tearDown() { server.shutdown(); db.close() }
    private fun body(title: String = "one") = "<rss><channel><title>Test</title><item><guid>stable</guid><title>$title</title><link>${server.url("/article")}</link></item></channel></rss>"
    @Test fun validators304ReadStateAndUndatedOrderingSurviveRefresh() = runBlocking {
        val feed = db.store().add(Feed(name = "test", url = server.url("/rss").toString(), showImages = false))
        server.enqueue(MockResponse().setBody(body()).addHeader("ETag", "\"v1\"").addHeader("Last-Modified", "Wed, 02 Oct 2002 08:00:00 GMT"))
        assertFalse(repo.sync())
        val first = db.store().recent(feed).single()
        db.store().markRead(first.id, true)
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(304))
        assertFalse(repo.sync())
        val request = server.takeRequest()
        assertEquals("\"v1\"", request.getHeader("If-None-Match"))
        assertEquals("Wed, 02 Oct 2002 08:00:00 GMT", request.getHeader("If-Modified-Since"))
        assertTrue(db.store().article(first.id)!!.read)
        server.enqueue(MockResponse().setBody(body("updated")))
        assertFalse(repo.sync())
        val updated = db.store().recent(feed).single()
        assertEquals("updated", updated.title); assertTrue(updated.read); assertEquals(first.published, updated.published)
        assertNull(db.store().feed(feed)!!.etag)
    }
    @Test fun legacyValidatorsDoNotPreventBodyBackfillAndReadBookmarkSurvive() = runBlocking {
        val feed = db.store().add(Feed(name = "old", url = server.url("/rss").toString(), showImages = false, etag = "old"))
        val id = digest("$feed\nstable")
        db.store().insert(Article(id, feed, "old", server.url("/article").toString(), "old summary", 1, read = true, bookmarked = true))
        server.enqueue(MockResponse().setBody(body().replace("</item>", "<description><![CDATA[<p>Retained summary</p>]]></description><content:encoded xmlns:content='http://purl.org/rss/1.0/modules/content/'><![CDATA[<h2>Complete body</h2>]]></content:encoded></item>")).addHeader("ETag", "new"))
        assertFalse(repo.sync())
        assertNull(server.takeRequest().getHeader("If-None-Match"))
        val article = db.store().article(id)!!
        assertTrue(article.read); assertTrue(article.bookmarked); assertTrue(article.bodyHtml.contains("<h2>Complete body</h2>"))
        assertEquals(1, db.store().feed(feed)!!.contentVersion)
        server.enqueue(MockResponse().setResponseCode(304)); assertFalse(repo.sync())
        assertEquals("new", server.takeRequest().getHeader("If-None-Match"))
        assertEquals(article, db.store().article(id))
    }
    @Test fun malformedResponseKeepsArticlesAndOldValidators() = runBlocking {
        val feed = db.store().add(Feed(name = "test", url = server.url("/rss").toString(), showImages = false))
        server.enqueue(MockResponse().setBody(body()).addHeader("ETag", "old")); repo.sync()
        server.enqueue(MockResponse().setBody("<html>wrong</html>").addHeader("ETag", "bad"))
        assertTrue(repo.sync()); assertEquals(1, db.store().recent(feed).size)
        assertEquals("old", db.store().feed(feed)!!.etag); assertNotNull(db.store().feed(feed)!!.error)
    }
    @Test fun feedDeletionCascadesButOtherFeedAndIndependentWidgetsRemain() = runBlocking {
        val one = db.store().add(Feed(name = "one", url = "https://one.test/feed"))
        val two = db.store().add(Feed(name = "two", url = "https://two.test/feed"))
        db.store().insert(Article("a", one, "one", "https://one.test/a", "", 1))
        db.store().insert(Article("b", two, "two", "https://two.test/a", "", 2))
        db.store().save(WidgetConfig(1, feedIds = one.toString(), opacity = 0, fontSp = 24))
        db.store().save(WidgetConfig(2, feedIds = two.toString(), opacity = 100, fontSp = 12))
        db.store().deleteFeed(one)
        assertNull(db.store().article("a")); assertNotNull(db.store().article("b"))
        assertEquals(0, db.store().widget(1)!!.opacity); assertEquals(100, db.store().widget(2)!!.opacity)
        assertEquals(0, Widgets.select(db.store().widgetArticles(), db.store().widget(1)!!).size)
        assertEquals("b", Widgets.select(db.store().widgetArticles(), db.store().widget(2)!!).single().article.id)
    }
    @Test fun lateImageCompletionCannotOverwriteRefreshedSource() = runBlocking {
        val feed = db.store().add(Feed(name = "one", url = "https://one.test/feed"))
        db.store().insert(Article("same", feed, "before", "https://one.test/old", "", 1, imageCandidates = "https://one.test/old.jpg"))
        db.store().updateContent("same", "after", "https://one.test/new", "", 1, "https://one.test/new.jpg")
        assertEquals(0, db.store().setResolvedImage("same", "https://one.test/old.jpg", 100, "https://one.test/old.jpg", "https://one.test/old"))
        assertNull(db.store().article("same")!!.imageUrl)
        assertEquals(1, db.store().setResolvedImage("same", "https://one.test/new.jpg", 101, "https://one.test/new.jpg", "https://one.test/new"))
        assertEquals("https://one.test/new.jpg", db.store().article("same")!!.imageUrl)
    }
    @Test fun widgetSelectionIsAppliedBeforeLimitAcrossManyFeeds() = runBlocking {
        val selected = db.store().add(Feed(name = "older", url = "https://older.test/feed"))
        val other = db.store().add(Feed(name = "newer", url = "https://newer.test/feed"))
        db.store().insert(Article("wanted", selected, "Older selected article", "https://older.test/a", "", 1))
        db.withTransaction {
            for (n in 1..1001) db.store().insert(Article("new-$n", other, "Other article", "https://newer.test/$n", "", n + 100L))
        }
        assertEquals("wanted", db.store().widgetArticlesFor(listOf(selected), false, false).single().article.id)
    }
    @Test fun widgetFilteringSortAndUnreadAreIndependent() = runBlocking {
        val id = db.store().add(Feed(name = "one", url = "https://one.test/feed"))
        for (n in 1..8) db.store().insert(Article(n.toString(), id, "Article $n", "https://one.test/$n", "", n.toLong(), read = n == 8))
        val rows = db.store().widgetArticles()
        assertEquals(listOf(8L, 7, 6, 5, 4), Widgets.select(rows, WidgetConfig(1)).map { it.article.published })
        assertEquals(listOf(7L, 6, 5, 4, 3), Widgets.select(rows, WidgetConfig(2, unreadOnly = true)).map { it.article.published })
    }
}
