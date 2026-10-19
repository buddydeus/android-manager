package com.buddydeus.androidmanager.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

class SafPasswordRepository(private val context: Context,private val metadata: AuthMetadataStore) : PasswordAccess {
    private val resolver get()=context.contentResolver
    @Volatile private var candidate: String?=null
    fun selectDirectory(uri: Uri) {
        require(uri.authority=="com.android.externalstorage.documents") { "请选择手机本地存储" }
        val id=DocumentsContract.getTreeDocumentId(uri)
        require(id.startsWith("primary:") && id.substringAfter(':').isNotBlank() && !id.substringAfter(':').startsWith("Android/",true)) { "请选择主存储 Documents 内的目录" }
        resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        children(uri)
        candidate=uri.toString()
    }
    private fun tree(): Uri { val d=candidate ?: metadata.read().directory;check(d.isNotBlank()) { "尚未授权密码目录" };return Uri.parse(d) }
    private fun children(uri: Uri): List<Uri> {
        val parent=DocumentsContract.getTreeDocumentId(uri)
        val list=DocumentsContract.buildChildDocumentsUriUsingTree(uri,parent)
        return resolver.query(list,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { c ->
            check(!c.extras.getBoolean(DocumentsContract.EXTRA_LOADING,false)) { "目录仍在加载" }
            buildList { while(c.moveToNext()) if(c.getString(1)=="password.json") add(DocumentsContract.buildDocumentUriUsingTree(uri,c.getString(0))) }
        } ?: error("无法查询密码目录")
    }
    private fun read(uri: Uri): String = resolver.openInputStream(uri)?.use { stream ->
        val buffer=ByteArray(8193);var count=0
        while(count<buffer.size) { val n=stream.read(buffer,count,buffer.size-count);if(n<0) break;count+=n }
        require(count<=8192) { "密码文件过大" };String(buffer,0,count,Charsets.UTF_8)
    } ?: error("无法打开密码文件")
    override fun inspect(): PasswordFileState = try {
        val files=children(tree())
        when { files.isEmpty() -> PasswordFileState.Missing
            files.size!=1 -> PasswordFileState.Broken("CORRUPT","存在重复密码文件")
            else -> { val text=read(files.single());PasswordCodec.validate(text);PasswordFileState.Ready(text,PasswordCodec.fingerprint(text)) } }
    } catch(e: SecurityException) { PasswordFileState.Broken("ACCESS_DENIED","密码目录授权失效") }
    catch(e: org.json.JSONException) { PasswordFileState.Broken("CORRUPT","密码文件损坏") }
    catch(e: IllegalArgumentException) { PasswordFileState.Broken("CORRUPT","密码文件或目录格式异常") }
    catch(e: Exception) { PasswordFileState.Broken("IO_ERROR","密码存储不可访问") }
    override fun writePin(pin: CharArray): String {
        val text=PasswordCodec.create(pin);val directory=tree();val files=children(directory)
        check(files.size<=1) { "存在重复密码文件" }
        val target=files.singleOrNull() ?: DocumentsContract.createDocument(resolver,DocumentsContract.buildDocumentUriUsingTree(directory,DocumentsContract.getTreeDocumentId(directory)),"application/json","password.json") ?: error("创建密码文件失败")
        resolver.openOutputStream(target,"rwt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("写入密码文件失败")
        val saved=read(target);check(saved==text && PasswordCodec.verify(saved,pin)) { "密码回读验证失败" }
        candidate?.let { metadata.write(metadata.read().copy(directory=it));candidate=null }
        return PasswordCodec.fingerprint(saved)
    }
    override fun location(): String = runCatching { DocumentsContract.getTreeDocumentId(tree())+"/password.json" }.getOrDefault("未选择密码目录")
}
