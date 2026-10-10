package net.fuyumori.stellarss

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WidgetMigrationTest {
    @Test fun schemaOnePreservesSubscriptionsReadsCustomAppearanceAndAddsCorners() = verifyMigration(1)
    @Test fun schemaTwoPreservesDataAndAddsEmptyClassifications() = verifyMigration(2)
    @Test fun schemaThreeKeepsFolderMembershipBookmarkAndAllSettings() = verifyMigration(3)
    private fun verifyMigration(version: Int) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "widget-migration-fixture.db"
        context.deleteDatabase(name)
        val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream("net.fuyumori.stellarss.RssDatabase/$version.json")!!.bufferedReader().readText()).getJSONObject("database")
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            legacy.execSQL("INSERT INTO feeds VALUES (1,'Kept','https://example.com/rss',1,1,1,1,'etag','date',123,NULL)")
            legacy.execSQL("INSERT INTO articles (id,feedId,title,url,summary,published,read,imageCandidates,imageUrl,imageChecked) VALUES ('kept',1,'Article','https://example.com/a','',123,1,'',NULL,0)")
            legacy.execSQL("INSERT INTO widgets (id,feedIds,background,opacity,textColor,fontSp,images,unreadOnly) VALUES (1,'1',${0xFF101C2D.toInt()},92,${0xFFF4F7FC.toInt()},16,1,1)")
            legacy.execSQL("INSERT INTO widgets (id,feedIds,background,opacity,textColor,fontSp,images,unreadOnly) VALUES (2,'1',${0xFF112233.toInt()},45,${0xFFEEEEEE.toInt()},24,0,0)")
            legacy.execSQL("INSERT INTO preferences VALUES ('page:2','3')")
            if (version >= 2) {
                legacy.execSQL("UPDATE widgets SET background=${0xFF242629.toInt()}, opacity=98, textColor=${0xFFF5F5F5.toInt()}, fontSp=15 WHERE id=1")
            }
            if (version == 3) {
                legacy.execSQL("INSERT INTO folders (id,name,parentId) VALUES (1,'Root',NULL),(2,'Child',1)")
                legacy.execSQL("INSERT INTO folder_feed VALUES (2,1)")
                legacy.execSQL("UPDATE articles SET bookmarked=1")
                legacy.execSQL("UPDATE widgets SET folderId=2 WHERE id=2")
            }
            legacy.version = version
        } finally { legacy.close() }
        val db = Room.databaseBuilder(context, RssDatabase::class.java, name).addMigrations(RssDatabase.MIGRATION_1_2, RssDatabase.MIGRATION_2_3, RssDatabase.MIGRATION_3_4).build()
        try {
            assertEquals("etag", db.store().feed(1)!!.etag)
            assertTrue(db.store().article("kept")!!.read)
            assertEquals(version == 3, db.store().article("kept")!!.bookmarked)
            assertEquals("", db.store().article("kept")!!.bodyHtml)
            assertEquals(0, db.store().feed(1)!!.contentVersion)
            assertEquals(if (version == 3) 2 else 0, db.store().folders().size)
            assertEquals(if (version == 3) listOf(FolderFeed(2, 1)) else emptyList<FolderFeed>(), db.store().memberships())
            assertNull(db.store().widget(1)!!.folderId)
            assertEquals(WidgetConfig(1, feedIds = "1", unreadOnly = true), db.store().widget(1))
            assertEquals(WidgetConfig(2, feedIds = "1", background = 0xFF112233.toInt(), opacity = 45,
                textColor = 0xFFEEEEEE.toInt(), fontSp = 24, images = false, folderId = if (version == 3) 2 else null), db.store().widget(2))
            assertEquals("3", db.store().preference("page:2"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
