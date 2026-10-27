@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.shixu.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.shixu.app.PlannerViewModel
import cn.shixu.app.data.*
import java.time.*

@Composable
fun TodoScreen(vm: PlannerViewModel,tasks: List<TaskEntity>) {
    var completed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val visible=tasks.filter { it.deletedAt==null && (it.completedAt!=null)==completed && (category==null || it.category==category) && (it.title.contains(query,true)||it.notes.contains(query,true)) }.sortedWith(compareBy<TaskEntity> { !it.overdue() }.thenBy { it.dueDate ?: "9999" })
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=100.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { PageHeading("待办", if(completed) "每一次完成，都算数。" else "按自己的节奏，一件一件来。") }
        item {
            Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=!completed,onClick={completed=false},label={Text("进行中 ${tasks.count { it.active }}")}); FilterChip(selected=completed,onClick={completed=true},label={Text("已完成 ${tasks.count { it.completedAt!=null && it.deletedAt==null }}")},leadingIcon={Icon(Icons.Outlined.CheckCircle,null,Modifier.size(17.dp))}) }
                OutlinedTextField(query,{query=it},placeholder={Text("搜索事项或备注")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    FilterChip(selected=category==null,onClick={category=null},label={Text("全部分类")})
                    tasks.filter { it.deletedAt==null }.map { it.category }.distinct().forEach { c -> FilterChip(selected=category==c,onClick={category=c},label={Text(c)}) }
                }
            }
        }
        if(visible.isEmpty()) item { EmptyCard(if(completed) "完成的事会留在这里" else "没有匹配的事项",if(completed) "在日历或待办中点圆圈，即可标记完成。" else "添加一件事，或试试其他搜索词。",if(completed) Icons.Outlined.Celebration else Icons.Outlined.Checklist) }
        items(visible,key={it.id}) { t -> Box(Modifier.padding(horizontal=18.dp)) { TaskRow(t,{vm.detailsId=t.id},if(t.active) {{vm.taskAction(t,"COMPLETE")}} else null) } }
    }
}

@Composable
fun PermissionCard(vm: PlannerViewModel) {
    val context=LocalContext.current
    val tick=vm.permissionTick
    val exact=remember(tick) { vm.app.alarms.exactAllowed() }
    val notifications=remember(tick) { vm.app.alarms.notificationsAllowed() }
    val battery=remember(tick) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshPermissions() }
    fun open(intent: Intent) { try { context.startActivity(intent) } catch(_: Exception) { vm.notify("此设备没有对应设置页，请前往系统应用设置中开启") } }
    SoftCard {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(if(exact && notifications) Icons.Outlined.Verified else Icons.Outlined.NotificationsActive,null,tint=MaterialTheme.colorScheme.primary)
            Text(if(exact && notifications) "本地提醒已就绪" else "完成提醒设置",style=MaterialTheme.typography.titleMedium)
        }
        Text("通知：${if(notifications) "已允许" else "未允许或渠道关闭"} · 精确闹钟：${if(exact) "已允许" else "未允许，提醒可能延迟"}",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(!notifications) OutlinedButton(onClick={
                if(Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) request.launch(Manifest.permission.POST_NOTIFICATIONS)
                else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
            }) { Text("开启通知") }
            if(!exact && Build.VERSION.SDK_INT>=31) OutlinedButton(onClick={open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${context.packageName}")))}) { Text("允许精确提醒") }
            if(!notifications) TextButton(onClick={open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}) { Text("系统通知设置") }
            TextButton(onClick={open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}) { Text("系统后台设置") }
            TextButton(onClick={vm.refreshPermissions();vm.notify("已重新检查权限并同步提醒")}) { Text("重新检查") }
        }
        Text("${if(battery) "电池优化已豁免。" else "可在系统设置中允许后台运行。"}vivo 等设备请同时开启自启动、后台高耗电运行，并允许通知。强行停止应用后，需再次打开才能恢复提醒。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RemindersScreen(vm: PlannerViewModel,tasks: List<TaskEntity>,reminders: List<ReminderEntity>) {
    var history by remember { mutableStateOf(false) }
    val taskMap=tasks.filter { it.deletedAt==null }.associateBy { it.id }
    val shown=reminders.filter { r -> taskMap[r.taskId]!=null && if(history) r.lastFiredLocal!=null || !r.enabled else r.enabled && taskMap[r.taskId]?.active==true }.sortedBy { it.scheduledAt ?: LocalDateTime.parse(it.localTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=100.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { PageHeading("提醒", "时间交给时序，专心做眼前的事。") }
        item { Box(Modifier.padding(horizontal=18.dp)) { PermissionCard(vm) } }
        item { Row(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=!history,onClick={history=false},label={Text("待提醒")}); FilterChip(selected=history,onClick={history=true},label={Text("已触发 / 已结束")}) } }
        if(shown.isEmpty()) item { EmptyCard("这里暂时很安静", "添加事项时，可单独指定一个或多个提醒时间。",Icons.Outlined.NotificationsNone) }
        items(shown,key={it.id}) { r ->
            val task=taskMap.getValue(r.taskId)
            val next=if(history) r.lastFiredLocal ?: r.localTime else Rules.nextReminder(r,LocalDateTime.now())?.toString() ?: r.localTime
            Surface(onClick={vm.detailsId=task.id},modifier=Modifier.padding(horizontal=18.dp).fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.NotificationsNone,null,tint=MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(next.replace('T',' '),style=MaterialTheme.typography.titleMedium)
                        Text(task.title,style=MaterialTheme.typography.bodyMedium)
                        Text(RepeatRule.valueOf(r.repeat).label+(if(r.relativeMinutes!=null) " · 随截止时间移动" else " · 独立时间"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when(r.delivery){"BLOCKED" -> "通知权限关闭，等待恢复"; "APPROXIMATE" -> "普通系统闹钟，可能延迟"; "EXACT" -> "系统精确闹钟已安排"; "DELIVERED" -> "已发送通知"; else -> if(!r.enabled) "已结束" else "等待同步"},style=MaterialTheme.typography.bodySmall,color=if(r.delivery=="BLOCKED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    Icon(Icons.Outlined.ChevronRight,null)
                }
            }
        }
    }
}
