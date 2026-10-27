package cn.shixu.app.data

import cn.shixu.app.ai.PhoneAlarm
import cn.shixu.app.ai.PhoneAlarmSpec
import kotlinx.coroutines.sync.withLock

/** External Clock actions cannot participate in a Room transaction or report creation success. */
suspend fun Repository.handoffPhoneAlarm(messageId: String,launch: (PhoneAlarmSpec) -> Unit) = gate.withLock {
    val message=dao.message(messageId) ?: error("闹钟卡片不存在")
    check(message.state=="PENDING" && PhoneAlarm.isProposal(message.proposal)) { "这条闹钟建议已处理，请在手机时钟中核对" }
    val alarm=PhoneAlarm.fromProposal(message.proposal!!) ?: error("请先补充闹钟时间")
    alarm.checkNextOccurrence()
    launch(alarm) // A missing Clock app or rejected intent leaves the card pending.
    try {
        dao.putMessage(message.copy(state="APPLIED",text="已交给手机时钟，请在时钟中核对闹钟时间和启用状态。修改或关闭请在手机时钟中操作。"))
    } catch(e: Exception) {
        if(e is kotlinx.coroutines.CancellationException) throw e
        error("手机时钟已打开，但聊天状态保存失败；请先在手机时钟核对，避免重复设置。")
    }
}
