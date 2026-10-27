package cn.shixu.app.data

import java.time.LocalDate
import java.time.YearMonth

data class CalendarBar(val task: TaskEntity, val start: Int, val end: Int, val lane: Int, val continuesBefore: Boolean, val continuesAfter: Boolean)
data class CalendarWeek(val start: LocalDate, val bars: List<CalendarBar>) {
    val lanes: Int get() = (bars.maxOfOrNull { it.lane } ?: -1)+1
}
object CalendarLayout {
    fun weeks(month: YearMonth, tasks: List<TaskEntity>): List<CalendarWeek> {
        val first = month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value-1).toLong())
        val count = ((month.lengthOfMonth() + month.atDay(1).dayOfWeek.value - 2)/7)+1
        return (0 until count).map { week ->
            val start = first.plusWeeks(week.toLong())
            val end = start.plusDays(6)
            val occupants = mutableListOf<Int>()
            val bars = tasks.filter { it.active }.mapNotNull { t ->
                val until = t.dueDate?.let(LocalDate::parse) ?: t.startDate?.let(LocalDate::parse) ?: return@mapNotNull null
                val from = t.startDate?.let(LocalDate::parse) ?: until
                if(until < start || from > end) null else Triple(t,from,until)
            }.sortedWith(compareBy<Triple<TaskEntity,LocalDate,LocalDate>> { maxOf(it.second,start) }.thenByDescending { java.time.temporal.ChronoUnit.DAYS.between(it.second,it.third) }.thenBy { it.first.id }).map { (task,from,until) ->
                val a = java.time.temporal.ChronoUnit.DAYS.between(start,maxOf(from,start)).toInt()
                val b = java.time.temporal.ChronoUnit.DAYS.between(start,minOf(until,end)).toInt()
                val lane = occupants.indexOfFirst { it < a }.let { if(it < 0) { occupants.add(b); occupants.lastIndex } else { occupants[it]=b; it } }
                CalendarBar(task,a,b,lane,(from < start),(until > end))
            }
            CalendarWeek(start,bars)
        }
    }
}
