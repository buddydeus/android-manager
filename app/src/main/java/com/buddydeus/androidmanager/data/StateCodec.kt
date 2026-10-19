package com.buddydeus.androidmanager.data

import com.buddydeus.androidmanager.domain.*
import org.json.JSONArray
import org.json.JSONObject

object StateCodec {
    fun encode(s: ManagerState): String = JSONObject().apply {
        put("schemaVersion",s.schemaVersion);put("revision",s.revision);put("initialized",s.initialized);put("enabled",s.enabled)
        put("schedule",JSONArray(s.schedule.map { w -> w?.let { JSONObject().put("start",it.startMinute).put("end",it.endMinute) } ?: JSONObject.NULL }))
        put("blacklist",JSONArray(s.blacklist.sorted()))
        put("appCache",JSONObject().apply { s.appCache.forEach { (k,v) -> put(k,JSONObject().put("name",v.name).put("system",v.system).put("installed",v.installed).put("instance",v.instance).put("protection",v.protection).put("launcher",v.knownLauncher).put("updating",v.updating)) } })
        put("records",JSONObject().apply { s.records.forEach { (k,r) -> put(k,JSONObject().apply {
            put("ownSuspended",r.ownSuspended);put("ownHidden",r.ownHidden);put("pending",r.pending);put("result",r.result);put("error",r.error)
            put("operationId",r.operationId);put("revision",r.revision);put("instance",r.instance)
            put("baselineSuspended",r.baselineSuspended ?: JSONObject.NULL);put("baselineHidden",r.baselineHidden ?: JSONObject.NULL)
            put("unknownSuspended",r.unknownSuspended);put("unknownHidden",r.unknownHidden)
        }) } })
        put("ownTimeRestriction",s.ownTimeRestriction);put("timePending",s.timePending);put("timeResult",s.timeResult)
        put("lastEvaluation",s.lastEvaluation);put("alarmStatus",s.alarmStatus);put("lastCleanupBoot",s.lastCleanupBoot);put("lastPolicyBoot",s.lastPolicyBoot)
    }.toString()
    fun decode(text: String): ManagerState {
        val j=JSONObject(text);require(j.getInt("schemaVersion")==1) { "不支持的状态格式" }
        val a=j.getJSONArray("schedule");require(a.length()==7)
        val b=j.getJSONArray("blacklist");val apps=j.getJSONObject("appCache");val records=j.getJSONObject("records")
        return ManagerState(
            revision=j.getLong("revision"),initialized=j.getBoolean("initialized"),enabled=j.getBoolean("enabled"),
            schedule=List(7) { i -> if(a.isNull(i)) null else a.getJSONObject(i).let { DayWindow(it.getInt("start"),it.getInt("end")) } },
            blacklist=(0 until b.length()).map { b.getString(it).also { p -> require(p.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) } }.toSet(),
            appCache=apps.keys().asSequence().associateWith { k -> apps.getJSONObject(k).let { AppEntry(k,it.getString("name"),it.getBoolean("system"),it.getBoolean("installed"),it.getLong("instance"),it.getString("protection"),it.getBoolean("launcher"),it.optBoolean("updating",false)) } },
            records=records.keys().asSequence().associateWith { k -> records.getJSONObject(k).let { r -> PolicyRecord(r.getBoolean("ownSuspended"),r.getBoolean("ownHidden"),r.getString("pending"),r.getString("result"),r.getString("error"),r.getString("operationId"),r.getLong("revision"),r.getLong("instance"),if(r.isNull("baselineSuspended")) null else r.getBoolean("baselineSuspended"),if(r.isNull("baselineHidden")) null else r.getBoolean("baselineHidden"),r.optBoolean("unknownSuspended",false),r.optBoolean("unknownHidden",false)) } },
            ownTimeRestriction=j.getBoolean("ownTimeRestriction"),timePending=j.getBoolean("timePending"),timeResult=j.getString("timeResult"),
            lastEvaluation=j.getString("lastEvaluation"),alarmStatus=j.getString("alarmStatus"),lastCleanupBoot=j.getInt("lastCleanupBoot"),lastPolicyBoot=j.getInt("lastPolicyBoot"),
        )
    }
}
