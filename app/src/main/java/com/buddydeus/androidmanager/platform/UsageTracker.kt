package com.buddydeus.androidmanager.platform

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import androidx.core.content.ContextCompat
import com.buddydeus.androidmanager.data.UsageRepository
import com.buddydeus.androidmanager.domain.*
import kotlinx.coroutines.*

class UsageTracker(private val context:Context,val repository:UsageRepository,private val boot:()->Int,private val ready:()->Boolean,private val notify:()->Unit,private val failure:(String)->Unit,private val dispatcher:CoroutineDispatcher=Dispatchers.IO.limitedParallelism(1)) {
    private var scope:CoroutineScope?=null
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            val kind=when(intent.action) { Intent.ACTION_SCREEN_ON -> UsageKind.INTERACTIVE;Intent.ACTION_SCREEN_OFF -> UsageKind.NON_INTERACTIVE;Intent.ACTION_USER_PRESENT -> UsageKind.AVAILABLE;else -> null }
            val wall=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime()
            val interactive=context.getSystemService(PowerManager::class.java).isInteractive
            val available=!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            scope?.launch { safely { if(kind!=null) repository.record(kind,wall,elapsed,boot(),available,interactive);recover();notify() } }
        }
    }
    fun hasAccess():Boolean=context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),context.packageName)==AppOpsManager.MODE_ALLOWED
    @Synchronized fun start() {
        if(scope!=null) return
        val run=CoroutineScope(SupervisorJob()+dispatcher);scope=run
        ContextCompat.registerReceiver(context,receiver,IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_USER_PRESENT);addAction(Intent.ACTION_USER_UNLOCKED) },ContextCompat.RECEIVER_NOT_EXPORTED)
        run.launch { safely {
            if(!ready()) return@safely
            repository.record(UsageKind.ATTACH,System.currentTimeMillis(),SystemClock.elapsedRealtime(),boot(),!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,context.getSystemService(PowerManager::class.java).isInteractive)
            recover();notify()
        } }
        run.launch { while(isActive) { delay(30_000);safely { if(ready()) repository.checkpoint(System.currentTimeMillis(),SystemClock.elapsedRealtime()) } } }
    }
    @Synchronized fun stop() {
        val previous=scope ?: return
        scope=null
        context.unregisterReceiver(receiver)
        previous.launch {
            try { safely { repository.record(UsageKind.INTERRUPTED,System.currentTimeMillis(),SystemClock.elapsedRealtime(),boot());notify() } }
            finally { previous.cancel() }
        }
    }
    fun refreshHistory() { scope?.launch { safely { recover();notify() } } }
    private fun recover() {
        if(!ready() || !hasAccess() || !context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val monitor=repository.monitor() ?: return
        // A persisted cursor limits work. Query a short overlap so a pair crossing a batch can be matched.
        val until=System.currentTimeMillis();var from=repository.historyCursor().coerceAtLeast(maxOf(monitor.getLong("bootStartedAtMs"),until-7*86400000L))
        repeat(28) {
            if(from>=until) return
            val end=minOf(from+6*3600000L,until)
            val query=context.getSystemService(UsageStatsManager::class.java).queryEvents((from-6*3600000L).coerceAtLeast(monitor.getLong("bootStartedAtMs")),end) ?: return
            val filtered=mutableListOf<ScreenHistoryEvent>();val event=UsageEvents.Event();var count=0
            while(query.hasNextEvent()) {
                query.getNextEvent(event);if(++count>200_000) error("历史事件过多，保留覆盖缺失")
                val kind=when(event.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> UsageKind.INTERACTIVE
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageKind.NON_INTERACTIVE
                    UsageEvents.Event.KEYGUARD_HIDDEN -> UsageKind.AVAILABLE
                    UsageEvents.Event.DEVICE_STARTUP,UsageEvents.Event.DEVICE_SHUTDOWN -> UsageKind.INTERRUPTED
                    else -> null
                }
                if(kind!=null) filtered+=ScreenHistoryEvent(kind,event.timeStamp)
            }
            repository.recover(UsageHistory.sessions(filtered,monitor.getString("bootKey"),monitor.getInt("bootId")),end)
            from=end
        }
    }
    private inline fun safely(block:()->Unit) { try { if(ready()) block() } catch(e:Exception) { failure("使用记录保存或历史补查失败：${e.javaClass.simpleName}") } }
}
