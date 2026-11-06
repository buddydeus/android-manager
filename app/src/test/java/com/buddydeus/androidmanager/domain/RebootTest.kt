package com.buddydeus.androidmanager.domain
import org.junit.Assert.*
import org.junit.Test
class RebootTest {
    @Test fun onlyCurrentBootAndChannelWithUnexpiredPermitCanExecute() {
        assertTrue(RebootGuard.allowed("one","one",3,3,100,99,false))
        assertFalse(RebootGuard.allowed("one","two",3,3,100,99,false))
        assertFalse(RebootGuard.allowed("one",null,3,3,100,99,false))
        assertFalse(RebootGuard.allowed("one","one",3,4,100,99,false))
        assertFalse(RebootGuard.allowed("one","one",-1,-1,100,99,false))
        assertFalse(RebootGuard.allowed("one","one",3,3,100,100,false))
        assertFalse(RebootGuard.allowed("one","one",3,3,100,99,true))
    }
}
