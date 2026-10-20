package com.buddydeus.androidmanager.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import com.buddydeus.androidmanager.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.buddydeus.androidmanager.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable internal fun PinForm(title: String, confirm: Boolean, busy: Boolean, onSubmit: (String, String) -> Unit) {
    var pin by remember(title) { mutableStateOf("") }
    var repeated by remember(title) { mutableStateOf("") }
    var error by remember(title) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, Modifier.fillMaxWidth(), style = if (confirm) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge, textAlign = if (confirm) TextAlign.Start else TextAlign.Center)
        if (confirm) SupportingText("至少6位数字")
        OutlinedTextField(
            pin, { pin = it.filter { c -> c in '0'..'9' }; error = "" },
            label = { Text(if (confirm) "新密码" else "管理密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            enabled = !busy, modifier = Modifier.fillMaxWidth(), isError = error.isNotEmpty(), shape = MaterialTheme.shapes.small,
        )
        if (confirm) OutlinedTextField(
            repeated, { repeated = it.filter { c -> c in '0'..'9' }; error = "" }, label = { Text("再次输入密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            enabled = !busy, modifier = Modifier.fillMaxWidth(), isError = error.isNotEmpty(), shape = MaterialTheme.shapes.small,
        )
        if (error.isNotEmpty()) Feedback(error, error = true)
        PrimaryAction(if (confirm) "保存新密码" else "登录", !busy && pin.isNotEmpty() && (!confirm || repeated.isNotEmpty())) {
            error = when {
                confirm && pin.length < 6 -> "请设置至少6位数字的密码"
                confirm && pin != repeated -> "两次密码不一致，请重新输入"
                else -> ""
            }
            if (error.isEmpty()) { onSubmit(pin, repeated); pin = ""; repeated = "" }
        }
    }
}

@Composable internal fun OverviewScreen(state: ManagerUiState, onEnabled: (Boolean) -> Unit, onLeave: () -> Unit) {
    val config = state.config ?: return
    val time = ScheduleEvaluator.evaluate(config.schedule, Instant.now())
    val boundary = time.nextBoundary?.atZone(ScheduleEvaluator.zone)?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Section { LabeledSwitch("管控总开关", config.enabled, !state.busy, onEnabled) }
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (time.active) "当前限制时段" else "当前非限制时段", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                SupportingText(when {
                    config.schedule.all { it == null } -> "尚未设置限制时段"
                    boundary != null -> "下个时段边界：$boundary"
                    time.active -> "整周连续限制"
                    else -> "当前没有下个边界"
                })
                SupportingText("上海时间；实际结果见下方执行状态")
            }
        }
        Section {
            StatusRow("执行结果", config.lastEvaluation)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatusRow("定时执行", config.alarmStatus, config.alarmStatus.startsWith("定时执行不可用"))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatusRow("时间设置限制", config.timeResult.ifBlank { "尚无执行结果" })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatusRow("黑名单", "${config.blacklist.size} 个应用")
        }
        SupportingText(if (config.enabled) "总开关已开启，按时间表执行限制；开启不代表每个应用都已成功限制。" else "总开关已关闭，将解除本软件施加的限制并保留设置和设备管理身份。")
        SecondaryAction("退出管理", !state.busy, onClick = onLeave)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun StatusRow(title: String, value: String, error: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (error) Feedback(value, error = true) else SupportingText(value)
    }
}

@Composable internal fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, description: String? = null, appIcon: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (appIcon) Icon(painterResource(R.drawable.ic_apps), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (description != null) SupportingText(description)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled, colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant, uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledUncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f),
            disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f),
            disabledUncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f),
        ))
    }
}

