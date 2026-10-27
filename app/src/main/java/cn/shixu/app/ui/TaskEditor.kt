@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.shixu.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.shixu.app.PlannerViewModel
import cn.shixu.app.data.*
import java.time.*

fun pickDate(context: Context,current: String?, result: (String)->Unit) {
    val d=current?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
    DatePickerDialog(context,{_,y,m,day -> result(LocalDate.of(y,m+1,day).toString()) },d.year,d.monthValue-1,d.dayOfMonth).show()
}
fun pickTime(context: Context,current: String?, result: (String)->Unit) {
    val t=current?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(9,0)
    TimePickerDialog(context,{_,h,m -> result(LocalTime.of(h,m).toString()) },t.hour,t.minute,true).show()
}

@Composable
fun TaskEditor(vm: PlannerViewModel, initial: TaskBundle) {
    val context=LocalContext.current
    var task by remember(initial.task.id,initial.task.version) { mutableStateOf(initial.task) }
    var reminders by remember(initial.task.id,initial.task.version) { mutableStateOf(initial.reminders) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val changeTask: (TaskEntity)->Unit = { new ->
        if(new.deadline()!=task.deadline()) reminders=reminders.map { r ->
            if(r.relativeMinutes!=null && new.deadline()!=null) r.copy(localTime=new.deadline()!!.minusMinutes(r.relativeMinutes).toString(),enabled=true,lastFiredLocal=null,scheduledAt=null,delivery="PENDING")
            else if(r.relativeMinutes!=null) r.copy(relativeMinutes=null) else r
        }
        task=new
    }
    val close: ()->Unit = { if(task!=initial.task || reminders!=initial.reminders) discard=true else vm.editor=null }
    Dialog(onDismissRequest={if(!vm.saving) close()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=close,enabled=!vm.saving) { Icon(Icons.Outlined.Close,"取消编辑") }
                    Text(if(vm.editorVersion==null) "新建事项" else "编辑事项",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                    Button(enabled=!vm.saving,onClick={
                        try { Rules.validate(task,reminders); error=null; vm.saveTask(TaskBundle(task,reminders)) } catch(e: Exception) { error=e.message ?: "请检查输入" }
                    }) { Text(if(vm.saving) "保存中" else "保存") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    OutlinedTextField(task.title,{task=task.copy(title=it.take(200))},label={Text("想安排什么？")},placeholder={Text("例如：流体输配管网作业")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),maxLines=3)
                    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    SoftCard {
                        SectionLabel("时间安排")
                        DateField("开始日期",task.startDate,{pickDate(context,task.startDate) {changeTask(task.copy(startDate=it))}},{changeTask(task.copy(startDate=null))})
                        DateField("截止日期",task.dueDate,{pickDate(context,task.dueDate) {changeTask(task.copy(dueDate=it))}},{changeTask(task.copy(dueDate=null,dueTime=null,repeat="NONE"))})
                        if(task.dueDate!=null) DateField("截止时间",task.dueTime,{pickTime(context,task.dueTime) {changeTask(task.copy(dueTime=it))}},{changeTask(task.copy(dueTime=null))},"当天结束")
                        Text("可以只设置截止日期。提醒时间在下方单独安排。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("事项重复",style=MaterialTheme.typography.labelLarge)
                        RepeatChips(task.repeat) { changeTask(task.copy(repeat=it)) }
                        if(task.repeat!="NONE") Text("完成本期后自动生成下一期；本期保留在已完成中。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SoftCard {
                        SectionLabel("提醒", "${reminders.size} / 20")
                        if(reminders.isEmpty()) Text("尚未设置提醒",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        reminders.forEach { reminder -> key(reminder.id) {
                            ReminderEditor(reminder,task,onChange={new -> reminders=reminders.map { if(it.id==new.id) new else it }},onDelete={reminders=reminders.filterNot { it.id==reminder.id }})
                        } }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={
                                if(reminders.size<20) reminders=reminders+ReminderEntity(taskId=task.id,localTime=LocalDateTime.now().plusHours(1).withSecond(0).withNano(0).toString())
                            },enabled=reminders.size<20) { Icon(Icons.Outlined.Add,null,Modifier.size(18.dp)); Text("自定提醒") }
                            TextButton(onClick={
                                val r=Rules.defaultReminder(task)
                                if(r==null) error="截止前一天 09:00 已经过了，或还未设置截止日期；请自定提醒时间"
                                else if(reminders.none { it.localTime==r.localTime && it.repeat==r.repeat }) reminders=reminders+r
                            },enabled=task.dueDate!=null && reminders.size<20) { Text("截止前一天 09:00") }
                        }
                    }
                    SoftCard {
                        SectionLabel("整理一下")
                        OutlinedTextField(task.category,{task=task.copy(category=it.take(40))},label={Text("分类")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("学习","工作","生活","日常").forEach { c -> SuggestionChip(onClick={task=task.copy(category=c)},label={Text(c)}) } }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            TaskColors.forEach { c -> Surface(onClick={task=task.copy(color=c)},shape=CircleShape,color=Color(c),modifier=Modifier.size(32.dp),border=if(task.color==c) BorderStroke(3.dp,MaterialTheme.colorScheme.onSurface) else null) { if(task.color==c) Icon(Icons.Outlined.Check,"已选颜色",Modifier.padding(5.dp),tint=Color(0xFF203020)) } }
                        }
                        OutlinedTextField(task.notes,{task=task.copy(notes=it.take(10000))},label={Text("备注")},placeholder={Text("地点、材料、具体要求……")},minLines=3,modifier=Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
    if(discard) AlertDialog(onDismissRequest={discard=false},title={Text("放弃未保存的修改？")},text={Text("关闭后，这次编辑的内容不会保存。")},confirmButton={TextButton(onClick={discard=false;vm.editor=null}){Text("放弃修改")}},dismissButton={TextButton(onClick={discard=false}){Text("继续编辑")}})
}

@Composable
private fun DateField(label: String,value: String?,onPick: ()->Unit,onClear: ()->Unit,empty: String="未设置") {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        TextButton(onClick=onPick) { Text(value ?: empty) }
        if(value!=null) IconButton(onClick=onClear,modifier=Modifier.size(32.dp)) { Icon(Icons.Outlined.Close,"清除$label",Modifier.size(16.dp)) }
    }
}
@Composable
fun RepeatChips(value: String,onChange: (String)->Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { RepeatRule.entries.forEach { r -> FilterChip(selected=value==r.name,onClick={onChange(r.name)},label={Text(r.label)}) } }
}
@Composable
private fun ReminderEditor(r: ReminderEntity, task: TaskEntity, onChange: (ReminderEntity)->Unit, onDelete: ()->Unit) {
    val context=LocalContext.current
    val at=LocalDateTime.parse(r.localTime)
    fun updateTime(new: LocalDateTime) {
        val minutes=if(r.relativeMinutes!=null && task.deadline()!=null) Duration.between(new,task.deadline()).toMinutes().takeIf { it>=0 } else null
        onChange(r.copy(localTime=new.toString(),relativeMinutes=minutes,enabled=true,lastFiredLocal=null,scheduledAt=null,delivery="PENDING"))
    }
    Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.6f)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.NotificationsNone,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
                TextButton(onClick={pickDate(context,at.toLocalDate().toString()) { updateTime(LocalDate.parse(it).atTime(at.toLocalTime())) }}) { Text(at.toLocalDate().toString()) }
                TextButton(onClick={pickTime(context,at.toLocalTime().toString()) { updateTime(at.toLocalDate().atTime(LocalTime.parse(it))) }}) { Text(at.toLocalTime().toString()) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick=onDelete,modifier=Modifier.size(28.dp)) { Icon(Icons.Outlined.Close,"删除此提醒",Modifier.size(18.dp)) }
            }
            RepeatChips(r.repeat) { onChange(r.copy(repeat=it,lastFiredLocal=null,enabled=true,scheduledAt=null,delivery="PENDING")) }
            if(task.deadline()!=null && at<=task.deadline()) Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=r.relativeMinutes!=null,onCheckedChange={ checked -> onChange(r.copy(relativeMinutes=if(checked) Duration.between(at,task.deadline()).toMinutes() else null)) })
                Text("随截止时间一起移动",style=MaterialTheme.typography.bodySmall)
            }
            if(!r.enabled && r.lastFiredLocal!=null) Text("此提醒已触发；修改时间可重新启用",style=MaterialTheme.typography.bodySmall)
            else if(at<LocalDateTime.now() && r.repeat=="NONE") Text("该时间已过去，保存后将尽快补发一次提醒",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun TaskDetails(vm: PlannerViewModel, task: TaskEntity, reminders: List<ReminderEntity>) {
    var delete by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val history by produceState<List<HistoryEntity>>(emptyList(),task.id,task.version) { value=vm.repo.dao.historyFor(task.id) }
    AlertDialog(onDismissRequest={vm.detailsId=null},title={Text(task.title)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(taskDateLabel(task))
            Text(task.category+" · "+RepeatRule.valueOf(task.repeat).label,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(task.overdue()) Text("已逾期，事项会一直保留到你处理",color=MaterialTheme.colorScheme.error)
            if(task.completedAt!=null) Text("已完成 ✨ 谢谢认真生活的你",color=MaterialTheme.colorScheme.primary)
            if(task.notes.isNotBlank()) Text(task.notes)
            reminders.forEach { Text("${it.localTime.replace('T',' ')} · ${RepeatRule.valueOf(it.repeat).label}${if(it.relativeMinutes!=null) " · 随截止移动" else ""}",style=MaterialTheme.typography.bodySmall) }
            TextButton(onClick={showHistory=!showHistory}) { Text(if(showHistory) "收起变更记录" else "查看变更记录（${history.size}）") }
            if(showHistory) history.take(30).forEach { h ->
                val time=Instant.ofEpochMilli(h.at).atZone(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T',' ').take(16)
                Text("$time · ${h.action}${if(h.sourceMessageId!=null) " · 来自 AI 对话" else ""}",style=MaterialTheme.typography.bodySmall)
                if(h.before!=null && h.after!=null) {
                    val before=runCatching { JsonCodec.readBundle(org.json.JSONObject(h.before)) }.getOrNull()
                    val after=runCatching { JsonCodec.readBundle(org.json.JSONObject(h.after)) }.getOrNull()
                    if(before!=null && after!=null) Text("${taskDateLabel(before.task)} → ${taskDateLabel(after.task)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={vm.editTask(task.id)}) { Text("编辑") }
                TextButton(onClick={delete=true}) { Text("删除",color=MaterialTheme.colorScheme.error) }
            }
            if(task.active) TextButton(onClick={vm.discussTask(task)}) { Text("在 AI 对话中继续修改") }
        }
    },confirmButton={Button(onClick={vm.taskAction(task,if(task.completedAt==null) "COMPLETE" else "RESTORE")}){Text(if(task.completedAt==null) "标记完成" else "重新打开")}},dismissButton={TextButton(onClick={vm.detailsId=null}){Text("关闭")}})
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("删除这件事？")},text={Text("「${task.title}」会从排期中移除，并取消所有未触发的提醒。修改记录仍保留在备份中。")},confirmButton={TextButton(onClick={delete=false;vm.taskAction(task,"DELETE")}){Text("删除")}},dismissButton={TextButton(onClick={delete=false}){Text("取消")}})
}
