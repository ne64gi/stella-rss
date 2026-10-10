package net.fuyumori.stellarss

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

@RunWith(AndroidJUnit4::class)
class ReaderChecks {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = context.rss.store
    private val org get() = Organization(context.rss.db)
    @Test fun denseReaderTreeCountsClassificationDragAndOpmlOnDevice() = runBlocking {
        val token = System.nanoTime()
        val feed = store.add(Feed(name = "STELLA LAB", url = "https://example.com/reader-$token", showImages = true))
        val other = store.add(Feed(name = "OSS JOURNAL", url = "https://example.com/oss-$token", showImages = false))
        val root = org.saveFolder(name = "01 テクノロジー ${token % 1000}")
        val ai = org.saveFolder(name = "AI", parent = root)
        val llm = org.saveFolder(name = "LLM", parent = ai)
        val world = org.saveFolder(name = "02 世界ニュース ${token % 1000}")
        val imageUrl = "https://example.com/reader-image-$token.png"
        val imageFile = File(context.cacheDir, "article-images/${digest(imageUrl)}.jpg")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            org.classify(feed, setOf(root, llm)); org.classify(other, setOf(root))
            // Deterministic image/no-image layout fixture, independent of the user's subscriptions.
            val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            val drawable = context.getDrawable(R.drawable.image_fallback)!!
            drawable.setBounds(0, 0, 192, 192); drawable.draw(Canvas(bitmap))
            imageFile.parentFile!!.mkdirs(); imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
            val photo = imageUrl
            val titles = listOf("Androidの新機能をチェック。毎日の操作を軽くする改善", "ローカルAIの最新動向と、端末で動かすための工夫", "注目のOSS更新：開発ツールの使いやすさを見直す", "新型デバイス発表、持ち歩きやすさと電池持ちのバランス", "情報を整理するフォルダとフィードの新しい読み方", "ネットワークとセキュリティの今週のトピック")
            titles.forEachIndexed { index, title ->
                store.insert(Article("reader-$token-$index", if (index == 5) other else feed, title, "https://example.com/", "大切なニュースを短く把握。詳しい内容は元記事で確認できます。", System.currentTimeMillis() - index * 3600000, read = index == 4, imageUrl = if (index < 2) photo else null, imageChecked = System.currentTimeMillis()))
            }
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val active = scenario
            // Physical edge gesture, not a direct openDrawer call.
            var edge = IntArray(2); var drawerWidth = 0
            active.onActivity { activity -> val drawer = activity.findViewById<DrawerLayout>(R.id.reader_drawer); drawer.getLocationOnScreen(edge); drawerWidth = drawer.width }
            gesture(edge[0] + context.dp(4).toFloat(), edge[1] + context.dp(240).toFloat(), edge[0] + drawerWidth * .75f, edge[1] + context.dp(240).toFloat())
            waitFor { var open = false; active.onActivity { open = it.findViewById<DrawerLayout>(R.id.reader_drawer).isDrawerOpen(Gravity.LEFT) }; open }
            clickTag(active, "tree:folder:$root:0")
            waitFor { var count = 0; active.onActivity { count = it.findViewById<RecyclerView>(R.id.article_list).adapter!!.itemCount }; count == 6 }
            var visible = 0
            active.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.article_list)
                for (index in 0 until list.childCount) {
                    val row = list.getChildAt(index)
                    if (row.bottom <= list.height && row.top >= 0) {
                        visible++
                        val heightDp = row.height / activity.resources.displayMetrics.density
                        assertTrue("row height $heightDp", heightDp in 96f..120f)
                        val title = row.findViewById<TextView>(R.id.article_title)
                        assertEquals(2, title.maxLines)
                        assertTrue(title.layout.getLineBottom(minOf(title.layout.lineCount, 2) - 1) <= title.height - title.totalPaddingTop - title.totalPaddingBottom)
                    }
                }
            }
            assertTrue("Expected at least 4 fully visible rows, got $visible", visible >= 4)
            active.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.article_list)
                for (index in 0..2) {
                    val row = list.getChildAt(index) as ViewGroup
                    val line = row.getChildAt(0) as ViewGroup
                    val image = line.getChildAt(1) as android.widget.ImageView
                    assertEquals(if (index < 2) View.VISIBLE else View.GONE, image.visibility)
                    if (index < 2) assertEquals(activity.dp(72), image.width)
                }
            }
            capture(active, "reader-dense-device.png")
            // Bookmark through the actual long-press action; restore state via our fixture cleanup.
            active.onActivity { it.findViewById<RecyclerView>(R.id.article_list).getChildAt(0).performLongClick() }
            tapText("あとで読むに保存")
            waitFor { runBlocking { store.article("reader-$token-0")!!.bookmarked } }
            active.onActivity { it.findViewById<DrawerLayout>(R.id.reader_drawer).openDrawer(Gravity.LEFT) }
            waitFor { var open = false; active.onActivity { open = it.findViewById<DrawerLayout>(R.id.reader_drawer).isDrawerOpen(Gravity.LEFT) }; open }
            capture(active, "reader-tree-device.png")
            // Drag feed's root membership to World; its LLM membership must survive.
            dragTag(active, "tree:feed:$feed:$root", "tree:folder:$world:0")
            waitFor { runBlocking { store.memberships().any { it.feedId == feed && it.folderId == world } } }
            assertTrue(store.memberships().any { it.feedId == feed && it.folderId == llm })
            assertFalse(store.memberships().any { it.feedId == feed && it.folderId == root })
            assertEquals(setOf(feed, other), org.library().feedIds(root))
            assertEquals(5, org.widgetRows(WidgetConfig(987654, folderId = root)).size)
            assertEquals(5, org.widgetRows(WidgetConfig(987654, folderId = world)).size)
            // Folder reparenting is a second real drag, and immediately changes recursive widget scope.
            dragTag(active, "tree:folder:$ai:$root", "tree:folder:$world:0")
            waitFor { runBlocking { store.folders().first { it.id == ai }.parentId == world } }
            assertEquals(listOf(other), org.widgetRows(WidgetConfig(987654, folderId = root)).map { it.article.feedId }.distinct())
            // Native parser/serializer round-trip uses a private temporary file, never a user's document.
            val temp = File(context.cacheDir, "opml-check-$token.opml")
            try {
                val fixture = org.library().let { lib -> lib.copy(feeds = lib.feeds.filter { it.id in setOf(feed, other) }, folders = lib.folders.filter { it.id in setOf(root, ai, llm, world) }, links = lib.links.filter { it.feedId in setOf(feed, other) }) }
                temp.outputStream().use { Opml.write(fixture, it) }
                val parsed = temp.inputStream().use(Opml::parse)
                assertEquals(ImportResult(0, 0), org.importOpml(parsed))
                assertEquals(6, store.recent(feed).size + store.recent(other).size)
            } finally { temp.delete() }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "READER_CHECK: visible=$visible rows=6 image72dp=true noImage=true edgeSwipe=true bookmark=true dragFeed=true dragFolder=true widgetScope=true opmlRoundTrip=true\n") })
        } finally {
            scenario?.close()
            org.deleteFolder(root); org.deleteFolder(world)
            store.deleteFeed(feed); store.deleteFeed(other); imageFile.delete()
            Widgets.updateAll(context)
        }
    }
    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < until) { instrumentation.waitForIdleSync(); if (condition()) return; SystemClock.sleep(60) }
        assertTrue("Timed out", condition())
    }
    private fun find(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), tag)?.let { return it }
        return null
    }
    private fun clickTag(scenario: ActivityScenario<MainActivity>, tag: String) {
        waitFor { var found = false; scenario.onActivity { activity -> find(activity.window.decorView, tag)?.let { found = true; it.performClick() } }; found }
    }
    private fun dragTag(scenario: ActivityScenario<MainActivity>, from: String, to: String) {
        val start = IntArray(2); val end = IntArray(2)
        waitFor {
            var found = false
            scenario.onActivity { activity ->
                val a = find(activity.window.decorView, from); val b = find(activity.window.decorView, to)
                if (a != null && b != null && a.width > 0 && b.width > 0) {
                    a.getLocationOnScreen(start); b.getLocationOnScreen(end)
                    start[0] += a.width / 2; start[1] += a.height / 2; end[0] += b.width / 2; end[1] += b.height / 2; found = true
                }
            }; found
        }
        gesture(start[0].toFloat(), start[1].toFloat(), end[0].toFloat(), end[1].toFloat(), true)
    }
    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, hold: Boolean = false) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) { val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle() }
        send(MotionEvent.ACTION_DOWN, x1, y1); if (hold) SystemClock.sleep(750)
        for (i in 1..12) { send(MotionEvent.ACTION_MOVE, x1 + (x2-x1)*i/12, y1+(y2-y1)*i/12); SystemClock.sleep(25) }
        send(MotionEvent.ACTION_UP, x2, y2)
    }
    private fun tapText(text: String) {
        waitFor {
            val node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull() ?: return@waitFor false
            val box = android.graphics.Rect(); node.getBoundsInScreen(box)
            gesture(box.centerX().toFloat(), box.centerY().toFloat(), box.centerX().toFloat(), box.centerY().toFloat()); true
        }
    }
    private fun capture(scenario: ActivityScenario<MainActivity>, name: String) {
        SystemClock.sleep(350); instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val root = activity.root; val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap)); File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
