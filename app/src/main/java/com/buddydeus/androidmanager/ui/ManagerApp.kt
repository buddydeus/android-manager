package com.buddydeus.androidmanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.app.TimePickerDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.buddydeus.androidmanager.domain.*
import java.time.LocalDate

@Composable fun ManagerApp(model:ManagerViewModel,onPick:()->Unit,onAlarms:()->Unit) {
    val state by model.ui.collectAsState()
    MaterialTheme(colorScheme=lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                Text("应用管控",style=MaterialTheme.typography.headlineMedium)
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.message.isNotBlank()) Text(state.message,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(vertical=8.dp))
                if(state.error.isNotBlank()) Text(state.error,color=MaterialTheme.colorScheme.error)
                when(state.screen) {
                    Screen.OWNER -> Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("首次初始化 · 设备管理",style=MaterialTheme.typography.titleLarge)
                        Text("请通过电脑为本应用注册 Device Owner。普通设备管理员授权不足以执行管控。")
                        Text("adb shell dpm set-device-owner\ncom.buddydeus.androidmanager/.platform.ManagerAdminReceiver")
                        Text("如系统提示已有账户，须先由管理员处理账户。不要直接清除手机数据。")
                        Button(onClick=model::refresh,enabled=!state.busy) { Text("重新检查管理身份") }
                    }
                    Screen.DIRECTORY -> Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("选择密码目录",style=MaterialTheme.typography.titleLarge)
                        Text("请选择手机主存储 Documents/AndroidManager 目录。可在系统选择器中创建文件夹；密码文件独立保存，删除后可恢复管理访问。")
                        Button(onClick=onPick,enabled=!state.busy) { Text("授权本地密码目录") }
                    }
                    Screen.LOGIN -> PinForm("验证管理密码",false,state.busy) { pin,_ -> model.login(pin) }
                    Screen.NEW_PIN -> PinForm("设置新密码（至少6位数字）",true,state.busy,model::setPin)
                    Screen.STORAGE_ERROR -> Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("密码存储异常",style=MaterialTheme.typography.titleLarge)
                        Text("已有后台管控继续运行。可重新选择目录并设置新密码；不要求旧密码，保留黑名单及时间表。")
                        Button(onClick=model::recover,enabled=!state.busy) { Text("重新设置密码存储") }
                        OutlinedButton(onClick=model::refresh,enabled=!state.busy) { Text("重新检查") }
                    }
                    Screen.MAIN -> ManagementScreens(state,model,onAlarms)
                }
            }
        }
    }
}
@Composable private fun PinForm(title:String,confirm:Boolean,busy:Boolean,onSubmit:(String,String)->Unit) {
    var pin by remember(title) { mutableStateOf("") };var repeated by remember(title) { mutableStateOf("") }
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge)
        OutlinedTextField(pin,{pin=it.filter { c->c in '0'..'9' }},label={Text("密码")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth())
        if(confirm) OutlinedTextField(repeated,{repeated=it.filter { c->c in '0'..'9' }},label={Text("再次输入密码")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth())
        if(!confirm) Text("首次使用或确认密码文件被删除后，输入 0000 并设置新密码。")
        Button(onClick={onSubmit(pin,repeated);pin="";repeated=""},enabled=!busy && pin.isNotEmpty() && (!confirm || repeated.isNotEmpty())) { Text(if(confirm) "保存新密码" else "登录") }
    }
}
@Composable private fun ManagementScreens(state:ManagerUiState,model:ManagerViewModel,onAlarms:()->Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val names=listOf("概览","应用","时间表","设置","日志")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        names.forEachIndexed { index,name -> FilterChip(selected=tab==index,onClick={tab=index},label={Text(name)},modifier=Modifier.padding(end=6.dp)) }
    }
    when(tab) {
        0 -> OverviewScreen(state,model)
        1 -> AppsScreen(state,model)
        2 -> ScheduleScreen(state,model)
        3 -> Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("密码文件：${state.location}")
            Text("删除此文件可恢复默认密码；文件不可访问时可从登录入口重新设置。")
            OutlinedButton(onClick=onAlarms) { Text("检查精确闹钟授权") }
            PinForm("修改管理密码",true,state.busy,model::changePin)
            HorizontalDivider()
            Text("清空密码会删除 password.json 并退出管理；下次使用 0000 登录后须设置新密码，现有管控继续运行。")
            OutlinedButton(onClick=model::clearPin,enabled=!state.busy,colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),modifier=Modifier.fillMaxWidth()) { Text("清空密码") }
        }
        4 -> LogsScreen(state,model)
    }
}
@Composable private fun OverviewScreen(state:ManagerUiState,model:ManagerViewModel) {
    val config=state.config ?: return
    Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("管控总开关",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            Switch(checked=config.enabled,onCheckedChange=model::setEnabled,enabled=!state.busy)
        }
        Text(if(config.enabled) "启用意愿：开启" else "启用意愿：关闭")
        Text(config.lastEvaluation)
        Text("当前时段：${if(ScheduleEvaluator.evaluate(config.schedule,java.time.Instant.now()).active) "限制时段" else "非限制时段"}（上海时间）")
        Text(config.timeResult);Text(config.alarmStatus)
        Text("黑名单 ${config.blacklist.size} 个应用")
        HorizontalDivider()
        Text("关闭总开关会解除本软件限制并保留设置和设备管理身份。系统拒绝及部分执行结果可在应用页和日志中查看。")
        OutlinedButton(onClick=model::leave) { Text("退出管理") }
    }
}
@Composable private fun AppsScreen(state:ManagerUiState,model:ManagerViewModel) {
    val config=state.config ?: return
    var query by remember { mutableStateOf("") };var systems by remember { mutableStateOf(false) }
    OutlinedTextField(query,{query=it},label={Text("搜索应用名称或包名")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(systems,{systems=it});Text("仅系统应用") }
    val apps=config.appCache.values.filter { (!systems || it.system) && (it.name.contains(query,true) || it.packageName.contains(query,true)) }.sortedWith(compareByDescending<AppEntry> { it.packageName in config.blacklist }.thenBy { it.name })
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(apps.isEmpty()) item { Text("没有匹配的应用。后台核验完成后将刷新列表。") }
        items(apps,key={it.packageName}) { app ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(app.name,style=MaterialTheme.typography.titleMedium)
                        Text(app.packageName,style=MaterialTheme.typography.bodySmall)
                        Text(if(app.updating) "正在更新 · 保留原策略记录" else if(!app.installed) "未安装 · 保留包名规则" else if(app.system) "系统应用" else "已安装")
                        if(app.protection.isNotEmpty()) Text("受保护：${app.protection}",color=MaterialTheme.colorScheme.error)
                        config.records[app.packageName]?.let { r ->
                            Text("暂停：${if(r.ownSuspended) "本软件已施加" else "未施加"}；隐藏：${if(r.ownHidden) "本软件已施加" else "未施加"}",style=MaterialTheme.typography.bodySmall)
                            if(r.error.isNotEmpty()) Text("${r.result}：${r.error}",color=MaterialTheme.colorScheme.error)
                        }
                    }
                    Switch(checked=app.packageName in config.blacklist,onCheckedChange={model.setBlacklisted(app.packageName,it)},enabled=!state.busy && (app.protection.isEmpty() || app.packageName in config.blacklist))
                }
            }
        }
    }
}
@Composable private fun ScheduleScreen(state:ManagerUiState,model:ManagerViewModel) {
    val config=state.config ?: return
    var windows by remember(config.revision) { mutableStateOf(config.schedule.map { it!=null }) }
    var starts by remember(config.revision) { mutableStateOf(config.schedule.map { it?.startMinute ?: 540 }) }
    var ends by remember(config.revision) { mutableStateOf(config.schedule.map { it?.endMinute ?: 1200 }) }
    var error by remember { mutableStateOf("") }
    val days=listOf("周一","周二","周三","周四","周五","周六","周日")
    Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("所有黑名单应用共用；结束早于开始则延续至次日。")
        days.forEachIndexed { i,name ->
            Card {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) { Text(name,Modifier.weight(1f));Text(if(windows[i]) "限制" else "本日不设置");Switch(windows[i],{v -> windows=windows.toMutableList().also { it[i]=v }}) }
                    if(windows[i]) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TimeSelectionButton("${name}开始时间",starts[i],Modifier.weight(1f),!state.busy) { v->starts=starts.toMutableList().also { it[i]=v } }
                        TimeSelectionButton("${name}结束时间",ends[i],Modifier.weight(1f),!state.busy) { v->ends=ends.toMutableList().also { it[i]=v } }
                    }
                }
            }
        }
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        Button(onClick={ runCatching { List(7) { i->if(windows[i]) DayWindow(starts[i],ends[i]) else null } }.onSuccess { error="";model.saveSchedule(it) }.onFailure { error="起止时间不能相同" } },enabled=!state.busy) { Text("保存周时间表") }
    }
}
private fun timeText(minute:Int)="%02d:%02d".format(minute/60,minute%60)
@Composable private fun TimeSelectionButton(label:String,minute:Int,modifier:Modifier,enabled:Boolean,onSelect:(Int)->Unit) {
    val context=LocalContext.current
    var picking by remember { mutableStateOf(false) }
    if(picking) DisposableEffect(context) {
        val dialog=TimePickerDialog(context,{_,hour,selectedMinute -> onSelect(hour*60+selectedMinute)},minute/60,minute%60,true)
        dialog.setTitle(label)
        dialog.setButton(android.content.DialogInterface.BUTTON_POSITIVE,"确定",dialog)
        dialog.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,"取消",dialog)
        dialog.setOnDismissListener { picking=false }
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        onDispose { dialog.dismiss() }
    }
    OutlinedButton(onClick={picking=true},enabled=enabled,modifier=modifier.heightIn(min=56.dp)) {
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text(label,style=MaterialTheme.typography.labelMedium)
            Text(timeText(minute),style=MaterialTheme.typography.titleMedium)
        }
    }
}
@Composable private fun LogsScreen(state:ManagerUiState,model:ManagerViewModel) {
    var date by remember { mutableStateOf(LocalDate.now(ScheduleEvaluator.zone).toString()) }
    var category by remember { mutableStateOf<LogCategory?>(null) };var offset by remember { mutableIntStateOf(0) }
    OutlinedTextField(date,{date=it},label={Text("日期 YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        (listOf<LogCategory?>(null)+LogCategory.entries).forEach { c -> FilterChip(category==c,{category=c;offset=0},label={Text(c?.label ?: "全部")},modifier=Modifier.padding(end=4.dp)) }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={offset=0;model.loadLogs(date,category,offset)},enabled=!state.busy) { Text("查询") }
        OutlinedButton(onClick={offset=(offset-50).coerceAtLeast(0);model.loadLogs(date,category,offset)},enabled=!state.busy && offset>0) { Text("上一页") }
        OutlinedButton(onClick={offset+=50;model.loadLogs(date,category,offset)},enabled=!state.busy && state.logs.size==50) { Text("下一页") }
    }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(state.logs.isEmpty()) item { Text("暂无记录；请选择日期和类别后查询。") }
        items(state.logs) { Text(it);HorizontalDivider() }
    }
}
