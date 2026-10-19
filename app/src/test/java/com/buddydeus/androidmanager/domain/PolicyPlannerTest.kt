package com.buddydeus.androidmanager.domain
import org.junit.Assert.*
import org.junit.Test

class PolicyPlannerTest {
    @Test fun initializationAndMasterGateEveryNewRestriction() {
        val apps=mapOf("a.b" to AppEntry("a.b","App"))
        assertFalse(PolicyPlanner.plan(ManagerState(blacklist=setOf("a.b")),apps,true).targets.getValue("a.b"))
        assertFalse(PolicyPlanner.plan(ManagerState(initialized=true,enabled=false,blacklist=setOf("a.b")),apps,true).targets.getValue("a.b"))
        assertTrue(PolicyPlanner.plan(ManagerState(initialized=true,blacklist=setOf("a.b")),apps,true).targets.getValue("a.b"))
    }
    @Test fun protectionOverridesBlacklistButKeepsCleanupTargets() {
        val apps=mapOf("a.b" to AppEntry("a.b","Home",protection="桌面"))
        val s=ManagerState(initialized=true,blacklist=setOf("a.b"),records=mapOf("old.pkg" to PolicyRecord(ownHidden=true)))
        val plan=PolicyPlanner.plan(s,apps,true)
        assertFalse(plan.targets.getValue("a.b"));assertFalse(plan.targets.getValue("old.pkg"))
    }
}
