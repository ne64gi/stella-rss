package net.fuyumori.stellarss

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.abs

/** Reading order is a snapshot of the tapped list, so marking unread items read cannot skip a page. */
class OpenArticleActivity : Screen() {
    private lateinit var web: ReadingWebView
    private lateinit var toolbar: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var position: TextView
    private lateinit var saved: TextView
    private lateinit var previous: TextView
    private lateinit var next: TextView
    private lateinit var original: TextView
    private var queue = arrayListOf<String>()
    private var index = 0
    private var settings = ReaderSettings()
    private var row: ArticleRow? = null
    private var loading: Job? = null
    private var initialized = false
    private var restoreScroll = 0
    private var documentUrl = ""
    @Volatile private var imageMap: Map<String, String> = emptyMap()
    var displayedPage: ArticleHtml.Page? = null
        private set
    val currentArticleId: String? get() = queue.getOrNull(index)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = savedInstanceState?.getString("current") ?: intent.getStringExtra("article")
        if (requested == null) { openList(); return }
        toolbar = row().apply { setPadding(dp(4), 0, dp(4), 0) }
        toolbar.addView(iconButton("‹", "記事一覧へ戻る") { leaveReader() })
        position = label("Stella RSS / 記事", 14f)
        toolbar.addView(position, LinearLayout.LayoutParams(0, -2, 1f))
        saved = iconButton("☆", "あとで読むに保存") { toggleSaved() }
        toolbar.addView(saved)
        toolbar.addView(iconButton("Aa", "記事の表示設定") { settingsDialog() }.apply { textSize = 17f; id = R.id.reading_settings })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(52)))
        web = ReadingWebView(this) { delta -> move(delta) }.apply { id = R.id.reading_web }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = row().apply { setPadding(dp(4), dp(2), dp(4), dp(2)) }
        previous = iconButton("‹", "前の記事") { move(-1) }.apply { id = R.id.reading_previous }
        next = iconButton("›", "次の記事") { move(1) }.apply { id = R.id.reading_next }
        original = label("元記事を開く ↗", 14f, ACCENT).apply {
            id = R.id.reading_original; gravity = android.view.Gravity.CENTER; isClickable = true; isFocusable = true
            setOnClickListener { row?.article?.url?.let(::openExternal) }
        }
        footer.addView(previous); footer.addView(original, LinearLayout.LayoutParams(0, dp(48), 1f)); footer.addView(next)
        root.addView(footer)
        configureWebView()
        lifecycleScope.launch {
            val initial = withContext(Dispatchers.IO) {
                settings = ReaderSettings.decode(rss.store.preference(ReaderSettings.KEY))
                savedInstanceState?.getStringArrayList("queue") ?: intent.getStringArrayListExtra("queue") ?: run {
                    val widgetId = intent.getIntExtra("widget", -1)
                    val config = rss.store.widget(widgetId)
                    if (config != null) ArrayList(Organization(rss.db).widgetRows(config).map { it.article.id })
                    else arrayListOf(requested)
                }
            }
            queue = ArrayList(initial.distinct().take(1000))
            if (requested !in queue) queue.add(0, requested)
            index = queue.indexOf(requested)
            initialized = true
            restoreScroll = savedInstanceState?.getInt("scroll") ?: 0
            loadCurrent()
        }
    }
    @Suppress("DEPRECATION")
    private fun configureWebView() {
        web.settings.apply {
            javaScriptEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false; allowContentAccess = false
            allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false; databaseEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false); mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true; displayZoomControls = false
            textZoom = (resources.configuration.fontScale * 100).toInt()
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.setDownloadListener { _, _, _, _, _ -> toast("ダウンロードは元記事から行ってください") }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Nothing from a publisher ever navigates this WebView, even a permitted HTTP URL.
                val safe = webUrl(request.url.toString())
                if (safe != null && request.isForMainFrame && request.hasGesture()) {
                    AlertDialog.Builder(this@OpenArticleActivity).setTitle("リンクをブラウザーで開く")
                        .setMessage(safe).setPositiveButton("開く") { _, _ -> openExternal(safe) }.setNegativeButton("キャンセル", null).show()
                }
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val source = imageMap[request.url.toString()]
                // Exact, per-page image map; all other network/file/content/style/script requests are denied.
                if (request.method == "GET" && !request.isForMainFrame && source != null) {
                    try {
                        val bitmap = runBlocking(Dispatchers.IO) { rss.images.fetch(source) }
                        if (bitmap != null) {
                            val output = ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output)
                            return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(output.toByteArray()))
                        }
                    } catch (_: Exception) { /* Missing/offline image never prevents reading. */ }
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url == documentUrl && restoreScroll > 0) {
                    val y = restoreScroll; restoreScroll = 0
                    view.post { view.scrollTo(0, y) }
                }
            }
        }
    }
    private fun loadCurrent() {
        val id = currentArticleId ?: return
        loading?.cancel()
        imageMap = emptyMap(); displayedPage = null
        original.isEnabled = false
        web.stopLoading(); web.visibility = View.INVISIBLE
        updateChrome()
        loading = lifecycleScope.launch {
            row = withContext(Dispatchers.IO) { rss.store.articleRow(id) }
            val current = row
            if (current == null) {
                web.visibility = View.VISIBLE
                web.loadDataWithBaseURL(ArticleHtml.ORIGIN, "<p>この記事は削除されたか、保存されていません。</p>", "text/html", "UTF-8", null)
                return@launch
            }
            render(current)
            // Once shown, a quick page turn or Back must not cancel the read-state write.
            appScope.launch { rss.markRead(id, true) }
        }
    }
    private suspend fun render(current: ArticleRow) {
        val page = withContext(Dispatchers.Default) {
            ArticleHtml.page(current, settings, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.article.published)), UUID.randomUUID().toString())
        }
        imageMap = page.images; displayedPage = page
        documentUrl = ArticleHtml.ORIGIN + "article/" + UUID.randomUUID()
        web.visibility = View.VISIBLE
        web.loadDataWithBaseURL(documentUrl, page.html, "text/html", "UTF-8", null)
        original.isEnabled = true
        updateChrome()
    }
    private fun updateChrome() {
        val bg = Color.parseColor(if (settings.light) "#FAFAF7" else "#0B1220")
        val ink = if (settings.light) Color.parseColor("#202936") else INK
        val accent = if (settings.light) Color.parseColor("#166681") else ACCENT
        root.setBackgroundColor(bg); web.setBackgroundColor(bg)
        for (line in listOf(toolbar, footer)) for (i in 0 until line.childCount) (line.getChildAt(i) as? TextView)?.setTextColor(ink)
        original.setTextColor(accent)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = settings.light; isAppearanceLightNavigationBars = settings.light
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = bg
        position.text = "記事  ${index + 1} / ${queue.size}"
        previous.isEnabled = index > 0; previous.alpha = if (previous.isEnabled) 1f else .3f
        next.isEnabled = index < queue.lastIndex; next.alpha = if (next.isEnabled) 1f else .3f
        saved.text = if (row?.article?.bookmarked == true) "★" else "☆"
        saved.contentDescription = if (row?.article?.bookmarked == true) "あとで読むから外す" else "あとで読むに保存"
    }
    private fun move(delta: Int) {
        if (!initialized) return
        val target = index + delta
        if (target !in queue.indices) { toast(if (delta < 0) "最初の記事です" else "最後の記事です"); return }
        index = target; restoreScroll = 0; row = null; loadCurrent()
    }
    private fun toggleSaved() {
        val current = row ?: return
        val updated = current.copy(article = current.article.copy(bookmarked = !current.article.bookmarked))
        row = updated; updateChrome()
        appScope.launch { rss.store.bookmark(updated.article.id, updated.article.bookmarked) }
    }
    private fun openExternal(url: String) {
        val safe = webUrl(url) ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: android.content.ActivityNotFoundException) { toast("記事を開くブラウザーが見つかりません") }
    }
    private fun leaveReader() { if (isTaskRoot) openList() else finish() }
    private fun openList() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish()
    }
    private fun settingsDialog() {
        if (!initialized) return
        val holder = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val light = CheckBox(this).apply { text = "ライトテーマ（OFFでダーク）"; isChecked = settings.light }
        val images = CheckBox(this).apply { text = "記事画像を表示"; isChecked = settings.images }
        val summary = CheckBox(this).apply { text = "概要を優先（OFFで本文を優先）"; isChecked = settings.summaryFirst }
        holder.addView(light)
        fun slider(title: String, min: Int, max: Int, value: Int, display: (Int) -> String): SeekBar {
            val label = label("$title · ${display(value)}", 14f)
            holder.addView(label)
            return SeekBar(this).apply {
                this.max = max - min; progress = value - min
                contentDescription = title
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, user: Boolean) { label.text = "$title · ${display(progress + min)}" }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
                holder.addView(this, LinearLayout.LayoutParams(-1, dp(44)))
            }
        }
        val size = slider("文字サイズ", 14, 28, settings.fontSp) { "$it sp" }
        val line = slider("行間", 13, 22, (settings.lineHeight * 10).toInt()) { "${it / 10f} 倍" }
        val margin = slider("余白", 8, 36, settings.margin) { "$it dp" }
        holder.addView(images); holder.addView(summary)
        val scroll = ScrollView(this).apply { addView(holder) }
        AlertDialog.Builder(this).setTitle("記事の表示設定").setView(scroll)
            .setPositiveButton("適用") { _, _ ->
                settings = ReaderSettings(light.isChecked, size.progress + 14, (line.progress + 13) / 10f, margin.progress + 8, images.isChecked, summary.isChecked)
                val encoded = settings.encode()
                appScope.launch { rss.store.save(Preference(ReaderSettings.KEY, encoded)) }
                restoreScroll = web.scrollY
                loading?.cancel()
                row?.let { current -> loading = lifecycleScope.launch { render(current) } }
            }.setNegativeButton("キャンセル", null).show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("queue", queue); outState.putString("current", currentArticleId)
        if (::web.isInitialized) outState.putInt("scroll", web.scrollY)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (::web.isInitialized) { imageMap = emptyMap(); web.stopLoading(); (web.parent as? android.view.ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }
}

