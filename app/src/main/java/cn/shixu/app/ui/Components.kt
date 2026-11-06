package cn.shixu.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import cn.shixu.app.data.*

@Composable
fun PageHeading(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    val accent=MaterialTheme.colorScheme.primary
    val ink=MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.width(12.dp).height(48.dp)) { rotate(12f) { drawRect(accent,size=size.copy(width=size.width*.65f)) };drawLine(ink,Offset(0f,size.height*.82f),Offset(size.width,size.height*.82f),2.dp.toPx()) }
        Column(Modifier.weight(1f)) {
            Text(when(title){"时序" -> "01 / SHIXU";"待办" -> "02 / ACTION";"提醒" -> "03 / SIGNAL";"聊聊安排" -> "04 / DIALOGUE";else -> "05 / STUDIO"},style=MaterialTheme.typography.labelSmall,color=accent)
            Text(title,style=MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}
@Composable
fun EmptyCard(title: String, body: String, icon: ImageVector=Icons.Outlined.Spa, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer) { Icon(icon,null,Modifier.padding(20.dp).size(32.dp),tint=MaterialTheme.colorScheme.primary) }
        Text(title,style=MaterialTheme.typography.titleMedium)
        Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}
@Composable
fun SoftCard(modifier: Modifier=Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier=modifier.fillMaxWidth().animateContentSize(),shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
fun taskDateLabel(task: TaskEntity): String = when {
    task.startDate!=null && task.dueDate!=null -> "${task.startDate} — ${task.dueDate}${task.dueTime?.let { " $it" } ?: ""}"
    task.dueDate!=null -> "${task.dueDate}${task.dueTime?.let { " $it" } ?: ""} 截止"
    task.startDate!=null -> "${task.startDate} 开始"
    else -> "无截止日期"
}
@Composable
fun TaskRow(task: TaskEntity, onClick: () -> Unit, onComplete: (() -> Unit)? = null) {
    Surface(onClick=onClick,shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(6.dp).height(46.dp).background(Color(task.color)))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(task.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text((if(task.overdue()) "已逾期 · " else "")+taskDateLabel(task),style=MaterialTheme.typography.labelMedium,color=if(task.overdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(task.category+(if(task.repeat!="NONE") " · ${RepeatRule.valueOf(task.repeat).label}" else ""),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(onComplete!=null) IconButton(onClick=onComplete) { Icon(Icons.Outlined.RadioButtonUnchecked,"标记完成",tint=MaterialTheme.colorScheme.primary) }
            else Icon(Icons.Outlined.ChevronRight,"查看事项",tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.padding(end=8.dp).size(6.dp).background(MaterialTheme.colorScheme.primary))
        Text(text,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
        trailing?.let { Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp) }
    }
}
