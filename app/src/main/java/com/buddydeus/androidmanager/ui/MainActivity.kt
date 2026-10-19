package com.buddydeus.androidmanager.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.KeyguardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.buddydeus.androidmanager.application.ManagerGraph

class MainActivity:ComponentActivity() {
    private lateinit var model:ManagerViewModel
    private var selecting=false
    private val picker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        selecting=false
        if(result.resultCode==RESULT_OK) result.data?.data?.let { model.directory(it) }
    }
    private val lockReceiver=object:BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) { if(::model.isInitialized) model.leave() } }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val graph=ManagerGraph.get(this)
        model=ViewModelProvider(this,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=ManagerViewModel(graph) as T
        })[ManagerViewModel::class.java]
        if(Build.VERSION.SDK_INT>=33) registerReceiver(lockReceiver,IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(lockReceiver,IntentFilter(Intent.ACTION_SCREEN_OFF))
        setContent { ManagerApp(model,onPick={
            selecting=true
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments")))
        },onAlarms={
            if(Build.VERSION.SDK_INT>=31) startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName")))
        }) }
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint();if(!selecting && ::model.isInitialized) model.leave() }
    override fun onStop() { if(!isChangingConfigurations && !selecting && ::model.isInitialized) model.leave();super.onStop() }
    override fun onResume() {
        super.onResume()
        if(::model.isInitialized) {
            if(getSystemService(KeyguardManager::class.java).isKeyguardLocked) model.leave()
            if(!selecting) model.refresh()
        }
    }
    override fun onDestroy() { unregisterReceiver(lockReceiver);super.onDestroy() }
}
