package cn.shixu.app

import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shixu.app.data.*
import cn.shixu.app.reminders.ReminderEngine
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class ReminderIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private lateinit var db: PlannerDatabase
    private lateinit var repo: Repository
    private lateinit var engine: ReminderEngine
    @Before fun before() {
        if(Build.VERSION.SDK_INT>=33) ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        db=Room.inMemoryDatabaseBuilder(context,PlannerDatabase::class.java).build();repo=Repository(db);engine=ReminderEngine(context,repo);engine.createChannel()
    }
    @After fun after() = runBlocking { repo.dao.reminders().forEach { engine.cancel(it.id) };db.close() }
    @Test fun coldStartReconcileDoesNotLosePendingNotification() = runBlocking {
        val task=TaskEntity(title="提醒冷启动验收")
        val r=ReminderEntity(taskId=task.id,localTime=LocalDateTime.now().minusMinutes(1).withNano(0).toString())
        repo.save(TaskBundle(task,listOf(r)),null)
        engine.reconcile()
        val first=repo.dao.reminder(r.id)!!
        engine.reconcile()
        val second=repo.dao.reminder(r.id)!!
        assertEquals(first.scheduledAt,second.scheduledAt)
        assertEquals(first.scheduledLocal,second.scheduledLocal)
        engine.fire(r.id,first.scheduledLocal!!,first.scheduledAt!!)
        assertEquals("DELIVERED",repo.dao.reminder(r.id)!!.delivery)
        val manager=context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.activeNotifications.any { it.tag==r.id })
        engine.fire(r.id,first.scheduledLocal,first.scheduledAt)
        assertEquals(1,manager.activeNotifications.count { it.tag==r.id })
        repo.setState(task.id,1,"COMPLETE");engine.reconcile()
        assertFalse(manager.activeNotifications.any { it.tag==r.id })
    }
    @Test fun staleOrDeletedReminderCannotNotify() = runBlocking {
        val task=TaskEntity(title="取消提醒验收")
        val r=ReminderEntity(taskId=task.id,localTime=LocalDateTime.now().minusMinutes(1).withNano(0).toString())
        repo.save(TaskBundle(task,listOf(r)),null);engine.reconcile()
        val scheduled=repo.dao.reminder(r.id)!!
        repo.save(TaskBundle(task.copy(title="已修改",version=1),listOf(r.copy(localTime=LocalDateTime.now().plusDays(1).withNano(0).toString()))),1)
        engine.reconcile()
        engine.fire(r.id,scheduled.scheduledLocal!!,scheduled.scheduledAt!!)
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag==r.id })
        repo.setState(task.id,2,"DELETE");engine.reconcile()
        assertTrue(repo.dao.remindersFor(task.id).none { it.enabled })
    }
}
