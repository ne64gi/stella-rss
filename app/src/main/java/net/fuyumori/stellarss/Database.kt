package net.fuyumori.stellarss

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "feeds", indices = [Index(value = ["url"], unique = true)])
data class Feed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "", val url: String,
    val enabled: Boolean = true, val inWidgets: Boolean = true,
    val showImages: Boolean = true, val showSummary: Boolean = true,
    val etag: String? = null, val modified: String? = null,
    val lastChecked: Long = 0, val error: String? = null,
    @ColumnInfo(defaultValue = "0") val contentVersion: Int = 0
)
@Entity(tableName = "articles", foreignKeys = [ForeignKey(entity = Feed::class, parentColumns = ["id"],
    childColumns = ["feedId"], onDelete = ForeignKey.CASCADE)], indices = [Index("feedId"), Index("published")])
data class Article(@PrimaryKey val id: String, val feedId: Long, val title: String, val url: String,
                   val summary: String, val published: Long, val read: Boolean = false,
                   val imageCandidates: String = "", val imageUrl: String? = null,
                   val imageChecked: Long = 0,
                   @ColumnInfo(defaultValue = "0") val bookmarked: Boolean = false,
                   @ColumnInfo(defaultValue = "''") val bodyHtml: String = "",
                   @ColumnInfo(defaultValue = "''") val summaryHtml: String = "",
                   @ColumnInfo(defaultValue = "0") val bodyTruncated: Boolean = false)
@Entity(tableName = "widgets")
data class WidgetConfig(@PrimaryKey val id: Int, val feedIds: String = "", val background: Int = 0xFF242629.toInt(),
                        val opacity: Int = 98, val textColor: Int = 0xFFF5F5F5.toInt(), val fontSp: Int = 15,
                        val images: Boolean = true, val unreadOnly: Boolean = false,
                        @ColumnInfo(defaultValue = "16") val cornerDp: Int = 16,
                        val folderId: Long? = null) {
    fun selectedFeeds() = feedIds.split(",").mapNotNull { it.toLongOrNull() }.toSet()
}
@Entity(tableName = "preferences")
data class Preference(@PrimaryKey val key: String, val value: String)
data class ArticleRow(@Embedded val article: Article, val feedName: String, val showImages: Boolean, val showSummary: Boolean)

@Entity(tableName = "folders", foreignKeys = [ForeignKey(entity = Folder::class, parentColumns = ["id"],
    childColumns = ["parentId"], onDelete = ForeignKey.CASCADE)], indices = [Index("parentId")])
