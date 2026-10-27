package cn.shixu.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.shixu.app.ai.*
import cn.shixu.app.data.*
import cn.shixu.app.widgets.WidgetRenderer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

class PlannerViewModel(application: Application) : AndroidViewModel(application) {
    val app=application as ShiXuApp
    val repo=app.repository
    var preferencesLoaded by mutableStateOf(false)
        private set
    var apiStorageError by mutableStateOf<String?>(null)
        private set
    val tasks=repo.dao.observeTasks().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val reminders=repo.dao.observeReminders().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val messages=repo.dao.observeMessages().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val links=repo.dao.observeLinks().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val drafts=repo.dao.observeDrafts().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val preferences=repo.dao.observeSettings().map { entries ->
        val p=Preferences.from(entries)
        try { withContext(Dispatchers.IO) { app.vault.migrateLegacy(p.provider) } } catch(e: Exception) { apiStorageError=e.message }
        preferencesLoaded=true
        p
    }.stateIn(viewModelScope,SharingStarted.Eagerly,Preferences())
    private val eventChannel=Channel<String>(Channel.BUFFERED)
    val events=eventChannel.receiveAsFlow()
    var page by mutableIntStateOf(0)
    var selectedDate by mutableStateOf(LocalDate.now())
    var month by mutableStateOf(YearMonth.now())
    var detailsId by mutableStateOf<String?>(null)
    var editor by mutableStateOf<TaskBundle?>(null)
    var editorVersion by mutableStateOf<Int?>(null)
    var draft by mutableStateOf("")
    var draftSharedText by mutableStateOf("")
    var draftImages by mutableStateOf<List<String>>(emptyList())
    var quoteId by mutableStateOf<String?>(null)
    var aiBusy by mutableStateOf(false)
    var attachmentBusy by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var apiTesting by mutableStateOf(false)
    var modelLoading by mutableStateOf(false)
        private set
    var availableModels by mutableStateOf<List<ModelOption>>(emptyList())
        private set
    var modelError by mutableStateOf<String?>(null)
        private set
    private var modelJob: Job?=null
    private var modelRequest=0L
    private val onboarding=app.getSharedPreferences("device_onboarding",android.content.Context.MODE_PRIVATE)
    var permissionIntroComplete by mutableStateOf(onboarding.getBoolean("permissions_reviewed",false))
        private set
    var permissionTick by mutableIntStateOf(0)
    var importPreview by mutableStateOf<BackupData?>(null)
    var dataBusy by mutableStateOf(false)
    private val ai=AiClient()
    private val backup=Backup(app)
    fun notify(text: String) { viewModelScope.launch { eventChannel.send(text) } }
    private fun operation(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch(e: Exception) { if(e is CancellationException) throw e; notify(e.message ?: "操作失败，请重试") } } }
    fun refreshPermissions() { permissionTick++; operation { repo.afterChange() } }
    fun showPermissionIntro() { permissionIntroComplete=false;refreshPermissions() }
    fun finishPermissionIntro(manualOnly: Boolean=false) = operation {
        require(manualOnly || app.alarms.exactAllowed() && app.alarms.notificationsAllowed()) { "请先开启通知和精确提醒，或选择暂时只用排期" }
        withContext(Dispatchers.IO) { check(onboarding.edit().putBoolean("permissions_reviewed",true).commit()) { "引导设置保存失败，请重试" } }
        permissionIntroComplete=true
        if(manualOnly) notify("已进入排期；需要提醒时，可在提醒页补齐权限")
    }
    fun newTask(date: LocalDate=selectedDate) {
        editor=TaskBundle(TaskEntity(title="",dueDate=date.toString()),emptyList()); editorVersion=null
    }
    fun editTask(id: String) = operation { editor=repo.bundle(id) ?: error("事项不存在"); editorVersion=editor!!.task.version; detailsId=null }
    fun saveTask(bundle: TaskBundle) {
        if(saving) return
        saving=true
        viewModelScope.launch {
            try {
                val previous=repo.bundle(bundle.task.id)
                val manual=bundle.copy(reminders=bundle.reminders.map { r ->
                    val old=previous?.reminders?.find { it.id==r.id }
                    if(old!=null && DraftContent.sameDefinition(old,r)) r.copy(origin=old.origin) else r.copy(origin="USER")
                })
                repo.save(manual,editorVersion); editor=null; notify("已保存，排期和提醒已同步")
            }
            catch(e: Exception) { notify(e.message ?: "保存失败，内容仍保留在编辑器中") }
            finally { saving=false }
        }
    }
    fun taskAction(task: TaskEntity, action: String) = operation {
        repo.setState(task.id,task.version,action); detailsId=null
        notify(when(action){"COMPLETE" -> if(task.repeat=="NONE") "又完成一件事，做得好 ✨" else "这一期完成了 ✨ 下一期已加入排期"; "DELETE" -> "事项已删除，相关提醒已取消"; else -> "事项已恢复"})
    }
    fun quote(message: MessageEntity) { quoteId=message.id; page=3 }
    fun discussTask(task: TaskEntity) = operation {
        val message=MessageEntity(role="assistant",text="事项：${task.title}\n${cn.shixu.app.ui.taskDateLabel(task)}\n可以引用这张卡片，补充或修改安排。")
        repo.putMessage(message)
        repo.dao.putLink(MessageTask(message.id,task.id))
        detailsId=null
        quoteId=message.id
        page=3
    }
    fun attach(uri: Uri) {
        if(draftImages.size>=4 || attachmentBusy) { notify("每次最多添加 4 张图片"); return }
        attachmentBusy=true
        viewModelScope.launch { try { draftImages=draftImages+Attachments.import(app,uri); page=3 } catch(e: Exception) { notify(e.message ?: "图片导入失败") } finally { attachmentBusy=false } }
    }
    fun receiveShare(text: String?, uris: List<Uri>) {
        page=3
        if(!text.isNullOrBlank()) draftSharedText=text.take(12000)
        operation {
            attachmentBusy=true
            try { uris.take((4-draftImages.size).coerceAtLeast(0)).forEach { draftImages=draftImages+Attachments.import(app,it) } }
            finally { attachmentBusy=false }
        }
    }
    fun send() {
        if(aiBusy || attachmentBusy || draft.isBlank() && draftImages.isEmpty() && draftSharedText.isBlank()) return
        val shared=draftSharedText
        val text=draft.trim().ifBlank { if(shared.isNotBlank()) "请识别这条分享内容中的事项。" else "请识别图片中的事项。" }; val images=draftImages; val quote=quoteId
        if(text.length>12000) { notify("单条消息最多 12000 字"); return }
        aiBusy=true
        viewModelScope.launch {
            var source: MessageEntity?=null
            try {
                val p=preferences.value
                val key=app.vault.read(p.provider)
                require(key.isNotBlank()) { "请先在设置中配置 API；排期、手动录入和提醒可以离线使用" }
                val current=MessageEntity(role="user",text=text,imagePaths=JSONArray(images).toString(),quoteId=quote,sharedText=shared)
                repo.putMessage(current); source=current
                draft=""; draftSharedText=""; draftImages=emptyList(); quoteId=null
                val context=repo.conversationContext(current)
                require(!context.unresolvedQuote) { "引用消息还没有关联明确事项，请选择待确认草稿，或从任务详情继续修改" }
                val ocr=if(images.isNotEmpty()) try { Attachments.ocr(app,images) } catch(e: Exception) { if(p.vision) "" else throw IllegalStateException("本地图片识别失败，请尝试清晰截图或改用支持图片的模型") } else ""
                require(images.isEmpty() || p.vision || ocr.isNotBlank()) { "图片中没有识别到文字，请补充说明或开启图片模型" }
                val raw=ai.parse(p,key,current,context,images.map { Attachments.file(app,it) },ocr)
                val reply=when(p.personality) {
                    "ROYAL" -> "陛下，安排已整理在下方，待您核对事项与时辰后定夺。"
                    "CAT" -> "喵～我把安排放到卡片里啦！核对事项和时间后，再点确认哦。"
                    "BUTLER" -> "安排建议已整理。请核对下列事项与提醒时间，确认后保存。"
                    else -> "已整理为下面的操作建议，请核对事项和时间后确认。"
                }
                val answer=repo.acceptAiResult(raw,current.id,context,reply)
                if(answer.state=="PENDING" && answer.proposal!=null && ProposalChoices.pending(answer.proposal).isNotEmpty()) startDateMessage=answer
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                val error=e.message ?: "解析失败，请重试"
                if(source!=null) repo.putMessage(MessageEntity(role="assistant",text="未执行任何任务操作。$error",quoteId=source.id,state="ERROR"))
                else notify(error)
            } finally { aiBusy=false }
        }
    }
    var startDateMessage by mutableStateOf<MessageEntity?>(null)
    var proposalSaving by mutableStateOf(false)
        private set
    var proposalError by mutableStateOf<String?>(null)
        private set
    fun requestApplyProposal(message: MessageEntity) {
        proposalError=null
        if(message.proposal!=null && cn.shixu.app.ai.ProposalChoices.pending(message.proposal).isNotEmpty()) startDateMessage=message
        else applyProposal(message.id)
    }
    fun applyProposal(id: String, startDates: Map<String,String?> = emptyMap()) {
        if(proposalSaving) return
        proposalSaving=true;proposalError=null
        operation { try {
            repo.applyProposal(id,startDates);startDateMessage=null;notify("已保存到排期，提醒已同步")
        } catch(e: Exception) { if(repo.dao.message(id)?.state=="APPLIED") startDateMessage=null;proposalError=e.message ?: "保存失败，请重新核对卡片";throw e
        } finally { proposalSaving=false } }
    }
    fun dismissProposal(id: String) = operation { repo.dismissProposal(id) }
    fun reviewDraft(id: String) = operation { repo.previewDraft(id);page=3 }
    fun cancelDraft(id: String) = operation { repo.cancelDraft(id);notify("已取消草稿，已保存事项保持原状态") }
    fun saveSettings(p: Preferences,key: String) = operation {
        if(key.isNotBlank()) { ApiProtocol.validKey(key);ApiProtocol.chatEndpoint(p.endpoint) }
        app.vault.write(p.provider,key)
        repo.saveSettings(p.copy(endpoint=p.endpoint.trim(),model=p.model.trim()))
        apiStorageError=null
        notify("设置已保存")
    }
    suspend fun apiProfile(provider: String): Pair<ApiProfile,String> = withContext(Dispatchers.IO) { repo.apiProfile(provider) to app.vault.read(provider) }
    fun clearModels() {
        modelRequest++;modelJob?.cancel();modelJob=null
        modelLoading=false;availableModels=emptyList();modelError=null
    }
    fun loadModels(endpoint: String,key: String) {
        clearModels()
        val request=modelRequest
        modelLoading=true
        modelJob=viewModelScope.launch {
            try { val result=ai.models(endpoint,key);if(request==modelRequest) availableModels=result }
            catch(e: Exception) { if(e is CancellationException) throw e;if(request==modelRequest) modelError=e.message ?: "获取模型失败，可手动填写模型名称" }
            finally { if(request==modelRequest) modelLoading=false }
        }
    }
    fun testApi(p: Preferences,key: String) {
        if(apiTesting) return
        apiTesting=true
        viewModelScope.launch { try { notify(ai.test(p,key)) } catch(e: Exception) { notify(e.message ?: "连接测试失败") } finally { apiTesting=false } }
    }
    fun exportBackup(uri: Uri) {
        dataBusy=true
        operation { try { backup.export(uri); notify("备份已导出，包含聊天图片，不包含 API Key") } finally { dataBusy=false } }
    }
    fun previewImport(uri: Uri) {
        dataBusy=true
        operation { try { importPreview=backup.read(uri) } finally { dataBusy=false } }
    }
    fun restoreBackup() {
        val data=importPreview ?: return
        dataBusy=true
        operation { try { backup.restore(data); importPreview=null; detailsId=null; editor=null; notify("恢复完成，已重新安排提醒；API 连接与 Key 保持本机配置") } finally { dataBusy=false } }
    }
}
