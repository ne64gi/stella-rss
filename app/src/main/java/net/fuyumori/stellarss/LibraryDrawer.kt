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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

private data class TreeEntry(val kind: String, val id: Long = 0, val parent: Long? = null, val depth: Int = 0,
                             val name: String, val count: Int = 0)
private data class TreeDrag(val folder: Boolean, val id: Long, val from: Long?)
class LibraryDrawer(private val screen: MainActivity, private val select: (String, Long) -> Unit,
                    private val addFeed: (Long?) -> Unit, private val importOpml: () -> Unit, private val exportOpml: () -> Unit) {
    val view = screen.column().apply { setBackgroundColor(0xFF101B2A.toInt()); elevation = screen.dp(8).toFloat() }
    private val organization = Organization(screen.rss.db)
    private var library = Library()
    private var savedCount = 0
    private var scope = "all"
    private var scopeId = 0L
    private val closed = mutableSetOf<Long>()
    private var entries = emptyList<TreeEntry>()
    private var dragging = false
    private var query = ""
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
        val header = screen.row().apply { setPadding(screen.dp(16), screen.dp(4), screen.dp(4), 0) }
        header.addView(screen.label("フィード", 19f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(screen.iconButton("＋", "フィード・フォルダを追加") {
            val folder = scopeId.takeIf { scope == "folder" }
            AlertDialog.Builder(screen).setTitle(if (folder == null) "追加" else library.folders.firstOrNull { it.id == folder }?.let { library.path(it) } ?: "追加")
                .setItems(arrayOf(if (folder == null) "フィードを追加" else "このフォルダにフィードを追加", "フォルダを追加", "OPMLを読み込む", "OPMLを書き出す")) { _, which ->
                    when (which) { 0 -> addFeed(folder); 1 -> editFolder(null, folder); 2 -> importOpml(); 3 -> exportOpml() }
                }.show()
        }.apply { id = R.id.library_add })
        view.addView(header)
        view.addView(screen.field("フィードを検索").apply {
            id = R.id.library_search; textSize = 14f; setSingleLine(); setPadding(screen.dp(12), 0, screen.dp(12), 0)
            rounded(SURFACE, 8f); doAfterTextChanged { query = it.toString().trim(); if (!dragging) rebuild() }
        }, LinearLayout.LayoutParams(-1, screen.dp(44)).apply {
            setMargins(screen.dp(12), screen.dp(4), screen.dp(12), screen.dp(8))
        })
        view.addView(screen.label("長押しで移動 · ⋮ で編集・フォルダに追加", 10f, MUTED).apply {
            setPadding(screen.dp(16), 0, screen.dp(12), screen.dp(8))
        })
        view.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
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
        val searching = query.isNotEmpty()
        val matchingFeeds = library.feeds.filter { it.name.contains(query, true) }.map { it.id }.toSet()
        val matchingFolders = library.folders.filter { it.name.contains(query, true) }.flatMap { library.descendants(it.id) }.toSet()
        val visibleFolders = matchingFolders.toMutableSet()
        library.links.filter { it.feedId in matchingFeeds }.forEach { visibleFolders.add(it.folderId) }
        val parents = library.folders.associate { it.id to it.parentId }
        visibleFolders.toList().forEach { id ->
            var parent = parents[id]
            val seen = mutableSetOf<Long>()
            while (parent != null && seen.add(parent)) { visibleFolders.add(parent); parent = parents[parent] }
        }
        fun folder(item: Folder, depth: Int) {
            if (!visited.add(item.id) || (searching && item.id !in visibleFolders)) return
            rows.add(TreeEntry("folder", item.id, item.parentId, depth, item.name, library.count(library.feedIds(item.id))))
            if (!searching && item.id in closed) return
            byParent[item.id].orEmpty().forEach { folder(it, depth + 1) }
            memberships[item.id].orEmpty().mapNotNull { feedMap[it.feedId] }.sortedBy { it.name.lowercase() }.forEach {
                if (!searching || item.id in matchingFolders || it.id in matchingFeeds)
                    rows.add(TreeEntry("feed", it.id, item.id, depth + 1, it.name, library.count(setOf(it.id))))
            }
        }
        byParent[null].orEmpty().forEach { folder(it, 0) }
        val filed = library.links.map { it.feedId }.toSet()
        val unfiled = library.feeds.filter { it.id !in filed && (!searching || it.id in matchingFeeds) }
        if (!searching || unfiled.isNotEmpty()) rows.add(TreeEntry("root", name = "未分類", count = 0))
        unfiled.forEach { rows.add(TreeEntry("feed", it.id, null, 0, it.name, library.count(setOf(it.id)))) }
        if (searching && rows.size == 2) rows.add(TreeEntry("empty", name = "該当するフィードはありません"))
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
        AlertDialog.Builder(screen).setTitle(library.path(item)).setItems(arrayOf("このフォルダにフィードを追加", "子フォルダを追加", "名前を変更", "フォルダを移動", "フォルダを削除")) { _, which ->
            when (which) {
                0 -> addFeed(id); 1 -> editFolder(null, id); 2 -> editFolder(item, null)
                3 -> {
                    val targets = library.sortedPaths().filter { it.id !in library.descendants(id) }
                    AlertDialog.Builder(screen).setTitle("移動先").setItems((listOf("最上位") + targets.map { library.path(it) }).toTypedArray()) { _, pos ->
                        change { organization.saveFolder(id, item.name, targets.getOrNull(pos - 1)?.id) }
                    }.show()
                }
                4 -> AlertDialog.Builder(screen).setTitle("${item.name}を削除")
                    .setMessage("配下のフォルダと分類を削除します。購読フィードと記事は残ります。このフォルダを指定したウィジェットは空になります。")
                    .setNegativeButton("キャンセル", null).setPositiveButton("削除") { _, _ -> change { organization.deleteFolder(id) } }.show()
            }
        }.show()
    }
    private fun feedMenu(id: Long, from: Long?) {
        val feed = library.feeds.firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(screen).setTitle(feed.name)
            .setItems(arrayOf("編集・表示設定", "フォルダへ移動", "分類を選択（複数可）", "削除")) { _, which ->
                when (which) {
                    0 -> screen.startActivity(Intent(screen, FeedActivity::class.java).putExtra("edit", id))
                    1 -> {
                        val folders = library.sortedPaths()
                        AlertDialog.Builder(screen).setTitle("移動先（ほかの分類は維持）")
                            .setItems((listOf("未分類（すべての分類を外す）") + folders.map { library.path(it) }).toTypedArray()) { _, index ->
                                change { organization.moveFeed(id, from, folders.getOrNull(index - 1)?.id) }
                            }.show()
                    }
                    2 -> {
                        val folders = library.sortedPaths()
                        if (folders.isEmpty()) { screen.toast("＋からフォルダを作成してください"); return@setItems }
                        val selected = library.links.filter { it.feedId == id }.map { it.folderId }.toMutableSet()
                        AlertDialog.Builder(screen).setTitle("分類を選択")
                            .setMultiChoiceItems(folders.map { library.path(it) }.toTypedArray(), folders.map { it.id in selected }.toBooleanArray()) { _, index, checked ->
                                if (checked) selected.add(folders[index].id) else selected.remove(folders[index].id)
                            }.setNegativeButton("キャンセル", null).setPositiveButton("保存") { _, _ -> change { organization.classify(id, selected) } }.show()
                    }
                    3 -> AlertDialog.Builder(screen).setTitle("${feed.name}を削除")
                        .setMessage("この購読と保存記事を削除します。すべてのフォルダから外れます。")
                        .setNegativeButton("キャンセル", null).setPositiveButton("削除") { _, _ ->
                            if (screen.rss.refreshing.value) screen.toast("更新完了後に削除します")
                            // A confirmed deletion must survive closing or recreating the reader.
                            screen.appScope.launch {
                                try { screen.rss.deleteFeed(id) }
                                catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    withContext(Dispatchers.Main) { if (!screen.isDestroyed) screen.toast(e.message ?: "削除できません") }
                                }
                            }
                        }.show()
                }
            }.show()
    }
    private inner class TreeAdapter : RecyclerView.Adapter<TreeHolder>() {
        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): TreeHolder {
            val line = screen.row().apply { layoutParams = RecyclerView.LayoutParams(-1, screen.dp(48)) }
            val expand = screen.label("", 14f, MUTED).apply { gravity = Gravity.CENTER }
            val title = screen.label("", 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val count = screen.label("", 11f, MUTED).apply { gravity = Gravity.RIGHT; setPadding(screen.dp(6), 0, screen.dp(2), 0) }
            val menu = screen.iconButton("⋮", "項目の設定") {}.apply { textSize = 20f; layoutParams = LinearLayout.LayoutParams(screen.dp(40), -1) }
            val icon = ImageView(screen).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            line.addView(expand, LinearLayout.LayoutParams(screen.dp(24), -1))
            line.addView(icon, LinearLayout.LayoutParams(screen.dp(18), screen.dp(18)).apply { rightMargin = screen.dp(8) })
            line.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(count, LinearLayout.LayoutParams(-2, -2)); line.addView(menu)
            return TreeHolder(line, expand, icon, title, count, menu)
        }
        override fun onBindViewHolder(holder: TreeHolder, position: Int) {
            val item = entries[position]
            holder.line.tag = "tree:${item.kind}:${item.id}:${item.parent ?: 0}"
            holder.line.setPadding(screen.dp(8 + minOf(item.depth, 8) * 12), 0, 0, 0)
            holder.title.text = item.name; holder.title.setTextColor(if (item.kind in listOf("root", "empty")) MUTED else INK)
            holder.title.setTypeface(null, if (item.kind == "folder") Typeface.BOLD else Typeface.NORMAL)
            holder.line.tooltipText = item.name
            holder.count.text = if (item.count > 0) item.count.toString() else ""
            holder.icon.setImageResource(when (item.kind) {
                "folder", "root" -> R.drawable.ic_library_folder; "saved" -> R.drawable.ic_library_saved
                "all" -> R.drawable.ic_library_articles; else -> R.drawable.ic_library_rss
            })
            holder.icon.imageTintList = android.content.res.ColorStateList.valueOf(if (item.kind in listOf("folder", "feed")) ACCENT else MUTED)
            holder.icon.visibility = if (item.kind == "empty") View.GONE else View.VISIBLE
            holder.expand.visibility = if (item.kind == "folder") View.VISIBLE else View.GONE
            holder.expand.text = if (query.isBlank() && item.id in closed) "▸" else "▾"
            holder.expand.contentDescription = "${item.name}を開閉"
            holder.expand.isEnabled = query.isBlank()
            holder.expand.setOnClickListener { if (item.kind == "folder") { if (!closed.add(item.id)) closed.remove(item.id); rebuild() } }
            holder.menu.visibility = if (item.kind in listOf("folder", "feed")) View.VISIBLE else View.GONE
            holder.menu.contentDescription = "${item.name}の設定"
            holder.menu.setOnClickListener { if (item.kind == "folder") folderMenu(item.id) else feedMenu(item.id, item.parent) }
            val selected = scope == item.kind && (scope in listOf("all", "saved") || scopeId == item.id)
            holder.line.setBackgroundColor(if (selected) SURFACE else android.graphics.Color.TRANSPARENT)
            holder.line.setOnClickListener { if (item.kind !in listOf("root", "empty")) select(item.kind, item.id) }
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
    private class TreeHolder(val line: LinearLayout, val expand: TextView, val icon: ImageView, val title: TextView, val count: TextView, val menu: TextView) : RecyclerView.ViewHolder(line)
}
