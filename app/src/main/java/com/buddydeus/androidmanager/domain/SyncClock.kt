package com.buddydeus.androidmanager.domain

data class TimeAnchor(val serverAtMs:Long,val elapsedAtMs:Long,val wallAtMs:Long,val bootId:Int,val uncertaintyMs:Long) {
    init { require(serverAtMs>=0 && elapsedAtMs>=0 && uncertaintyMs in 0..10000) }
    fun time(wall:Long,elapsed:Long,boot:Int):SyncTime =
        if(boot>=0 && boot==bootId && elapsed>=elapsedAtMs) SyncTime(serverAtMs+elapsed-elapsedAtMs,"CALIBRATED")
        else SyncTime((wall+serverAtMs-wallAtMs).coerceAtLeast(0),"ESTIMATED_AFTER_BOOT")
}
