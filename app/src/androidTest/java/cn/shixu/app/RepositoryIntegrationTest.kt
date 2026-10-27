package cn.shixu.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shixu.app.ai.ProposalValidator
import cn.shixu.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class RepositoryIntegrationTest {
    private lateinit var db: PlannerDatabase
    private lateinit var repo: Repository
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,PlannerDatabase::class.java).build()
        repo=Repository(db)
    }
    @After fun close() { db.close() }
    private suspend fun createTask(title: String="作业"): TaskBundle {
        val t=TaskEntity(title=title,dueDate="2030-11-06")
        val b=TaskBundle(t,listOf(Rules.defaultReminder(t,LocalDateTime.of(2030,10,24,12,0))!!))
        repo.save(b,null)
        return repo.bundle(t.id)!!
    }
    @Test fun createsAndEditsActualRoomRowsWithAudit() = runBlocking {
        val original=createTask()
        repo.save(original.copy(task=original.task.copy(title="已修改")),original.task.version)
        val saved=repo.bundle(original.task.id)!!
        assertEquals("已修改",saved.task.title)
        assertEquals(2,saved.task.version)
        assertEquals(2,repo.dao.historyFor(saved.task.id).size)
        assertEquals(1,saved.reminders.size)
    }
    @Test fun optimisticVersionPreventsLostUpdate() = runBlocking {
        val original=createTask()
        repo.save(original.copy(task=original.task.copy(title="最新标题")),1)
        try { repo.save(original.copy(task=original.task.copy(title="旧窗口")),1); fail("stale edit applied") } catch(_: IllegalStateException) { }
        assertEquals("最新标题",repo.dao.task(original.task.id)!!.title)
        assertEquals(2,repo.dao.historyFor(original.task.id).size)
    }
    @Test fun completionCancelsAllRemindersAndKeepsTask() = runBlocking {
        val original=createTask()
        repo.setState(original.task.id,1,"COMPLETE")
        assertNotNull(repo.dao.task(original.task.id)!!.completedAt)
        assertTrue(repo.dao.remindersFor(original.task.id).all { !it.enabled && it.scheduledAt==null })
    }
    @Test fun reopeningResumesRepeatingRemindersWithoutReplayingOneOff() = runBlocking {
        val task=TaskEntity(title="重新打开重复提醒")
        val once=ReminderEntity(taskId=task.id,localTime="2025-01-01T09:00",enabled=false,lastFiredLocal="2025-01-01T09:00")
        val daily=ReminderEntity(taskId=task.id,localTime="2025-01-01T10:00",repeat="DAILY",lastFiredLocal="2025-01-01T10:00")
        repo.save(TaskBundle(task,listOf(once,daily)),null)
        repo.setState(task.id,1,"COMPLETE")
        repo.setState(task.id,2,"RESTORE")
        assertFalse(repo.dao.reminder(once.id)!!.enabled)
        assertTrue(repo.dao.reminder(daily.id)!!.enabled)
        assertEquals(daily.lastFiredLocal,repo.dao.reminder(daily.id)!!.lastFiredLocal)
    }
    @Test fun titleEditDoesNotRearmReminderThatFiredWhileEditorWasOpen() = runBlocking {
        val original=createTask()
        val r=original.reminders.single()
        repo.dao.putReminder(r.copy(enabled=false,lastFiredLocal=r.localTime,delivery="DELIVERED"))
        repo.save(original.copy(task=original.task.copy(title="新标题")),1)
        val saved=repo.dao.remindersFor(original.task.id).single()
        assertFalse(saved.enabled)
        assertEquals(r.localTime,saved.lastFiredLocal)
    }
    @Test fun proposalCommitsTaskLinksHistoryAndAppliedStateTogether() = runBlocking {
        val source=MessageEntity(role="user",text="11月6日交作业")
        repo.putMessage(source)
        val raw=JSONObject().put("operations",JSONArray().put(JSONObject().put("action","CREATE").put("changes",JSONObject().put("title","AI 作业").put("dueDate","2030-11-06"))))
        val normalized=ProposalValidator.normalize(raw,source.id,emptyList(),emptySet(),LocalDateTime.of(2030,10,24,12,0))!!
        val card=MessageEntity(role="assistant",text="确认",proposal=normalized,state="PENDING")
        repo.putMessage(card)
        val target=cn.shixu.app.ai.ProposalChoices.pending(normalized).single()
        try { repo.applyProposal(card.id);fail("Missing start decision accepted") } catch(_: IllegalArgumentException) { }
        assertTrue(repo.dao.tasks().isEmpty());assertEquals("PENDING",repo.dao.message(card.id)!!.state)
        repo.applyProposal(card.id,mapOf(target.id to "2030-11-01"))
        val saved=repo.dao.tasks().single()
        assertEquals("2030-11-01",saved.startDate);assertEquals("学习",saved.category)
        assertTrue(cn.shixu.app.ai.ProposalChoices.pending(repo.dao.message(card.id)!!.proposal!!).isEmpty())
        assertEquals("2030-11-05T09:00",repo.dao.remindersFor(saved.id).single().localTime)
        assertEquals(saved.id,repo.dao.linksFor(source.id).single().taskId)
        assertEquals(saved.id,repo.dao.linksFor(card.id).single().taskId)
        assertEquals("APPLIED",repo.dao.message(card.id)!!.state)
        assertEquals(1,repo.dao.historyFor(saved.id).size)
        try { repo.applyProposal(card.id); fail("double apply succeeded") } catch(_: IllegalStateException) { }
        assertEquals(1,repo.dao.tasks().size)
    }
    @Test fun staleBatchRollsBackEarlierOperations() = runBlocking {
        val a=createTask("A"); val b=createTask("B")
        val source=MessageEntity(role="user",text="都改掉")
        repo.putMessage(source)
        val ops=JSONArray()
        listOf(a,b).forEach { old -> ops.put(JSONObject().put("action","UPDATE").put("taskId",old.task.id).put("version",old.task.version).put("changes",JSONObject().put("title",old.task.title+"changed"))) }
        val normalized=ProposalValidator.normalize(JSONObject().put("operations",ops),source.id,listOf(a,b),emptySet())!!
        val card=MessageEntity(role="assistant",text="确认",proposal=normalized,state="PENDING")
        repo.putMessage(card)
        repo.save(b.copy(task=b.task.copy(title="B最新")),1)
        try { repo.applyProposal(card.id); fail("stale batch applied") } catch(_: IllegalStateException) { }
        assertEquals("A",repo.dao.task(a.task.id)!!.title)
        assertEquals("B最新",repo.dao.task(b.task.id)!!.title)
        assertEquals("PENDING",repo.dao.message(card.id)!!.state)
        assertEquals(1,repo.dao.historyFor(a.task.id).size)
    }
    @Test fun repeatingTaskHasSeparateStableOccurrenceIds() = runBlocking {
        val original=createTask()
        repo.save(original.copy(task=original.task.copy(repeat="MONTHLY")),1)
        repo.setState(original.task.id,2,"COMPLETE")
        val next=repo.dao.tasks().single { it.active }
        assertNotEquals(original.task.id,next.id)
        assertEquals(original.task.id,next.seriesId)
        assertEquals("2030-12-06",next.dueDate)
        assertTrue(repo.dao.remindersFor(original.task.id).none { it.enabled })
        assertTrue(repo.dao.remindersFor(next.id).all { it.enabled })
    }
}
