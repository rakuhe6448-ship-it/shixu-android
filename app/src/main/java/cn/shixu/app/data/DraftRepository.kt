package cn.shixu.app.data

import androidx.room.withTransaction
import cn.shixu.app.ai.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

suspend fun Repository.conversationContext(current: MessageEntity): ConversationContext = gate.withLock {
    db.withTransaction { ConversationTargetResolver.resolve(this@conversationContext,current) }
}

internal suspend fun Repository.supersedeDraftCards(ids: Set<String>,except: String?=null) {
    dao.messages().filter { it.id!=except && it.state in setOf("PENDING","WAITING_INPUT") && it.proposal!=null }.forEach { m ->
        val p=JSONObject(m.proposal!!)
        if(p.optInt("protocolVersion")==2 && p.getJSONArray("operations").objects().any { it.getString("draftId") in ids }) dao.putMessage(m.copy(state="SUPERSEDED"))
    }
}

suspend fun Repository.acceptAiResult(raw: JSONObject,sourceId: String,context: ConversationContext,proposalReply: String): MessageEntity = gate.withLock {
    db.withTransaction {
        val responseId=UUID.nameUUIDFromBytes("shixu-ai-result:$sourceId".toByteArray(Charsets.UTF_8)).toString()
        dao.message(responseId)?.let { return@withTransaction it }
        check(dao.message(sourceId)!=null) { "原消息不存在" }
        val changes=DraftEngine.normalize(raw,sourceId,context)
        val used=changes.map { it.draft.targetTaskId }.toSet()
        context.targets.filter { it.bundle.task.id in used }.forEach { target ->
            check(dao.draftForTask(target.bundle.task.id)?.revision==target.draft?.revision) { "草稿已更新，请重新发送这条补充" }
            check(dao.task(target.bundle.task.id)?.version==target.formal?.task?.version) { "事项已变化，请重新核对" }
        }
        val proposal=changes.takeIf { it.isNotEmpty() }?.let { DraftContent.proposal(sourceId,it) }
        val answer=MessageEntity(id=responseId,role="assistant",text=if(proposal!=null) proposalReply else
            "本条回复未更改排期。\n"+raw.optString("clarification").ifBlank { raw.optString("reply","请补充事项信息。") }.take(4000),
            quoteId=sourceId,proposal=proposal,state=if(changes.isEmpty()) "SENT" else if(changes.all { DraftContent.ready(DraftContent.bundle(it.draft)) }) "PENDING" else "WAITING_INPUT")
        changes.forEach { dao.putDraft(it.draft) }
        supersedeDraftCards(changes.map { it.draft.id }.toSet())
        dao.putMessage(answer)
        val clarificationTarget=context.targets.find { it.ref==raw.optString("clarificationTarget") }
            ?: context.targets.singleOrNull()?.takeIf { context.quoteBound }
        val associated=if(changes.isNotEmpty()) changes.map { it.draft } else clarificationTarget?.draft?.takeIf { it.status=="OPEN" }?.let(::listOf).orEmpty()
        associated.forEach { draft ->
            dao.putDraftLink(MessageDraft(sourceId,draft.id));dao.putDraftLink(MessageDraft(answer.id,draft.id))
            if(draft.targetKind=="SAVED") { dao.putLink(MessageTask(sourceId,draft.targetTaskId));dao.putLink(MessageTask(answer.id,draft.targetTaskId)) }
        }
        // A clarification about a uniquely resolved saved task should also retain its identity.
        if(changes.isEmpty() && associated.isEmpty()) clarificationTarget?.formal?.task?.let { task ->
            dao.putLink(MessageTask(sourceId,task.id));dao.putLink(MessageTask(answer.id,task.id))
        }
        answer
    }
}

internal suspend fun Repository.checkProposalDrafts(envelope: JSONObject) {
    if(envelope.optInt("protocolVersion")!=2) return
    envelope.getJSONArray("operations").objects().forEach { op ->
        val draft=dao.draft(op.getString("draftId")) ?: error("草稿不存在")
        check(draft.status=="OPEN" && draft.revision==op.getInt("expectedRevision")) { "卡片已过期，请打开最新草稿" }
        val proposed=JsonCodec.readBundle(op.getJSONObject("bundle"))
        check(proposed.task.id==draft.targetTaskId) { "卡片目标与草稿不一致" }
        val actual=dao.task(draft.targetTaskId)
        check(if(draft.targetKind=="NEW") actual==null else actual?.version==draft.baseTaskVersion && actual?.active==true) { "事项已修改、完成或删除；旧卡片不能覆盖，请取消旧修改后重试" }
    }
}

