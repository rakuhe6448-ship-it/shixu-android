package cn.shixu.app

import android.app.Application
import androidx.room.Room
import cn.shixu.app.data.*
import cn.shixu.app.reminders.*
import cn.shixu.app.widgets.WidgetRenderer
import kotlinx.coroutines.*

open class ShiXuApp : Application() {
    protected open val databaseName = "shixu.db"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var db: PlannerDatabase
    lateinit var repository: Repository
    lateinit var alarms: ReminderEngine
    lateinit var vault: KeyVault
    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this,PlannerDatabase::class.java,databaseName).addMigrations(PlannerDatabase.MIGRATION_1_2).build()
        repository = Repository(db)
        vault = KeyVault(this)
        alarms = ReminderEngine(this,repository)
        alarms.createChannel()
        RecoveryWorker.ensurePeriodic(this)
        repository.afterChange = {
            RecoveryWorker.enqueue(this)
            try { alarms.reconcile() } catch (_: Exception) { throw IllegalStateException("数据已保存，但系统提醒同步失败。请打开提醒页检查权限，系统会再次尝试恢复。") }
            try { WidgetRenderer.updateAll(this) } catch (_: Exception) { throw IllegalStateException("事项和提醒已保存，但桌面组件暂未刷新；系统会再次尝试。") }
        }
        scope.launch { runCatching { alarms.reconcile(); WidgetRenderer.updateAll(this@ShiXuApp) } }
    }
}
