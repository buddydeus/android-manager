package com.buddydeus.androidmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.application.ManagerGraph
import com.buddydeus.androidmanager.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID

/** Explicit two-phase debug probe: instrumentation exits before the real alarm is observed. */
@RunWith(AndroidJUnit4::class)
class BackgroundBoundaryProbeTest {
    @Test fun explicitlyArmedBoundaryAndCleanup() {
        val args=InstrumentationRegistry.getArguments()
        val token=args.getString("boundaryProbeToken")
        assumeTrue("Opt-in only",token!=null)
        UUID.fromString(token)
        val graph=ManagerGraph.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val target="com.xingin.xhs"
        val marker=File(graph.deviceContext.filesDir,"boundary-probe-$token.json")
        assertTrue(graph.policy.isOwner())
        when(args.getString("boundaryProbeMode")) {
            "arm" -> {
                assertFalse("Probe already armed",marker.exists())
                val baseline=graph.coordinator.refresh()
                assertEquals(args.getString("expectedRevision")!!.toLong(),baseline.revision)
                assertTrue(baseline.initialized && baseline.enabled)
                // Accept only an empty configuration or the previously authorized temporary UI fixture.
                val empty=baseline.blacklist.isEmpty() && baseline.schedule.all { it==null }
                val legacy=baseline.blacklist==setOf(target) && baseline.schedule==List(7) { if(it==1) DayWindow(0,503) else null }
                assertTrue("Preserve administrator rules",empty || legacy)
                assertEquals("",graph.catalog.protection(target))
                assertEquals(PackagePolicyState(false,false),graph.policy.inspect(target))
                val now=ZonedDateTime.now(ScheduleEvaluator.zone)
                val end=now.withSecond(0).withNano(0).plusMinutes(3)
                require(now.toLocalDate()==end.toLocalDate()) { "Run outside midnight transition" }
                val endMinute=end.minusMinutes(1)
                val windows=List(7) { if(it==now.dayOfWeek.value-1) DayWindow(0,endMinute.hour*60+endMinute.minute) else null }
                val fixture=JSONObject().put("revision",baseline.revision+1).put("day",now.dayOfWeek.value-1)
                    .put("end",endMinute.hour*60+endMinute.minute).put("endEpoch",end.toEpochSecond())
                marker.writeText(fixture.toString())
                try {
                    graph.coordinator.updateConfig("显式实机边界探针") {
                        check(it.revision==baseline.revision)
                        it.copy(blacklist=setOf(target),schedule=windows)
                    }
                    graph.coordinator.reconcile("显式实机边界探针")
                    val applied=graph.coordinator.refresh()
                    assertEquals(PackagePolicyState(true,true),graph.policy.inspect(target))
                    assertTrue(applied.alarmStatus,applied.alarmStatus.startsWith("下次边界："))
                    println("Boundary probe armed: $end; token=$token")
                } catch(e:Throwable) {
                    if(graph.coordinator.refresh().revision==baseline.revision) marker.delete()
                    else restore(graph,marker,target)
                    throw e
                }
            }
            "cleanup" -> restore(graph,marker,target)
            else -> error("Specify arm or cleanup")
        }
    }
    private fun restore(graph:ManagerGraph,marker:File,target:String) {
        val fixture=JSONObject(marker.readText())
        val current=graph.coordinator.refresh()
        val windows=List(7) { if(it==fixture.getInt("day")) DayWindow(0,fixture.getInt("end")) else null }
        check(current.revision==fixture.getLong("revision") && current.blacklist==setOf(target) && current.schedule==windows) {
            "Configuration changed after probe; preserve administrator edits"
        }
        graph.coordinator.updateConfig("清理实机临时黑名单与周表") {
            check(it.revision==fixture.getLong("revision") && it.blacklist==setOf(target) && it.schedule==windows)
            it.copy(blacklist=emptySet(),schedule=List(7){null})
        }
        graph.coordinator.reconcile("清理实机临时黑名单与周表")
        assertEquals(PackagePolicyState(false,false),graph.policy.inspect(target))
        assertTrue(graph.coordinator.refresh().blacklist.isEmpty())
        assertTrue(graph.coordinator.refresh().schedule.all { it==null })
        assertTrue(marker.delete())
    }
}
