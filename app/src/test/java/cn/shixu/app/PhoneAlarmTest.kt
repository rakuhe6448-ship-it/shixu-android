package cn.shixu.app

import cn.shixu.app.ai.PhoneAlarm
import cn.shixu.app.ai.PhoneAlarmSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDateTime

class PhoneAlarmTest {
    private fun raw(alarm: JSONObject)=JSONObject().put("protocolVersion",2).put("intent","PHONE_ALARM").put("operations",JSONArray().put(JSONObject().put("action","SET_ALARM").put("alarm",alarm)))
    private fun invalid(block: () -> Unit) { try { block();fail("Invalid clock request accepted") } catch(_: IllegalArgumentException) { } }
    @Test fun weeklyAlarmIsIndependentOfTaskProtocol() {
        val spec=PhoneAlarmSpec("07:30",null,listOf(1,2,3,4,5),"起床")
        val proposal=PhoneAlarm.normalize(raw(spec.json()),"source")
        assertTrue(PhoneAlarm.isProposal(proposal));assertEquals(spec,PhoneAlarm.fromProposal(proposal))
        assertEquals(0,JSONObject(proposal).getJSONArray("operations").length())
    }
    @Test fun clarificationDoesNotInventTime() {
        val response=JSONObject().put("protocolVersion",2).put("intent","PHONE_ALARM").put("clarification","几点叫醒你？").put("operations",JSONArray())
        assertTrue(PhoneAlarm.isResponse(response));assertNull(PhoneAlarm.fromProposal(PhoneAlarm.normalize(response,"source")))
    }
    @Test fun invalidTimeDaysAndUnknownFieldsAreRejected() {
        invalid { PhoneAlarm.read(JSONObject().put("time","24:00")) }
        invalid { PhoneAlarm.read(JSONObject().put("time","7:00")) }
        invalid { PhoneAlarm.read(JSONObject().put("time","07:00").put("days",JSONArray(listOf(1,1)))) }
        invalid { PhoneAlarm.read(JSONObject().put("time","07:00").put("days",JSONArray(listOf(1.5)))) }
        invalid { PhoneAlarm.read(JSONObject().put("time","07:00").put("days",JSONArray(listOf(8)))) }
        invalid { PhoneAlarm.read(JSONObject().put("time","07:00").put("date","2030-11-02").put("days",JSONArray(listOf(1)))) }
        invalid { PhoneAlarm.read(JSONObject().put("time","07:00").put("skipUi",true)) }
    }
    @Test fun mixedBatchIsRejected() {
        val response=raw(PhoneAlarmSpec("07:00",null,emptyList(),"起床").json())
        response.getJSONArray("operations").put(JSONObject().put("action","PATCH"))
        invalid { PhoneAlarm.normalize(response,"source") }
    }
    @Test fun explicitDateMustBeTheNextOccurrenceAndIsRecheckedLater() {
        val spec=PhoneAlarmSpec("07:00","2030-11-02",emptyList(),"起床")
        spec.checkNextOccurrence(LocalDateTime.parse("2030-11-01T22:00"))
        invalid { spec.checkNextOccurrence(LocalDateTime.parse("2030-11-01T06:00")) }
        invalid { spec.checkNextOccurrence(LocalDateTime.parse("2030-11-02T08:00")) }
        invalid { spec.checkNextOccurrence(LocalDateTime.parse("2030-11-02T07:00")) }
    }
    @Test fun clockIntentIsDistinctFromShoppingAndExistingAlarmEdits() {
        assertTrue(PhoneAlarm.explicitRequest("明早7点设一个闹钟"))
        assertTrue(PhoneAlarm.explicitRequest("每天6点半叫醒我"))
        assertFalse(PhoneAlarm.explicitRequest("提醒我买一个闹钟"))
        assertFalse(PhoneAlarm.explicitRequest("不要设闹钟，普通提醒就行"))
        assertFalse(PhoneAlarm.newAfterHandoff("把闹钟设为7点",false))
        assertFalse(PhoneAlarm.newAfterHandoff("改成7点",true))
        assertTrue(PhoneAlarm.newAfterHandoff("再设一个8点的闹钟",true))
    }
}
