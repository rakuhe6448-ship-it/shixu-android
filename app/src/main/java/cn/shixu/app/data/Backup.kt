package cn.shixu.app.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import cn.shixu.app.ShiXuApp
import cn.shixu.app.ai.Attachments
import cn.shixu.app.ai.DraftContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.UUID

data class BackupData(val tasks: List<TaskEntity>,val reminders: List<ReminderEntity>,val messages: List<MessageEntity>,val links: List<MessageTask>,val history: List<HistoryEntity>,val settings: List<SettingEntity>,val images: Map<String,ByteArray>,val drafts: List<TaskDraftEntity> = emptyList(),val draftLinks: List<MessageDraft> = emptyList())

class Backup(private val app: ShiXuApp) {
    private val repo = app.repository
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val root = repo.gate.withLock { repo.db.withTransaction {
            JSONObject().put("format","shixu").put("version",2).put("exportedAt",System.currentTimeMillis())
                .put("tasks",JSONArray(repo.dao.tasks().map(JsonCodec::task)))
                .put("reminders",JSONArray(repo.dao.reminders().map(JsonCodec::reminder)))
                .put("messages",JSONArray(repo.dao.messages().map(JsonCodec::message)))
                .put("drafts",JSONArray(repo.dao.drafts().map(JsonCodec::draft)))
                .put("draftLinks",JSONArray(repo.dao.draftLinks().map { JSONObject().put("messageId",it.messageId).put("draftId",it.draftId) }))
                .put("links",JSONArray(repo.dao.links().map { JSONObject().put("messageId",it.messageId).put("taskId",it.taskId) }))
                .put("history",JSONArray(repo.dao.history().map { JSONObject().put("id",it.id).put("taskId",it.taskId).put("action",it.action).put("before",it.before ?: JSONObject.NULL).put("after",it.after ?: JSONObject.NULL).put("sourceMessageId",it.sourceMessageId ?: JSONObject.NULL).put("at",it.at) }))
                .put("settings",JSONObject(Preferences.from(repo.dao.settings()).entries()))
        } }
        val images = JSONObject()
        val names = root.getJSONArray("messages").objects().flatMap { it.getJSONArray("imagePaths").strings() }.distinct()
        var size = 0
        names.forEach { name ->
            val file=Attachments.file(app,name)
            if(file.exists()) {
                val bytes=file.readBytes(); size+=bytes.size
                require(size <= 30*1024*1024) { "聊天图片超过 30 MB，本版单个备份大小受限" }
                images.put(name,Base64.encodeToString(bytes,Base64.NO_WRAP))
            }
        }
        root.put("images",images)
        val bytes=root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 50*1024*1024) { "备份超过 50 MB" }
        app.contentResolver.openOutputStream(uri,"wt")?.use { it.write(bytes) } ?: error("无法写入备份文件")
    }
    suspend fun read(uri: Uri): BackupData = withContext(Dispatchers.IO) {
        val root = app.contentResolver.openInputStream(uri)?.use { JSONObject(String(it.readBounded(50*1024*1024),Charsets.UTF_8)) } ?: error("无法读取备份")
        require(root.getString("format")=="shixu" && root.getInt("version") in setOf(1,2)) { "不支持的备份格式或版本" }
        val tasks=root.getJSONArray("tasks").objects().map(JsonCodec::readTask)
        val reminders=root.getJSONArray("reminders").objects().map { JsonCodec.readReminder(it).copy(scheduledAt=null) }
        // Pending commands are intentionally invalidated on restore; they need fresh target checks.
        val messages=root.getJSONArray("messages").objects().map { JsonCodec.readMessage(it).let { m -> if(m.state in setOf("PENDING","WAITING_INPUT")) m.copy(state="DISMISSED") else m } }
        val drafts=if(root.getInt("version")==2) root.getJSONArray("drafts").objects().map(JsonCodec::readDraft) else emptyList()
        val draftLinks=if(root.getInt("version")==2) root.getJSONArray("draftLinks").objects().map { MessageDraft(it.getString("messageId"),it.getString("draftId")) } else emptyList()
        val links=root.getJSONArray("links").objects().map { MessageTask(it.getString("messageId"),it.getString("taskId")) }
        val history=root.getJSONArray("history").objects().map { HistoryEntity(it.getString("id"),it.getString("taskId"),it.getString("action"),it.nullString("before"),it.nullString("after"),it.nullString("sourceMessageId"),it.getLong("at")) }
        val settings=root.getJSONObject("settings").let { o -> o.keys().asSequence().filter { it in Preferences().entries() }.map { SettingEntity(it,o.getString(it)) }.toList() }
        require(tasks.size <= 20000 && drafts.size<=20000 && draftLinks.size<=100000 && messages.size <= 30000 && reminders.size <= 100000 && history.size <= 100000) { "备份记录过多" }
        fun validIds(ids: List<String>) { require(ids.distinct().size==ids.size) { "备份有重复 ID" }; ids.forEach { UUID.fromString(it) } }
        validIds(tasks.map { it.id }); validIds(reminders.map { it.id }); validIds(messages.map { it.id }); validIds(history.map { it.id })
        validIds(drafts.map { it.id });validIds(drafts.map { it.targetTaskId })
        val taskIds=tasks.map { it.id }.toSet(); val messageIds=messages.map { it.id }.toSet()
        val remindersByTask=reminders.groupBy { it.taskId }
        tasks.forEach { require(it.version>0); Rules.validate(it,remindersByTask[it.id].orEmpty()) }
        reminders.forEach { require(it.taskId in taskIds) { "提醒关联缺失" }; it.lastFiredLocal?.let(LocalDateTime::parse); it.scheduledLocal?.let(LocalDateTime::parse) }
        messages.forEach {
            require(it.role in setOf("user","assistant") && it.text.length<=60000 && it.sharedText.length<=12000 && it.state in setOf("SENT","PENDING","APPLIED","DISMISSED","ERROR","WAITING_INPUT","SUPERSEDED","CONFLICT")) { "聊天记录格式错误" }
            require(it.quoteId==null || it.quoteId in messageIds) { "引用消息缺失" }
            it.proposal?.let { proposal ->
                val ops=JSONObject(proposal).getJSONArray("operations")
                require(ops.length()<=10)
                ops.objects().forEach { op ->
                    require(op.getString("action") in setOf("CREATE","UPDATE","COMPLETE","DELETE"))
                    val b=JsonCodec.readBundle(op.getJSONObject("bundle")); DraftContent.validatePartial(b)
                    if(!op.isNull("before")) { val old=JsonCodec.readBundle(op.getJSONObject("before")); Rules.validate(old.task,old.reminders) }
                }
            }
        }
        links.forEach { require(it.taskId in taskIds && it.messageId in messageIds) { "聊天关联缺失" } }
        history.forEach { require(it.taskId in taskIds) { "历史记录关联缺失" } }
        drafts.forEach { d ->
            require(d.targetKind in setOf("NEW","SAVED") && d.status in setOf("OPEN","APPLIED","CANCELLED","CONFLICT") && d.revision>0 && d.sourceMessageId in messageIds) { "草稿格式不正确" }
            require(if(d.targetKind=="NEW") d.baseTaskVersion==null && d.targetTaskId !in taskIds else d.baseTaskVersion!=null && d.baseTaskVersion>0 && d.targetTaskId in taskIds) { "草稿目标不正确" }
            require(JSONObject(d.workingJson).getInt("formatVersion")==1 && DraftContent.action(d) in setOf("UPSERT","COMPLETE","DELETE") && DraftContent.intent(d) in setOf("UNSPECIFIED","EXPLICIT") && DraftContent.decision(d) in setOf("NOT_NEEDED","UNDECIDED","DATE","NONE")) { "草稿版本或状态不正确" }
            val b=DraftContent.bundle(d);require(b.task.id==d.targetTaskId);DraftContent.validatePartial(b)
        }
        val draftIds=drafts.map { it.id }.toSet()
        require(draftLinks.distinct().size==draftLinks.size)
        draftLinks.forEach { require(it.draftId in draftIds && it.messageId in messageIds) { "草稿消息关联缺失" } }
        val images=mutableMapOf<String,ByteArray>()
        val imageObject=root.optJSONObject("images") ?: JSONObject()
        var imageBytes=0
        imageObject.keys().forEach { name ->
            Attachments.file(app,name)
            val bytes=Base64.decode(imageObject.getString(name),Base64.NO_WRAP)
            imageBytes+=bytes.size
            require(bytes.size<=5*1024*1024 && imageBytes<=30*1024*1024) { "图片备份过大" }
            val dimensions=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,dimensions)
            require(dimensions.outWidth in 1..4096 && dimensions.outHeight in 1..4096) { "备份包含无效图片" }
            images[name]=bytes
        }
        messages.forEach { m -> JSONArray(m.imagePaths).strings().forEach { require(it in images) { "备份缺少聊天图片" } } }
        val p=Preferences.from(settings)
        require(p.theme in setOf("SYSTEM","LIGHT","DARK") && p.personality in Personality.styles && p.customPrompt.length<=2000) { "设置格式不正确" }
        BackupData(tasks,reminders,messages,links,history,settings,images,drafts,draftLinks)
    }
    suspend fun restore(data: BackupData) = withContext(Dispatchers.IO) {
        val renamed=mutableMapOf<String,String>()
        var committed=false
        val oldReminders=repo.dao.reminders()
        try {
            data.images.forEach { (old,bytes) ->
                val name=UUID.randomUUID().toString()+".jpg"
                Attachments.file(app,name).writeBytes(bytes)
                renamed[old]=name
            }
            repo.gate.withLock { repo.db.withTransaction {
                val localSettings=repo.dao.settings()
                val localConnection=localSettings.filter { it.key.startsWith("apiProfile.") }.associate { it.key to it.value } + Preferences.from(localSettings).entries().filterKeys { it in setOf("provider","endpoint","model","vision","jsonMode") }
                repo.dao.clearDraftLinks();repo.dao.clearDrafts();repo.dao.clearLinks(); repo.dao.clearMessages(); repo.dao.clearHistory(); repo.dao.clearReminders(); repo.dao.clearTasks(); repo.dao.clearSettings()
                data.tasks.forEach { repo.dao.putTask(it) }
                data.reminders.forEach { repo.dao.putReminder(it) }
                data.messages.forEach { m -> repo.dao.putMessage(m.copy(imagePaths=JSONArray(JSONArray(m.imagePaths).strings().map { renamed.getValue(it) }).toString())) }
                data.links.forEach { repo.dao.putLink(it) }; data.history.forEach { repo.dao.putHistory(it) }; data.settings.forEach { repo.dao.putSetting(it) }
                data.drafts.forEach { d -> repo.dao.putDraft(if(d.status in setOf("OPEN","CONFLICT")) d.copy(status="CONFLICT",revision=d.revision+1) else d) }
                data.draftLinks.forEach { repo.dao.putDraftLink(it) }
                // Imported files cannot redirect an existing local API Key to another service.
                localConnection.forEach { (k,v) -> repo.dao.putSetting(SettingEntity(k,v)) }
            } }
            committed=true
            oldReminders.forEach { app.alarms.cancel(it.id) }
        } catch(e: Exception) {
            if(!committed) renamed.values.forEach { Attachments.file(app,it).delete() }
            throw e
        }
        repo.afterChange()
    }
}
