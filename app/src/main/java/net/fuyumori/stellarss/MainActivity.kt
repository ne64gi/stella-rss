package net.fuyumori.stellarss

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private data class ArticleQuery(val ids: List<Long> = emptyList(), val all: Boolean = true, val unread: Boolean = false, val saved: Boolean = false, val containerId: Long? = null)
class MainActivity : Screen() {
    private var unread = false
    private var scope = "all"
    private var scopeId = 0L
    private var library = Library()
    private var spaceState = ReadingSpaces.State()
    private lateinit var spaceControls: SpaceControls
    private lateinit var headlineTranslation: HeadlineTranslation
    private val spaces by lazy { ReadingSpaces(rss.db) }
    internal val containersEnabled get() = spaceState.enabled
    internal fun moveToContainer(id: Long, folder: Boolean) = spaceControls.move(id, folder)
    private var libraryLoaded = false
    private var savedCount = 0
    private val query = MutableStateFlow(ArticleQuery(all = false))
    private var allRows: List<ArticleRow> = emptyList()
    private lateinit var articles: ArticleAdapter
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private lateinit var allTab: TextView
    private lateinit var unreadTab: TextView
    private lateinit var scopeTab: TextView
    private lateinit var drawer: DrawerLayout
    private lateinit var tree: LibraryDrawer
    private lateinit var refresh: ArticleRefreshLayout
    private var manualState: WorkInfo.State? = null
    private var undoMillis = 8000L
    private var markingAll = false
    private val organization by lazy { Organization(rss.db) }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val outlines = contentResolver.openInputStream(uri)?.use(Opml::parse) ?: error("ファイルを開けません")
                    organization.importOpml(outlines, if (spaceState.enabled) spaceState.selected else 1).also { Widgets.updateAll(this@MainActivity); Updates.refresh(this@MainActivity) }
                }
                toast("${result.feeds}フィード・${result.folders}フォルダを追加しました")
            } catch (e: Exception) { if (e is CancellationException) throw e; toast(e.message ?: "OPMLを読み込めません") }
        }
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Serialize completely before opening the destination, so errors don't truncate it.
                    val bytes = java.io.ByteArrayOutputStream().also { Opml.write(organization.library().inContainer(if (spaceState.enabled) spaceState.selected else null), it) }.toByteArray()
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("ファイルに書き込めません")
                }
                toast("OPMLを書き出しました")
            } catch (e: Exception) { if (e is CancellationException) throw e; toast(e.message ?: "書き出せません") }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        unread = savedInstanceState?.getBoolean("unread") ?: false
        scope = savedInstanceState?.getString("scope") ?: "all"
        scopeId = savedInstanceState?.getLong("scopeId") ?: 0
        spaceState = ReadingSpaces.State(savedInstanceState?.getBoolean("containers") ?: false, savedInstanceState?.getLong("scopeContainer", 1) ?: 1)
        drawer = DrawerLayout(this).apply { id = R.id.reader_drawer; setScrimColor(0x99000000.toInt()) }
        root.addView(drawer, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = column()
        drawer.addView(content, DrawerLayout.LayoutParams(-1, -1))
        val toolbar = row().apply { setPadding(dp(4), dp(4), dp(4), 0) }
        toolbar.addView(iconButton("☰", "フィードツリーを開く") { drawer.openDrawer(Gravity.LEFT) })
        val words = column().apply { setPadding(dp(6), 0, 0, 0) }
        words.addView(label("Stella RSS", 21f).apply { setTypeface(null, Typeface.BOLD) })
        status = label("すべての記事", 11f, MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        words.addView(status)
        toolbar.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(iconButton("✓", "すべて既読にする（選択中の範囲）") { markAllRead() }.apply { id = R.id.article_mark_all_read })
        toolbar.addView(iconButton("↻", "フィードを更新") { requestRefresh() })
        toolbar.addView(iconButton("⋮", "設定と管理") { settingsMenu() })
        content.addView(toolbar, LinearLayout.LayoutParams(-1, dp(60)))
        val tabs = row().apply { setPadding(dp(16), 0, dp(16), 0) }
        fun tab(text: String, click: () -> Unit) = label(text, 13f).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(4) }
            isClickable = true; setOnClickListener { click() }; tabs.addView(this)
        }
        allTab = tab("すべて") { unread = false; refreshQuery() }
        unreadTab = tab("未読") { unread = true; refreshQuery() }
        scopeTab = tab("全フィード ▾") { drawer.openDrawer(Gravity.LEFT) }.apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        }
        content.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))
        content.addView(View(this).apply { setBackgroundColor(DIVIDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        empty = label("記事を読み込み中…", 15f, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(32), dp(24), dp(32)) }
        articles = ArticleAdapter(this)
        val list = RecyclerView(this).apply {
            id = R.id.article_list; layoutManager = LinearLayoutManager(this@MainActivity); adapter = articles
            // Read/save updates should be immediate; don't leave duplicate fading rows.
            itemAnimator = null
            clipToPadding = false; setPadding(dp(16), 0, dp(16), dp(8))
        }
        headlineTranslation = HeadlineTranslation(this, list, { articles.currentList }, articles::showTitles)
        content.addView(headlineTranslation.view, LinearLayout.LayoutParams(-1, -2))
        refresh = ArticleRefreshLayout(this).apply {
            id = R.id.article_refresh
            setColorSchemeColors(ACCENT); setProgressBackgroundColorSchemeColor(SURFACE)
            setOnChildScrollUpCallback { _, _ -> list.canScrollVertically(-1) || drawer.isDrawerVisible(Gravity.LEFT) }
            setOnRefreshListener { requestRefresh() }
            addView(FrameLayout(this@MainActivity).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            })
        }
        content.addView(refresh, LinearLayout.LayoutParams(-1, 0, 1f))
        val undoBar = row().apply { visibility = View.GONE; setBackgroundColor(SURFACE); setPadding(dp(16), dp(4), dp(4), dp(4)) }
        val undoMessage = label("", 13f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; maxLines = 2 }
        undoBar.addView(undoMessage, LinearLayout.LayoutParams(0, -2, 1f))
        val undoButton = label("元に戻す", 14f, ACCENT).apply {
            id = R.id.article_undo; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            setPadding(dp(16), 0, dp(16), 0)
        }
        undoBar.addView(undoButton, LinearLayout.LayoutParams(-2, dp(48)))
        content.addView(undoBar)
        val accessibility = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        undoMillis = if (android.os.Build.VERSION.SDK_INT >= 29) accessibility.getRecommendedTimeoutMillis(8000,
            android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS).toLong() else 8000L
        var resetSwipe: () -> Unit = {}
        val swipeCallback = ArticleSwipeCallback(this) { holder, direction ->
            val id = (holder as ArticleAdapter.Holder).articleId ?: return@ArticleSwipeCallback
            // Capture identity before an unread filter/Room diff can move or remove this row.
            appScope.launch {
                try {
                    val changed = rss.swipeArticle(id, direction == ItemTouchHelper.LEFT, undoMillis)
                    if (!changed) withContext(Dispatchers.Main) { if (!isDestroyed) toast(if (direction == ItemTouchHelper.LEFT) "すでに既読です" else "保存済みです") }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    withContext(Dispatchers.Main) { if (!isDestroyed) toast("変更を保存できませんでした") }
                } finally {
                    // In All/Saved the row stays; even an already-read/saved swipe must snap back.
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (!isDestroyed) list.post {
                            // This is an action, not a dismiss: retained rows must leave
                            // ItemTouchHelper's pending-dismiss state even after a no-op.
                            resetSwipe()
                            val position = articles.currentList.indexOfFirst { it.article.id == id }
                            if (position >= 0) articles.notifyItemChanged(position)
                        }
                    }
                }
            }
        }
        var touchHelper = ItemTouchHelper(swipeCallback).also { it.attachToRecyclerView(list) }
        resetSwipe = {
            touchHelper.attachToRecyclerView(null)
            touchHelper = ItemTouchHelper(swipeCallback).also { it.attachToRecyclerView(list) }
        }
        tree = LibraryDrawer(this, { kind, id ->
            scope = kind; scopeId = id; refreshQuery(); drawer.closeDrawer(Gravity.LEFT)
        }, { folder -> addFeed(folder) }, { launchImport() }, { exportDocument.launch("stella-rss.opml") })
        spaceControls = SpaceControls(this)
        tree.view.addView(spaceControls.view, 0)
        drawer.addView(tree.view, DrawerLayout.LayoutParams(dp(320).coerceAtMost(resources.displayMetrics.widthPixels - dp(40)), -1, Gravity.LEFT))
        val back = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() { drawer.closeDrawer(Gravity.LEFT) } }
        onBackPressedDispatcher.addCallback(this, back)
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(view: View) { back.isEnabled = true }
            override fun onDrawerClosed(view: View) { back.isEnabled = false }
        })
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("manual-feed").observe(this) { work ->
            manualState = work.firstOrNull { !it.state.isFinished }?.state
            showStatus()
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { rss.swipeUndo.collectLatest { action ->
                val remaining = (action?.expiresAt ?: 0) - android.os.SystemClock.elapsedRealtime()
                undoBar.visibility = if (remaining > 0) View.VISIBLE else View.GONE
                if (action != null && remaining > 0) {
                    undoMessage.text = if (action.readAction) "${action.ids.size}件を既読にしました" else "あとで読むに移しました"
                    undoButton.isEnabled = true
                    undoButton.setOnClickListener {
                        undoButton.isEnabled = false
                        appScope.launch {
                            try { rss.undoSwipe(action) }
                            catch (e: Exception) {
                                if (e is CancellationException) throw e
                                withContext(Dispatchers.Main) { if (!isDestroyed) { toast("元に戻せませんでした"); undoButton.isEnabled = true } }
                            }
                        }
                    }
                    delay(remaining)
                }
                if (action != null) rss.dismissSwipeUndo(action)
            } }
            launch {
                withContext(Dispatchers.IO) { spaces.ensureDefault() }
                val base = combine(rss.store.watchFeeds(), rss.store.watchFolders(), rss.store.watchMemberships(), rss.store.watchUnread()) {
                    feeds, folders, links, counts -> Library(feeds, folders, links, counts)
                }
                combine(base, rss.store.watchContainers(), rss.store.watchSavedCounts(), spaces.watch()) { tree, containers, saved, state ->
                    tree.copy(containers = containers, saved = saved) to state
                }.collect { (tree, requested) ->
                    val state = requested.copy(selected = requested.selected.takeIf { id -> tree.containers.any { it.id == id } } ?: 1)
                    if (state != spaceState) { scope = "all"; scopeId = 0; allRows = emptyList(); articles.submitList(emptyList()) }
                    libraryLoaded = true; spaceState = state
                    library = tree.inContainer(if (state.enabled) state.selected else null)
                    headlineTranslation.eligibleFeeds(ReadingSpaces.automaticFeedIds(library.feeds, tree.containers, state.enabled))
                    savedCount = library.saved.sumOf { it.count }
                    if ((scope == "feed" && library.feeds.none { it.id == scopeId }) || (scope == "folder" && library.folders.none { it.id == scopeId })) { scope = "all"; scopeId = 0 }
                    spaceControls.render(state, tree.containers); refreshQuery()
                }
            }
            launch { query.flatMapLatest { selection -> rss.store.watchSelection(selection.ids, selection.all, selection.unread, selection.saved, selection.containerId).map { selection to it } }.collect { (selection, rows) ->
                if (selection != query.value) return@collect
                allRows = rows; articles.submitList(rows) { list.post { headlineTranslation.refresh() } }; showStatus()
            } }
            launch { rss.refreshing.collect { showStatus() } }
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("containers", spaceState.enabled); outState.putLong("scopeContainer", spaceState.selected)
        outState.putBoolean("unread", unread); outState.putString("scope", scope); outState.putLong("scopeId", scopeId)
        super.onSaveInstanceState(outState)
    }
    private fun selectionName() = when (scope) {
        "folder" -> library.folders.firstOrNull { it.id == scopeId }?.name ?: "削除されたフォルダ"
        "feed" -> library.feeds.firstOrNull { it.id == scopeId }?.name ?: "削除されたフィード"
        "saved" -> "あとで読む"
        else -> "すべての記事"
    }
    private fun refreshQuery() {
        if (!libraryLoaded) return
        val ids = when (scope) { "folder" -> library.feedIds(scopeId).sorted(); "feed" -> listOf(scopeId); else -> emptyList() }
        query.value = ArticleQuery(ids, scope == "all" || scope == "saved", unread, scope == "saved", if (spaceState.enabled) spaceState.selected else null)
        allTab.rounded(if (!unread) SURFACE else android.graphics.Color.TRANSPARENT, 16f)
        unreadTab.rounded(if (unread) SURFACE else android.graphics.Color.TRANSPARENT, 16f)
        allTab.setTextColor(if (!unread) INK else MUTED); unreadTab.setTextColor(if (unread) INK else MUTED)
        scopeTab.text = "${if (scope == "all") "全フィード" else selectionName()} ▾"; scopeTab.setTextColor(ACCENT)
        tree.render(library, savedCount, scope, scopeId); showStatus()
    }
    private fun showStatus() {
        val count = library.count(if (query.value.all) null else query.value.ids.toSet())
        val running = rss.refreshing.value || manualState == WorkInfo.State.RUNNING
        refresh.isRefreshing = running
        status.text = if (running) "${selectionName()} · 更新中…" else if (manualState == WorkInfo.State.ENQUEUED || manualState == WorkInfo.State.BLOCKED) "${selectionName()} · 更新待ち（接続・実行待ち）" else if (scope == "saved") "あとで読む · $savedCount 件" else "${selectionName()} · $count 件未読"
        empty.visibility = if (allRows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (library.feeds.isEmpty()) "左上の ☰ からフィードを追加、またはOPMLを読み込めます。" else if (scope == "saved") "記事を右へスワイプ、または長押しして「あとで読む」に保存できます。" else if (unread) "未読記事はありません。下に引くと更新できます。" else "表示できる記事がありません。フィードの選択や購読状態を確認してください。"
    }
    private fun requestRefresh() {
        if (!rss.refreshing.value) Updates.refresh(this)
        // Only spin during execution, never indefinitely while offline or waiting for retry.
        showStatus()
    }
    private fun markAllRead() {
        if (markingAll) return
        val selected = query.value // Never let a subsequent folder change alter this command.
        markingAll = true
        appScope.launch {
            try {
                val count = rss.markAllRead(selected.ids, selected.all, selected.saved, undoMillis, selected.containerId)
                if (count == 0) withContext(Dispatchers.Main) { if (!isDestroyed) toast("この範囲に未読記事はありません") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) { if (!isDestroyed) toast("既読状態を保存できませんでした") }
            } finally { withContext(NonCancellable + Dispatchers.Main) { markingAll = false } }
        }
    }
    private fun addFeed(folder: Long?) { startActivity(Intent(this, FeedActivity::class.java).putExtra("add", true).putExtra("folder", folder ?: 0L).putExtra("container", if (spaceState.enabled) spaceState.selected else 1L)) }
    private fun launchImport() { importDocument.launch(arrayOf("text/*", "application/xml", "application/octet-stream", "application/x-opml+xml")) }
    private fun settingsMenu() {
        AlertDialog.Builder(this).setTitle(selectionName()).setItems(arrayOf("すべて既読にする", "ウィジェット", "更新間隔", "OPMLを読み込む", "OPMLを書き出す", "拡張機能")) { _, item ->
            when (item) {
                0 -> markAllRead()
                1 -> widgetsMenu(); 2 -> intervalDialog(); 3 -> launchImport(); 4 -> exportDocument.launch("stella-rss.opml"); 5 -> spaceControls.extensions()
            }
        }.show()
    }
    private fun widgetsMenu() {
        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(ComponentName(this, NewsWidget::class.java)) + manager.getAppWidgetIds(ComponentName(this, CompactWidget::class.java))
        val labels = mutableListOf("ニュース一覧を追加", "コンパクトを追加")
        labels.addAll(ids.map { "ウィジェット #$it を設定" })
        AlertDialog.Builder(this).setTitle("ホームにニュースを").setItems(labels.toTypedArray()) { _, which ->
            if (which < 2) {
                if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this,
                    if (which == 0) NewsWidget::class.java else CompactWidget::class.java), null, null)
                else toast("ホームのウィジェット一覧からStella RSSを追加してください")
            } else startActivity(Intent(this, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, ids[which - 2]))
        }.setNegativeButton("閉じる", null).show()
    }
    private fun intervalDialog() {
        val times = listOf(15, 30, 60, 180, 360)
        lifecycleScope.launch {
            val selected = withContext(Dispatchers.IO) { rss.store.preference("interval")?.toIntOrNull() ?: 60 }
            AlertDialog.Builder(this@MainActivity).setTitle("定期更新（Androidにより遅延あり）")
                .setSingleChoiceItems(times.map { "$it 分" }.toTypedArray(), times.indexOf(selected)) { dialog, which ->
                    appScope.launch { rss.store.save(Preference("interval", times[which].toString())); Updates.schedule(this@MainActivity) }; dialog.dismiss()
                }.setNegativeButton("閉じる", null).show()
        }
    }
}
internal class ArticleAdapter(val screen: MainActivity) : ListAdapter<ArticleRow, ArticleAdapter.Holder>(object : DiffUtil.ItemCallback<ArticleRow>() {
    override fun areItemsTheSame(a: ArticleRow, b: ArticleRow) = a.article.id == b.article.id
    override fun areContentsTheSame(a: ArticleRow, b: ArticleRow) = a == b
}) {
    private var titles = emptyMap<String, String>()
    fun showTitles(value: Map<String, String>) {
        if (value == titles) return
        val previous = titles
        titles = value
        // A translated title must not rebind every unread row (or restart image requests).
        currentList.forEachIndexed { position, row ->
            val article = row.article
            if ((previous[article.id] ?: article.title) != (value[article.id] ?: article.title))
                notifyItemChanged(position, "headline")
        }
    }
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == "headline" }) {
            val row = getItem(position); holder.title.text = titles[row.article.id] ?: row.article.title
        } else super.onBindViewHolder(holder, position, payloads)
    }
    class Holder(val panel: LinearLayout, val image: ImageView, val source: TextView, val title: TextView, val summary: TextView) : RecyclerView.ViewHolder(panel) { var job: Job? = null; var articleId: String? = null }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
        val panel = screen.column().apply { layoutParams = RecyclerView.LayoutParams(-1, -2); setBackgroundColor(0xFF0B1220.toInt()) }
        val line = screen.row().apply { setPadding(0, screen.dp(11), 0, screen.dp(11)); minimumHeight = screen.dp(106) }
        val words = screen.column()
        val source = screen.label("", 10f, ACCENT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false }
        val title = screen.label("", 15f).apply {
            id = R.id.article_title; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
            setPadding(0, screen.dp(5), 0, screen.dp(4)); breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
        }
        val summary = screen.label("", 12f, MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false }
        words.addView(source); words.addView(title); words.addView(summary)
        line.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val image = ImageView(screen).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            rounded(SURFACE, 8f); clipToOutline = true
        }
        line.addView(image, LinearLayout.LayoutParams(screen.dp(72), screen.dp(72)).apply { leftMargin = screen.dp(12) })
        panel.addView(line)
        panel.addView(View(screen).apply { setBackgroundColor(DIVIDER) }, LinearLayout.LayoutParams(-1, screen.dp(1)))
        return Holder(panel, image, source, title, summary)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position); holder.job?.cancel(); holder.articleId = row.article.id; holder.panel.tag = "article:${row.article.id}"
        holder.title.text = titles[row.article.id] ?: row.article.title; holder.title.setTypeface(null, if (row.article.read) Typeface.NORMAL else Typeface.BOLD)
        holder.title.setTextColor(if (row.article.read) MUTED else INK)
        holder.source.text = "${if (row.article.bookmarked) "☆ " else if (!row.article.read) "• " else ""}${row.feedName} · ${DateUtils.getRelativeTimeSpanString(row.article.published)}"
        holder.source.setTextColor(if (row.article.read) MUTED else ACCENT)
        holder.summary.text = row.article.summary
        holder.summary.visibility = if (row.showSummary && row.article.summary.isNotBlank()) View.VISIBLE else View.GONE
        val hasImage = row.showImages && (row.article.imageUrl != null || row.article.imageCandidates.isNotBlank())
        holder.image.visibility = if (hasImage) View.VISIBLE else View.GONE
        holder.image.setImageResource(R.drawable.image_fallback)
        if (row.showImages) holder.job = screen.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { screen.rss.resolveImage(row.article) }
            if (bitmap != null) { holder.image.setImageBitmap(bitmap); holder.image.visibility = View.VISIBLE }
        }
        holder.panel.setOnClickListener { screen.startActivity(Intent(screen, OpenArticleActivity::class.java).putExtra("article", row.article.id).putStringArrayListExtra("queue", ArrayList(currentList.map { it.article.id }))) }
        holder.panel.setOnLongClickListener {
            AlertDialog.Builder(screen).setItems(arrayOf(if (row.article.read) "未読に戻す" else "既読にする", if (row.article.bookmarked) "あとで読むから外す" else "あとで読むに保存")) { _, which ->
                screen.appScope.launch {
                    if (which == 0) screen.rss.markRead(row.article.id, !row.article.read)
                    else screen.rss.store.bookmark(row.article.id, !row.article.bookmarked)
                }
            }.show(); true
        }
    }
    override fun onViewRecycled(holder: Holder) { holder.job?.cancel() }
}
