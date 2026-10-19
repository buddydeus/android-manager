package com.buddydeus.androidmanager.domain

import java.time.Instant
import java.time.ZoneId

object ScheduleEvaluator {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    fun evaluate(schedule: List<DayWindow?>, now: Instant): ScheduleState {
        require(schedule.size == 7)
        val today = now.atZone(zone).toLocalDate()
        val ranges = (-1L..7L).mapNotNull { offset ->
            val day = today.plusDays(offset)
            schedule[day.dayOfWeek.value - 1]?.let { w ->
                val start = day.atStartOfDay(zone).plusMinutes(w.startMinute.toLong()).toInstant()
                val endDay = if (w.endMinute < w.startMinute) day.plusDays(1) else day
                start to endDay.atStartOfDay(zone).plusMinutes(w.endMinute.toLong()).toInstant()
            }
        }.sortedBy { it.first }
        val merged = mutableListOf<Pair<Instant, Instant>>()
        for (r in ranges) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, r.second)
            else merged += r
        }
        return ScheduleState(merged.any { now >= it.first && now < it.second },
            merged.flatMap { listOf(it.first, it.second) }.filter { it > now }.minOrNull())
    }
}
