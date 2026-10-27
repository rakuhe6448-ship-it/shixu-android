package cn.shixu.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.shixu.app.PlannerViewModel

@Composable
fun PermissionWelcome(vm: PlannerViewModel) {
    var confirmManual by remember { mutableStateOf(false) }
    val tick=vm.permissionTick
    val ready=remember(tick) { vm.app.alarms.exactAllowed() && vm.app.alarms.notificationsAllowed() }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { PageHeading("先让提醒准备好", "欢迎来到时序，先完成这一步。") }
        item { Text("通知权限用来显示到点提醒；精确闹钟权限用来按你指定的时间触发。设置完成后，再开始安排事项。",Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.bodyLarge) }
        item { Box(Modifier.padding(horizontal=18.dp)) { PermissionCard(vm) } }
        item { Text("从系统设置返回后，会自动重新检查。后台运行设置因手机品牌而异，可按卡片中的说明调整。",Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button(onClick={vm.finishPermissionIntro()},enabled=ready,modifier=Modifier.padding(horizontal=20.dp).fillMaxWidth().height(52.dp),shape=RoundedCornerShape(18.dp)) { Text("完成设置，进入时序") } }
        item { TextButton(onClick={confirmManual=true},modifier=Modifier.padding(horizontal=20.dp).fillMaxWidth()) { Text("暂时只用排期") } }
    }
    if(confirmManual) AlertDialog(onDismissRequest={confirmManual=false},title={Text("暂不开启提醒？")},text={Text("你仍可手动管理排期。权限未齐全时，通知可能无法显示或无法准时触发；之后可在提醒页或设置中补齐。")},confirmButton={TextButton(onClick={confirmManual=false;vm.finishPermissionIntro(manualOnly=true)}) { Text("仅使用排期") }},dismissButton={TextButton(onClick={confirmManual=false}) { Text("继续设置权限") }})
}
