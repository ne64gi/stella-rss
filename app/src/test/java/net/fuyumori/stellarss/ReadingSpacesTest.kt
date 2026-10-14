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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReadingSpacesTest {
    private fun fixture(block: suspend (RssDatabase, Organization, ReadingSpaces) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RssDatabase::class.java).build()
        try { block(db, Organization(db), ReadingSpaces(db)) } finally { db.close() }
    }
    @Test fun disabledByDefaultAndTogglingKeepsSelectionDataAndPolicies() = fixture { db, org, spaces ->
        assertEquals(ReadingSpaces.State(), spaces.state())
        val world = spaces.save(name = "英語圏", target = "ja")
        val folder = org.saveFolder(name = "World", containerId = world)
        val feed = db.store().add(Feed(url = "https://example.com/a", containerId = world))
        org.classify(feed, setOf(folder))
        spaces.setEnabled(true); spaces.select(world)
        assertEquals("ja", spaces.translationTarget(feed))
        spaces.setEnabled(false)
        assertEquals(ReadingSpaces.State(false, world), spaces.watch().first())
        assertEquals("", spaces.translationTarget(feed))
        assertEquals(setOf(feed), org.library().inContainer(null).feedIds(folder))
        spaces.setEnabled(true)
        assertEquals(world, spaces.state().selected)
        assertEquals("ja", spaces.translationTarget(feed))
        db.store().update(db.store().feed(feed)!!.copy(translationTarget = ""))
        assertEquals("", spaces.translationTarget(feed))
        db.store().update(db.store().feed(feed)!!.copy(translationTarget = "ja"))
        spaces.setEnabled(false)
        assertEquals("ja", spaces.translationTarget(feed))
    }
    @Test fun selectionUnreadSavedAndWidgetFiltersDoNotLeakOtherContainers() = fixture { db, org, spaces ->
        val world = spaces.save(name = "World")
        val local = db.store().add(Feed(url = "https://example.com/local"))
        val global = db.store().add(Feed(url = "https://example.com/global", containerId = world))
        val folder = org.saveFolder(name = "World", containerId = world); org.classify(global, setOf(folder))
        db.store().insert(Article("local", local, "Local", "https://example.com/l", "", 1, bookmarked = true))
        db.store().insert(Article("global", global, "Global", "https://example.com/g", "", 2, bookmarked = true))
        val library = org.library().copy(unread = db.store().watchUnread().first(), saved = db.store().watchSavedCounts().first())
        val selected = library.inContainer(world)
        assertEquals(setOf(global), selected.feeds.map { it.id }.toSet())
        assertEquals(0, selected.count()); assertEquals(1, selected.saved.sumOf { it.count })
        assertEquals(listOf("global"), db.store().watchSelection(selected.feeds.map { it.id }, false, false, true).first().map { it.article.id })
        assertEquals(listOf("global"), org.widgetRows(WidgetConfig(1, containerId = world)).map { it.article.id })
        assertTrue(org.widgetRows(WidgetConfig(1, containerId = world, feedIds = "$local")).isEmpty())
        assertTrue(org.widgetRows(WidgetConfig(1, containerId = 1, folderId = folder)).isEmpty())
        assertTrue(org.widgetRows(WidgetConfig(1, containerId = 999)).isEmpty())
        spaces.setEnabled(false)
        assertEquals(2, org.widgetRows(WidgetConfig(1)).size)
        assertEquals(listOf("global"), org.widgetRows(WidgetConfig(1, containerId = world)).map { it.article.id })
        db.store().bookmark("global", false)
        val after = org.library().copy(unread = db.store().watchUnread().first(), saved = db.store().watchSavedCounts().first()).inContainer(world)
        assertEquals(1, after.count()); assertEquals(0, after.saved.sumOf { it.count })
    }
    @Test fun movingBranchRetainsArticlesAndOnlyRemovesOutsideClassifications() = fixture { db, org, spaces ->
        val target = spaces.save(name = "World")
        val root = org.saveFolder(name = "News"); val child = org.saveFolder(name = "Child", parent = root)
        val other = org.saveFolder(name = "Other")
        val feed = db.store().add(Feed(url = "https://example.com/feed", etag = "kept"))
        org.classify(feed, setOf(root, child, other))
        val article = Article("kept", feed, "Kept", "https://example.com/a", "", 1, read = true, bookmarked = true, bodyHtml = "<p>Body</p>")
        db.store().insert(article)
        spaces.moveFolder(root, target)
        assertEquals(article, db.store().article("kept"))
        assertEquals("kept", db.store().feed(feed)!!.etag)
        assertEquals(target, db.store().feed(feed)!!.containerId)
        assertEquals(setOf(root, child), db.store().memberships().map { it.folderId }.toSet())
        assertEquals(root, db.store().folders().first { it.id == child }.parentId)
        assertEquals(1L, db.store().folders().first { it.id == other }.containerId)
        assertTrue(runCatching { org.classify(feed, setOf(other)) }.isFailure)
        assertTrue(runCatching { org.saveFolder(child, "Child", other) }.isFailure)
        spaces.moveFeed(feed, 1)
        assertTrue(db.store().memberships().isEmpty())
        assertEquals(article, db.store().article("kept"))
    }
    @Test fun deletionAndConflictingMovesFailAtomicallyAndSelectedEmptyDeleteFallsBack() = fixture { db, org, spaces ->
        val target = spaces.save(name = "World")
        val root = org.saveFolder(name = "News"); org.saveFolder(name = "News", containerId = target)
        assertTrue(runCatching { spaces.moveFolder(root, target) }.isFailure)
        assertEquals(1L, db.store().folders().first { it.id == root }.containerId)
        assertTrue(runCatching { spaces.deleteEmpty(target) }.isFailure)
        assertTrue(runCatching { spaces.deleteEmpty(1) }.isFailure)
        val empty = spaces.save(name = "Empty"); spaces.select(empty); spaces.deleteEmpty(empty)
        assertEquals(1L, spaces.state().selected)
        assertTrue(runCatching { spaces.save(name = "world") }.isFailure)
    }
    @Test fun opmlTargetsContainerAndCrossContainerDuplicateRollsBack() = fixture { db, org, spaces ->
        val target = spaces.save(name = "World")
        val items = Opml.parse("<opml><body><outline text='News'><outline text='Feed' xmlUrl='https://example.com/feed'/></outline></body></opml>".byteInputStream())
        assertEquals(ImportResult(1, 1), org.importOpml(items, target))
        assertEquals(target, db.store().feeds().single().containerId)
        assertTrue(runCatching { org.importOpml(items, 1) }.isFailure)
        assertEquals(1, db.store().folders().size)
        assertEquals(target, db.store().folders().single().containerId)
    }
}
