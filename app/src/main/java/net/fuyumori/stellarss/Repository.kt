package net.fuyumori.stellarss

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class RssApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository by lazy { Repository(this) }
    override fun onCreate() { super.onCreate(); scope.launch { Updates.schedule(this@RssApp) } }
}
val Context.rss: Repository get() = (applicationContext as RssApp).repository
val Context.appScope: CoroutineScope get() = (applicationContext as RssApp).scope

class Repository(private val context: Context, val db: RssDatabase = RssDatabase.get(context)) {
    val store = db.store()
    val images = Images(context)
    val refreshing = MutableStateFlow(false)
    private val syncLock = Mutex()
    private val imageLock = Mutex()

    suspend fun saveFeed(feed: Feed): Long = syncLock.withLock {
        val url = requireNotNull(webUrl(feed.url)) { "有効なHTTP(S) URLを入力してください" }
        require(store.feeds().none { it.url == url && it.id != feed.id }) { "このフィードは登録済みです" }
        val id = db.withTransaction {
            val old = store.feed(feed.id)
            val changed = old != null && old.url != url
            val value = feed.copy(url = url, name = feed.name.trim().ifBlank { java.net.URI(url).host },
                etag = if (changed) null else old?.etag, modified = if (changed) null else old?.modified,
                contentVersion = if (changed) 0 else old?.contentVersion ?: 0,
                lastChecked = if (changed) 0 else old?.lastChecked ?: 0, error = if (changed) null else old?.error)
            if (feed.id == 0L) store.add(value) else {
                if (changed) store.deleteArticles(feed.id)
                store.update(value); feed.id
            }
        }
        Updates.schedule(context)
        Widgets.updateAll(context)
        Updates.refresh(context)
        id
    }
    suspend fun deleteFeed(id: Long) = syncLock.withLock {
        store.deleteFeed(id)
        Updates.schedule(context)
        Widgets.updateAll(context)
    }
    suspend fun sync(): Boolean = syncLock.withLock {
        refreshing.value = true
        var failed = false
        try {
            for (feed in store.feeds().filter { it.enabled }) {
                currentCoroutineContext().ensureActive()
                try {
                    Http.get(feed.url, feed.etag.takeIf { feed.contentVersion > 0 }, feed.modified.takeIf { feed.contentVersion > 0 }).use { response ->
                        val now = System.currentTimeMillis()
                        if (response.code == 304) {
                            store.fetched(feed.id, feed.url, response.header("ETag") ?: feed.etag,
                                response.header("Last-Modified") ?: feed.modified, now, null)
                        } else {
                            val parsed = FeedParser.parse(Http.bytes(response, 4 * 1024 * 1024).inputStream(), response.request.url.toString())
                            db.withTransaction {
                                for (entry in parsed.entries) {
                                    val id = digest("${feed.id}\n${entry.key}")
                                    val previous = store.article(id)
                                    val date = entry.published ?: previous?.published ?: now
                                    val candidates = entry.images.joinToString("\n")
                                    store.insert(Article(id, feed.id, entry.title, entry.url, entry.summary, date, imageCandidates = candidates))
                                    store.updateContent(id, entry.title, entry.url, entry.summary, date, candidates)
                                    store.updateBody(id, entry.bodyHtml, entry.summaryHtml, entry.bodyTruncated)
                                    if (previous != null && (previous.imageCandidates != candidates || previous.url != entry.url)) store.setImage(id, null, 0)
                                }
                                store.fetched(feed.id, feed.url, response.header("ETag"), response.header("Last-Modified"), now, null)
                                store.contentFetched(feed.id, feed.url)
                                store.trimRead(feed.id)
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    failed = true
                    store.fetched(feed.id, feed.url, feed.etag, feed.modified, System.currentTimeMillis(), friendlyError(e))
                }
            }
            // Show fresh headlines before fetching optional images.
            Widgets.updateAll(context)
            withTimeoutOrNull(60_000) {
                val feeds = store.feeds().filter { it.enabled && it.showImages }
                val recent = feeds.flatMap { store.recent(it.id).take(5) }.sortedByDescending { it.published }.take(10)
                for (article in recent) {
                    currentCoroutineContext().ensureActive()
                    resolveImage(article)
                }
            }
            Widgets.updateAll(context)
            return failed
        } finally { refreshing.value = false }
    }
    suspend fun resolveImage(article: Article): android.graphics.Bitmap? = imageLock.withLock {
        val current = store.article(article.id) ?: return null
        images.cached(current.imageUrl)?.let { return it }
        if (current.imageUrl == null && System.currentTimeMillis() - current.imageChecked < 6 * 3600_000L) return null
        val candidates = current.imageCandidates.lines().filter { it.isNotBlank() }.toMutableList()
        current.imageUrl?.let { if (!candidates.contains(it)) candidates.add(0, it) }
        suspend fun tryImages(urls: List<String>): android.graphics.Bitmap? {
            for (url in urls) {
                currentCoroutineContext().ensureActive()
                try {
                    images.fetch(url)?.let {
                        store.setResolvedImage(current.id, url, System.currentTimeMillis(), current.imageCandidates, current.url)
                        return it
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { /* next source */ }
            }
            return null
        }
        tryImages(candidates)?.let { return it }
        try {
            val og = Http.get(current.url).use { response ->
                val raw = Http.bytes(response, 2 * 1024 * 1024)
                val charset = response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                FeedParser.ogImages(String(raw, charset), response.request.url.toString())
            }
            tryImages(og.take(3))?.let { return it }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* fallback drawable */ }
        store.setResolvedImage(current.id, null, System.currentTimeMillis(), current.imageCandidates, current.url)
        null
    }
    suspend fun markRead(id: String, read: Boolean) {
        store.markRead(id, read)
        Widgets.updateAll(context)
    }
    companion object {
        fun friendlyError(e: Exception): String = when (e) {
            is java.net.UnknownHostException -> "接続できません。ネットワークとURLを確認してください"
            is java.net.SocketTimeoutException -> "接続がタイムアウトしました"
            is javax.net.ssl.SSLException -> "HTTPSの証明書を確認できません"
            else -> e.message?.take(180) ?: "取得に失敗しました"
        }
    }
}

object Updates {
    suspend fun schedule(context: Context) {
        val store = context.rss.store
        val minutes = store.preference("interval")?.toLongOrNull()?.coerceIn(15, 1440) ?: 60L
        val manager = WorkManager.getInstance(context)
        if (store.feeds().none { it.enabled }) { manager.cancelUniqueWork("periodic-feed"); return }
        val work = PeriodicWorkRequestBuilder<RefreshWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork("periodic-feed", ExistingPeriodicWorkPolicy.UPDATE, work)
    }
    fun refresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("manual-feed", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun render(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("render-widgets", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<RenderWorker>().build())
    }
}
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val failed = applicationContext.rss.sync()
            if (failed && runAttemptCount < 2) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}
class RenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Widgets.updateAll(applicationContext); Result.success()
    }
}
