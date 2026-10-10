package net.fuyumori.stellarss

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Typeface
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

private data class TreeEntry(val kind: String, val id: Long = 0, val parent: Long? = null, val depth: Int = 0,
                             val name: String, val count: Int = 0)
private data class TreeDrag(val folder: Boolean, val id: Long, val from: Long?)
class LibraryDrawer(private val screen: MainActivity, private val select: (String, Long) -> Unit,
                    private val addFeed: () -> Unit, private val importOpml: () -> Unit, private val exportOpml: () -> Unit) {
    val view = screen.column().apply { setBackgroundColor(0xFF101B2A.toInt()); elevation = screen.dp(8).toFloat() }
    private val organization = Organization(screen.rss.db)
    private var library = Library()
    private var savedCount = 0
    private var scope = "all"
    private var scopeId = 0L
    private val closed = mutableSetOf<Long>()
    private var entries = emptyList<TreeEntry>()
    private var dragging = false
    private val adapter = TreeAdapter()
    private val list = RecyclerView(screen).apply {
        id = R.id.folder_tree; layoutManager = LinearLayoutManager(screen); adapter = this@LibraryDrawer.adapter; itemAnimator = null
        setOnDragListener { _, event ->
            if (event.localState !is TreeDrag) false else {
                if (event.action == DragEvent.ACTION_DRAG_LOCATION) {
                    if (event.y < screen.dp(56)) scrollBy(0, -screen.dp(12))
                    else if (event.y > height - screen.dp(56)) scrollBy(0, screen.dp(12))
                }
                if (event.action == DragEvent.ACTION_DRAG_ENDED) { dragging = false; rebuild() }
                event.action != DragEvent.ACTION_DROP
            }
        }
    }
    init {
        val header = screen.row().apply { setPadding(screen.dp(16), screen.dp(8), screen.dp(4), 0) }
        header.addView(screen.label("フィード", 21f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(screen.iconButton("＋", "追加とOPML") {
            AlertDialog.Builder(screen).setItems(arrayOf("フィードを追加", "フォルダを追加", "OPMLを読み込む", "OPMLを書き出す")) { _, which ->
                when (which) { 0 -> addFeed(); 1 -> editFolder(null, null); 2 -> importOpml(); 3 -> exportOpml() }
            }.show()
        })
        view.addView(header)
        view.addView(screen.label("長押ししてフォルダへ移動 · ⋯ で分類", 10f, MUTED).apply {
            setPadding(screen.dp(16), 0, screen.dp(12), screen.dp(12))
        })
        view.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(screen.button("＋ フィードを追加") { addFeed() })
    }
    fun render(value: Library, saved: Int, selected: String, id: Long) {
        library = value; savedCount = saved; scope = selected; scopeId = id
        if (!dragging) rebuild()
    }
    private fun rebuild() {
        val rows = mutableListOf(TreeEntry("all", name = "すべての記事", count = library.count()), TreeEntry("saved", name = "あとで読む", count = savedCount))
        val visited = mutableSetOf<Long>()
        val byParent = library.folders.groupBy { it.parentId }
        val feedMap = library.feeds.associateBy { it.id }
        val memberships = library.links.groupBy { it.folderId }
        fun folder(item: Folder, depth: Int) {
            if (!visited.add(item.id)) return
            rows.add(TreeEntry("folder", item.id, item.parentId, depth, item.name, library.count(library.feedIds(item.id))))
            if (item.id in closed) return
            byParent[item.id].orEmpty().forEach { folder(it, depth + 1) }
            memberships[item.id].orEmpty().mapNotNull { feedMap[it.feedId] }.sortedBy { it.name.lowercase() }.forEach {
                rows.add(TreeEntry("feed", it.id, item.id, depth + 1, it.name, library.count(setOf(it.id))))
            }
        }
        byParent[null].orEmpty().forEach { folder(it, 0) }
        rows.add(TreeEntry("root", name = "未分類", count = 0))
        val filed = library.links.map { it.feedId }.toSet()
        library.feeds.filter { it.id !in filed }.forEach {
            rows.add(TreeEntry("feed", it.id, null, 0, it.name, library.count(setOf(it.id))))
        }
        entries = rows; adapter.notifyDataSetChanged()
    }
    private fun change(action: suspend () -> Unit) {
        screen.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { action(); Widgets.updateAll(screen) } }
            catch (e: Exception) { if (e is CancellationException) throw e; screen.toast(e.message ?: "変更できません") }
        }
    }
    private fun editFolder(old: Folder?, parent: Long?) {
        val field = screen.field("フォルダ名", old?.name.orEmpty())
        val box = screen.column().apply { setPadding(screen.dp(24), screen.dp(8), screen.dp(24), screen.dp(8)); addView(field) }
        val dialog = AlertDialog.Builder(screen).setTitle(if (old == null) "フォルダを追加" else "フォルダ名を変更")
            .setView(box).setNegativeButton("キャンセル", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            screen.lifecycleScope.launch {
                try { withContext(Dispatchers.IO) { organization.saveFolder(old?.id ?: 0, field.text.toString(), old?.parentId ?: parent); Widgets.updateAll(screen) }; dialog.dismiss() }
                catch (e: Exception) { if (e is CancellationException) throw e; field.error = e.message }
            }
        } }; dialog.show()
    }
    private fun folderMenu(id: Long) {
        val item = library.folders.firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(screen).setTitle(library.path(item)).setItems(arrayOf("子フォルダを追加", "名前を変更", "フォルダを移動", "フォルダを削除")) { _, which ->
            when (which) {
                0 -> editFolder(null, id); 1 -> editFolder(item, null)
                2 -> {
                    val targets = library.sortedPaths().filter { it.id !in library.descendants(id) }
                    AlertDialog.Builder(screen).setTitle("移動先").setItems((listOf("最上位") + targets.map { library.path(it) }).toTypedArray()) { _, pos ->
                        change { organization.saveFolder(id, item.name, targets.getOrNull(pos - 1)?.id) }
                    }.show()
                }
                3 -> AlertDialog.Builder(screen).setTitle("${item.name}を削除")
                    .setMessage("配下のフォルダと分類を削除します。購読フィードと記事は残ります。このフォルダを指定したウィジェットは空になります。")
                    .setNegativeButton("キャンセル", null).setPositiveButton("削除") { _, _ -> change { organization.deleteFolder(id) } }.show()
            }
        }.show()
    }
    private fun feedMenu(id: Long) {
        val feed = library.feeds.firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(screen).setTitle(feed.name).setItems(arrayOf("分類を選択（複数可）", "フィードを編集")) { _, which ->
            if (which == 1) screen.startActivity(Intent(screen, FeedActivity::class.java).putExtra("edit", id))
            else {
                val folders = library.sortedPaths()
                if (folders.isEmpty()) { screen.toast("＋からフォルダを作成してください"); return@setItems }
                val selected = library.links.filter { it.feedId == id }.map { it.folderId }.toMutableSet()
                AlertDialog.Builder(screen).setTitle("分類を選択")
                    .setMultiChoiceItems(folders.map { library.path(it) }.toTypedArray(), folders.map { it.id in selected }.toBooleanArray()) { _, index, checked ->
                        if (checked) selected.add(folders[index].id) else selected.remove(folders[index].id)
                    }.setNegativeButton("キャンセル", null).setPositiveButton("保存") { _, _ -> change { organization.classify(id, selected) } }.show()
            }
        }.show()
    }
    private inner class TreeAdapter : RecyclerView.Adapter<TreeHolder>() {
        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): TreeHolder {
            val line = screen.row().apply { layoutParams = RecyclerView.LayoutParams(-1, screen.dp(48)) }
            val expand = screen.label("", 16f, MUTED).apply { gravity = Gravity.CENTER }
            val title = screen.label("", 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val count = screen.label("", 11f, MUTED).apply { gravity = Gravity.RIGHT }
            val menu = screen.iconButton("⋯", "項目の設定") {}
            line.addView(expand, LinearLayout.LayoutParams(screen.dp(32), -1))
            line.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(count, LinearLayout.LayoutParams(screen.dp(38), -2)); line.addView(menu)
            return TreeHolder(line, expand, title, count, menu)
        }
        override fun onBindViewHolder(holder: TreeHolder, position: Int) {
            val item = entries[position]
            holder.line.tag = "tree:${item.kind}:${item.id}:${item.parent ?: 0}"
            holder.line.setPadding(screen.dp(8 + minOf(item.depth, 8) * 12), 0, 0, 0)
            holder.title.text = item.name; holder.title.setTextColor(if (item.kind == "root") MUTED else INK)
            holder.count.text = if (item.count > 0) item.count.toString() else ""
            holder.expand.text = when (item.kind) { "folder" -> if (item.id in closed) "▸" else "▾"; "saved" -> "☆"; "all" -> "≡"; "root" -> "↳"; else -> "·" }
            holder.expand.contentDescription = if (item.kind == "folder") "${item.name}を開閉" else null
            holder.expand.isClickable = item.kind == "folder"
            holder.expand.setOnClickListener { if (item.kind == "folder") { if (!closed.add(item.id)) closed.remove(item.id); rebuild() } }
            holder.menu.visibility = if (item.kind in listOf("folder", "feed")) View.VISIBLE else View.INVISIBLE
            holder.menu.contentDescription = "${item.name}の設定"
            holder.menu.setOnClickListener { if (item.kind == "folder") folderMenu(item.id) else feedMenu(item.id) }
            val selected = scope == item.kind && (scope in listOf("all", "saved") || scopeId == item.id)
            holder.line.setBackgroundColor(if (selected) SURFACE else android.graphics.Color.TRANSPARENT)
            holder.line.setOnClickListener { if (item.kind != "root") select(item.kind, item.id) }
            holder.line.setOnLongClickListener {
                if (item.kind !in listOf("folder", "feed")) false else {
                    dragging = true
                    val started = holder.line.startDragAndDrop(ClipData.newPlainText("Stella RSS", item.name), View.DragShadowBuilder(holder.line), TreeDrag(item.kind == "folder", item.id, item.parent), 0)
                    if (!started) dragging = false
                    started
                }
            }
            holder.line.setOnDragListener { view, event ->
                val payload = event.localState as? TreeDrag
                val target = if (item.kind == "folder") item.id else null
                val valid = payload != null && item.kind in listOf("folder", "root") && (!payload.folder || target !in library.descendants(payload.id))
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> valid
                    DragEvent.ACTION_DRAG_ENTERED -> { if (valid) view.setBackgroundColor(0xFF284A60.toInt()); valid }
                    DragEvent.ACTION_DRAG_EXITED -> { view.setBackgroundColor(if (selected) SURFACE else android.graphics.Color.TRANSPARENT); valid }
                    DragEvent.ACTION_DROP -> {
                        if (valid) change {
                            if (payload.folder) {
                                val folder = library.folders.firstOrNull { it.id == payload.id } ?: error("フォルダが見つかりません")
                                organization.saveFolder(folder.id, folder.name, target)
                            } else organization.moveFeed(payload.id, payload.from, target)
                        }
                        valid
                    }
                    DragEvent.ACTION_DRAG_ENDED -> { dragging = false; list.post { rebuild() }; true }
                    else -> valid
                }
            }
        }
    }
    private class TreeHolder(val line: LinearLayout, val expand: TextView, val title: TextView, val count: TextView, val menu: TextView) : RecyclerView.ViewHolder(line)
}
