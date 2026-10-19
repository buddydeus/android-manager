package com.buddydeus.androidmanager.domain

data class PackagePolicyState(val suspended: Boolean?,val hidden: Boolean?)
data class PolicyStepResult(val success: Boolean,val reason: String = "")
data class TimePolicyResult(val owned: Boolean,val complete: Boolean,val message: String)
interface DevicePolicyPort {
    fun isOwner(): Boolean
    fun inspect(packageName: String): PackagePolicyState
    fun setSuspended(packageName: String,value: Boolean): PolicyStepResult
    fun setHidden(packageName: String,value: Boolean): PolicyStepResult
    fun applyTime(enabled: Boolean,owned: Boolean): TimePolicyResult
}
interface CatalogPort { fun load(known: Map<String,AppEntry>): Map<String,AppEntry>;fun protection(packageName: String): String }
