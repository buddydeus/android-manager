package com.buddydeus.androidmanager.domain

import java.time.Instant

data class DayWindow(val startMinute: Int, val endMinute: Int) {
    init { require(startMinute in 0..1439 && endMinute in 0..1439 && startMinute != endMinute) }
}
data class ScheduleState(val active: Boolean, val nextBoundary: Instant?)
enum class LogCategory(val label: String) {
    LOGIN_SECURITY("登录安全"), CONFIG_CHANGE("配置变更"), POLICY_EXECUTION("管控执行"), PERMISSION_ERROR("权限异常")
}
data class AuditEvent(val category: LogCategory, val action: String, val result: String, val subject: String = "", val revision: Long = 0, val at: Instant = Instant.now())
data class AppEntry(val packageName: String, val name: String, val system: Boolean = false, val installed: Boolean = true, val instance: Long = 0, val protection: String = "", val knownLauncher: Boolean = true, val updating: Boolean = false)
data class PolicyRecord(
    val ownSuspended: Boolean = false, val ownHidden: Boolean = false,
    val pending: String = "", val result: String = "", val error: String = "",
    val operationId: String = "", val revision: Long = 0, val instance: Long = 0,
    val baselineSuspended: Boolean? = null, val baselineHidden: Boolean? = null,
    val unknownSuspended: Boolean = false, val unknownHidden: Boolean = false,
)
data class ManagerState(
    val schemaVersion: Int = 1, val revision: Long = 0, val initialized: Boolean = false,
    val enabled: Boolean = true, val schedule: List<DayWindow?> = List(7) { null },
    val blacklist: Set<String> = emptySet(), val appCache: Map<String, AppEntry> = emptyMap(),
    val records: Map<String, PolicyRecord> = emptyMap(), val ownTimeRestriction: Boolean = false,
    val timePending: Boolean = false, val timeResult: String = "", val lastEvaluation: String = "尚未执行",
    val alarmStatus: String = "未安排", val lastCleanupBoot: Int = -1, val lastPolicyBoot: Int = -1,
) { init { require(schemaVersion == 1 && schedule.size == 7 && revision >= 0) } }
interface StateStore { fun read(): ManagerState; fun write(state: ManagerState) }
fun interface AuditSink { fun append(event: AuditEvent) }
