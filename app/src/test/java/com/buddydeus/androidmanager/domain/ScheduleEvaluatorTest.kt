package com.buddydeus.androidmanager.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ScheduleEvaluatorTest {
    private fun at(s: String) = Instant.parse(s)
    private fun week(vararg windows: Pair<Int, DayWindow>) = List(7) { d -> windows.toMap()[d] }
    @Test fun crossMidnightIncludesStartExcludesEnd() {
        val w = week(0 to DayWindow(720,540))
        assertTrue(ScheduleEvaluator.evaluate(w, at("2026-10-19T04:00:00Z")).active)
        assertTrue(ScheduleEvaluator.evaluate(w, at("2026-10-20T00:59:00Z")).active)
        assertFalse(ScheduleEvaluator.evaluate(w, at("2026-10-20T01:00:00Z")).active)
    }
    @Test fun sundayExtendsIntoMonday() {
        val w=week(6 to DayWindow(720,540))
        assertTrue(ScheduleEvaluator.evaluate(w,at("2026-10-19T00:00:00Z")).active)
        assertEquals(at("2026-10-19T01:00:00Z"),ScheduleEvaluator.evaluate(w,at("2026-10-19T00:00:00Z")).nextBoundary)
    }
    @Test fun overlappingAndAdjacentIntervalsHaveNoFalseBoundary() {
        val w=week(0 to DayWindow(720,540),1 to DayWindow(480,600))
        assertEquals(at("2026-10-20T02:00:00Z"),ScheduleEvaluator.evaluate(w,at("2026-10-20T00:00:00Z")).nextBoundary)
        val adjacent=week(0 to DayWindow(720,540),1 to DayWindow(540,600))
        assertEquals(at("2026-10-20T02:00:00Z"),ScheduleEvaluator.evaluate(adjacent,at("2026-10-20T00:00:00Z")).nextBoundary)
    }
    @Test fun emptyWeekHasNoAlarm() { assertNull(ScheduleEvaluator.evaluate(List(7){null},at("2026-10-20T00:00:00Z")).nextBoundary) }
    @Test(expected=IllegalArgumentException::class) fun equalTimesRejected() { DayWindow(540,540) }
    @Test(expected=IllegalArgumentException::class) fun outOfRangeRejected() { DayWindow(-1,540) }
}
