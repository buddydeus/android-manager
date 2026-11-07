package com.buddydeus.androidmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.application.*
import com.buddydeus.androidmanager.data.StateRepository
import com.buddydeus.androidmanager.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/** User-authorized repair of exactly two legacy system apps; never part of the default suite. */
@RunWith(AndroidJUnit4::class)
class AuthorizedSystemVisibilityTest {
    @Test fun recoverAndVerifyOnlyAuthorizedSystemApps() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("systemVisibilityMode")=="authorized-repair-and-probe")
        val id=UUID.fromString(args.getString("systemVisibilityId")).toString()
        val revision=checkNotNull(args.getString("expectedRevision")).toLong()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(BuildConfig.DEBUG)
        val graph=ManagerGraph.get(context)
        val before=graph.coordinator.refresh()
        check(before.initialized && before.revision==revision && graph.policy.isOwner())
        val targets=setOf("com.heytap.market","com.heytap.browser")
        graph.coordinator.remoteAction("用户授权两项系统应用恢复及验证 $id") {
            check(graph.coordinator.refresh()==before)
            check(!before.enabled || !ScheduleEvaluator.evaluate(before.schedule,Instant.now()).active) { "当前处于禁止时段，停止" }
            for(pkg in targets) {
                check(graph.catalog.protection(pkg).isEmpty())
                check(graph.policy.inspect(pkg).suspended==false)
                graph.audit.append(AuditEvent(LogCategory.CONFIG_CHANGE,"授权恢复显示",id,pkg,before.revision))
                graph.policy.setHidden(pkg,false)
                assertEquals(PackagePolicyState(false,false),graph.policy.inspect(pkg))
                val entry=graph.catalog.load(mapOf(pkg to AppEntry(pkg,pkg))).getValue(pkg)
                val store=StateRepository(File(graph.deviceContext.filesDir,"visibility-probe-$id-$pkg"))
                store.write(ManagerState(initialized=true,blacklist=setOf(pkg),schedule=List(7){DayWindow(0,1439)}))
                val port=object:DevicePolicyPort by graph.policy {
                    override fun applyTime(enabled:Boolean,owned:Boolean)=TimePolicyResult(false,true,"隔离探针不改时间策略")
                    override fun setSuspended(packageName:String,value:Boolean):PolicyStepResult {
                        check(packageName==pkg);val a=graph.policy.inspect(pkg);val r=graph.policy.setSuspended(pkg,value)
                        println("VISIBILITY $pkg suspend=$value before=$a result=$r after=${graph.policy.inspect(pkg)}");return r
                    }
                    override fun setHidden(packageName:String,value:Boolean):PolicyStepResult {
                        check(packageName==pkg);val a=graph.policy.inspect(pkg);val r=graph.policy.setHidden(pkg,value)
                        println("VISIBILITY $pkg hide=$value before=$a result=$r after=${graph.policy.inspect(pkg)}");return r
                    }
                }
                val catalog=object:CatalogPort {
                    override fun load(known:Map<String,AppEntry>)=mapOf(pkg to entry)
                    override fun protection(packageName:String)=graph.catalog.protection(packageName)
                }
                val coordinator=PolicyCoordinator(store,catalog,port,graph.audit)
                try {
                    coordinator.reconcile("授权禁止探针")
                    Thread.sleep(1000)
                    println("VISIBILITY restricted $pkg state=${graph.policy.inspect(pkg)} receipt=${store.read().records[pkg]}")
                    assertEquals(true,graph.policy.inspect(pkg).suspended)
                } finally {
                    var restored:PackagePolicyState?=null
                    try {
                        coordinator.updateConfig("恢复隔离探针") { it.copy(enabled=false) }
                        coordinator.reconcile("授权恢复探针")
                        restored=graph.policy.inspect(pkg)
                    } finally {
                        val actual=graph.policy.inspect(pkg)
                        if(actual!=PackagePolicyState(false,false)) {
                            try {
                                graph.audit.append(AuditEvent(LogCategory.POLICY_EXECUTION,"探针失败授权清理",actual.toString(),pkg,before.revision))
                            } finally {
                                try { graph.policy.setHidden(pkg,false) }
                                finally { graph.policy.setSuspended(pkg,false) }
                            }
                        }
                        assertEquals("授权清理未完成 $pkg",PackagePolicyState(false,false),graph.policy.inspect(pkg))
                    }
                    assertEquals("协调器未完整恢复 $pkg",PackagePolicyState(false,false),restored)
                }
                Thread.sleep(1000)
                assertEquals("恢复后仍有系统限制 $pkg",PackagePolicyState(false,false),graph.policy.inspect(pkg))
                assertTrue(graph.catalog.load(mapOf(pkg to entry)).getValue(pkg).installed)
            }
        }
        graph.coordinator.reconcile("定向恢复后核验")
        val after=graph.coordinator.refresh()
        assertEquals(before.initialized,after.initialized);assertEquals(before.enabled,after.enabled)
        assertEquals(before.blacklist,after.blacklist);assertEquals(before.schedule,after.schedule)
    }
}
