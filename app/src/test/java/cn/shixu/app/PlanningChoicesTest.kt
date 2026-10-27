package cn.shixu.app

import cn.shixu.app.ai.*
import cn.shixu.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class PlanningChoicesTest {
    private fun proposal(title: String,advice: String?=null,start: String?=null,category: String?=null): String {
        val changes=JSONObject().put("title",title).put("dueDate","2030-11-06")
        start?.let { changes.put("startDate",it) };category?.let { changes.put("category",it) }
        val op=JSONObject().put("action","CREATE").put("changes",changes)
        advice?.let { op.put("startDateAdvice",it) }
        return ProposalValidator.normalize(JSONObject().put("operations",JSONArray().put(op)),"source",emptyList(),emptySet(),LocalDateTime.of(2030,10,24,12,0))!!
    }
    private fun bundle(p: String)=JsonCodec.readBundle(JSONObject(p).getJSONArray("operations").getJSONObject(0).getJSONObject("bundle"))
    @Test fun ongoingTaskOffersChoiceWithoutInventingStartDate() {
        val p=proposal("流体输配管网作业","ASK")
        assertEquals(1,ProposalChoices.pending(p).size)
        assertNull(bundle(p).task.startDate)
        assertEquals("学习",bundle(p).task.category)
        assertThrows(IllegalArgumentException::class.java) { ProposalChoices.resolve(p,emptyMap()) }
    }
    @Test fun selectedStartProducesContinuousCalendarAndKeepsReminder() {
        val p=proposal("作业计划","ASK")
        val before=bundle(p)
        val after=bundle(ProposalChoices.resolve(p,mapOf(before.task.id to "2030-11-01")))
        assertEquals("2030-11-01",after.task.startDate)
        assertEquals(before.reminders,after.reminders)
        assertTrue(after.task.covers(java.time.LocalDate.of(2030,11,3)))
    }
    @Test fun deadlineOnlyChoiceAndExplicitStartAreRespected() {
        val p=proposal("复习","ASK")
        val resolved=ProposalChoices.resolve(p,mapOf(bundle(p).task.id to null))
        assertTrue(ProposalChoices.pending(resolved).isEmpty());assertNull(bundle(resolved).task.startDate)
        assertTrue(ProposalChoices.pending(proposal("复习","ASK","2030-11-01")).isEmpty())
        assertTrue(ProposalChoices.pending(proposal("作业","NONE")).isEmpty())
        assertTrue(ProposalChoices.pending(proposal("明晚开会")).isEmpty())
    }
    @Test fun missingAdviceFallsBackAndUserCategoryWins() {
        assertEquals(1,ProposalChoices.pending(proposal("复习计划")).size)
        assertEquals("专业课",bundle(proposal("作业",category="专业课")).task.category)
        assertEquals("工作",bundle(proposal("项目交付")).task.category)
        assertEquals("生活",bundle(proposal("缴费")).task.category)
    }
    @Test fun invalidChoiceAndUnknownTargetCannotBeSaved() {
        val p=proposal("作业","ASK");val id=bundle(p).task.id
        assertThrows(IllegalArgumentException::class.java) { ProposalChoices.resolve(p,mapOf(id to "2030-11-07")) }
        assertThrows(IllegalArgumentException::class.java) { ProposalChoices.resolve(p,mapOf("other" to "2030-11-01")) }
        assertThrows(IllegalArgumentException::class.java) { proposal("作业","RUN") }
    }
    @Test fun ordinaryPostponementKeepsCategoryAndDoesNotAskForStart() {
        val old=TaskEntity(title="作业",category="专业课",dueDate="2030-11-06")
        val op=JSONObject().put("action","UPDATE").put("taskId",old.id).put("version",old.version).put("changes",JSONObject().put("dueDate","2030-11-08"))
        val p=ProposalValidator.normalize(JSONObject().put("operations",JSONArray().put(op)),"source",listOf(TaskBundle(old,emptyList())),emptySet())!!
        assertEquals("专业课",bundle(p).task.category);assertTrue(ProposalChoices.pending(p).isEmpty())
    }
}
