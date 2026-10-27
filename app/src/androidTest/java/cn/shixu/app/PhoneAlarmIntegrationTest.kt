package cn.shixu.app

import android.content.ActivityNotFoundException
import android.provider.AlarmClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import cn.shixu.app.ai.*
import cn.shixu.app.data.*
import cn.shixu.app.reminders.SystemClock
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.Calendar

class PhoneAlarmIntegrationTest {
    private lateinit var db: PlannerDatabase
    private lateinit var repo: Repository
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,PlannerDatabase::class.java).build();repo=Repository(db) }
    @After fun close() { db.close() }
    private fun raw(time: String="07:00")=JSONObject().put("protocolVersion",2).put("intent","PHONE_ALARM").put("operations",JSONArray().put(JSONObject().put("action","SET_ALARM").put("alarm",PhoneAlarmSpec(time,null,emptyList(),"起床").json())))
    private suspend fun send(text: String,raw: JSONObject=raw(),quote: String?=null): MessageEntity {
        val source=MessageEntity(role="user",text=text,quoteId=quote);repo.putMessage(source)
        return repo.acceptAiResult(raw,source.id,repo.conversationContext(source),"核对")
    }
    @Test fun pendingAlarmFollowupUsesClockContextWithoutCreatingAnyTasks() = runBlocking {
        val first=send("明早7点设闹钟")
        val next=MessageEntity(role="user",text="改成7点半",quoteId=first.quoteId);repo.putMessage(next)
        val context=repo.conversationContext(next)
        assertEquals(first.id,context.phoneAlarm!!.id);assertFalse(context.unresolvedQuote);assertTrue(context.targets.isEmpty())
        val second=repo.acceptAiResult(raw("07:30"),next.id,context,"")
        assertEquals("SUPERSEDED",repo.dao.message(first.id)!!.state)
        assertEquals("07:30",PhoneAlarm.fromProposal(second.proposal!!)!!.time)
        assertTrue(repo.dao.tasks().isEmpty());assertTrue(repo.dao.reminders().isEmpty());assertTrue(repo.dao.drafts().isEmpty());assertTrue(repo.dao.links().isEmpty())
        try { repo.applyProposal(second.id);fail("Clock request was saved as a task") } catch(_: IllegalStateException) { }
        assertEquals("PENDING",repo.dao.message(second.id)!!.state)
    }
    @Test fun failedLaunchStaysPendingAndSuccessfulHandoffCannotBeRepeated() = runBlocking {
        val card=send("设7点的闹钟")
        try { repo.handoffPhoneAlarm(card.id) { throw ActivityNotFoundException() };fail("Launch failure swallowed") } catch(_: ActivityNotFoundException) { }
        assertEquals("PENDING",repo.dao.message(card.id)!!.state)
        var launches=0
        repo.handoffPhoneAlarm(card.id) { launches++;assertEquals("07:00",it.time) }
        assertEquals("APPLIED",repo.dao.message(card.id)!!.state)
        try { repo.handoffPhoneAlarm(card.id) { launches++ };fail("Duplicate handoff") } catch(_: IllegalStateException) { }
        assertEquals(1,launches);assertTrue(repo.dao.tasks().isEmpty());assertTrue(repo.dao.reminders().isEmpty())
    }
    @Test fun existingClockAlarmCannotBeRecreatedByAnEditEvenAfterClarification() = runBlocking {
        val first=send("设7点的闹钟");repo.handoffPhoneAlarm(first.id) { }
        val clarification=JSONObject().put("protocolVersion",2).put("intent","PHONE_ALARM").put("clarification","请在手机时钟中修改").put("operations",JSONArray())
        val answer=send("改成8点",clarification,first.id)
        assertEquals("APPLIED",answer.state)
        try { send("改成9点",raw("09:00"),answer.id);fail("Existing alarm recreated") } catch(_: IllegalStateException) { }
        assertTrue(repo.dao.tasks().isEmpty())
    }
    @Test fun explicitAlarmRequestCannotBecomeATaskNamedAlarm() = runBlocking {
        val taskResponse=JSONObject().put("protocolVersion",2).put("operations",JSONArray().put(JSONObject().put("action","PATCH").put("target","NEW").put("fields",JSONObject().put("title","闹钟").put("dueDate","2030-11-02"))))
        try { send("设早上7点闹钟",taskResponse);fail("Alarm became an ordinary reminder") } catch(_: IllegalArgumentException) { }
        assertTrue(repo.dao.tasks().isEmpty());assertTrue(repo.dao.drafts().isEmpty())
    }
    @Test fun clockIntentHasCorrectTimesWeekdaysAndKeepsSystemUiVisible() {
        val intent=SystemClock.createIntent(PhoneAlarmSpec("06:35",null,listOf(1,5,7),"起床"))
        assertEquals(AlarmClock.ACTION_SET_ALARM,intent.action)
        assertEquals(6,intent.getIntExtra(AlarmClock.EXTRA_HOUR,-1));assertEquals(35,intent.getIntExtra(AlarmClock.EXTRA_MINUTES,-1))
        assertEquals("起床",intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertEquals(listOf(Calendar.MONDAY,Calendar.FRIDAY,Calendar.SUNDAY),intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))
        assertFalse(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI,true))
        assertFalse(SystemClock.createIntent(PhoneAlarmSpec("07:00",null,emptyList(),"单次")).hasExtra(AlarmClock.EXTRA_DAYS))
    }
}
