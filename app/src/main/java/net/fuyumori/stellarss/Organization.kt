package net.fuyumori.stellarss

import androidx.room.withTransaction

/** One feed/article store, many classifications. Descendant unions always deduplicate feed IDs. */
data class Library(val feeds: List<Feed> = emptyList(), val folders: List<Folder> = emptyList(),
                   val links: List<FolderFeed> = emptyList(), val unread: List<FeedUnread> = emptyList()) {
    fun descendants(id: Long): Set<Long> {
        if (folders.none { it.id == id }) return emptySet()
        val result = mutableSetOf<Long>()
        val pending = ArrayDeque<Long>().apply { add(id) }
        val children = folders.groupBy { it.parentId }
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (result.add(next)) children[next].orEmpty().forEach { pending.add(it.id) }
        }
        return result
    }
    fun feedIds(id: Long): Set<Long> {
        val branch = descendants(id)
        return links.filter { it.folderId in branch }.map { it.feedId }.toSet()
    }
    fun count(ids: Set<Long>? = null) = unread.filter { ids == null || it.feedId in ids }.sumOf { it.count }
    fun path(folder: Folder): String {
        val parts = mutableListOf(folder.name)
        val seen = mutableSetOf(folder.id)
        var parent = folder.parentId
        val byId = folders.associateBy { it.id }
        while (parent != null && seen.add(parent)) {
            val item = byId[parent] ?: break
            parts.add(item.name); parent = item.parentId
        }
        return parts.asReversed().joinToString(" / ")
    }
    fun sortedPaths() = folders.sortedBy { path(it).lowercase() }
}

class Organization(private val db: RssDatabase) {
    private val store get() = db.store()
    suspend fun library() = db.withTransaction { Library(store.feeds(), store.folders(), store.memberships()) }
    suspend fun saveFolder(id: Long = 0, name: String, parent: Long? = null): Long = db.withTransaction {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 120) { "フォルダ名は1〜120文字で入力してください" }
        val tree = library()
        require(parent == null || tree.folders.any { it.id == parent }) { "移動先が見つかりません" }
        require(id == 0L || tree.folders.any { it.id == id }) { "フォルダが見つかりません" }
        require(parent == null || parent !in tree.descendants(id)) { "自分自身や配下のフォルダへは移動できません" }
        require(tree.folders.none { it.id != id && it.parentId == parent && it.name.equals(title, true) }) { "同じ場所に同名のフォルダがあります" }
        var level = 1
        var ancestor = parent
        while (ancestor != null) { level++; ancestor = tree.folders.first { it.id == ancestor }.parentId }
        fun height(node: Long): Int = 1 + (tree.folders.filter { it.parentId == node }.maxOfOrNull { height(it.id) } ?: 0)
        require(level + (if (id == 0L) 0 else height(id) - 1) <= 32) { "フォルダは最大32階層です" }
        val value = Folder(id, title, parent)
        if (id == 0L) store.add(value) else { store.update(value); id }
    }
    suspend fun deleteFolder(id: Long) = store.deleteFolder(id) // FK removes only folders/classifications, never feeds/articles.
    suspend fun classify(feed: Long, folders: Set<Long>) = db.withTransaction {
        require(store.feed(feed) != null) { "フィードが見つかりません" }
        require(store.folders().map { it.id }.containsAll(folders)) { "フォルダが見つかりません" }
        store.clearMemberships(feed)
        folders.forEach { store.add(FolderFeed(it, feed)) }
    }
    suspend fun moveFeed(feed: Long, from: Long?, to: Long?) = db.withTransaction {
        require(store.feed(feed) != null) { "フィードが見つかりません" }
        require(to == null || store.folders().any { it.id == to }) { "移動先が見つかりません" }
        if (from != null) store.unlink(feed, from)
        // Dropping on the root means unfiled. Other memberships are retained when moving between folders.
        if (to == null) store.clearMemberships(feed) else store.add(FolderFeed(to, feed))
    }
    suspend fun widgetRows(config: WidgetConfig): List<ArticleRow> {
        val ids = config.folderId?.let { library().feedIds(it) } ?: config.selectedFeeds()
        return store.widgetArticlesFor(ids.toList(), config.folderId == null && ids.isEmpty(), config.unreadOnly)
    }
    suspend fun importOpml(items: List<Outline>): ImportResult = db.withTransaction {
        var addedFeeds = 0; var addedFolders = 0
        val feeds = store.feeds().associateBy { it.url }.toMutableMap()
        val folders = store.folders().toMutableList()
        suspend fun add(items: List<Outline>, parent: Long?) {
            for (item in items) {
                if (item.url != null) {
                    val feed = feeds[item.url] ?: Feed(name = item.title, url = item.url).let {
                        it.copy(id = store.add(it)).also { value -> feeds[item.url] = value; addedFeeds++ }
                    }
                    if (parent != null) store.add(FolderFeed(parent, feed.id))
                    add(item.children, parent)
                } else {
                    val folder = folders.firstOrNull { it.parentId == parent && it.name.equals(item.title, true) }
                        ?: Folder(name = item.title, parentId = parent).let {
                            it.copy(id = store.add(it)).also { value -> folders.add(value); addedFolders++ }
                        }
                    add(item.children, folder.id)
                }
            }
        }
        add(items, null)
        ImportResult(addedFeeds, addedFolders)
    }
}
data class ImportResult(val feeds: Int, val folders: Int)
