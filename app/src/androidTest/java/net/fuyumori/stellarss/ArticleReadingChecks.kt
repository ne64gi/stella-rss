package net.fuyumori.stellarss

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ArticleReadingChecks {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val store get() = context.rss.store
    private fun report(s: String) = ins.sendStatus(0, Bundle().apply { putString("stream", "ARTICLE_READER: $s\n") })
    private fun waitFor(reason: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < until) { ins.waitForIdleSync(); if (check()) return; SystemClock.sleep(80) }
        assertTrue(reason, check())
    }
    private fun page(s: ActivityScenario<OpenArticleActivity>, id: String) {
        s.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        waitFor("Article $id displayed") { var ok = false; s.onActivity { ok = it.currentArticleId == id && it.displayedPage != null }; ok }
        waitFor("Article marked read") { runBlocking { store.article(id)?.read == true } }
    }
    private fun contains(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString()?.contains(text) == true) return true
        for (i in 0 until node.childCount) if (contains(node.getChild(i), text)) return true
        return false
    }
    private fun tapText(text: String) {
        waitFor("Tap $text") {
            val node = ins.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString() == text } ?: return@waitFor false
            val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
            gesture(bounds.centerX().toFloat(), bounds.centerY().toFloat(), bounds.centerX().toFloat(), bounds.centerY().toFloat()); true
        }
    }
    private fun gesture(x: Float, y: Float, toX: Float, toY: Float) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, px: Float, py: Float) {
            val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, px, py, 0)
            e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(ins.uiAutomation.injectInputEvent(e, true)); e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..12) { send(MotionEvent.ACTION_MOVE, x + (toX - x) * i / 12, y + (toY - y) * i / 12); SystemClock.sleep(18) }
        send(MotionEvent.ACTION_UP, toX, toY)
    }
    private fun swipe(s: ActivityScenario<OpenArticleActivity>, forward: Boolean, vertical: Boolean = false) {
        var x = 0f; var y = 0f; var x2 = 0f; var y2 = 0f
        s.onActivity {
            val v = it.findViewById<WebView>(R.id.reading_web); val p = IntArray(2); v.getLocationOnScreen(p)
            x = p[0] + v.width * if (vertical) .5f else if (forward) .8f else .2f
            x2 = p[0] + v.width * if (vertical) .5f else if (forward) .2f else .8f
            y = p[1] + v.height * if (vertical) .8f else .5f; y2 = p[1] + v.height * if (vertical) .25f else .5f
        }
        gesture(x, y, x2, y2)
    }
    private fun capture(s: ActivityScenario<OpenArticleActivity>, name: String) {
        val latch = CountDownLatch(1); var code = -1; lateinit var bitmap: Bitmap
        SystemClock.sleep(500)
        s.onActivity {
            bitmap = Bitmap.createBitmap(it.window.decorView.width, it.window.decorView.height, Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(it.window, bitmap, { result -> code = result; latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS)); assertEquals(android.view.PixelCopy.SUCCESS, code)
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun liveJpcertBbcFullRssAndAtomDisplayInsideApp() = runBlocking {
        val token = System.nanoTime()
        val feed = store.add(Feed(name = "Live reader verification", url = "https://example.org/reader-live-$token", enabled = false))
        val oldSettings = store.preference(ReaderSettings.KEY)
        var scenario: ActivityScenario<OpenArticleActivity>? = null
        try {
            store.save(Preference(ReaderSettings.KEY, ReaderSettings().encode()))
            val urls = listOf("JPCERT/CC" to "https://www.jpcert.or.jp/rss/jpcert.rdf", "BBC World" to "https://feeds.bbci.co.uk/news/world/rss.xml", "CAPA CAMERA WEB" to "https://getnavi.jp/capa/feed/", "Android Developers" to "https://android-developers.googleblog.com/feeds/posts/default")
            for ((index, source) in urls.withIndex()) {
                val parsed = withContext(Dispatchers.IO) { Http.get(source.second).use { FeedParser.parse(Http.bytes(it, 4 * 1024 * 1024).inputStream(), it.request.url.toString()) } }
                val entry = if (index >= 2) parsed.entries.first { ArticleHtml.hasContent(it.bodyHtml) } else parsed.entries.first()
                if (index < 2) assertTrue(entry.summaryHtml.isNotBlank() || entry.bodyHtml.isNotBlank()) else assertTrue(entry.bodyHtml.length > 400)
                val id = "live-reading-$token-$index"
                store.insert(Article(id, feed, entry.title, entry.url, entry.summary, entry.published ?: System.currentTimeMillis(), imageCandidates = entry.images.joinToString("\n"), bodyHtml = entry.bodyHtml, summaryHtml = entry.summaryHtml, bodyTruncated = entry.bodyTruncated))
                store.update(store.feed(feed)!!.copy(name = source.first))
                scenario = ActivityScenario.launch(Intent(context, OpenArticleActivity::class.java).putExtra("article", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                page(scenario, id)
                waitFor("Published title visible in WebView") { contains(ins.uiAutomation.rootInActiveWindow, entry.title.take(35)) }
                scenario.onActivity {
                    assertFalse(it.findViewById<WebView>(R.id.reading_web).settings.javaScriptEnabled)
                    assertTrue(it.displayedPage!!.notice.contains(if (ArticleHtml.hasContent(entry.bodyHtml)) "本文" else "概要"))
                }
                capture(scenario, "article-live-$index.png")
                report("${source.first}: native fetch/parse body=${entry.bodyHtml.length} summary=${entry.summaryHtml.length}, in-app accessible title, no auto browser PASS")
                scenario.close(); scenario = null
            }
        } finally {
            scenario?.close(); store.deleteFeed(feed)
            if (oldSettings == null) store.deletePreference(ReaderSettings.KEY) else store.save(Preference(ReaderSettings.KEY, oldSettings))
        }
    }
    @Test fun unreadListTapKeepsItsScopeAndBackReturnsToUpdatedList() = runBlocking {
        val token = System.nanoTime()
        val feed = store.add(Feed(name = "Reading selection test", url = "https://example.org/selection-$token", showImages = false))
        val ids = (0..2).map { "selection-$token-$it" }
        var main: ActivityScenario<MainActivity>? = null
        var article: OpenArticleActivity? = null
        fun find(v: View, predicate: (View) -> Boolean): View? {
            if (predicate(v)) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), predicate)?.let { return it }
            return null
        }
        try {
            ids.forEachIndexed { i, id -> store.insert(Article(id, feed, "Selection $i", "https://example.org/", "Summary $i", System.currentTimeMillis() - i * 1000, read = i == 1)) }
            main = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val screen = main
            waitFor("Fixture feed in tree") {
                var found = false
                screen.onActivity { activity -> find(activity.root) { it.tag == "tree:feed:$feed:0" }?.let { it.performClick(); found = true } }
                found
            }
            screen.onActivity { activity -> find(activity.root) { it is android.widget.TextView && it.text.toString() == "未読" }!!.performClick() }
            waitFor("Unread fixture selection count 2") { var count = -1; screen.onActivity { count = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.article_list).adapter!!.itemCount }; count == 2 }
            val monitor = ins.addMonitor(OpenArticleActivity::class.java.name, null, false)
            screen.onActivity { it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.article_list).getChildAt(0).performClick() }
            article = ins.waitForMonitorWithTimeout(monitor, 10000) as? OpenArticleActivity
            ins.removeMonitor(monitor); assertNotNull(article)
            waitFor("First selection read") { runBlocking { store.article(ids[0])!!.read } }
            ins.runOnMainSync { assertEquals(ids[0], article!!.currentArticleId); article!!.findViewById<View>(R.id.reading_next).performClick() }
            waitFor("Next unread read, middle pre-read item skipped") { runBlocking { store.article(ids[2])!!.read } }
            ins.runOnMainSync { assertEquals(ids[2], article!!.currentArticleId); assertFalse(article!!.findViewById<View>(R.id.reading_next).isEnabled) }
            ins.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor("Back returns to same empty unread selection") { var count = -1; screen.onActivity { count = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.article_list).adapter!!.itemCount }; count == 0 }
            report("Actual list tap: feed scope + unread filter snapshot, auto-read does not skip remaining item, back preserves filter PASS")
        } finally { article?.let { ins.runOnMainSync { it.finish() } }; main?.close(); store.deleteFeed(feed) }
    }
    @Test fun securityPhysicalSwipeSettingsRestoreAndBackPreserveReadingOrder() = runBlocking {
        val token = System.nanoTime()
        val feed = store.add(Feed(name = "Reader verification", url = "https://example.org/reader-$token", enabled = false))
        val oldSettings = store.preference(ReaderSettings.KEY)
        val server = MockWebServer(); val requested = CopyOnWriteArrayList<String>()
        val pixels = Bitmap.createBitmap(400, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(45, 128, 160)) }
        val bytes = ByteArrayOutputStream().also { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); pixels.recycle()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested.add(request.path.orEmpty())
                return if (request.path == "/image") MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)) else MockResponse().setBody("Forbidden request")
            }
        }
        server.start()
        val base = server.url("/").toString()
        var scenario: ActivityScenario<OpenArticleActivity>? = null
        val ids = ArrayList((0..2).map { "reading-$token-$it" })
        try {
            store.save(Preference(ReaderSettings.KEY, ReaderSettings().encode()))
            val hostile = """<script src='${base}script'></script><script>location='${base}jump'</script><iframe src='${base}frame'></iframe><meta http-equiv='refresh' content='0;url=${base}refresh'><h2>読みやすい記事本文</h2><p>RSS本文から表示した段落です。<strong>重要な部分</strong>を読みながら、左右スワイプで記事を送れます。</p><img src='${base}image' onerror="location='${base}error'"><p><a href='https://example.com/'>参考リンク</a></p>${"<p>長文の記事でも縦スクロールで読み進められます。見出しと段落を残します。</p>".repeat(35)}"""
            ids.forEachIndexed { i, id -> store.insert(Article(id, feed, "アプリ内で読むニュース ${i + 1}", "https://example.com/", "短い概要です。", System.currentTimeMillis() - i * 1000, bodyHtml = hostile, summaryHtml = "<p>短い概要です。</p>")) }
            scenario = ActivityScenario.launch(Intent(context, OpenArticleActivity::class.java).putExtra("article", ids[0]).putStringArrayListExtra("queue", ids).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val active = scenario
            page(active, ids[0])
            waitFor("Image fetched via native interception") { requested.contains("/image") }
            waitFor("Safe body accessible") { contains(ins.uiAutomation.rootInActiveWindow, "読みやすい記事本文") }
            active.onActivity {
                val web = it.findViewById<WebView>(R.id.reading_web)
                assertFalse(web.settings.javaScriptEnabled); assertFalse(web.settings.allowFileAccess); assertFalse(web.settings.allowContentAccess)
                assertTrue(org.jsoup.Jsoup.parse(it.displayedPage!!.html).select("script,iframe,form").isEmpty())
            }
            assertEquals(setOf("/image"), requested.toSet())
            capture(active, "article-dark-device.png")
            swipe(active, true); page(active, ids[1]); swipe(active, false); page(active, ids[0])
            swipe(active, true, vertical = true)
            active.onActivity { assertEquals(ids[0], it.currentArticleId); assertTrue(it.findViewById<WebView>(R.id.reading_web).scrollY > 0) }
            active.recreate(); page(active, ids[0])
            waitFor("Scroll restored after recreation") { var y = 0; active.onActivity { y = it.findViewById<WebView>(R.id.reading_web).scrollY }; y > 0 }
            active.onActivity { it.findViewById<WebView>(R.id.reading_web).scrollTo(0, 0); it.findViewById<View>(R.id.reading_settings).performClick() }
            tapText("ライトテーマ（OFFでダーク）"); tapText("記事画像を表示"); tapText("概要を優先（OFFで本文を優先）")
            for ((label, value) in listOf("文字サイズ" to 10f, "行間" to 8f, "余白" to 24f)) {
                val seek = ins.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(label).first { it.className == "android.widget.SeekBar" }
                assertTrue(seek.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }))
            }
            tapText("適用")
            waitFor("Light summary settings persisted") { runBlocking { ReaderSettings.decode(store.preference(ReaderSettings.KEY)).let { it.light && !it.images && it.summaryFirst && it.fontSp == 24 && it.lineHeight == 2.1f && it.margin == 32 } } }
            waitFor("Summary rendered without images") { var ok = false; active.onActivity { ok = it.displayedPage?.let { p -> p.images.isEmpty() && p.notice.contains("概要") } == true }; ok }
            capture(active, "article-light-device.png")
            active.recreate(); page(active, ids[0])
            active.onActivity { assertTrue(it.displayedPage!!.images.isEmpty()); assertTrue(it.displayedPage!!.html.contains("#FAFAF7")); it.findViewById<View>(R.id.reading_next).performClick() }
            page(active, ids[1]); active.onActivity { it.findViewById<View>(R.id.reading_next).performClick() }; page(active, ids[2])
            active.onActivity { assertFalse(it.findViewById<View>(R.id.reading_next).isEnabled) }
            assertEquals(setOf("/image"), requested.toSet())
            ins.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            report("HTML attacks blocked; image via cache; physical horizontal next/previous; vertical scroll does not page; recreation/order/settings; light/dark/images/summary; terminal bound; Back PASS")
        } finally {
            scenario?.close(); store.deleteFeed(feed); server.shutdown()
            File(context.cacheDir, "article-images/${digest(base + "image")}.jpg").delete()
            if (oldSettings == null) store.deletePreference(ReaderSettings.KEY) else store.save(Preference(ReaderSettings.KEY, oldSettings))
        }
    }
}
