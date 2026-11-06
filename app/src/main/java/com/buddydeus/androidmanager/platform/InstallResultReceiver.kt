package com.buddydeus.androidmanager.platform
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.buddydeus.androidmanager.application.ManagerGraph
import kotlinx.coroutines.*
class InstallResultReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { val graph=ManagerGraph.get(context);graph.upgrades.callback(intent);graph.remote.requestSync() }
            catch(e:Exception) { ManagerGraph.get(context).coordinator.reportFailure("安装回调","安装结果保存失败") }
            finally { pending.finish() }
        }
    }
}
