package com.buddydeus.androidmanager.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

data class AuthMetadata(val directory: String = "",val failures: Int = 0,val wallUntil: Long = 0,val elapsedUntil: Long = 0,val boot: Int = -1)
interface AuthMetadataStore { fun read(): AuthMetadata; fun write(value: AuthMetadata) }
class AuthMetadataRepository(context: Context) : AuthMetadataStore {
    private val file=AtomicFile(File(context.filesDir,"auth-meta.json"))
    @Synchronized override fun read(): AuthMetadata {
        if(!file.baseFile.exists()) return AuthMetadata()
        val j=JSONObject(file.openRead().bufferedReader().use { it.readText() })
        return AuthMetadata(j.getString("directory"),j.getInt("failures"),j.getLong("wallUntil"),j.getLong("elapsedUntil"),j.getInt("boot"))
    }
    @Synchronized override fun write(value: AuthMetadata) {
        val bytes=JSONObject().put("directory",value.directory).put("failures",value.failures).put("wallUntil",value.wallUntil).put("elapsedUntil",value.elapsedUntil).put("boot",value.boot).toString().toByteArray()
        val out=file.startWrite();try { out.write(bytes);file.finishWrite(out) } catch(e: Exception) { file.failWrite(out);throw e }
    }
}
