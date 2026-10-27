package cn.shixu.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import cn.shixu.app.ai.*
import cn.shixu.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class DraftRepositoryIntegrationTest {
    private lateinit var db: PlannerDatabase
    private lateinit var repo: Repository
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,PlannerDatabase::class.java).build();repo=Repository(db) }
    @After fun close() { db.close() }
    private fun raw(target: String="T1",fields: JSONObject=JSONObject(),patch: JSONObject?=null): JSONObject {
        val op=JSONObject().put("target",target).put("action","PATCH").put("fields",fields)
        patch?.let { op.put("reminderPatch",it) }
        return JSONObject().put("protocolVersion",2).put("operations",JSONArray().put(op))
    }
    private fun absolute(time: String)=JSONObject().put("kind","ABSOLUTE_LOCAL").put("localTime",time)
    private fun adds(vararg items: JSONObject)=JSONObject().put("mode","MERGE").put("add",JSONArray(items.toList()))
    private suspend fun send(text: String,raw: JSONObject,quote: String?=null): Pair<MessageEntity,MessageEntity> {
        val source=MessageEntity(role="user",text=text,quoteId=quote);repo.putMessage(source)
        return source to repo.acceptAiResult(raw,source.id,repo.conversationContext(source),"请核对累计草稿")
    }
    @Test fun chainWorksUnconfirmedPartlyConfirmedAndConfirmedEveryTurn() = runBlocking {
        for(mode in 0..2) {
            repo.dao.clearDraftLinks();repo.dao.clearDrafts();repo.dao.clearLinks();repo.dao.clearMessages();repo.dao.clearHistory();repo.dao.clearReminders();repo.dao.clearTasks()
            val a=send("11月2日下午3点抢报名额",raw("NEW",JSONObject().put("title","抢报名额").put("dueDate","2030-11-02").put("dueTime","15:00")))
            val id=repo.dao.drafts().single().targetTaskId
            if(mode>0) repo.applyProposal(a.second.id)
            val b=send("提前一小时、十分钟、一分钟提醒",raw(patch=adds(*listOf(60,10,1).map { JSONObject().put("kind","RELATIVE_MINUTES").put("relativeMinutes",it) }.toTypedArray())))
            val originalIds=DraftContent.bundle(repo.dao.drafts().single()).reminders.map { it.id }.toSet()
            if(mode==2) repo.applyProposal(b.second.id)
            val c=send("再加前一天晚上10点",raw(patch=adds(absolute("2030-11-01T22:00"))),b.first.id)
            if(mode==2) repo.applyProposal(c.second.id)
            val d=send("当天早上6点也提醒",raw(patch=adds(absolute("2030-11-02T06:00"))),a.first.id)
            if(mode==0) assertTrue(repo.dao.tasks().isEmpty())
            assertEquals(id,repo.dao.drafts().single().targetTaskId)
            repo.applyProposal(d.second.id)
            assertEquals(1,repo.dao.tasks().size)
            val reminders=repo.dao.remindersFor(id)
            assertEquals(setOf("2030-11-02T14:00","2030-11-02T14:50","2030-11-02T14:59","2030-11-01T22:00","2030-11-02T06:00"),reminders.map { it.localTime }.toSet())
            assertEquals(5,reminders.size);assertTrue(reminders.map { it.id }.containsAll(originalIds))
            listOf(a,b,c,d).forEach { (source,answer) -> assertEquals(id,repo.dao.linksFor(source.id).single().taskId);assertEquals(id,repo.dao.linksFor(answer.id).single().taskId) }
            try { repo.applyProposal(b.second.id);fail("Old card executed") } catch(_: IllegalStateException) { }
        }
    }
    @Test fun liveDeliveryStateIsPreservedWhenAnOpenDraftAddsAnotherReminder() = runBlocking {
        val task=TaskEntity(title="组会",dueDate="2030-11-02",dueTime="15:00")
        val reminder=ReminderEntity(taskId=task.id,localTime="2030-11-02T14:00",origin="USER")
        repo.save(TaskBundle(task,listOf(reminder)),null)
        val anchor=MessageEntity(role="assistant",text="组会");repo.putMessage(anchor);repo.dao.putLink(MessageTask(anchor.id,task.id))
        val suggestion=send("也加一个提醒",raw(patch=adds(absolute("2030-11-02T14:50"))),anchor.id)
        repo.dao.putReminder(reminder.copy(enabled=false,lastFiredLocal=reminder.localTime,delivery="DELIVERED"))
        repo.applyProposal(suggestion.second.id)
        assertFalse(repo.dao.reminder(reminder.id)!!.enabled)
        assertEquals(reminder.localTime,repo.dao.reminder(reminder.id)!!.lastFiredLocal)
        assertEquals(2,repo.dao.remindersFor(task.id).size)
    }
    @Test fun staleResponseAndManualVersionConflictCannotOverwriteNewerData() = runBlocking {
        val a=send("新建",raw("NEW",JSONObject().put("title","组会").put("dueDate","2030-11-02")))
        val delayed=MessageEntity(role="user",text="旧请求");repo.putMessage(delayed)
        val captured=repo.conversationContext(delayed)
        val b=send("补充备注",raw(fields=JSONObject().put("notes","最新备注")),a.first.id)
        try { repo.acceptAiResult(raw(fields=JSONObject().put("notes","旧备注")),delayed.id,captured,"");fail("Stale response applied") } catch(_: IllegalStateException) { }
        repo.applyProposal(b.second.id)
        val task=repo.dao.tasks().single()
        val c=send("再补充",raw(fields=JSONObject().put("notes","聊天修改")),a.first.id)
        repo.save(repo.bundle(task.id)!!.copy(task=task.copy(notes="手动修改")),task.version)
        try { repo.applyProposal(c.second.id);fail("Manual change overwritten") } catch(_: IllegalStateException) { }
        assertEquals("手动修改",repo.dao.task(task.id)!!.notes)
        assertEquals("CONFLICT",repo.dao.drafts().single().status)
        assertEquals("CONFLICT",repo.dao.message(c.second.id)!!.state)
    }
    @Test fun acceptedSourceIsIdempotentAndUnrelatedExchangeDoesNotReuseFocus() = runBlocking {
        val a=send("新建",raw("NEW",JSONObject().put("title","组会")))
        val revision=repo.dao.drafts().single().revision
        val empty=ConversationContext(emptyList(),null,emptyList())
        assertEquals(a.second.id,repo.acceptAiResult(raw("NEW",JSONObject().put("title","不应创建")),a.first.id,empty,"").id)
        assertEquals(revision,repo.dao.drafts().single().revision)
        repo.putMessage(MessageEntity(role="user",text="聊点别的"));repo.putMessage(MessageEntity(role="assistant",text="好的"))
        val current=MessageEntity(role="user",text="提前一分钟提醒");repo.putMessage(current)
        assertTrue(repo.conversationContext(current).targets.isEmpty())
        val quoted=current.copy(quoteId=a.first.id)
        assertEquals(repo.dao.drafts().single().targetTaskId,repo.conversationContext(quoted).targets.single().bundle.task.id)
    }
    @Test fun reopeningDatabaseRestoresUnconfirmedTargetWithoutViewModelMemory() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="draft-reopen-${java.util.UUID.randomUUID()}.db"
        var persistent=Room.databaseBuilder(context,PlannerDatabase::class.java,name).build()
        try {
            var repository=Repository(persistent)
            val a=MessageEntity(role="user",text="组会");repository.putMessage(a)
            repository.acceptAiResult(raw("NEW",JSONObject().put("title","组会").put("dueDate","2030-11-02")),a.id,repository.conversationContext(a),"核对")
            val id=repository.dao.drafts().single().targetTaskId
            persistent.close()
            persistent=Room.databaseBuilder(context,PlannerDatabase::class.java,name).build();repository=Repository(persistent)
            val b=MessageEntity(role="user",text="再加一个提醒");repository.putMessage(b)
            assertEquals(id,repository.conversationContext(b).targets.single().bundle.task.id)
            val answer=repository.acceptAiResult(raw(patch=adds(absolute("2030-11-01T22:00"))),b.id,repository.conversationContext(b),"核对")
            repository.applyProposal(answer.id)
            assertEquals(id,repository.dao.linksFor(a.id).single().taskId)
            assertEquals(1,repository.dao.tasks().size)
        } finally { persistent.close();context.deleteDatabase(name) }
    }
    @Test fun cancelledDraftAndUnboundClarificationDoNotCaptureTheNextNewTopic() = runBlocking {
        val a=send("新建",raw("NEW",JSONObject().put("title","组会")))
        repo.cancelDraft(repo.dao.drafts().single().id)
        val b=MessageEntity(role="user",text="新建另一件事情");repo.putMessage(b)
        assertTrue(repo.conversationContext(b).targets.isEmpty())
        val answer=repo.acceptAiResult(raw("NEW",JSONObject().put("title","新安排")),b.id,repo.conversationContext(b),"核对")
        val c=MessageEntity(role="user",text="我不确定要不要换一个事项");repo.putMessage(c)
        val clarify=JSONObject().put("protocolVersion",2).put("clarification","你想补充还是新建？").put("operations",JSONArray())
        val reply=repo.acceptAiResult(clarify,c.id,repo.conversationContext(c),"")
        assertTrue(repo.dao.draftsForMessage(reply.id).isEmpty())
        assertNotEquals(a.second.id,answer.id)
    }
}
