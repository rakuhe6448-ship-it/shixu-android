package cn.shixu.app.widgets

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.SizeF
import androidx.core.graphics.ColorUtils
import android.view.View
import android.widget.RemoteViews
import cn.shixu.app.MainActivity
import cn.shixu.app.R
import cn.shixu.app.ShiXuApp
import cn.shixu.app.data.*
import kotlinx.coroutines.launch
import java.time.*
import android.util.TypedValue
import kotlin.math.ceil
import kotlin.math.sqrt

open class PlannerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)
    private fun refresh(context: Context) {
        val pending = goAsync()
        (context.applicationContext as ShiXuApp).scope.launch { try { WidgetRenderer.updateAll(context) } finally { pending.finish() } }
    }
}
class TodayWidget : PlannerWidget()
class UpcomingWidget : PlannerWidget()
class MonthWidget : PlannerWidget()

object WidgetRenderer {
    private fun open(context: Context, taskId: String? = null, date: String? = null): PendingIntent {
        val intent = Intent(context,MainActivity::class.java).setData(Uri.parse("shixu://widget/${taskId ?: date ?: "home"}"))
        taskId?.let { intent.putExtra("taskId",it) }
        date?.let { intent.putExtra("date",it) }
        return PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    suspend fun updateAll(context: Context) {
        val app = context.applicationContext as ShiXuApp
        val tasks = app.repository.dao.tasks().filter { it.active }
        val reminders = app.repository.dao.reminders().filter { it.enabled && tasks.any { t -> t.id == it.taskId } }
        val p = Preferences.from(app.repository.dao.settings())
        val manager = AppWidgetManager.getInstance(context)
        listOf(TodayWidget::class.java,UpcomingWidget::class.java,MonthWidget::class.java).forEachIndexed { size,clazz ->
            manager.getAppWidgetIds(ComponentName(context,clazz)).forEach { id ->
                manager.updateAppWidget(id,responsive(context,manager.getAppWidgetOptions(id),size,tasks,reminders,p))
            }
        }
    }
    @Suppress("DEPRECATION")
    fun responsive(context: Context, options: Bundle, size: Int, tasks: List<TaskEntity>, reminders: List<ReminderEntity>, p: Preferences): RemoteViews {
        if(Build.VERSION.SDK_INT>=31) {
            val sizes=options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty()
                .filter { it.width.isFinite() && it.height.isFinite() && it.width>=100 && it.height>=80 }.distinct().take(2)
            if(sizes.isNotEmpty()) return RemoteViews(sizes.associateWith { dimensions ->
                render(context,Bundle(options).apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,dimensions.width.toInt())
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,dimensions.height.toInt())
                },size,tasks,reminders,p)
            })
        }
        val landscape=context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
        return render(context,Bundle(options).apply {
            if(landscape && options.containsKey(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)) putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH))
            if(!landscape && options.containsKey(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)) putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
        },size,tasks,reminders,p)
    }
    fun render(context: Context, options: Bundle, size: Int, tasks: List<TaskEntity>, reminders: List<ReminderEntity>, p: Preferences): RemoteViews {
        val today = LocalDate.now()
        val dark = p.theme == "DARK" || p.theme == "SYSTEM" && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = if(dark) Color.rgb(236,240,229) else Color.rgb(45,54,42)
        val bg = if(dark) Color.rgb(30,37,30) else p.background.toInt()
        val views = RemoteViews(context.packageName,R.layout.widget_root)
        val height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,150)
        val width=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,320)
        val compact=height<160
        val density=context.resources.displayMetrics.density
        val fontScale=context.resources.configuration.fontScale
        val weeks=if(size==2) CalendarLayout.weeks(YearMonth.from(today),tasks) else emptyList()
        val month=if(size==2) MonthWidgetLayout.calculate(width,height,weeks.size,fontScale) else null
        if(month!=null) {
            val padding=(month.padding*density).toInt()
            views.setViewPadding(R.id.widget_content,padding,padding,padding,padding)
            views.setInt(R.id.widget_title,"setHeight",(month.headingHeight*density).toInt())
            views.setTextViewTextSize(R.id.widget_title,TypedValue.COMPLEX_UNIT_SP,month.headingSp)
            views.setTextViewTextSize(R.id.widget_footer,TypedValue.COMPLEX_UNIT_SP,month.footerSp)
        } else if(compact) {
            val padding=(8*density).toInt()
            views.setViewPadding(R.id.widget_content,padding,padding,padding,padding)
            views.setInt(R.id.widget_title,"setHeight",(24*density).toInt())
            views.setTextViewTextSize(R.id.widget_title,TypedValue.COMPLEX_UNIT_SP,14f)
            views.setTextViewTextSize(R.id.widget_footer,TypedValue.COMPLEX_UNIT_SP,10f)
            views.setViewPadding(R.id.widget_footer,0,(2*density).toInt(),0,0)
        }
        views.setImageViewResource(R.id.widget_background,R.drawable.widget_background)
        views.setInt(R.id.widget_background,"setColorFilter",bg)
        views.setInt(R.id.widget_background,"setImageAlpha",(p.widgetOpacity*255).toInt())
        views.setTextViewText(R.id.widget_title,when(size){0 -> "今日 · ${today.monthValue}/${today.dayOfMonth}"; 1 -> "未来七天  ·  时序"; else -> if(month!!.narrow) "${today.monthValue} 月 · 时序" else "${today.year} 年 ${today.monthValue} 月  ·  时序"})
        views.setTextColor(R.id.widget_title,ink)
        views.setTextColor(R.id.widget_footer,ink)
        views.setOnClickPendingIntent(R.id.widget_title,open(context,date=today.toString()))
        views.removeAllViews(R.id.widget_items)
        views.removeAllViews(R.id.widget_calendar)
        if(size == 2) {
            // Keep date text sharp at device density while bounding the two-size bitmap budget.
            val metrics=context.resources.displayMetrics
            val bitmapScale=minOf(density,sqrt(metrics.widthPixels.toFloat()*metrics.heightPixels*.5f/(month!!.contentWidth*month.weekHeight*weeks.size)))
            val weekdays=RemoteViews(context.packageName,R.layout.widget_weekdays)
            listOf(R.id.weekday1,R.id.weekday2,R.id.weekday3,R.id.weekday4,R.id.weekday5,R.id.weekday6,R.id.weekday7).forEach { weekdays.setTextColor(it,ink);weekdays.setTextViewTextSize(it,TypedValue.COMPLEX_UNIT_SP,month!!.weekdaySp) }
            views.addView(R.id.widget_calendar,weekdays)
            weeks.forEach { week ->
                val row = RemoteViews(context.packageName,R.layout.widget_week)
                row.setImageViewBitmap(R.id.widget_week_image,weekImage(week,today,ink,month,bitmapScale))
                row.removeAllViews(R.id.widget_week_cells)
                (0..6).forEach { day ->
                    val cell = RemoteViews(context.packageName,R.layout.widget_day)
                    val date = week.start.plusDays(day.toLong())
                    cell.setContentDescription(R.id.widget_day,"${date}，${week.bars.count { day in it.start..it.end }} 个事项")
                    cell.setOnClickPendingIntent(R.id.widget_day,open(context,date=date.toString()))
                    row.addView(R.id.widget_week_cells,cell)
                }
                views.addView(R.id.widget_calendar,row)
            }
        }
        // Reserve launcher padding, heading and both footer lines before fitting task rows.
        val reserved=16+(if(compact) 16+24 else 24+32)+ceil((if(compact) 24 else 32)*fontScale).toInt()+6
        val rowHeight=ceil((if(compact) 18 else 20)*fontScale).toInt()+(if(compact) 4 else 12)
        val limit=month?.itemLimit ?: ((height-reserved)/rowHeight).coerceIn(0,8)
        val upcoming = tasks.filter { it.overdue() || if(size==0) it.covers(today) else (0L..6L).any { d -> it.covers(today.plusDays(d)) } }
            .sortedWith(compareBy<TaskEntity> { !it.overdue() }.thenBy { it.dueDate ?: "9999" })
        upcoming.take(limit).forEach { t ->
            val row = RemoteViews(context.packageName,R.layout.widget_item)
            if(month!=null) {
                val padding=((if(month.narrow) 4 else 6)*density).toInt()
                row.setViewPadding(R.id.widget_item_root,0,padding,0,padding)
                row.setTextViewTextSize(R.id.widget_item_text,TypedValue.COMPLEX_UNIT_SP,month.itemSp)
                row.setInt(R.id.widget_item_text,"setMaxLines",month.itemLines)
            } else if(compact) {
                row.setViewPadding(R.id.widget_item_root,0,(2*density).toInt(),0,(2*density).toInt())
                row.setTextViewTextSize(R.id.widget_item_text,TypedValue.COMPLEX_UNIT_SP,12f)
            }
            row.setTextViewText(R.id.widget_item_text,"${if(t.overdue()) "逾期 · " else if(size==1) (t.dueDate?.takeLast(5) ?: "")+" " else ""}${t.title}")
            row.setTextColor(R.id.widget_item_text,ink)
            row.setTextColor(R.id.widget_item_dot,t.color.toInt())
            row.setOnClickPendingIntent(R.id.widget_item_root,open(context,taskId=t.id))
            views.addView(R.id.widget_items,row)
        }
        val next = reminders.mapNotNull { r -> Rules.nextReminder(r,LocalDateTime.now())?.let { r to it } }.minByOrNull { it.second }
        val footer = if(size==0 && next!=null) "下次提醒 ${next.second.toLocalDate().toString().takeLast(5)} ${next.second.toLocalTime()}" else if(upcoming.isEmpty()) "暂时没有待办，留一点时间给自己" else "${upcoming.size} 项待办${if(upcoming.size>limit) " · 点击查看全部" else ""}"
        views.setTextViewText(R.id.widget_footer,footer)
        views.setOnClickPendingIntent(R.id.widget_footer,next?.let { open(context,taskId=it.first.taskId) } ?: open(context,date=today.toString()))
        return views
    }
    private fun weekImage(week: CalendarWeek, today: LocalDate, ink: Int,layout: MonthWidgetLayout,scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(ceil(layout.contentWidth*scale).toInt(),ceil(layout.weekHeight*scale).toInt(),Bitmap.Config.ARGB_8888)
        bitmap.density=Bitmap.DENSITY_NONE
        val canvas = Canvas(bitmap)
        canvas.scale(scale,scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cell=layout.contentWidth/7
        val dateArea=layout.dateDp*1.5f+4
        val desiredLanes=(week.bars.maxOfOrNull { it.lane+1 } ?: 1).coerceAtMost(3)
        val barHeight=if(!layout.narrow && layout.weekHeight-dateArea>=desiredLanes*13.5f+6) 11f else 4f
        val stride=barHeight+2.5f
        val lanes=((layout.weekHeight-dateArea-6)/stride).toInt().coerceIn(1,4)
        (0..6).forEach { d ->
            val date=week.start.plusDays(d.toLong())
            val x=(d+.5f)*cell
            if(date==today) { paint.color=0xFF66785B.toInt();paint.alpha=255;canvas.drawCircle(x,dateArea/2,minOf(layout.dateDp*.8f,cell*.44f),paint) }
            paint.color=if(date==today) Color.WHITE else ink
            paint.alpha=if(date.month==today.month) 255 else 90
            paint.textSize=layout.dateDp; paint.textAlign=Paint.Align.CENTER
            canvas.drawText(date.dayOfMonth.toString(),x,dateArea/2-(paint.ascent()+paint.descent())/2,paint)
        }
        paint.alpha=255
        week.bars.filter { it.lane<lanes }.forEach { bar ->
            paint.color=bar.task.color.toInt()
            val left=bar.start*cell+1; val right=(bar.end+1)*cell-1;val top=dateArea+bar.lane*stride
            canvas.drawRoundRect(left,top,right,minOf(top+barHeight,layout.weekHeight-1),2f,2f,paint)
            if(barHeight>=10 && right-left>=55) {
                paint.color=if(ColorUtils.calculateLuminance(bar.task.color.toInt())>.45) Color.rgb(30,37,30) else Color.WHITE
                paint.textSize=8f;paint.textAlign=Paint.Align.LEFT
                val title=bar.task.title
                val count=paint.breakText(title,true,right-left-8,null)
                val text=if(count<title.length) title.take((count-1).coerceAtLeast(0))+"…" else title
                canvas.drawText(text,left+3,top+barHeight/2-(paint.ascent()+paint.descent())/2,paint)
            }
        }
        (0..6).forEach { d ->
            val more=week.bars.count { it.lane>=lanes && d in it.start..it.end }
            if(more>0 && dateArea+lanes*stride+6<layout.weekHeight) { paint.color=ink; paint.textSize=7f; paint.textAlign=Paint.Align.CENTER; canvas.drawText("+$more",(d+.5f)*cell,layout.weekHeight-1,paint) }
        }
        return bitmap
    }
}
