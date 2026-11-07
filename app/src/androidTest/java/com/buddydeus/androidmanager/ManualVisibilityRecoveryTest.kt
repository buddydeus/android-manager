package com.buddydeus.androidmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.application.ManagerGraph
import com.buddydeus.androidmanager.domain.AuditEvent
import com.buddydeus.androidmanager.domain.LogCategory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit operator recovery, never an automatic policy cleanup or a normal test fixture. */
@RunWith(AndroidJUnit4::class)
class ManualVisibilityRecoveryTest {
    @Test fun restoreOnlyExplicitlyAuthorizedMarketVisibility() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("须在用户明确授权后显式执行",args.getString("manualRecoveryMode")=="restore-market-visibility")
        val id=UUID.fromString(args.getString("manualRecoveryId")).toString()
        val target=args.getString("physicalRecoveryTarget")
        require(target=="com.heytap.market") { "仅允许本次明确授权的软件商店" }
        val expectedRevision=checkNotNull(args.getString("expectedRevision")).toLong()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.buddydeus.androidmanager" && BuildConfig.DEBUG)
        val graph=ManagerGraph.get(context)
        val before=graph.coordinator.refresh()
        check(before.initialized && !before.enabled && before.revision==expectedRevision) { "状态已变化或管理未关闭，停止恢复" }
        check(graph.policy.isOwner())
        graph.coordinator.remoteAction("用户授权恢复软件商店显示") {
            val current=graph.coordinator.refresh()
            check(current==before) { "状态已变化，停止恢复" }
            val policy=graph.policy.inspect(target)
            check(policy.hidden!=null && policy.suspended==false) { "无法确认状态或仍被暂停，停止恢复" }
            graph.audit.append(AuditEvent(LogCategory.CONFIG_CHANGE,"授权恢复显示","已记录用户显式授权 $id",target,before.revision))
            if(policy.hidden==true) {
                val result=graph.policy.setHidden(target,false)
                graph.audit.append(AuditEvent(LogCategory.POLICY_EXECUTION,"手动UNHIDE",if(result.success) "系统调用返回成功" else result.reason,target,before.revision))
                check(result.success) { result.reason }
            }
            assertEquals(false,graph.policy.inspect(target).hidden)
        }
        val after=graph.coordinator.refresh()
        assertEquals(before.initialized,after.initialized);assertEquals(before.enabled,after.enabled)
        assertEquals(before.blacklist,after.blacklist);assertEquals(before.schedule,after.schedule)
    }
}
