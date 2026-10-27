package cn.shixu.app.ai

import cn.shixu.app.data.objects
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A Clock-app request, deliberately independent of task drafts and app reminders. */
data class PhoneAlarmSpec(val time: String,val date: String?,val days: List<Int>,val label: String) {
    fun json(): JSONObject = JSONObject().put("time",time).put("date",date ?: JSONObject.NULL)
        .put("days",JSONArray(days)).put("label",label)
    fun description(): String = when {
        days.size==7 -> "每天 $time"
        days.isNotEmpty() -> days.sorted().joinToString("、",postfix=" $time") { "周"+"一二三四五六日"[it-1] }
        date!=null -> "$date $time · 单次"
        else -> "下一次 $time · 单次"
    }
    // Android's standard Clock intent has no arbitrary calendar-date extra.
    fun checkNextOccurrence(now: LocalDateTime=LocalDateTime.now()) {
        if(date==null) return
        val today=now.toLocalDate().atTime(LocalTime.parse(time))
        val next=if(today.isAfter(now)) today else today.plusDays(1)
        require(LocalDate.parse(date)==next.toLocalDate()) {
            "这个日期已过期，或不是下一次响铃日期。手机闹钟接口无法指定任意日期，请点击“打开手机时钟”手动设置。"
        }
    }
}

object PhoneAlarm {
    const val KIND="PHONE_ALARM"
    fun isProposal(text: String?): Boolean = text!=null && runCatching { JSONObject(text).optString("kind")==KIND }.getOrDefault(false)
    fun isResponse(raw: JSONObject): Boolean = raw.optString("intent")==KIND ||
        raw.optJSONArray("operations")?.objects()?.any { it.optString("action")=="SET_ALARM" }==true
    fun explicitRequest(text: String): Boolean {
        if(Regex("不要|不用|别|取消|关闭|删除|购买|买.*闹钟|叫.*的[事项待办任务]|名[字称]").containsMatchIn(text)) return false
        return Regex("(设|定|订|添加|开).{0,12}闹钟|闹钟.{0,12}(设|定|开)|叫醒我").containsMatchIn(text)
    }
    fun newAfterHandoff(text: String,quoted: Boolean): Boolean = explicitRequest(text) &&
        !Regex("改|调整|提前|推迟|设为|设成|原来|刚才|这个|那个|把.*闹钟").containsMatchIn(text) &&
        (!quoted || Regex("新建|再设|再定|另外|另一个|加一个").containsMatchIn(text))
    private fun keys(o: JSONObject,allowed: Set<String>) = require(o.keys().asSequence().all { it in allowed }) { "手机闹钟包含不支持的字段" }
    fun read(o: JSONObject): PhoneAlarmSpec {
        keys(o,setOf("time","date","days","label"))
        require(o.opt("time") is String && o.getString("time").matches(Regex("\\d{2}:\\d{2}"))) { "请明确闹钟时刻，使用 24 小时制" }
        val time=o.getString("time")
        require(runCatching { LocalTime.parse(time) }.isSuccess) { "闹钟时刻必须在 00:00 至 23:59 之间" }
        val date=if(!o.has("date") || o.isNull("date")) null else {
            require(o.opt("date") is String)
            require(runCatching { LocalDate.parse(o.getString("date")) }.isSuccess) { "闹钟日期格式不正确" }
            LocalDate.parse(o.getString("date")).toString()
        }
        require(!o.has("days") || o.opt("days") is JSONArray) { "闹钟重复日期格式不正确" }
        val array=o.optJSONArray("days") ?: JSONArray()
        require(array.length()<=7)
        val days=(0 until array.length()).map { i ->
            val value=array.get(i);require(value is Number && value.toDouble()==value.toInt().toDouble() && value.toInt() in 1..7) { "重复日期必须为周一至周日（1 至 7）" };value.toInt()
        }
        require(days.distinct().size==days.size && (date==null || days.isEmpty())) { "单次日期和每周重复不能同时设置" }
        require(!o.has("label") || o.opt("label") is String)
        val label=o.optString("label","闹钟").trim().ifBlank { "闹钟" }
        require(label.length<=80 && label.none { it.isISOControl() }) { "闹钟名称过长或包含无效字符" }
        return PhoneAlarmSpec(time,date,days.sorted(),label)
    }
    fun fromProposal(text: String): PhoneAlarmSpec? {
        val p=JSONObject(text)
        keys(p,setOf("kind","protocolVersion","sourceId","alarm","operations"))
        require(p.getString("kind")==KIND && p.getInt("protocolVersion")==1 && p.getJSONArray("operations").length()==0)
        return if(p.isNull("alarm")) null else read(p.getJSONObject("alarm"))
    }
    fun normalize(raw: JSONObject,sourceId: String): String {
        keys(raw,setOf("protocolVersion","intent","reply","clarification","operations"))
        require(raw.opt("protocolVersion") is Number && raw.getInt("protocolVersion")==2)
        require(!raw.has("intent") || raw.getString("intent")==KIND)
        val ops=raw.getJSONArray("operations").objects()
        require(ops.size<=1) { "请一次确认一个手机闹钟；手机闹钟和排期事项请分开发送" }
        val spec=if(ops.isEmpty()) {
            require(raw.optString("clarification").isNotBlank()) { "请补充手机闹钟的具体时间" };null
        } else {
            require(raw.optString("clarification").isBlank()) { "闹钟信息有歧义，请先补充确认" }
            val op=ops.single();keys(op,setOf("action","alarm"))
            require(op.getString("action")=="SET_ALARM") { "手机闹钟不能与事项操作混合" }
            read(op.getJSONObject("alarm"))
        }
        return JSONObject().put("kind",KIND).put("protocolVersion",1).put("sourceId",sourceId)
            .put("alarm",spec?.json() ?: JSONObject.NULL).put("operations",JSONArray()).toString()
    }
}
