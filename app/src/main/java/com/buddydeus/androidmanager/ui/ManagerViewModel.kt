package com.buddydeus.androidmanager.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.buddydeus.androidmanager.application.*
import com.buddydeus.androidmanager.data.*
import com.buddydeus.androidmanager.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

enum class Screen { OWNER, DIRECTORY, LOGIN, NEW_PIN, STORAGE_ERROR, MAIN }
data class ManagerUiState(
    val screen:Screen=Screen.LOGIN,val busy:Boolean=false,val message:String="",val config:ManagerState?=null,
    val error:String="",val location:String="",val owner:Boolean=false,val logs:List<String> = emptyList(),
)
class ManagerViewModel(private val graph:ManagerGraph):ViewModel() {
    private val mutable=MutableStateFlow(ManagerUiState())
    val ui=mutable.asStateFlow()
    private val generation=AtomicLong()
    private var recovering=false
    init {
        viewModelScope.launch { graph.coordinator.snapshots.collect { s -> mutable.update { it.copy(config=s) } } }
        viewModelScope.launch { graph.coordinator.errors.collect { e -> mutable.update { it.copy(error=e) } } }
        refresh()
    }
    private fun post(epoch:Long,block:(ManagerUiState)->ManagerUiState) { mutable.update { if(epoch==generation.get()) block(it) else it } }
    private fun task(block:(Long)->Unit) {
        if(mutable.value.busy) return
        val epoch=generation.get();mutable.update { it.copy(busy=true,message="") }
        viewModelScope.launch(Dispatchers.IO) {
            try { if(epoch==generation.get()) block(epoch) } catch(e:Exception) { post(epoch) { it.copy(message=e.message ?: "操作失败") } }
            finally { post(epoch) { it.copy(busy=false) } }
        }
    }
    fun leave() {
        generation.incrementAndGet();graph.credentials.auth.invalidate();recovering=false
        mutable.update { it.copy(screen=Screen.LOGIN,busy=false,logs=emptyList(),message="请重新验证密码") }
    }
    fun refresh()=task { epoch ->
        val s=graph.coordinator.refresh();val c=graph.credentials
        val owner=graph.policy.isOwner()
        val file=c.auth.inspectPassword()
        val screen=when {
            !s.initialized && !owner -> Screen.OWNER
            c.auth.requireSession() -> Screen.MAIN
            c.passwords.location()=="未选择密码目录" -> Screen.DIRECTORY
            file is PasswordFileState.Broken -> Screen.STORAGE_ERROR
            else -> Screen.LOGIN
        }
        post(epoch) { it.copy(screen=screen,config=s,owner=owner,location=c.passwords.location(),message=if(file is PasswordFileState.Broken && screen==Screen.STORAGE_ERROR) file.reason else "") }
        if(s.initialized) graph.request("管理界面进入")
    }
    fun directory(uri:Uri)=task { epoch ->
        graph.credentials.passwords.selectDirectory(uri)
        post(epoch) { it.copy(screen=if(recovering) Screen.NEW_PIN else Screen.LOGIN,location=graph.credentials.passwords.location(),message=if(recovering) "请设置至少6位数字的新密码" else "目录已授权，请验证密码；首次使用输入 0000") }
    }
    fun recover() {
        val token=graph.credentials.auth.generationToken()
        task { epoch ->
            check(graph.credentials.auth.beginRecovery(token)) { "恢复请求已失效或当前文件可正常验证" }
            if(epoch==generation.get()) recovering=true
            post(epoch) { it.copy(screen=Screen.DIRECTORY,message="重新选择本地密码目录，随后设置新密码") }
        }
    }
    private fun authenticated(epoch:Long,result:AuthResult,token:Long) {
        if(epoch!=generation.get()) return
        when(result) {
            AuthResult.NeedsNewPin -> post(epoch) { it.copy(screen=Screen.NEW_PIN,message="请设置至少6位数字的新密码") }
            AuthResult.Authenticated -> {
                if(!graph.credentials.auth.requireSession(token)) { post(epoch) { it.copy(screen=Screen.LOGIN,message="验证已失效") };return }
                val s=graph.coordinator.refresh()
                if(!s.initialized) {
                    check(graph.policy.isOwner()) { "尚未取得 Device Owner" }
                    check(graph.context.getSystemService(android.os.UserManager::class.java).isSystemUser) { "仅支持主用户" }
                    graph.coordinator.updateConfig("完成首次初始化") { check(epoch==generation.get() && graph.credentials.auth.requireSession(token));it.copy(initialized=true) }
                }
                post(epoch) { it.copy(screen=Screen.MAIN,location=graph.credentials.passwords.location(),logs=emptyList()) }
                graph.request("认证后核验")
            }
            is AuthResult.Denied -> post(epoch) { it.copy(message=result.message) }
        }
    }
    fun login(pin:String) {
        val token=graph.credentials.auth.generationToken()
        task { authenticated(it,graph.credentials.auth.login(pin.toCharArray(),token),token) }
    }
    fun setPin(pin:String,confirmation:String) {
        val token=graph.credentials.auth.generationToken()
        task { epoch ->
            require(pin==confirmation) { "两次密码不一致" }
            authenticated(epoch,graph.credentials.auth.setNewPin(pin.toCharArray(),token),token)
        }
    }
    private fun command(action:String,transform:(ManagerState)->ManagerState) {
        val token=graph.credentials.auth.generationToken()
        task { epoch ->
            val c=graph.credentials
            if(!c.auth.requireSession(token)) { post(epoch) { it.copy(screen=Screen.LOGIN,logs=emptyList(),message="请重新登录") };return@task }
            graph.coordinator.updateConfig(action) { check(epoch==generation.get() && c.auth.requireSession(token)) { "会话已失效" };transform(it) }
            post(epoch) { it.copy(message="已保存，正在应用") };graph.request(action)
        }
    }
    fun setEnabled(enabled:Boolean)=command("切换总开关") { it.copy(enabled=enabled) }
    fun setBlacklisted(pkg:String,selected:Boolean)=command("修改黑名单") {
        if(selected) check(graph.catalog.protection(pkg).isEmpty()) { "受保护应用不可加入" }
        it.copy(blacklist=if(selected) it.blacklist+pkg else it.blacklist-pkg)
    }
    fun saveSchedule(schedule:List<DayWindow?>)=command("修改周时间表") { require(schedule.size==7);it.copy(schedule=schedule) }
    fun changePin(pin:String,confirmation:String) {
        val token=graph.credentials.auth.generationToken()
        task { epoch ->
            require(pin==confirmation) { "两次密码不一致" }
            val result=graph.credentials.auth.changePin(pin.toCharArray(),token)
            post(epoch) { it.copy(screen=Screen.LOGIN,logs=emptyList(),message=(result as? AuthResult.Denied)?.message.orEmpty()) }
        }
    }
    fun loadLogs(date:String,category:LogCategory?,offset:Int) {
        val token=graph.credentials.auth.generationToken()
        task { epoch ->
            check(graph.credentials.auth.requireSession(token)) { "请重新登录" }
            val rows=graph.audit.readPage(LocalDate.parse(date),category,offset,50)
            if(graph.credentials.auth.requireSession(token)) post(epoch) { it.copy(logs=rows,message=graph.audit.lastReadWarning) }
            else post(epoch) { it.copy(screen=Screen.LOGIN,logs=emptyList()) }
        }
    }
}
