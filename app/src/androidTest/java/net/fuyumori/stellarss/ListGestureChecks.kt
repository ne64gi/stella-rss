package net.fuyumori.stellarss

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ListGestureChecks {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val store get() = context.rss.store
    private fun waitFor(reason: String, timeout: Long = 12000, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < until) { ins.waitForIdleSync(); if (check()) return; SystemClock.sleep(80) }
        assertTrue(reason, check())
    }
    private fun find(v: View, predicate: (View) -> Boolean): View? {
        if (predicate(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), predicate)?.let { return it }
        return null
    }
    private fun gesture(x: Float, y: Float, tx: Float, ty: Float, cancel: Boolean = false) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, px: Float, py: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0)
            e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(ins.uiAutomation.injectInputEvent(e, true)); e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..16) { send(MotionEvent.ACTION_MOVE, x + (tx-x)*i/16, y + (ty-y)*i/16); SystemClock.sleep(22) }
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, tx, ty)
        SystemClock.sleep(400)
    }
    @Test fun physicalRowsPullRefreshAndDrawerKeepTheirOwnGestures() = runBlocking {
        val token = System.nanoTime()
        val server = MockWebServer(); server.start()
        val feed = store.add(Feed(name = "Gesture verification", url = server.url("/feed").toString(), showImages = false))
        val org = Organization(context.rss.db)
        val folder = org.saveFolder(name = "00 Gesture verification $token")
        org.classify(feed, setOf(folder))
        val refreshReady = java.util.concurrent.atomic.AtomicBoolean(false)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = if (!refreshReady.get()) MockResponse().setBody("<rss version='2.0'><channel><title>Gesture verification</title></channel></rss>") else MockResponse().setBody("<rss version='2.0'><channel><title>Gesture verification</title><item><guid>pull-$token</guid><title>引き下げ更新で取得した記事</title><link>https://example.org/pull-$token</link><description>Updated by WorkManager</description></item></channel></rss>")
        }
        val ids = (0..17).map { "gesture-$token-$it" }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            ids.forEachIndexed { i, id -> store.insert(Article(id, feed, "スワイプ操作の記事 ${i + 1}", "https://example.org/", "左は既読、右はあとで読む。縦は一覧スクロール。", System.currentTimeMillis() - i * 1000)) }
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val active = scenario
            active.onActivity { it.root.keepScreenOn = true }
            waitFor("Fixture feed in tree") { var found = false; active.onActivity { a -> find(a.root) { it.tag == "tree:folder:$folder:0" }?.let { it.performClick(); found = true } }; found }
            waitFor("Reader owns input focus") { var focused = false; active.onActivity { focused = it.hasWindowFocus() }; focused }
            SystemClock.sleep(350)
            waitFor("18 rows") { var n = 0; active.onActivity { n = it.findViewById<RecyclerView>(R.id.article_list).adapter!!.itemCount }; n == 18 }
            fun swipeRow(id: String, right: Boolean, cancel: Boolean = false) {
                // Undo can reinsert above the retained scroll anchor. Bring that ID into view.
                waitFor("Article $id in adapter") {
                    var found = false
                    active.onActivity { a ->
                        val list = a.findViewById<RecyclerView>(R.id.article_list)
                        val rows = (list.adapter as androidx.recyclerview.widget.ListAdapter<*, *>).currentList
                        val position = rows.indexOfFirst { (it as ArticleRow).article.id == id }
                        if (position >= 0) {
                            val view = list.findViewHolderForAdapterPosition(position)?.itemView
                            if (view == null || view.top < 0 || view.bottom > list.height)
                                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
                            found = true
                        }
                    }
                    found
                }
                // Room completion precedes the next layout frame (especially after Undo insertion).
                SystemClock.sleep(200)
                var x = 0f; var y = 0f; var tx = 0f
                var diagnostics = ""
                try { waitFor("Row $id positioned") {
                    var found = false
                    active.onActivity { a ->
                        val list = a.findViewById<RecyclerView>(R.id.article_list)
                        (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull {
                            it.tag == "article:$id" && list.getChildAdapterPosition(it) != RecyclerView.NO_POSITION
                        }?.let { v ->
                        if (v.translationX == 0f && v.width > 0 && !list.hasPendingAdapterUpdates() && !list.isLayoutRequested && !v.isLayoutRequested) {
                            val p = IntArray(2); v.getLocationOnScreen(p)
                            x = p[0] + v.width * if (right) .22f else .78f
                            tx = p[0] + v.width * if (right) .94f else .06f
                            y = p[1] + v.height / 2f; found = true
                        }
                        diagnostics = "tag=${v.tag} tx=${v.translationX} width=${v.width} pos=${list.getChildAdapterPosition(v)}"

                    } }; found
                }
                } catch (e: AssertionError) { throw AssertionError("${e.message}; $diagnostics", e) }
                gesture(x, y, if (cancel) x + (tx-x)*.15f else tx, y + context.dp(8), cancel)
            }
            fun undo() {
                waitFor("Undo available") { var visible = false; active.onActivity {
                    val button = it.findViewById<View>(R.id.article_undo)
                    if (button.isShown && button.isEnabled) { button.performClick(); visible = true }
                }; visible }
            }
            swipeRow(ids[0], true)
            waitFor("Right saves but does not read") { runBlocking { store.article(ids[0])!!.let { it.bookmarked && !it.read } } }
            active.recreate()
            active.onActivity { it.root.keepScreenOn = true }
            waitFor("Recreated reader owns input focus") { var focused = false; active.onActivity { focused = it.hasWindowFocus() }; focused }
            SystemClock.sleep(350)
            undo()
            waitFor("Undo save survives recreation") { runBlocking { !store.article(ids[0])!!.bookmarked && !store.article(ids[0])!!.read } }
            // Saved rows leave the inbox; read rows remain in All and must be reusable.
            swipeRow(ids[0], false)
            waitFor("Left reads without saving") { runBlocking { store.article(ids[0])!!.let { it.read && !it.bookmarked } } }
            undo()
            waitFor("Undo read") { runBlocking { !store.article(ids[0])!!.read } }
            swipeRow(ids[0], false)
            waitFor("Read again") { runBlocking { store.article(ids[0])!!.read } }
            swipeRow(ids[0], false)
            assertTrue(store.article(ids[0])!!.read)
            swipeRow(ids[1], true, cancel = true)
            assertFalse(store.article(ids[1])!!.bookmarked)
            active.onActivity { a -> find(a.root) { it is TextView && it.text.toString() == "未読" }!!.performClick() }
            waitFor("Unread filter 17") { var n = 0; active.onActivity { n = it.findViewById<RecyclerView>(R.id.article_list).adapter!!.itemCount }; n == 17 }
            swipeRow(ids[1], false)
            waitFor("Read row removed from unread only") { var n = 0; active.onActivity { n = it.findViewById<RecyclerView>(R.id.article_list).adapter!!.itemCount }; n == 16 }
            undo()
            waitFor("Undo restores row in unread list") { var n = 0; active.onActivity { n = it.findViewById<RecyclerView>(R.id.article_list).adapter!!.itemCount }; n == 17 }
            swipeRow(ids[1], false)
            waitFor("Unread count after re-read") { var n = 0; active.onActivity { n = it.findViewById<RecyclerView>(R.id.article_list).adapter!!.itemCount }; n == 16 }
            swipeRow(ids[2], true)
            assertFalse(store.article(ids[2])!!.read)
            waitFor("Saved row leaves unread inbox") { var n = 0; active.onActivity { n = it.findViewById<RecyclerView>(R.id.article_list).adapter!!.itemCount }; n == 15 }
            swipeRow(ids[3], true)
            waitFor("Second saved row leaves unread inbox") { var n = 0; active.onActivity { n = it.findViewById<RecyclerView>(R.id.article_list).adapter!!.itemCount }; n == 14 }
            var left = 0f; var top = 0f; var width = 0; var height = 0
            active.onActivity { a -> val v = a.findViewById<RecyclerView>(R.id.article_list); val p = IntArray(2); v.getLocationOnScreen(p); left = p[0].toFloat(); top = p[1].toFloat(); width = v.width; height = v.height }
            val manager = WorkManager.getInstance(context)
            fun workIds() = manager.getWorkInfosForUniqueWork("manual-feed").get(5, TimeUnit.SECONDS).map { it.id }.toSet()
            val before = workIds()
            gesture(left + width*.5f, top + height*.8f, left + width*.5f, top + height*.2f)
            active.onActivity { assertTrue(it.findViewById<RecyclerView>(R.id.article_list).canScrollVertically(-1)) }
            gesture(left + width*.5f, top + height*.4f, left + width*.5f, top + height*.55f)
            assertEquals("Scrolling in the middle must not refresh", before, workIds())
            assertEquals("Vertical scroll must not mark other rows read", 2, ids.count { store.article(it)!!.read })
            assertEquals("Vertical scroll must not bookmark other rows", 2, ids.count { store.article(it)!!.bookmarked })
            gesture(context.dp(4).toFloat(), top + context.dp(80), width*.8f, top + context.dp(80))
            waitFor("Left edge still opens tree") { var open = false; active.onActivity { open = it.findViewById<DrawerLayout>(R.id.reader_drawer).isDrawerOpen(Gravity.LEFT) }; open }
            active.onActivity { it.findViewById<DrawerLayout>(R.id.reader_drawer).closeDrawer(Gravity.LEFT) }
            waitFor("Tree closed") { var visible = true; active.onActivity { visible = it.findViewById<DrawerLayout>(R.id.reader_drawer).isDrawerVisible(Gravity.LEFT) }; !visible }
            active.onActivity { (it.findViewById<RecyclerView>(R.id.article_list).layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0) }
            SystemClock.sleep(250)
            // Actual production refresh, with one test-owned HTTP feed and real WorkManager.
            waitFor("Prior manual refresh idle", 180000) { !context.rss.refreshing.value && manager.getWorkInfosForUniqueWork("manual-feed").get().none { !it.state.isFinished } }
            val beforeRequests = server.requestCount
            refreshReady.set(true)
            store.update(store.feed(feed)!!.copy(enabled = true))
            gesture(left + width*.5f, top + context.dp(35), left + width*.5f, top + context.dp(245))
            waitFor("Pull enqueues and fetches feed", 240000) { runBlocking { server.requestCount > beforeRequests && store.recent(feed).any { it.title == "引き下げ更新で取得した記事" } } }
            waitFor("Refresh spinner ends", 180000) { var spin = true; active.onActivity { spin = it.findViewById<SwipeRefreshLayout>(R.id.article_refresh).isRefreshing }; !spin }
            active.onActivity { a ->
                val bitmap = Bitmap.createBitmap(a.root.width, a.root.height, Bitmap.Config.ARGB_8888)
                a.root.draw(Canvas(bitmap)); File(context.filesDir, "reader-gestures-device.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            ins.sendStatus(0, android.os.Bundle().apply { putString("stream", "LIST_GESTURES: physical left/read + right/save idempotent; undo save/recreation/read/unread reappearance; cancel; unread removal; vertical scroll; edge drawer; top pull -> WorkManager -> HTTP -> Room; spinner stops PASS\n") })
        } finally { scenario?.close(); org.deleteFolder(folder); store.deleteFeed(feed); server.shutdown(); Widgets.updateAll(context) }
    }
}
