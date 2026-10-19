package com.buddydeus.androidmanager

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buddydeus.androidmanager.application.ManagerGraph
import com.buddydeus.androidmanager.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import android.os.SystemClock

@RunWith(AndroidJUnit4::class)
class ManagementGateTest {
    private fun texts(node:android.view.accessibility.AccessibilityNodeInfo?):List<String> {
        if(node==null) return emptyList()
        return listOfNotNull(node.text?.toString())+(0 until node.childCount).flatMap { texts(node.getChild(it)) }
    }
    @Test fun uninitializedLaunchDoesNotExposeManagementControls() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val graph=ManagerGraph.get(instrumentation.targetContext)
        assumeFalse(graph.coordinator.refresh().initialized)
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val deadline=SystemClock.elapsedRealtime()+5000
            var found=false
            while(SystemClock.elapsedRealtime()<deadline && !found) {
                val root=instrumentation.uiAutomation.rootInActiveWindow
                found=texts(root).any { it.contains("首次初始化") || it.contains("选择密码目录") }
                if(!found) SystemClock.sleep(100)
            }
            assertTrue("Expected initialization gate",found)
            val root=instrumentation.uiAutomation.rootInActiveWindow
            assertFalse(texts(root).any { it.contains("管控总开关") })
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