data class Folder(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val parentId: Long? = null)
@Entity(tableName = "folder_feed", primaryKeys = ["folderId", "feedId"], foreignKeys = [
    ForeignKey(entity = Folder::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Feed::class, parentColumns = ["id"], childColumns = ["feedId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("feedId")])
data class FolderFeed(val folderId: Long, val feedId: Long)
data class FeedUnread(val feedId: Long, val count: Int)

// Lists/widgets do not materialize hundreds of full HTML bodies; only the open article loads them.
private const val LIST_COLUMNS = "articles.id, articles.feedId, articles.title, articles.url, articles.summary, articles.published, articles.read, articles.imageCandidates, articles.imageUrl, articles.imageChecked, articles.bookmarked, '' AS bodyHtml, '' AS summaryHtml, 0 AS bodyTruncated"

@Dao
interface Store {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, id") fun watchFolders(): Flow<List<Folder>>
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, id") suspend fun folders(): List<Folder>
    @Insert suspend fun add(folder: Folder): Long
    @Update suspend fun update(folder: Folder)
    @Query("DELETE FROM folders WHERE id=:id") suspend fun deleteFolder(id: Long)
    @Query("SELECT * FROM folder_feed") fun watchMemberships(): Flow<List<FolderFeed>>
    @Query("SELECT * FROM folder_feed") suspend fun memberships(): List<FolderFeed>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(link: FolderFeed)
    @Query("DELETE FROM folder_feed WHERE feedId=:id") suspend fun clearMemberships(id: Long)
    @Query("DELETE FROM folder_feed WHERE feedId=:feedId AND folderId=:folderId") suspend fun unlink(feedId: Long, folderId: Long)
    @Query("SELECT feedId, COUNT(*) AS count FROM articles JOIN feeds ON feeds.id=articles.feedId WHERE feeds.enabled=1 AND read=0 GROUP BY feedId")
    fun watchUnread(): Flow<List<FeedUnread>>
    @Query("SELECT COUNT(*) FROM articles JOIN feeds ON feeds.id=articles.feedId WHERE feeds.enabled=1 AND bookmarked=1")
    fun watchSavedCount(): Flow<Int>
    @Query("UPDATE articles SET bookmarked=:saved WHERE id=:id") suspend fun bookmark(id: String, saved: Boolean)
    @Query("SELECT " + LIST_COLUMNS + ", feeds.name AS feedName, feeds.showImages, feeds.showSummary FROM articles JOIN feeds ON feeds.id=articles.feedId WHERE feeds.enabled=1 AND (:allFeeds=1 OR feedId IN (:ids)) AND (:unread=0 OR read=0) AND (:saved=0 OR bookmarked=1) ORDER BY published DESC, articles.id LIMIT 1000")
    fun watchSelection(ids: List<Long>, allFeeds: Boolean, unread: Boolean, saved: Boolean): Flow<List<ArticleRow>>
    @Query("SELECT * FROM feeds ORDER BY id") fun watchFeeds(): Flow<List<Feed>>
    @Query("SELECT * FROM feeds ORDER BY id") suspend fun feeds(): List<Feed>
    @Query("SELECT * FROM feeds WHERE id=:id") suspend fun feed(id: Long): Feed?
    @Insert suspend fun add(feed: Feed): Long
    @Update suspend fun update(feed: Feed)
    @Query("DELETE FROM feeds WHERE id=:id") suspend fun deleteFeed(id: Long)
    @Query("DELETE FROM articles WHERE feedId=:id") suspend fun deleteArticles(id: Long)
    @Query("SELECT * FROM articles WHERE id=:id") suspend fun article(id: String): Article?
    @Query("SELECT articles.*, feeds.name AS feedName, feeds.showImages, feeds.showSummary FROM articles JOIN feeds ON feeds.id=articles.feedId WHERE articles.id=:id")
    suspend fun articleRow(id: String): ArticleRow?
    @Query("UPDATE articles SET bodyHtml=:body,summaryHtml=:summary,bodyTruncated=:truncated WHERE id=:id")
    suspend fun updateBody(id: String, body: String, summary: String, truncated: Boolean)
    @Query("UPDATE feeds SET contentVersion=1 WHERE id=:id AND url=:url")
    suspend fun contentFetched(id: Long, url: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(article: Article): Long
    // Never overwrite read/image state during a network refresh.
    @Query("UPDATE articles SET title=:title,url=:url,summary=:summary,published=:published,imageCandidates=:candidates WHERE id=:id")
    suspend fun updateContent(id: String, title: String, url: String, summary: String, published: Long, candidates: String)
    @Query("UPDATE articles SET read=:read WHERE id=:id") suspend fun markRead(id: String, read: Boolean)
    @Query("UPDATE articles SET imageUrl=:url,imageChecked=:checked WHERE id=:id") suspend fun setImage(id: String, url: String?, checked: Long)
    @Query("UPDATE articles SET imageUrl=:url,imageChecked=:checked WHERE id=:id AND imageCandidates=:candidates AND url=:articleUrl")
    suspend fun setResolvedImage(id: String, url: String?, checked: Long, candidates: String, articleUrl: String): Int
    @Query("UPDATE feeds SET etag=:etag,modified=:modified,lastChecked=:checked,error=:error WHERE id=:id AND url=:url")
    suspend fun fetched(id: Long, url: String, etag: String?, modified: String?, checked: Long, error: String?)
    @Query("SELECT " + LIST_COLUMNS + ", feeds.name AS feedName, feeds.showImages, feeds.showSummary FROM articles JOIN feeds ON feeds.id=articles.feedId WHERE feeds.enabled=1 ORDER BY published DESC, articles.id LIMIT 1000")
    fun watchArticles(): Flow<List<ArticleRow>>
    @Query("SELECT " + LIST_COLUMNS + ", feeds.name AS feedName, feeds.showImages, feeds.showSummary FROM articles JOIN feeds ON feeds.id=articles.feedId WHERE feeds.enabled=1 AND feeds.inWidgets=1 ORDER BY published DESC, articles.id LIMIT 1000")
    suspend fun widgetArticles(): List<ArticleRow>
    @Query("SELECT " + LIST_COLUMNS + ", feeds.name AS feedName, feeds.showImages, feeds.showSummary FROM articles JOIN feeds ON feeds.id=articles.feedId WHERE feeds.enabled=1 AND feeds.inWidgets=1 AND (:allFeeds=1 OR feedId IN (:ids)) AND (:unread=0 OR read=0) ORDER BY published DESC, articles.id LIMIT 5")
    suspend fun widgetArticlesFor(ids: List<Long>, allFeeds: Boolean, unread: Boolean): List<ArticleRow>
    @Query("SELECT * FROM articles WHERE feedId=:id ORDER BY published DESC LIMIT 60") suspend fun recent(id: Long): List<Article>
    @Query("DELETE FROM articles WHERE feedId=:id AND read=1 AND bookmarked=0 AND id NOT IN (SELECT id FROM articles WHERE feedId=:id ORDER BY published DESC LIMIT 500)")
    suspend fun trimRead(id: Long)
    @Query("SELECT * FROM widgets WHERE id=:id") suspend fun widget(id: Int): WidgetConfig?
    @Query("SELECT * FROM widgets") suspend fun widgets(): List<WidgetConfig>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(config: WidgetConfig)
    @Query("DELETE FROM widgets WHERE id=:id") suspend fun deleteWidget(id: Int)
    @Query("SELECT value FROM preferences WHERE `key`=:key") suspend fun preference(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(pref: Preference)
    @Query("DELETE FROM preferences WHERE `key`=:key") suspend fun deletePreference(key: String)
}
@Database(entities = [Feed::class, Article::class, WidgetConfig::class, Preference::class, Folder::class, FolderFeed::class], version = 4, exportSchema = true)
abstract class RssDatabase : RoomDatabase() {
    abstract fun store(): Store
    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN bodyHtml TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE articles ADD COLUMN summaryHtml TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE articles ADD COLUMN bodyTruncated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE feeds ADD COLUMN contentVersion INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN bookmarked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE widgets ADD COLUMN folderId INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS folders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, parentId INTEGER, FOREIGN KEY(parentId) REFERENCES folders(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_parentId ON folders(parentId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS folder_feed (folderId INTEGER NOT NULL, feedId INTEGER NOT NULL, PRIMARY KEY(folderId,feedId), FOREIGN KEY(folderId) REFERENCES folders(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(feedId) REFERENCES feeds(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folder_feed_feedId ON folder_feed(feedId)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE widgets ADD COLUMN cornerDp INTEGER NOT NULL DEFAULT 16")
                // Update only the untouched factory appearance. User palettes, filters and sizes survive.
                db.execSQL("UPDATE widgets SET background=${0xFF242629.toInt()}, opacity=98, textColor=${0xFFF5F5F5.toInt()}, fontSp=15 " +
                    "WHERE background=${0xFF101C2D.toInt()} AND opacity=92 AND textColor=${0xFFF4F7FC.toInt()} AND fontSp=16")
            }
        }
        @Volatile private var instance: RssDatabase? = null
        fun get(context: Context): RssDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RssDatabase::class.java, "stellarss.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
