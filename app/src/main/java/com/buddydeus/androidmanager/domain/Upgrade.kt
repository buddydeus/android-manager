package com.buddydeus.androidmanager.domain

data class ApkIdentity(val packageName:String,val versionCode:Long,val minSdk:Int,val signers:Set<String>)
object UpgradeGate {
    fun rejection(target:ApkIdentity,archive:ApkIdentity,current:ApkIdentity,apiLevel:Int,expectedHash:String,actualHash:String,expectedSize:Long,actualSize:Long):String?=when {
        target.packageName!="com.buddydeus.androidmanager" || archive.packageName!=target.packageName || current.packageName!=target.packageName -> "安装包包名不符"
        expectedSize<=0 || expectedSize>100L*1024*1024 || actualSize!=expectedSize || expectedHash!=actualHash -> "安装包大小或摘要不符"
        target.versionCode<=current.versionCode || archive.versionCode!=target.versionCode -> "安装包版本不符或不高于现装版本"
        target.minSdk>apiLevel || archive.minSdk!=target.minSdk -> "安装包不支持当前系统"
        target.signers.isEmpty() || archive.signers!=target.signers || current.signers!=target.signers -> "安装包签名与现装版本不符"
        else -> null
    }
}
