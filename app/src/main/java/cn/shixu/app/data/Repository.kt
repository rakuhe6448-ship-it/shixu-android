package cn.shixu.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.UUID

class Repository(val db: PlannerDatabase) {
    val dao = db.dao()
    val gate = Mutex()
    var afterChange: suspend () -> Unit = {}

    suspend fun bundle(id: String): TaskBundle? = dao.task(id)?.let { TaskBundle(it,dao.remindersFor(id)) }
    private suspend fun audit(before: TaskBundle?, after: TaskBundle?, action: String, source: String?) {
        dao.putHistory(HistoryEntity(taskId = (after ?: before)!!.task.id, action = action, before = before?.let { JsonCodec.bundle(it).toString() }, after = after?.let { JsonCodec.bundle(it).toString() }, sourceMessageId = source))
    }
    private suspend fun saveInside(newBundle: TaskBundle, expectedVersion: Int?, source: String? = null, action: String = "编辑", preserveDelivery: Boolean = true): TaskBundle {
        val old = bundle(newBundle.task.id)
        check(if (expectedVersion == null) old == null else old?.task?.version == expectedVersion) { "事项已发生变化，请重新打开后再保存" }
        val t = newBundle.task.copy(version = (old?.task?.version ?: 0) + 1, updatedAt = System.currentTimeMillis())
        val merged = newBundle.reminders.map { incoming ->
            val current = old?.reminders?.find { it.id == incoming.id }
            if (preserveDelivery && current != null && current.localTime == incoming.localTime && current.repeat == incoming.repeat && current.relativeMinutes == incoming.relativeMinutes)
                incoming.copy(enabled=current.enabled,lastFiredLocal=current.lastFiredLocal,scheduledAt=current.scheduledAt,scheduledLocal=current.scheduledLocal,delivery=current.delivery)
            else incoming.copy(scheduledAt=null,scheduledLocal=null)
        }
        val b = TaskBundle(t,merged)
        Rules.validate(t,b.reminders)
        dao.putTask(t)
        dao.deleteReminders(t.id)
        b.reminders.forEach { dao.putReminder(it) }
        audit(old,b,if (old == null) "创建" else action,source)
        return b
    }
    suspend fun save(bundle: TaskBundle, expectedVersion: Int?) {
        gate.withLock { db.withTransaction { saveInside(bundle,expectedVersion) } }
        afterChange()
    }
    suspend fun setState(id: String, expectedVersion: Int, action: String) {
        gate.withLock { db.withTransaction { stateInside(id,expectedVersion,action,null) } }
        afterChange()
    }
    private suspend fun stateInside(id: String, expectedVersion: Int, action: String, source: String?): TaskBundle {
        val old = bundle(id) ?: error("事项不存在")
        check(old.task.version == expectedVersion) { "事项已修改，请刷新后操作" }
        val now = System.currentTimeMillis()
        val task = when (action) {
            "COMPLETE" -> { check(old.task.active) { "事项已完成或删除" }; old.task.copy(completedAt = now) }
            "DELETE" -> old.task.copy(deletedAt = now)
            "RESTORE" -> old.task.copy(completedAt = null,deletedAt = null)
            else -> error("未知操作")
        }
        val result = saveInside(TaskBundle(task,old.reminders.map { it.copy(enabled = if(action == "RESTORE") it.repeat != "NONE" || it.lastFiredLocal == null else false, scheduledAt = null, scheduledLocal=null) }),expectedVersion,source,when(action){"COMPLETE" -> "完成"; "DELETE" -> "删除"; else -> "恢复"},preserveDelivery=false)
        if (action == "COMPLETE" && old.task.repeat != "NONE") {
            val anchor = old.task.deadline()!!
            val originalAnchor = old.task.seriesId?.let { dao.task(it)?.deadline() } ?: anchor
            val next = Rules.nextAfter(originalAnchor,old.task.repeat,maxOf(LocalDateTime.now(),anchor))!!
            val seriesId=old.task.seriesId ?: old.task.id
            if(dao.tasks().any { it.active && it.seriesId==seriesId && it.dueDate==next.toLocalDate().toString() }) return result
            val days = java.time.temporal.ChronoUnit.DAYS.between(anchor.toLocalDate(),next.toLocalDate())
            val nextId = UUID.randomUUID().toString()
            val nextTask = old.task.copy(id=nextId,dueDate=next.toLocalDate().toString(),startDate=old.task.startDate?.let { LocalDate.parse(it).plusDays(days).toString() },completedAt=null,deletedAt=null,version=1,createdAt=now,updatedAt=now,seriesId=old.task.seriesId ?: old.task.id)
            val reminders = old.reminders.map { r -> r.copy(id=UUID.randomUUID().toString(),taskId=nextId,localTime=(r.relativeMinutes?.let { next.minusMinutes(it) } ?: LocalDateTime.parse(r.localTime).plusDays(days)).toString(),enabled=true,lastFiredLocal=null,scheduledAt=null,scheduledLocal=null,delivery="PENDING") }
            saveInside(TaskBundle(nextTask,reminders),null,source)
        }
        return result
    }

