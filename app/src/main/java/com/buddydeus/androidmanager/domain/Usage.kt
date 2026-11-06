package com.buddydeus.androidmanager.domain

import java.util.UUID

enum class UsageQuality { IN_PROGRESS, COMPLETE, MISSING_START, MISSING_END, COVERAGE_UNKNOWN, SUPERSEDED }
enum class UsageSource { LIVE, HISTORY_ESTIMATED }
data class UsageSession(
    val id:String=UUID.randomUUID().toString(),val recordVersion:Int=1,val bootKey:String,val bootId:Int,
    val observedAtMs:Long,val startedAtMs:Long?=null,val endedAtMs:Long?=null,val durationMs:Long?=null,
    val startElapsedMs:Long?=null,val quality:UsageQuality=UsageQuality.IN_PROGRESS,val source:UsageSource=UsageSource.LIVE,
    val clockAdjusted:Boolean=false,
)
enum class UsageKind { ATTACH, INTERACTIVE, AVAILABLE, NON_INTERACTIVE, INTERRUPTED }
data class UsageEvent(val kind:UsageKind,val atMs:Long,val elapsedMs:Long,val bootKey:String,val bootId:Int,val available:Boolean=false,val interactive:Boolean=false)
data class UsageState(val active:UsageSession?=null,val interactive:Boolean?=null,val fingerprint:String="")
data class UsageTransition(val state:UsageState,val changed:List<UsageSession>)
object UsageMachine {
    fun reduce(state:UsageState,event:UsageEvent):UsageTransition {
        require(event.atMs>=0 && event.elapsedMs>=0)
        val fingerprint="${event.bootKey}:${event.kind}:${event.elapsedMs}"
        if(state.fingerprint==fingerprint) return UsageTransition(state,emptyList())
        val changed=mutableListOf<UsageSession>()
        var active=state.active
        var interactive=state.interactive
        fun interrupt() {
            active?.let { changed+=it.copy(recordVersion=it.recordVersion+1,quality=UsageQuality.MISSING_END) }
            active=null
        }
        fun begin(known:Boolean) {
            if(active!=null) return
            active=UsageSession(bootKey=event.bootKey,bootId=event.bootId,observedAtMs=event.atMs,
                startedAtMs=if(known) event.atMs else null,startElapsedMs=if(known) event.elapsedMs else null)
            changed+=active!!
        }
        if(active!=null && active!!.bootKey!=event.bootKey) interrupt()
        when(event.kind) {
            UsageKind.ATTACH -> { interrupt();interactive=event.interactive;if(event.interactive && event.available) begin(false) }
            UsageKind.INTERRUPTED -> { interrupt();interactive=null }
            UsageKind.INTERACTIVE -> {
                interactive=true;if(event.available) begin(true)
            }
            UsageKind.AVAILABLE -> { interactive=true;begin(true) }
            UsageKind.NON_INTERACTIVE -> {
                if(active==null && interactive!=false) begin(false)
                active?.let {
                    val duration=it.startElapsedMs?.let { start -> (event.elapsedMs-start).takeIf { value -> value>=0 } }
                    changed+=it.copy(recordVersion=it.recordVersion+1,endedAtMs=event.atMs,durationMs=duration,
                        quality=if(duration!=null && it.startedAtMs!=null) UsageQuality.COMPLETE else UsageQuality.MISSING_START,
                        clockAdjusted=duration!=null && it.startedAtMs!=null && kotlin.math.abs(event.atMs-it.startedAtMs-duration)>2000)
                }
                active=null;interactive=false
            }
        }
        return UsageTransition(UsageState(active,interactive,fingerprint),changed.groupBy { it.id }.values.map { it.last() })
    }
}

data class ScreenHistoryEvent(val kind:UsageKind,val atMs:Long)
object UsageHistory {
    // History has no elapsed clock; only balanced, ordered screen/unlock pairs are recoverable.
    fun sessions(events:List<ScreenHistoryEvent>,bootKey:String,bootId:Int):List<UsageSession> {
        val result=mutableListOf<UsageSession>();var interactive=false;var start:Long?=null;var previous=Long.MIN_VALUE
        events.forEach { e ->
            if(e.atMs<previous) return emptyList()
            previous=e.atMs
            when(e.kind) {
                UsageKind.INTERACTIVE -> { if(!interactive) start=null;interactive=true }
                UsageKind.AVAILABLE -> if(interactive && start==null) start=e.atMs
                UsageKind.NON_INTERACTIVE -> {
                    val from=start
                    if(from!=null && e.atMs>=from) result+=UsageSession(id=UUID.nameUUIDFromBytes("$bootKey:$from:${e.atMs}".toByteArray()).toString(),bootKey=bootKey,bootId=bootId,
                        observedAtMs=from,startedAtMs=from,endedAtMs=e.atMs,durationMs=e.atMs-from,quality=UsageQuality.COMPLETE,source=UsageSource.HISTORY_ESTIMATED)
                    interactive=false;start=null
                }
                UsageKind.INTERRUPTED,UsageKind.ATTACH -> { interactive=false;start=null }
            }
        };return result
    }
}
