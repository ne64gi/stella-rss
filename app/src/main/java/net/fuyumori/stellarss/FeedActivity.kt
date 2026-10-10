package net.fuyumori.stellarss

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.widget.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*

class FeedActivity : Screen() {
    private lateinit var feeds: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        heading("フィード", "購読と表示を、自分好みに。")
        root.addView(button("＋ フィードを追加") { edit(null) })
        feeds = scrollColumn()
        if (savedInstanceState == null) lifecycleScope.launch {
            val id = intent.getLongExtra("edit", 0)
            if (id != 0L) edit(withContext(Dispatchers.IO) { rss.store.feed(id) })
            else if (intent.getBooleanExtra("add", false)) edit(null)
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { rss.store.watchFeeds().collect { render(it) } } }
    }
    private fun render(items: List<Feed>) {
        feeds.removeAllViews()
        if (items.isEmpty()) feeds.addView(label("RSS 1.0 / RSS 2.0 / Atom のフィードURLを追加してください。", 16f, MUTED))
        items.forEach { feed ->
            val panel = column().apply { rounded(); setPadding(dp(16), dp(12), dp(16), dp(12)) }
            panel.addView(label(feed.name, 20f))
            panel.addView(label(feed.url, 12f, MUTED))
            val time = if (feed.lastChecked == 0L) "未取得" else "最終確認 ${DateUtils.getRelativeTimeSpanString(feed.lastChecked)}"
            panel.addView(label(feed.error ?: if (feed.enabled) time else "購読を一時停止中", 12f, if (feed.error == null) ACCENT else 0xFFFFBC95.toInt()))
            val buttons = row()
            buttons.addView(button("編集・表示設定") { edit(feed) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            buttons.addView(button("削除") {
                AlertDialog.Builder(this).setTitle("${feed.name}を削除")
                    .setMessage("このフィードと保存記事をStella RSSから削除します。")
                    .setNegativeButton("キャンセル", null).setPositiveButton("削除") { _, _ ->
                        appScope.launch { rss.deleteFeed(feed.id) }
                    }.show()
            })
            panel.addView(buttons)
            feeds.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }
    private fun edit(old: Feed?) {
        val panel = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val name = field("表示名", old?.name.orEmpty())
        val url = field("RSS / Atom URL", old?.url.orEmpty()).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        panel.addView(name); panel.addView(url)
        fun check(label: String, value: Boolean) = CheckBox(this).apply { text = label; isChecked = value; panel.addView(this) }
        val enabled = check("購読・更新する", old?.enabled ?: true)
        val widgets = check("ウィジェットにも表示", old?.inWidgets ?: true)
        val images = check("画像を表示", old?.showImages ?: true)
        val summary = check("リーダーで概要を表示", old?.showSummary ?: true)
        if (old != null) panel.addView(label("URLを変更すると、このフィードの保存記事を入れ替えます。", 12f, MUTED))
        val scroll = ScrollView(this).apply { addView(panel) }
        val dialog = AlertDialog.Builder(this).setTitle(if (old == null) "フィードを追加" else "フィードを編集")
            .setView(scroll).setNegativeButton("キャンセル", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val feed = (old ?: Feed(url = "")).copy(name = name.text.toString(), url = url.text.toString(),
                    enabled = enabled.isChecked, inWidgets = widgets.isChecked, showImages = images.isChecked, showSummary = summary.isChecked)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    try { withContext(Dispatchers.IO) {
                        val id = rss.saveFeed(feed)
                        val folder = intent.getLongExtra("folder", 0)
                        if (old == null && folder != 0L && rss.store.folders().any { it.id == folder }) {
                            Organization(rss.db).classify(id, setOf(folder)); Widgets.updateAll(this@FeedActivity)
                        }
                    }; dialog.dismiss()
                    } catch (e: Exception) { url.error = Repository.friendlyError(e); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                }
            }
        }
        dialog.show()
    }
}
