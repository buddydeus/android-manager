package com.buddydeus.androidmanager.platform

import com.buddydeus.androidmanager.BuildConfig
import com.buddydeus.androidmanager.data.RemoteIdentity
import com.buddydeus.androidmanager.data.RemoteIdentityRepository
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RemoteHttpException(val status:Int):Exception("远程请求失败")
class RemoteApi {
    val origin=BuildConfig.AMS_SERVER_ORIGIN
    private val base=origin.toHttpUrl().also { require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.encodedPath=="/") }
    val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    fun url(path:String):HttpUrl {
        require(path.startsWith("/api/v1/"))
        return base.newBuilder().encodedPath(path).build()
    }
    fun request(path:String,token:String="",body:JSONObject?=null,enrollment:Boolean=false):JSONObject {
        val builder=Request.Builder().url(url(path))
        if(token.isNotEmpty()) builder.header("Authorization","Bearer $token")
        if(enrollment) { check(BuildConfig.AMS_ENROLLMENT_KEY.length>=32) { "未配置远程接入凭据" };builder.header("x-enrollment-key",BuildConfig.AMS_ENROLLMENT_KEY) }
        if(body!=null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            if(!response.isSuccessful) throw RemoteHttpException(response.code)
            val source=response.body.source();source.request(1024L*1024+1)
            check(source.buffer.size<=1024L*1024) { "服务器响应超过限制" }
            val json=JSONObject(source.readUtf8());return json.getJSONObject("data")
        }
    }
    fun register(repository:RemoteIdentityRepository):RemoteIdentity {
        val identity=repository.read();val publicKey=repository.publicKey()
        val request=JSONObject().put("installId",identity.installId).put("publicKey",publicKey)
        val challenge=request("/api/v1/device/challenges",body=request,enrollment=true)
        val id=challenge.getString("challengeId");val nonce=challenge.getString("nonce")
        val signature=repository.sign("$id\n$nonce\n${identity.installId}\n$publicKey")
        request.put("challengeId",id).put("signature",signature).put("model",android.os.Build.MODEL).put("apiLevel",android.os.Build.VERSION.SDK_INT)
            .put("versionCode",BuildConfig.VERSION_CODE).put("versionName",BuildConfig.VERSION_NAME)
        val response=request("/api/v1/device/register",body=request,enrollment=true)
        return identity.copy(deviceId=response.getString("deviceId"),token=response.getString("token")).also(repository::write)
    }
    fun channel(identity:RemoteIdentity,listener:WebSocketListener):WebSocket = client.newWebSocket(Request.Builder().url(url("/api/v1/device/channel")).header("Authorization","Bearer ${identity.token}").build(),listener)
}
