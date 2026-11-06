package com.buddydeus.androidmanager.domain

import org.junit.Assert.*
import org.junit.Test

class UsageTest {
    private fun event(kind:UsageKind,wall:Long=1000,elapsed:Long=1000,available:Boolean=false,interactive:Boolean=false)=UsageEvent(kind,wall,elapsed,"boot",1,available,interactive)
    @Test fun durationUsesElapsedTimeWhenWallClockChanges() {
        val opened=UsageMachine.reduce(UsageState(),event(UsageKind.AVAILABLE,10000,1000)).state
        val result=UsageMachine.reduce(opened,event(UsageKind.NON_INTERACTIVE,5000,4000))
        val record=result.changed.single()
        assertEquals(10000L,record.startedAtMs);assertEquals(5000L,record.endedAtMs)
        assertEquals(3000L,record.durationMs);assertEquals(UsageQuality.COMPLETE,record.quality)
        assertTrue(record.clockAdjusted);assertNull(result.state.active)
    }
    @Test fun attachingToAlreadyUnlockedScreenDoesNotInventStart() {
        val opened=UsageMachine.reduce(UsageState(),event(UsageKind.ATTACH,available=true,interactive=true)).state
        assertNotNull(opened.active);assertNull(opened.active!!.startedAtMs)
        val record=UsageMachine.reduce(opened,event(UsageKind.NON_INTERACTIVE,2000,2000)).changed.single()
        assertEquals(UsageQuality.MISSING_START,record.quality);assertNull(record.durationMs)
    }
    @Test fun duplicateUnlockDoesNotSplitAnActiveSession() {
        val first=UsageMachine.reduce(UsageState(),event(UsageKind.AVAILABLE))
        val second=UsageMachine.reduce(first.state,event(UsageKind.AVAILABLE))
        assertEquals(first.state.active,second.state.active);assertTrue(second.changed.isEmpty())
    }
    @Test fun noLockScreenStartsOnInteractiveAndIdleTimeCountsUntilOff() {
        val start=UsageMachine.reduce(UsageState(),event(UsageKind.INTERACTIVE,available=true))
        val stop=UsageMachine.reduce(start.state,event(UsageKind.NON_INTERACTIVE,61000,61000))
        assertEquals(60000L,stop.changed.single().durationMs)
    }
    @Test fun interruptionLeavesUnknownDurationInsteadOfClosingAtDetectionTime() {
        val start=UsageMachine.reduce(UsageState(),event(UsageKind.AVAILABLE))
        val stopped=UsageMachine.reduce(start.state,event(UsageKind.INTERRUPTED,2000,2000))
        val record=stopped.changed.single()
        assertEquals(UsageQuality.MISSING_END,record.quality);assertNull(record.endedAtMs);assertNull(record.durationMs)
    }
    @Test fun historyDoesNotTreatInteractiveAsUnlockOrInventAnEnd() {
        val records=UsageHistory.sessions(listOf(ScreenHistoryEvent(UsageKind.INTERACTIVE,1),ScreenHistoryEvent(UsageKind.NON_INTERACTIVE,5),ScreenHistoryEvent(UsageKind.INTERACTIVE,10),ScreenHistoryEvent(UsageKind.AVAILABLE,11)),"boot",1)
        assertTrue(records.isEmpty())
    }
    @Test fun historyKeepsOnlyBalancedPairsAndRejectsTimeReversal() {
        val events=listOf(ScreenHistoryEvent(UsageKind.INTERACTIVE,10),ScreenHistoryEvent(UsageKind.AVAILABLE,11),ScreenHistoryEvent(UsageKind.NON_INTERACTIVE,20))
        assertEquals(9L,UsageHistory.sessions(events,"boot",1).single().durationMs)
        assertTrue(UsageHistory.sessions(events.reversed(),"boot",1).isEmpty())
    }
    @Test fun orphanScreenOffProducesOneMissingStartRecord() {
        val records=UsageMachine.reduce(UsageState(),event(UsageKind.NON_INTERACTIVE)).changed
        assertEquals(1,records.size);assertEquals(UsageQuality.MISSING_START,records.single().quality)
        assertNull(records.single().startedAtMs);assertNull(records.single().durationMs)
    }
}
