package net.fuyumori.stellarss

import androidx.room.withTransaction
import kotlinx.coroutines.flow.combine

/** Owns the optional browsing boundary. Article identity and read/save state never move. */
class ReadingSpaces(private val db: RssDatabase) {
    private val store get() = db.store()
    data class State(val enabled: Boolean = false, val selected: Long = 1)
    fun watch() = combine(store.watchPreference(ENABLED), store.watchPreference(SELECTED)) { on, id ->
        State(on == "true", id?.toLongOrNull()?.takeIf { it > 0 } ?: 1)
    }
    suspend fun state() = State(store.preference(ENABLED) == "true", store.preference(SELECTED)?.toLongOrNull()?.takeIf { it > 0 } ?: 1)
    suspend fun ensureDefault() { store.add(NewsContainer(1, "デフォルト")) }
    suspend fun setEnabled(value: Boolean) = db.withTransaction {
        ensureDefault(); store.save(Preference(ENABLED, value.toString()))
    }
    suspend fun select(id: Long) = db.withTransaction {
        ensureDefault(); require(store.containers().any { it.id == id }) { "コンテナが見つかりません" }
        store.save(Preference(SELECTED, id.toString()))
    }
    suspend fun save(id: Long = 0, name: String, target: String = ""): Long = db.withTransaction {
        ensureDefault()
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 120) { "名前は1〜120文字で入力してください" }
        require(target in listOf("", "ja")) { "翻訳先が正しくありません" }
        val all = store.containers()
        require(id == 0L || all.any { it.id == id }) { "コンテナが見つかりません" }
        require(all.none { it.id != id && it.name.equals(title, true) }) { "同じ名前のコンテナがあります" }
        val value = NewsContainer(id, title, target)
        if (id == 0L) store.add(value) else { store.update(value); id }
    }
    suspend fun deleteEmpty(id: Long) = db.withTransaction {
        require(id != 1L) { "デフォルトは削除できません" }
        require(store.feeds().none { it.containerId == id } && store.folders().none { it.containerId == id }) { "中のフィードとフォルダを移動してから削除してください" }
        store.deleteContainer(id)
        if (state().selected == id) store.save(Preference(SELECTED, "1"))
    }
    suspend fun moveFeed(id: Long, target: Long) = db.withTransaction {
        ensureDefault()
        require(store.containers().any { it.id == target }) { "移動先がありません" }
        val feed = requireNotNull(store.feed(id)) { "フィードが見つかりません" }
        if (feed.containerId != target) {
            store.clearMemberships(id)
            store.moveFeedContainer(id, target)
        }
    }
    suspend fun moveFolder(id: Long, target: Long) = db.withTransaction {
        ensureDefault()
        require(store.containers().any { it.id == target }) { "移動先がありません" }
        val tree = Organization(db).library()
        val folder = requireNotNull(tree.folders.find { it.id == id }) { "フォルダが見つかりません" }
        if (folder.containerId != target) {
            require(tree.folders.none { it.containerId == target && it.parentId == null && it.name.equals(folder.name, true) }) { "移動先に同名のフォルダがあります" }
            val branch = tree.descendants(id)
            val feeds = tree.feedIds(id)
            tree.folders.filter { it.id in branch }.forEach { store.update(it.copy(containerId = target, parentId = if (it.id == id) null else it.parentId)) }
            feeds.forEach { feed ->
                store.moveFeedContainer(feed, target)
                tree.links.filter { it.feedId == feed && it.folderId !in branch }.forEach { store.unlink(feed, it.folderId) }
            }
        }
    }
    suspend fun translationTarget(feedId: Long): String {
        val feed = store.feed(feedId) ?: return ""
        // Manual translation is always available. An explicit feed override also works without containers.
        feed.translationTarget?.let { return it }
        if (!state().enabled) return ""
        return store.containers().find { it.id == feed.containerId }?.translationTarget.orEmpty()
    }
    companion object {
        fun automaticFeedIds(feeds: List<Feed>, containers: List<NewsContainer>, enabled: Boolean): Set<Long> {
            val targets = containers.associate { it.id to it.translationTarget }
            return feeds.filter { (it.translationTarget ?: if (enabled) targets[it.containerId].orEmpty() else "") == "ja" }
                .map { it.id }.toSet()
        }
        const val ENABLED = "extension_containers"
        const val SELECTED = "selected_container"
    }
}
