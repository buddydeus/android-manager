package com.buddydeus.androidmanager.domain

object RebootGuard {
    fun allowed(commandConnection:String,currentConnection:String?,commandBoot:Int,currentBoot:Int,deadlineElapsed:Long,nowElapsed:Long,previouslyAttempted:Boolean):Boolean=commandConnection==currentConnection && commandBoot>=0 && commandBoot==currentBoot && nowElapsed<deadlineElapsed && !previouslyAttempted
}
