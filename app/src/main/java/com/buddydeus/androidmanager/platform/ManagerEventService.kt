package com.buddydeus.androidmanager.platform

import android.app.admin.DeviceAdminService
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.buddydeus.androidmanager.application.ManagerGraph

class ManagerEventService:DeviceAdminService() {
    private val receiver=object:BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) { PolicyExecutionService.enqueue(context,intent.action.orEmpty()) } }
    private val roleReceiver=object:BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) { PolicyExecutionService.enqueue(context,"系统角色变化") } }
    private val observer=object:ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange:Boolean) { PolicyExecutionService.enqueue(this@ManagerEventService,"时间或输入法配置变化") } }
    override fun onCreate() {
        super.onCreate()
        val packages=IntentFilter().apply { addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_REPLACED);addAction(Intent.ACTION_PACKAGE_CHANGED);addDataScheme("package") }
        val roles=IntentFilter().apply { addAction(Intent.ACTION_INPUT_METHOD_CHANGED);addAction("android.intent.action.PREFERRED_ACTIVITY_CHANGED");addAction("android.telecom.action.DEFAULT_DIALER_CHANGED");addAction(Intent.ACTION_DATE_CHANGED) }
        ContextCompat.registerReceiver(this,receiver,packages,ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this,roleReceiver,roles,ContextCompat.RECEIVER_NOT_EXPORTED)
        listOf(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS),Settings.Global.getUriFor(Settings.Global.AUTO_TIME),Settings.Global.getUriFor(Settings.Global.AUTO_TIME_ZONE)).forEach { contentResolver.registerContentObserver(it,false,observer) }
        PolicyExecutionService.enqueue(this,"事件服务恢复")
    }
    override fun onDestroy() {
        unregisterReceiver(receiver);unregisterReceiver(roleReceiver);contentResolver.unregisterContentObserver(observer);super.onDestroy()
    }
}