@Composable internal fun AppsScreen(state: ManagerUiState, onBlacklisted: (String, Boolean) -> Unit) {
    val config = state.config ?: return
    var query by rememberSaveable { mutableStateOf("") }
    var systems by rememberSaveable { mutableStateOf(false) }
    val apps = config.appCache.values.filter {
        (!systems || it.system) && (it.name.contains(query, true) || it.packageName.contains(query, true))
    }.sortedWith(compareByDescending<AppEntry> { it.packageName in config.blacklist }.thenBy { it.name })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            OutlinedTextField(query, { query = it }, label = { Text("搜索应用名称或包名") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 48.dp).toggleable(systems, role = Role.Checkbox, onValueChange = { systems = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(systems, onCheckedChange = null)
                Text("仅系统应用", Modifier.padding(start = 12.dp))
            }
        }
        if (apps.isEmpty()) item { Section { Text("没有匹配的应用"); SupportingText("尝试修改名称、包名或系统应用筛选。后台核验完成后列表会刷新。") } }
        items(apps, key = { it.packageName }) { app ->
            ListEntry {
                LabeledSwitch(
                    label = app.name, checked = app.packageName in config.blacklist,
                    enabled = !state.busy && (app.protection.isEmpty() || app.packageName in config.blacklist),
                    onChange = { onBlacklisted(app.packageName, it) },
                    description = if (app.packageName in config.blacklist) "已加入黑名单" else "未加入黑名单", appIcon = true,
                )
                SupportingText(app.packageName)
                SupportingText(when {
                    app.updating -> "正在更新，保留原策略记录"
                    !app.installed -> "未安装，保留包名规则"
                    app.system -> "系统应用"
                    else -> "已安装"
                })
                if (app.protection.isNotEmpty()) Feedback("受保护：${app.protection}", error = true)
                config.records[app.packageName]?.let { record ->
                    SupportingText("暂停：${if (record.ownSuspended) "本软件已施加" else "未确认施加"}")
                    SupportingText("隐藏：${if (record.ownHidden) "本软件已施加" else "未确认施加"}")
                    if (record.error.isNotEmpty()) Feedback("执行异常：${record.error}", error = true)
                    if (record.pending.isNotEmpty()) SupportingText("策略变更待确认")
                    if (record.unknownSuspended || record.unknownHidden) SupportingText("部分策略归属未知，请查看日志")
                }
            }
        }
    }
}

@Composable internal fun ScheduleScreen(
    state: ManagerUiState, draft: List<DayDraft>, dirty: Boolean,
    onDraft: (List<DayDraft>) -> Unit, onSave: (List<DayWindow?>) -> Unit,
) {
    var errors by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    fun update(index: Int, day: DayDraft) {
        onDraft(draft.toMutableList().also { it[index] = day })
        errors = errors - index
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                SupportingText("上海时间；结束分钟包含至第59秒。结束早于开始则延续至次日。")
            }
            items(7, key = { it }) { i ->
                val day = draft[i]
                Section {
                    LabeledSwitch(days[i], day.enabled, !state.busy, { update(i, day.copy(enabled = it)) }, if (day.enabled) null else "本日未设置")
                    SecondaryAction(if (day.enabled && day.start == 0 && day.end == 1439) "${days[i]}全天（已选择）" else "${days[i]}设为全天", !state.busy) {
                        update(i, DayDraft(true, 0, 1439))
                    }
                    if (day.enabled) {
                        TimeFields(days[i], day, !state.busy, { update(i, day.copy(start = it)) }, { update(i, day.copy(end = it)) })
                        if (day.end < day.start) SupportingText("延续至次日 ${timeText(day.end)}:59")
                    }
                    if (i in errors) Feedback("${days[i]}起止时间不能相同", error = true)
                    TimeRail(day)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dirty) SupportingText("有未保存的修改")
                PrimaryAction("保存周时间表", !state.busy) {
                    errors = draft.indices.filter { draft[it].enabled && draft[it].start == draft[it].end }.toSet()
                    if (errors.isEmpty()) onSave(draft.map { if (it.enabled) DayWindow(it.start, it.end) else null })
                }
                if (errors.isNotEmpty()) Text("${errors.joinToString("、") { days[it] }}起止时间相同，请修改后保存", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable private fun TimeFields(name: String, day: DayDraft, enabled: Boolean, onStart: (Int) -> Unit, onEnd: (Int) -> Unit) {
    BoxWithConstraints {
        if (maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeSelectionButton("${name}开始时间", "开始", day.start, Modifier.fillMaxWidth(), enabled, onStart)
                TimeSelectionButton("${name}结束时间", "结束", day.end, Modifier.fillMaxWidth(), enabled, onEnd)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeSelectionButton("${name}开始时间", "开始", day.start, Modifier.weight(1f), enabled, onStart)
                TimeSelectionButton("${name}结束时间", "结束", day.end, Modifier.weight(1f), enabled, onEnd)
            }
        }
    }
}

@Composable private fun TimeRail(day: DayDraft) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary
    // Display the part belonging to this start day; a cross-day continuation is described above.
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        if (day.enabled && day.start != day.end) {
            val end = if (day.end < day.start) 1440 else day.end + 1
            val startX = size.width * day.start / 1440f
            drawRoundRect(fill, Offset(startX, 0f), Size(size.width * (end - day.start) / 1440f, size.height), CornerRadius(size.height / 2))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        SupportingText("00", Modifier.weight(1f))
        SupportingText("24")
    }
}

internal fun timeText(minute: Int) = "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)

@Composable private fun TimeSelectionButton(label: String, caption: String, minute: Int, modifier: Modifier, enabled: Boolean, onSelect: (Int) -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val currentSelect by rememberUpdatedState(onSelect)
    if (picking) DisposableEffect(context) {
        val dialog = TimePickerDialog(context, { _, hour, selectedMinute -> currentSelect(hour * 60 + selectedMinute) }, minute / 60, minute % 60, true)
        dialog.setTitle(label)
        dialog.setButton(android.content.DialogInterface.BUTTON_POSITIVE, "确定", dialog)
        dialog.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "取消", dialog)
        dialog.setOnDismissListener { picking = false }
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        onDispose { dialog.dismiss() }
    }
    OutlinedButton(onClick = { picking = true }, enabled = enabled, shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = 64.dp).semantics { contentDescription = "$label ${timeText(minute)}" }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(caption, style = MaterialTheme.typography.labelMedium)
            Text(timeText(minute), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
        }
    }
}

