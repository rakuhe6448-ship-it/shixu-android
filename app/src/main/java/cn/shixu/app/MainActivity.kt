package cn.shixu.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.shixu.app.ui.*
import java.time.*

class MainActivity : ComponentActivity() {
    private val vm: PlannerViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if(savedInstanceState==null) acceptIntent(intent)
        setContent { PlannerRoot(vm) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    @Suppress("DEPRECATION")
    private fun acceptIntent(intent: Intent) {
        intent.getStringExtra("taskId")?.let { vm.detailsId=it }
        intent.getStringExtra("date")?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull()?.let { vm.selectedDate=it;vm.month=YearMonth.from(it);vm.page=0 } }
        if(intent.action in setOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) {
            val uris=if(intent.action==Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty() else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            vm.receiveShare(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),uris.filter { it.scheme=="content" }.take(4))
        }
    }
}

@Composable
fun PlannerRoot(vm: PlannerViewModel) {
    val p by vm.preferences.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val snackbar=remember { SnackbarHostState() }
    val lifecycle=LocalLifecycleOwner.current
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) vm.refreshPermissions() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    ShiXuTheme(p) {
        if(!vm.preferencesLoaded) {
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() } }
            return@ShiXuTheme
        }
        if(!vm.permissionIntroComplete) {
            Scaffold(containerColor=MaterialTheme.colorScheme.background,snackbarHost={SnackbarHost(snackbar)}) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { PermissionWelcome(vm) } }
            return@ShiXuTheme
        }
        Scaffold(containerColor=MaterialTheme.colorScheme.background,snackbarHost={SnackbarHost(snackbar)},bottomBar={
            NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceContainerLow,tonalElevation=0.dp) {
                val labels=listOf("排期","待办","提醒","AI 聊天","设置")
                val icons=listOf(Icons.Outlined.CalendarMonth,Icons.Outlined.Checklist,Icons.Outlined.NotificationsNone,Icons.Outlined.ChatBubbleOutline,Icons.Outlined.Tune)
                labels.forEachIndexed { index,label -> NavigationBarItem(selected=vm.page==index,onClick={vm.page=index},icon={Icon(icons[index],label,Modifier.size(23.dp))},label={Text(label)}) }
            }
        },floatingActionButton={if(vm.page in 0..2) ExtendedFloatingActionButton(onClick={vm.newTask()},icon={Icon(Icons.Outlined.Add,null)},text={Text("新建事项")},containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary)}) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when(vm.page) {
                    0 -> CalendarScreen(vm,tasks)
                    1 -> TodoScreen(vm,tasks)
                    2 -> RemindersScreen(vm,tasks,reminders)
                    3 -> ChatScreen(vm,messages,tasks,links,p)
                    else -> SettingsScreen(vm,p)
                }
            }
        }
        vm.editor?.let { TaskEditor(vm,it) }
        vm.detailsId?.let { id -> tasks.find { it.id==id && it.deletedAt==null }?.let { TaskDetails(vm,it,reminders.filter { r -> r.taskId==id }) } }
        vm.importPreview?.let { preview ->
            AlertDialog(onDismissRequest={if(!vm.dataBusy) vm.importPreview=null},title={Text("恢复这份备份？")},text={Text("包含 ${preview.tasks.size} 个事项、${preview.reminders.size} 条提醒、${preview.messages.size} 条消息和 ${preview.images.size} 张图片。\n\n恢复会替换本机现有数据，未确认的 AI 建议会失效。请先导出当前数据备份。")},confirmButton={Button(onClick=vm::restoreBackup,enabled=!vm.dataBusy){Text(if(vm.dataBusy) "恢复中…" else "替换并恢复")}},dismissButton={TextButton(onClick={vm.importPreview=null},enabled=!vm.dataBusy){Text("取消")}})
        }
    }
}
