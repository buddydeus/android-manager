package com.buddydeus.androidmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.data.StateRepository
import com.buddydeus.androidmanager.domain.*
import com.buddydeus.androidmanager.platform.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun deviceProtectedAtomicStatePreservesPolicyAndRejectsCorruption() {
        val dp=context.createDeviceProtectedStorageContext()
        assertTrue(dp.isDeviceProtectedStorage)
        val directory=File(dp.filesDir,"instrumented-${UUID.randomUUID()}")
        try {
            val repo=StateRepository(directory)
            val state=ManagerState(initialized=true,blacklist=setOf("test.app"),records=mapOf("test.app" to PolicyRecord(ownSuspended=true)))
            repo.write(state);assertEquals(state,StateRepository(directory).read())
            File(directory,"state.json").writeText("broken")
            assertTrue(runCatching { StateRepository(directory).read() }.isFailure)
        } finally { directory.deleteRecursively() }
    }
    @Test fun actualCatalogIncludesLaunchableSystemAppsAndProtectsController() {
        val catalog=AppCatalog(context)
        val apps=catalog.load(emptyMap())
        assertTrue(apps.isNotEmpty());assertTrue(apps.values.any { it.system })
        assertTrue(catalog.protection(context.packageName).isNotBlank())
        assertTrue(catalog.protection("com.android.settings").isNotBlank())
    }
}
