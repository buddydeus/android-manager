package com.buddydeus.androidmanager.data

import android.os.SystemClock
import android.util.AtomicFile
import com.buddydeus.androidmanager.domain.*
import org.json.JSONObject
import java.io.File

class SyncClockRepository(directory:File,private val boot:()->Int) {
    private val file=AtomicFile(File(directory,"sync-clock.json"))
    @Synchronized private fun read():TimeAnchor? {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return null
        val j=JSONObject(file.openRead().bufferedReader().use { it.readText() })
        require(j.getInt("schemaVersion")==1) { "不支持的同步时钟格式" }
        return TimeAnchor(j.getLong("serverAtMs"),j.getLong("elapsedAtMs"),j.getLong("wallAtMs"),j.getInt("bootId"),j.getLong("uncertaintyMs"))
    }
    @Synchronized fun now():SyncTime=read()?.time(System.currentTimeMillis(),SystemClock.elapsedRealtime(),boot()) ?: SyncTime(System.currentTimeMillis(),"UNCALIBRATED")
    @Synchronized fun calibrate(server:Long,sent:Long,received:Long) {
        val rtt=received-sent;if(rtt !in 0..10000) return
        val old=read();val bootId=boot()
        if(old!=null && old.bootId==bootId && received-old.elapsedAtMs in 0..3600000 && old.uncertaintyMs<rtt/2) return
        val anchor=TimeAnchor(server+rtt/2,received,System.currentTimeMillis(),bootId,rtt/2)
        val json=JSONObject().put("schemaVersion",1).put("serverAtMs",anchor.serverAtMs).put("elapsedAtMs",anchor.elapsedAtMs).put("wallAtMs",anchor.wallAtMs).put("bootId",bootId).put("uncertaintyMs",anchor.uncertaintyMs)
        val out=file.startWrite()
        try { out.write(json.toString().toByteArray());file.finishWrite(out) } catch(e:Exception) { file.failWrite(out);throw e }
    }
}
