package cn.shixu.app

import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shixu.app.ai.Attachments
import cn.shixu.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class RuntimeIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ShiXuApp
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    @Test fun explicitNoReminderCancelsTheRegisteredSystemAlarm() = runBlocking {
        if(Build.VERSION.SDK_INT>=33) shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        if(Build.VERSION.SDK_INT>=31) shell("appops set ${app.packageName} SCHEDULE_EXACT_ALARM allow")
        val task=TaskEntity(title="取消通知验收")
        val reminder=ReminderEntity(taskId=task.id,localTime=LocalDateTime.now().plusDays(1).withNano(0).toString())
        fun alarmDump()=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys alarm")).use { it.bufferedReader().readText() }
        fun queuedCount()=alarmDump().lineSequence().count { it.trim()=="tag=*walarm*:cn.shixu.REMINDER" }
        val registry=app.getSharedPreferences("scheduled_alarm_ids",android.content.Context.MODE_PRIVATE)
        val before=queuedCount()
        try {
            app.repository.save(TaskBundle(task,listOf(reminder)),null)
            assertNotNull(app.repository.dao.reminder(reminder.id)!!.scheduledAt)
            assertTrue(reminder.id in registry.getStringSet("ids",emptySet()).orEmpty())
            assertEquals(before+1,queuedCount())
            val anchor=MessageEntity(role="assistant",text=task.title);app.repository.putMessage(anchor);app.repository.dao.putLink(MessageTask(anchor.id,task.id))
            val source=MessageEntity(role="user",text="只做待办，不用通知",quoteId=anchor.id);app.repository.putMessage(source)
            val raw=org.json.JSONObject().put("protocolVersion",2).put("operations",org.json.JSONArray().put(org.json.JSONObject().put("target","T1").put("action","PATCH").put("reminderPolicy","NONE")))
            val answer=app.repository.acceptAiResult(raw,source.id,app.repository.conversationContext(source),"")
            app.repository.applyProposal(answer.id)
            assertTrue(app.repository.dao.remindersFor(task.id).isEmpty())
            assertFalse(reminder.id in registry.getStringSet("ids",emptySet()).orEmpty())
            assertEquals(before,queuedCount())
            assertTrue(app.repository.dao.task(task.id)!!.active)
        } finally { app.repository.dao.task(task.id)?.let { app.repository.setState(it.id,it.version,"DELETE") };app.alarms.cancel(reminder.id) }
    }
    @Test fun systemAlarmActuallyDeliversThroughBroadcastReceiver() = runBlocking {
        if(Build.VERSION.SDK_INT>=33) shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        if(Build.VERSION.SDK_INT>=31) shell("appops set ${app.packageName} SCHEDULE_EXACT_ALARM allow")
        assertTrue(app.alarms.notificationsAllowed())
        assertTrue(app.alarms.exactAllowed())
        val task=TaskEntity(title="系统到点提醒验收")
        val reminder=ReminderEntity(taskId=task.id,localTime=LocalDateTime.now().plusSeconds(5).withNano(0).toString())
        try {
            app.repository.save(TaskBundle(task,listOf(reminder)),null)
            // No manual fire() call: AlarmManager must send the registered PendingIntent.
            withTimeout(25000) {
                while(app.repository.dao.reminder(reminder.id)?.delivery!="DELIVERED") delay(150)
            }
            assertEquals(reminder.localTime,app.repository.dao.reminder(reminder.id)!!.lastFiredLocal)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag==reminder.id })
        } finally {
            app.repository.dao.task(task.id)?.let { app.repository.setState(it.id,it.version,"DELETE") }
            app.alarms.cancel(reminder.id)
        }
    }
    @Test fun bundledChineseOcrReadsImportedAnnouncementImage() = runBlocking {
        val source=File(app.cacheDir,"ocr-announcement.png")
        val bitmap=Bitmap.createBitmap(1200,400,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=64f }
        canvas.drawText("流体输配管网作业",50f,130f,paint)
        canvas.drawText("11月6日交",50f,260f,paint)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        var imported: String?=null
        try {
            val name=Attachments.import(app,Uri.fromFile(source));imported=name
            val text=withTimeout(45000) { Attachments.ocr(app,listOf(name)) }.replace(" ","")
            assertTrue("Chinese text was not recognized: $text",text.contains("作业"))
            assertTrue("Date was not recognized: $text",text.contains("11") && text.contains("6"))
        } finally { source.delete();imported?.let { Attachments.file(app,it).delete() } }
    }
}
