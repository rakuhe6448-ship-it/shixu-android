@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
package cn.shixu.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.shixu.app.PlannerViewModel
import cn.shixu.app.ai.Attachments
import cn.shixu.app.ai.PhoneAlarm
import cn.shixu.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

@Composable
fun ChatScreen(vm: PlannerViewModel,messages: List<MessageEntity>,tasks: List<TaskEntity>,links: List<MessageTask>,p: Preferences) {
    vm.startDateMessage?.let { StartDateChoicesDialog(vm,it) }
    val scroll=rememberLazyListState()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::attach) }
    val byId=messages.associateBy { it.id }
    val drafts by vm.drafts.collectAsState()
    LaunchedEffect(messages.size,vm.aiBusy) { if(messages.isNotEmpty()) scroll.animateScrollToItem(messages.size+1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageHeading("聊聊安排", "${Personality.styles[p.personality]} · 确认后才会保存")
        LazyColumn(Modifier.weight(1f),state=scroll,contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
            item {
                if(messages.isEmpty()) EmptyCard("把事情说给我听", "发一句话，或分享群公告截图。\n我会整理成任务卡片，等你确认。",Icons.Outlined.AutoAwesome) {
                    SuggestionChip(onClick={vm.draft="11月6日交流体输配管网作业，提前一天提醒我"},label={Text("试试：11 月 6 日交作业")})
                } else Text("模型只接收本次相关信息；长按消息或点引用可继续补充。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                val open=drafts.filter { it.status in setOf("OPEN","CONFLICT") }
                if(open.isNotEmpty()) Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("待确认草稿 · ${open.size}",style=MaterialTheme.typography.labelLarge)
                    open.forEach { draft -> Row(verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={vm.reviewDraft(draft.id)},modifier=Modifier.weight(1f)) { Text(cn.shixu.app.ai.DraftContent.bundle(draft).task.title.ifBlank { "待补充事项" },maxLines=1,overflow=TextOverflow.Ellipsis) }
                        IconButton(onClick={vm.cancelDraft(draft.id)}) { Icon(Icons.Outlined.Close,"取消这份草稿") }
                    } }
                }
            }
            items(messages,key={it.id}) { message ->
                val mine=message.role=="user"
                Column(Modifier.fillMaxWidth(),horizontalAlignment=if(mine) Alignment.End else Alignment.Start) {
                    Text(if(mine) "我" else "时序",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=5.dp,vertical=4.dp))
                    Surface(shape=RoundedCornerShape(topStart=20.dp,topEnd=20.dp,bottomStart=if(mine) 20.dp else 5.dp,bottomEnd=if(mine) 5.dp else 20.dp),color=if(mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.widthIn(max=340.dp).combinedClickable(onClick={},onLongClick={vm.quote(message)})) {
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            message.quoteId?.let { id -> byId[id]?.let { quoted -> QuoteBlock(quoted) } }
                            if(message.sharedText.isNotBlank()) SharedTextBlock(message.sharedText)
                            JSONArray(message.imagePaths).strings().forEach { AttachmentImage(it,Modifier.fillMaxWidth().height(160.dp)) }
                            if(message.text.isNotBlank()) Text(message.text,style=MaterialTheme.typography.bodyMedium,color=if(message.state=="ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            val time=Instant.ofEpochMilli(message.createdAt).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text(time,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
                                TextButton(onClick={vm.quote(message)},contentPadding=PaddingValues(horizontal=5.dp,vertical=0.dp),modifier=Modifier.height(28.dp)) { Icon(Icons.AutoMirrored.Outlined.Reply,null,Modifier.size(14.dp)); Text("引用",fontSize=11.sp) }
                                if(mine) TextButton(onClick={vm.draft=message.text;vm.draftSharedText=message.sharedText;vm.draftImages=JSONArray(message.imagePaths).strings();vm.quoteId=message.quoteId},modifier=Modifier.height(28.dp),contentPadding=PaddingValues(horizontal=5.dp,vertical=0.dp)) { Text("重用",fontSize=11.sp) }
                            }
                        }
                    }
                    if(message.proposal!=null) ProposalCard(vm,message)
                    val linked=links.filter { it.messageId==message.id }.mapNotNull { link -> tasks.find { it.id==link.taskId && it.deletedAt==null } }
                    if(linked.isNotEmpty() && message.state!="PENDING") linked.forEach { task ->
                        Surface(onClick={vm.detailsId=task.id},modifier=Modifier.padding(top=7.dp).widthIn(max=340.dp).fillMaxWidth(),shape=RoundedCornerShape(15.dp),color=MaterialTheme.colorScheme.secondaryContainer.copy(alpha=.5f)) {
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                Icon(if(task.completedAt!=null) Icons.Outlined.TaskAlt else Icons.Outlined.EventNote,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) { Text(task.title,style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis); Text(if(task.completedAt!=null) "已完成 ✨" else taskDateLabel(task),style=MaterialTheme.typography.bodySmall) }
                                IconButton(onClick={vm.editTask(task.id)},modifier=Modifier.size(32.dp)) { Icon(Icons.Outlined.Edit,"编辑关联事项",Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
            }
            if(vm.aiBusy) item { Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp); Text("正在识别和整理…",style=MaterialTheme.typography.bodySmall) } }
        }
        Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,tonalElevation=2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                if(vm.draftSharedText.isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SharedTextBlock(vm.draftSharedText) }
                    IconButton(onClick={vm.draftSharedText=""},modifier=Modifier.size(28.dp)) { Icon(Icons.Outlined.Close,"移除分享内容",Modifier.size(16.dp)) }
                }
                vm.quoteId?.let { id ->
                    byId[id]?.let { message ->
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { QuoteBlock(message) }
                            IconButton(onClick={vm.quoteId=null},modifier=Modifier.size(28.dp)) { Icon(Icons.Outlined.Close,"取消引用",Modifier.size(16.dp)) }
                        }
                    }
                }
                if(vm.draftImages.isNotEmpty()) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { vm.draftImages.forEach { name -> Box { AttachmentImage(name,Modifier.size(64.dp)); IconButton(onClick={vm.draftImages=vm.draftImages-name},modifier=Modifier.align(Alignment.TopEnd).size(23.dp).background(MaterialTheme.colorScheme.surface,CircleShape)) { Icon(Icons.Outlined.Close,"移除图片",Modifier.size(14.dp)) } } } }
                Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    IconButton(onClick={picker.launch("image/*")},enabled=!vm.attachmentBusy && vm.draftImages.size<4) { Icon(Icons.Outlined.AddPhotoAlternate,"添加图片") }
                    OutlinedTextField(vm.draft,{vm.draft=it},placeholder={Text(if(vm.attachmentBusy) "正在导入图片…" else "说说接下来要做的事")},modifier=Modifier.weight(1f),shape=RoundedCornerShape(24.dp),maxLines=4)
                    FilledIconButton(onClick=vm::send,enabled=!vm.aiBusy && !vm.attachmentBusy && (vm.draft.isNotBlank()||vm.draftImages.isNotEmpty()||vm.draftSharedText.isNotBlank()),modifier=Modifier.padding(bottom=4.dp)) { Icon(Icons.AutoMirrored.Outlined.Send,"发送") }
                }
            }
        }
    }
}

