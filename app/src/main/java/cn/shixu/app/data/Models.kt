package cn.shixu.app.data

import androidx.room.*
import java.time.*
import java.util.UUID

enum class RepeatRule(val label: String) { NONE("不重复"), DAILY("每天"), WEEKLY("每周"), MONTHLY("每月"), YEARLY("每年") }

@Entity(tableName = "tasks", indices = [Index("dueDate"), Index("updatedAt")])
data class TaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startDate: String? = null,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val color: Long = 0xFF839B75,
    val category: String = "日常",
    val notes: String = "",
    val repeat: String = "NONE",
    val completedAt: Long? = null,
    val deletedAt: Long? = null,
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val seriesId: String? = null
) {
    val active: Boolean get() = completedAt == null && deletedAt == null
    fun deadline(): LocalDateTime? = dueDate?.let { LocalDate.parse(it).atTime(dueTime?.let(LocalTime::parse) ?: LocalTime.of(23, 59)) }
    fun overdue(now: LocalDateTime = LocalDateTime.now()) = active && deadline()?.isBefore(now) == true
    fun covers(date: LocalDate): Boolean {
        val end = dueDate?.let(LocalDate::parse) ?: startDate?.let(LocalDate::parse) ?: return false
        val start = startDate?.let(LocalDate::parse) ?: end
        return !date.isBefore(start) && !date.isAfter(end)
    }
}

@Entity(tableName = "reminders", foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)], indices = [Index("taskId")])
data class ReminderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val localTime: String,
    val repeat: String = "NONE",
    val relativeMinutes: Long? = null,
    val enabled: Boolean = true,
    val lastFiredLocal: String? = null,
    val scheduledAt: Long? = null,
    val scheduledLocal: String? = null,
    val delivery: String = "PENDING",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val origin: String = "UNKNOWN"
)

@Entity(tableName = "messages", indices = [Index("createdAt")])
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val imagePaths: String = "[]",
    val quoteId: String? = null,
    val proposal: String? = null,
    val state: String = "SENT",
    val createdAt: Long = System.currentTimeMillis(),
    val sharedText: String = ""
)

