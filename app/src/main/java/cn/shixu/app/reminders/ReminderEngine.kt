package cn.shixu.app.reminders

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.shixu.app.MainActivity
import cn.shixu.app.R
import cn.shixu.app.data.*
import kotlinx.coroutines.sync.withLock
import java.time.*

class ReminderEngine(private val context: Context, private val repo: Repository) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val registry = context.getSharedPreferences("scheduled_alarm_ids",Context.MODE_PRIVATE)
    fun exactAllowed() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"事项提醒",NotificationManager.IMPORTANCE_HIGH).apply { description = "到点提醒你安排好的事项" })
    }
    private fun pending(id: String, occurrence: String = "", epoch: Long = 0L): PendingIntent = PendingIntent.getBroadcast(context,0,
        Intent(context,AlarmReceiver::class.java).setAction("cn.shixu.REMINDER").setData(Uri.parse("shixu://reminder/$id"))
            .putExtra("id",id).putExtra("occurrence",occurrence).putExtra("epoch",epoch),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(id: String) { manager.cancel(pending(id)); NotificationManagerCompat.from(context).cancel(id,0) }

    suspend fun reconcile() = repo.gate.withLock { reconcileLocked() }
    private suspend fun reconcileLocked() {
        val now = LocalDateTime.now()
        val tasks = repo.dao.tasks().associateBy { it.id }
        val reminders = repo.dao.reminders()
        val currentIds = reminders.map { it.id }.toSet()
        (registry.getStringSet("ids",emptySet()).orEmpty() - currentIds).forEach(::cancel)
        // Persist the cancel registry first: interrupted reconciliation is safely repeatable.
        check(registry.edit().putStringSet("ids",currentIds).commit()) { "系统提醒登记失败" }
        reminders.forEach { r ->
            val t = tasks[r.taskId]
            if (t?.active != true || !r.enabled) {
                if (t?.active != true) cancel(r.id) else manager.cancel(pending(r.id))
                if(r.scheduledAt != null) repo.dao.putReminder(r.copy(scheduledAt=null,scheduledLocal=null))
                return@forEach
            }
            val occurrence = Rules.nextReminder(r,now)
            if (occurrence == null) { manager.cancel(pending(r.id)); return@forEach }
            if (!notificationsAllowed()) {
                manager.cancel(pending(r.id))
                repo.dao.putReminder(r.copy(scheduledAt=null,delivery="BLOCKED"))
                return@forEach
            }
            // A missed one-off reminder is caught up once; repeating alarms skip to the next occurrence.
            val epoch = occurrence.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val triggerAt = maxOf(epoch,System.currentTimeMillis()+1500)
            val pi = pending(r.id,occurrence.toString(),epoch)
            var mode = "EXACT"
            try {
                if (exactAllowed()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,triggerAt,pi)
                else { mode="APPROXIMATE"; manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,triggerAt,pi) }
            } catch (_: SecurityException) { mode="APPROXIMATE"; manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,triggerAt,pi) }
            repo.dao.putReminder(r.copy(scheduledAt=epoch,scheduledLocal=occurrence.toString(),delivery=mode))
        }
        scheduleMidnight()
    }
    private fun scheduleMidnight() {
        val epoch = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(context,0,Intent(context,AlarmReceiver::class.java).setAction("cn.shixu.MIDNIGHT"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,epoch,pi)
    }
    suspend fun fire(id: String, occurrence: String, expectedEpoch: Long) = repo.gate.withLock {
        val r = repo.dao.reminder(id) ?: return@withLock
        val t = repo.dao.task(r.taskId) ?: return@withLock
        if (!t.active || !r.enabled || r.scheduledAt != expectedEpoch || r.scheduledLocal != occurrence || r.lastFiredLocal == occurrence) return@withLock
        if (expectedEpoch > System.currentTimeMillis()+1000) return@withLock
        if (!notificationsAllowed()) { repo.dao.putReminder(r.copy(delivery="BLOCKED",scheduledAt=null)); return@withLock }
        val p = Preferences.from(repo.dao.settings())
        val open = PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).setData(Uri.parse("shixu://task/${t.id}")).putExtra("taskId",t.id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val finish = PendingIntent.getBroadcast(context,0,Intent(context,AlarmReceiver::class.java).setAction("cn.shixu.COMPLETE").setData(Uri.parse("shixu://complete/${t.id}/${t.version}")).putExtra("taskId",t.id).putExtra("version",t.version),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = Personality.notification(p,t)
        val notification = NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(t.title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH).addAction(0,"已完成",finish).build()
        try { NotificationManagerCompat.from(context).notify(r.id,0,notification) }
        catch (_: SecurityException) { repo.dao.putReminder(r.copy(delivery="BLOCKED",scheduledAt=null)); return@withLock }
        repo.dao.putReminder(r.copy(lastFiredLocal=occurrence,scheduledAt=null,scheduledLocal=null,delivery="DELIVERED",enabled=r.repeat != "NONE"))
        reconcileLocked()
    }
    companion object { const val CHANNEL = "shixu_reminders_v1" }
}
