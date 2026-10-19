package com.buddydeus.androidmanager.data

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed class PasswordFileState {
    data class Ready(val content: String, val fingerprint: String) : PasswordFileState()
    data object Missing : PasswordFileState()
    data class Broken(val kind: String, val reason: String) : PasswordFileState()
}
interface PasswordAccess {
    fun inspect(): PasswordFileState
    fun writePin(pin: CharArray): String
    fun location(): String
    fun needsDirectoryCommit(): Boolean = false
}
object PasswordCodec {
    fun fingerprint(text: String): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    fun validPin(pin: CharArray): Boolean = pin.size>=6 && pin.all { it in '0'..'9' }
    private fun derive(pin: CharArray,salt: ByteArray): ByteArray {
        val spec=PBEKeySpec(pin,salt,600000,256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun create(pin: CharArray): String {
        require(validPin(pin)) { "密码须为至少6位数字" }
        val salt=ByteArray(16).also { SecureRandom().nextBytes(it) }
        return JSONObject().put("schemaVersion",1).put("kdf","PBKDF2WithHmacSHA256").put("iterations",600000)
            .put("salt",Base64.getEncoder().encodeToString(salt)).put("verifier",Base64.getEncoder().encodeToString(derive(pin,salt))).toString()
    }
    fun validate(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size<=8192)
        val j=JSONObject(text)
        require(j.getInt("schemaVersion")==1 && j.getString("kdf")=="PBKDF2WithHmacSHA256" && j.getInt("iterations")==600000)
        require(j.getString("salt").length<=32 && j.getString("verifier").length<=64)
        require(Base64.getDecoder().decode(j.getString("salt")).size==16 && Base64.getDecoder().decode(j.getString("verifier")).size==32)
        return j
    }
    fun verify(text: String,pin: CharArray): Boolean {
        val j=validate(text)
        return MessageDigest.isEqual(derive(pin,Base64.getDecoder().decode(j.getString("salt"))),Base64.getDecoder().decode(j.getString("verifier")))
    }
}
