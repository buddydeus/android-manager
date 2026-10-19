package com.buddydeus.androidmanager.application
import com.buddydeus.androidmanager.data.*
import com.buddydeus.androidmanager.domain.*
import org.junit.Assert.*
import org.junit.Test

class AuthControllerTest {
    private class Meta : AuthMetadataStore { var value=AuthMetadata();override fun read()=value;override fun write(value:AuthMetadata){this.value=value} }
    private class Passwords : PasswordAccess {
        var content:String?=null
        override fun inspect()=content?.let { PasswordFileState.Ready(it,PasswordCodec.fingerprint(it)) } ?: PasswordFileState.Missing
        override fun writePin(pin:CharArray):String { content=PasswordCodec.create(pin);return PasswordCodec.fingerprint(content!!) }
        override fun location()="test"
    }
    @Test fun defaultLoginCannotGrantManagementUntilPinChanged() {
        val p=Passwords();val auth=AuthController(p,Meta(),AuditSink{}, {0},{0},{1})
        assertEquals(AuthResult.NeedsNewPin,auth.login("0000".toCharArray()));assertFalse(auth.requireSession())
        assertEquals(AuthResult.Authenticated,auth.setNewPin("123456".toCharArray()));assertTrue(auth.requireSession())
        p.content=null;assertFalse(auth.requireSession())
    }
    @Test fun fiveFailuresPauseSurvivesControllerRecreation() {
        val p=Passwords();val m=Meta();var time=0L
        var a=AuthController(p,m,AuditSink{}, {time},{time},{1})
        repeat(5) { assertTrue(a.login("9999".toCharArray()) is AuthResult.Denied) }
        a=AuthController(p,m,AuditSink{}, {time},{time},{1})
        assertTrue(a.login("0000".toCharArray()) is AuthResult.Denied)
        time=300000;assertEquals(AuthResult.NeedsNewPin,a.login("0000".toCharArray()))
    }
    @Test fun expiredPauseStartsANewFailureWindow() {
        val p=Passwords();val m=Meta();var time=0L
        val a=AuthController(p,m,AuditSink{}, {time},{time},{1})
        repeat(5) { a.login("9999".toCharArray()) }
        time=300000
        a.login("9999".toCharArray())
        assertEquals(0L,a.pausedMillis())
    }
    @Test fun storageFailureIsAuditedWithoutCredentialContents() {
        val events=mutableListOf<AuditEvent>()
        val p=object:PasswordAccess {
            override fun inspect()=PasswordFileState.Broken("ACCESS_DENIED","storage denied")
            override fun writePin(pin:CharArray)=""
            override fun location()="test"
        }
        val a=AuthController(p,Meta(),AuditSink { events+=it }, {0},{0},{1})
        a.login("123456".toCharArray())
        assertTrue(events.any { it.category==LogCategory.PERMISSION_ERROR })
        assertFalse(events.any { it.result.contains("123456") })
    }
    @Test fun leavingDuringPasswordVerificationCannotReauthenticate() {
        val m=Meta();lateinit var a:AuthController
        val p=object:PasswordAccess {
            override fun inspect():PasswordFileState { a.invalidate();return PasswordFileState.Missing }
            override fun writePin(pin:CharArray)=""
            override fun location()="test"
        }
        a=AuthController(p,m,AuditSink{}, {0},{0},{1})
        assertTrue(a.login("0000".toCharArray()) is AuthResult.Denied)
        assertFalse(a.requireSession())
    }
}
