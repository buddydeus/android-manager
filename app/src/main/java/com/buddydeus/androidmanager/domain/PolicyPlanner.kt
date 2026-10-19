package com.buddydeus.androidmanager.domain

data class PolicyPlan(val timeRequired:Boolean,val targets:Map<String,Boolean>)
object PolicyPlanner {
    fun plan(state:ManagerState,apps:Map<String,AppEntry>,active:Boolean):PolicyPlan {
        val enabled=state.initialized && state.enabled
        return PolicyPlan(enabled,(state.blacklist+state.records.keys).associateWith { p ->
            val app=apps[p]
            enabled && active && p in state.blacklist && app?.installed==true && !app.updating && app.protection.isEmpty()
        })
    }
}