    suspend fun saveSettings(p: Preferences) {
        require(p.customPrompt.length <= 2000) { "自定义提示词最多 2000 字" }
        require(p.provider in ApiProviders.labels) { "未知 API 类型" }
        gate.withLock { db.withTransaction { (p.entries()+p.apiProfile().entries(p.provider)).forEach { (k,v) -> dao.putSetting(SettingEntity(k,v)) } } }
        afterChange()
    }
    suspend fun apiProfile(provider: String): ApiProfile {
        require(provider in ApiProviders.labels)
        val entries=dao.settings()
        val current=Preferences.from(entries)
        if(current.provider==provider) return current.apiProfile()
        val map=entries.associate { it.key to it.value }
        val prefix="apiProfile.$provider."
        if(prefix+"endpoint" !in map) return ApiProviders.preset(provider)
        return ApiProfile(map[prefix+"endpoint"].orEmpty(),map[prefix+"model"].orEmpty(),map[prefix+"vision"]=="true",map[prefix+"jsonMode"]!="false")
    }

    suspend fun putMessage(message: MessageEntity) { dao.putMessage(message) }
    suspend fun dismissProposal(id: String) {
        gate.withLock { db.withTransaction {
            val m = dao.message(id) ?: error("消息不存在")
            check(m.state in setOf("PENDING","WAITING_INPUT")) { "这条建议已经处理" }
            m.proposal?.let { text ->
                val p=JSONObject(text)
                if(p.optInt("protocolVersion")==2) p.getJSONArray("operations").objects().forEach { op ->
                    dao.draft(op.getString("draftId"))?.takeIf { it.status=="OPEN" && it.revision==op.getInt("expectedRevision") }?.let { draft ->
                        dao.putDraft(draft.copy(status="CANCELLED",revision=draft.revision+1,updatedAt=System.currentTimeMillis()))
                        supersedeDraftCards(setOf(draft.id))
                    }
                }
            }
            dao.putMessage(m.copy(state="DISMISSED"))
        } }
    }

    // Normalized target versions stay immutable; optional start dates are explicit user choices.
    // The entire batch, audit records, source links and applied state commit together.
    suspend fun applyProposal(messageId: String, startDates: Map<String,String?> = emptyMap()) {
        try { gate.withLock { db.withTransaction {
            val message = dao.message(messageId) ?: error("消息不存在")
            check(message.state == "PENDING") { "这条建议已处理，请勿重复提交" }
            check(!cn.shixu.app.ai.PhoneAlarm.isProposal(message.proposal)) { "手机闹钟需要打开手机时钟，不能保存到排期" }
            val resolved = cn.shixu.app.ai.ProposalChoices.resolve(message.proposal ?: error("缺少操作数据"),startDates)
            val envelope = JSONObject(resolved)
            checkProposalDrafts(envelope)
            val sourceId = envelope.getString("sourceId")
            check(dao.message(sourceId) != null) { "原消息不存在" }
            envelope.getJSONArray("operations").objects().forEach { op ->
                val action = op.getString("action")
                val proposed = JsonCodec.readBundle(op.getJSONObject("bundle"))
                val t = proposed.task
                val version = op.nullLong("expectedVersion")?.toInt()
                when(action) {
                    "CREATE" -> {
                        check(dao.tasks().none { it.active && it.title.trim() == t.title.trim() && it.dueDate == t.dueDate }) { "已存在同名同日期事项，请检查后手动编辑" }
                        saveInside(proposed,null,sourceId)
                    }
                    "UPDATE" -> { check(version != null); check(dao.task(t.id)?.active == true) { "事项已完成或删除" }; saveInside(proposed,version,sourceId,"AI 修改") }
                    "COMPLETE","DELETE" -> stateInside(t.id,version ?: error("缺少版本"),action,sourceId)
                    else -> error("不支持的操作")
                }
                commitDraft(op,bundle(t.id) ?: error("保存后的事项不存在"))
                dao.putLink(MessageTask(sourceId,t.id))
                dao.putLink(MessageTask(message.id,t.id))
                dao.message(sourceId)?.quoteId?.let { quoted ->
                    if (dao.message(quoted) != null) dao.putLink(MessageTask(quoted,t.id))
                }
            }
            dao.putMessage(message.copy(state="APPLIED",proposal=resolved))
        } } } catch(e: IllegalStateException) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            runCatching { markDraftConflicts(messageId) }
            throw e
        }
        afterChange()
    }
}
