package com.buddydeus.androidmanager.application

import com.buddydeus.androidmanager.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Instant
import java.util.UUID

class PolicyCoordinator(
    private val store:StateStore,private val catalog:CatalogPort,private val policy:DevicePolicyPort,
    private val audit:AuditSink,private val clock:Clock=Clock.systemUTC(),
    private val schedule:(Instant?)->String={"未配置调度器"},
    private val events:(Boolean)->Unit={},private val cleanup:(java.time.LocalDate)->Unit={},
) {
    private val lock=Any()
    private val mutableState=MutableStateFlow<ManagerState?>(null)
    val snapshots=mutableState.asStateFlow()
    private val mutableError=MutableStateFlow("")
    val errors=mutableError.asStateFlow()
    private fun save(s:ManagerState):ManagerState { store.write(s);mutableState.value=s;return s }
    fun reportFailure(action:String,reason:String) {
        mutableError.value="$action：$reason"
        log(AuditEvent(LogCategory.PERMISSION_ERROR,action,reason))
    }
    fun refresh():ManagerState=synchronized(lock) { try { store.read().also { mutableState.value=it } } catch(e:Exception) { mutableError.value=e.message ?: "配置读取失败";throw e } }
    private fun log(e:AuditEvent) { try { audit.append(e) } catch(ex:Exception) { mutableError.value="日志写入失败" } }
    fun updateConfig(action:String,transform:(ManagerState)->ManagerState):ManagerState=synchronized(lock) {
        val before=store.read();val after=transform(before).copy(revision=before.revision+1)
        save(after);log(AuditEvent(LogCategory.CONFIG_CHANGE,action,"已保存",revision=after.revision));after
    }
    fun reconcile(reason:String,bootId:Int?=null) = synchronized(lock) {
        try {
            mutableError.value=""
            var s=store.read()
            if(bootId!=null && (bootId<0 || s.lastCleanupBoot!=bootId)) {
                try { cleanup(clock.instant().atZone(ScheduleEvaluator.zone).toLocalDate());s=save(s.copy(lastCleanupBoot=bootId)) }
                catch(e:Exception) { mutableError.value="过期日志清理失败";log(AuditEvent(LogCategory.PERMISSION_ERROR,"清理过期日志","失败")) }
            }
            if(!s.initialized) { schedule(null);events(false);mutableState.value=s;return@synchronized }
            events(s.enabled)
            if(bootId!=null && !s.enabled && bootId>=0 && s.lastPolicyBoot==bootId) { schedule(null);mutableState.value=s;return@synchronized }
            if(!policy.isOwner()) {
                s=save(s.copy(lastEvaluation="执行失败：未取得 Device Owner",alarmStatus=schedule(null)))
                log(AuditEvent(LogCategory.PERMISSION_ERROR,"管理身份","未取得 Device Owner",revision=s.revision));return@synchronized
            }
            // A boundary crossed during a batch is evaluated again before the service stops.
            do {
                val time=ScheduleEvaluator.evaluate(s.schedule,clock.instant())
                val interesting=s.blacklist+s.records.keys
                val known=s.appCache.filterKeys { it in interesting }.toMutableMap();interesting.forEach { p -> known.putIfAbsent(p,AppEntry(p,p)) }
                val apps=catalog.load(known)
                s=save(s.copy(appCache=apps))
                val plan=PolicyPlanner.plan(s,apps,time.active)
                s=save(s.copy(timePending=true))
                val tr=policy.applyTime(plan.timeRequired,s.ownTimeRestriction)
                s=save(s.copy(ownTimeRestriction=tr.owned,timePending=false,timeResult=tr.message))
                if(!tr.complete) log(AuditEvent(LogCategory.PERMISSION_ERROR,"时间策略",tr.message,revision=s.revision))
                for((pkg,desired) in plan.targets) {
                    val app=apps[pkg]
                    if(app?.installed==false) {
                        s=save(s.copy(records=s.records+(pkg to PolicyRecord(result="UNINSTALLED",error="未安装"))))
                        continue
                    }
                    if(app==null) continue
                    val old=s.records[pkg]
                    if(old!=null && old.instance!=0L && old.instance!=app.instance) s=save(s.copy(records=s.records-(pkg)))
                    s=process(s,pkg,desired && catalog.protection(pkg).isEmpty(),app.instance)
                    val done=s.records[pkg]
                    if(pkg !in s.blacklist && done!=null && !done.ownHidden && !done.ownSuspended && done.pending.isEmpty() && done.result=="CONFIRMED") s=save(s.copy(records=s.records-pkg))
                }
                val next=ScheduleEvaluator.evaluate(s.schedule,clock.instant())
                val alarm=schedule(if(s.enabled) next.nextBoundary else null)
                if(alarm.startsWith("定时执行不可用") && alarm!=s.alarmStatus) log(AuditEvent(LogCategory.PERMISSION_ERROR,"边界调度",alarm,revision=s.revision))
                val failures=s.records.values.count { it.result in setOf("FAILED","UNKNOWN","PARTIAL") }
                val summary=if(failures==0 && tr.complete) if(s.enabled) "已核验当前策略" else "已关闭并解除本软件限制" else "存在执行异常，请查看逐项结果"
                s=save(s.copy(alarmStatus=alarm,lastEvaluation=summary,lastPolicyBoot=bootId ?: s.lastPolicyBoot))
            } while(s.enabled && ScheduleEvaluator.evaluate(s.schedule,clock.instant()).active != time.active)
        } catch(e:Exception) {
            mutableError.value=e.message ?: "策略执行失败"
            log(AuditEvent(LogCategory.PERMISSION_ERROR,"执行策略","失败：${e.javaClass.simpleName}"))
        }
    }
    private fun process(initial:ManagerState,pkg:String,restrict:Boolean,instance:Long):ManagerState {
        var s=initial;var record=s.records[pkg] ?: PolicyRecord(instance=instance)
        fun persist(r:PolicyRecord) { record=r;s=save(s.copy(records=s.records+(pkg to r))) }
        val observed=policy.inspect(pkg)
        if(record.pending.isNotEmpty()) {
            val value=if(record.pending.contains("HIDE")) observed.hidden else observed.suspended
            val applying=record.pending in setOf("HIDE","SUSPEND")
            if(value==null || (applying && value==true && !(if(record.pending=="HIDE") record.ownHidden else record.ownSuspended))) {
                persist(record.copy(result="UNKNOWN",error="上次调用中断，无法确认策略归属"));return s
            }
            persist(when(record.pending) {
                "UNHIDE" -> if(value==false) record.copy(ownHidden=false,pending="") else record.copy(pending="")
                "UNSUSPEND" -> if(value==false) record.copy(ownSuspended=false,pending="") else record.copy(pending="")
                else -> record.copy(pending="")
            })
        }
        fun step(name:String,target:Boolean,hidden:Boolean):Boolean {
            val current=policy.inspect(pkg).let { if(hidden) it.hidden else it.suspended }
            val owned=if(hidden) record.ownHidden else record.ownSuspended
            if(!target && !owned) return true
            if(target && current==true) {
                if(owned) return true
                persist(record.copy(result="UNKNOWN",error="已存在外部或归属未知的限制"));return false
            }
            if(target && current==null) { persist(record.copy(result="UNKNOWN",error="无法查询当前策略"));return false }
            if(!target && current==false) {
                persist(if(hidden) record.copy(ownHidden=false) else record.copy(ownSuspended=false));return true
            }
            if(target && catalog.protection(pkg).isNotEmpty()) { persist(record.copy(result="FAILED",error="应用已受保护"));return false }
            persist(record.copy(pending=name,result="PREPARED",operationId=UUID.randomUUID().toString(),revision=s.revision,baselineSuspended=policy.inspect(pkg).suspended,baselineHidden=policy.inspect(pkg).hidden))
            val result=if(hidden) policy.setHidden(pkg,target) else policy.setSuspended(pkg,target)
            if(result.success) {
                persist((if(hidden) record.copy(ownHidden=target) else record.copy(ownSuspended=target)).copy(pending="",error="",result="CONFIRMED"))
            } else persist(record.copy(pending="",result="FAILED",error=result.reason))
            log(AuditEvent(LogCategory.POLICY_EXECUTION,name,if(result.success) "成功" else result.reason,pkg,s.revision))
            return result.success
        }
        if(restrict) {
            if(!step("SUSPEND",true,false)) return s
            if(!step("HIDE",true,true) && record.ownSuspended) persist(record.copy(result="PARTIAL"))
        } else {
            val unhide=step("UNHIDE",false,true)
            val unsuspend=step("UNSUSPEND",false,false)
            if(unhide && unsuspend && record.pending.isEmpty()) persist(record.copy(result="CONFIRMED",error=""))
        }
        return s
    }
}
