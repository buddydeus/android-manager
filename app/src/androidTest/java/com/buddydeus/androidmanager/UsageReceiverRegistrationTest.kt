package com.buddydeus.androidmanager

import android.content.*
import android.os.Build
import android.os.Handler
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.data.UsageRepository
import com.buddydeus.androidmanager.platform.UsageTracker
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import kotlin.coroutines.CoroutineContext

class UsageReceiverRegistrationTest {
    @Test fun protectedUsageActionsAcceptPrivilegedSystemComponentsOutsideSystemUid() {
        assumeTrue(Build.VERSION.SDK_INT>=33)
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val registrations=mutableListOf<Pair<Int,Set<String>>>()
        val context=object:ContextWrapper(target) {
            private fun capture(filter:IntentFilter,flags:Int):Intent? {
                registrations+=flags to (0 until filter.countActions()).map { filter.getAction(it) }.toSet()
                return null
            }
            override fun registerReceiver(receiver:BroadcastReceiver?,filter:IntentFilter?,flags:Int)=capture(checkNotNull(filter),flags)
            override fun registerReceiver(receiver:BroadcastReceiver?,filter:IntentFilter?,permission:String?,scheduler:Handler?,flags:Int)=capture(checkNotNull(filter),flags)
            override fun unregisterReceiver(receiver:BroadcastReceiver?) {}
        }
        val name="receiver-fixture-${UUID.randomUUID()}.db"
        val store=UsageRepository(context,name)
        val queue=ArrayDeque<Runnable>()
        val dispatcher=object:CoroutineDispatcher() { override fun dispatch(context:CoroutineContext,block:Runnable) { queue.addLast(block) } }
        val tracker=UsageTracker(context,store,{1},{false},{},{error(it)},dispatcher)
        try {
            tracker.start();assertEquals(1,registrations.size)
            assertEquals("解锁可能由非system UID的SystemUI发送",ContextCompat.RECEIVER_EXPORTED,registrations.single().first)
            assertEquals(setOf(Intent.ACTION_SCREEN_ON,Intent.ACTION_SCREEN_OFF,Intent.ACTION_USER_PRESENT,Intent.ACTION_USER_UNLOCKED),registrations.single().second)
        } finally { tracker.stop();while(queue.isNotEmpty()) queue.removeFirst().run();store.close();target.createDeviceProtectedStorageContext().deleteDatabase(name) }
    }
}
