package com.buddydeus.androidmanager.platform
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class AlarmReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) { PolicyExecutionService.enqueue(context,"时段边界") }
}
