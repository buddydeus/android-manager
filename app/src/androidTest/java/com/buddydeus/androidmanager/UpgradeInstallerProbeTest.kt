package com.buddydeus.androidmanager

import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.application.PolicyCoordinator
import com.buddydeus.androidmanager.data.UsageRepository
import com.buddydeus.androidmanager.domain.*
import com.buddydeus.androidmanager.platform.UpgradeController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UpgradeInstallerProbeTest {
    @Suppress("DEPRECATION")
    @Test fun realSignedArchiveAndOrphanSessionRecoveryWithoutCommit() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val args=InstrumentationRegistry.getArguments();val fixture=args.getString("upgradeProbeId")
        assumeTrue("须显式提供隔离夹具UUID",fixture!=null)
        val id=UUID.fromString(fixture).toString()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File("/data/local/tmp/ams-upgrade-$id.apk");check(file.isFile)
        val pm=context.packageManager;val archive=checkNotNull(pm.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES))
        val installed=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        fun identity(p:android.content.pm.PackageInfo)=ApkIdentity(p.packageName,p.longVersionCode,p.applicationInfo!!.minSdkVersion,p.signingInfo!!.apkContentsSigners.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet())
        val target=identity(archive);val current=identity(installed)
        assertTrue("夹具版本必须递增",target.versionCode>current.versionCode)
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use { input -> val b=ByteArray(8192);while(true) { val n=input.read(b);if(n<0) break;digest.update(b,0,n) } }
        val hash=digest.digest().joinToString("") { "%02x".format(it) }
        assertNull(UpgradeGate.rejection(target,target,current,Build.VERSION.SDK_INT,hash,hash,file.length(),file.length()))
        val dp=context.createDeviceProtectedStorageContext();val name="upgrade-fixture-$id.db";check(!dp.getDatabasePath(name).exists())
        val store=UsageRepository(dp,name);var session:Int?=null
        try {
            val state=object:StateStore { override fun read()=ManagerState(initialized=true);override fun write(state:ManagerState) {} }
            val catalog=object:CatalogPort { override fun load(known:Map<String,AppEntry>)=known;override fun protection(packageName:String)="" }
            val policy=object:DevicePolicyPort {
                override fun isOwner()=true
                override fun inspect(packageName:String)=error("unused")
                override fun setSuspended(packageName:String,value:Boolean)=error("unused")
                override fun setHidden(packageName:String,value:Boolean)=error("unused")
                override fun applyTime(enabled:Boolean,owned:Boolean)=error("unused")
            }
            val controller=UpgradeController(context,store,PolicyCoordinator(state,catalog,policy,AuditSink{}),{true},{})
            store.saveOperation("upgrade",JSONObject().put("id",id).put("kind","upgrade").put("state","VERIFIED").put("version",1).put("pending",true).put("result","fixture")
                .put("release",JSONObject().put("packageName",target.packageName).put("versionCode",target.versionCode).put("minSdk",target.minSdk).put("signers",JSONArray(target.signers.sorted()))))
            session=pm.packageInstaller.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName);setAppLabel("AMS:$id") })
            assertNotNull(pm.packageInstaller.getSessionInfo(session))
            controller.recover()
            assertEquals("UNKNOWN",store.operation(id)!!.getString("state"));assertNull(pm.packageInstaller.getSessionInfo(session))
            val retryId=UUID.randomUUID().toString();var attempts=0
            val retry=UpgradeController(context,store,PolicyCoordinator(state,catalog,policy,AuditSink{}),{true},{},{ value ->
                if(attempts++==0) throw IllegalStateException("transient abandonment rejection")
                pm.packageInstaller.abandonSession(value)
            })
            session=pm.packageInstaller.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName);setAppLabel("AMS:$retryId") })
            val row=JSONObject(store.operation(id).toString()).put("id",retryId).put("state","INSTALLING").put("sessionId",session).put("installStartedAtMs",System.currentTimeMillis()-16*60_000)
            store.saveOperation("upgrade",row)
            retry.recover();assertEquals("UNKNOWN",store.operation(retryId)!!.getString("state"));assertNotNull(pm.packageInstaller.getSessionInfo(session))
            retry.recover();assertNull("终态仍须重试清理本任务会话",pm.packageInstaller.getSessionInfo(session))
        } finally {
            session?.let { if(pm.packageInstaller.getSessionInfo(it)!=null) pm.packageInstaller.abandonSession(it) }
            store.close();dp.deleteDatabase(name)
        }
    }
}
