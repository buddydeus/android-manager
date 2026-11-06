package com.buddydeus.androidmanager

import android.os.Build
import android.content.*
import com.buddydeus.androidmanager.platform.UsageTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.data.UsageRepository
import com.buddydeus.androidmanager.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UsageStorageTest {
    private fun fixture(test:(UsageRepository,Long,String)->Unit) {
        assumeTrue("仅限独立模拟器",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
        val name="usage-fixture-${UUID.randomUUID()}.db"
        val store=UsageRepository(context,name)
        try { val deviceId=UUID.randomUUID().toString();store.associateDevice(deviceId);test(store,System.currentTimeMillis(),deviceId) } finally { store.close();context.deleteDatabase(name) }
    }
    @Test fun staleUploadAcknowledgementCannotClearFinalizedSession()=fixture { store,now,deviceId ->
        store.record(UsageKind.ATTACH,now,1000,1)
        store.record(UsageKind.AVAILABLE,now+100,1100,1)
        val prior=store.pending().getJSONArray("sessions").getJSONObject(0)
        store.record(UsageKind.NON_INTERACTIVE,now+5100,6100,1)
        store.acknowledge(JSONObject().put("sessions",JSONArray().put(JSONObject().put("id",prior.getString("id")).put("recordVersion",prior.getInt("recordVersion")))).put("gaps",JSONArray()),deviceId)
        val pending=store.pending().getJSONArray("sessions")
        assertEquals(1,pending.length());assertEquals(2,pending.getJSONObject(0).getInt("recordVersion"));assertEquals(5000L,pending.getJSONObject(0).getLong("durationMs"))
    }
    @Test fun serviceReattachKeepsUnknownEndpointsAndSystemBootIdentity()=fixture { store,now,deviceId ->
        val first=store.record(UsageKind.ATTACH,now,1000,7)
        store.record(UsageKind.AVAILABLE,now+100,1100,7)
        val reattached=store.record(UsageKind.ATTACH,now+2000,3000,7,true,true)
        assertEquals(first.getString("bootKey"),reattached.getString("bootKey"));assertEquals(first.getLong("bootStartedAtMs"),reattached.getLong("bootStartedAtMs"))
        val rows=store.pending().getJSONArray("sessions")
        assertEquals(2,rows.length());assertEquals("MISSING_END",rows.getJSONObject(0).getString("quality"));assertTrue(rows.getJSONObject(0).isNull("endedAtMs"))
        assertTrue(rows.getJSONObject(1).isNull("startedAtMs"));assertEquals(2,store.pending().getJSONArray("gaps").length())
        val reboot=store.record(UsageKind.ATTACH,now+3000,500,8)
        assertNotEquals(first.getString("bootKey"),reboot.getString("bootKey"))
        assertEquals(now,first.getLong("firstObservedAtMs"));assertEquals(now,reboot.getLong("firstObservedAtMs"))
    }
    @Test fun uniqueHistoricalPairRepairsOriginalRecordWithoutAddingDuplicate()=fixture { store,now,deviceId ->
        val monitor=store.record(UsageKind.ATTACH,now,1000,1)
        store.record(UsageKind.AVAILABLE,now+100,1100,1)
        store.record(UsageKind.INTERRUPTED,now+200,1200,1)
        val before=store.pending().getJSONArray("sessions").getJSONObject(0)
        val pair=UsageSession(bootKey=monitor.getString("bootKey"),bootId=1,observedAtMs=now+100,startedAtMs=now+100,endedAtMs=now+500,durationMs=400,quality=UsageQuality.COMPLETE,source=UsageSource.HISTORY_ESTIMATED)
        store.recover(listOf(pair),now+1000);store.recover(listOf(pair),now+1000)
        val rows=store.pending().getJSONArray("sessions")
        assertEquals(1,rows.length());assertEquals(before.getString("id"),rows.getJSONObject(0).getString("id"));assertEquals("COMPLETE",rows.getJSONObject(0).getString("quality"))
    }
    @Test fun immediateServiceRecreationRegistersANewListenerBeforeOldCleanupRuns()=fixture { store,_,_ ->
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        var registrations=0
        val context=object:ContextWrapper(target) {
            override fun registerReceiver(receiver:BroadcastReceiver?,filter:IntentFilter?,flags:Int):Intent? {
                registrations++;return super.registerReceiver(receiver,filter,flags)
            }
            override fun registerReceiver(receiver:BroadcastReceiver?,filter:IntentFilter?,permission:String?,scheduler:android.os.Handler?,flags:Int):Intent? {
                registrations++;return super.registerReceiver(receiver,filter,permission,scheduler,flags)
            }
            override fun unregisterReceiver(receiver:BroadcastReceiver?) { registrations--;super.unregisterReceiver(receiver) }
        }
        val queue=ArrayDeque<Runnable>()
        val dispatcher=object:CoroutineDispatcher() { override fun dispatch(context:CoroutineContext,block:Runnable) { queue.addLast(block) } }
        val tracker=UsageTracker(context,store,{ 1 },{ true },{}, { error(it) },dispatcher)
        try { tracker.start();tracker.stop();tracker.start();assertEquals(1,registrations) }
        finally { if(registrations>0) tracker.stop();while(queue.isNotEmpty()) queue.removeFirst().run() }
    }
    @Test fun initialUnknownCoverageSurvivesEmptyHistory()=fixture { store,now,deviceId ->
        store.record(UsageKind.ATTACH,now,1000,1)
        store.recover(emptyList(),now)
        val gaps=store.pending().getJSONArray("gaps")
        assertEquals(1,gaps.length());assertFalse(gaps.getJSONObject(0).getBoolean("resolved"))
    }
    @Test fun deviceAssociationRequeuesRetainedRecordsAndRejectsOldServerAcknowledgements()=fixture { store,now,_ ->
        val first=UUID.randomUUID().toString();val second=UUID.randomUUID().toString()
        assertTrue(store.associateDevice(first))
        store.record(UsageKind.ATTACH,now,1000,1);store.record(UsageKind.AVAILABLE,now+100,1100,1)
        val batch=store.pending();store.acknowledge(batch,first)
        assertEquals(0,store.pending().getJSONArray("sessions").length())
        assertFalse(store.associateDevice(first));assertTrue(store.associateDevice(second))
        store.acknowledge(batch,first)
        assertEquals(1,store.pending().getJSONArray("sessions").length())
        assertEquals(1,store.pending().getJSONArray("gaps").length())
        store.acknowledge(batch,second);assertEquals(0,store.pending().getJSONArray("sessions").length())
    }
    @Test fun orphanedReceiptPreservesOperationAndCannotRetireANewerResult() = fixture { store,_,_ ->
        val id=java.util.UUID.randomUUID().toString()
        store.saveOperation("reboot",JSONObject().put("id",id).put("kind","reboot").put("version",3).put("state","ATTEMPTED").put("result","original").put("pending",true))
        store.retireOrphanedOperation(id,2);assertEquals(1,store.operations("reboot",true).size)
        store.retireOrphanedOperation(id,3);assertEquals(0,store.operations("reboot",true).size)
        val saved=store.operation(id)!!;assertEquals("ATTEMPTED",saved.getString("state"));assertEquals("original",saved.getString("result"));assertEquals("SERVER_RECEIPT_UNVERIFIABLE",saved.getString("receiptDisposition"))
    }

}
