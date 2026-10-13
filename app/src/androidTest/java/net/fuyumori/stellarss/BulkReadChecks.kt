package net.fuyumori.stellarss

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BulkReadChecks {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private fun find(v: View, predicate: (View) -> Boolean): View? {
        if (predicate(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), predicate)?.let { return it }
        return null
    }
    private fun waitFor(reason: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < until) { ins.waitForIdleSync(); if (check()) return; SystemClock.sleep(60) }
        assertTrue(reason, check())
    }
    @Test fun selectedFolderBulkReadUndoAndSavedInboxSeparation() = runBlocking {
        val repo = context.rss; val store = repo.store; val org = Organization(repo.db)
        val token = System.nanoTime()
        val first = store.add(Feed(name = "STELLA LAB", url = "https://example.org/bulk-$token", showImages = false))
        val second = store.add(Feed(name = "DEV JOURNAL", url = "https://example.org/bulk-child-$token", showImages = false))
        val outside = store.add(Feed(name = "OUTSIDE", url = "https://example.org/bulk-other-$token", showImages = false))
        val root = org.saveFolder(name = "00 テクノロジー $token")
        val child = org.saveFolder(name = "開発", parent = root)
        var scenario: ActivityScenario<MainActivity>? = null
        fun id(n: Int) = "bulk-ui-$token-$n"
        try {
            org.classify(first, setOf(root, child)); org.classify(second, setOf(child))
            val titles = listOf("Androidの新機能をチェック", "注目のOSS更新と開発ツール", "フォルダのニュースをまとめて読む", "すでに読んだ記事", "あとでじっくり読む記事", "別のフォルダの記事")
            titles.forEachIndexed { n, title ->
                store.insert(Article(id(n), if (n == 5) outside else if (n == 2) second else first,
                    title, "https://example.org/", "必要なニュースを整理して、読みたい記事だけを一覧に。", System.currentTimeMillis() - n * 60000,
                    read = n == 3, bookmarked = n == 4))
            }
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val active = scenario
            active.onActivity { it.root.keepScreenOn = true }
            waitFor("Visible reader window") { var focused = false; active.onActivity { focused = it.hasWindowFocus() }; focused }
            waitFor("Folder available") { var ok = false; active.onActivity { a -> find(a.root) { it.tag == "tree:folder:$root:0" }?.let { it.performClick(); ok = true } }; ok }
            fun count(expected: Int) = waitFor("List count $expected") { var n = -1; active.onActivity { n = it.findViewById<RecyclerView>(R.id.article_list).adapter!!.itemCount }; n == expected }
            count(4) // Saved article is absent, even from All.
            active.onActivity { a -> find(a.root) { it is TextView && it.text.toString() == "未読" }!!.performClick() }
            count(3)
            active.onActivity { it.findViewById<View>(R.id.article_mark_all_read).performClick() }
            count(0)
            assertTrue((0..3).all { store.article(id(it))!!.read })
            assertFalse(store.article(id(4))!!.read); assertTrue(store.article(id(4))!!.bookmarked)
            assertFalse(store.article(id(5))!!.read)
            active.recreate()
            active.onActivity { it.root.keepScreenOn = true }
            waitFor("Bulk Undo after recreation") { var ok = false; active.onActivity { a ->
                val v = a.findViewById<View>(R.id.article_undo)
                if (v.isShown && v.isEnabled) { v.performClick(); ok = true }
            }; ok }
            count(3)
            assertFalse((0..2).any { store.article(id(it))!!.read }); assertTrue(store.article(id(3))!!.read)
            // Use the actual Saved screen / unsave menu, not a replacement database command.
            active.onActivity { a -> find(a.root) { it.tag == "tree:saved:0:0" }!!.performClick() }
            waitFor("Saved article visible") { var ok = false; active.onActivity { a -> find(a.root) { it.tag == "article:${id(4)}" }?.let { it.performLongClick(); ok = true } }; ok }
            waitFor("Unsave menu") {
                ins.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("あとで読むから外す")?.firstOrNull()
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            waitFor("Saved removed") { runBlocking { !store.article(id(4))!!.bookmarked } }
            assertFalse(store.article(id(4))!!.read)
            active.onActivity { a -> find(a.root) { it.tag == "tree:folder:$root:0" }!!.performClick() }
            count(4)
            store.bookmark(id(4), true); count(3)
            active.onActivity { a -> find(a.root) { it is TextView && it.text.toString() == "すべて" }!!.performClick() }
            count(4)
            active.onActivity { it.findViewById<View>(R.id.article_mark_all_read).performClick() }
            waitFor("Bulk completion message") { var ok = false; active.onActivity { a -> ok = find(a.root) { it is TextView && it.text.toString() == "3件を既読にしました" }?.isShown == true }; ok }
            waitFor("Read styling applied") { var ok = false; active.onActivity { a ->
                val row = find(a.root) { it.tag == "article:${id(0)}" }
                val title = row?.findViewById<TextView>(R.id.article_title)
                ok = title != null && title.currentTextColor == MUTED && title.typeface?.isBold != true
            }; ok }
            SystemClock.sleep(250)
            active.onActivity { a ->
                val bitmap = Bitmap.createBitmap(a.root.width, a.root.height, Bitmap.Config.ARGB_8888)
                a.root.draw(Canvas(bitmap)); File(context.filesDir, "reader-bulk-read-device.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                a.findViewById<View>(R.id.article_undo).performClick()
            }
            waitFor("Read restored after screenshot") { runBlocking { (0..2).none { store.article(id(it))!!.read } } }
            ins.sendStatus(0, android.os.Bundle().apply { putString("stream", "BULK_READ: folder descendants/dedup + only unread + outside/saved preserved + empty unread list + Undo/recreation + saved removal/restoration + screenshot PASS\n") })
        } finally {
            scenario?.close(); repo.swipeUndo.value?.let(repo::dismissSwipeUndo)
            org.deleteFolder(root); listOf(first, second, outside).forEach { store.deleteFeed(it) }; Widgets.updateAll(context)
        }
    }
}
