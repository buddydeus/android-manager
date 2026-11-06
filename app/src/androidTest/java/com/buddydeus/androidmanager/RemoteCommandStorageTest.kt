package com.buddydeus.androidmanager

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.application.*
import com.buddydeus.androidmanager.data.UsageRepository
import com.buddydeus.androidmanager.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RemoteCommandStorageTest {
    @Test fun attemptedIsDurableBeforeSystemCallAndDuplicateMessagesCannotRetry() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
        val name="command-fixture-${UUID.randomUUID()}.db";val store=UsageRepository(context,name)
        try {
            val states=object:StateStore { override fun read()=ManagerState(initialized=true);override fun write(state:ManagerState) {} }
            val catalog=object:CatalogPort { override fun load(known:Map<String,AppEntry>)=known;override fun protection(packageName:String)="" }
            val policy=object:DevicePolicyPort {
                override fun isOwner()=true
                override fun inspect(packageName:String)=error("not used")
                override fun setSuspended(packageName:String,value:Boolean)=error("not used")
                override fun setHidden(packageName:String,value:Boolean)=error("not used")
                override fun applyTime(enabled:Boolean,owned:Boolean)=error("not used")
            }
            val coordinator=PolicyCoordinator(states,catalog,policy,AuditSink{})
            val id=UUID.randomUUID().toString();val connection=UUID.randomUUID().toString();var calls=0
            val controller=RebootController(store,coordinator,{1},{connection},{1000},{true},{
                calls++;assertEquals("ATTEMPTED",store.operation(id)!!.getString("state"));throw IllegalStateException("simulated rejection")
            },{false},{})
            val request=JSONObject().put("type","reboot.request").put("commandId",id).put("connectionId",connection).put("bootId",1).put("ttlMs",10000)
            val permit=JSONObject().put("type","reboot.permit").put("commandId",id).put("connectionId",connection).put("allowed",true).put("remainingMs",9999)
            controller.message(request);controller.message(permit);controller.message(request);controller.message(permit)
            assertEquals(1,calls);assertEquals("FAILED",store.operation(id)!!.getString("state"));assertEquals(3,store.operation(id)!!.getInt("version"))
            store.acknowledgeOperation(id,2);assertTrue(store.operation(id)!!.getBoolean("pending"))
            val nextId=UUID.randomUUID().toString();var elapsed=1000L
            val expiring=RebootController(store,coordinator,{1},{connection},{elapsed},{true},{calls++},{false},{
                if(store.operation(nextId)?.getString("state")=="ATTEMPTED") elapsed=20000
            })
            expiring.message(JSONObject(request.toString()).put("commandId",nextId))
            expiring.message(JSONObject(permit.toString()).put("commandId",nextId))
            assertEquals("落盘期间过期不得调用系统",1,calls)
            assertEquals("FAILED",store.operation(nextId)!!.getString("state"))
        } finally { store.close();context.deleteDatabase(name) }
    }
    @Test fun migrationFromUsageOnlySchemaPreservesExistingRecords() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
        val name="migration-fixture-${UUID.randomUUID()}.db"
        try {
            UsageRepository(context,name).use { it.record(UsageKind.ATTACH,System.currentTimeMillis(),1000,1) }
            context.openOrCreateDatabase(name,0,null).use { db -> db.execSQL("DROP TABLE operations");db.version=1 }
            UsageRepository(context,name).use { store ->
                assertNotNull(store.monitor());assertEquals(1,store.pending().getJSONArray("gaps").length())
                store.saveOperation("reboot",JSONObject().put("id","fixture").put("kind","reboot").put("version",1).put("pending",true))
                assertEquals(1,store.operations("reboot",true).size)
            }
        } finally { context.deleteDatabase(name) }
    }
}
