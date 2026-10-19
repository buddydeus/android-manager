package com.buddydeus.androidmanager.application
import com.buddydeus.androidmanager.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PolicyCoordinatorTest {
    private class Store(var state:ManagerState):StateStore {
        var failIntent=false
        override fun read()=state
        override fun write(state:ManagerState) { if(failIntent && state.records.values.any { it.pending.isNotEmpty() }) error("disk full");this.state=state }
    }
    private class Port:DevicePolicyPort {
        var suspended=false;var hidden=false;var suspendFails=false;var hideFails=false;var crashAfterSuspend=false
        val calls=mutableListOf<String>()
        override fun isOwner()=true
        override fun inspect(packageName:String)=PackagePolicyState(suspended,hidden)
        override fun setSuspended(packageName:String,value:Boolean):PolicyStepResult {
            calls+="suspend:$value"
            if(suspendFails) return PolicyStepResult(false,"rejected")
            suspended=value;if(crashAfterSuspend) throw IllegalStateException("process stopped")
            return PolicyStepResult(true)
        }
        override fun setHidden(packageName:String,value:Boolean):PolicyStepResult { calls+="hide:$value";if(hideFails) return PolicyStepResult(false,"rejected");hidden=value;return PolicyStepResult(true) }
        override fun applyTime(enabled:Boolean,owned:Boolean)=TimePolicyResult(enabled,true,"ok")
    }
    private val catalog=object:CatalogPort { override fun load(known:Map<String,AppEntry>)=mapOf("test.app" to AppEntry("test.app","Test",instance=1));override fun protection(packageName:String)="" }
    private fun active()=ManagerState(initialized=true,blacklist=setOf("test.app"),schedule=List(7){DayWindow(0,1439)})
    private fun coordinator(store:Store,port:Port)=PolicyCoordinator(store,catalog,port,AuditSink{},Clock.fixed(Instant.parse("2026-10-19T04:00:00Z"),ZoneOffset.UTC))
    @Test fun suspensionFailureNeverFallsBackToHiding() {
        val s=Store(active());val p=Port().apply { suspendFails=true };coordinator(s,p).reconcile("test")
        assertEquals(listOf("suspend:true"),p.calls);assertEquals("FAILED",s.state.records.getValue("test.app").result)
    }
    @Test fun hideFailurePreservesSuspensionAndIsPartial() {
        val s=Store(active());val p=Port().apply { hideFails=true };coordinator(s,p).reconcile("test")
        assertTrue(p.suspended);assertFalse(p.hidden);assertEquals("PARTIAL",s.state.records.getValue("test.app").result)
    }
    @Test fun interruptedCallCannotClaimOrUndoUnknownOwnership() {
        val s=Store(active());val p=Port().apply { crashAfterSuspend=true };val c=coordinator(s,p);c.reconcile("test")
        assertTrue(p.suspended);assertEquals("SUSPEND",s.state.records.getValue("test.app").pending)
        s.state=s.state.copy(enabled=false);p.calls.clear();p.crashAfterSuspend=false
        coordinator(s,p).reconcile("boot")
        assertTrue(p.suspended);assertTrue(p.calls.isEmpty());assertEquals("UNKNOWN",s.state.records.getValue("test.app").result)
    }
    @Test fun intentWriteFailurePreventsSystemMutation() {
        val s=Store(active()).apply { failIntent=true };val p=Port();coordinator(s,p).reconcile("test")
        assertTrue(p.calls.isEmpty());assertFalse(p.suspended)
    }
    @Test fun disabledStateReleasesOwnedHiddenThenSuspended() {
        val s=Store(active().copy(enabled=false,records=mapOf("test.app" to PolicyRecord(ownSuspended=true,ownHidden=true,instance=1))))
        val p=Port().apply { suspended=true;hidden=true };coordinator(s,p).reconcile("boot",1)
        assertEquals(listOf("hide:false","suspend:false"),p.calls);assertFalse(p.hidden);assertFalse(p.suspended)
        p.calls.clear();coordinator(s,p).reconcile("unlock",1);assertTrue(p.calls.isEmpty())
    }
}
