package com.buddydeus.androidmanager.platform

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.buddydeus.androidmanager.domain.*
import java.time.ZoneId

class DevicePolicyGateway(private val context: Context) : DevicePolicyPort {
    private val dpm=context.getSystemService(DevicePolicyManager::class.java)
    private val admin=ComponentName(context,ManagerAdminReceiver::class.java)
    override fun isOwner()=dpm.isDeviceOwnerApp(context.packageName)
    override fun inspect(packageName: String): PackagePolicyState = PackagePolicyState(
        runCatching { dpm.isPackageSuspended(admin,packageName) }.getOrNull(),runCatching { dpm.isApplicationHidden(admin,packageName) }.getOrNull())
    override fun setSuspended(packageName: String,value: Boolean): PolicyStepResult = try {
        check(isOwner()) { "未取得 Device Owner" }
        val rejected=dpm.setPackagesSuspended(admin,arrayOf(packageName),value)
        if(rejected.contains(packageName)) PolicyStepResult(false,"系统拒绝暂停状态变更")
        else PolicyStepResult(true)
    } catch(e:Exception) { PolicyStepResult(false,describe(e)) }
    override fun setHidden(packageName: String,value: Boolean): PolicyStepResult = try {
        check(isOwner()) { "未取得 Device Owner" }
        if(dpm.setApplicationHidden(admin,packageName,value)) PolicyStepResult(true) else PolicyStepResult(false,"系统拒绝隐藏状态变更")
    } catch(e:Exception) { PolicyStepResult(false,describe(e)) }
    override fun applyTime(enabled: Boolean,owned: Boolean): TimePolicyResult {
        if(!isOwner()) return TimePolicyResult(owned,false,"未取得 Device Owner")
        val errors=mutableListOf<String>()
        val actual=try { dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME) || (Build.VERSION.SDK_INT>=34 && dpm.getUserRestrictionsGlobally().getBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME)) }
        catch(e:Exception) { return TimePolicyResult(owned,false,"无法确认本管理员时间限制") }
        var own=owned || actual
        fun step(label:String,block:()->Unit) { try { block() } catch(e:Exception) { errors+="$label：${describe(e)}" } }
        if(enabled) {
            step("自动校时") { if(!dpm.getAutoTimeEnabled(admin)) dpm.setAutoTimeEnabled(admin,true) }
            step("关闭自动时区") { if(dpm.getAutoTimeZoneEnabled(admin)) dpm.setAutoTimeZoneEnabled(admin,false) }
            step("上海时区") { if(ZoneId.systemDefault().id!="Asia/Shanghai") check(dpm.setTimeZone(admin,"Asia/Shanghai")) { "设置被拒绝" } }
            step("禁止修改时间") {
                val present=dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME) || (Build.VERSION.SDK_INT>=34 && dpm.getUserRestrictionsGlobally().getBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME))
                if(!present) dpm.addUserRestriction(admin,UserManager.DISALLOW_CONFIG_DATE_TIME)
                own=true
            }
        } else if(own) {
            step("解除时间限制") { dpm.clearUserRestriction(admin,UserManager.DISALLOW_CONFIG_DATE_TIME);own=false }
        }
        return TimePolicyResult(own,errors.isEmpty(),if(errors.isEmpty()) if(enabled) "时间策略已启用" else "时间限制已解除" else errors.joinToString("；"))
    }
    private fun describe(e:Exception):String = when(e) { is SecurityException -> "管理权限被系统拒绝";is IllegalStateException -> e.message ?: "系统状态异常";else -> e.javaClass.simpleName }
}
