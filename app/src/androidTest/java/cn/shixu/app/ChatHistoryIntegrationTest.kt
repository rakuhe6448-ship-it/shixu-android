package cn.shixu.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import cn.shixu.app.ai.DraftContent
import cn.shixu.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class ChatHistoryIntegrationTest {
    private lateinit var db: PlannerDatabase
    private lateinit var repo: Repository
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,PlannerDatabase::class.java).build();repo=Repository(db) }
    @After fun close() { db.close() }
    private suspend fun draft(source: MessageEntity): MessageEntity {
        repo.putMessage(source)
        val op=JSONObject().put("target","NEW").put("action","PATCH").put("fields",JSONObject().put("title","购物").put("dueDate","2030-11-06"))
        return repo.acceptAiResult(JSONObject().put("protocolVersion",2).put("operations",JSONArray().put(op)),source.id,repo.conversationContext(source),"核对后保存")
    }
    @Test fun deletingSourceRemovesContentButDraftCanStillBeConfirmed() = runBlocking {
        val source=MessageEntity(role="user",text="私人文字",sharedText="分享内容",imagePaths="[\"private.jpg\"]")
        draft(source)
        assertEquals(listOf("private.jpg"),repo.deleteChatHistory(source.id))
        val tombstone=repo.dao.message(source.id)!!
        assertEquals("DELETED",tombstone.state);assertEquals("",tombstone.text);assertEquals("",tombstone.sharedText);assertEquals("[]",tombstone.imagePaths)
        assertFalse(repo.dao.observeMessages().first().any { it.id==source.id })
        val d=repo.dao.drafts().single();assertTrue(DraftContent.ready(d))
        repo.applyProposal(repo.previewDraft(d.id).id)
        assertEquals(1,repo.dao.tasks().size)
        assertEquals("购物",repo.dao.tasks().single().title)
    }
    @Test fun sharedImageIsOnlyReleasedAfterTheLastMessageIsDeleted() = runBlocking {
        val a=MessageEntity(role="user",text="A",imagePaths="[\"shared.jpg\"]")
        val b=MessageEntity(role="user",text="B",imagePaths=a.imagePaths)
        repo.putMessage(a);repo.putMessage(b)
        assertTrue(repo.deleteChatHistory(a.id).isEmpty())
        assertEquals(listOf("shared.jpg"),repo.deleteChatHistory(b.id))
    }
    @Test fun clearHistoryDiscardsUnconfirmedOperationsAndPreservesFormalTaskAndReminder() = runBlocking {
        val source=MessageEntity(role="user",text="录入购物");val card=draft(source)
        repo.applyProposal(card.id)
        val task=repo.dao.tasks().single()
        val reminder=ReminderEntity(taskId=task.id,localTime="2030-11-05T09:00")
        repo.save(TaskBundle(task,listOf(reminder)),task.version)
        val saved=repo.bundle(task.id)!!
        draft(MessageEntity(role="user",text="另一件待确认的事"))
        repo.deleteChatHistory()
        assertTrue(repo.dao.messages().isEmpty());assertTrue(repo.dao.drafts().isEmpty());assertTrue(repo.dao.links().isEmpty())
        assertEquals(saved,repo.bundle(task.id))
        assertTrue(repo.dao.history().isNotEmpty());assertTrue(repo.dao.history().all { it.sourceMessageId==null })
        try { repo.applyProposal(card.id);fail("Deleted card executed") } catch(_: IllegalStateException) { }
    }
}
