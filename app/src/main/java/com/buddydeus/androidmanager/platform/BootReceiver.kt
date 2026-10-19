package com.buddydeus.androidmanager.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.buddydeus.androidmanager.application.ManagerGraph
import kotlinx.coroutines.*

class BootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val allowed=setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED,Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_USER_UNLOCKED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        if(intent.action !in allowed) return
        val graph=ManagerGraph.get(context)
        val boot=intent.action in setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED,Intent.ACTION_BOOT_COMPLETED)
        if(graph.policy.isOwner()) PolicyExecutionService.enqueue(context,intent.action.orEmpty(),boot)
        else {
            val pending=goAsync()
            CoroutineScope(Dispatchers.IO).launch { try { graph.coordinator.reconcile(intent.action.orEmpty(),if(boot) graph.bootCount() else null) } finally { pending.finish() } }
        }
    }
}
