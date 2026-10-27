package cn.shixu.app

import cn.shixu.app.ai.*
import cn.shixu.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class DraftEngineTest {
    private val now=LocalDateTime.of(2026,10,27,12,0)
    private val task=TaskEntity(title="抢报名额",dueDate="2026-11-02",dueTime="15:00")
    private fun context(reminders: List<ReminderEntity>)=ConversationContext(listOf(ConversationTarget("T1",TaskBundle(task,reminders),null,TaskBundle(task,reminders))),null,emptyList())
    private fun response(op: JSONObject)=JSONObject().put("protocolVersion",2).put("operations",JSONArray().put(op))
    private fun op(patch: JSONObject)=JSONObject().put("target","T1").put("action","PATCH").put("reminderPatch",patch)
    private fun absolute(at: String)=JSONObject().put("kind","ABSOLUTE_LOCAL").put("localTime",at)
    private fun merge(vararg adds: JSONObject)=JSONObject().put("mode","MERGE").put("add",JSONArray(adds.toList()))
    private fun result(op: JSONObject,c: ConversationContext)=DraftContent.bundle(DraftEngine.normalize(response(op),"source",c,now).single().draft)
    @Test fun appendPreservesExistingIdsAndDoesNotReplayDeliveredReminder() {
        val old=ReminderEntity(taskId=task.id,localTime="2026-11-02T14:00",relativeMinutes=60,enabled=false,lastFiredLocal="2026-11-02T14:00",origin="USER")
        val b=result(op(merge(JSONObject().put("kind","RELATIVE_MINUTES").put("relativeMinutes",60),absolute("2026-11-01T22:00"))),context(listOf(old)))
        assertEquals(2,b.reminders.size);assertEquals(old.id,b.reminders.first().id)
        // Drafts store definitions only; the repository must merge live delivery state on commit.
    }
    @Test fun firstExplicitGroupReplacesAutoButKeepsUserAndUnknown() {
        val auto=Rules.defaultReminder(task,now)!!
        val added=op(merge(absolute("2026-11-01T22:00")))
        assertEquals(1,result(added,context(listOf(auto))).reminders.size)
        listOf("USER","UNKNOWN").forEach { origin ->
            val old=auto.copy(origin=origin)
            assertTrue(result(added,context(listOf(old))).reminders.any { it.id==old.id })
        }
    }
    @Test fun explicitTimeMatchingAutoAdoptsTheSameId() {
        val auto=Rules.defaultReminder(task,now)!!
        val b=result(op(merge(JSONObject().put("kind","RELATIVE_MINUTES").put("relativeMinutes",auto.relativeMinutes))),context(listOf(auto)))
        assertEquals(auto.id,b.reminders.single().id);assertEquals("USER",b.reminders.single().origin)
    }
    @Test fun missingPatchPreservesAndClearIsExplicit() {
        val old=ReminderEntity(taskId=task.id,localTime="2026-11-01T22:00")
        val fieldsOnly=JSONObject().put("target","T1").put("action","PATCH").put("fields",JSONObject().put("notes","带好材料"))
        assertEquals(old.id,result(fieldsOnly,context(listOf(old))).reminders.single().id)
        assertTrue(result(op(JSONObject().put("mode","CLEAR")),context(listOf(old))).reminders.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { result(op(merge()),context(listOf(old))) }
    }
    @Test fun invalidReminderIdsAndMixedTimeDefinitionsAreRejected() {
        val p=JSONObject().put("mode","MERGE").put("removeIds",JSONArray().put("other-task-reminder"))
        assertThrows(IllegalArgumentException::class.java) { result(op(p),context(emptyList())) }
        val mixed=absolute("2026-11-01T22:00").put("relativeMinutes",60)
        assertThrows(IllegalArgumentException::class.java) { result(op(merge(mixed)),context(emptyList())) }
    }
    @Test fun newPartialDraftCannotPassFormalValidation() {
        val change=DraftEngine.normalize(response(JSONObject().put("target","NEW").put("action","PATCH").put("fields",JSONObject().put("dueDate","2026-11-02"))),"source",ConversationContext(emptyList(),null,emptyList()),now).single()
        assertFalse(DraftContent.ready(DraftContent.bundle(change.draft)))
        assertEquals("NEW",change.draft.targetKind)
    }
    @Test fun nullableUnknownStartStillAsksInsteadOfTreatingItAsUserOptOut() {
        val op=JSONObject().put("target","NEW").put("action","PATCH").put("startDateAdvice","ASK")
            .put("fields",JSONObject().put("title","复习").put("dueDate","2026-11-02").put("startDate",JSONObject.NULL))
        val d=DraftEngine.normalize(response(op),"source",ConversationContext(emptyList(),null,emptyList()),now).single().draft
        assertEquals("UNDECIDED",DraftContent.decision(d))
    }
    @Test fun titleOnlyDraftGetsOneDefaultWhenDeadlineIsAddedLater() {
        val empty=ConversationContext(emptyList(),null,emptyList())
        val a=DraftEngine.normalize(response(JSONObject().put("target","NEW").put("action","PATCH").put("fields",JSONObject().put("title","抢报名额"))),"a",empty,now).single().draft
        val context=ConversationContext(listOf(ConversationTarget("T1",DraftContent.bundle(a),a,null)),null,emptyList())
        val patch=JSONObject().put("target","T1").put("action","PATCH").put("fields",JSONObject().put("dueDate","2026-11-02").put("dueTime","15:00"))
        val b=DraftEngine.normalize(response(patch),"b",context,now).single().draft
        val reminder=DraftContent.bundle(b).reminders.single()
        assertEquals("2026-11-01T09:00",reminder.localTime)
        val after=DraftEngine.normalize(response(JSONObject().put("target","T1").put("action","PATCH").put("fields",JSONObject().put("notes","带材料"))),"c",context.copy(targets=listOf(ConversationTarget("T1",DraftContent.bundle(b),b,null))),now).single().draft
        assertEquals(reminder.id,DraftContent.bundle(after).reminders.single().id)
    }
    @Test fun quoteCannotCreateASeparateTaskOrAddressUnknownTarget() {
        val quoted=context(emptyList()).copy(quoteBound=true)
        assertThrows(IllegalArgumentException::class.java) { DraftEngine.normalize(response(JSONObject().put("target","NEW").put("action","PATCH")),"source",quoted,now) }
        assertThrows(IllegalStateException::class.java) { DraftEngine.normalize(response(JSONObject().put("target","T999").put("action","PATCH")),"source",quoted,now) }
    }
}
