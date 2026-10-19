package com.buddydeus.androidmanager.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant

class BoundaryScheduler(private val context:Context) {
    private val alarms=context.getSystemService(AlarmManager::class.java)
    private fun token()=PendingIntent.getBroadcast(context,1,Intent(context,AlarmReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(boundary:Instant?):String = try {
        alarms.cancel(token())
        if(boundary==null) "无待执行边界"
        else if(Build.VERSION.SDK_INT>=31 && !alarms.canScheduleExactAlarms()) "定时执行不可用：请授予精确闹钟权限"
        else { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,boundary.toEpochMilli(),token());"下次边界：${boundary.atZone(com.buddydeus.androidmanager.domain.ScheduleEvaluator.zone)}" }
    } catch(e:Exception) { "定时执行不可用：${e.javaClass.simpleName}" }
}
