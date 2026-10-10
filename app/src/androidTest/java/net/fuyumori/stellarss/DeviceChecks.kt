package net.fuyumori.stellarss

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import java.io.File
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class DeviceChecks {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val layoutErrors = linkedSetOf<String>()
    private fun report(message: String) { instrumentation.sendStatus(0, Bundle().apply { putString("stream", "STELLA_RSS: $message\n") }) }
    private fun waitFor(description: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < until) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Timed out: $description")
    }
    @Test fun liveRssAndAtomFetchOnDevice() = runBlocking {
        for ((name, url) in listOf("RSS" to "https://feeds.bbci.co.uk/news/world/rss.xml", "Atom" to "https://android-developers.googleblog.com/feeds/posts/default")) {
            val feed = withContext(Dispatchers.IO) { Http.get(url).use { FeedParser.parse(Http.bytes(it, 4 * 1024 * 1024).inputStream(), it.request.url.toString()) } }
            assertTrue("Live $name has no articles", feed.entries.isNotEmpty())
            report("live $name fetched and parsed: ${feed.entries.size} entries")
        }
    }
    @Test fun liveJpcertRdfFetchAndRefresh() = runBlocking {
        val url = "https://www.jpcert.or.jp/rss/jpcert.rdf"
        val parsed = withContext(Dispatchers.IO) {
            Http.get(url).use { FeedParser.parse(Http.bytes(it, 4 * 1024 * 1024).inputStream(), it.request.url.toString()) }
        }
        assertEquals("JPCERT/CC RSS Feed", parsed.title)
        assertTrue("JPCERT has no articles", parsed.entries.isNotEmpty())
        assertTrue("JPCERT dates or URLs lost", parsed.entries.all { it.published != null && it.url.startsWith("https://www.jpcert.or.jp/") })
        report("live JPCERT RSS1/RDF parsed: ${parsed.entries.size} entries; title, dc:date and links PASS")

        val db = Room.inMemoryDatabaseBuilder(context, RssDatabase::class.java).build()
        try {
            val repo = Repository(context, db)
            val id = db.store().add(Feed(name = "JPCERT", url = url, showImages = false))
            assertFalse(withContext(Dispatchers.IO) { repo.sync() })
            val articles = db.store().recent(id)
            assertEquals(parsed.entries.size, articles.size)
            assertNull(db.store().feed(id)!!.error)
            assertEquals(articles.size, db.store().watchArticles().first().size)
            assertTrue(articles.zipWithNext().all { (a, b) -> a.published >= b.published })
            db.store().markRead(articles.first().id, true)
            assertFalse(withContext(Dispatchers.IO) { repo.sync() })
            assertTrue(db.store().article(articles.first().id)!!.read)
            assertEquals(articles.map { it.id }.toSet(), db.store().recent(id).map { it.id }.toSet())
            assertEquals(minOf(5, articles.size), db.store().widgetArticlesFor(listOf(id), false, false).size)
            report("JPCERT production Repository → Room → reader/widget queries, date ordering, refresh/read preservation PASS")
        } finally { db.close() }

        // Only refresh an existing subscription; do not create/delete feeds or change user preferences.
        val store = context.rss.store
        val before = store.feeds()
        val existing = before.firstOrNull { it.url == url && it.enabled }
        if (existing != null) {
            val widgets = store.widgets()
            val reads = store.recent(existing.id).associate { it.id to it.read }
            withContext(Dispatchers.IO) { context.rss.sync() }
            assertNull("Installed JPCERT subscription still has an error", store.feed(existing.id)!!.error)
            assertTrue(store.recent(existing.id).isNotEmpty())
            assertEquals(before.map { it.copy(etag = null, modified = null, lastChecked = 0, error = null, contentVersion = 0) },
                store.feeds().map { it.copy(etag = null, modified = null, lastChecked = 0, error = null, contentVersion = 0) })
            assertEquals(widgets, store.widgets())
            for ((id, read) in reads) assertEquals(read, store.article(id)!!.read)
            report("installed existing JPCERT subscription refreshed: ${store.recent(existing.id).size} articles; feed/widget settings and read state preserved PASS")
        } else report("No enabled JPCERT subscription on installed app; live integration checked in isolated Room only")
    }
    @Test fun httpRoomImageFallbackAndCacheOnDevice() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, RssDatabase::class.java).build()
        val repo = Repository(context, db)
        val server = MockWebServer()
        val png = ByteArrayOutputStream().apply { Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        server.start()
        try {
            val base = server.url("/").toString()
            var seenEtag = false
            var feedRequests = 0
            val paths = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(paths) { paths.add(request.path.orEmpty()) }
                    return when (request.path) {
                        "/rss" -> {
                            feedRequests++
                            if (feedRequests > 1 && request.getHeader("If-None-Match") == "\"fixture-v1\"") {
                                seenEtag = true; MockResponse().setResponseCode(304)
                            } else MockResponse().addHeader("ETag", "\"fixture-v1\"").addHeader("Last-Modified", "Wed, 02 Oct 2002 08:00:00 GMT")
                                .setBody("""<rss xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Fixture</title><item><guid>fixed</guid><title>Fixture title</title><link>${base}article</link><media:content url="${base}bad-image"/><enclosure url="${base}good-image" type="image/png"/></item></channel></rss>""")
                        }
                        "/bad-image" -> MockResponse().setResponseCode(404)
                        "/good-image", "/og-image" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                        "/article" -> MockResponse().setBody("<html><head><meta property='og:image' content='${base}og-image'></head></html>")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val id = db.store().add(Feed(name = "fixture", url = "${base}rss"))
            assertFalse(repo.sync())
            val article = db.store().recent(id).single()
            assertEquals("${base}good-image", article.imageUrl)
            assertFalse(paths.contains("/article"))
            assertTrue(paths.indexOf("/bad-image") < paths.indexOf("/good-image"))
            db.store().markRead(article.id, true)
            assertFalse(repo.sync()); assertTrue(seenEtag); assertTrue(db.store().article(article.id)!!.read)
            val count = paths.size
            assertNotNull(repo.resolveImage(article)); assertEquals(count, paths.size)
            val og = article.copy(id = "og-test", imageCandidates = "", imageUrl = null, imageChecked = 0)
            db.store().insert(og)
            assertNotNull(repo.resolveImage(og)); assertEquals("${base}og-image", db.store().article(og.id)!!.imageUrl)
            report("Room + ETag/304 + read persistence + failed media → enclosure + og:image + disk cache PASS")
        } finally { db.close(); server.shutdown() }
    }
    @Test fun standardHostRenderingUpdateIndependentSettingsAndGestures() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 0x535253)
        val store = context.rss.store
        val server = MockWebServer(); server.start()
        val feedUrl = server.url("/fixture-rss").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path?.startsWith("/article") == true)
                MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html><head><title>Stella RSS verified original</title></head><body><h1>Stella RSS verified original</h1><p>Local public test article, no personal content.</p></body></html>")
            else MockResponse().setBody("<rss><channel><title>Test</title></channel></rss>")
        }
        val feedId = store.add(Feed(name = "NEWS", url = feedUrl))
        val widgetIds = mutableListOf<Int>()
        val organization = Organization(context.rss.db)
        val folder = organization.saveFolder(name = "Widget verification ${System.nanoTime()}")
        val child = organization.saveFolder(name = "Child", parent = folder)
        organization.classify(feedId, setOf(folder, child))
        var scenario: ActivityScenario<FixtureHostActivity>? = null
        try {
            Updates.schedule(context)
            val scheduled = androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("periodic-feed").get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("Periodic refresh not scheduled", scheduled.isNotEmpty())
            assertEquals(androidx.work.NetworkType.CONNECTED, scheduled.first().constraints.requiredNetworkType)
            report("WorkManager periodic refresh registered with CONNECTED constraint PASS (elapsed periodic firing not claimed)")

            val leadTitle = "国内組織への不正アクセス相次ぐ、最新の注意喚起と対策を確認"
            val photo = store.feeds().firstOrNull { it.url == "https://feeds.bbci.co.uk/news/world/rss.xml" }
                ?.let { store.recent(it.id).firstOrNull { article -> article.imageUrl != null }?.imageUrl }
            val titles = listOf("国際ニュース：現地で続く調査と今後の見通し", "新しい合意を発表、各国の対応は", "航空会社が安全対策を更新", "今週の重要なニュースをまとめて読む", leadTitle)
            for (n in 1..5) store.insert(Article(digest("fixture-$feedId-$n"), feedId, titles[n - 1], server.url("/article$n").toString(), "Fixture summary", System.currentTimeMillis() + n, imageUrl = photo, imageChecked = System.currentTimeMillis()))
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try {
                for (provider in listOf(NewsWidget::class.java, CompactWidget::class.java)) {
                    val id = host.allocateAppWidgetId(); widgetIds.add(id)
                    assertTrue("Unable to bind isolated test host", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, provider)))
                }
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            store.save(WidgetConfig(widgetIds[0], feedIds = feedId.toString(), opacity = 100, fontSp = 15))
            store.save(WidgetConfig(widgetIds[1], feedIds = feedId.toString(), opacity = 45, fontSp = 18))
            // Exercise the actual folder source selector and save action, not only the DAO.
            val folderName = store.folders().first { it.id == folder }.name
            val configScreen = ActivityScenario.launch<WidgetConfigActivity>(android.content.Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetIds[0]))
            try {
                var ready = false
                val until = SystemClock.uptimeMillis() + 10000
                while (!ready && SystemClock.uptimeMillis() < until) {
                    instrumentation.waitForIdleSync()
                    configScreen.onActivity { activity -> ready = findText(activity.root, "保存して適用") != null }
                    if (!ready) Thread.sleep(50)
                }
                assertTrue("Widget source screen not ready", ready)
                configScreen.onActivity { activity ->
                    fun spinners(view: View): List<Spinner> = if (view is Spinner) listOf(view) else if (view is ViewGroup)
                        (0 until view.childCount).flatMap { spinners(view.getChildAt(it)) } else emptyList()
                    val sources = spinners(activity.root)
                    assertEquals(2, sources.size)
                    sources[0].setSelection(2)
                    val index = (0 until sources[1].count).first { sources[1].getItemAtPosition(it).toString() == folderName }
                    sources[1].setSelection(index)
                }
                instrumentation.waitForIdleSync()
                configScreen.onActivity { activity -> findText(activity.root, "保存して適用")!!.performClick() }
                val savedUntil = SystemClock.uptimeMillis() + 10000
                while (store.widget(widgetIds[0])?.folderId != folder && SystemClock.uptimeMillis() < savedUntil) delay(50)
                assertEquals(folder, store.widget(widgetIds[0])!!.folderId)
                assertEquals(100, store.widget(widgetIds[0])!!.opacity)
                report("widget configuration UI: feed source → recursive folder source → save; appearance preserved PASS")
            } finally { configScreen.close() }
            host.startListening()
            scenario = ActivityScenario.launch(FixtureHostActivity::class.java)
            lateinit var news: AppWidgetHostView
            lateinit var compact: AppWidgetHostView
            scenario.onActivity { activity ->
                news = host.createView(activity, widgetIds[0], manager.getAppWidgetInfo(widgetIds[0]))
                compact = host.createView(activity, widgetIds[1], manager.getAppWidgetInfo(widgetIds[1]))
                activity.surface.layoutParams.width = activity.dp(320); activity.surface.layoutParams.height = activity.dp(300)
                news.setPadding(0, 0, 0, 0); compact.setPadding(0, 0, 0, 0)
                activity.surface.addView(news, FrameLayout.LayoutParams(-1, -1))
            }
            setWidgetSize(manager, widgetIds[0], 320, 300)
            setWidgetSize(manager, widgetIds[1], 280, 120)
            Widgets.updateAll(context)
            waitFor("news RemoteViews inflation") { news.findViewById<TextView>(R.id.hero_title)?.text?.toString() == leadTitle }
            instrumentation.runOnMainSync {
                assertEquals(255, news.findViewById<ImageView>(android.R.id.background).imageAlpha)
                assertEquals(4, news.findViewById<LinearLayout>(R.id.headlines).childCount)
                assertTrue(news.findViewById<TextView>(R.id.more).text.contains("More"))
            }
            assertWidgetTextFits(news)
            capture(news, "news-redesign-320x300")
            scenario.onActivity { a -> a.surface.layoutParams.width = a.dp(240); a.surface.layoutParams.height = a.dp(240); a.surface.requestLayout() }
            setWidgetSize(manager, widgetIds[0], 240, 240); Widgets.updateAll(context)
            waitFor("dense news resize") { news.width == context.resources.displayMetrics.density.times(240).toInt() && news.findViewById<View>(R.id.hero_kicker).visibility == View.GONE }
            assertWidgetTextFits(news); capture(news, "news-redesign-240x240")
            store.save(store.widget(widgetIds[0])!!.copy(fontSp = 24, cornerDp = 24))
            scenario.onActivity { a -> a.surface.layoutParams.width = a.dp(320); a.surface.layoutParams.height = a.dp(360); a.surface.requestLayout() }
            setWidgetSize(manager, widgetIds[0], 320, 360); Widgets.updateAll(context)
            waitFor("large font news resize") { news.height == context.resources.displayMetrics.density.times(360).toInt() }
            assertWidgetTextFits(news); capture(news, "news-redesign-font24")
            store.save(store.widget(widgetIds[0])!!.copy(fontSp = 15, cornerDp = 16))
            val top = store.recent(feedId).first()
            store.updateContent(top.id, "Updated headline", "https://example.com/", top.summary, top.published, "")
            Widgets.updateAll(context)
            waitFor("widget refresh delivered") { news.findViewById<TextView>(R.id.hero_title)?.text?.toString() == "Updated headline" }
            assertEquals(100, store.widget(widgetIds[0])!!.opacity); assertEquals(45, store.widget(widgetIds[1])!!.opacity)
            capture(news, "news-widget")
            report("standard AppWidgetHost: news hero + four rows + More, DB update delivery, independent settings PASS")
            store.updateContent(top.id, leadTitle, "https://example.com/", top.summary, top.published, "")
            Widgets.updateAll(context)
            scenario.onActivity { activity ->
                activity.surface.removeAllViews(); activity.surface.layoutParams.width = activity.dp(280); activity.surface.layoutParams.height = activity.dp(120)
                activity.surface.addView(compact, FrameLayout.LayoutParams(-1, -1))
            }
            waitFor("compact six cards") { compact.findViewById<ViewFlipper>(R.id.cards)?.childCount == 6 && compact.findViewById<ViewFlipper>(R.id.cards).width > 0 && compact.findViewById<ViewFlipper>(R.id.cards).currentView?.findViewById<TextView>(R.id.card_title)?.text?.toString() == leadTitle }
            lateinit var stack: ViewFlipper
            instrumentation.runOnMainSync {
                stack = compact.findViewById(R.id.cards)
                assertEquals(114, compact.findViewById<ImageView>(android.R.id.background).imageAlpha)
            }
            assertWidgetTextFits(compact); capture(compact, "compact-redesign-280x120")
            scenario.onActivity { a -> a.surface.setBackgroundColor(0xFF456879.toInt()) }
            capture(scenarioSurface(scenario), "compact-redesign-glass")
            instrumentation.runOnMainSync {
                assertNull(compact.findViewById<View>(R.id.refresh)); assertNull(compact.findViewById<View>(R.id.configure))
            }
            scenario.onActivity { a -> a.surface.layoutParams.width = a.dp(200); a.surface.layoutParams.height = a.dp(96); a.surface.requestLayout() }
            setWidgetSize(manager, widgetIds[1], 200, 96); Widgets.updateAll(context)
            waitFor("small compact resize") { compact.height == context.resources.displayMetrics.density.times(96).toInt() }
            assertWidgetTextFits(compact); capture(compact, "compact-redesign-200x96")
            store.save(store.widget(widgetIds[1])!!.copy(fontSp = 24, cornerDp = 0, opacity = 0))
            scenario.onActivity { a -> a.surface.layoutParams.width = a.dp(320); a.surface.layoutParams.height = a.dp(136); a.surface.requestLayout() }
            setWidgetSize(manager, widgetIds[1], 320, 136); Widgets.updateAll(context)
            waitFor("large compact resize") { compact.height == context.resources.displayMetrics.density.times(136).toInt() }
            assertWidgetTextFits(compact); capture(compact, "compact-redesign-font24")
            instrumentation.runOnMainSync {
                assertEquals(0, compact.findViewById<ImageView>(android.R.id.background).imageAlpha)
                assertEquals(View.GONE, compact.findViewById<View>(R.id.widget_border).visibility)
            }
            store.save(store.widget(widgetIds[1])!!.copy(fontSp = 15, opacity = 98, cornerDp = 16))
            scenario.onActivity { a -> a.surface.setBackgroundColor(android.graphics.Color.TRANSPARENT) }
            scenario.onActivity { a -> a.surface.layoutParams.width = a.dp(280); a.surface.layoutParams.height = a.dp(120); a.surface.requestLayout() }
            setWidgetSize(manager, widgetIds[1], 280, 120); Widgets.updateAll(context)
            waitFor("compact final palette") { compact.findViewById<ImageView>(android.R.id.background).imageAlpha == 249 }
            assertWidgetTextFits(compact); capture(compact, "compact-redesign-final")
            report("news/compact responsive sizes, font24, rounded/square corners, background-only alpha, no compact controls; size captures completed")
            store.updateContent(top.id, leadTitle, "https://example.com/", top.summary, top.published, "")
            store.save(store.widget(widgetIds[0])!!.copy(opacity = 98))
            store.save(store.widget(widgetIds[1])!!.copy(opacity = 48, background = 0xFF20252A.toInt()))
            scenario.onActivity { a ->
                a.surface.removeAllViews(); a.surface.layoutParams.width = a.dp(320); a.surface.layoutParams.height = a.dp(432)
                a.surface.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF15232D.toInt(), 0xFF456879.toInt()))
                a.surface.addView(news, FrameLayout.LayoutParams(-1, a.dp(300)))
                a.surface.addView(compact, FrameLayout.LayoutParams(-1, a.dp(120)).apply { topMargin = a.dp(312) })
                a.surface.requestLayout()
            }
            setWidgetSize(manager, widgetIds[0], 320, 300); setWidgetSize(manager, widgetIds[1], 320, 120); Widgets.updateAll(context)
            waitFor("showcase rendered") { news.findViewById<TextView>(R.id.hero_title)?.text?.toString() == leadTitle && compact.findViewById<ImageView>(android.R.id.background).imageAlpha == 122 }
            assertWidgetTextFits(news); assertWidgetTextFits(compact)
            capture(scenarioSurface(scenario), "widget-redesign-showcase")
            assertEquals(context.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull("Device screenshot unavailable", screenshot)
            File(context.cacheDir, "widget-redesign-device.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            store.updateContent(top.id, "Updated headline", "https://example.com/", top.summary, top.published, "")
            scenario.onActivity { a ->
                a.surface.removeAllViews(); a.surface.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                a.surface.layoutParams.width = a.dp(280); a.surface.layoutParams.height = a.dp(120)
                a.surface.addView(compact, FrameLayout.LayoutParams(-1, -1)); a.surface.requestLayout()
            }
            setWidgetSize(manager, widgetIds[1], 280, 120); Widgets.updateAll(context)
            waitFor("compact restored") { compact.findViewById<ViewFlipper>(R.id.cards).currentView?.findViewById<TextView>(R.id.card_title)?.text?.toString() == "Updated headline" }
            instrumentation.runOnMainSync { stack = compact.findViewById(R.id.cards) }
            assertTrue(layoutErrors.joinToString("\n"), layoutErrors.isEmpty())
            report("all tested widget text bounds PASS")
            var start = 0
            instrumentation.runOnMainSync { start = stack.displayedChild }
            swipe(stack, horizontal = true)
            Thread.sleep(450)
            instrumentation.runOnMainSync { assertEquals("Horizontal swipe unexpectedly pages", start, stack.displayedChild) }
            capture(compact, "compact-widget")
            report("physical Android ViewFlipper: horizontal swipe did NOT page (standard limitation confirmed)")
            instrumentation.runOnMainSync { compact.findViewById<LinearLayout>(R.id.widget_pages).getChildAt(1).performClick() }
            waitFor("indicator tap pages") { stack = compact.findViewById(R.id.cards); stack.displayedChild == 1 }
            report("physical ViewFlipper: indicator tap pages PASS; six items, no left/right buttons")
            instrumentation.runOnMainSync { compact.findViewById<LinearLayout>(R.id.widget_pages).getChildAt(5).performClick() }
            waitFor("More page") { stack = compact.findViewById(R.id.cards); stack.currentView?.findViewById<TextView>(R.id.card_title)?.text?.startsWith("More") == true }
            report("sixth page is More; small per-page indicator PASS")
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { stack.currentView.findViewById<View>(R.id.card_root).performClick() }
            val reader = instrumentation.waitForMonitorWithTimeout(monitor, 10000)
            instrumentation.removeMonitor(monitor)
            assertNotNull("More did not open reader", reader)
            try {
                waitFor("reader displays merged articles") { findText(reader.window.decorView, "Updated headline")?.let { (it.parent as View).alpha >= 0.99f } == true }

                report("More PendingIntent opened MainActivity; reader renders merged article titles PASS")
                val articleMonitor = instrumentation.addMonitor(OpenArticleActivity::class.java.name, null, false)
                instrumentation.runOnMainSync { (findText(reader.window.decorView, "Updated headline")!!.parent.parent.parent as View).performClick() }
                val articleReader = instrumentation.waitForMonitorWithTimeout(articleMonitor, 10000) as? OpenArticleActivity
                instrumentation.removeMonitor(articleMonitor)
                assertNotNull("Article tap did not open in-app reader", articleReader)
                waitFor("RSS body displayed") { articleReader!!.displayedPage != null }
                assertTrue("In-app article not marked read", store.article(top.id)!!.read)
                report("article tap → in-app reader; no automatic browser navigation PASS")
                instrumentation.runOnMainSync { articleReader!!.findViewById<View>(R.id.reading_original).performClick() }
                val serviceInfo = instrumentation.uiAutomation.serviceInfo
                serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                instrumentation.uiAutomation.serviceInfo = serviceInfo
                val until = SystemClock.uptimeMillis() + 20000
                var displayed = false
                while (SystemClock.uptimeMillis() < until) {
                    val window = instrumentation.uiAutomation.rootInActiveWindow
                    displayed = containsText(window, "Example Domain") ||
                        instrumentation.uiAutomation.windows.any { containsText(it.root, "Example Domain") }
                    if (displayed) break
                    Thread.sleep(200)
                }
                report("browser observation: package=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}, expected page visible=$displayed, read=${store.article(top.id)?.read}")
                assertTrue("Original HTTPS article not displayed in browser", displayed)
                assertTrue("Article was not marked read", store.article(top.id)!!.read)
                report("explicit original link → real default browser → public HTTPS original-page heading visible; read state persisted PASS")
                if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName)
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.runOnMainSync { articleReader!!.finish() }
            } finally { instrumentation.runOnMainSync { reader.finish() } }
        } finally {
            scenario?.close()
            host.stopListening()
            widgetIds.forEach { host.deleteAppWidgetId(it); store.deleteWidget(it) }
            widgetIds.forEach { store.deletePreference("page:$it") }
            organization.deleteFolder(folder)
            store.deleteFeed(feedId)
            Updates.schedule(context)
            server.shutdown()
        }
    }
    private fun scenarioSurface(scenario: ActivityScenario<FixtureHostActivity>): View {
        lateinit var view: View
        scenario.onActivity { view = it.surface }
        return view
    }
    private fun setWidgetSize(manager: AppWidgetManager, id: Int, width: Int, height: Int) {
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            if (android.os.Build.VERSION.SDK_INT >= 31) putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(android.util.SizeF(width.toFloat(), height.toFloat())))
        })
    }
    private fun assertWidgetTextFits(root: View) {
        fun check(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is ViewFlipper) { view.currentView?.let { check(it) }; return }
            if (view is TextView && view.text.isNotEmpty()) {
                val layout = requireNotNull(view.layout)
                val visibleLines = if (view.maxLines > 0) minOf(layout.lineCount, view.maxLines) else layout.lineCount
                if (layout.getLineBottom(visibleLines - 1) > view.height - view.compoundPaddingTop - view.compoundPaddingBottom + 1)
                    layoutErrors.add("Vertically clipped text: ${view.resources.getResourceEntryName(view.id)}, lines=${layout.lineCount}, max=${view.maxLines}, bottom=${layout.getLineBottom(visibleLines - 1)}, available=${view.height - view.compoundPaddingTop - view.compoundPaddingBottom}")
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) check(view.getChildAt(i))
        }
        try {
            // Options callbacks can reapply RemoteViews after idle. Inspect readiness and bounds
            // atomically on the UI thread, never an unmeasured newly-added row between frames.
            waitFor("widget text layout settled") {
                if (!readyForCapture(root)) false else { check(root); true }
            }
        } finally { capture(root, "widget-layout-latest") }
    }
    // Gecko exposes virtual descendants but does not consistently implement the platform's
    // findAccessibilityNodeInfosByText search. Traverse the bounded public node tree instead.
    private fun containsText(node: android.view.accessibility.AccessibilityNodeInfo?, value: String,
                             remaining: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(2000)): Boolean {
        if (node == null || remaining.decrementAndGet() < 0) return false
        if (node.text?.contains(value) == true || node.contentDescription?.contains(value) == true) return true
        for (i in 0 until node.childCount) if (containsText(node.getChild(i), value, remaining)) return true
        return false
    }
    private fun findText(view: View, value: String): TextView? {
        if (view is TextView && view.text.toString() == value) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), value)?.let { return it }
        return null
    }
    private fun readyForCapture(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return true
        if (view.isLayoutRequested || view.width <= 0 || view.height <= 0 || view.alpha < 0.99f) return false
        if (view is AppWidgetHostView && view.findViewById<View>(R.id.hero_title) == null && view.findViewById<View>(R.id.cards) == null) return false
        if (view is ViewFlipper) return view.currentView?.let { readyForCapture(it) } ?: false
        if (view is TextView && view.text.isNotEmpty() && view.layout == null) return false
        if (view is ViewGroup) for (i in 0 until view.childCount) if (!readyForCapture(view.getChildAt(i))) return false
        return true
    }
    private fun capture(view: View, name: String) {
        // Checking readiness and drawing must be one UI-thread action: a size-map reapply can
        // replace the hierarchy between separate calls, producing an empty transitional frame.
        waitFor("render capture $name") {
            if (!readyForCapture(view)) false else {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                true
            }
        }
    }
    private fun swipe(view: View, horizontal: Boolean) {
        val location = IntArray(2)
        var width = 0; var height = 0
        instrumentation.runOnMainSync {
            assertTrue("Fixture must own focus before injecting input", view.hasWindowFocus())
            view.getLocationOnScreen(location); width = view.width; height = view.height
        }
        assertEquals("Do not inject into other apps", context.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val x = location[0] + width * if (horizontal) 0.8f else 0.5f
        val y = location[1] + height * if (horizontal) 0.5f else 0.8f
        val dx = if (horizontal) -width * 0.6f else 0f
        val dy = if (horizontal) 0f else -height * 0.6f
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), if (step == 0) MotionEvent.ACTION_DOWN else if (step == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                x + dx * step / 12f, y + dy * step / 12f, 0)
            instrumentation.sendPointerSync(event); event.recycle(); Thread.sleep(20)
        }
        instrumentation.waitForIdleSync()
    }
}
