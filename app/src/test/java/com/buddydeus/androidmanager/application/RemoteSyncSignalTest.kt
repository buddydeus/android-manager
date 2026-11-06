package com.buddydeus.androidmanager.application
import com.buddydeus.androidmanager.domain.*
import org.junit.Assert.*
import org.junit.Test
class RemoteSyncSignalTest {
    @Test fun catalogAndProtectionChangesTriggerSyncWithoutChangingConfigRevision() {
        val initial=ManagerState(initialized=true,revision=4)
        val app=AppEntry("example.probe","Probe")
        val installed=initial.copy(appCache=mapOf(app.packageName to app))
        assertNotEquals(remoteSyncSignal(initial,""),remoteSyncSignal(installed,""))
        assertNotEquals(remoteSyncSignal(installed,""),remoteSyncSignal(installed.copy(appCache=mapOf(app.packageName to app.copy(protection="系统必要应用"))),""))
    }
    @Test fun equivalentCatalogSnapshotsDoNotContinuouslyWakeTheNetworkWorker() {
        val state=ManagerState(appCache=mapOf("example.probe" to AppEntry("example.probe","Probe")))
        assertEquals(remoteSyncSignal(state,""),remoteSyncSignal(state.copy(appCache=state.appCache.mapValues { it.value.copy() }),""))
    }
}
