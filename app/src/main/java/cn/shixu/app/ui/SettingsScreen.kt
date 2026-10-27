@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.shixu.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.shixu.app.PlannerViewModel
import cn.shixu.app.BuildConfig
import cn.shixu.app.ai.ApiProtocol
import cn.shixu.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: PlannerViewModel,p: Preferences) {
    var edit by remember(p) { mutableStateOf(p) }
    var vaultError by remember { mutableStateOf(vm.apiStorageError) }
    var key by remember(p.provider) { mutableStateOf(try { vm.app.vault.read(p.provider) } catch(e: Exception) { vaultError=e.message; "" }) }
    var showKey by remember { mutableStateOf(false) }
    var apiExpanded by remember { mutableStateOf(key.isBlank()) }
    var appearanceExpanded by remember { mutableStateOf(false) }
    var personaExpanded by remember { mutableStateOf(false) }
    var profileLoading by remember { mutableStateOf(false) }
    val profileDrafts=remember { mutableMapOf<String,Pair<ApiProfile,String>>() }
    val scope=rememberCoroutineScope()
    var endpointFocused by remember { mutableStateOf(false) }
    var keyFocused by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    var modelSearch by remember { mutableStateOf("") }
    LaunchedEffect(edit.provider,edit.endpoint,key,endpointFocused,keyFocused,profileLoading,apiExpanded) {
        vm.clearModels()
        if(profileLoading || !apiExpanded || endpointFocused || keyFocused || key.isBlank() || edit.endpoint.isBlank()) return@LaunchedEffect
        if(runCatching { ApiProtocol.modelsEndpoint(edit.endpoint);ApiProtocol.validKey(key) }.isFailure) return@LaunchedEffect
        delay(800)
        vm.loadModels(edit.endpoint,key)
    }
    DisposableEffect(vm) { onDispose { vm.clearModels() } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportBackup) }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::previewImport) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=36.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { PageHeading("设置", "让时序，成为你习惯的样子。") }
        item { Box(Modifier.padding(horizontal=18.dp)) { SoftCard {
            Row(Modifier.fillMaxWidth().clickable { apiExpanded=!apiExpanded },verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Outlined.AutoAwesome,null,tint=MaterialTheme.colorScheme.primary); Text("  AI 连接",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium); Icon(if(apiExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null) }
            Text(if(key.isBlank()) "尚未配置 · 手动功能照常使用" else "使用自己的 API，直接连接模型服务",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(apiExpanded) {
                Text("服务类型",style=MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { ApiProviders.labels.forEach { (provider,label) ->
                    FilterChip(selected=edit.provider==provider,enabled=!profileLoading,onClick={
                        if(edit.provider!=provider) {
                            profileDrafts[edit.provider]=edit.apiProfile() to key
                            profileLoading=true;vm.clearModels();modelPicker=false
                            scope.launch {
                                try {
                                    val profile=profileDrafts[provider] ?: vm.apiProfile(provider)
                                    edit=edit.withProfile(provider,profile.first);key=profile.second;vaultError=null
                                } catch(e: Exception) { vaultError=e.message ?: "服务配置读取失败" }
                                finally { profileLoading=false }
                            }
                        }
                    },label={Text(label)})
                } }
                Text("两类服务分别保存地址、模型和 Key；修改后请保存设置。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(edit.endpoint,{edit=edit.copy(endpoint=it)},label={Text("API 地址（HTTPS）")},supportingText={Text(if(edit.provider==ApiProviders.DEEPSEEK) "预设基础地址：https://api.deepseek.com/v1" else "填写你的服务基础地址，例如 https://你的服务地址/v1")},modifier=Modifier.fillMaxWidth().onFocusChanged { endpointFocused=it.isFocused },singleLine=true,enabled=!profileLoading)
                OutlinedTextField(key,{key=it;vaultError=null},label={Text("API Key")},visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),trailingIcon={IconButton(onClick={showKey=!showKey}){Icon(if(showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,"显示或隐藏 Key")}},modifier=Modifier.fillMaxWidth().onFocusChanged { keyFocused=it.isFocused },singleLine=true,enabled=!profileLoading)
                vaultError?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                OutlinedTextField(edit.model,{edit=edit.copy(model=it)},label={Text("模型名称")},supportingText={Text("可从服务列表选择，也可以手动填写。")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!profileLoading)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={modelSearch="";modelPicker=true;if(vm.availableModels.isEmpty() && !vm.modelLoading) vm.loadModels(edit.endpoint,key)},enabled=!profileLoading && key.isNotBlank() && edit.endpoint.isNotBlank()) { Text(if(vm.modelLoading) "获取模型中…" else "选择模型${if(vm.availableModels.isNotEmpty()) "（${vm.availableModels.size}）" else ""}") }
                    TextButton(onClick={vm.loadModels(edit.endpoint,key)},enabled=!profileLoading && !vm.modelLoading && key.isNotBlank() && edit.endpoint.isNotBlank()) { Text("刷新模型列表") }
                }
                if(vm.modelLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.modelError?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                Text("填好地址和 Key、离开输入框后，会自动获取模型列表。列表来自服务，不代表每个模型都支持聊天或图片。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                SettingSwitch("模型支持图片输入", "关闭时使用本地中文 OCR，再发送识别文字。",edit.vision) { edit=edit.copy(vision=it) }
                SettingSwitch("请求 JSON 输出", "接口不支持 response_format 时可关闭，程序仍会校验返回内容。",edit.jsonMode) { edit=edit.copy(jsonMode=it) }
                Text("仅发送本次消息、图片及相关事项和必要上下文。Key 由 Android Keystore 加密，不写入备份。连接测试会发送一个极短请求。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick={vm.testApi(edit,key)},enabled=!profileLoading && !vm.apiTesting && key.isNotBlank() && edit.model.isNotBlank() && edit.endpoint.isNotBlank()) { Text(if(vm.apiTesting) "连接中…" else "测试连接") }
            }
        } } }
        item { Box(Modifier.padding(horizontal=18.dp)) { SoftCard {
            Row(Modifier.fillMaxWidth().clickable { appearanceExpanded=!appearanceExpanded },verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Outlined.Palette,null,tint=MaterialTheme.colorScheme.primary); Text("  外观与桌面组件",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium); Icon(if(appearanceExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null) }
            if(appearanceExpanded) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) { listOf("SYSTEM" to "跟随系统","LIGHT" to "浅色","DARK" to "深色").forEach { (v,label) -> FilterChip(selected=edit.theme==v,onClick={edit=edit.copy(theme=v)},label={Text(label)}) } }
                Text("主题色",style=MaterialTheme.typography.labelLarge)
                ColorChoices(edit.accent,listOf(0xFF66785B,0xFF557C94,0xFF927357,0xFF86749B,0xFF9C6477)) { edit=edit.copy(accent=it) }
                Text("浅色背景",style=MaterialTheme.typography.labelLarge)
                ColorChoices(edit.background,listOf(0xFFF7F8F2,0xFFF4F7FB,0xFFFCF7F2,0xFFF8F4FA,0xFFFFF5F7,0xFFFFFFFF)) { edit=edit.copy(background=it) }
                var customAccent by remember(edit.accent) { mutableStateOf("#%06X".format(edit.accent and 0xFFFFFF)) }
                var customBackground by remember(edit.background) { mutableStateOf("#%06X".format(edit.background and 0xFFFFFF)) }
                OutlinedTextField(customAccent,{customAccent=it},label={Text("自定义主题色 #RRGGBB")},singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={IconButton(onClick={parseHex(customAccent)?.let {edit=edit.copy(accent=it)} ?: vm.notify("请输入 #RRGGBB 格式颜色")}){Icon(Icons.Outlined.Check,"应用主题色")}})
                OutlinedTextField(customBackground,{customBackground=it},label={Text("自定义背景 #RRGGBB")},singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={IconButton(onClick={parseHex(customBackground)?.let {edit=edit.copy(background=it)} ?: vm.notify("请输入 #RRGGBB 格式颜色")}){Icon(Icons.Outlined.Check,"应用背景色")}})
                Text("桌面组件背景不透明度 ${(edit.widgetOpacity*100).toInt()}%")
                Slider(value=edit.widgetOpacity,onValueChange={edit=edit.copy(widgetOpacity=it)},valueRange=0f..1f)
                Text("长按安卓桌面 → 小组件 → 时序，可添加今日、近日、月历三种组件。尺寸可拖动调整。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } } }
        item { Box(Modifier.padding(horizontal=18.dp)) { SoftCard {
            Row(Modifier.fillMaxWidth().clickable { personaExpanded=!personaExpanded },verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Outlined.Face,null,tint=MaterialTheme.colorScheme.primary); Text("  提醒与聊天风格",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium); Icon(if(personaExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null) }
            Text(Personality.styles[edit.personality] ?: "标准助手",color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(personaExpanded) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) { Personality.styles.forEach { (v,label) -> FilterChip(selected=edit.personality==v,onClick={edit=edit.copy(personality=v)},label={Text(label)}) } }
                if(edit.personality=="CUSTOM") OutlinedTextField(edit.customPrompt,{edit=edit.copy(customPrompt=it.take(2000))},label={Text("自定义聊天人格提示词")},minLines=3,modifier=Modifier.fillMaxWidth())
                Text(Personality.notification(edit,TaskEntity(title="准备明天的作业",dueDate=LocalDate.now().plusDays(1).toString())),style=MaterialTheme.typography.bodyMedium)
                Text("通知使用本地模板，不联网调用 AI。自定义人格作用于聊天，通知采用标准模板。选择标准助手即可关闭角色风格。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } } }
        item { Button(onClick={vm.saveSettings(edit,key)},enabled=!profileLoading,modifier=Modifier.padding(horizontal=18.dp).fillMaxWidth().height(50.dp),shape=RoundedCornerShape(16.dp)) { Text("保存设置") } }
        item { TextButton(onClick=vm::showPermissionIntro,modifier=Modifier.padding(horizontal=18.dp)) { Icon(Icons.Outlined.NotificationsActive,null);Text("  重新检查提醒权限") } }
        item { Box(Modifier.padding(horizontal=18.dp)) { SoftCard {
            SectionLabel("本地数据")
            Text("备份事项、提醒、聊天图片、引用关联和变更记录。不包含 API Key。恢复会替换现有本地数据。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick={export.launch("时序备份-${LocalDate.now()}.json")},enabled=!vm.dataBusy) { Text("导出 JSON") }
                OutlinedButton(onClick={import.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!vm.dataBusy) { Text("恢复备份") }
            }
            if(vm.dataBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } } }
        item { Text("时序 ${BuildConfig.VERSION_NAME} · 原生安卓 · 本地优先",Modifier.padding(horizontal=24.dp,vertical=10.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if(modelPicker) AlertDialog(onDismissRequest={modelPicker=false},title={Text("选择模型")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(modelSearch,{modelSearch=it},label={Text("搜索模型")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(vm.modelLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.modelError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            val shown=vm.availableModels.filter { it.id.contains(modelSearch,true) || it.name.contains(modelSearch,true) }
            if(shown.isEmpty() && !vm.modelLoading && vm.modelError==null) Text("没有匹配的模型，可返回手动填写。")
            LazyColumn(Modifier.heightIn(max=340.dp)) { items(shown,key={it.id}) { model ->
                Column(Modifier.fillMaxWidth().clickable { edit=edit.copy(model=model.id);modelPicker=false }.padding(vertical=12.dp)) {
                    Text(model.name,style=MaterialTheme.typography.bodyLarge,color=if(edit.model==model.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    if(model.name!=model.id) Text(model.id,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
        }
    },confirmButton={TextButton(onClick={modelPicker=false}) { Text("关闭") }})
}
private fun parseHex(value: String): Long? = value.trim().removePrefix("#").takeIf { Regex("[0-9a-fA-F]{6}").matches(it) }?.toLongOrNull(16)?.or(0xFF000000)
@Composable
private fun ColorChoices(selected: Long,colors: List<Long>,onChange: (Long)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { colors.forEach { c -> Surface(onClick={onChange(c)},shape=CircleShape,color=Color(c),modifier=Modifier.size(36.dp),border=BorderStroke(if(c==selected) 3.dp else 1.dp,if(c==selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {} } }
}
@Composable
private fun SettingSwitch(title: String,description: String,value: Boolean,onChange: (Boolean)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) { Column(Modifier.weight(1f)) { Text(title,style=MaterialTheme.typography.bodyMedium); Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked=value,onCheckedChange=onChange) }
}
