package com.buddydeus.androidmanager.application

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.buddydeus.androidmanager.data.*
import com.buddydeus.androidmanager.domain.*
import com.buddydeus.androidmanager.platform.*
import java.io.File

class ManagerGraph private constructor(val context:Context) {
    val deviceContext=context.createDeviceProtectedStorageContext()
    val store=StateRepository(deviceContext.filesDir)
    val audit=AuditRepository(File(deviceContext.filesDir,"logs"))
    val policy=DevicePolicyGateway(context)
    val catalog=AppCatalog(context)
    val scheduler=BoundaryScheduler(deviceContext)
    val coordinator=PolicyCoordinator(store,catalog,policy,audit,schedule=scheduler::schedule,events={ enabled ->
        val component=ComponentName(context,ManagerEventService::class.java)
        val wanted=if(enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if(context.packageManager.getComponentEnabledSetting(component)!=wanted) context.packageManager.setComponentEnabledSetting(component,wanted,PackageManager.DONT_KILL_APP)
    },cleanup=audit::cleanup)
    val credentials:Credentials by lazy {
        check(context.getSystemService(UserManager::class.java).isUserUnlocked) { "请先解锁手机" }
        Credentials(context,audit)
    }
    fun request(reason:String) { if(policy.isOwner()) PolicyExecutionService.enqueue(context,reason) else coordinator.reconcile(reason) }
    fun bootCount()=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
    companion object {
        // Only applicationContext is retained; Activity contexts never escape.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance:ManagerGraph?=null
        fun get(context:Context):ManagerGraph = instance ?: synchronized(this) { instance ?: ManagerGraph(context.applicationContext).also { instance=it } }
    }
}
class Credentials(context:Context,audit:AuditSink) {
    val metadata=AuthMetadataRepository(context)
    val passwords=SafPasswordRepository(context,metadata)
    val auth=AuthController(passwords,metadata,audit,System::currentTimeMillis,SystemClock::elapsedRealtime,{ Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1) })
}
