package net.fuyumori.stellarss

import androidx.room.withTransaction

/** One feed/article store, many classifications. Descendant unions always deduplicate feed IDs. */
data class Library(val feeds: List<Feed> = emptyList(), val folders: List<Folder> = emptyList(),
                   val links: List<FolderFeed> = emptyList(), val unread: List<FeedUnread> = emptyList(),
                   val containers: List<NewsContainer> = listOf(NewsContainer(1, "デフォルト")),
                   val saved: List<FeedUnread> = emptyList()) {
    fun inContainer(id: Long?): Library {
        if (id == null) return this
        val selectedFeeds = feeds.filter { it.containerId == id }
        val selectedFolders = folders.filter { it.containerId == id }
        val ids = selectedFeeds.map { it.id }.toSet()
        val folderIds = selectedFolders.map { it.id }.toSet()
        return copy(feeds = selectedFeeds, folders = selectedFolders,
            links = links.filter { it.feedId in ids && it.folderId in folderIds },
            unread = unread.filter { it.feedId in ids }, saved = saved.filter { it.feedId in ids })
    }
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
    suspend fun library() = db.withTransaction {
        ReadingSpaces(db).ensureDefault()
        Library(store.feeds(), store.folders(), store.memberships(), containers = store.containers())
    }
    suspend fun saveFolder(id: Long = 0, name: String, parent: Long? = null, containerId: Long? = null): Long = db.withTransaction {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 120) { "フォルダ名は1〜120文字で入力してください" }
        val tree = library()
        val old = tree.folders.find { it.id == id }
        val parentFolder = tree.folders.find { it.id == parent }
        val state = ReadingSpaces(db).state()
        val container = containerId ?: old?.containerId ?: parentFolder?.containerId ?: if (state.enabled) state.selected else 1L
        require(tree.containers.any { it.id == container }) { "コンテナが見つかりません" }
        require(old == null || old.containerId == container) { "別コンテナへの移動を使用してください" }
        require(parentFolder == null || parentFolder.containerId == container) { "同じコンテナのフォルダを選んでください" }
        require(parent == null || tree.folders.any { it.id == parent }) { "移動先が見つかりません" }
        require(id == 0L || tree.folders.any { it.id == id }) { "フォルダが見つかりません" }
        require(parent == null || parent !in tree.descendants(id)) { "自分自身や配下のフォルダへは移動できません" }
        require(tree.folders.none { it.id != id && it.containerId == container && it.parentId == parent && it.name.equals(title, true) }) { "同じ場所に同名のフォルダがあります" }
        var level = 1
        var ancestor = parent
        while (ancestor != null) { level++; ancestor = tree.folders.first { it.id == ancestor }.parentId }
        fun height(node: Long): Int = 1 + (tree.folders.filter { it.parentId == node }.maxOfOrNull { height(it.id) } ?: 0)
        require(level + (if (id == 0L) 0 else height(id) - 1) <= 32) { "フォルダは最大32階層です" }
        val value = Folder(id, title, parent, container)
        if (id == 0L) store.add(value) else { store.update(value); id }
    }
    suspend fun deleteFolder(id: Long) = store.deleteFolder(id) // FK removes only folders/classifications, never feeds/articles.
    suspend fun classify(feed: Long, folders: Set<Long>) = db.withTransaction {
        val source = requireNotNull(store.feed(feed)) { "フィードが見つかりません" }
        require(store.folders().filter { it.containerId == source.containerId }.map { it.id }.containsAll(folders)) { "同じコンテナのフォルダを選んでください" }
        store.clearMemberships(feed)
        folders.forEach { store.add(FolderFeed(it, feed)) }
    }
    suspend fun moveFeed(feed: Long, from: Long?, to: Long?) = db.withTransaction {
        val source = requireNotNull(store.feed(feed)) { "フィードが見つかりません" }
        require(to == null || store.folders().any { it.id == to && it.containerId == source.containerId }) { "同じコンテナのフォルダを選んでください" }
        if (from != null) store.unlink(feed, from)
        // Dropping on the root means unfiled. Other memberships are retained when moving between folders.
        if (to == null) store.clearMemberships(feed) else store.add(FolderFeed(to, feed))
    }
    suspend fun widgetRows(config: WidgetConfig): List<ArticleRow> {
        if (config.folderId == null && config.selectedFeeds().isEmpty())
            return store.widgetArticlesFor(emptyList(), true, config.unreadOnly, config.containerId)
        val tree = library().inContainer(config.containerId)
        val ids = config.folderId?.let { tree.feedIds(it) }
            ?: config.selectedFeeds().takeIf { it.isNotEmpty() }?.intersect(tree.feeds.map { it.id }.toSet())
            ?: tree.feeds.map { it.id }.toSet()
        return store.widgetArticlesFor(ids.toList(), false, config.unreadOnly, config.containerId)
    }
    suspend fun importOpml(items: List<Outline>, containerId: Long = 1): ImportResult = db.withTransaction {
        ReadingSpaces(db).ensureDefault()
        require(store.containers().any { it.id == containerId }) { "コンテナが見つかりません" }
        var addedFeeds = 0; var addedFolders = 0
        val feeds = store.feeds().associateBy { it.url }.toMutableMap()
        val folders = store.folders().toMutableList()
        suspend fun add(items: List<Outline>, parent: Long?) {
            for (item in items) {
                if (item.url != null) {
                    val feed = feeds[item.url] ?: Feed(name = item.title, url = item.url, containerId = containerId).let {
                        it.copy(id = store.add(it)).also { value -> feeds[item.url] = value; addedFeeds++ }
                    }
                    require(feed.containerId == containerId) { "別のコンテナに登録済みのフィードがあります。移動してから読み込んでください" }
                    if (parent != null) store.add(FolderFeed(parent, feed.id))
                    add(item.children, parent)
                } else {
                    val folder = folders.firstOrNull { it.containerId == containerId && it.parentId == parent && it.name.equals(item.title, true) }
                        ?: Folder(name = item.title, parentId = parent, containerId = containerId).let {
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
