package com.buddydeus.androidmanager.application

import com.buddydeus.androidmanager.data.*
import com.buddydeus.androidmanager.domain.*

sealed class AuthResult {
    data object Authenticated : AuthResult()
    data object NeedsNewPin : AuthResult()
    data class Denied(val message: String) : AuthResult()
}
class AuthController(
    private val passwords: PasswordAccess,private val metadata: AuthMetadataStore,
    private val audit: AuditSink,private val wall: () -> Long,private val elapsed: () -> Long,private val boot: () -> Int,
) {
    private val lock=Any()
    private var generation=0L
    private var fingerprint: String?=null
    private var maySetPin=false
    @Volatile private var lastStorageFailure:String?=null
    fun invalidate() { synchronized(lock) { generation++;fingerprint=null;maySetPin=false } }
    private fun log(action: String,result: String) { audit.append(AuditEvent(LogCategory.LOGIN_SECURITY,action,result)) }
    fun inspectPassword():PasswordFileState {
        val value=passwords.inspect()
        val kind=(value as? PasswordFileState.Broken)?.kind
        if(kind!=null && kind!=lastStorageFailure) audit.append(AuditEvent(LogCategory.PERMISSION_ERROR,"密码存储",kind))
        lastStorageFailure=kind
        return value
    }
    fun pausedMillis(): Long {
        val m=metadata.read()
        return (if(m.boot==boot()) m.elapsedUntil-elapsed() else m.wallUntil-wall()).coerceIn(0,300000)
    }
    fun generationToken():Long=synchronized(lock) { generation }
    fun login(pin: CharArray,token:Long=generationToken()): AuthResult {
        val epoch=token
        try {
            if(epoch!=generationToken()) return AuthResult.Denied("会话已失效，请重新操作")
            if(pausedMillis()>0) return AuthResult.Denied("登录暂停，请稍后再试")
            val previous=metadata.read()
            if(previous.failures>=5) metadata.write(previous.copy(failures=0,wallUntil=0,elapsedUntil=0))
            val file=inspectPassword()
            if(file is PasswordFileState.Broken) return AuthResult.Denied(file.reason)
            val valid=when(file) { is PasswordFileState.Ready -> PasswordCodec.verify(file.content,pin);PasswordFileState.Missing -> pin.contentEquals(charArrayOf('0','0','0','0'));else -> false }
            if(!valid) {
                val m=metadata.read();val failures=m.failures+1
                metadata.write(m.copy(failures=failures,wallUntil=if(failures>=5) wall()+300000 else 0,elapsedUntil=if(failures>=5) elapsed()+300000 else 0,boot=boot()))
                log("密码验证","失败")
                return AuthResult.Denied(if(failures>=5) "连续错误5次，暂停5分钟" else "密码错误")
            }
            metadata.write(metadata.read().copy(failures=0,wallUntil=0,elapsedUntil=0))
            return synchronized(lock) {
                if(epoch!=generation) AuthResult.Denied("会话已失效，请重新登录")
                else if(file is PasswordFileState.Ready && !passwords.needsDirectoryCommit()) { fingerprint=file.fingerprint;AuthResult.Authenticated }
                else { maySetPin=true;AuthResult.NeedsNewPin }
            }
        } finally { pin.fill('\u0000') }
    }
    fun beginRecovery(token:Long=generationToken()): Boolean {
        if(inspectPassword() !is PasswordFileState.Broken) return false
        synchronized(lock) { if(token!=generation) return false;generation++;fingerprint=null;maySetPin=true };return true
    }
    fun setNewPin(pin: CharArray,token:Long=generationToken()): AuthResult {
        val epoch=token
        try {
            if(synchronized(lock) { !maySetPin || epoch!=generation }) return AuthResult.Denied("请先验证默认密码或进入恢复流程")
            val fp=passwords.writePin(pin)
            log("设置密码","成功")
            return synchronized(lock) { if(epoch!=generation) AuthResult.Denied("会话已失效") else { fingerprint=fp;maySetPin=false;AuthResult.Authenticated } }
        } finally { pin.fill('\u0000') }
    }
    fun requireSession(token:Long=generationToken()): Boolean {
        val expected=synchronized(lock) { if(token!=generation) return false;fingerprint } ?: return false
        val current=inspectPassword()
        val valid=current is PasswordFileState.Ready && current.fingerprint==expected
        if(!valid) invalidate()
        return valid && synchronized(lock) { token==generation && fingerprint==expected }
    }
    fun changePin(pin: CharArray,token:Long=generationToken()): AuthResult {
        try {
            if(!requireSession(token)) return AuthResult.Denied("请重新登录")
            passwords.writePin(pin);log("修改密码","成功");invalidate()
            return AuthResult.Denied("密码已修改，请重新登录")
        } finally { pin.fill('\u0000') }
    }
}
