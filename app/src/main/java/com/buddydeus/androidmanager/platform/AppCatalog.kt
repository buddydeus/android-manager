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
    override fun protection(packageName: String): String = try { resolver.resolve(packageName) } catch(e:Exception) { "保护状态无法确认" }
    @Suppress("DEPRECATION") override fun load(known: Map<String,AppEntry>): Map<String,AppEntry> {
        val launch=pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map { it.activityInfo.packageName }.toSet()
        val all=launch+known.keys
        return all.associateWith { pkg ->
            try {
                val info=pm.getPackageInfo(pkg,PackageManager.MATCH_UNINSTALLED_PACKAGES)
                val ai=checkNotNull(info.applicationInfo)
                AppEntry(pkg,pm.getApplicationLabel(ai).toString(),ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0,
                    ai.flags and ApplicationInfo.FLAG_INSTALLED !=0,info.firstInstallTime,protection(pkg),pkg in launch || known[pkg]?.knownLauncher==true)
            } catch(e:PackageManager.NameNotFoundException) { (known[pkg] ?: AppEntry(pkg,pkg)).copy(installed=false,protection="") }
            catch(e:Exception) { (known[pkg] ?: AppEntry(pkg,pkg)).copy(protection="应用状态无法确认") }
        }
    }
}