@Composable
private fun SharedTextBlock(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    Surface(shape=RoundedCornerShape(9.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.65f),onClick={expanded=!expanded}) {
        Column(Modifier.padding(9.dp)) {
            Text("来自其他应用 · 待识别内容",fontSize=10.sp,color=MaterialTheme.colorScheme.primary)
            Text(text,maxLines=if(expanded) Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
private fun QuoteBlock(message: MessageEntity) {
    Surface(shape=RoundedCornerShape(9.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.65f)) {
        Row(Modifier.padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(3.dp).height(30.dp).background(MaterialTheme.colorScheme.primary))
            Column { Text("引用 · ${if(message.role=="user") "我" else "时序"}",fontSize=10.sp,color=MaterialTheme.colorScheme.primary); Text(message.text.ifBlank { "[图片]" },maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable
fun AttachmentImage(name: String,modifier: Modifier=Modifier) {
    val context=LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null,name) { value=withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(Attachments.file(context,name).absolutePath)?.asImageBitmap() }.getOrNull() } }
    bitmap?.let { Image(it,"聊天图片",modifier.clip(RoundedCornerShape(12.dp)),contentScale=ContentScale.Crop) }
}
@Composable
private fun ProposalCard(vm: PlannerViewModel,message: MessageEntity) {
    if(PhoneAlarm.isProposal(message.proposal)) { PhoneAlarmCard(vm,message);return }
    val operations=remember(message.proposal) { runCatching { JSONObject(message.proposal!!).getJSONArray("operations").objects() }.getOrDefault(emptyList()) }
    Surface(modifier=Modifier.padding(top=8.dp).fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.25f))) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(when(message.state){"APPLIED" -> "已应用到排期 ✓"; "DISMISSED" -> "这条建议已取消";"SUPERSEDED" -> "已有更新，请查看最新草稿";"CONFLICT" -> "事项已变化，需要重新核对";"WAITING_INPUT" -> "草稿待补充，尚未保存";else -> "待确认的操作"},style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
            operations.forEach { op ->
                val bundle=JsonCodec.readBundle(op.getJSONObject("bundle"))
                val before=if(op.isNull("before")) null else JsonCodec.readBundle(op.getJSONObject("before"))
                val action=op.getString("action")
                Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("${when(action){"CREATE" -> "新建";"UPDATE" -> "修改";"COMPLETE" -> "完成";else -> "删除"}} · ${bundle.task.title.ifBlank { "待补充事项" }}",style=MaterialTheme.typography.titleMedium)
                    if(before!=null && action=="UPDATE") Text("原：${taskDateLabel(before.task)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text((if(action=="UPDATE") "改为：" else "")+taskDateLabel(bundle.task),style=MaterialTheme.typography.bodyMedium)
                    if(action in setOf("CREATE","UPDATE")) {
                        Text("${bundle.task.category} · ${RepeatRule.valueOf(bundle.task.repeat).label}",style=MaterialTheme.typography.bodySmall)
                        if(before!=null) {
                            val removed=before.reminders.filter { old -> bundle.reminders.none { it.id==old.id } }
                            if(removed.isNotEmpty()) Text("将移除提醒："+removed.joinToString("、") { it.localTime.replace('T',' ') },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                        }
                        if(op.optBoolean("needsStartDate",false)) Text("建议补充开始日期，日历可显示持续排期条",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                        if(bundle.task.notes.isNotBlank()) Text(bundle.task.notes,maxLines=4,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                        if(bundle.reminders.isEmpty()) Text("不设置提醒",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        bundle.reminders.forEach { r -> Text("提醒 ${r.localTime.replace('T',' ')} · ${if(r.relativeMinutes!=null) "随截止移动" else "独立时间"} · ${RepeatRule.valueOf(r.repeat).label}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
                    } else Text("相关未触发提醒会取消",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
            }
            if(message.state in setOf("PENDING","WAITING_INPUT")) {
                Text("请核对目标、日期与提醒。确认后会更新本地排期。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={vm.requestApplyProposal(message)},enabled=!vm.proposalSaving && message.state=="PENDING") { Text("确认执行") }
                    TextButton(onClick={vm.quote(message)}) { Text("引用补充") }
                    TextButton(onClick={vm.dismissProposal(message.id)}) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun PhoneAlarmCard(vm: PlannerViewModel,message: MessageEntity) {
    val context=LocalContext.current
    val alarm=remember(message.proposal) { runCatching { PhoneAlarm.fromProposal(message.proposal!!) }.getOrNull() }
    Surface(modifier=Modifier.padding(top=8.dp).fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.25f))) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(when(message.state) {
                "APPLIED" -> "已交给手机时钟"
                "DISMISSED" -> "这条闹钟建议已取消"
                "SUPERSEDED" -> "请查看最新闹钟卡片"
                "WAITING_INPUT" -> "手机闹钟 · 待补充"
                else -> "待确认的手机闹钟"
            },style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
            alarm?.let {
                Text(it.label,style=MaterialTheme.typography.titleMedium)
                Text(it.description(),style=MaterialTheme.typography.bodyLarge)
            }
            Text("由手机时钟响铃。请在时钟中核对并启用，后续修改或关闭也在手机时钟中操作。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(message.state in setOf("PENDING","WAITING_INPUT")) {
                    if(alarm!=null && message.state=="PENDING") Button(onClick={vm.openPhoneAlarm(message,context)},enabled=!vm.proposalSaving) { Text("确认并打开手机时钟") }
                    TextButton(onClick={vm.quote(message)}) { Text("引用补充") }
                    TextButton(onClick={vm.dismissProposal(message.id)}) { Text("取消建议") }
                }
                TextButton(onClick={vm.showPhoneClock(context)}) { Text("打开手机时钟") }
            }
        }
    }
}
