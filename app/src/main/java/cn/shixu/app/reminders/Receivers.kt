package cn.shixu.app.reminders

import android.content.*
import androidx.work.*
import cn.shixu.app.ShiXuApp
import cn.shixu.app.widgets.WidgetRenderer
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as ShiXuApp
        app.scope.launch {
            try {
                when(intent.action) {
                    "cn.shixu.COMPLETE" -> app.repository.setState(intent.getStringExtra("taskId") ?: return@launch,intent.getIntExtra("version",-1),"COMPLETE")
                    "cn.shixu.MIDNIGHT" -> app.alarms.reconcile()
                    "cn.shixu.REMINDER" -> app.alarms.fire(intent.getStringExtra("id") ?: return@launch,intent.getStringExtra("occurrence") ?: return@launch,intent.getLongExtra("epoch",0))
                }
                WidgetRenderer.updateAll(context)
            } catch (_: Exception) { RecoveryWorker.enqueue(context) }
            finally { pending.finish() }
        }
    }
}
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_DATE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED,"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        RecoveryWorker.enqueue(context)
        val pending = goAsync()
        val app = context.applicationContext as ShiXuApp
        app.scope.launch { try { app.alarms.reconcile(); WidgetRenderer.updateAll(context) } catch (_: Exception) { RecoveryWorker.enqueue(context) } finally { pending.finish() } }
    }
}
class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as ShiXuApp
        app.alarms.reconcile()
        WidgetRenderer.updateAll(applicationContext)
        Result.success()
    } catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("reminder_recovery",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<RecoveryWorker>().setInitialDelay(3,TimeUnit.SECONDS).build())
        }
        fun ensurePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminder_safety_net",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<RecoveryWorker>(15,TimeUnit.MINUTES).build())
        }
    }
}