/** Horizontal direction lock leaves vertical scrolling, text selection and pinch zoom to WebView. */
class ReadingWebView(context: Context, private val turn: (Int) -> Unit) : WebView(context) {
    private var x = 0f
    private var y = 0f
    private var down = 0L
    private var horizontal = false
    private var disabled = false
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x = event.x; y = event.y; down = event.eventTime; horizontal = false; disabled = false }
            MotionEvent.ACTION_POINTER_DOWN -> disabled = true
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - x; val dy = event.y - y
                if (abs(dy) > context.dp(24) && abs(dy) > abs(dx)) disabled = true
                if (!disabled && !horizontal && event.eventTime - down < 500 && abs(dx) > context.dp(24) && abs(dx) > abs(dy) * 1.8f && !canScrollHorizontally(if (dx > 0) -1 else 1)) {
                    horizontal = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                }
                if (horizontal) return true
            }
            MotionEvent.ACTION_UP -> if (horizontal) {
                val dx = event.x - x
                if (!disabled && abs(dx) > context.dp(72) && abs(dx) > abs(event.y - y) * 1.8f && event.eventTime - down < 1200) turn(if (dx < 0) 1 else -1)
                horizontal = false; performClick(); return true
            }
            MotionEvent.ACTION_CANCEL -> { horizontal = false; disabled = true }
        }
        return super.dispatchTouchEvent(event)
    }
    override fun performClick(): Boolean = super.performClick()
}
