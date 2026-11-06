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
    private fun fixture(test:(UsageRepository,Long)->Unit) {
        assumeTrue("仅限独立模拟器",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
        val name="usage-fixture-${UUID.randomUUID()}.db"
        val store=UsageRepository(context,name)
        try { test(store,System.currentTimeMillis()) } finally { store.close();context.deleteDatabase(name) }
    }
    @Test fun staleUploadAcknowledgementCannotClearFinalizedSession()=fixture { store,now ->
        store.record(UsageKind.ATTACH,now,1000,1)
        store.record(UsageKind.AVAILABLE,now+100,1100,1)
        val prior=store.pending().getJSONArray("sessions").getJSONObject(0)
        store.record(UsageKind.NON_INTERACTIVE,now+5100,6100,1)
        store.acknowledge(JSONObject().put("sessions",JSONArray().put(JSONObject().put("id",prior.getString("id")).put("recordVersion",prior.getInt("recordVersion")))).put("gaps",JSONArray()))
        val pending=store.pending().getJSONArray("sessions")
        assertEquals(1,pending.length());assertEquals(2,pending.getJSONObject(0).getInt("recordVersion"));assertEquals(5000L,pending.getJSONObject(0).getLong("durationMs"))
    }
    @Test fun serviceReattachKeepsUnknownEndpointsAndSystemBootIdentity()=fixture { store,now ->
        val first=store.record(UsageKind.ATTACH,now,1000,7)
        store.record(UsageKind.AVAILABLE,now+100,1100,7)
        val reattached=store.record(UsageKind.ATTACH,now+2000,3000,7,true,true)
        assertEquals(first.getString("bootKey"),reattached.getString("bootKey"));assertEquals(first.getLong("bootStartedAtMs"),reattached.getLong("bootStartedAtMs"))
        val rows=store.pending().getJSONArray("sessions")
        assertEquals(2,rows.length());assertEquals("MISSING_END",rows.getJSONObject(0).getString("quality"));assertTrue(rows.getJSONObject(0).isNull("endedAtMs"))
        assertTrue(rows.getJSONObject(1).isNull("startedAtMs"));assertEquals(2,store.pending().getJSONArray("gaps").length())
        val reboot=store.record(UsageKind.ATTACH,now+3000,500,8)
        assertNotEquals(first.getString("bootKey"),reboot.getString("bootKey"))
    }
    @Test fun uniqueHistoricalPairRepairsOriginalRecordWithoutAddingDuplicate()=fixture { store,now ->
        val monitor=store.record(UsageKind.ATTACH,now,1000,1)
        store.record(UsageKind.AVAILABLE,now+100,1100,1)
        store.record(UsageKind.INTERRUPTED,now+200,1200,1)
        val before=store.pending().getJSONArray("sessions").getJSONObject(0)
        val pair=UsageSession(bootKey=monitor.getString("bootKey"),bootId=1,observedAtMs=now+100,startedAtMs=now+100,endedAtMs=now+500,durationMs=400,quality=UsageQuality.COMPLETE,source=UsageSource.HISTORY_ESTIMATED)
        store.recover(listOf(pair),now+1000);store.recover(listOf(pair),now+1000)
        val rows=store.pending().getJSONArray("sessions")
        assertEquals(1,rows.length());assertEquals(before.getString("id"),rows.getJSONObject(0).getString("id"));assertEquals("COMPLETE",rows.getJSONObject(0).getString("quality"))
    }
    @Test fun immediateServiceRecreationRegistersANewListenerBeforeOldCleanupRuns()=fixture { store,_ ->
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
    @Test fun initialUnknownCoverageSurvivesEmptyHistory()=fixture { store,now ->
        store.record(UsageKind.ATTACH,now,1000,1)
        store.recover(emptyList(),now)
        val gaps=store.pending().getJSONArray("gaps")
        assertEquals(1,gaps.length());assertFalse(gaps.getJSONObject(0).getBoolean("resolved"))
    }
}
