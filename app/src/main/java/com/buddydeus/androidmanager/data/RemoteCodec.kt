package com.buddydeus.androidmanager.data

import com.buddydeus.androidmanager.domain.*
import org.json.JSONArray
import org.json.JSONObject

object RemoteCodec {
    fun syncRequest(s:ManagerState):JSONObject { check(s.initialized) { "请先完成本机初始化" };return JSONObject().put("candidate",candidate(s)) }
    fun stamp(s:SyncStamp)=JSONObject().put("mutationId",s.mutationId).put("modifiedAtMs",s.modifiedAtMs).put("timeQuality",s.timeQuality).put("origin",s.origin)
    fun readStamp(j:JSONObject)=SyncStamp(j.getString("mutationId"),integer(j,"modifiedAtMs"),j.getString("timeQuality"),j.getString("origin"))
    fun candidate(s:ManagerState):JSONObject {
        val meta=checkNotNull(s.remote) { "同步配置尚未建立" }
        return stamp(meta).put("config",config(s.managedConfig()))
    }
    fun config(c:ManagedConfig)=JSONObject().put("enabled",c.enabled).put("blacklist",JSONArray(c.blacklist.sorted()))
        .put("schedule",JSONArray(c.schedule.map { w -> w?.let { JSONObject().put("startMinute",it.startMinute).put("endMinute",it.endMinute) } ?: JSONObject.NULL }))
    fun snapshot(j:JSONObject):RemoteSnapshot {
        require(j.keys().asSequence().toSet()==setOf("mutationId","modifiedAtMs","timeQuality","origin","config","serverRevision")) { "不支持的同步格式" }
        val c=j.getJSONObject("config")
        require(c.keys().asSequence().toSet()==setOf("enabled","blacklist","schedule"))
        require(c.get("enabled") is Boolean)
        val a=c.getJSONArray("schedule");require(a.length()==7)
        val list=c.getJSONArray("blacklist");require(list.length()<=5000)
        return RemoteSnapshot(readStamp(j).copy(serverRevision=integer(j,"serverRevision"),pending=false),ManagedConfig(
            c.getBoolean("enabled"),(0 until list.length()).map { list.getString(it) }.toSet(),
            List(7) { i -> if(a.isNull(i)) null else a.getJSONObject(i).let { w ->
                require(w.keys().asSequence().toSet()==setOf("startMinute","endMinute"))
                val start=integer(w,"startMinute");val end=integer(w,"endMinute")
                require(start in 0..1439 && end in 0..1439)
                DayWindow(start.toInt(),end.toInt())
            } },
        ))
    }
    private fun integer(j:JSONObject,key:String):Long {
        val n=j.get(key);require(n is Number && n.toDouble()==n.toLong().toDouble() && n.toLong() in 0..9007199254740991L) { "同步数值无效" }
        return n.toLong()
    }
}
