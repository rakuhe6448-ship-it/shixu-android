package cn.shixu.app.data

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.nullString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
fun JSONObject.nullLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

object JsonCodec {
    fun draft(d: TaskDraftEntity)=JSONObject().put("id",d.id).put("targetTaskId",d.targetTaskId).put("targetKind",d.targetKind)
        .put("baseTaskVersion",d.baseTaskVersion ?: JSONObject.NULL).put("revision",d.revision).put("status",d.status)
        .put("workingJson",d.workingJson).put("sourceMessageId",d.sourceMessageId).put("updatedAt",d.updatedAt)
    fun readDraft(o: JSONObject)=TaskDraftEntity(id=o.getString("id"),targetTaskId=o.getString("targetTaskId"),targetKind=o.getString("targetKind"),
        baseTaskVersion=o.nullLong("baseTaskVersion")?.toInt(),revision=o.getInt("revision"),status=o.getString("status"),
        workingJson=o.getString("workingJson"),sourceMessageId=o.getString("sourceMessageId"),updatedAt=o.getLong("updatedAt"))
    fun task(t: TaskEntity) = JSONObject().apply {
        put("id", t.id); put("title", t.title); put("startDate", t.startDate ?: JSONObject.NULL)
        put("dueDate", t.dueDate ?: JSONObject.NULL); put("dueTime", t.dueTime ?: JSONObject.NULL)
        put("color", t.color); put("category", t.category); put("notes", t.notes); put("repeat", t.repeat)
        put("completedAt", t.completedAt ?: JSONObject.NULL); put("deletedAt", t.deletedAt ?: JSONObject.NULL)
        put("version", t.version); put("createdAt", t.createdAt); put("updatedAt", t.updatedAt); put("seriesId", t.seriesId ?: JSONObject.NULL)
    }
    fun readTask(o: JSONObject) = TaskEntity(id=o.getString("id"), title=o.getString("title"), startDate=o.nullString("startDate"), dueDate=o.nullString("dueDate"), dueTime=o.nullString("dueTime"), color=o.getLong("color"), category=o.getString("category"), notes=o.getString("notes"), repeat=o.getString("repeat"), completedAt=o.nullLong("completedAt"), deletedAt=o.nullLong("deletedAt"), version=o.getInt("version"), createdAt=o.getLong("createdAt"), updatedAt=o.getLong("updatedAt"), seriesId=o.nullString("seriesId"))
    fun reminder(r: ReminderEntity) = JSONObject().apply {
        put("id", r.id); put("taskId", r.taskId); put("localTime", r.localTime); put("repeat", r.repeat)
        put("relativeMinutes", r.relativeMinutes ?: JSONObject.NULL); put("enabled", r.enabled)
        put("lastFiredLocal", r.lastFiredLocal ?: JSONObject.NULL); put("scheduledAt", r.scheduledAt ?: JSONObject.NULL); put("scheduledLocal", r.scheduledLocal ?: JSONObject.NULL); put("delivery", r.delivery); put("origin",r.origin)
    }
    fun readReminder(o: JSONObject) = ReminderEntity(id=o.getString("id"), taskId=o.getString("taskId"), localTime=o.getString("localTime"), repeat=o.getString("repeat"), relativeMinutes=o.nullLong("relativeMinutes"), enabled=o.getBoolean("enabled"), lastFiredLocal=o.nullString("lastFiredLocal"), scheduledAt=o.nullLong("scheduledAt"), scheduledLocal=o.nullString("scheduledLocal"), delivery=o.getString("delivery"),origin=o.optString("origin","UNKNOWN"))
    fun bundle(b: TaskBundle) = JSONObject().put("task", task(b.task)).put("reminders", JSONArray(b.reminders.map(::reminder)))
    fun readBundle(o: JSONObject) = TaskBundle(readTask(o.getJSONObject("task")), o.getJSONArray("reminders").objects().map(::readReminder))
    fun message(m: MessageEntity) = JSONObject().apply {
        put("id",m.id); put("role",m.role); put("text",m.text); put("imagePaths",JSONArray(m.imagePaths)); put("quoteId",m.quoteId ?: JSONObject.NULL)
        put("proposal",m.proposal ?: JSONObject.NULL); put("state",m.state); put("createdAt",m.createdAt); put("sharedText",m.sharedText)
    }
    fun readMessage(o: JSONObject) = MessageEntity(o.getString("id"),o.getString("role"),o.getString("text"),o.getJSONArray("imagePaths").toString(),o.nullString("quoteId"),o.nullString("proposal"),o.getString("state"),o.getLong("createdAt"),o.optString("sharedText",""))
}
