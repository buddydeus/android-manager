package com.buddydeus.androidmanager.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.buddydeus.androidmanager.domain.*

class AppCatalog(private val context: Context) : CatalogPort {
    private val pm=context.packageManager
    private val resolver=ProtectedAppResolver(context)
    private val replacing=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val settled=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun packageEvent(action:String?,pkg:String?,isReplacing:Boolean) {
        if(pkg==null) return
        if(action==Intent.ACTION_PACKAGE_REMOVED && isReplacing) { replacing.add(pkg);settled.remove(pkg) }
        else if(action in setOf(Intent.ACTION_PACKAGE_ADDED,Intent.ACTION_PACKAGE_REPLACED,Intent.ACTION_PACKAGE_FULLY_REMOVED,Intent.ACTION_PACKAGE_REMOVED)) { replacing.remove(pkg);settled.add(pkg) }
    }
    override fun protection(packageName: String): String = try { resolver.resolve(packageName) } catch(e:Exception) { "保护状态无法确认" }
    @Suppress("DEPRECATION") override fun load(known: Map<String,AppEntry>): Map<String,AppEntry> {
        val launch=pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map { it.activityInfo.packageName }.toSet()
        val all=launch+known.keys
        return all.associateWith { pkg ->
            if(pkg in replacing) return@associateWith (known[pkg] ?: AppEntry(pkg,pkg)).copy(updating=true)
            try {
                val info=pm.getPackageInfo(pkg,PackageManager.MATCH_UNINSTALLED_PACKAGES)
                val ai=checkNotNull(info.applicationInfo)
                AppEntry(pkg,pm.getApplicationLabel(ai).toString(),ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0,
                    ai.flags and ApplicationInfo.FLAG_INSTALLED !=0,info.firstInstallTime,protection(pkg),pkg in launch || known[pkg]?.knownLauncher==true)
            } catch(e:PackageManager.NameNotFoundException) { (known[pkg] ?: AppEntry(pkg,pkg)).copy(installed=false,protection="",updating=known[pkg]?.updating==true && pkg !in settled) }
            catch(e:Exception) { (known[pkg] ?: AppEntry(pkg,pkg)).copy(protection="应用状态无法确认") }
        }
    }
}
