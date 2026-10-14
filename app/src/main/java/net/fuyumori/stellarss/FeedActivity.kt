package net.fuyumori.stellarss

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/** One subscription's details; the library tree owns browsing and management. */
class FeedActivity : Screen() {
    private var old: Feed? = null
    private var library = Library()
    private var folderId: Long? = null
    private var containerId = 1L
    private var extensions = false
    private lateinit var translation: Spinner
    private lateinit var name: EditText
    private lateinit var url: EditText
    private lateinit var enabled: CheckBox
    private lateinit var widgets: CheckBox
    private lateinit var images: CheckBox
    private lateinit var summary: CheckBox
    private lateinit var destination: TextView
    private lateinit var save: TextView
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editId = intent.getLongExtra("edit", 0)
        folderId = (savedInstanceState?.getLong("destination") ?: intent.getLongExtra("folder", 0)).takeIf { it != 0L }
        val header = row().apply { setPadding(dp(4), dp(4), dp(8), dp(4)) }
        header.addView(iconButton("‹", "戻る") { finish() })
        header.addView(label(if (editId == 0L) "フィードを追加" else "フィードの詳細", 18f).apply {
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        save = label("保存", 14f, ACCENT).apply {
            id = R.id.feed_save; gravity = Gravity.CENTER; isEnabled = false; isFocusable = true
            setOnClickListener { saveFeed() }
        }
        header.addView(save, LinearLayout.LayoutParams(dp(56), dp(48)))
        root.addView(header)
        val content = scrollColumn()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    library = Organization(rss.db).library()
                    if (editId != 0L) old = rss.store.feed(editId) ?: error("このフィードは削除されています")
                    extensions = ReadingSpaces(rss.db).state().enabled
                    containerId = old?.containerId ?: library.folders.find { it.id == folderId }?.containerId ?: intent.getLongExtra("container", 1)
                    library = library.inContainer(containerId)
                }
                buildForm(content, savedInstanceState)
                save.isEnabled = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                content.addView(label(Repository.friendlyError(e), 14f, MUTED))
            }
        }
    }

    private fun buildForm(panel: LinearLayout, draft: Bundle?) {
        fun caption(text: String) = label(text, 12f, MUTED).apply {
            setPadding(0, dp(16), 0, dp(4)); panel.addView(this)
        }
        caption(if (old == null) "登録先" else "フォルダ")
        destination = label("", 14f, ACCENT).apply {
            id = R.id.feed_destination; setPadding(0, dp(12), 0, dp(12))
            if (old == null) { isFocusable = true; setOnClickListener { chooseFolder() } }
        }
        panel.addView(destination); showDestination()
        caption("RSS / Atom URL")
        url = field("https://…", draft?.getString("url") ?: old?.url.orEmpty()).apply {
            id = R.id.feed_url; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        panel.addView(url)
        caption("表示名（空欄なら自動取得）")
        name = field("フィード名", draft?.getString("name") ?: old?.name.orEmpty()).apply { id = R.id.feed_name }
        panel.addView(name)
        if (old == null) panel.addView(label("フォルダを指定した場合、登録済みのURLは既存の購読をそのフォルダにも追加します。", 12f, MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })
        caption("表示と更新")
        fun check(title: String, value: Boolean, key: String, viewId: Int) = CheckBox(this).apply {
            id = viewId; text = title; textSize = 14f; setTextColor(INK)
            isChecked = draft?.getBoolean(key) ?: value; minHeight = dp(48); panel.addView(this)
        }
        enabled = check("購読・更新する", old?.enabled ?: true, "enabled", R.id.feed_enabled)
        widgets = check("ウィジェットにも表示", old?.inWidgets ?: true, "widgets", R.id.feed_widgets)
        images = check("画像を表示", old?.showImages ?: true, "images", R.id.feed_images)
        summary = check("一覧に概要を表示", old?.showSummary ?: true, "summary", R.id.feed_summary)
        caption("記事の翻訳")
        translation = Spinner(this).apply {
            adapter = ArrayAdapter(this@FeedActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(if (extensions) "コンテナの設定を使う" else "標準設定を使う", "手動で翻訳", "開いたら日本語に翻訳"))
            setSelection(draft?.getInt("translation") ?: when (old?.translationTarget) { "" -> 1; "ja" -> 2; else -> 0 })
        }
        panel.addView(translation)
        panel.addView(label("翻訳はGoogleの端末内モデルで行います。初回は言語データの取得が必要です。", 12f, MUTED))
        old?.let { feed ->
            caption("取得状態")
            val time = if (feed.lastChecked == 0L) "未取得" else "最終確認 ${DateUtils.getRelativeTimeSpanString(feed.lastChecked)}"
            panel.addView(label(feed.error ?: time, 13f, if (feed.error == null) MUTED else 0xFFFFBC95.toInt()))
            panel.addView(label("URLを変更すると保存記事を入れ替えます。フォルダの変更・削除はツリーの項目メニューから行えます。", 12f, MUTED).apply {
                setPadding(0, dp(16), 0, dp(16))
            })
        }
    }
    private fun showDestination() {
        destination.text = if (old == null) {
            val folder = library.folders.firstOrNull { it.id == folderId }
            (if (folderId == null) "未分類" else folder?.let { library.path(it) } ?: "登録先がありません") + "  ▾"
        } else {
            val ids = library.links.filter { it.feedId == old!!.id }.map { it.folderId }.toSet()
            library.sortedPaths().filter { it.id in ids }.joinToString("\n") { library.path(it) }.ifBlank { "未分類" }
        }
    }
    private fun chooseFolder() {
        lifecycleScope.launch {
            library = withContext(Dispatchers.IO) { Organization(rss.db).library().inContainer(containerId) }
            val folders = library.sortedPaths()
            AlertDialog.Builder(this@FeedActivity).setTitle("登録先フォルダ")
                .setSingleChoiceItems((listOf("未分類") + folders.map { library.path(it) }).toTypedArray(),
                    folders.indexOfFirst { it.id == folderId } + 1) { dialog, index ->
                    folderId = folders.getOrNull(index - 1)?.id; showDestination(); dialog.dismiss()
                }.setNegativeButton("キャンセル", null).show()
        }
    }
    private fun saveFeed() {
        if (saving) return
        val value = (old ?: Feed(url = "")).copy(name = name.text.toString(), url = url.text.toString(),
            enabled = enabled.isChecked, inWidgets = widgets.isChecked, showImages = images.isChecked, showSummary = summary.isChecked, containerId = containerId,
            translationTarget = when (translation.selectedItemPosition) { 1 -> ""; 2 -> "ja"; else -> null })
        val target = if (old == null) folderId else null
        saving = true; save.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { rss.saveFeed(value, target) }
                toast(if (target != null) "フォルダに追加しました" else "保存しました"); finish()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                url.error = Repository.friendlyError(e); saving = false; save.isEnabled = true
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("destination", folderId ?: 0)
        if (::summary.isInitialized) {
            outState.putString("url", url.text.toString()); outState.putString("name", name.text.toString())
            outState.putBoolean("enabled", enabled.isChecked); outState.putBoolean("widgets", widgets.isChecked)
            outState.putInt("translation", translation.selectedItemPosition)
            outState.putBoolean("images", images.isChecked); outState.putBoolean("summary", summary.isChecked)
        }
        super.onSaveInstanceState(outState)
    }
}
