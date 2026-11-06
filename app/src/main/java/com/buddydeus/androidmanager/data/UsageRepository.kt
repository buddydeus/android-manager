package com.buddydeus.androidmanager.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.buddydeus.androidmanager.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

object UsageCodec {
    fun encode(s:UsageSession)=JSONObject().put("id",s.id).put("recordVersion",s.recordVersion).put("bootKey",s.bootKey).put("bootId",s.bootId)
        .put("observedAtMs",s.observedAtMs).put("startedAtMs",s.startedAtMs ?: JSONObject.NULL).put("endedAtMs",s.endedAtMs ?: JSONObject.NULL)
        .put("durationMs",s.durationMs ?: JSONObject.NULL).put("quality",s.quality.name).put("source",s.source.name).put("clockAdjusted",s.clockAdjusted)
    fun decode(j:JSONObject)=UsageSession(id=j.getString("id"),recordVersion=j.getInt("recordVersion"),bootKey=j.getString("bootKey"),bootId=j.getInt("bootId"),
        observedAtMs=j.getLong("observedAtMs"),startedAtMs=number(j,"startedAtMs"),endedAtMs=number(j,"endedAtMs"),durationMs=number(j,"durationMs"),
        quality=UsageQuality.valueOf(j.getString("quality")),source=UsageSource.valueOf(j.getString("source")),clockAdjusted=j.getBoolean("clockAdjusted"))
    fun number(j:JSONObject,key:String)=if(j.isNull(key)) null else j.getLong(key)
}
class UsageRepository(context:Context,databaseName:String="remote.db"):SQLiteOpenHelper(context.createDeviceProtectedStorageContext(),databaseName,null,2) {
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,kind TEXT NOT NULL,version INTEGER NOT NULL,dirty INTEGER NOT NULL,untilMs INTEGER NOT NULL,payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX records_pending ON records(dirty,kind)")
        db.execSQL("CREATE TABLE metadata(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        createOperations(db)
    }
    private fun createOperations(db:SQLiteDatabase) { db.execSQL("CREATE TABLE operations(id TEXT PRIMARY KEY,kind TEXT NOT NULL,pending INTEGER NOT NULL,payload TEXT NOT NULL)") }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        check(oldVersion==1 && newVersion==2) { "不支持的远程存储版本 $oldVersion → $newVersion" };createOperations(db)
    }
    override fun onDowngrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) { error("远程存储版本过新") }
    private fun meta(db:SQLiteDatabase,key:String):JSONObject?=db.rawQuery("SELECT payload FROM metadata WHERE id=?",arrayOf(key)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
    private fun saveMeta(db:SQLiteDatabase,key:String,j:JSONObject) { check(db.insertWithOnConflict("metadata",null,ContentValues().apply { put("id",key);put("payload",j.toString()) },SQLiteDatabase.CONFLICT_REPLACE)!=-1L) }
    private fun put(db:SQLiteDatabase,kind:String,j:JSONObject,until:Long) {
        db.insertWithOnConflict("records",null,ContentValues().apply { put("id",j.getString("id"));put("kind",kind);put("version",j.getInt("recordVersion"));put("dirty",1);put("untilMs",until);put("payload",j.toString()) },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) { "使用记录写入失败" } }
    }
    private fun session(db:SQLiteDatabase,s:UsageSession) {
        val j=UsageCodec.encode(s).put("startElapsedMs",s.startElapsedMs ?: JSONObject.NULL)
        put(db,"sessions",j,if(s.quality==UsageQuality.IN_PROGRESS) Long.MAX_VALUE else maxOf(s.observedAtMs,s.endedAtMs ?: s.startedAtMs ?: 0,(s.startedAtMs ?: 0)+(s.durationMs ?: 0)))
    }
    @Synchronized fun record(kind:UsageKind,wall:Long,elapsed:Long,bootId:Int,available:Boolean=false,interactive:Boolean=false):JSONObject {
        val db=writableDatabase;db.beginTransaction()
        try {
            val previous=meta(db,"monitor")
            val newBoot=previous==null || (bootId>=0 && previous.getInt("bootId")!=bootId) || elapsed<(previous?.getLong("elapsed") ?: 0)
            val bootKey=if(newBoot) UUID.randomUUID().toString() else previous!!.getString("bootKey")
            if(previous==null && elapsed>0) put(db,"gaps",JSONObject().put("id",UUID.randomUUID().toString()).put("recordVersion",1)
                .put("startedAtMs",(wall-elapsed).coerceAtLeast(0)).put("endedAtMs",wall).put("reason","INITIAL_COVERAGE_UNKNOWN").put("resolved",false),wall)
            val priorActive=previous?.optJSONObject("active")?.let { UsageCodec.decode(it).copy(startElapsedMs=UsageCodec.number(it,"startElapsedMs")) }
            val state=UsageState(priorActive,previous?.let { if(it.isNull("interactive")) null else it.getBoolean("interactive") },previous?.optString("fingerprint") ?: "")
            if(kind==UsageKind.ATTACH && previous!=null) {
                val start=previous.getLong("wall")
                if(wall>start) put(db,"gaps",JSONObject().put("id",UUID.randomUUID().toString()).put("recordVersion",1).put("startedAtMs",start).put("endedAtMs",wall).put("reason","MONITOR_INTERRUPTED").put("resolved",false),wall)
            }
            val transition=UsageMachine.reduce(state,UsageEvent(kind,wall,elapsed,bootKey,bootId,available,interactive))
            transition.changed.forEach { session(db,it) }
            val bootAt=if(newBoot) (wall-elapsed).coerceAtLeast(0) else previous!!.getLong("bootStartedAtMs")
            val next=JSONObject().put("firstObservedAtMs",previous?.optLong("firstObservedAtMs",previous.getLong("wall")) ?: wall).put("bootKey",bootKey).put("bootId",bootId).put("bootStartedAtMs",bootAt).put("wall",wall).put("elapsed",elapsed)
                .put("interactive",transition.state.interactive ?: JSONObject.NULL).put("fingerprint",transition.state.fingerprint)
                .put("active",transition.state.active?.let { UsageCodec.encode(it).put("startElapsedMs",it.startElapsedMs ?: JSONObject.NULL) } ?: JSONObject.NULL)
            saveMeta(db,"monitor",next);cleanup(db,wall);db.setTransactionSuccessful();return next
        } finally { db.endTransaction() }
    }
    @Synchronized fun checkpoint(wall:Long,elapsed:Long) {
        val db=writableDatabase;val j=meta(db,"monitor") ?: return
        saveMeta(db,"monitor",j.put("wall",wall).put("elapsed",elapsed))
    }
    @Synchronized fun monitor():JSONObject?=meta(readableDatabase,"monitor")
    @Synchronized fun historyCursor():Long=meta(readableDatabase,"history")?.getLong("until") ?: 0
    @Synchronized fun recover(records:List<UsageSession>,until:Long) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val monitor=meta(db,"monitor") ?: return
            // A wall-clock discontinuity makes matching history with monotonic live records ambiguous.
            val expected=monitor.getLong("bootStartedAtMs")+monitor.getLong("elapsed")
            if(kotlin.math.abs(expected-monitor.getLong("wall"))>2000) return
            records.forEach { incoming ->
                val from=incoming.startedAtMs!!;val end=incoming.endedAtMs!!
                val existing=mutableListOf<UsageSession>()
                db.rawQuery("SELECT payload FROM records WHERE kind='sessions' AND untilMs>=?",arrayOf((from-2000).toString())).use { c ->
                    while(c.moveToNext()) {
                        val row=UsageCodec.decode(JSONObject(c.getString(0)))
                        if(row.bootKey==incoming.bootKey && row.observedAtMs<=end+2000) existing+=row
                    }
                }
                val overlaps=existing.filter { row ->
                    val a=row.startedAtMs ?: row.observedAtMs;val b=row.endedAtMs ?: row.startedAtMs ?: row.observedAtMs
                    (a<=end && b>=from) || (row.startedAtMs?.let { kotlin.math.abs(it-from)<=2000 }==true) || (row.endedAtMs?.let { kotlin.math.abs(it-end)<=2000 }==true)
                }
                if(overlaps.any { it.quality in setOf(UsageQuality.COMPLETE,UsageQuality.IN_PROGRESS) }) return@forEach
                val matches=overlaps.filter { row ->
                    (row.startedAtMs?.let { kotlin.math.abs(it-from)<=2000 }==true) || (row.endedAtMs?.let { kotlin.math.abs(it-end)<=2000 }==true)
                }
                if(matches.size>1 || overlaps.size>1 || (overlaps.isNotEmpty() && matches.isEmpty())) return@forEach
                val match=matches.singleOrNull()
                session(db,if(match==null) incoming else incoming.copy(id=match.id,recordVersion=match.recordVersion+1,observedAtMs=match.observedAtMs))
                val gaps=mutableListOf<JSONObject>()
                db.rawQuery("SELECT payload FROM records WHERE kind='gaps' AND untilMs>=?",arrayOf(from.toString())).use { c -> while(c.moveToNext()) {
                    val gap=JSONObject(c.getString(0));if(!gap.getBoolean("resolved") && gap.getLong("startedAtMs")>=from && gap.getLong("endedAtMs")<=end) gaps+=gap
                } }
                gaps.forEach { gap -> put(db,"gaps",gap.put("resolved",true).put("recordVersion",gap.getInt("recordVersion")+1),gap.getLong("endedAtMs")) }
            }
            saveMeta(db,"history",JSONObject().put("until",until));db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun associateDevice(deviceId:String):Boolean {
        require(UUID.fromString(deviceId).toString()==deviceId)
        val db=writableDatabase;db.beginTransaction()
        try {
            if(meta(db,"server")?.optString("deviceId")==deviceId) { db.setTransactionSuccessful();return false }
            cleanup(db,System.currentTimeMillis());db.execSQL("UPDATE records SET dirty=1")
            saveMeta(db,"server",JSONObject().put("deviceId",deviceId));db.setTransactionSuccessful();return true
        } finally { db.endTransaction() }
    }
    @Synchronized fun pending():JSONObject {
        val result=JSONObject().put("sessions",JSONArray()).put("gaps",JSONArray())
        readableDatabase.rawQuery("SELECT kind,payload FROM records WHERE dirty=1 ORDER BY rowid LIMIT 200",null).use { c -> while(c.moveToNext()) {
            val j=JSONObject(c.getString(1));j.remove("startElapsedMs");result.getJSONArray(c.getString(0)).put(j)
        } };return result
    }
    @Synchronized fun acknowledge(response:JSONObject,deviceId:String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            if(meta(db,"server")?.optString("deviceId")!=deviceId) return
            listOf("sessions","gaps").forEach { kind -> val rows=response.getJSONArray(kind);for(i in 0 until rows.length()) {
            val r=rows.getJSONObject(i);db.execSQL("UPDATE records SET dirty=0 WHERE id=? AND kind=? AND version=?",arrayOf(r.getString("id"),kind,r.getInt("recordVersion")))
        } };db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    @Synchronized fun operation(id:String):JSONObject?=readableDatabase.rawQuery("SELECT payload FROM operations WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
    @Synchronized fun saveOperation(kind:String,j:JSONObject) {
        check(writableDatabase.insertWithOnConflict("operations",null,ContentValues().apply { put("id",j.getString("id"));put("kind",kind);put("pending",if(j.getBoolean("pending")) 1 else 0);put("payload",j.toString()) },SQLiteDatabase.CONFLICT_REPLACE)!=-1L) { "远程操作保存失败" }
    }
    @Synchronized fun operations(kind:String,pendingOnly:Boolean=false):List<JSONObject> = readableDatabase.rawQuery("SELECT payload FROM operations WHERE kind=?"+(if(pendingOnly) " AND pending=1" else "")+" ORDER BY rowid DESC LIMIT 200",arrayOf(kind)).use { c -> buildList { while(c.moveToNext()) add(JSONObject(c.getString(0))) } }
    @Synchronized fun acknowledgeOperation(id:String,version:Int) {
        val j=operation(id) ?: return
        if(j.getInt("version")==version) saveOperation(j.getString("kind"),j.put("pending",false))
    }
    @Synchronized fun retireOrphanedOperation(id:String,version:Int) {
        val j=operation(id) ?: return
        if(j.getInt("version")==version) saveOperation(j.getString("kind"),j.put("pending",false).put("receiptDisposition","SERVER_RECEIPT_UNVERIFIABLE"))
    }
    private fun cleanup(db:SQLiteDatabase,wall:Long) {
        val cutoff=java.time.Instant.ofEpochMilli(wall).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().minusDays(90).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        db.delete("records","untilMs<?",arrayOf(cutoff.toString()))
    }
}