@Entity(tableName = "message_tasks", primaryKeys = ["messageId", "taskId"],
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")])
data class MessageTask(val messageId: String, val taskId: String)

@Entity(tableName="task_drafts",indices=[Index(value=["targetTaskId"],unique=true)])
data class TaskDraftEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val targetTaskId: String,
    val targetKind: String,
    val baseTaskVersion: Int?,
    val revision: Int,
    val status: String = "OPEN",
    val workingJson: String,
    val sourceMessageId: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName="message_drafts",primaryKeys=["messageId","draftId"],foreignKeys=[
    ForeignKey(entity=MessageEntity::class,parentColumns=["id"],childColumns=["messageId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=TaskDraftEntity::class,parentColumns=["id"],childColumns=["draftId"],onDelete=ForeignKey.CASCADE)
],indices=[Index("draftId")])
data class MessageDraft(val messageId: String,val draftId: String)

@Entity(tableName = "history", indices = [Index("taskId")])
data class HistoryEntity(@PrimaryKey val id: String = UUID.randomUUID().toString(), val taskId: String, val action: String, val before: String?, val after: String?, val sourceMessageId: String?, val at: Long = System.currentTimeMillis())

@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

data class TaskBundle(val task: TaskEntity, val reminders: List<ReminderEntity>)

object Rules {
    fun validate(task: TaskEntity, reminders: List<ReminderEntity>) {
        require(task.title.isNotBlank() && task.title.length <= 200) { "事项标题需为 1–200 字" }
        require(task.notes.length <= 10000 && task.category.length <= 40) { "备注或分类过长" }
        require(task.color in 0xFF000000..0xFFFFFFFF) { "事项颜色必须是不透明 ARGB 颜色" }
        val start = task.startDate?.let(LocalDate::parse)
        val due = task.dueDate?.let(LocalDate::parse)
        task.dueTime?.let { require(due != null) { "设置截止时间前请选择截止日期" }; LocalTime.parse(it) }
        require(start == null || due == null || start <= due) { "开始日期不能晚于截止日期" }
        RepeatRule.valueOf(task.repeat)
        require(task.repeat == "NONE" || due != null) { "重复事项需要截止日期" }
        require(reminders.size <= 20) { "每个事项最多 20 条提醒" }
        reminders.forEach {
            require(it.taskId == task.id) { "提醒关联的事项不正确" }
            LocalDateTime.parse(it.localTime)
            RepeatRule.valueOf(it.repeat)
            require(it.origin in setOf("AUTO","USER","UNKNOWN")) { "提醒来源不正确" }
            require(it.relativeMinutes == null || task.deadline() != null) { "提前提醒需要截止时间" }
            require(it.relativeMinutes == null || it.relativeMinutes >= 0) { "提前时间不能为负数" }
        }
        val times = reminders.filter { it.enabled }.map { it.localTime to it.repeat }
        require(times.distinct().size == times.size) { "存在重复的提醒时间" }
    }

    // Each occurrence is computed from the original anchor, so Jan 31 → Feb 28 → Mar 31.
    fun occurrence(anchor: LocalDateTime, rule: String, index: Long): LocalDateTime = when (RepeatRule.valueOf(rule)) {
        RepeatRule.NONE -> anchor
        RepeatRule.DAILY -> anchor.plusDays(index)
        RepeatRule.WEEKLY -> anchor.plusWeeks(index)
        RepeatRule.MONTHLY -> anchor.plusMonths(index)
        RepeatRule.YEARLY -> anchor.plusYears(index)
    }

    fun nextAfter(anchor: LocalDateTime, rule: String, after: LocalDateTime): LocalDateTime? {
        if (anchor > after) return anchor
        if (rule == "NONE") return null
        val rough = when (RepeatRule.valueOf(rule)) {
            RepeatRule.DAILY -> java.time.temporal.ChronoUnit.DAYS.between(anchor, after)
            RepeatRule.WEEKLY -> java.time.temporal.ChronoUnit.WEEKS.between(anchor, after)
            RepeatRule.MONTHLY -> java.time.temporal.ChronoUnit.MONTHS.between(anchor, after)
            RepeatRule.YEARLY -> java.time.temporal.ChronoUnit.YEARS.between(anchor, after)
            else -> 0
        }.coerceAtLeast(0)
        var index = rough
        while (occurrence(anchor, rule, index) <= after) index++
        return occurrence(anchor, rule, index)
    }

    fun nextReminder(reminder: ReminderEntity, now: LocalDateTime): LocalDateTime? {
        if (!reminder.enabled) return null
        val anchor = LocalDateTime.parse(reminder.localTime)
        val fired = reminder.lastFiredLocal?.let(LocalDateTime::parse)
        reminder.scheduledLocal?.let { planned ->
            val pending = LocalDateTime.parse(planned)
            if (pending >= anchor && (fired == null || pending > fired)) return pending
        }
        if (reminder.repeat == "NONE") return if (fired == null) anchor else null
        // Repeating reminders skip missed occurrences, avoiding a burst on reboot.
        val threshold = maxOf(now.minusSeconds(1), fired ?: LocalDateTime.MIN)
        return nextAfter(anchor, reminder.repeat, threshold)
    }

    fun defaultReminder(task: TaskEntity, now: LocalDateTime = LocalDateTime.now()): ReminderEntity? {
        val due = task.deadline() ?: return null
        val at = due.toLocalDate().minusDays(1).atTime(9, 0)
        if (at <= now) return null
        return ReminderEntity(taskId = task.id, localTime = at.toString(), relativeMinutes = java.time.Duration.between(at, due).toMinutes(),origin="AUTO")
    }
}
