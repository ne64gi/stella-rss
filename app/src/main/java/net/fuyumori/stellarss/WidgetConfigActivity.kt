package net.fuyumori.stellarss

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class WidgetConfigActivity : Screen() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var config: WidgetConfig
    private lateinit var backgroundField: EditText
    private lateinit var foregroundField: EditText
    private lateinit var alpha: SeekBar
    private lateinit var font: SeekBar
    private lateinit var corners: SeekBar
    private lateinit var images: CheckBox
    private lateinit var unread: CheckBox
    private val selected = mutableSetOf<Long>()
    private var allFeeds = true
    private var folderId: Long? = null
    private var sourceMode = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider?.packageName != packageName) { finish(); return }
        heading("ウィジェット設定", "このウィジェットだけに適用")
        lifecycleScope.launch {
            config = withContext(Dispatchers.IO) { rss.store.widget(widgetId) ?: WidgetConfig(widgetId) }
            if (savedInstanceState != null) config = config.copy(feedIds = savedInstanceState.getString("feeds", config.feedIds),
                folderId = if (savedInstanceState.containsKey("folder")) savedInstanceState.getLong("folder", -1).takeIf { it != -1L } else config.folderId,
                background = savedInstanceState.getInt("bg", config.background), textColor = savedInstanceState.getInt("fg", config.textColor),
                opacity = savedInstanceState.getInt("opacity", config.opacity), fontSp = savedInstanceState.getInt("font", config.fontSp),
                cornerDp = savedInstanceState.getInt("corners", config.cornerDp),
                images = savedInstanceState.getBoolean("images", config.images), unreadOnly = savedInstanceState.getBoolean("unread", config.unreadOnly))
            selected.addAll(config.selectedFeeds()); folderId = config.folderId
            sourceMode = if (folderId != null) 2 else if (selected.isEmpty()) 0 else 1
            allFeeds = sourceMode == 0
            val library = withContext(Dispatchers.IO) { Organization(rss.db).library() }
            val feeds = library.feeds
            val content = scrollColumn()
            content.addView(label("見た目", 22f, ACCENT))
            content.addView(label("背景色（#RRGGBB）", 13f, MUTED))
            backgroundField = field("#242629", hex(config.background)); content.addView(backgroundField)
            content.addView(label("文字色（#RRGGBB）", 13f, MUTED))
            foregroundField = field("#F4F7FC", hex(config.textColor)); content.addView(foregroundField)
            val presets = row()
            for ((name, colors) in listOf("ダーク" to (0xFF242629.toInt() to 0xFFF5F5F5.toInt()), "ガラス" to (0xFF20252A.toInt() to 0xFFF5F5F5.toInt()), "紙" to (Color.WHITE to Color.BLACK), "森" to (0xFF11372B.toInt() to 0xFFF0FFEF.toInt()))) {
                presets.addView(button(name) { backgroundField.setText(hex(colors.first)); foregroundField.setText(hex(colors.second))
                    alpha.progress = if (name == "ガラス") 48 else if (name == "ダーク") 98 else 100 }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            content.addView(presets)
            fun slider(title: String, maximum: Int, progressValue: Int, offset: Int = 0, step: Int = 1): SeekBar {
                val label = label("$title ${progressValue * step + offset}", 14f)
                content.addView(label)
                return SeekBar(this@WidgetConfigActivity).apply {
                    max = maximum; progress = progressValue
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) { label.text = "$title ${value * step + offset}" }
                        override fun onStartTrackingTouch(bar: SeekBar) {}
                        override fun onStopTrackingTouch(bar: SeekBar) {}
                    }); content.addView(this)
                }
            }
            alpha = slider("背景の不透明度 (%)", 100, config.opacity)
            font = slider("文字サイズ (sp)", 12, config.fontSp - 12, 12)
            corners = slider("角丸 (dp)", 3, config.cornerDp / 8, step = 8)
            content.addView(label("ガラスは半透明＋薄い縁のスタイルです。壁紙のぼかしは行わず、文字は不透明のまま表示します。", 12f, MUTED))
            images = CheckBox(this@WidgetConfigActivity).apply { text = "記事画像を表示"; isChecked = config.images; content.addView(this) }
            unread = CheckBox(this@WidgetConfigActivity).apply { text = "未読記事のみ"; isChecked = config.unreadOnly; content.addView(this) }
            content.addView(label("ニュースの配信元", 22f, ACCENT))
            content.addView(label("フォルダは子孫を含み、後から追加したフィードにも追従します。フィード側の「ウィジェットにも表示」が有効なものが対象です。", 12f, MUTED))
            val modes = Spinner(this@WidgetConfigActivity).apply {
                adapter = ArrayAdapter(this@WidgetConfigActivity, android.R.layout.simple_spinner_dropdown_item, listOf("全フィード", "フィードを選択", "フォルダを選択"))
                setSelection(sourceMode)
            }
            content.addView(modes)
            val feedChoices = column()
            feeds.forEach { feed ->
                feedChoices.addView(CheckBox(this@WidgetConfigActivity).apply {
                    text = feed.name; isChecked = feed.id in selected
                    setOnCheckedChangeListener { _, checked -> if (checked) selected.add(feed.id) else selected.remove(feed.id) }
                })
            }
            content.addView(feedChoices)
            val folders = library.sortedPaths().toMutableList()
            if (folderId != null && folders.none { it.id == folderId }) folders.add(Folder(folderId!!, "削除されたフォルダ（表示は空）"))
            val folderChoices = Spinner(this@WidgetConfigActivity).apply {
                adapter = ArrayAdapter(this@WidgetConfigActivity, android.R.layout.simple_spinner_dropdown_item,
                    if (folders.isEmpty()) listOf("先にリーダーでフォルダを作成してください") else folders.map { library.path(it) })
                setSelection(folders.indexOfFirst { it.id == folderId }.coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { folderId = folders.getOrNull(position)?.id }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            content.addView(folderChoices)
            fun showSources() {
                feedChoices.visibility = if (sourceMode == 1) android.view.View.VISIBLE else android.view.View.GONE
                folderChoices.visibility = if (sourceMode == 2) android.view.View.VISIBLE else android.view.View.GONE
            }
            modes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    sourceMode = position; allFeeds = position == 0; showSources()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            showSources()
            content.addView(label("サイズはホームで長押しして変更できます。狭い枠では文字サイズ・行数を調整し、長い見出しは省略します。コンパクト版の設定・更新はリーダーから行えます。", 13f, MUTED))
            if (info.provider.className.endsWith("CompactWidget")) content.addView(label("コンパクト版は下の小さな点をタップして切り替えます。標準Androidウィジェットは横スワイプに対応していません。", 13f, ACCENT))
            val preview = FrameLayout(this@WidgetConfigActivity)
            content.addView(button("プレビュー") {
                try {
                    val value = readConfig()
                    lifecycleScope.launch {
                        val views = withContext(Dispatchers.IO) {
                            val compact = info.provider.className.endsWith("CompactWidget")
                            Widgets.build(this@WidgetConfigActivity, value, Widgets.rows(this@WidgetConfigActivity, value), compact,
                                android.util.SizeF(resources.displayMetrics.widthPixels / resources.displayMetrics.density - 40, if (compact) 120f else 300f))
                        }
                        preview.removeAllViews(); preview.addView(views.apply(this@WidgetConfigActivity, preview))
                        preview.layoutParams.height = dp(if (info.provider.className.endsWith("CompactWidget")) 120 else 300)
                        preview.requestLayout()
                    }
                } catch (e: Exception) { toast(e.message ?: "色を確認してください") }
            })
            content.addView(preview, LinearLayout.LayoutParams(-1, 0))
            root.addView(button("保存して適用") {
                try {
                    val value = readConfig()
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { rss.store.save(value); Widgets.updateAll(this@WidgetConfigActivity) }
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)); finish()
                    }
                } catch (e: Exception) { toast(e.message ?: "設定を確認してください") }
            })
        }
    }
    private fun readConfig(): WidgetConfig {
        require(sourceMode != 1 || selected.isNotEmpty()) { "表示フィードを1つ以上選んでください" }
        require(sourceMode != 2 || folderId != null) { "フォルダを選んでください" }
        fun color(text: String): Int {
            require(Regex("#[0-9a-fA-F]{6}").matches(text.trim())) { "色は #RRGGBB の6桁で入力してください" }
            return Color.parseColor(text.trim())
        }
        return config.copy(folderId = if (sourceMode == 2) folderId else null,
            feedIds = if (sourceMode == 1) selected.sorted().joinToString(",") else "", background = color(backgroundField.text.toString()),
            textColor = color(foregroundField.text.toString()), opacity = alpha.progress, fontSp = font.progress + 12,
            images = images.isChecked, unreadOnly = unread.isChecked, cornerDp = corners.progress * 8)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::font.isInitialized) runCatching { readConfig() }.getOrNull()?.let {
            outState.putLong("folder", it.folderId ?: -1); outState.putString("feeds", it.feedIds); outState.putInt("bg", it.background); outState.putInt("fg", it.textColor)
            outState.putInt("opacity", it.opacity); outState.putInt("font", it.fontSp); outState.putBoolean("images", it.images); outState.putBoolean("unread", it.unreadOnly)
            outState.putInt("corners", it.cornerDp)
        }
        super.onSaveInstanceState(outState)
    }
    private fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)
}
