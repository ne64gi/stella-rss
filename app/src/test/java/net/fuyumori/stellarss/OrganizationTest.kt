package net.fuyumori.stellarss

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OrganizationTest {
    private fun fixture(block: suspend (RssDatabase, Organization) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RssDatabase::class.java).build()
        try { block(db, Organization(db)) } finally { db.close() }
    }
    @Test fun descendantsMultiMembershipCountsSelectionAndWidgetsDeduplicate() = fixture { db, org ->
        val store = db.store()
        val root = org.saveFolder(name = "Tech"); val ai = org.saveFolder(name = "AI", parent = root)
        val llm = org.saveFolder(name = "LLM", parent = ai); val world = org.saveFolder(name = "World")
        val feed = store.add(Feed(name = "Shared", url = "https://example.com/rss"))
        org.classify(feed, setOf(root, llm, world))
        store.insert(Article("a", feed, "Unread", "https://example.com/a", "", 2))
        store.insert(Article("b", feed, "Read", "https://example.com/b", "", 1, read = true))
        val tree = org.library().copy(unread = store.watchUnread().first())
        assertEquals(setOf(root, ai, llm), tree.descendants(root))
        assertEquals(setOf(feed), tree.feedIds(root)); assertEquals(1, tree.count(tree.feedIds(root))); assertEquals(1, tree.count())
        assertEquals(listOf("a", "b"), store.watchSelection(tree.feedIds(root).toList(), false, false, false).first().map { it.article.id })
        assertEquals(listOf("a"), org.widgetRows(WidgetConfig(1, folderId = root, unreadOnly = true)).map { it.article.id })
        val newFeed = store.add(Feed(name = "New", url = "https://example.com/new"))
        org.classify(newFeed, setOf(llm)); store.insert(Article("c", newFeed, "Newest", "https://example.com/c", "", 3))
        assertEquals(listOf("c", "a", "b"), org.widgetRows(WidgetConfig(1, folderId = root)).map { it.article.id })
        assertTrue(org.widgetRows(WidgetConfig(1, folderId = 9999)).isEmpty())
    }
    @Test fun movesRejectCyclesAndDeleteOnlyClassification() = fixture { db, org ->
        val a = org.saveFolder(name = "A"); val b = org.saveFolder(name = "B", parent = a); val c = org.saveFolder(name = "C")
        val feed = db.store().add(Feed(url = "https://example.com/rss")); org.classify(feed, setOf(b, c))
        db.store().insert(Article("keep", feed, "Keep", "https://example.com/a", "", 1, read = true))
        assertTrue(runCatching { org.saveFolder(a, "A", b) }.isFailure)
        assertTrue(runCatching { org.saveFolder(b, "B", b) }.isFailure)
        org.moveFeed(feed, b, a)
        assertEquals(setOf(a, c), db.store().memberships().map { it.folderId }.toSet())
        org.saveFolder(b, "B", c)
        assertEquals(c, db.store().folders().first { it.id == b }.parentId)
        org.deleteFolder(c)
        assertNotNull(db.store().feed(feed)); assertTrue(db.store().article("keep")!!.read)
        assertEquals(setOf(a), db.store().memberships().map { it.folderId }.toSet())
    }
    @Test fun opmlRoundTripPreservesNestedAndMultipleMembershipsMergesWithoutReset() = fixture { db, org ->
        val source = """<?xml version="1.0"?><opml version="2.0"><body>
          <outline text="Tech &amp; OSS"><outline text="AI"><outline text="Shared" type="rss" xmlUrl="https://example.com/rss"/></outline></outline>
          <outline text="World"><outline title="Shared again" xmlUrl="https://example.com/rss"/></outline>
          <outline text="Unfiled" type="rss" xmlUrl="https://example.com/root"/>
          <outline text="Empty"/>
        </body></opml>"""
        val result = org.importOpml(Opml.parse(source.byteInputStream()))
        assertEquals(ImportResult(2, 4), result)
        val feed = db.store().feeds().first { it.url.endsWith("rss") }
        db.store().update(feed.copy(enabled = false, showImages = false, etag = "kept"))
        db.store().insert(Article("kept", feed.id, "Kept", "https://example.com/a", "", 1, read = true, bookmarked = true))
        val out = ByteArrayOutputStream(); Opml.write(org.library(), out)
        assertEquals(ImportResult(0, 0), org.importOpml(Opml.parse(out.toByteArray().inputStream())))
        assertEquals(2, db.store().feeds().size); assertEquals(2, db.store().memberships().size)
        assertEquals("kept", db.store().feed(feed.id)!!.etag); assertFalse(db.store().feed(feed.id)!!.enabled)
        assertTrue(db.store().article("kept")!!.bookmarked)
        val fresh = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RssDatabase::class.java).build()
        try {
            val copy = Organization(fresh); copy.importOpml(Opml.parse(out.toByteArray().inputStream()))
            assertEquals(org.library().folders.map { org.library().path(it) }.toSet(), copy.library().folders.map { copy.library().path(it) }.toSet())
            assertEquals(2, fresh.store().memberships().size)
        } finally { fresh.close() }
    }
    @Test fun rejectsUnsafeMalformedAndExcessiveOpmlWithoutImportingAnything() = fixture { db, org ->
        for (bad in listOf("<rss/>", "<!DOCTYPE opml [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><opml><body/></opml>",
            "<opml><body><outline xmlUrl='javascript:alert(1)'/></body></opml>",
            "<opml><body>" + "<outline text='x'>".repeat(40) + "</outline>".repeat(40) + "</body></opml>",
            "<opml><body><outline></body></opml>")) {
            assertTrue(bad, runCatching { org.importOpml(Opml.parse(bad.byteInputStream())) }.isFailure)
        }
        assertTrue(db.store().feeds().isEmpty()); assertTrue(db.store().folders().isEmpty())
    }
    @Test fun scopedQueryFiltersBeforeLimitAndSavedArticlesSurviveTrim() = fixture { db, org ->
        val many = db.store().add(Feed(url = "https://example.com/many")); val old = db.store().add(Feed(url = "https://example.com/old"))
        for (i in 0..1002) db.store().insert(Article("many-$i", many, "News", "https://example.com/$i", "", i + 10L, read = true))
        db.store().insert(Article("saved", many, "Saved", "https://example.com/save", "", 1, read = true, bookmarked = true))
        db.store().insert(Article("old", old, "Old", "https://example.com/old-a", "", 1))
        assertEquals("old", db.store().watchSelection(listOf(old), false, false, false).first().single().article.id)
        assertEquals("saved", db.store().watchSelection(emptyList(), true, false, true).first().single().article.id)
        db.store().trimRead(many); assertNotNull(db.store().article("saved"))
        assertTrue(db.store().watchSelection(emptyList(), false, false, false).first().isEmpty())
    }
}
