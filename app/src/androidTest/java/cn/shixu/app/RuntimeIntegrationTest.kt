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
