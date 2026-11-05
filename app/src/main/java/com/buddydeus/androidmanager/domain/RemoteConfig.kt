package com.buddydeus.androidmanager.domain

import java.util.UUID

data class SyncTime(val atMs:Long,val quality:String)
data class SyncStamp(
    val mutationId:String,val modifiedAtMs:Long,val timeQuality:String,val origin:String,
    val serverRevision:Long=0,val pending:Boolean=true,
) {
    init {
        UUID.fromString(mutationId)
        require(modifiedAtMs in 0..9007199254740991L && serverRevision>=0)
        require(timeQuality in setOf("CALIBRATED","ESTIMATED_AFTER_BOOT","UNCALIBRATED"))
        require(origin in setOf("LOCAL","REMOTE","MIGRATED_BASELINE"))
    }
}
data class ManagedConfig(val enabled:Boolean,val blacklist:Set<String>,val schedule:List<DayWindow?>) {
    init {
        require(schedule.size==7 && blacklist.size<=5000)
        require(blacklist.all { it.length<=255 && it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) })
    }
}
data class RemoteSnapshot(val stamp:SyncStamp,val config:ManagedConfig)
fun ManagerState.managedConfig()=ManagedConfig(enabled,blacklist,schedule)
