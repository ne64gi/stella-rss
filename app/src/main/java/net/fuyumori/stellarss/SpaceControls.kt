package net.fuyumori.stellarss

import android.app.AlertDialog
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/** Optional UI only; ReadingSpaces owns persistence and migration rules. */
class SpaceControls(private val screen: MainActivity) {
    private val spaces = ReadingSpaces(screen.rss.db)
    private var state = ReadingSpaces.State()
    private var containers = emptyList<NewsContainer>()
    private val choice = screen.label("", 16f, ACCENT).apply {
        id = R.id.container_select; gravity = android.view.Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true
        setOnClickListener {
            AlertDialog.Builder(screen).setTitle("コンテナを切り替え")
                .setItems(containers.map { it.name }.toTypedArray()) { _, index -> change { spaces.select(containers[index].id) } }
                .setNegativeButton("キャンセル", null).show()
        }
    }
    val view = screen.row().apply {
        visibility = View.GONE; setPadding(screen.dp(16), screen.dp(4), screen.dp(4), screen.dp(4))
        addView(choice, LinearLayout.LayoutParams(0, screen.dp(48), 1f))
        addView(screen.iconButton("⋮", "コンテナを管理") { menu() })
    }
    fun render(value: ReadingSpaces.State, values: List<NewsContainer>) {
        state = value; containers = values
        view.visibility = if (state.enabled) View.VISIBLE else View.GONE
        choice.text = (containers.find { it.id == state.selected }?.name ?: "デフォルト") + " ▾"
    }
    fun extensions() {
        var enabled = state.enabled
        AlertDialog.Builder(screen).setTitle("拡張機能")
            .setMultiChoiceItems(arrayOf("コンテナでニュース空間を分ける"), booleanArrayOf(enabled)) { _, _, on -> enabled = on }
            .setPositiveButton("適用") { _, _ -> change { spaces.setEnabled(enabled) } }
            .setNegativeButton("キャンセル", null).show()
    }
    private fun menu() {
        val current = containers.find { it.id == state.selected } ?: return
        AlertDialog.Builder(screen).setTitle(current.name)
            .setItems(arrayOf("名前・翻訳設定", "コンテナを追加", "空のコンテナを削除")) { _, index ->
                when (index) {
                    0 -> edit(current)
                    1 -> edit(null)
                    2 -> AlertDialog.Builder(screen).setTitle("${current.name}を削除")
                        .setMessage("中にフィードやフォルダがある場合は削除しません。")
                        .setPositiveButton("削除") { _, _ -> change { spaces.deleteEmpty(current.id) } }
                        .setNegativeButton("キャンセル", null).show()
                }
            }.show()
    }
    private fun edit(old: NewsContainer?) {
        val name = screen.field("コンテナ名", old?.name.orEmpty())
        val auto = CheckBox(screen).apply { text = "記事を開いたら日本語に翻訳"; isChecked = old?.translationTarget == "ja" }
        val box = screen.column().apply {
            setPadding(screen.dp(20), screen.dp(8), screen.dp(20), screen.dp(8)); addView(name); addView(auto)
            addView(screen.label("Googleの端末内翻訳を使用します。初回に言語データが必要です。フィードごとにも変更できます。", 12f, MUTED))
        }
        val dialog = AlertDialog.Builder(screen).setTitle(if (old == null) "コンテナを追加" else "コンテナの設定")
            .setView(box).setPositiveButton("保存", null).setNegativeButton("キャンセル", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            screen.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val id = spaces.save(old?.id ?: 0, name.text.toString(), if (auto.isChecked) "ja" else "")
                        if (old == null) spaces.select(id)
                    }
                    dialog.dismiss()
                } catch (e: Exception) { if (e is CancellationException) throw e; name.error = e.message }
            }
        } }; dialog.show()
    }
    fun move(id: Long, folder: Boolean) {
        if (!state.enabled) return
        val targets = containers.filter { it.id != state.selected }
        if (targets.isEmpty()) { screen.toast("先に移動先のコンテナを作成してください"); return }
        AlertDialog.Builder(screen).setTitle("別のコンテナへ移動")
            .setItems(targets.map { it.name }.toTypedArray()) { _, index ->
                val target = targets[index]
                AlertDialog.Builder(screen).setTitle("${target.name}へ移動")
                    .setMessage(if (folder) "配下のフォルダとフィードを移動します。移動するフィードの、このフォルダ外の分類は解除します。既読・保存記事は残ります。"
                        else "現在のフォルダ分類を外して移動します。既読・保存記事は残ります。")
                    .setPositiveButton("移動") { _, _ -> change { if (folder) spaces.moveFolder(id, target.id) else spaces.moveFeed(id, target.id) } }
                    .setNegativeButton("キャンセル", null).show()
            }.show()
    }
    private fun change(action: suspend () -> Unit) {
        screen.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { action(); Widgets.updateAll(screen) } }
            catch (e: Exception) { if (e is CancellationException) throw e; screen.toast(e.message ?: "変更できませんでした") }
        }
    }
}
