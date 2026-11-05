package com.buddydeus.androidmanager.application

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.buddydeus.androidmanager.BuildConfig
import com.buddydeus.androidmanager.data.*
import com.buddydeus.androidmanager.domain.*
import com.buddydeus.androidmanager.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class RemoteStatus(val deviceId:String="",val phase:String="等待接入",val lastSyncAt:Long?=null,val error:String="")
class RemoteManager(
    context:Context,private val coordinator:PolicyCoordinator,private val clock:SyncClockRepository,
    private val owner:()->Boolean,private val boot:()->Int,private val audit:AuditSink,private val applyPolicy:()->Unit,
) {
    private val api=RemoteApi()
    val origin get()=api.origin
    private val identities=RemoteIdentityRepository(context.createDeviceProtectedStorageContext().filesDir)
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private val mutable=MutableStateFlow(RemoteStatus())
    val status=mutable.asStateFlow()
    @Volatile private var scope:CoroutineScope?=null
    private var signals=Channel<Unit>(Channel.CONFLATED)
    @Volatile private var socket:WebSocket?=null
    @Volatile private var connectionId:String?=null
    private val epoch=AtomicLong()
    private val channelEpoch=AtomicLong()
    private val channelFailures=AtomicLong()
    private var lastFailure=""
    private val network=object:ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network:Network) { signals.trySend(Unit) }
    }
    @Synchronized fun start() {
        if(scope!=null) return
        val generation=epoch.incrementAndGet()
        val run=CoroutineScope(SupervisorJob()+Dispatchers.IO);scope=run
        signals=Channel(Channel.CONFLATED)
        try { connectivity.registerDefaultNetworkCallback(network) }
        catch(e:Exception) { report("网络状态监听不可用") }
        run.launch {
            combine(coordinator.snapshots,coordinator.errors) { state,error ->
                listOf(state?.revision,state?.lastEvaluation,state?.lastEvaluationRevision,state?.alarmStatus,state?.remote?.pending,error)
            }.distinctUntilChanged().collect { signals.trySend(Unit) }
        }
        run.launch { worker(generation) }
        run.launch { while(isActive) { delay(30_000);sendHeartbeat() } }
        signals.trySend(Unit)
    }
    @Synchronized fun stop() {
        epoch.incrementAndGet();channelEpoch.incrementAndGet()
        scope?.cancel();scope=null;signals.close();socket?.cancel();socket=null;connectionId=null
        api.client.dispatcher.cancelAll()
        try { connectivity.unregisterNetworkCallback(network) } catch(_:IllegalArgumentException) { /* Registration may have failed. */ }
        mutable.update { it.copy(phase="等待后台服务恢复") }
    }
    private suspend fun worker(generation:Long) {
        var retry:Long?=null
        while(currentCoroutineContext().isActive && epoch.get()==generation) {
            if(retry==null) signals.receive() else withTimeoutOrNull(retry) { signals.receive() }
            try {
                if(connectivity.activeNetwork==null) { report("等待网络恢复");retry=null;continue }
                check(owner()) { "设备管理身份不可用" }
                coordinator.syncState()
                var identity=identities.read()
                if(identity.token.isEmpty()) identity=api.register(identities)
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(deviceId=identity.deviceId,phase="同步中",error="") }
                val sent=SystemClock.elapsedRealtime()
                val time=api.request("/api/v1/device/time",identity.token)
                clock.calibrate(time.getLong("nowMs"),sent,SystemClock.elapsedRealtime())
                val local=coordinator.syncState()
                val response=api.request("/api/v1/device/sync",identity.token,JSONObject().put("candidate",if(local.remote?.pending==true) RemoteCodec.candidate(local) else JSONObject.NULL))
                currentCoroutineContext().ensureActive()
                if(!response.isNull("current") && coordinator.acceptRemote(RemoteCodec.snapshot(response.getJSONObject("current")))) applyPolicy()
                val current=coordinator.refresh()
                api.request("/api/v1/device/status",identity.token,JSONObject().put("versionCode",BuildConfig.VERSION_CODE).put("versionName",BuildConfig.VERSION_NAME)
                    .put("apiLevel",android.os.Build.VERSION.SDK_INT).put("bootId",boot()).put("status",JSONObject()
                        .put("configMutationId",current.remote?.mutationId ?: JSONObject.NULL).put("configRevision",current.revision)
                        .put("policyRevision",current.lastEvaluationRevision)
                        .put("policyResult",coordinator.errors.value.ifBlank { if(current.lastEvaluationRevision==current.revision) current.lastEvaluation else "已保存，等待策略执行" }).put("alarmStatus",current.alarmStatus)))
                currentCoroutineContext().ensureActive()
                lastFailure="";retry=null
                mutable.update { it.copy(phase=if(connectionId==null) "配置已同步，正在连接" else "已连接",lastSyncAt=System.currentTimeMillis(),error="") }
                if(socket==null) openChannel(identity,generation)
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                if(e is RemoteHttpException && e.status==401) {
                    try { val identity=identities.read();identities.write(identity.copy(token="")) }
                    catch(_:Exception) { report("远程身份保存失败，请检查设备存储");retry=300_000;continue }
                }
                report(if(e is RemoteHttpException && e.status==401) "远程身份已失效，正在恢复" else "远程暂不可用，请检查网络、接入配置和服务状态")
                retry=((retry ?: 500)*2).coerceAtMost(300_000)+kotlin.random.Random.nextLong(0,500)
            }
        }
    }
    @Synchronized private fun openChannel(identity:RemoteIdentity,generation:Long) {
        val attempt=channelEpoch.incrementAndGet()
        fun current()=epoch.get()==generation && channelEpoch.get()==attempt
        socket=api.channel(identity,object:WebSocketListener() {
            override fun onMessage(webSocket:WebSocket,text:String) {
                if(!current()) return
                try {
                    require(text.toByteArray(Charsets.UTF_8).size<=65536)
                    val message=JSONObject(text)
                    when(message.getString("type")) {
                        "connected" -> { connectionId=message.getString("connectionId").also { UUID.fromString(it) };channelFailures.set(0);mutable.update { it.copy(phase="已连接",error="") };sendHeartbeat(webSocket);signals.trySend(Unit) }
                        "heartbeat.ack" -> { require(message.getString("connectionId")==connectionId) }
                        "config.changed" -> signals.trySend(Unit)
                        else -> error("不支持的远程消息")
                    }
                } catch(e:Exception) { webSocket.close(1008,"protocol error");report("远程协议或存储异常") }
            }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) { disconnected() }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String) { webSocket.close(code,reason) }
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String) { disconnected() }
            private fun disconnected() {
                synchronized(this@RemoteManager) {
                    if(!current()) return
                    socket=null;connectionId=null;report("推送连接已断开，等待恢复")
                    val attempt=channelFailures.incrementAndGet().coerceAtMost(9).toInt()
                    val wait=(1000L shl attempt).coerceAtMost(300_000)+kotlin.random.Random.nextLong(1000)
                    scope?.launch { delay(wait);if(current()) signals.trySend(Unit) }
                }
            }
        })
    }
    private fun sendHeartbeat(target:WebSocket?=socket) {
        target?.send(JSONObject().put("type","heartbeat").put("bootId",boot()).put("syncError",mutable.value.error).toString())
    }
    private fun report(message:String) {
        mutable.update { it.copy(phase="待同步",error=message) }
        if(lastFailure==message) return
        lastFailure=message
        try { audit.append(AuditEvent(LogCategory.PERMISSION_ERROR,"远程连接",message)) }
        catch(_:Exception) { coordinator.reportFailure("远程日志","日志写入失败") }
    }
}
