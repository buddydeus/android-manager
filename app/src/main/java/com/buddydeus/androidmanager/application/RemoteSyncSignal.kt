package com.buddydeus.androidmanager.application
import com.buddydeus.androidmanager.domain.ManagerState
import com.buddydeus.androidmanager.domain.AppEntry

data class RemoteSyncSignal(val revision:Long?,val evaluation:String?,val evaluationRevision:Long?,val alarmStatus:String?,val pending:Boolean?,val error:String,val apps:Map<String,AppEntry>?)
fun remoteSyncSignal(state:ManagerState?,error:String)=RemoteSyncSignal(state?.revision,state?.lastEvaluation,state?.lastEvaluationRevision,state?.alarmStatus,state?.remote?.pending,error,state?.appCache)
