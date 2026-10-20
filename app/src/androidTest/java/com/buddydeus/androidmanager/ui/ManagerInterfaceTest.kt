package com.buddydeus.androidmanager.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Uses only immutable fixtures and callback spies, never ManagerGraph, SAF or DPM. */
class ManagerInterfaceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val today = LocalDate.now(ScheduleEvaluator.zone).toString()
    private fun fixture() = ManagerUiState(
        screen = Screen.MAIN, location = "Documents/AndroidManager/password.json",
        config = ManagerState(initialized = true, schedule = List(7) { when (it) {
            0 -> DayWindow(0, 1439); 1 -> DayWindow(540, 1200); else -> null
        } }, blacklist = setOf("demo.reader"), appCache = mapOf(
            "demo.reader" to AppEntry("demo.reader", "阅读应用"),
            "demo.browser" to AppEntry("demo.browser", "浏览器", system = true),
            "demo.protected" to AppEntry("demo.protected", "系统桌面", protection = "当前桌面"),
        ), records = mapOf("demo.reader" to PolicyRecord(ownSuspended = true, ownHidden = false, error = "系统拒绝隐藏")),
            lastEvaluation = "已执行，部分应用存在异常", alarmStatus = "定时执行不可用：请授予精确闹钟权限", timeResult = "已限制时间设置"),
        logs = listOf("22:40 配置变更 保存周时间表 成功", "22:42 权限异常 定时执行不可用，请授予精确闹钟权限"),
        logsQuery = LogPageKey(today, null, 0),
    )

    @Before fun isolatedEmulatorOnly() {
        assumeTrue("UI fixtures run only on an emulator", Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
    }
    private fun tab(name: String) { rule.onNode(hasText(name) and hasClickAction()).performClick() }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-validation").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun sixPageGalleryPreservesActualFailureMessages() {
        val state = mutableStateOf(fixture())
        rule.setContent { ManagerContent(state.value, ManagerActions()) }
        screenshot("overview")
        tab("应用")
        rule.onNodeWithText("暂停：本软件已施加").assertExists()
        rule.onNodeWithText("隐藏：未确认施加").assertExists()
        rule.onNodeWithText("执行异常：系统拒绝隐藏").assertExists()
        screenshot("apps")
        tab("时间表"); screenshot("schedule")
        tab("设置"); screenshot("settings")
        tab("日志"); screenshot("logs")
        rule.runOnIdle { state.value = ManagerUiState(screen = Screen.LOGIN) }
        rule.onNodeWithText("管理密码").assertExists()
        rule.onNodeWithText("0000", substring = true).assertDoesNotExist()
        rule.onNodeWithText("概览").assertDoesNotExist()
        screenshot("login")
    }

    @Test fun allDayIsAvailableWhileOffAndOnlySaveCommitsDraftAcrossTabsAndRevisions() {
        val state = mutableStateOf(fixture())
        var saved: List<DayWindow?>? = null
        rule.setContent { ManagerContent(state.value, ManagerActions(saveSchedule = { saved = it })) }
        tab("时间表")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        rule.onNodeWithText("周三设为全天").performClick()
        rule.runOnIdle { assertNull(saved); state.value = state.value.copy(config = state.value.config!!.copy(revision = 1)) }
        tab("应用"); tab("时间表")
        rule.onNodeWithText("有未保存的修改").assertExists()
        rule.onNodeWithText("保存周时间表").performClick()
        rule.runOnIdle { assertEquals(DayWindow(0, 1439), saved!![2]); assertEquals(DayWindow(540, 1200), saved!![1]) }
    }

    @Test fun draftSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { ManagerContent(fixture(), ManagerActions()) }
        tab("时间表")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        rule.onNodeWithText("周二设为全天").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("有未保存的修改").assertExists()
        rule.onNodeWithText("周二全天（已选择）").assertExists()
    }

    @Test fun busyDisablesAllDayAndSave() {
        rule.setContent { ManagerContent(fixture().copy(busy = true), ManagerActions()) }
        tab("时间表")
        rule.onNodeWithText("周一全天（已选择）").assertIsNotEnabled()
        rule.onNodeWithText("保存周时间表").assertIsNotEnabled()
    }

    @Test fun protectedAppCannotBeSelectedAndDualPolicyStatusIsSeparate() {
        rule.setContent { ManagerContent(fixture(), ManagerActions()) }
        tab("应用")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        rule.onNode(hasText("系统桌面") and isToggleable()).assertIsNotEnabled()
    }

    @Test fun cancellingNativeTimePickerLeavesDraftUnchanged() {
        rule.setContent { ManagerContent(fixture(), ManagerActions()) }
        tab("时间表")
        rule.onNodeWithContentDescription("周一结束时间 23:59").performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.onNodeWithText("有未保存的修改").assertDoesNotExist()
        rule.onNodeWithText("周一全天（已选择）").assertExists()
    }

    @Test fun passwordConfirmationRejectsMismatchWithoutCallingStorage() {
        var called = false
        rule.setContent { ManagerContent(ManagerUiState(screen = Screen.NEW_PIN), ManagerActions(setPin = { _, _ -> called = true })) }
        rule.onNodeWithText("新密码", substring = false).performTextInput("123456")
        rule.onNodeWithText("再次输入密码").performTextInput("654321")
        rule.onNodeWithText("保存新密码").performScrollTo().performClick()
        rule.onNodeWithText("两次密码不一致，请重新输入").assertExists()
        rule.runOnIdle { assertFalse(called) }
    }

    @Test fun invalidLogDateNeverStartsRead() {
        var called = false
        rule.setContent { ManagerContent(fixture(), ManagerActions(loadLogs = { _, _, _ -> called = true })) }
        tab("日志")
        rule.onNodeWithText("日期 YYYY-MM-DD").performTextReplacement("2026-02-30")
        rule.onNodeWithText("查询").performClick()
        rule.onNodeWithText("请输入有效日期，例如 2026-10-20").assertExists()
        rule.runOnIdle { assertFalse(called) }
    }

    @Test fun invalidSameMinuteDraftIsRejectedWithDaySpecificFeedback() {
        var called = false
        rule.setContent { ManagerTheme {
            ScheduleScreen(fixture(), List(7) { DayDraft(it == 0, 540, 540) }, true, {}, { called = true })
        } }
        rule.onNodeWithText("保存周时间表").performClick()
        rule.onNodeWithText("周一起止时间不能相同").assertExists()
        rule.runOnIdle { assertFalse(called) }
    }

    @Test fun leavingDirtyScheduleRequiresExplicitDiscard() {
        var left = false
        rule.setContent { ManagerContent(fixture(), ManagerActions(leave = { left = true })) }
        tab("时间表")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        rule.onNodeWithText("周二设为全天").performClick()
        tab("概览")
        rule.onNodeWithText("退出管理").performScrollTo().performClick()
        rule.onNodeWithText("时间表尚未保存").assertExists()
        rule.runOnIdle { assertFalse(left) }
        rule.onNodeWithText("继续编辑").performClick()
        rule.onNodeWithText("有未保存的修改").assertExists()
    }

    @Test fun changedLogFilterNeverShowsPreviousQueryAsCurrentResults() {
        rule.setContent { ManagerContent(fixture(), ManagerActions()) }
        tab("日志")
        rule.onNodeWithText("22:40 配置变更 保存周时间表 成功").assertExists()
        rule.onNodeWithText("配置变更", substring = false).performClick()
        rule.onNodeWithText("22:40 配置变更 保存周时间表 成功").assertDoesNotExist()
        rule.onNodeWithText("请选择日期和类别后查询").assertExists()
    }

    @Test fun loginAndLargeFontScheduleRemainScrollable() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ManagerContent(fixture(), ManagerActions())
            }
        }
        tab("时间表")
        rule.onNodeWithText("保存周时间表").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(7)
        rule.onNodeWithText("周日设为全天").assertIsDisplayed()
        screenshot("schedule-large-font")
    }

    @Test fun landscapeKeepsSaveAndBottomNavigationVisible() {
        rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        rule.waitUntil(10000) { rule.activity.resources.configuration.screenWidthDp > rule.activity.resources.configuration.screenHeightDp }
        rule.setContent { ManagerContent(fixture(), ManagerActions()) }
        tab("时间表")
        rule.onNodeWithText("保存周时间表").assertIsDisplayed()
        rule.onNode(hasText("日志") and hasClickAction()).assertIsDisplayed()
        screenshot("schedule-landscape")
    }
}
