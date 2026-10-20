package com.buddydeus.androidmanager.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ScheduleEvaluatorTest {
    private fun at(s: String) = Instant.parse(s)
    private fun week(vararg windows: Pair<Int, DayWindow>) = List(7) { d -> windows.toMap()[d] }
    @Test fun crossMidnightIncludesEntireEndMinute() {
        val w = week(0 to DayWindow(720,540))
        assertTrue(ScheduleEvaluator.evaluate(w, at("2026-10-19T04:00:00Z")).active)
        assertTrue(ScheduleEvaluator.evaluate(w, at("2026-10-20T01:00:59.999999999Z")).active)
        assertFalse(ScheduleEvaluator.evaluate(w, at("2026-10-20T01:01:00Z")).active)
    }
    @Test fun sundayExtendsIntoMonday() {
        val w=week(6 to DayWindow(720,540))
        assertTrue(ScheduleEvaluator.evaluate(w,at("2026-10-19T00:00:00Z")).active)
        assertEquals(at("2026-10-19T01:01:00Z"),ScheduleEvaluator.evaluate(w,at("2026-10-19T00:00:00Z")).nextBoundary)
    }
    @Test fun overlappingAndAdjacentIntervalsHaveNoFalseBoundary() {
        val w=week(0 to DayWindow(720,540),1 to DayWindow(480,600))
        assertEquals(at("2026-10-20T02:01:00Z"),ScheduleEvaluator.evaluate(w,at("2026-10-20T00:00:00Z")).nextBoundary)
        val adjacent=week(0 to DayWindow(720,540),1 to DayWindow(541,600))
        assertEquals(at("2026-10-20T02:01:00Z"),ScheduleEvaluator.evaluate(adjacent,at("2026-10-20T00:00:00Z")).nextBoundary)
    }
    @Test fun sameDayIncludesLastSecondAndFractionOfEndMinute() {
        val w=week(0 to DayWindow(540,600))
        assertFalse(ScheduleEvaluator.evaluate(w,at("2026-10-19T00:59:59Z")).active)
        assertTrue(ScheduleEvaluator.evaluate(w,at("2026-10-19T01:00:00Z")).active)
        assertTrue(ScheduleEvaluator.evaluate(w,at("2026-10-19T02:00:59.999999999Z")).active)
        assertEquals(at("2026-10-19T02:01:00Z"),ScheduleEvaluator.evaluate(w,at("2026-10-19T02:00:59Z")).nextBoundary)
        assertFalse(ScheduleEvaluator.evaluate(w,at("2026-10-19T02:01:00Z")).active)
    }
    @Test fun fullDayEndsAtNextMidnight() {
        val w=week(0 to DayWindow(0,1439))
        assertTrue(ScheduleEvaluator.evaluate(w,at("2026-10-18T16:00:00Z")).active)
        assertTrue(ScheduleEvaluator.evaluate(w,at("2026-10-19T15:59:59.999999999Z")).active)
        assertEquals(at("2026-10-19T16:00:00Z"),ScheduleEvaluator.evaluate(w,at("2026-10-19T15:59:59Z")).nextBoundary)
        assertFalse(ScheduleEvaluator.evaluate(w,at("2026-10-19T16:00:00Z")).active)
    }
    @Test fun consecutiveFullDaysMergeAcrossWeekBoundary() {
        val w=week(6 to DayWindow(0,1439),0 to DayWindow(0,1439))
        assertTrue(ScheduleEvaluator.evaluate(w,at("2026-10-18T16:00:00Z")).active)
        assertEquals(at("2026-10-19T16:00:00Z"),ScheduleEvaluator.evaluate(w,at("2026-10-18T15:59:59Z")).nextBoundary)
    }
    @Test fun fullWeekHasNoFalseHorizonAlarm() {
        val w=List(7) { DayWindow(0,1439) }
        for(day in 19..25) {
            val state=ScheduleEvaluator.evaluate(w,at("2026-10-${day}T16:00:00Z"))
            assertTrue(state.active)
            assertNull(state.nextBoundary)
        }
    }
    @Test fun emptyWeekHasNoAlarm() { assertNull(ScheduleEvaluator.evaluate(List(7){null},at("2026-10-20T00:00:00Z")).nextBoundary) }
    @Test(expected=IllegalArgumentException::class) fun equalTimesRejected() { DayWindow(540,540) }
    @Test(expected=IllegalArgumentException::class) fun outOfRangeRejected() { DayWindow(-1,540) }
}
