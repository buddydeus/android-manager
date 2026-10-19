package com.buddydeus.androidmanager.platform

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.buddydeus.androidmanager.application.ManagerGraph
import com.buddydeus.androidmanager.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class PolicyExecutionService:Service() {
    private data class Request(val id:Int,val reason:String,val boot:Boolean)
    private val queue=Channel<Request>(Channel.UNLIMITED)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for(request in queue) {
                val wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AndroidManager:policy")
                try {
                    wake.acquire(60000)
                    val graph=ManagerGraph.get(this@PolicyExecutionService)
                    graph.coordinator.reconcile(request.reason,if(request.boot) graph.bootCount() else null)
                } finally { if(wake.isHeld) wake.release();stopSelfResult(request.id) }
            }
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        try {
            val notifications=getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("policy","管控执行",NotificationManager.IMPORTANCE_LOW))
            val open=PendingIntent.getActivity(this,0,Intent(this,com.buddydeus.androidmanager.ui.MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification=Notification.Builder(this,"policy").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("应用管控").setContentText("正在核验并应用策略").setContentIntent(open).setOngoing(true).build()
            if(Build.VERSION.SDK_INT>=34) startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) else startForeground(1,notification)
            queue.trySend(Request(startId,intent?.getStringExtra("reason") ?: "当前状态",intent?.getBooleanExtra("boot",false) ?: false))
        } catch(e:Exception) { report(this,"执行服务启动失败",e);stopSelf(startId) }
        return START_NOT_STICKY
    }
    override fun onDestroy() { queue.close();scope.cancel();super.onDestroy() }
    companion object {
        fun enqueue(context:Context,reason:String,boot:Boolean=false) {
            try {
                val graph=ManagerGraph.get(context)
                if(!graph.policy.isOwner()) return
                context.startForegroundService(Intent(context,PolicyExecutionService::class.java).putExtra("reason",reason).putExtra("boot",boot))
            } catch(e:Exception) { report(context,"无法启动管控任务",e) }
        }
        private fun report(context:Context,action:String,e:Exception) {
            runCatching { ManagerGraph.get(context).coordinator.reportFailure(action,e.javaClass.simpleName) }
        }
    }
}
