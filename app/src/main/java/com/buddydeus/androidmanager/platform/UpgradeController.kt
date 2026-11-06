package com.buddydeus.androidmanager.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.buddydeus.androidmanager.application.PolicyCoordinator
import com.buddydeus.androidmanager.data.UsageRepository
import com.buddydeus.androidmanager.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

private class InvalidUpgrade(message:String):Exception(message)
class UpgradeController(private val context:Context,private val store:UsageRepository,private val coordinator:PolicyCoordinator,private val owner:()->Boolean,private val notify:()->Unit,private val abandonSession:(Int)->Unit={ context.packageManager.packageInstaller.abandonSession(it) }) {
    private val pm=context.packageManager
    private val installer=pm.packageInstaller
    private val directory=File(context.createDeviceProtectedStorageContext().filesDir,"updates")
    private val runLock=Mutex()
    private val terminal=setOf("SUCCEEDED","FAILED","UNKNOWN","SUPERSEDED")
    fun isInstalling(excluding:Int?=null)=store.operations("upgrade").any { it.getString("state")=="INSTALLING" } || installer.mySessions.any { it.appPackageName==context.packageName && it.sessionId!=excluding }
    private fun identity(j:JSONObject)=ApkIdentity(j.getString("packageName"),j.getLong("versionCode"),j.getInt("minSdk"),(0 until j.getJSONArray("signers").length()).map { j.getJSONArray("signers").getString(it) }.toSet())
    @Suppress("DEPRECATION") private fun identity(info:PackageInfo):ApkIdentity {
        val signers=checkNotNull(info.signingInfo).apkContentsSigners.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        return ApkIdentity(info.packageName,info.longVersionCode,checkNotNull(info.applicationInfo).minSdkVersion,signers)
    }
    @Suppress("DEPRECATION") private fun current()=identity(pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES))
    @Synchronized private fun state(id:String,next:String,result:String,installed:ApkIdentity?=null):JSONObject {
        val prior=store.operation(id) ?: error("升级意图缺失")
        if(prior.getString("state") in terminal) return prior
        val row=JSONObject(prior.toString()).put("state",next).put("result",result.take(1000)).put("version",prior.getInt("version")+1).put("pending",true)
        if(installed!=null) row.put("installedVersion",installed.versionCode).put("installedSigners",JSONArray(installed.signers.sorted()))
        store.saveOperation("upgrade",row);notify();return row
    }
    @Synchronized fun recover() {
        val known=store.operations("upgrade")
        val sessions=installer.mySessions
        known.filter { it.getString("state") in terminal && it.has("sessionId") }.forEach { row ->
            val id=row.getString("id");val sessionId=row.getInt("sessionId")
            if(sessions.any { it.sessionId==sessionId && it.appPackageName==context.packageName && it.appLabel?.toString()=="AMS:$id" }) abandon(sessionId)
        }
        val referenced=known.mapNotNull { if(it.has("sessionId")) it.getInt("sessionId") else null }.toSet()
        installer.mySessions.filter { it.appPackageName==context.packageName && it.sessionId !in referenced && it.appLabel?.toString()?.startsWith("AMS:")==true }.forEach { orphan ->
            val id=orphan.appLabel.toString().removePrefix("AMS:")
            val row=store.operation(id)
            if(row?.optString("kind")=="upgrade") { abandon(orphan.sessionId);state(id,"UNKNOWN","会话创建期间进程中断，未确认提交；不重复安装") }
        }
        store.operations("upgrade").filter { it.getString("state")=="INSTALLING" }.forEach { row ->
            val target=identity(row.getJSONObject("release"));val installed=current();val sessionId=row.getInt("sessionId")
            when {
                installed.versionCode==target.versionCode && installed.signers==target.signers -> state(row.getString("id"),"SUCCEEDED","已核实现装目标版本与签名",installed)
                installed.versionCode>target.versionCode -> { abandon(sessionId);state(row.getString("id"),"SUPERSEDED","现装版本已更高，不执行降级") }
                installer.getSessionInfo(sessionId)==null -> state(row.getString("id"),"UNKNOWN","系统安装会话已消失，现装版本未匹配目标")
                System.currentTimeMillis()-row.getLong("installStartedAtMs")>15*60_000 -> { abandon(sessionId);state(row.getString("id"),"UNKNOWN","安装结果超时，未确认目标版本；不重复提交") }
            }
        }
    }
    @Synchronized fun callback(intent:Intent) {
        if(intent.action!="${context.packageName}.INSTALL_RESULT") return
        val id=intent.getStringExtra("jobId") ?: return
        val row=store.operation(id) ?: return
        if(row.optString("kind")!="upgrade" || row.getString("state")!="INSTALLING") return
        val session=row.getInt("sessionId")
        if(intent.getIntExtra("expectedSessionId",-1)!=session || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1)!=session || intent.data!=Uri.parse("ams-upgrade:$id")) return
        when(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> {
                val target=identity(row.getJSONObject("release"));val installed=current()
                state(id,if(installed.versionCode==target.versionCode && installed.signers==target.signers) "SUCCEEDED" else "UNKNOWN","安装回调后核验现装版本",installed)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> { abandon(session);state(id,"FAILED","系统要求现场确认，无法自动安装") }
            else -> { abandon(session);state(id,"FAILED","系统拒绝安装：状态${intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)}") }
        }
    }
    suspend fun run(api:RemoteApi,token:String) { runLock.withLock { runLoop(api,token) } }
    private suspend fun runLoop(api:RemoteApi,token:String) {
        directory.listFiles()?.filter { file -> file.extension in setOf("apk","part") && store.operation(file.nameWithoutExtension)?.optString("kind")=="upgrade" }?.forEach { file -> check(file.delete()) { "无法清理上次升级临时文件" } }
        var backoff=1000L
        while(currentCoroutineContext().isActive) {
            try {
                check(owner()) { "未取得 Device Owner" };recover();uploadReceipts(api,token)
                if(isInstalling()) { delay(30_000);continue }
                val jobs=api.request("/api/v1/device/upgrades",token).getJSONArray("jobs")
                if(jobs.length()==0) return
                for(index in 0 until jobs.length()) {
                    currentCoroutineContext().ensureActive()
                    val job=jobs.getJSONObject(index);val id=job.getString("id").also { UUID.fromString(it) }
                    if(store.operation(id)==null) store.saveOperation("upgrade",JSONObject().put("id",id).put("kind","upgrade").put("release",job.getJSONObject("release"))
                        .put("state","QUEUED").put("version",1).put("pending",true).put("result","设备已领取任务"))
                    val local=store.operation(id)!!
                    check(local.getJSONObject("release").getString("id")==job.getJSONObject("release").getString("id")) { "升级任务编号内容不一致" }
                    if(local.getString("state") !in terminal) install(id,api,token)
                    else store.saveOperation("upgrade",JSONObject(local.toString()).put("pending",true))
                    uploadReceipts(api,token)
                    if(isInstalling()) break
                }
                backoff=1000
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(e is RemoteHttpException && e.status==401) throw e;coordinator.reportFailure("远程升级","安装任务或回执暂不可用，等待恢复");delay(backoff);backoff=(backoff*2).coerceAtMost(300_000) }
        }
    }
    private suspend fun install(id:String,api:RemoteApi,token:String) {
        val row=store.operation(id) ?: error("升级意图缺失");val release=row.getJSONObject("release");val target=identity(release)
        val file=File(directory,"$id.apk");val temporary=File(directory,"$id.part")
        var sessionId:Int?=null;var committed=false
        try {
            check(directory.isDirectory || directory.mkdirs()) { "升级目录不可用" }
            val before=current()
            if(before.versionCode>=target.versionCode) { state(id,"SUPERSEDED","现装版本已达到或高于目标，不重复安装");return }
            if(target.minSdk>Build.VERSION.SDK_INT || target.signers!=before.signers || target.packageName!=context.packageName) throw InvalidUpgrade("安装包签名、包名或系统兼容性不符")
            val size=release.getLong("size");if(size !in 1..100L*1024*1024) throw InvalidUpgrade("安装包大小无效")
            check(directory.usableSpace>size*2+64L*1024*1024) { "升级存储空间不足" }
            state(id,"DOWNLOADING","正在下载已分配安装包")
            val digest=MessageDigest.getInstance("SHA-256");var count=0L
            val client=api.client.newBuilder().callTimeout(5,TimeUnit.MINUTES).build()
            client.newCall(Request.Builder().url(api.url("/api/v1/device/releases/${release.getString("id").also { require(it.matches(Regex("[a-f0-9]{64}"))) }}/apk")).header("Authorization","Bearer $token").build()).execute().use { response ->
                if(!response.isSuccessful) throw RemoteHttpException(response.code)
                if(response.body.contentLength()!=size) throw InvalidUpgrade("下载声明大小与发布记录不符")
                FileOutputStream(temporary).use { out -> response.body.byteStream().use { input ->
                    val buffer=ByteArray(8192)
                    while(true) { currentCoroutineContext().ensureActive();val read=input.read(buffer);if(read<0) break;count+=read;if(count>size) throw InvalidUpgrade("下载文件超过声明大小");digest.update(buffer,0,read);out.write(buffer,0,read) }
                };out.fd.sync() }
            }
            if(!temporary.renameTo(file)) error("无法完成安装包下载")
            @Suppress("DEPRECATION") val archive=pm.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES) ?: throw InvalidUpgrade("无法解析安装包")
            val rejection=UpgradeGate.rejection(target,identity(archive),current(),Build.VERSION.SDK_INT,release.getString("sha256"),digest.digest().joinToString("") { "%02x".format(it) },size,count)
            if(rejection!=null) throw InvalidUpgrade(rejection)
            state(id,"VERIFIED","安装包校验通过，等待系统安装")
            currentCoroutineContext().ensureActive()
            val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName);setAppLabel("AMS:$id");setSize(size);if(Build.VERSION.SDK_INT>=31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val createdId=installer.createSession(params);sessionId=createdId
            installer.openSession(createdId).use { session ->
                session.openWrite("base.apk",0,size).use { out -> file.inputStream().use { it.copyTo(out) };session.fsync(out) }
                val coroutine=currentCoroutineContext()
                coordinator.remoteAction("远程安装更新") {
                    coroutine.ensureActive();check(owner() && !isInstalling(createdId)) { "设备管理身份或安装状态已变化" }
                    if(current().versionCode>=target.versionCode) throw InvalidUpgrade("现装版本已变化，不重复安装")
                    synchronized(this) {
                        val latest=store.operation(id) ?: error("升级意图缺失")
                        store.saveOperation("upgrade",JSONObject(latest.toString()).put("sessionId",createdId).put("installStartedAtMs",System.currentTimeMillis())
                            .put("state","INSTALLING").put("result","已持久保存安装意图，等待系统结果").put("version",latest.getInt("version")+1).put("pending",true));notify()
                    }
                    val intent=Intent(context,InstallResultReceiver::class.java).setAction("${context.packageName}.INSTALL_RESULT").setData(Uri.parse("ams-upgrade:$id")).putExtra("jobId",id).putExtra("expectedSessionId",createdId)
                    val sender=PendingIntent.getBroadcast(context,createdId,intent,PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0))
                    coroutine.ensureActive();session.commit(sender.intentSender);committed=true
                }
            }
        } catch(e:InvalidUpgrade) { state(id,"FAILED",e.message ?: "安装校验失败") }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            if(sessionId!=null) state(id,if(store.operation(id)?.getString("state")=="INSTALLING") "UNKNOWN" else "FAILED","系统安装准备或提交异常：${e.javaClass.simpleName}") else throw e
        } finally {
            if(sessionId!=null && !committed) abandon(sessionId)
            if(temporary.exists() && !temporary.delete()) coordinator.reportFailure("远程升级","临时下载文件清理失败")
            if(file.exists() && !file.delete()) coordinator.reportFailure("远程升级","安装包临时文件清理失败")
        }
    }
    private fun uploadReceipts(api:RemoteApi,token:String) {
        val pending=store.operations("upgrade",true)
        if(pending.isEmpty()) return
        val rows=JSONArray(pending.map { row -> JSONObject().put("id",row.getString("id")).put("version",row.getInt("version")).put("state",row.getString("state")).put("result",row.getString("result"))
            .put("installedVersion",row.opt("installedVersion") ?: JSONObject.NULL).put("installedSigners",row.optJSONArray("installedSigners") ?: JSONArray()) })
        val response=api.request("/api/v1/device/upgrades/receipts",token,JSONObject().put("receipts",rows))
        val accepted=response.getJSONArray("accepted")
        val orphaned=response.optJSONArray("orphaned") ?: JSONArray()
        for(i in 0 until orphaned.length()) { val row=orphaned.getJSONObject(i);store.retireOrphanedOperation(row.getString("id"),row.getInt("version")) }
        if(orphaned.length()>0) coordinator.reportFailure("远程升级回执","服务器无法核验旧任务或执行许可；保留本机操作记录，继续接收其他任务")
        for(i in 0 until accepted.length()) { val row=accepted.getJSONObject(i);store.acknowledgeOperation(row.getString("id"),row.getInt("version")) }
    }
    private fun abandon(sessionId:Int) { try { abandonSession(sessionId) } catch(e:Exception) { coordinator.reportFailure("远程升级","无法清理安装会话") } }
}
