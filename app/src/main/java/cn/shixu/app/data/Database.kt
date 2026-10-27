package cn.shixu.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao
interface PlannerDao {
    @Query("SELECT * FROM tasks ORDER BY dueDate IS NULL, dueDate, createdAt") fun observeTasks(): Flow<List<TaskEntity>>
    @Query("SELECT * FROM reminders ORDER BY localTime") fun observeReminders(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM messages ORDER BY createdAt, rowid") fun observeMessages(): Flow<List<MessageEntity>>
    @Query("SELECT * FROM message_tasks") fun observeLinks(): Flow<List<MessageTask>>
    @Query("SELECT * FROM task_drafts ORDER BY updatedAt DESC") fun observeDrafts(): Flow<List<TaskDraftEntity>>
    @Query("SELECT * FROM task_drafts") suspend fun drafts(): List<TaskDraftEntity>
    @Query("SELECT * FROM task_drafts WHERE id=:id") suspend fun draft(id: String): TaskDraftEntity?
    @Query("SELECT * FROM task_drafts WHERE targetTaskId=:id") suspend fun draftForTask(id: String): TaskDraftEntity?
    @Query("SELECT * FROM message_drafts WHERE messageId=:id") suspend fun draftsForMessage(id: String): List<MessageDraft>
    @Query("SELECT * FROM message_drafts WHERE draftId=:id") suspend fun messagesForDraft(id: String): List<MessageDraft>
    @Query("SELECT * FROM message_drafts") suspend fun draftLinks(): List<MessageDraft>
    @Query("SELECT * FROM settings") fun observeSettings(): Flow<List<SettingEntity>>
    @Query("SELECT * FROM tasks") suspend fun tasks(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE id=:id") suspend fun task(id: String): TaskEntity?
    @Query("SELECT * FROM reminders") suspend fun reminders(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE taskId=:id") suspend fun remindersFor(id: String): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE id=:id") suspend fun reminder(id: String): ReminderEntity?
    @Query("SELECT * FROM messages ORDER BY createdAt, rowid") suspend fun messages(): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE id=:id") suspend fun message(id: String): MessageEntity?
    @Query("SELECT * FROM message_tasks") suspend fun links(): List<MessageTask>
    @Query("SELECT * FROM message_tasks WHERE messageId=:id") suspend fun linksFor(id: String): List<MessageTask>
    @Query("SELECT * FROM history ORDER BY at DESC") suspend fun history(): List<HistoryEntity>
    @Query("SELECT * FROM history WHERE taskId=:id ORDER BY at DESC") suspend fun historyFor(id: String): List<HistoryEntity>
    @Query("SELECT * FROM settings") suspend fun settings(): List<SettingEntity>
    @Upsert suspend fun putTask(task: TaskEntity)
    @Upsert suspend fun putReminder(reminder: ReminderEntity)
    @Upsert suspend fun putMessage(message: MessageEntity)
    @Upsert suspend fun putDraft(draft: TaskDraftEntity)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun putDraftLink(link: MessageDraft)
    @Query("DELETE FROM message_drafts") suspend fun clearDraftLinks()
    @Query("DELETE FROM task_drafts") suspend fun clearDrafts()
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putLink(link: MessageTask)
    @Insert suspend fun putHistory(history: HistoryEntity)
    @Upsert suspend fun putSetting(setting: SettingEntity)
    @Query("DELETE FROM reminders WHERE taskId=:id") suspend fun deleteReminders(id: String)
    @Query("DELETE FROM message_tasks") suspend fun clearLinks()
    @Query("DELETE FROM messages") suspend fun clearMessages()
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("DELETE FROM reminders") suspend fun clearReminders()
    @Query("DELETE FROM tasks") suspend fun clearTasks()
    @Query("DELETE FROM settings") suspend fun clearSettings()
}

@Database(entities = [TaskEntity::class, ReminderEntity::class, MessageEntity::class, MessageTask::class, HistoryEntity::class, SettingEntity::class,TaskDraftEntity::class,MessageDraft::class], version = 2, exportSchema = true)
abstract class PlannerDatabase : RoomDatabase() {
    abstract fun dao(): PlannerDao
    companion object {
        val MIGRATION_1_2=object: Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN origin TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("CREATE TABLE IF NOT EXISTS task_drafts (id TEXT NOT NULL PRIMARY KEY, targetTaskId TEXT NOT NULL, targetKind TEXT NOT NULL, baseTaskVersion INTEGER, revision INTEGER NOT NULL, status TEXT NOT NULL, workingJson TEXT NOT NULL, sourceMessageId TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_task_drafts_targetTaskId ON task_drafts(targetTaskId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS message_drafts (messageId TEXT NOT NULL, draftId TEXT NOT NULL, PRIMARY KEY(messageId,draftId), FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE, FOREIGN KEY(draftId) REFERENCES task_drafts(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_drafts_draftId ON message_drafts(draftId)")
                db.execSQL("UPDATE messages SET state='DISMISSED', text=text || char(10) || '旧版建议需要重新解析，请核对后重新发送。' WHERE state='PENDING'")
            }
        }
    }
}
