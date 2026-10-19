package com.buddydeus.androidmanager.platform

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

class ProtectedAppResolver(private val context: Context) {
    fun resolve(packageName: String): String = protectedPackages()[packageName].orEmpty()
    private fun protectedPackages(): Map<String,String> = buildMap {
        put(context.packageName,"管控软件自身");put("android","系统核心");put("com.android.systemui","系统界面")
        fun protect(intent: Intent,reason: String) {
            context.packageManager.resolveActivity(intent,0)?.activityInfo?.packageName?.let { put(it,reason) }
        }
        protect(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),"当前系统桌面")
        protect(Intent(Settings.ACTION_SETTINGS),"系统设置")
        protect(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),"密码目录选择器")
        protect(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(android.net.Uri.parse("content://placeholder/app.apk"),"application/vnd.android.package-archive"),"系统安装器")
        protect(Intent(Intent.ACTION_UNINSTALL_PACKAGE,android.net.Uri.parse("package:${context.packageName}")),"系统卸载器")
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.forEach { put(it.packageName,"已启用输入法") }
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { put(it,"默认拨号应用") }
        Telephony.Sms.getDefaultSmsPackage(context)?.let { put(it,"默认短信应用") }
        protect(Intent("android.intent.action.MANAGE_PERMISSIONS"),"权限控制器")
        put("com.android.permissioncontroller","权限控制器");put("com.google.android.permissioncontroller","权限控制器")
        context.getSystemService(android.app.admin.DevicePolicyManager::class.java).activeAdmins.orEmpty().forEach { put(it.packageName,"设备管理组件") }
    }
}
