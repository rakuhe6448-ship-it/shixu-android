package cn.shixu.app.ai

import cn.shixu.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.UUID

data class ConversationTarget(val ref: String,val bundle: TaskBundle,val draft: TaskDraftEntity?,val formal: TaskBundle?)
data class ConversationContext(val targets: List<ConversationTarget>,val quoted: MessageEntity?,val recent: List<MessageEntity>,val quoteBound: Boolean=false,val unresolvedQuote: Boolean=false,val phoneAlarm: MessageEntity?=null)
data class DraftChange(val draft: TaskDraftEntity,val before: TaskBundle?)

object DraftContent {
    fun bundle(d: TaskDraftEntity)=JsonCodec.readBundle(JSONObject(d.workingJson).getJSONObject("bundle"))
    fun action(d: TaskDraftEntity)=JSONObject(d.workingJson).optString("action","UPSERT")
    fun decision(d: TaskDraftEntity)=JSONObject(d.workingJson).optString("startDateDecision","NOT_NEEDED")
    fun intent(d: TaskDraftEntity)=JSONObject(d.workingJson).optString("reminderIntent","EXPLICIT")
    fun encode(b: TaskBundle,action: String,intent: String,decision: String): String {
        val definitions=b.copy(reminders=b.reminders.map { it.copy(enabled=true,lastFiredLocal=null,scheduledAt=null,scheduledLocal=null,delivery="PENDING") })
        return JSONObject().put("formatVersion",1).put("bundle",JsonCodec.bundle(definitions)).put("action",action)
            .put("reminderIntent",intent).put("startDateDecision",decision).toString()
    }
    fun sameDefinition(a: ReminderEntity,b: ReminderEntity)=a.localTime==b.localTime && a.repeat==b.repeat && a.relativeMinutes==b.relativeMinutes
    fun live(d: TaskDraftEntity,formal: TaskBundle?): TaskBundle {
        val b=bundle(d)
        return b.copy(reminders=b.reminders.map { definition ->
            formal?.reminders?.find { it.id==definition.id && sameDefinition(it,definition) }?.copy(origin=definition.origin) ?: definition
        })
    }
    fun validatePartial(b: TaskBundle) {
        Rules.validate(b.task.copy(title=b.task.title.ifBlank { "待补充事项" },dueTime=if(b.task.dueDate==null) null else b.task.dueTime),b.reminders)
        b.task.dueTime?.let(java.time.LocalTime::parse)
    }
    fun ready(b: TaskBundle)=runCatching { Rules.validate(b.task,b.reminders) }.isSuccess
    fun operation(d: TaskDraftEntity,before: TaskBundle?): JSONObject {
        val b=bundle(d)
        val action=when(action(d)) { "UPSERT" -> if(d.targetKind=="NEW") "CREATE" else "UPDATE";else -> action(d) }
        return JSONObject().put("action",action).put("draftId",d.id).put("expectedRevision",d.revision)
            .put("expectedVersion",d.baseTaskVersion ?: JSONObject.NULL).put("needsStartDate",decision(d)=="UNDECIDED")
            .put("before",before?.let(JsonCodec::bundle) ?: JSONObject.NULL).put("bundle",JsonCodec.bundle(b))
    }
    fun proposal(sourceId: String,changes: List<DraftChange>)=JSONObject().put("protocolVersion",2)
        .put("id",UUID.randomUUID().toString()).put("sourceId",sourceId)
        .put("operations",JSONArray(changes.map { operation(it.draft,it.before) })).toString()
}

