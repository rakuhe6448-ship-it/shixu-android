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

class ClarificationIntegrationTest {
    private lateinit var db: PlannerDatabase
    private lateinit var repo: Repository
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,PlannerDatabase::class.java).build();repo=Repository(db) }
    @After fun close() { db.close() }
    private fun op(target: String,fields: JSONObject=JSONObject())=JSONObject().put("target",target).put("action","PATCH").put("fields",fields)
    private fun raw(vararg ops: JSONObject)=JSONObject().put("protocolVersion",2).put("operations",JSONArray(ops.toList()))
    @Test fun screenshotConversationWorksWithNoQuoteUserQuoteAndAssistantQuote() = runBlocking {
        for(mode in 0..2) {
            repo.dao.clearDraftLinks();repo.dao.clearDrafts();repo.dao.clearLinks();repo.dao.clearMessages();repo.dao.clearHistory();repo.dao.clearReminders();repo.dao.clearTasks()
            val source=MessageEntity(role="user",text="每隔三天提醒我面部清洁。每天晚上提醒我护肤。");repo.putMessage(source)
            val first=repo.acceptAiResult(raw(
                op("NEW",JSONObject().put("title","面部清洁")).put("reminderPolicy","NOTIFICATION").put("questions",JSONArray().put(DraftQuestion("REPEAT","目前支持每天或每周等周期，你选哪种？","每隔三天").json()).put(DraftQuestion("REMINDER_TIME","哪天几点提醒？").json())),
                op("NEW",JSONObject().put("title","护肤").put("repeat","DAILY")).put("reminderPolicy","NOTIFICATION").put("questions",JSONArray().put(DraftQuestion("REMINDER_TIME","晚上几点提醒？").json()))
            ).put("clarification","请补充清洁周期和通知时刻"),source.id,repo.conversationContext(source),"")
            assertEquals("WAITING_INPUT",first.state);assertEquals(2,repo.dao.drafts().size);assertTrue(repo.dao.tasks().isEmpty());assertTrue(repo.dao.reminders().isEmpty())
            val ids=repo.dao.drafts().associate { DraftContent.bundle(it).task.title to it.targetTaskId }
            val reply=MessageEntity(role="user",text="那改成每周，然后每天护肤不用设置提醒，只需要设成待办",quoteId=when(mode) { 1 -> source.id;2 -> first.id;else -> null });repo.putMessage(reply)
            val context=repo.conversationContext(reply);assertEquals(2,context.targets.size);assertFalse(context.unresolvedQuote)
            fun ref(title: String)=context.targets.single { it.bundle.task.title==title }.ref
            val second=repo.acceptAiResult(raw(op(ref("面部清洁"),JSONObject().put("repeat","WEEKLY")),op(ref("护肤")).put("reminderPolicy","NONE")).put("clarification","面部清洁希望每周几、几点提醒？"),reply.id,context,"")
            assertEquals("WAITING_INPUT",second.state);assertEquals(2,repo.dao.drafts().size)
            val skin=repo.dao.drafts().single { it.targetTaskId==ids["护肤"] }
            assertTrue(DraftContent.ready(skin));assertTrue(DraftContent.bundle(skin).reminders.isEmpty());assertEquals("DAILY",DraftContent.bundle(skin).task.repeat)
            val preview=repo.previewDraft(skin.id);repo.applyProposal(preview.id)
            val probe=MessageEntity(role="user",text="每周一21点提醒",quoteId=source.id);repo.putMessage(probe)
            val next=repo.conversationContext(probe);assertEquals(2,next.targets.size)
            val clean=next.targets.single { it.bundle.task.title=="面部清洁" }
            val reminder=JSONObject().put("mode","MERGE").put("add",JSONArray().put(JSONObject().put("kind","ABSOLUTE_LOCAL").put("localTime","2030-11-04T21:00").put("repeat","WEEKLY")))
            val final=repo.acceptAiResult(raw(op(clean.ref,JSONObject().put("dueDate","2030-11-04")).put("reminderPatch",reminder)),probe.id,next,"")
            assertEquals("PENDING",final.state);repo.applyProposal(final.id)
            assertEquals(2,repo.dao.tasks().size);assertEquals(ids.values.toSet(),repo.dao.tasks().map { it.id }.toSet())
            assertTrue(repo.dao.remindersFor(ids.getValue("护肤")).isEmpty());assertEquals("WEEKLY",repo.dao.remindersFor(ids.getValue("面部清洁")).single().repeat)
            assertEquals(2,repo.dao.linksFor(source.id).size)
        }
    }
    @Test fun legacyUnboundClarificationCanBeReparsedAndItsOriginalQuoteIsRepaired() = runBlocking {
        val original=MessageEntity(role="user",text="每天护肤，只放待办");repo.putMessage(original)
        val question=MessageEntity(role="assistant",text="本条回复未更改排期。\n请补充信息",quoteId=original.id);repo.putMessage(question)
        val reply=MessageEntity(role="user",text="就是每天，不需要通知",quoteId=question.id);repo.putMessage(reply)
        val context=repo.conversationContext(reply)
        assertTrue(context.unresolvedQuote);assertTrue(context.recent.any { it.id==original.id })
        val card=repo.acceptAiResult(raw(op("NEW",JSONObject().put("title","护肤").put("repeat","DAILY")).put("reminderPolicy","NONE")),reply.id,context,"")
        assertEquals("PENDING",card.state)
        val later=MessageEntity(role="user",text="补充备注",quoteId=original.id);repo.putMessage(later)
        assertEquals(repo.dao.drafts().single().targetTaskId,repo.conversationContext(later).targets.single().bundle.task.id)
        assertTrue(repo.dao.tasks().isEmpty());assertTrue(repo.dao.reminders().isEmpty())
    }
    @Test fun notificationPolicyWithoutTimeCannotBeSubmittedEvenIfTaskFieldsAreValid() = runBlocking {
        val source=MessageEntity(role="user",text="明天提醒我清洁");repo.putMessage(source)
        val card=repo.acceptAiResult(raw(op("NEW",JSONObject().put("title","清洁").put("dueDate","2030-11-04")).put("reminderPolicy","NOTIFICATION")),source.id,repo.conversationContext(source),"")
        assertEquals("WAITING_INPUT",card.state)
        // Even a corrupted UI state cannot bypass the persisted draft's missing fields.
        repo.dao.putMessage(card.copy(state="PENDING"))
        try { repo.applyProposal(card.id);fail("Incomplete notification submitted") } catch(_: IllegalStateException) { }
        assertTrue(repo.dao.tasks().isEmpty());assertTrue(repo.dao.reminders().isEmpty())
    }
}
