package com.buddydeus.androidmanager

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.data.StateRepository
import com.buddydeus.androidmanager.platform.ManagerAdminReceiver
import com.buddydeus.androidmanager.platform.RemoteApi
import com.buddydeus.androidmanager.platform.RemoteHttpException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID

/** Installs only the explicitly fingerprinted CA on a new emulator; never initializes policy or SAF. */
@RunWith(AndroidJUnit4::class)
class RemoteValidationBootstrapTest {
    @Test fun installExplicitFixtureCaWithoutChangingInitialization() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val args=InstrumentationRegistry.getArguments();val value=args.getString("remoteFixtureId")
        assumeTrue("须显式指定隔离夹具",value!=null && args.getString("remoteFixtureMode")=="installCa")
        val id=UUID.fromString(value).toString();check(BuildConfig.DEBUG && BuildConfig.AMS_SERVER_ORIGIN=="https://10.0.2.2:8443")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val initialized=StateRepository(context.createDeviceProtectedStorageContext().filesDir).read().initialized
        val bytes=File("/data/local/tmp/ams-ca-$id.pem").readBytes()
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(args.getString("remoteFixtureCaSha256"),digest)
        val certificate=CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        assertTrue(certificate.basicConstraints>=0);assertTrue(certificate.subjectX500Principal.name.contains(id));certificate.checkValidity()
        val dpm=context.getSystemService(DevicePolicyManager::class.java);val admin=ComponentName(context,ManagerAdminReceiver::class.java)
        assertTrue(dpm.isDeviceOwnerApp(context.packageName));assertTrue(dpm.installCaCert(admin,bytes));assertTrue(dpm.hasCaCertInstalled(admin,bytes))
        assertEquals(initialized,StateRepository(context.createDeviceProtectedStorageContext().filesDir).read().initialized)
        try { RemoteApi().request("/api/v1/device/time");fail("未提供设备身份应拒绝") }
        catch(e:RemoteHttpException) { assertEquals(401,e.status) }
    }
}
