package cn.shixu.app

import android.appwidget.*
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shixu.app.data.*
import cn.shixu.app.widgets.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class WidgetIntegrationTest {
    @Test fun allThreeProvidersBindAndInflateRealRemoteViews() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ShiXuApp
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${app.packageName} --user 0")).use { it.readBytes() }
        val manager=AppWidgetManager.getInstance(app)
        val host=AppWidgetHost(app,2048)
        val today=LocalDate.now()
        val tasks=listOf(TaskEntity(title="课程作业",startDate=today.minusDays(3).toString(),dueDate=today.plusDays(3).toString()),TaskEntity(title="今日组会",dueDate=today.toString(),color=0xFFD0BC73),TaskEntity(title="读书计划",startDate=today.minusDays(6).toString(),dueDate=today.plusDays(7).toString(),color=0xFF94B8CE))
        val classes=listOf(TodayWidget::class.java,UpcomingWidget::class.java,MonthWidget::class.java)
        val reminders=listOf(ReminderEntity(taskId=tasks.first().id,localTime=LocalDateTime.now().plusHours(1).withSecond(0).withNano(0).toString()))
        classes.forEachIndexed { size,provider ->
          (if(size==2) listOf(140 to 260,160 to 520,220 to 620,320 to 340,320 to 400,480 to 320) else listOf((if(size==0) 160 else 320) to 110,(if(size==0) 160 else 320) to 180)).forEach { (widgetWidth,widgetHeight) ->
            val id=host.allocateAppWidgetId()
            val options=Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,widgetWidth);putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,widgetHeight) }
            try {
                assertTrue(manager.bindAppWidgetIdIfAllowed(id,ComponentName(app,provider),options))
                val info=manager.getAppWidgetInfo(id)
                assertNotNull(info)
                if(size==2) options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,arrayListOf(android.util.SizeF(widgetWidth.toFloat(),widgetHeight.toFloat())))
                val views=WidgetRenderer.responsive(app,options,size,tasks,reminders,Preferences())
                manager.updateAppWidget(id,views)
                instrumentation.runOnMainSync {
                    val view=host.createView(app,id,info)
                    view.updateAppWidget(views)
                    val density=app.resources.displayMetrics.density
                    val width=(widgetWidth*density).toInt();val height=(widgetHeight*density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
                    assertTrue(view.findViewById<TextView>(R.id.widget_title).text.isNotBlank())
                    val footer=view.findViewById<TextView>(R.id.widget_footer)
                    val footerBounds=Rect();footer.getDrawingRect(footerBounds);view.offsetDescendantRectToMyCoords(footer,footerBounds)
                    assertTrue("Footer clipped: size=$size width=$widgetWidth height=$widgetHeight bounds=$footerBounds",footerBounds.bottom<=height-view.paddingBottom)
                    if(size==2) {
                        val weekday=view.findViewById<TextView>(R.id.weekday1)
                        assertTrue("Weekday too wide",weekday.paint.measureText(weekday.text.toString())<=weekday.width)
                        if(widgetWidth<240) assertTrue(view.findViewById<TextView>(R.id.widget_title).textSize/density<16)
                    }
                    if(size==0) assertTrue(footer.text.contains("下次提醒"))
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                    val file=File(app.getExternalFilesDir(null),"screenshots/widget-$size-${widgetWidth}x$widgetHeight.png").apply { parentFile!!.mkdirs() }
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
            } finally { host.deleteAppWidgetId(id) }
          }
        }
    }
}
