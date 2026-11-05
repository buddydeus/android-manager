package com.buddydeus.androidmanager.domain

import com.buddydeus.androidmanager.data.RemoteCodec
import com.buddydeus.androidmanager.data.StateCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteConfigTest {
    @Test fun calibratedTimeUsesElapsedInsteadOfAdjustedWallClock() {
        val anchor=TimeAnchor(10000,1000,8000,1,30)
        assertEquals(SyncTime(12000,"CALIBRATED"),anchor.time(900000,3000,1))
    }
    @Test fun anotherBootUsesPersistedOffsetAndMarksEstimate() {
        assertEquals(SyncTime(7000,"ESTIMATED_AFTER_BOOT"),TimeAnchor(10000,1000,8000,1,30).time(5000,20,2))
    }
    @Test fun remoteMetadataRoundTripsWithoutAffectingLegacyState() {
        val old=ManagerState(initialized=true)
        val json=JSONObject(StateCodec.encode(old));json.remove("remote")
        assertNull(StateCodec.decode(json.toString()).remote)
        val state=old.copy(remote=SyncStamp(java.util.UUID.randomUUID().toString(),1000,"CALIBRATED","LOCAL",3,true))
        assertEquals(state,StateCodec.decode(StateCodec.encode(state)))
    }
    @Test(expected=IllegalArgumentException::class) fun oversizedMinuteCannotWrapIntoValidMinute() {
        val stamp=SyncStamp(java.util.UUID.randomUUID().toString(),1000,"CALIBRATED","REMOTE")
        val json=RemoteCodec.stamp(stamp).put("serverRevision",1).put("config",RemoteCodec.config(ManagedConfig(true,emptySet(),listOf(DayWindow(0,1))+List(6){null})))
        json.getJSONObject("config").getJSONArray("schedule").getJSONObject(0).put("startMinute",4294967296L)
        RemoteCodec.snapshot(json)
    }
}
