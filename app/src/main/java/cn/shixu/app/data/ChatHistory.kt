package cn.shixu.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/** A single deletion leaves only an empty ID so draft/audit references stay valid. */
suspend fun Repository.deleteChatHistory(messageId: String? = null): List<String> = gate.withLock {
    db.withTransaction {
        val all=dao.messages()
        val selected=if(messageId==null) all else listOf(dao.message(messageId) ?: error("消息不存在"))
        val images=selected.flatMap { JSONArray(it.imagePaths).strings() }.distinct()
        if(messageId==null) {
            dao.clearDraftLinks();dao.clearDrafts();dao.clearLinks();dao.clearHistorySources();dao.clearMessages()
        } else {
            val m=selected.single()
            dao.putMessage(m.copy(text="",sharedText="",imagePaths="[]",quoteId=null,proposal=null,state="DELETED"))
        }
        val retained=dao.messages().flatMap { JSONArray(it.imagePaths).strings() }.toSet()
        images.filterNot { it in retained }
    }
}
