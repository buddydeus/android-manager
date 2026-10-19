package com.buddydeus.androidmanager

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Build
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.application.PolicyCoordinator
import com.buddydeus.androidmanager.data.*
import com.buddydeus.androidmanager.domain.*
import com.buddydeus.androidmanager.platform.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceOwnerPolicyTest {
    private class TestClock(var value:Instant):Clock() {
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=this
        override fun instant():Instant=value
    }
    @Test fun isolatedOwnerSuspendsHidesAndReleasesAtBoundaryAndDisabledBoot() {
        val args=InstrumentationRegistry.getArguments()
        val target=args.getString("policyTarget")
        assumeTrue("Only explicitly selected disposable emulator",target!=null && (Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic")))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val port=DevicePolicyGateway(context)
        assumeTrue("Device Owner is required",port.isOwner())
        val baseline=port.inspect(target!!)
        assertEquals(false,baseline.hidden);assertEquals(false,baseline.suspended)
        val dp=context.createDeviceProtectedStorageContext()
        val directory=File(dp.filesDir,"policy-test-${UUID.randomUUID()}")
        val store=StateRepository(directory)
        val clock=TestClock(Instant.parse("2026-10-19T04:00:00Z"))
        val coordinator=PolicyCoordinator(store,AppCatalog(context),port,AuditRepository(File(directory,"logs")),clock)
        fun timeRestricted():Boolean {
            val dpm=context.getSystemService(DevicePolicyManager::class.java)
            return dpm.getUserRestrictions(ComponentName(context,ManagerAdminReceiver::class.java)).getBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME) ||
                (Build.VERSION.SDK_INT>=34 && dpm.getUserRestrictionsGlobally().getBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME))
        }
        try {
            store.write(ManagerState(initialized=true,blacklist=setOf(target),schedule=List(7){DayWindow(540,1200)}))
            coordinator.reconcile("integration active")
            val record=store.read().records.getValue(target)
            assertTrue(record.ownSuspended);assertTrue(record.ownHidden);assertEquals("CONFIRMED",record.result)
            assertEquals(true,port.inspect(target).hidden)
            assertTrue(AppCatalog(context).load(store.read().appCache).getValue(target).installed)
            assertTrue(timeRestricted())
            clock.value=Instant.parse("2026-10-19T15:00:00Z")
            coordinator.reconcile("integration boundary")
            assertEquals(PackagePolicyState(false,false),port.inspect(target))
            clock.value=Instant.parse("2026-10-19T04:00:00Z");coordinator.reconcile("active again")
            store.write(store.read().copy(enabled=false))
            coordinator.reconcile("disabled boot",77)
            assertEquals(PackagePolicyState(false,false),port.inspect(target));assertFalse(timeRestricted())
        } finally {
            val record=runCatching { store.read().records[target] }.getOrNull()
            if(record?.ownHidden==true) port.setHidden(target,false)
            if(record?.ownSuspended==true) port.setSuspended(target,false)
            port.applyTime(false,true)
            directory.deleteRecursively()
        }
    }
}
