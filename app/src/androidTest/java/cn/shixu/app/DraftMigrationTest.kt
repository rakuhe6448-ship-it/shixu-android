package cn.shixu.app

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import cn.shixu.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DraftMigrationTest {
    @Test fun actualVersionOneDatabaseMigratesWithoutChangingIdsOrDeliveryState() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val name="migration-${UUID.randomUUID()}.db"
        val schema=JSONObject(instrumentation.context.assets.open("cn.shixu.app.data.PlannerDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object: SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getJSONArray("entities").objects().forEach { entity ->
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                        entity.optJSONArray("indices")?.objects().orEmpty().forEach { index -> db.execSQL(index.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName"))) }
                    }
                    schema.getJSONArray("setupQueries").strings().forEach(db::execSQL)
                    db.execSQL("INSERT INTO tasks (id,title,startDate,dueDate,dueTime,color,category,notes,repeat,completedAt,deletedAt,version,createdAt,updatedAt,seriesId) VALUES ('legacy-task','旧任务',NULL,'2030-11-02','15:00',4286815093,'学习','','NONE',NULL,NULL,7,1,1,NULL)")
                    db.execSQL("INSERT INTO reminders (id,taskId,localTime,repeat,relativeMinutes,enabled,lastFiredLocal,scheduledAt,scheduledLocal,delivery) VALUES ('legacy-reminder','legacy-task','2030-11-02T14:00','NONE',60,0,'2030-11-02T14:00',NULL,NULL,'DELIVERED')")
                    db.execSQL("INSERT INTO messages (id,role,text,imagePaths,quoteId,proposal,state,createdAt,sharedText) VALUES ('legacy-card','assistant','待确认','[]',NULL,NULL,'PENDING',1,'')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase,oldVersion: Int,newVersion: Int) { error("Unexpected upgrade") }
            }).build())
        try {
            helper.writableDatabase;helper.close()
            val db=Room.databaseBuilder(context,PlannerDatabase::class.java,name).addMigrations(PlannerDatabase.MIGRATION_1_2).build()
            try {
                assertEquals(7,db.dao().task("legacy-task")!!.version)
                val reminder=db.dao().reminder("legacy-reminder")!!
                assertEquals("UNKNOWN",reminder.origin);assertFalse(reminder.enabled)
                assertEquals("2030-11-02T14:00",reminder.lastFiredLocal);assertEquals(60L,reminder.relativeMinutes)
                assertEquals("DISMISSED",db.dao().message("legacy-card")!!.state)
                assertTrue(db.dao().drafts().isEmpty())
            } finally { db.close() }
        } finally { helper.close();context.deleteDatabase(name) }
    }
}
