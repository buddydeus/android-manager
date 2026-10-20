package com.buddydeus.androidmanager

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.data.StateRepository
import com.buddydeus.androidmanager.domain.PackagePolicyState
import com.buddydeus.androidmanager.platform.AppCatalog
import com.buddydeus.androidmanager.platform.DevicePolicyGateway
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in target-device probe; never changes the production configuration or time policy. */
@RunWith(AndroidJUnit4::class)
class TargetPackagePolicyTest {
    @Test fun explicitlySelectedPackageCanBeSuspendedHiddenAndRestored() {
        val target=InstrumentationRegistry.getArguments().getString("physicalPolicyTarget")
        assumeTrue("Requires an explicitly selected package",target!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val port=DevicePolicyGateway(context)
        assertTrue("Device Owner required",port.isOwner())
        val catalog=AppCatalog(context)
        assertEquals("Protected packages must never be probed","",catalog.protection(target!!))
        val state=StateRepository(context.createDeviceProtectedStorageContext().filesDir).read()
        assertFalse("Do not interfere with a configured blacklist",target in state.blacklist)
        assertEquals("Do not release pre-existing restrictions",PackagePolicyState(false,false),port.inspect(target))
        val entry=catalog.load(emptyMap()).getValue(target)
        val launch=checkNotNull(context.packageManager.getLaunchIntentForPackage(target))
        var suspendAttempted=false
        var hideAttempted=false
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            suspendAttempted=true
            val suspended=port.setSuspended(target,true)
            assertTrue(suspended.reason,suspended.success)
            assertEquals(true,port.inspect(target).suspended)
            hideAttempted=true
            val hidden=port.setHidden(target,true)
            assertTrue(hidden.reason,hidden.success)
            assertEquals(PackagePolicyState(true,true),port.inspect(target))
            assertTrue(catalog.load(mapOf(target to entry)).getValue(target).installed)
            assertNull("Hidden package must not expose a launch intent",context.packageManager.getLaunchIntentForPackage(target))
        } finally {
            val restoredHidden=!hideAttempted || port.setHidden(target,false).success
            val restoredSuspended=!suspendAttempted || port.setSuspended(target,false).success
            assertTrue("Restore hidden state",restoredHidden)
            assertTrue("Restore suspended state",restoredSuspended)
        }
        assertEquals(PackagePolicyState(false,false),port.inspect(target))
        assertNotNull(context.packageManager.getLaunchIntentForPackage(target))
        assertEquals(state.blacklist,StateRepository(context.createDeviceProtectedStorageContext().filesDir).read().blacklist)
    }
}
