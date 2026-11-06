package cn.shixu.app.data

/** Stable, local color selection. Explicit user colors are never rewritten. */
object TaskPalette {
    val colors=listOf(0xFFBE3A2B,0xFF286381,0xFFB47B20,0xFF38756A,0xFF795A86,0xFFC45F35,0xFF526078,0xFF856447)
    fun next(tasks: List<TaskEntity>, candidate: TaskEntity): Long {
        val active=tasks.filter { it.active && it.id!=candidate.id }
        fun overlaps(t: TaskEntity): Boolean {
            val end=candidate.dueDate ?: candidate.startDate ?: return false
            val start=candidate.startDate ?: end
            val otherEnd=t.dueDate ?: t.startDate ?: return false
            return start<=otherEnd && (t.startDate ?: otherEnd)<=end
        }
        return colors.minWith(compareBy<Long> { c -> active.count { it.color==c && overlaps(it) } }
            .thenBy { c -> active.count { it.color==c } }.thenBy { colors.indexOf(it) })
    }
}
