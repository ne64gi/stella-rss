package net.fuyumori.stellarss

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only task-owned fixture subscriptions/folders are modified or removed. */
@RunWith(AndroidJUnit4::class)
class LibraryManagementChecks {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private fun find(v: View, predicate: (View) -> Boolean): View? {
        if (predicate(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), predicate)?.let { return it }
        return null
    }
    private fun waitFor(reason: String, timeoutMs: Long = 15000, test: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) { ins.waitForIdleSync(); if (test()) return; SystemClock.sleep(80) }
        assertTrue(reason, test())
    }
    private fun tapText(text: String) {
        waitFor("Menu: $text") {
            ins.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString() == text }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        // A successful accessibility action is queued; allow the next window to attach.
        ins.waitForIdleSync(); SystemClock.sleep(350)
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(400); ins.waitForIdleSync()
        val bitmap = ins.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun treeSearchDirectFolderAddDetailsAndDeleteConfirmation() = runBlocking {
        val repo = context.rss; val store = repo.store; val org = Organization(repo.db)
        val token = System.nanoTime(); val prefix = "library-ui-$token"
        val root = org.saveFolder(name = "00 開発ラボ $token")
        val nested = org.saveFolder(name = "Android", parent = root)
        val leaf = org.saveFolder(name = "Kotlin", parent = nested)
        val feeds = mutableListOf<Long>()
        val newUrl = "https://example.org/$prefix/new"
        var scenario: ActivityScenario<MainActivity>? = null
        var editor: FeedActivity? = null
        try {
            for (i in 0..59) {
                val name = if (i == 0) "A とても長いフィード名でもニュースを読みやすく整理できる開発情報の購読テスト" else "購読 ${i.toString().padStart(2, '0')}"
                val id = store.add(Feed(name = name, url = "https://example.org/$prefix/$i", enabled = false)); feeds.add(id)
                org.classify(id, setOf(if (i == 0) leaf else root))
            }
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val active = scenario
            active.onActivity { it.root.keepScreenOn = true; it.findViewById<DrawerLayout>(R.id.reader_drawer).openDrawer(Gravity.LEFT, false) }
            fun row(tag: String, action: (View) -> Unit) = waitFor("Tree row $tag") {
                var done = false; active.onActivity { a ->
                    a.findViewById<DrawerLayout>(R.id.reader_drawer).openDrawer(Gravity.LEFT, false)
                    find(a.root) { it.tag == tag && it.isShown }?.let { action(it); done = true }
                }; done
            }
            fun search(value: String) {
                active.onActivity { it.findViewById<DrawerLayout>(R.id.reader_drawer).openDrawer(Gravity.LEFT, false); it.findViewById<EditText>(R.id.library_search).setText(value) }
                ins.waitForIdleSync(); SystemClock.sleep(180)
            }
            val rootTag = "tree:folder:$root:0"; val nestedTag = "tree:folder:$nested:$root"; val leafTag = "tree:folder:$leaf:$nested"
            waitFor("Window focus") { var focus = false; active.onActivity { focus = it.hasWindowFocus() }; focus }
            waitFor("Many feeds") { var size = 0; active.onActivity { size = it.findViewById<RecyclerView>(R.id.folder_tree).adapter!!.itemCount }; size >= 65 }
            row("tree:feed:${feeds[0]}:$leaf") { line ->
                val title = find(line) { it is TextView && it.text.toString().startsWith("A とても長い") } as TextView
                assertEquals(1, title.maxLines); assertTrue(title.layout.getEllipsisCount(0) > 0)
            }
            screenshot("library-tree-device.png")
            // Large library remains virtualized and scrollable, with all fixture subscriptions reachable.
            active.onActivity { val list = it.findViewById<RecyclerView>(R.id.folder_tree); assertTrue(list.canScrollVertically(1)); list.scrollToPosition(60) }
            waitFor("Scrolled tree") { var yes = false; active.onActivity { yes = it.findViewById<RecyclerView>(R.id.folder_tree).canScrollVertically(-1) }; yes }
            search("とても長い")
            row(rootTag) {}; row(nestedTag) {}; row(leafTag) {}; row("tree:feed:${feeds[0]}:$leaf") {}
            active.onActivity { assertEquals(6, it.findViewById<RecyclerView>(R.id.folder_tree).adapter!!.itemCount) }
            screenshot("library-search-device.png")
            search("no-match-$token")
            waitFor("Empty search") { var found = false; active.onActivity { a -> found = find(a.root) { it is TextView && it.text.toString() == "該当するフィードはありません" } != null }; found }
            search("")
            active.onActivity { it.findViewById<RecyclerView>(R.id.folder_tree).scrollToPosition(0) }
            // Separate disclosure arrow collapses without selecting the folder.
            row(rootTag) { find(it) { v -> v.contentDescription?.toString()?.endsWith("を開閉") == true }!!.performClick() }
            active.onActivity { a -> assertNull(find(a.root) { it.tag == nestedTag }) }
            row(rootTag) { find(it) { v -> v.contentDescription?.toString()?.endsWith("を開閉") == true }!!.performClick() }
            // Context menu destination is the clicked folder, independent of current article scope.
            val monitor = ins.addMonitor(FeedActivity::class.java.name, null, false)
            row(leafTag) { find(it) { v -> v.contentDescription?.toString() == "Kotlinの設定" }!!.performClick() }
            tapText("このフォルダにフィードを追加")
            editor = ins.waitForMonitorWithTimeout(monitor, 8000) as? FeedActivity ?: error("No add screen")
            ins.removeMonitor(monitor)
            waitFor("Destination visible") { var ready = false; ins.runOnMainSync { ready = editor!!.findViewById<TextView>(R.id.feed_destination)?.text?.contains("Android / Kotlin") == true }; ready }
            ins.runOnMainSync {
                editor!!.root.keepScreenOn = true
                editor!!.findViewById<EditText>(R.id.feed_url).setText(newUrl)
                editor!!.findViewById<EditText>(R.id.feed_name).setText("Kotlin weekly")
                editor!!.findViewById<CheckBox>(R.id.feed_enabled).isChecked = false
            }
            screenshot("library-add-folder-device.png")
            ins.runOnMainSync { editor!!.findViewById<View>(R.id.feed_save).performClick() }
            waitFor("Atomic subscription and classification") { runBlocking {
                store.feeds().firstOrNull { it.url == newUrl }?.let { f -> store.memberships().any { it.feedId == f.id && it.folderId == leaf } } == true
            } }
            editor = null
            val added = store.feeds().first { it.url == newUrl }.id
            // Top plus inherits the selected folder as well.
            row(leafTag) { it.performClick() }
            active.onActivity { it.findViewById<DrawerLayout>(R.id.reader_drawer).openDrawer(Gravity.LEFT, false); it.findViewById<View>(R.id.library_add).performClick() }
            val plusMonitor = ins.addMonitor(FeedActivity::class.java.name, null, false)
            tapText("このフォルダにフィードを追加")
            editor = ins.waitForMonitorWithTimeout(plusMonitor, 8000) as? FeedActivity ?: error("No plus add screen")
            ins.removeMonitor(plusMonitor)
            waitFor("Plus destination") { var ok = false; ins.runOnMainSync { ok = editor!!.findViewById<TextView>(R.id.feed_destination)?.text?.contains("Android / Kotlin") == true }; ok }
            ins.runOnMainSync { editor!!.finish() }; editor = null
            waitFor("Back to tree") { var ok = false; active.onActivity { ok = it.hasWindowFocus() }; ok }
            fun addedMenu() = row("tree:feed:$added:$leaf") { find(it) { v -> v.contentDescription?.toString() == "Kotlin weeklyの設定" }!!.performClick() }
            val detailMonitor = ins.addMonitor(FeedActivity::class.java.name, null, false)
            addedMenu(); tapText("編集・表示設定")
            editor = ins.waitForMonitorWithTimeout(detailMonitor, 8000) as? FeedActivity ?: error("No details")
            ins.removeMonitor(detailMonitor)
            waitFor("Single feed loaded") { var ok = false; ins.runOnMainSync { ok = editor!!.findViewById<EditText>(R.id.feed_url)?.text?.toString() == newUrl }; ok }
            ins.runOnMainSync { assertEquals("Kotlin weekly", editor!!.findViewById<EditText>(R.id.feed_name).text.toString()); editor!!.finish() }; editor = null
            waitFor("Details returned") { var ok = false; active.onActivity { ok = it.hasWindowFocus() }; ok }
            addedMenu(); tapText("削除"); tapText("キャンセル")
            assertNotNull(store.feed(added)); assertTrue(store.memberships().contains(FolderFeed(leaf, added)))
            addedMenu(); tapText("削除"); screenshot("library-delete-confirm-device.png"); tapText("削除")
            waitFor("Only fixture subscription deleted after in-flight refresh", 180000) { runBlocking { store.feed(added) == null } }
            assertEquals(60, feeds.count { store.feed(it) != null })
            // Real long-press drag still moves one membership, retaining another classification.
            org.classify(feeds[0], setOf(root, leaf)); search("とても長い")
            assertTrue(store.memberships().contains(FolderFeed(root, feeds[0])))
            active.onActivity { it.findViewById<RecyclerView>(R.id.folder_tree).scrollToPosition(0) }
            screenshot("library-before-drag-device.png")
            var start = floatArrayOf(); var end = floatArrayOf()
            fun center(v: View): FloatArray { val r = Rect(); assertTrue(v.getGlobalVisibleRect(r)); return floatArrayOf(r.left + r.width() * .55f, r.exactCenterY()) }
            row("tree:feed:${feeds[0]}:$root") { start = center(it) }; row(nestedTag) { end = center(it) }
            SystemClock.sleep(400)
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, x: Float, y: Float) {
                val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; assertTrue(ins.uiAutomation.injectInputEvent(e, true)); e.recycle()
            }
            touch(MotionEvent.ACTION_DOWN, start[0], start[1]); SystemClock.sleep(750)
            for (step in 1..18) { touch(MotionEvent.ACTION_MOVE, start[0] + (end[0] - start[0]) * step / 18, start[1] + (end[1] - start[1]) * step / 18); SystemClock.sleep(30) }
            touch(MotionEvent.ACTION_UP, end[0], end[1])
            waitFor("Real drag membership moved") { runBlocking { store.memberships().contains(FolderFeed(nested, feeds[0])) } }
            assertTrue(store.memberships().contains(FolderFeed(leaf, feeds[0])))
            assertFalse(store.memberships().contains(FolderFeed(root, feeds[0])))
            ins.sendStatus(0, android.os.Bundle().apply { putString("stream", "LIBRARY_UI: 60 feeds/scroll, long name ellipsis, ancestor-preserving search, separate disclosure, contextual folder add, selected-folder plus, single-feed details, delete cancel/confirm, native long-press drag preserving other membership PASS\n") })
        } catch (failure: Throwable) {
            screenshot("library-failure-device.png")
            scenario?.onActivity { a ->
                val list = a.findViewById<RecyclerView>(R.id.folder_tree)
                val tags = (0 until list.childCount).map { list.getChildAt(it).tag }
                ins.sendStatus(0, android.os.Bundle().apply { putString("stream", "TREE_DEBUG: count=${list.adapter?.itemCount}, visible=$tags, syncing=${repo.refreshing.value}, search=${a.findViewById<EditText>(R.id.library_search).text}\n") })
            }
            throw failure
        } finally {
            editor?.let { ins.runOnMainSync { it.finish() } }; scenario?.close()
            store.feeds().filter { it.url.startsWith("https://example.org/$prefix/") }.forEach { store.deleteFeed(it.id) }
            org.deleteFolder(root); Widgets.updateAll(context)
        }
    }
    @Test fun addDraftAndDestinationSurviveRecreationWithoutSaving() = runBlocking {
        val org = Organization(context.rss.db)
        val root = org.saveFolder(name = "Draft fixture ${System.nanoTime()}")
        val url = "https://example.org/unsaved-${System.nanoTime()}"
        try {
            ActivityScenario.launch<FeedActivity>(Intent(context, FeedActivity::class.java).putExtra("add", true).putExtra("folder", root)).use { active ->
                fun ready() = waitFor("Draft form loaded") { var ok = false; active.onActivity { ok = it.findViewById<CheckBox>(R.id.feed_summary) != null }; ok }
                ready()
                active.onActivity {
                    it.root.keepScreenOn = true
                    it.findViewById<EditText>(R.id.feed_url).setText(url)
                    it.findViewById<EditText>(R.id.feed_name).setText("Unsaved name")
                    it.findViewById<CheckBox>(R.id.feed_images).isChecked = false
                }
                active.recreate(); ready()
                active.onActivity {
                    assertEquals(url, it.findViewById<EditText>(R.id.feed_url).text.toString())
                    assertEquals("Unsaved name", it.findViewById<EditText>(R.id.feed_name).text.toString())
                    assertFalse(it.findViewById<CheckBox>(R.id.feed_images).isChecked)
                    assertTrue(it.findViewById<TextView>(R.id.feed_destination).text.contains("Draft fixture"))
                }
            }
            assertTrue(context.rss.store.feeds().none { it.url == url })
        } finally { org.deleteFolder(root) }
    }

}
