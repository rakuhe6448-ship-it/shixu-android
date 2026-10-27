package cn.shixu.app.ai

import cn.shixu.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.UUID

object ProposalValidator {
    private val fields = setOf("title","startDate","dueDate","dueTime","category","notes","color","repeat","reminders")
    private fun keys(o: JSONObject) = o.keys().asSequence().toSet()
    fun extract(raw: String): JSONObject {
        require(raw.length <= 60000) { "模型返回内容过长" }
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try { JSONObject(trimmed) } catch (_: Exception) { throw IllegalArgumentException("模型没有返回有效 JSON，请重试或手动录入") }
    }
    fun normalize(raw: JSONObject, sourceId: String, candidates: List<TaskBundle>, quoteTargets: Set<String>, now: LocalDateTime = LocalDateTime.now()): String? {
        require(keys(raw).all { it in setOf("reply","operations","clarification") }) { "模型返回了未知字段" }
        require(!raw.has("operations") || raw.opt("operations") is JSONArray) { "operations 必须是数组" }
        val ops = raw.optJSONArray("operations") ?: JSONArray()
        require(ops.length() <= 10) { "一次最多处理 10 个事项" }
        if (raw.optString("clarification").isNotBlank() || ops.length() == 0) return null
        val seen = mutableSetOf<String>()
        val result = JSONArray()
        ops.objects().forEach { op ->
            require(keys(op).all { it in setOf("action","taskId","version","changes","startDateAdvice") }) { "操作字段不受支持" }
            if(op.has("startDateAdvice")) require(op.opt("startDateAdvice") is String && op.getString("startDateAdvice") in setOf("ASK","NONE")) { "开始日期建议格式不正确" }
            val action = op.getString("action")
            require(action in setOf("CREATE","UPDATE","COMPLETE","DELETE")) { "不支持的 AI 操作" }
            val changes = op.optJSONObject("changes") ?: JSONObject()
            require(keys(changes).all { it in fields }) { "操作包含不允许修改的字段" }
            val old = if(action != "CREATE") {
                val id = op.getString("taskId")
                require(seen.add(id)) { "同一批操作中不能重复修改同一个事项" }
                require(quoteTargets.isEmpty() || id in quoteTargets) { "目标与引用消息关联的事项不一致，请明确选择" }
                candidates.find { it.task.id == id }?.also {
                    require(it.task.active) { "目标事项已完成或删除" }
                    require(op.getInt("version") == it.task.version) { "目标版本不正确，请重新解析" }
                } ?: throw IllegalArgumentException("AI 选择的事项不在本次相关事项中，请引用具体任务")
            } else null
            var task = old?.task ?: TaskEntity(title="")
            var reminders = old?.reminders ?: emptyList()
            if(action == "CREATE" || action == "UPDATE") {
                val beforeDue = task.deadline()
                task = task.copy(
                    title=if(changes.has("title")) changes.getString("title").trim() else task.title,
                    startDate=if(changes.has("startDate")) changes.nullString("startDate") else task.startDate,
                    dueDate=if(changes.has("dueDate")) changes.nullString("dueDate") else task.dueDate,
                    dueTime=if(changes.has("dueTime")) changes.nullString("dueTime") else task.dueTime,
                    category=if(changes.has("category")) changes.getString("category").trim() else if(old==null) ProposalChoices.category(changes.optString("title")) else task.category,
                    notes=if(changes.has("notes")) changes.getString("notes") else task.notes,
                    color=if(changes.has("color")) changes.getLong("color") else task.color,
                    repeat=if(changes.has("repeat")) changes.getString("repeat") else task.repeat
                )
                if(changes.has("reminders")) {
                    reminders = changes.getJSONArray("reminders").objects().map { r ->
                        require(keys(r).all { it in setOf("localTime","repeat","relativeMinutes") }) { "提醒字段不正确" }
                        val relative = r.nullLong("relativeMinutes")
                        val at = if(relative != null) (task.deadline() ?: error("提前提醒需要截止日期")).minusMinutes(relative) else LocalDateTime.parse(r.getString("localTime"))
                        ReminderEntity(taskId=task.id,localTime=at.toString(),repeat=r.optString("repeat","NONE"),relativeMinutes=relative)
                    }
                } else if(old == null) {
                    reminders = listOfNotNull(Rules.defaultReminder(task,now))
                } else if(beforeDue != task.deadline()) {
                    reminders = reminders.map { r ->
                        if(r.relativeMinutes != null && task.deadline() != null) r.copy(localTime=task.deadline()!!.minusMinutes(r.relativeMinutes).toString(),lastFiredLocal=null,scheduledAt=null,enabled=true,delivery="PENDING")
                        else if(r.relativeMinutes != null) r.copy(relativeMinutes=null)
                        else r
                    }
                }
                Rules.validate(task,reminders)
                if(action == "CREATE") require(candidates.none { it.task.active && it.task.title.trim() == task.title && it.task.dueDate == task.dueDate }) { "相关事项中已有同名同日期任务，请引用它进行修改" }
            } else require(changes.length() == 0) { "完成或删除操作不能同时修改字段" }
            val askStart=action in setOf("CREATE","UPDATE") && task.startDate==null && task.dueDate!=null &&
                (if(op.has("startDateAdvice")) op.getString("startDateAdvice")=="ASK" else action=="CREATE" && ProposalChoices.mayNeedStart(task.title))
            result.put(JSONObject().put("action",action).put("needsStartDate",askStart).put("expectedVersion",old?.task?.version ?: JSONObject.NULL)
                .put("before",old?.let(JsonCodec::bundle) ?: JSONObject.NULL).put("bundle",JsonCodec.bundle(TaskBundle(task,reminders))))
        }
        return JSONObject().put("id",UUID.randomUUID().toString()).put("sourceId",sourceId).put("operations",result).toString()
    }
}
