package cn.shixu.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.shixu.app.data.*

@Composable
fun PageHeading(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
    Surface(modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,tonalElevation=1.dp) {
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
    Surface(onClick=onClick,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(4.dp).height(42.dp).background(Color(task.color),RoundedCornerShape(3.dp)))
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
        Text(text,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
        trailing?.let { Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp) }
    }
}