/** Applies only validated semantic deltas. This object never writes tasks or schedules alarms. */
object DraftEngine {
    private val fields=setOf("title","startDate","dueDate","dueTime","category","notes","color","repeat")
    private fun keys(o: JSONObject)=o.keys().asSequence().toSet()
    private fun checkKeys(o: JSONObject,allowed: Set<String>)=require(keys(o).all { it in allowed }) { "模型返回了未知或不允许的字段" }
    private fun array(o: JSONObject,key: String): List<JSONObject> {
        if(!o.has(key)) return emptyList()
        require(o.opt(key) is JSONArray) { "$key 必须是数组" }
        return o.getJSONArray(key).objects()
    }
    fun normalize(raw: JSONObject,sourceId: String,context: ConversationContext,now: LocalDateTime=LocalDateTime.now()): List<DraftChange> {
        checkKeys(raw,setOf("protocolVersion","reply","clarification","clarificationTarget","operations"))
        require(raw.opt("protocolVersion") is Number && raw.getInt("protocolVersion")==2) { "请使用支持增量操作的 v2 JSON 格式" }
        val ops=array(raw,"operations")
        require(ops.size<=10) { "一次最多处理 10 个事项" }
        if(raw.has("clarificationTarget")) {
            require(raw.opt("clarificationTarget") is String) { "追问目标格式不正确" }
            val ref=raw.getString("clarificationTarget")
            require(ref.isBlank() || context.targets.any { it.ref==ref }) { "追问目标不在本轮相关事项中" }
            require(ops.isEmpty()) { "追问目标不能和执行操作同时返回" }
        }
        if(raw.optString("clarification").isNotBlank()) { require(ops.isEmpty()) { "存在歧义时不能同时返回操作" };return emptyList() }
        require(!context.unresolvedQuote || ops.isEmpty()) { "引用消息尚未关联事项，请选择具体事项" }
        val seen=mutableSetOf<String>()
        return ops.map { op ->
            checkKeys(op,setOf("target","action","fields","reminderPatch","startDateAdvice"))
            val ref=op.getString("target")
            val action=op.getString("action")
            require(action in setOf("PATCH","COMPLETE","DELETE")) { "不支持的事项操作" }
            require(ref=="NEW" || seen.add(ref)) { "同一批次请合并同一事项的修改" }
            val target=if(ref=="NEW") null else context.targets.find { it.ref==ref } ?: error("模型选择了本轮范围外的事项")
            require(ref!="NEW" || !context.quoteBound) { "引用补充不能另建重复事项，请明确提出新建" }
            val previous=target?.draft
            require(previous?.status !in setOf("CANCELLED","CONFLICT")) { "草稿已取消或发生冲突，请重新核对" }
            val old=target?.bundle ?: TaskBundle(TaskEntity(title=""),emptyList())
            require(old.task.active) { "事项已完成或删除" }
            require(target?.formal!=null || action=="PATCH") { "未保存的草稿请先保存，或取消草稿" }
            if(action!="PATCH") require(previous?.status!="OPEN") { "请先确认或取消该事项尚未保存的修改" }
            val f=op.optJSONObject("fields") ?: JSONObject().also { require(!op.has("fields")) { "fields 必须是对象" } }
            checkKeys(f,fields)
            if(action!="PATCH") require(f.length()==0 && !op.has("reminderPatch") && !op.has("startDateAdvice")) { "完成或删除不能同时修改字段" }
            var t=old.task.copy(
                title=if(f.has("title")) f.getString("title").trim() else old.task.title,
                startDate=if(f.has("startDate")) f.nullString("startDate") else old.task.startDate,
                dueDate=if(f.has("dueDate")) f.nullString("dueDate") else old.task.dueDate,
                dueTime=if(f.has("dueTime")) f.nullString("dueTime") else old.task.dueTime,
                category=if(f.has("category")) f.getString("category").trim() else if(target==null) ProposalChoices.category(f.optString("title")) else old.task.category,
                notes=if(f.has("notes")) f.getString("notes") else old.task.notes,
                color=if(f.has("color")) f.getLong("color") else old.task.color,
                repeat=if(f.has("repeat")) f.getString("repeat") else old.task.repeat
            )
            var reminders=old.reminders.map { r ->
                if(old.task.deadline()!=t.deadline() && r.relativeMinutes!=null) {
                    if(t.deadline()!=null) r.copy(localTime=t.deadline()!!.minusMinutes(r.relativeMinutes).toString(),enabled=true,lastFiredLocal=null,scheduledAt=null,scheduledLocal=null,delivery="PENDING")
                    else r.copy(relativeMinutes=null)
                } else r
            }
            val initialIntent=previous?.takeIf { it.status=="OPEN" || it.status=="APPLIED" && it.baseTaskVersion==target?.formal?.task?.version }?.let(DraftContent::intent)
                ?: if(target==null || old.reminders.isNotEmpty() && old.reminders.all { it.origin=="AUTO" }) "UNSPECIFIED" else "EXPLICIT"
            var intent=initialIntent
            if(op.has("reminderPatch")) {
                val patch=op.getJSONObject("reminderPatch")
                reminders=patchReminders(patch,t,reminders)
                if(intent=="UNSPECIFIED") reminders=reminders.filterNot { it.origin=="AUTO" && it.lastFiredLocal==null }
                intent="EXPLICIT"
            } else if(action=="PATCH" && intent=="UNSPECIFIED" && (target==null || old.task.deadline()==null && t.deadline()!=null)) {
                Rules.defaultReminder(t,now)?.let { candidate -> if(reminders.none { DraftContent.sameDefinition(it,candidate) }) reminders=reminders+candidate }
            }
            if(op.has("startDateAdvice")) require(op.opt("startDateAdvice") is String && op.getString("startDateAdvice") in setOf("ASK","NONE")) { "开始日期建议格式不正确" }
            var decision=previous?.let(DraftContent::decision) ?: "NOT_NEEDED"
            if(f.has("startDate")) decision=if(t.startDate!=null) "DATE" else if(op.optString("startDateAdvice")=="ASK" && decision!="NONE") "UNDECIDED" else "NONE"
            else if(t.startDate!=null) decision="DATE"
            else if(t.dueDate==null) decision="NOT_NEEDED"
            else if(decision !in setOf("NONE","UNDECIDED") && action=="PATCH" &&
                (op.optString("startDateAdvice")=="ASK" || target==null && !op.has("startDateAdvice") && ProposalChoices.mayNeedStart(t.title))) decision="UNDECIDED"
            val b=TaskBundle(t,reminders)
            DraftContent.validatePartial(b)
            val d=TaskDraftEntity(id=previous?.id ?: UUID.randomUUID().toString(),targetTaskId=t.id,
                targetKind=if(target?.formal==null) "NEW" else "SAVED",baseTaskVersion=target?.formal?.task?.version,
                revision=(previous?.revision ?: 0)+1,workingJson=DraftContent.encode(b,if(action=="PATCH") "UPSERT" else action,intent,decision),sourceMessageId=sourceId)
            DraftChange(d,target?.formal)
        }
    }
    private fun definition(o: JSONObject,task: TaskEntity,old: ReminderEntity?=null): ReminderEntity {
        checkKeys(o,setOf("kind","localTime","relativeMinutes","repeat"))
        val kind=o.optString("kind",if(old?.relativeMinutes!=null) "RELATIVE_MINUTES" else "ABSOLUTE_LOCAL")
        require(kind in setOf("RELATIVE_MINUTES","ABSOLUTE_LOCAL")) { "提醒时间类型不正确" }
        val relative=if(kind=="RELATIVE_MINUTES") {
            require(!o.has("localTime")) { "相对提醒不能同时指定独立时间" }
            if(o.has("relativeMinutes")) o.getLong("relativeMinutes") else old?.relativeMinutes ?: error("缺少提前分钟数")
        } else null
        val at=if(relative!=null) (task.deadline() ?: error("提前提醒需要截止日期和时刻")).minusMinutes(relative)
            else { require(!o.has("relativeMinutes")) { "独立提醒不能指定提前分钟数" };LocalDateTime.parse(if(o.has("localTime")) o.getString("localTime") else old?.takeIf { it.relativeMinutes==null }?.localTime ?: error("缺少提醒日期和时间")) }
        val result=ReminderEntity(id=old?.id ?: UUID.randomUUID().toString(),taskId=task.id,localTime=at.toString(),repeat=if(o.has("repeat")) o.getString("repeat") else old?.repeat ?: "NONE",relativeMinutes=relative,origin="USER")
        return if(old!=null && DraftContent.sameDefinition(old,result)) old.copy(origin="USER") else result
    }
    private fun patchReminders(p: JSONObject,t: TaskEntity,old: List<ReminderEntity>): List<ReminderEntity> {
        fun add(list: MutableList<ReminderEntity>,candidate: ReminderEntity) {
            val same=list.indexOfFirst { DraftContent.sameDefinition(it,candidate) }
            if(same>=0) { if(list[same].origin=="AUTO") list[same]=list[same].copy(origin="USER");return }
            require(list.none { it.enabled && it.localTime==candidate.localTime && it.repeat==candidate.repeat }) { "同一提醒时刻存在不同的相对/独立定义，请明确选择" }
            list.add(candidate)
        }
        return when(p.getString("mode")) {
            "CLEAR" -> { checkKeys(p,setOf("mode"));emptyList() }
            "REPLACE_ALL" -> {
                checkKeys(p,setOf("mode","items"));require(p.opt("items") is JSONArray) { "替换提醒需要完整列表" }
                val result=mutableListOf<ReminderEntity>();val ids=mutableSetOf<String>()
                array(p,"items").forEach { row ->
                    val copy=JSONObject(row.toString());val id=copy.nullString("id");copy.remove("id")
                    val existing=id?.let { require(ids.add(it)) { "提醒 ID 重复" };old.find { r -> r.id==it } ?: error("提醒不属于当前事项") }
                    var candidate=definition(copy,t,existing)
                    if(existing==null) old.find { DraftContent.sameDefinition(it,candidate) }?.let { candidate=it.copy(origin="USER") }
                    add(result,candidate)
                };result
            }
            "MERGE" -> {
                checkKeys(p,setOf("mode","add","update","removeIds"))
                if(p.has("removeIds")) require(p.opt("removeIds") is JSONArray) { "removeIds 必须是数组" }
                val removed=p.optJSONArray("removeIds")?.strings().orEmpty()
                require(removed.isNotEmpty() || array(p,"add").isNotEmpty() || array(p,"update").isNotEmpty()) { "空的增量操作不会改变提醒，请省略 reminderPatch" }
                require(removed.distinct().size==removed.size && removed.all { id -> old.any { it.id==id } }) { "要删除的提醒不属于当前事项" }
                val updated=mutableSetOf<String>();val result=old.filterNot { it.id in removed }.toMutableList()
                array(p,"update").forEach { row ->
                    checkKeys(row,setOf("id","changes"));val id=row.getString("id")
                    require(id !in removed && updated.add(id)) { "同一提醒不能重复修改或同时删除" }
                    val index=result.indexOfFirst { it.id==id };require(index>=0) { "要修改的提醒不属于当前事项" }
                    val edits=row.getJSONObject("changes");require(edits.length()>0) { "提醒修改内容为空" }
                    result[index]=definition(edits,t,result[index])
                }
                array(p,"add").forEach { add(result,definition(it,t)) };result
            }
            else -> error("不支持的提醒增量操作")
        }
    }
}
