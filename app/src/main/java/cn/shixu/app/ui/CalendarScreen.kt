package cn.shixu.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.shixu.app.PlannerViewModel
import cn.shixu.app.data.*
import java.time.*

@Composable
fun CalendarScreen(vm: PlannerViewModel, tasks: List<TaskEntity>) {
    val active=tasks.filter { it.active }
    val weeks=remember(vm.month,active) { CalendarLayout.weeks(vm.month,active) }
    val today=LocalDate.now()
    val overdue=active.filter { it.overdue() }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=100.dp)) {
        item { PageHeading("时序", "把重要的事，放在看得见的时间里") { Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Outlined.FilterVintage,null,Modifier.padding(13.dp).size(25.dp),tint=MaterialTheme.colorScheme.primary) } } }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("${vm.month.monthValue} 月",style=MaterialTheme.typography.headlineMedium); Text("${vm.month.year}  /  ${active.size} 件进行中的事",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick={vm.month=vm.month.minusMonths(1)}) { Icon(Icons.Outlined.ChevronLeft,"上个月") }
                TextButton(onClick={vm.month=YearMonth.now(); vm.selectedDate=today}) { Text("今天") }
                IconButton(onClick={vm.month=vm.month.plusMonths(1)}) { Icon(Icons.Outlined.ChevronRight,"下个月") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp)) {
                listOf("一","二","三","四","五","六","日").forEach { Text(it,Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        items(weeks,key={it.start.toString()}) { week -> CalendarWeekView(week,vm.month,vm.selectedDate,{vm.selectedDate=it},{vm.detailsId=it.id}) }
        item {
            Row(Modifier.padding(horizontal=22.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Info,null,Modifier.size(14.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("横条为持续事项 · ◇ 为仅有截止日的事项",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(overdue.isNotEmpty()) item {
            Surface(onClick={vm.page=1},modifier=Modifier.padding(horizontal=18.dp,vertical=6.dp).fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.errorContainer.copy(alpha=.55f)) {
                Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Outlined.Schedule,null,Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("${overdue.size} 件事项已逾期，仍为你保留",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium); Icon(Icons.Outlined.ChevronRight,null) }
            }
        }
        item { Row(Modifier.padding(horizontal=22.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) { Text("${vm.selectedDate.monthValue} 月 ${vm.selectedDate.dayOfMonth} 日",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f)); Text("${active.count { it.covers(vm.selectedDate) }} 项",color=MaterialTheme.colorScheme.onSurfaceVariant) } }
        val daily=active.filter { it.covers(vm.selectedDate) }
        if(daily.isEmpty()) item { EmptyCard("这一天，留白也很好", "点击右下角，为这一天安排一件事。") }
        items(daily,key={it.id}) { task -> Box(Modifier.padding(horizontal=18.dp,vertical=5.dp)) { TaskRow(task,{vm.detailsId=task.id},{vm.taskAction(task,"COMPLETE")}) } }
    }
}

@Composable
private fun CalendarWeekView(week: CalendarWeek, month: YearMonth, selected: LocalDate, onDate: (LocalDate)->Unit, onTask: (TaskEntity)->Unit) {
    var expanded by remember(week.start,month) { mutableStateOf(false) }
    val visibleLanes=if(expanded) week.lanes else minOf(week.lanes,3)
    val height=42.dp + 25.dp*visibleLanes + if(week.lanes>3) 24.dp else 8.dp
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=12.dp).height(height)) {
        val cell=maxWidth/7
        Row(Modifier.fillMaxWidth()) {
            (0..6).forEach { index ->
                val date=week.start.plusDays(index.toLong())
                Box(Modifier.width(cell).height(38.dp).clip(RoundedCornerShape(12.dp)).clickable { onDate(date) },contentAlignment=Alignment.Center) {
                    val isToday=date==LocalDate.now()
                    val isSelected=date==selected
                    Surface(shape=CircleShape,color=when { isToday -> MaterialTheme.colorScheme.primary; isSelected -> MaterialTheme.colorScheme.primaryContainer; else -> Color.Transparent }) {
                        Box(Modifier.size(31.dp),contentAlignment=Alignment.Center) { Text(date.dayOfMonth.toString(),fontWeight=if(isToday||isSelected) FontWeight.Bold else FontWeight.Normal,fontSize=13.sp,color=when { isToday -> MaterialTheme.colorScheme.onPrimary; YearMonth.from(date)!=month -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.4f); else -> MaterialTheme.colorScheme.onSurface }) }
                    }
                }
            }
        }
        week.bars.filter { it.lane<visibleLanes }.forEach { bar ->
            val bg=Color(bar.task.color)
            val foreground=if(bg.copy(alpha=.82f).compositeOver(MaterialTheme.colorScheme.background).luminance()<.35f) Color.White else Color(0xFF233022)
            Box(Modifier.offset(x=cell*bar.start+2.dp,y=41.dp+25.dp*bar.lane).width(cell*(bar.end-bar.start+1)-4.dp).height(21.dp)
                .clip(RoundedCornerShape(topStart=if(bar.continuesBefore) 2.dp else 6.dp,bottomStart=if(bar.continuesBefore) 2.dp else 6.dp,topEnd=if(bar.continuesAfter) 2.dp else 6.dp,bottomEnd=if(bar.continuesAfter) 2.dp else 6.dp)).background(bg.copy(alpha=.82f)).clickable { onTask(bar.task) }.padding(horizontal=5.dp),contentAlignment=Alignment.CenterStart) {
                Text((if(bar.task.startDate==null) "◇ " else "")+bar.task.title,fontSize=10.sp,lineHeight=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,color=foreground)
            }
        }
        if(week.lanes>3) Text(if(expanded) "收起" else "+ ${week.lanes-3} 行事项 · 展开",Modifier.align(Alignment.BottomEnd).clickable { expanded=!expanded }.padding(horizontal=8.dp,vertical=3.dp),fontSize=10.sp,color=MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.align(Alignment.BottomCenter),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f))
    }
}
