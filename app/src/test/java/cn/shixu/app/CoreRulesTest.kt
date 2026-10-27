package cn.shixu.app

import cn.shixu.app.data.*
import cn.shixu.app.ai.ProposalValidator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CoreRulesTest {
    private val now=LocalDateTime.of(2026,10,24,12,0)
    private val task=TaskEntity(title="流体输配管网作业",dueDate="2026-11-06",category="学习")
    private fun raw(action: String,changes: JSONObject=JSONObject(),target: TaskEntity=task): JSONObject = JSONObject().put("reply","").put("clarification","").put("operations",JSONArray().put(JSONObject().put("action",action).put("taskId",target.id).put("version",target.version).put("changes",changes)))
    private fun proposal(raw: JSONObject, candidates: List<TaskBundle> = listOf(TaskBundle(task,emptyList())),quoted: Set<String> = emptySet()): JSONObject = JSONObject(ProposalValidator.normalize(raw,"source",candidates,quoted,now)!!)
    private fun bundle(json: JSONObject) = JsonCodec.readBundle(json.getJSONArray("operations").getJSONObject(0).getJSONObject("bundle"))
    private fun fails(block: () -> Unit) { try { block(); fail("Expected validation failure") } catch(e: IllegalArgumentException) { assertNotNull(e.message) } }

    @Test fun deadlineOnlyDoesNotOccupyEarlierDays() {
        assertFalse(task.covers(LocalDate.of(2026,11,5)))
        assertTrue(task.covers(LocalDate.of(2026,11,6)))
        assertFalse(task.covers(LocalDate.of(2026,11,7)))
    }
    @Test fun defaultReminderIsPreviousDayAtNine() {
        val reminder=Rules.defaultReminder(task,now)!!
        assertEquals("2026-11-05T09:00",reminder.localTime)
        assertEquals(task.deadline(),LocalDateTime.parse(reminder.localTime).plusMinutes(reminder.relativeMinutes!!))
    }
    @Test fun pastDefaultIsNotInventedAtAnotherTime() {
        assertNull(Rules.defaultReminder(task,LocalDateTime.of(2026,11,5,10,0)))
    }
    @Test fun overdueTaskRemainsActiveAndVisibleAtItsDate() {
        assertTrue(task.overdue(LocalDateTime.of(2026,11,8,0,0)))
        assertTrue(task.active)
        assertTrue(task.covers(LocalDate.of(2026,11,6)))
    }
    @Test fun startAfterDueIsRejected() { fails { Rules.validate(task.copy(startDate="2026-11-07"),emptyList()) } }
    @Test fun remindersMustHaveCorrectForeignKey() { fails { Rules.validate(task,listOf(ReminderEntity(taskId="wrong",localTime="2026-11-05T09:00"))) } }
    @Test fun duplicateRemindersAreRejected() {
        val r=Rules.defaultReminder(task,now)!!
        fails { Rules.validate(task,listOf(r,r.copy(id="other"))) }
    }
    @Test fun monthlyRepeatKeepsTheOriginalDay() {
        val anchor=LocalDateTime.of(2027,1,31,9,0)
        assertEquals(LocalDateTime.of(2027,2,28,9,0),Rules.nextAfter(anchor,"MONTHLY",anchor))
        assertEquals(LocalDateTime.of(2027,3,31,9,0),Rules.nextAfter(anchor,"MONTHLY",LocalDateTime.of(2027,2,28,9,0)))
    }
    @Test fun leapYearRecurrenceReturnsToFebruary29() {
        val anchor=LocalDateTime.of(2024,2,29,9,0)
        assertEquals(LocalDateTime.of(2028,2,29,9,0),Rules.nextAfter(anchor,"YEARLY",LocalDateTime.of(2027,2,28,9,0)))
    }
    @Test fun pendingOccurrenceSurvivesProcessStartAfterAlarmTime() {
        val r=ReminderEntity(taskId=task.id,localTime="2026-10-24T09:00",repeat="DAILY",scheduledLocal="2026-10-24T09:00",scheduledAt=1234)
        assertEquals(LocalDateTime.of(2026,10,24,9,0),Rules.nextReminder(r,now))
        val delivered=r.copy(lastFiredLocal="2026-10-24T09:00",scheduledLocal=null,scheduledAt=null)
        assertEquals(LocalDateTime.of(2026,10,25,9,0),Rules.nextReminder(delivered,now))
    }
    @Test fun deliveredOneShotDoesNotRepeatOnReboot() {
        val r=ReminderEntity(taskId=task.id,localTime="2026-10-24T09:00",lastFiredLocal="2026-10-24T09:00")
        assertNull(Rules.nextReminder(r,now))
    }
    @Test fun missedOneShotCatchesUpOnce() {
        val r=ReminderEntity(taskId=task.id,localTime="2026-10-20T09:00")
        assertEquals(LocalDateTime.of(2026,10,20,9,0),Rules.nextReminder(r,now))
    }
    @Test fun newRepeatingReminderSkipsHistoricalBurst() {
        val r=ReminderEntity(taskId=task.id,localTime="2020-10-24T09:00",repeat="DAILY")
        assertEquals(LocalDateTime.of(2026,10,25,9,0),Rules.nextReminder(r,now))
    }
    @Test fun calendarHandlesSixRowsAndContinuousBars() {
        val multi=task.copy(startDate="2026-11-01",dueDate="2026-11-30")
        val weeks=CalendarLayout.weeks(YearMonth.of(2026,11),listOf(multi))
        assertEquals(6,weeks.size)
        assertEquals(6,weeks.sumOf { it.bars.size })
        assertFalse(weeks.first().bars.first().continuesBefore)
        assertTrue(weeks.first().bars.first().continuesAfter)
        assertFalse(weeks.last().bars.first().continuesAfter)
    }
    @Test fun sameLaneNeverOverlaps() {
        val tasks=(1..40).map { n -> task.copy(id=n.toString(),startDate="2026-11-%02d".format(n%20+1),dueDate="2026-11-%02d".format(n%20+5)) }
        CalendarLayout.weeks(YearMonth.of(2026,11),tasks).forEach { week ->
            week.bars.groupBy { it.lane }.values.forEach { lane -> lane.sortedBy { it.start }.zipWithNext().forEach { (a,b) -> assertTrue(a.end < b.start) } }
        }
    }
    @Test fun completedTasksAreRemovedFromCalendar() {
        assertEquals(0,CalendarLayout.weeks(YearMonth.of(2026,11),listOf(task.copy(completedAt=1))).sumOf { it.bars.size })
    }
    @Test fun postponedDeadlineMovesRelativeButPreservesAbsoluteReminder() {
        val relative=Rules.defaultReminder(task,now)!!
        val absolute=ReminderEntity(taskId=task.id,localTime="2026-11-05T20:50")
        val result=bundle(proposal(raw("UPDATE",JSONObject().put("dueDate","2026-11-08")),listOf(TaskBundle(task,listOf(relative,absolute)))))
        assertEquals("2026-11-07T09:00",result.reminders[0].localTime)
        assertEquals("2026-11-05T20:50",result.reminders[1].localTime)
    }
    @Test fun quotedTargetCannotBeReplacedByAnotherTask() {
        val other=task.copy(id="other")
        fails { proposal(raw("DELETE",target=other),listOf(TaskBundle(task,emptyList()),TaskBundle(other,emptyList())),setOf(task.id)) }
    }
    @Test fun unseenTargetIsRejected() { fails { proposal(raw("COMPLETE",target=task.copy(id="unknown"))) } }
    @Test fun staleModelVersionIsRejected() { fails { proposal(raw("COMPLETE",target=task.copy(version=99))) } }
    @Test fun injectedSystemFieldsAreRejected() { fails { proposal(raw("UPDATE",JSONObject().put("completedAt",123))) } }
    @Test fun completionCannotAlsoEditTitle() { fails { proposal(raw("COMPLETE",JSONObject().put("title","changed"))) } }
    @Test fun ambiguousResponseNeverContainsExecutableProposal() {
        assertNull(ProposalValidator.normalize(raw("DELETE").put("clarification","你说的是哪一份作业？"),"source",listOf(TaskBundle(task,emptyList())),emptySet(),now))
    }
    @Test fun explicitReminderOverridesDefault() {
        val changes=JSONObject().put("title","会议").put("dueDate","2026-11-06").put("dueTime","21:00").put("reminders",JSONArray().put(JSONObject().put("localTime","2026-11-06T20:50")))
        val result=bundle(proposal(raw("CREATE",changes)))
        assertEquals(1,result.reminders.size)
        assertEquals("2026-11-06T20:50",result.reminders.single().localTime)
        assertNull(result.reminders.single().relativeMinutes)
    }
    @Test fun explicitlyEmptyRemindersDisablesDefault() {
        val changes=JSONObject().put("title","会议").put("dueDate","2026-11-06").put("reminders",JSONArray())
        assertTrue(bundle(proposal(raw("CREATE",changes))).reminders.isEmpty())
    }
    @Test fun jsonCodeFenceIsTolerated() { assertEquals(0,ProposalValidator.extract("```json\n{\"operations\":[]}\n```").getJSONArray("operations").length()) }
    @Test fun duplicateTargetInOneBatchIsRejected() {
        val data=raw("COMPLETE")
        data.getJSONArray("operations").put(data.getJSONArray("operations").getJSONObject(0))
        fails { proposal(data) }
    }
}
