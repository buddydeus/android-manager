package com.buddydeus.androidmanager.application

import com.buddydeus.androidmanager.data.UsageRepository
import com.buddydeus.androidmanager.domain.RebootGuard
import org.json.JSONObject
import java.util.UUID

class RebootController(
    private val store:UsageRepository,private val coordinator:PolicyCoordinator,private val boot:()->Int,
    private val connection:()->String?,private val now:()->Long,private val send:(JSONObject)->Boolean,
    private val reboot:()->Unit,private val installing:()->Boolean,private val notify:()->Unit,
) {
    private data class Pending(val connection:String,val boot:Int,val deadline:Long,val sentAt:Long)
    private val pending=mutableMapOf<String,Pending>()
    @Synchronized fun disconnected() {
        pending.clear()
        store.operations("reboot").filter { it.getString("state")=="ACCEPTED" }.forEach { result(it,"UNKNOWN","执行许可连接已中断，旧命令不会重试") }
    }
    @Synchronized fun message(message:JSONObject) {
        val id=message.getString("commandId").also { UUID.fromString(it) }
        val channel=message.getString("connectionId").also { UUID.fromString(it) }
        if(channel!=connection()) return
        when(message.getString("type")) {
            "reboot.request" -> {
                if(store.operation(id)!=null) { notify();return }
                val commandBoot=message.getInt("bootId");val ttl=message.getLong("ttlMs").also { require(it in 1..10000) }
                val row=JSONObject().put("id",id).put("kind","reboot").put("version",1).put("pending",true)
                    .put("state","ACCEPTED").put("result","设备已接收，等待本连接执行许可").put("bootId",commandBoot).put("atMs",System.currentTimeMillis())
                store.saveOperation("reboot",row);notify()
                if(commandBoot<0 || commandBoot!=boot() || installing()) { result(row,"FAILED","启动身份不匹配或正在安装，拒绝重启");return }
                val sent=now();pending[id]=Pending(channel,commandBoot,sent+ttl,sent)
                if(!send(JSONObject().put("type","reboot.permit.request").put("commandId",id).put("connectionId",channel))) {
                    pending.remove(id);result(row,"UNKNOWN","执行许可未发出；旧命令不会重试")
                }
            }
            "reboot.permit" -> {
                val p=pending.remove(id) ?: return
                val row=store.operation(id) ?: error("重启意图缺失")
                val received=now();val rtt=(received-p.sentAt).coerceAtLeast(0)
                val remaining=message.getDouble("remainingMs").also { require(it.isFinite() && it in 0.0..10000.0) }.toLong()-rtt
                val deadline=minOf(p.deadline,received+remaining)
                if(!message.getBoolean("allowed") || !RebootGuard.allowed(p.connection,connection(),p.boot,boot(),deadline,received,row.getString("state")!="ACCEPTED")) {
                    result(row,"UNKNOWN","许可失效或连接已变化；旧命令不会重试");return
                }
                try {
                    coordinator.remoteAction("远程整机重启") {
                        check(!installing() && RebootGuard.allowed(p.connection,connection(),p.boot,boot(),deadline,now(),row.getString("state")!="ACCEPTED")) { "重启条件已变化" }
                        result(row,"ATTEMPTED","已持久记录系统调用尝试；等待后续启动证据")
                        check(!installing() && RebootGuard.allowed(p.connection,connection(),p.boot,boot(),deadline,now(),false)) { "重启许可在持久保存期间失效，已中止" }
                        reboot()
                    }
                } catch(e:Exception) { result(row,"FAILED","系统重启调用失败：${e.message ?: e.javaClass.simpleName}".take(1000)) }
            }
            else -> error("不支持的重启协议")
        }
    }
    private fun result(row:JSONObject,state:String,description:String) {
        val latest=store.operation(row.getString("id")) ?: error("重启意图缺失")
        store.saveOperation("reboot",JSONObject(latest.toString()).put("version",latest.getInt("version")+1).put("pending",true).put("state",state).put("result",description));notify()
    }
}
