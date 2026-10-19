package com.buddydeus.androidmanager.domain
import com.buddydeus.androidmanager.data.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.*

class StorageTest {
    @Test fun stateRoundtripPreservesRulesAndOwnedPolicies() {
        val s=ManagerState(initialized=true,blacklist=setOf("a.b"), records=mapOf("a.b" to PolicyRecord(ownHidden=true,pending="SUSPEND",result="UNKNOWN",unknownHidden=true,unknownSuspended=true)))
        assertEquals(s,StateCodec.decode(StateCodec.encode(s)))
    }
    @Test(expected=Exception::class) fun corruptStateIsNotEmptyDefaults() { StateCodec.decode("{bad") }
    @Test(expected=Exception::class) fun unknownSchemaRejected() { StateCodec.decode(StateCodec.encode(ManagerState()).replace("\"schemaVersion\":1","\"schemaVersion\":99")) }
    @Test fun retentionPreservesExactlyNinetyDaysAndInvalidNames() {
        val dir=Files.createTempDirectory("audit").toFile()
        try {
            listOf("20260720.log","20260721.log","nonsense.log").forEach { dir.resolve(it).writeText("x") }
            val audit=AuditRepository(dir)
            audit.cleanup(LocalDate.of(2026,10,19))
            assertFalse(dir.resolve("20260720.log").exists())
            assertTrue(dir.resolve("20260721.log").exists())
            assertTrue(dir.resolve("nonsense.log").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun appendAfterPartialLinePreservesNewRecordAndFiltersCategory() {
        val dir=Files.createTempDirectory("audit").toFile()
        try {
            dir.resolve("20261019.log").writeText("{broken")
            val audit=AuditRepository(dir)
            audit.append(AuditEvent(LogCategory.CONFIG_CHANGE,"switch","ok",at=Instant.parse("2026-10-19T01:00:00Z")))
            audit.append(AuditEvent(LogCategory.LOGIN_SECURITY,"login","failed",at=Instant.parse("2026-10-19T01:01:00Z")))
            assertEquals(1,audit.readPage(LocalDate.of(2026,10,19),LogCategory.CONFIG_CHANGE,0,20).size)
            assertTrue(audit.lastReadWarning.isNotEmpty())
        } finally { dir.deleteRecursively() }
    }
}
