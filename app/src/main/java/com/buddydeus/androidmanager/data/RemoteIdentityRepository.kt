package com.buddydeus.androidmanager.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

data class RemoteIdentity(val installId:String,val deviceId:String="",val token:String="")
class RemoteIdentityRepository(directory:File) {
    private val file=AtomicFile(File(directory,"remote-identity.json"))
    private val marker=File(directory,"remote-identity.created")
    private val alias="android-manager-remote-install"
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized fun read():RemoteIdentity {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) {
            check(!marker.exists()) { "远程身份文件缺失" }
            val value=RemoteIdentity(UUID.randomUUID().toString());write(value);return value
        }
        val j=JSONObject(file.openRead().bufferedReader().use { it.readText() });require(j.getInt("schemaVersion")==1)
        return RemoteIdentity(j.getString("installId").also { UUID.fromString(it) },j.getString("deviceId"),j.getString("token"))
    }
    @Synchronized fun write(value:RemoteIdentity) {
        UUID.fromString(value.installId)
        if(value.deviceId.isNotEmpty()) UUID.fromString(value.deviceId)
        val out=file.startWrite()
        try {
            out.write(JSONObject().put("schemaVersion",1).put("installId",value.installId).put("deviceId",value.deviceId).put("token",value.token).toString().toByteArray())
            file.finishWrite(out);if(!marker.exists()) marker.writeText("1")
        } catch(e:Exception) { file.failWrite(out);throw e }
    }
    @Synchronized fun publicKey():String {
        var store=keys()
        if(!store.containsAlias(alias)) {
            check(read().deviceId.isEmpty()) { "远程身份密钥缺失" }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false).setUnlockedDeviceRequired(false).build())
                generateKeyPair()
            }
            store=keys()
        }
        return Base64.encodeToString(store.getCertificate(alias).publicKey.encoded,Base64.NO_WRAP)
    }
    @Synchronized fun sign(text:String):String {
        val key=keys().getKey(alias,null)
        check(key is java.security.PrivateKey) { "远程身份密钥不可用" }
        return Base64.encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(key);update(text.toByteArray(Charsets.UTF_8));sign() },Base64.NO_WRAP)
    }
}
