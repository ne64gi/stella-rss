package net.fuyumori.stellarss

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
    @Test fun folderSubscriptionIsAtomicAndExistingSubscriptionIsReused() = runBlocking {
        androidx.work.WorkManager.initialize(ApplicationProvider.getApplicationContext(), androidx.work.Configuration.Builder().build())
        val store = db.store(); val org = Organization(db)
        val root = org.saveFolder(name = "Development")
        val child = org.saveFolder(name = "Kotlin", parent = root)
        val feed = Feed(name = "Original", url = server.url("/folder-rss").toString(), enabled = false, showImages = false)
        val id = repo.saveFeed(feed, child)
        store.insert(Article("retained", id, "Retained", "https://example.org/", "", 1, read = true, bookmarked = true))
        assertEquals(listOf(FolderFeed(child, id)), store.memberships())
        assertEquals(id, repo.saveFeed(feed.copy(name = "Do not overwrite", showImages = true), root))
        assertEquals(1, store.feeds().size)
        assertEquals("Original", store.feed(id)!!.name)
        assertFalse(store.feed(id)!!.showImages)
        assertTrue(store.article("retained")!!.read && store.article("retained")!!.bookmarked)
        assertEquals(setOf(root, child), store.memberships().map { it.folderId }.toSet())
        assertEquals(setOf(id), org.library().feedIds(root))
        assertEquals(id, repo.saveFeed(feed, root)) // Repeating an add is idempotent.
        assertEquals(2, store.memberships().size)
    }
    @Test fun deletedDestinationDoesNotLeaveAnUnclassifiedSubscription() = runBlocking {
        val store = db.store(); val org = Organization(db)
        val folder = org.saveFolder(name = "Removed"); org.deleteFolder(folder)
        try {
            repo.saveFeed(Feed(url = server.url("/new").toString(), enabled = false), folder)
            fail("Deleted destination must fail")
        } catch (_: IllegalArgumentException) { }
        assertTrue(store.feeds().isEmpty()); assertTrue(store.memberships().isEmpty())
        val deleted = store.add(Feed(url = server.url("/deleted").toString(), enabled = false))
        val value = store.feed(deleted)!!; store.deleteFeed(deleted)
        try { repo.saveFeed(value); fail("Deleted feed must not appear to save") }
        catch (_: IllegalArgumentException) { }
        assertTrue(store.feeds().isEmpty())
    }
    @Test fun bulkReadIncludesOlderThanDisplayLimitAndUndoSurvivesRefresh() = runBlocking {
        val store = db.store()
        val feed = store.add(Feed(name = "many", url = server.url("/rss").toString(), showImages = false))
        db.withTransaction {
            for (n in 1..1205) store.insert(Article("bulk-$n", feed, "Article $n", "https://example.org/$n", "", n.toLong()))
            store.insert(Article("was-read", feed, "Read", "https://example.org/read", "", 0, read = true))
            store.insert(Article("saved", feed, "Saved", "https://example.org/saved", "", 0, bookmarked = true))
        }
        assertEquals(1000, store.watchSelection(emptyList(), true, false, false).first().size)
        assertEquals(1205, repo.markAllRead(emptyList(), true, false, 60000))
        val undo = repo.swipeUndo.value!!
        assertEquals(0, repo.markAllRead(emptyList(), true, false, 60000))
        assertEquals(undo, repo.swipeUndo.value)
        assertTrue(store.article("bulk-1")!!.read)
        assertFalse(store.article("saved")!!.read)
        // Refresh would normally prune older read rows; keep them until Undo expires.
        server.enqueue(MockResponse().setBody(body("New arrival")))
        assertFalse(repo.sync())
        assertNotNull(store.article("bulk-1"))
        store.bookmark("bulk-2", true)
        assertTrue(repo.undoSwipe(undo))
        assertEquals(1205, store.unreadIds(emptyList(), true, false).size) // 1204 originals + new arrival
        assertFalse(store.article(digest("$feed\nstable"))!!.read)
        assertTrue(store.article("was-read")!!.read)
        assertTrue(store.article("bulk-2")!!.bookmarked)
        assertFalse(store.article("bulk-2")!!.read)
        assertFalse(store.article("saved")!!.read)
    }
    @Test fun bulkScopeDeduplicatesNestedFoldersAndKeepsSavedAndDisabledSeparate() = runBlocking {
        val store = db.store(); val org = Organization(db)
        val root = org.saveFolder(name = "root"); val child = org.saveFolder(name = "child", parent = root)
        val inside = store.add(Feed(name = "inside", url = "https://inside.test/rss"))
        val outside = store.add(Feed(name = "outside", url = "https://outside.test/rss"))
        val disabled = store.add(Feed(name = "disabled", url = "https://disabled.test/rss", enabled = false))
        org.classify(inside, setOf(root, child)); org.classify(disabled, setOf(child))
        for ((id, feed) in listOf("inside" to inside, "outside" to outside, "disabled" to disabled))
            store.insert(Article(id, feed, id, "https://example.org/", "", 1))
        store.insert(Article("saved", inside, "saved", "https://example.org/", "", 2, bookmarked = true))
        val ids = org.library().feedIds(root).toList()
        assertEquals(1, repo.markAllRead(ids + ids, false, false, 8000))
        assertTrue(store.article("inside")!!.read)
        assertFalse(store.article("outside")!!.read); assertFalse(store.article("disabled")!!.read)
        assertFalse(store.article("saved")!!.read)
        val undo = repo.swipeUndo.value!!
        assertEquals(0, repo.markAllRead(emptyList(), false, false, 8000))
        assertTrue(repo.undoSwipe(undo))
        assertEquals(setOf("inside", "outside"), store.watchSelection(emptyList(), true, false, false).first().map { it.article.id }.toSet())
        assertEquals(2, store.watchUnread().first().sumOf { it.count })
        assertEquals(listOf("saved"), store.watchSelection(emptyList(), true, false, true).first().map { it.article.id })
        assertEquals(1, repo.markAllRead(emptyList(), true, true, 8000))
        assertTrue(store.article("saved")!!.read); assertTrue(store.article("saved")!!.bookmarked)
        assertFalse(store.article("inside")!!.read)
        assertTrue(repo.undoSwipe(repo.swipeUndo.value!!))
        store.bookmark("saved", false)
        assertEquals(3, store.watchUnread().first().sumOf { it.count })
        assertEquals(3, store.watchSelection(emptyList(), true, false, false).first().size)
    }
    @Test fun swipeUndoRestoresOnlyChangedFieldAndRejectsSupersededAction() = runBlocking {
        val store = db.store()
        val feed = store.add(Feed(name = "test", url = server.url("/rss").toString()))
        store.insert(Article("undo", feed, "one", "https://example.org/", "", 1))
        assertTrue(repo.swipeArticle("undo", true, 8000))
        val read = repo.swipeUndo.value!!
        assertTrue(repo.swipeArticle("undo", false, 8000))
        val saved = repo.swipeUndo.value!!
        assertFalse(repo.undoSwipe(read))
        assertFalse(repo.swipeArticle("undo", false, 8000))
        assertEquals(saved, repo.swipeUndo.value)
        assertTrue(repo.undoSwipe(saved))
        assertTrue(store.article("undo")!!.read)
        assertFalse(store.article("undo")!!.bookmarked)
        store.markRead("undo", false)
        assertTrue(repo.swipeArticle("undo", true, 8000))
        store.bookmark("undo", true)
        assertTrue(repo.undoSwipe(repo.swipeUndo.value!!))
        assertFalse(store.article("undo")!!.read)
        assertTrue(store.article("undo")!!.bookmarked)
    }
    @Test fun expiredUndoAndAlreadyReadDoNotAlterState() = runBlocking {
        val store = db.store()
        val feed = store.add(Feed(name = "test", url = server.url("/rss").toString()))
        store.insert(Article("undo", feed, "one", "https://example.org/", "", 1, read = true))
        assertFalse(repo.swipeArticle("undo", true, 8000)); assertNull(repo.swipeUndo.value)
        assertTrue(repo.swipeArticle("undo", false, -1))
        assertFalse(repo.undoSwipe(repo.swipeUndo.value!!))
        assertTrue(store.article("undo")!!.read); assertTrue(store.article("undo")!!.bookmarked)
        assertNull(repo.swipeUndo.value)
    }
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
