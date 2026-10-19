package com.buddydeus.androidmanager.data

import android.util.AtomicFile
import com.buddydeus.androidmanager.domain.*
import java.io.File

class StateRepository(directory: File) : StateStore {
    private val file=AtomicFile(File(directory,"state.json"))
    private val marker=File(directory,"state.created")
    init { check(directory.isDirectory || directory.mkdirs()) { "无法访问私有存储" } }
    @Synchronized override fun read(): ManagerState {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) {
            check(!marker.exists()) { "配置文件缺失，已停止新增管控" }
            return ManagerState()
        }
        return StateCodec.decode(file.openRead().bufferedReader().use { it.readText() })
    }
    @Synchronized override fun write(state: ManagerState) {
        val bytes=StateCodec.encode(state).toByteArray(Charsets.UTF_8)
        val out=file.startWrite()
        try { out.write(bytes);file.finishWrite(out); if(!marker.exists()) marker.writeText("1") }
        catch(e: Exception) { file.failWrite(out);throw e }
    }
}
