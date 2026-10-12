package app.ondesk

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LinkShareTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OnDeskApplication
    @Test fun browserShareBecomesPersistentLinkAndCanBeDeleted() = runBlocking {
        app.transfers.lock()
        try {
            val before = app.database.queue().all().map { it.id }.toSet()
            app.startActivity(Intent(app, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Example page\nhttps://example.org/?ondesk=share#section")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val row = kotlinx.coroutines.withTimeout(10000) {
                var found: QueueEntry? = null
                while (found == null) { found = app.database.queue().all().firstOrNull { it.id !in before }; kotlinx.coroutines.delay(100) }
                found!!
            }
            assertEquals("https://example.org/?ondesk=share#section", row.link)
            assertEquals("", row.path)
            assertEquals(0L, row.size)
            QueueRepository(app).delete(row.id)
            assertFalse(app.database.queue().all().any { it.id == row.id })
        } finally { app.transfers.unlock() }
    }
    @Test fun versionTwoUpgradeKeepsQueuedLinks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val name = "migration-links-${UUID.randomUUID()}.db"
        val path = app.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val old = JSONObject(context.assets.open("app.ondesk.QueueDatabase/2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(old.getJSONArray("entities").getJSONObject(0).getString("createSql").replace("\${TABLE_NAME}", "queue"))
            val setup = old.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO queue VALUES ('legacy-link', 'Example', 'text/uri-list', 0, '', '', 'mac', 1, 0, 'queued', NULL, 'https://example.org/?retained=1')")
            db.version = 2
        }
        val upgraded = Room.databaseBuilder(app, QueueDatabase::class.java, name).build()
        try {
            val row = upgraded.queue().all().single()
            assertEquals("https://example.org/?retained=1", row.link)
            assertEquals("mac", row.serverID); assertEquals("queued", row.state); assertNull(row.shareBatchID)
        } finally { upgraded.close(); app.deleteDatabase(name) }
    }
    @Test fun databaseUpgradePreservesExistingFileQueue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val name = "migration-${UUID.randomUUID()}.db"
        val path = app.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val old = JSONObject(context.assets.open("app.ondesk.QueueDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(old.getJSONArray("entities").getJSONObject(0).getString("createSql").replace("\${TABLE_NAME}", "queue"))
            val setup = old.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO queue VALUES ('legacy', 'file.txt', 'text/plain', 3, 'hash', '/synthetic/queue/file', 'mac', 1, 2, 'queued', NULL)")
            db.version = 1
        }
        val upgraded = Room.databaseBuilder(app, QueueDatabase::class.java, name).build()
        try {
            val row = upgraded.queue().all().single()
            assertEquals("legacy", row.id); assertEquals("/synthetic/queue/file", row.path)
            assertEquals(2L, row.offset); assertEquals("mac", row.serverID); assertNull(row.link); assertNull(row.shareBatchID)
        } finally { upgraded.close(); app.deleteDatabase(name) }
    }
}