internal suspend fun Repository.commitDraft(op: JSONObject,bundle: TaskBundle) {
    if(!op.has("draftId")) return
    val draft=dao.draft(op.getString("draftId")) ?: error("草稿不存在")
    val decision=if(op.has("startDateChoice")) if(bundle.task.startDate==null) "NONE" else "DATE" else DraftContent.decision(draft)
    dao.putDraft(draft.copy(targetKind="SAVED",baseTaskVersion=bundle.task.version,revision=draft.revision+1,status="APPLIED",
        workingJson=DraftContent.encode(bundle,"UPSERT",DraftContent.intent(draft),decision),updatedAt=System.currentTimeMillis()))
    dao.messagesForDraft(draft.id).forEach { dao.putLink(MessageTask(it.messageId,bundle.task.id)) }
    supersedeDraftCards(setOf(draft.id))
}

// Run after the failed task transaction, so the diagnostic is not rolled back with it.
internal suspend fun Repository.markDraftConflicts(messageId: String) = gate.withLock { db.withTransaction {
    val message=dao.message(messageId) ?: return@withTransaction
    if(message.state !in setOf("PENDING","WAITING_INPUT") || message.proposal==null) return@withTransaction
    val p=JSONObject(message.proposal)
    if(p.optInt("protocolVersion")!=2) return@withTransaction
    var conflict=false;var superseded=false
    p.getJSONArray("operations").objects().forEach { op ->
        val d=dao.draft(op.getString("draftId"))
        if(d==null || d.revision!=op.getInt("expectedRevision") || d.status!="OPEN") superseded=true
        else if(d.targetKind=="SAVED") {
            val actual=dao.task(d.targetTaskId)
            if(actual?.version!=d.baseTaskVersion || actual?.active!=true) {
                dao.putDraft(d.copy(status="CONFLICT",revision=d.revision+1,updatedAt=System.currentTimeMillis()))
                supersedeDraftCards(setOf(d.id));conflict=true
            }
        }
    }
    if(conflict || superseded) dao.putMessage(message.copy(state=if(conflict) "CONFLICT" else "SUPERSEDED"))
} }

suspend fun Repository.previewDraft(id: String): MessageEntity = gate.withLock { db.withTransaction {
    var draft=dao.draft(id) ?: error("草稿不存在")
    check(draft.status in setOf("OPEN","CONFLICT")) { "这份草稿已处理" }
    val formal=bundle(draft.targetTaskId)
    if(draft.targetKind=="SAVED") check(formal?.task?.version==draft.baseTaskVersion && formal?.task?.active==true) { "事项已变化，请先取消旧修改，再从任务详情发起新修改" }
    if(draft.status=="CONFLICT") { draft=draft.copy(status="OPEN",revision=draft.revision+1);dao.putDraft(draft) }
    check(dao.message(draft.sourceMessageId)!=null) { "原消息不存在，请取消该草稿后重新录入" }
    val b=DraftContent.live(draft,formal)
    draft=draft.copy(workingJson=DraftContent.encode(b,DraftContent.action(draft),DraftContent.intent(draft),DraftContent.decision(draft)))
    dao.putDraft(draft)
    val message=MessageEntity(role="assistant",text="这是当前累计的事项草稿，请核对后确认。",quoteId=draft.sourceMessageId,
        proposal=DraftContent.proposal(draft.sourceMessageId,listOf(DraftChange(draft,formal))),state=if(DraftContent.ready(b)) "PENDING" else "WAITING_INPUT")
    supersedeDraftCards(setOf(id));dao.putMessage(message);dao.putDraftLink(MessageDraft(message.id,id));message
} }

suspend fun Repository.cancelDraft(id: String) = gate.withLock { db.withTransaction {
    val d=dao.draft(id) ?: error("草稿不存在")
    check(d.status in setOf("OPEN","CONFLICT")) { "这份草稿已处理" }
    dao.putDraft(d.copy(status="CANCELLED",revision=d.revision+1,updatedAt=System.currentTimeMillis()))
    supersedeDraftCards(setOf(id))
} }
