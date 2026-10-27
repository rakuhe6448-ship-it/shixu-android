package cn.shixu.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.shixu.app.PlannerViewModel
import cn.shixu.app.ai.ProposalChoices
import cn.shixu.app.data.MessageEntity
import cn.shixu.app.data.JsonCodec
import cn.shixu.app.data.objects
import org.json.JSONObject
import java.time.LocalDate

@Composable
fun StartDateChoicesDialog(vm: PlannerViewModel,message: MessageEntity) {
    val context=LocalContext.current
    val tasks=remember(message.proposal) { ProposalChoices.pending(message.proposal!!) }
    val operations=remember(message.proposal) { JSONObject(message.proposal!!).getJSONArray("operations").objects() }
    var choices by remember(message.id) { mutableStateOf<Map<String,String?>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest={if(!vm.proposalSaving) vm.startDateMessage=null},title={Text("要从哪天开始安排？")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("这些事项可能需要持续准备。设置开始日期后，月历会显示跨日彩条；仅设截止日期则只标记截止当天。")
            operations.forEach { op ->
                val bundle=JsonCodec.readBundle(op.getJSONObject("bundle"))
                Text("${when(op.getString("action")){"CREATE" -> "新建";"UPDATE" -> "修改";"COMPLETE" -> "完成";else -> "删除"}} · ${bundle.task.title}\n${taskDateLabel(bundle.task)}",style=MaterialTheme.typography.bodySmall)
                if(op.getString("action") in setOf("CREATE","UPDATE")) Text(bundle.reminders.joinToString("\n") { "提醒 ${it.localTime.replace('T',' ')}" }.ifEmpty { "不设置提醒" },style=MaterialTheme.typography.bodySmall)
            }
            tasks.forEach { task ->
                HorizontalDivider()
                Text(task.title,style=MaterialTheme.typography.titleSmall)
                Text("${task.category} · 截止 ${task.dueDate}",style=MaterialTheme.typography.bodySmall)
                val selected=choices.containsKey(task.id)
                Text(if(!selected) "请选择开始日期" else choices[task.id]?.let { "开始：$it" } ?: "仅设截止日期",color=MaterialTheme.colorScheme.primary)
                TextButton(onClick={choices=choices+(task.id to LocalDate.now().toString());error=null},enabled=!vm.proposalSaving && !LocalDate.now().isAfter(LocalDate.parse(task.dueDate))) { Text("今天开始") }
                TextButton(onClick={pickDate(context,choices[task.id] ?: task.dueDate) { date ->
                    if(LocalDate.parse(date).isAfter(LocalDate.parse(task.dueDate))) error="开始日期不能晚于截止日期"
                    else { choices=choices+(task.id to date);error=null }
                }},enabled=!vm.proposalSaving) { Text("指定开始日期") }
                TextButton(onClick={choices=choices+(task.id to null);error=null},enabled=!vm.proposalSaving) { Text("仅设截止日期") }
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            vm.proposalError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Text("确认将保存本条卡片的全部操作；提醒时间仍以卡片中的设置为准。",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={vm.applyProposal(message.id,choices)},enabled=!vm.proposalSaving && choices.size==tasks.size) { Text(if(vm.proposalSaving) "正在保存…" else "确认并保存") }},dismissButton={TextButton(onClick={vm.startDateMessage=null},enabled=!vm.proposalSaving) { Text("返回核对卡片") }})
}