@Composable internal fun SettingsScreen(state: ManagerUiState, actions: ManagerActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Section {
            Text("权限", style = MaterialTheme.typography.titleLarge)
            StatusRow("精确闹钟", state.config?.alarmStatus ?: "尚无执行结果", state.config?.alarmStatus?.startsWith("定时执行不可用") == true)
            SecondaryAction("检查精确闹钟授权", !state.busy, onClick = actions.checkAlarms)
        }
        Section {
            Text("密码目录", style = MaterialTheme.typography.titleLarge)
            Text(state.location.ifBlank { "未选择密码目录" })
            SupportingText("文件不可访问时可从登录入口重新设置。")
        }
        Section { PinForm("修改管理密码", true, state.busy, actions.changePin) }
        Section {
            Text("清空密码", style = MaterialTheme.typography.titleLarge)
            SupportingText("删除 password.json 并退出管理；下次用 0000 登录后须设置新密码。现有管控继续运行，规则和目录授权保留。")
            SecondaryAction("清空密码", !state.busy, destructive = true, onClick = actions.clearPin)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable internal fun LogsScreen(state: ManagerUiState, onLoad: (String, LogCategory?, Int) -> Unit) {
    var date by rememberSaveable { mutableStateOf(LocalDate.now(ScheduleEvaluator.zone).toString()) }
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val category = categoryName?.let(LogCategory::valueOf)
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var dateError by remember { mutableStateOf("") }
    val matches = state.logsQuery == LogPageKey(date, category, offset)
    fun load(page: Int) {
        if (runCatching { LocalDate.parse(date) }.isFailure) { dateError = "请输入有效日期，例如 2026-10-20"; return }
        dateError = ""; offset = page; onLoad(date, category, page)
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            OutlinedTextField(date, { date = it; dateError = "" }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true,
                enabled = !state.busy, modifier = Modifier.fillMaxWidth(), isError = dateError.isNotEmpty(), shape = MaterialTheme.shapes.small)
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<LogCategory?>(null) + LogCategory.entries).forEach { c ->
                    FilterChip(category == c, { categoryName = c?.name; offset = 0 }, label = { Text(c?.label ?: "全部") }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        if (dateError.isNotEmpty()) item { Feedback(dateError, error = true) }
        item { PrimaryAction("查询", !state.busy) { load(0) } }
        when {
            state.busy -> item { SupportingText("正在读取日志…") }
            !matches -> item { Section { Text("请选择日期和类别后查询") } }
            state.logs.isEmpty() -> item { Section { Text("暂无记录"); SupportingText("该日期和类别下没有可显示的日志。读取异常请查看上方提示。") } }
            else -> items(state.logs) { line -> ListEntry { Text(line, style = MaterialTheme.typography.bodyLarge) } }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { load((offset - 50).coerceAtLeast(0)) }, enabled = !state.busy && matches && offset > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("上一页") }
                OutlinedButton(onClick = { load(offset + 50) }, enabled = !state.busy && matches && state.logs.size == 50, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("下一页") }
            }
        }
    }
}
