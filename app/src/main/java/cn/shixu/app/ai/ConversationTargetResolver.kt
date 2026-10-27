package cn.shixu.app.ai

import cn.shixu.app.data.*

object ConversationTargetResolver {
    suspend fun resolve(repo: Repository,current: MessageEntity): ConversationContext {
        val dao=repo.dao
        val messages=dao.messages().filter { it.id!=current.id }
        val quoted=current.quoteId?.let { dao.message(it) }
        // Clock requests have no task IDs. Keep their context separate from the planner.
        fun alarmFor(message: MessageEntity): MessageEntity? = if(PhoneAlarm.isProposal(message.proposal)) message else
            messages.lastOrNull { it.quoteId==message.id && PhoneAlarm.isProposal(it.proposal) }
        var alarm=if(quoted!=null) alarmFor(quoted) else messages.lastOrNull()?.let(::alarmFor)
        if(quoted!=null && alarm==null) {
            var node: MessageEntity=quoted;val visited=mutableSetOf<String>()
            repeat(16) {
                if(alarm==null && visited.add(node.id)) {
                    val parent=node.quoteId?.let { id -> messages.find { it.id==id } }
                    if(parent!=null) { node=parent;alarm=alarmFor(parent) }
                }
            }
        }
        if(alarm!=null) return ConversationContext(emptyList(),quoted,listOfNotNull(alarm),phoneAlarm=alarm)
        suspend fun direct(message: MessageEntity): Set<String> =
            (dao.draftsForMessage(message.id).mapNotNull { dao.draft(it.draftId)?.targetTaskId }+dao.linksFor(message.id).map { it.taskId }).toSet()
        var ids=emptySet<String>()
        if(quoted!=null) {
            var node: MessageEntity?=quoted;val visited=mutableSetOf<String>()
            repeat(16) {
                val at=node
                if(at!=null && ids.isEmpty() && visited.add(at.id)) { ids=direct(at);node=at.quoteId?.let { dao.message(it) } }
            }
        } else if(current.quoteId==null) {
            val latest=messages.lastOrNull()
            if(latest!=null && latest.state !in setOf("ERROR","DISMISSED","CONFLICT","SUPERSEDED")) {
                ids=direct(latest)
                if(ids.isEmpty() && latest.role=="assistant") latest.quoteId?.let { id -> dao.message(id)?.takeIf { it.role=="user" }?.let { ids=direct(it) } }
            }
        }
        val bound=ids.isNotEmpty()
        if(!bound && current.quoteId==null) {
            val pairs=current.text.windowed(2).toSet()
            ids=dao.tasks().filter { it.active }.map { t -> t to ((if(current.text.contains(t.title)) 100 else 0)+t.title.windowed(2).count { it in pairs }) }
                .filter { it.second>=2 }.sortedByDescending { it.second }.take(4).map { it.first.id }.toSet()
        }
        val targets=ids.mapIndexed { index,id ->
            val d=dao.draftForTask(id)
            val formal=repo.bundle(id)
            val freshSavedReference=quoted!=null && dao.draftsForMessage(quoted.id).isEmpty() && dao.linksFor(quoted.id).any { it.taskId==id }
            require(d?.status!="CANCELLED" || formal!=null && (current.quoteId==null || freshSavedReference)) { "这份草稿已取消，请重新选择事项或新建" }
            require(d?.status!="CONFLICT") { "草稿需要重新核对，请打开待确认草稿或取消旧修改" }
            if(d?.status=="OPEN" && d.targetKind=="SAVED") require(formal?.task?.version==d.baseTaskVersion && formal?.task?.active==true) { "事项已手动修改、完成或删除，请取消旧草稿后重新引用事项" }
            val bundle=if(d?.status=="OPEN") DraftContent.live(d,formal) else formal ?: error("事项不存在")
            ConversationTarget("T${index+1}",bundle,if(d?.status=="CANCELLED") d.copy(status="APPLIED") else d,formal)
        }
        val related=messages.filter { m -> ids.isNotEmpty() && direct(m).any { it in ids } }.takeLast(3)
        return ConversationContext(targets,quoted,related,quoteBound=current.quoteId!=null && bound,unresolvedQuote=current.quoteId!=null && !bound)
    }
}
