package com.buddydeus.androidmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.buddydeus.androidmanager.R
import com.buddydeus.androidmanager.domain.*

/** UI events stay bound to the existing authenticated ViewModel operations. */
internal class ManagerActions(
    val login: (String) -> Unit = {},
    val setPin: (String, String) -> Unit = { _, _ -> },
    val changePin: (String, String) -> Unit = { _, _ -> },
    val setEnabled: (Boolean) -> Unit = {},
    val setBlacklisted: (String, Boolean) -> Unit = { _, _ -> },
    val saveSchedule: (List<DayWindow?>) -> Unit = {},
    val loadLogs: (String, LogCategory?, Int) -> Unit = { _, _, _ -> },
    val pickDirectory: () -> Unit = {}, val checkAlarms: () -> Unit = {},
    val recover: () -> Unit = {}, val refresh: () -> Unit = {},
    val leave: () -> Unit = {}, val clearPin: () -> Unit = {},
)

@Composable fun ManagerApp(model: ManagerViewModel, onPick: () -> Unit, onAlarms: () -> Unit) {
    val state by model.ui.collectAsState()
    val actions = remember(model, onPick, onAlarms) {
        ManagerActions(
            login = model::login, setPin = model::setPin, changePin = model::changePin,
            setEnabled = model::setEnabled, setBlacklisted = model::setBlacklisted,
            saveSchedule = model::saveSchedule, loadLogs = model::loadLogs,
            pickDirectory = onPick, checkAlarms = onAlarms, recover = model::recover,
            refresh = model::refresh, leave = model::leave, clearPin = model::clearPin,
        )
    }
    ManagerContent(state, actions)
}

@Composable internal fun ManagerContent(state: ManagerUiState, actions: ManagerActions) {
    ManagerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.screen == Screen.MAIN) ManagementScreens(state, actions)
            else EntryScreen(state, actions)
        }
    }
}

@Composable private fun EntryScreen(state: ManagerUiState, actions: ManagerActions) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(if (state.screen == Screen.LOGIN) 64.dp else 16.dp))
        Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(48.dp).align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.primary)
        Text("应用管控", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.headlineMedium)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Feedback(state.message)
        Feedback(state.error, error = true)
        when (state.screen) {
            Screen.LOGIN -> PinForm("验证管理密码", false, state.busy) { pin, _ -> actions.login(pin) }
            Screen.NEW_PIN -> PinForm("设置新密码", true, state.busy, actions.setPin)
            Screen.OWNER -> Section {
                Text("1. 设备管理", style = MaterialTheme.typography.titleLarge)
                Text("请通过电脑注册 Device Owner。普通设备管理员权限不足。")
                var showCommand by remember { mutableStateOf(false) }
                TextButton(onClick = { showCommand = !showCommand }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showCommand) "收起注册命令" else "查看注册命令") }
                if (showCommand) SupportingText("adb shell dpm set-device-owner\ncom.buddydeus.androidmanager/.platform.ManagerAdminReceiver")
                SupportingText("已有账户可能阻挡注册，请由管理员处理。不要直接清除手机数据。")
                PrimaryAction("重新检查管理身份", !state.busy, actions.refresh)
            }
            Screen.DIRECTORY -> Section {
                Text("2. 密码目录", style = MaterialTheme.typography.titleLarge)
                Text("选择手机主存储 Documents/AndroidManager 目录，可在系统选择器中新建。")
                SupportingText("密码文件独立保存；删除后可恢复管理访问，已有管控规则保留。")
                PrimaryAction("授权本地密码目录", !state.busy, actions.pickDirectory)
            }
            Screen.STORAGE_ERROR -> Section {
                Text("密码存储异常", style = MaterialTheme.typography.titleLarge)
                Text("已有后台管控继续运行。重新选择目录并设置新密码，可保留黑名单和时间表。")
                PrimaryAction("重新设置密码存储", !state.busy, actions.recover)
                SecondaryAction("重新检查", !state.busy, onClick = actions.refresh)
            }
            else -> Unit
        }
    }
}

internal data class DayDraft(val enabled: Boolean, val start: Int, val end: Int)
private val DraftSaver = listSaver<List<DayDraft>, Int>(
    save = { days -> days.flatMap { listOf(if (it.enabled) 1 else 0, it.start, it.end) } },
    restore = { values -> values.chunked(3).map { DayDraft(it[0] == 1, it[1], it[2]) } },
)

@Composable private fun ManagementScreens(state: ManagerUiState, actions: ManagerActions) {
    val config = state.config ?: return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Keep the draft above tab content. Unrelated policy revisions cannot discard edits.
    var draft by rememberSaveable(config.schedule, stateSaver = DraftSaver) {
        mutableStateOf(config.schedule.map { DayDraft(it != null, it?.startMinute ?: 540, it?.endMinute ?: 1200) })
    }
    val dirty = draft.indices.any { i ->
        val saved = config.schedule[i]
        draft[i].enabled != (saved != null) || (draft[i].enabled && (draft[i].start != saved?.startMinute || draft[i].end != saved?.endMinute))
    }
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler {
        if (tab != 0) tab = 0
        else if (dirty) confirmExit = true
        else actions.leave()
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false }, title = { Text("时间表尚未保存") },
        text = { Text("退出管理会丢弃未保存的时间表修改。") },
        confirmButton = { TextButton(onClick = { confirmExit = false; actions.leave() }) { Text("丢弃并退出") } },
        dismissButton = { TextButton(onClick = { confirmExit = false; tab = 2 }) { Text("继续编辑") } },
    )
    val guardedActions = remember(actions, dirty) {
        ManagerActions(leave = { if (dirty) confirmExit = true else actions.leave() })
    }
    val names = listOf("概览", "应用", "时间表", "设置", "日志")
    val icons = listOf(R.drawable.ic_home, R.drawable.ic_apps, R.drawable.ic_clock, R.drawable.ic_settings, R.drawable.ic_logs)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                names.forEachIndexed { i, name ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(painterResource(icons[i]), null, Modifier.size(24.dp)) },
                        label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(8.dp))
                Text(names[tab], Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Feedback(state.message)
                Feedback(state.error, error = true)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> OverviewScreen(state, actions.setEnabled, guardedActions.leave)
                        1 -> AppsScreen(state, actions.setBlacklisted)
                        2 -> ScheduleScreen(state, draft, dirty, { draft = it }, actions.saveSchedule)
                        3 -> SettingsScreen(state, actions)
                        4 -> LogsScreen(state, actions.loadLogs)
                    }
                }
            }
        }
    }
}
