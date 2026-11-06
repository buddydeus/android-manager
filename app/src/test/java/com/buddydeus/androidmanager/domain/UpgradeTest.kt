package com.buddydeus.androidmanager.domain
import org.junit.Assert.*
import org.junit.Test
class UpgradeTest {
    private val current=ApkIdentity("com.buddydeus.androidmanager",1,30,setOf("signer"))
    private val target=current.copy(versionCode=2)
    private fun reject(t:ApkIdentity=target,a:ApkIdentity=target,c:ApkIdentity=current,api:Int=36,hash:String="hash",size:Long=100)=UpgradeGate.rejection(t,a,c,api,"hash",hash,100,size)
    @Test fun verifiesTargetArchiveAndCurrentSignatureBeforeAnyInstallation() {
        assertNull(reject())
        assertNotNull(reject(a=target.copy(packageName="other.app")))
        assertNotNull(reject(a=target.copy(signers=setOf("other"))))
        assertNotNull(reject(t=target.copy(signers=setOf("other")),a=target.copy(signers=setOf("other"))))
        assertNotNull(reject(c=current.copy(versionCode=2)))
        assertNotNull(reject(api=29));assertNotNull(reject(hash="wrong"));assertNotNull(reject(size=99))
        assertNotNull(reject(a=target.copy(versionCode=3)))
    }
}
