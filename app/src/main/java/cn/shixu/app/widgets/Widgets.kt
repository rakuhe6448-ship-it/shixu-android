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
                .filter { it.width.isFinite() && it.height.isFinite() && it.width>=100 && it.height>=80 }.distinct().let { all -> if(all.size<=4) all else listOf(all.minBy { it.width },all.maxBy { it.width },all.minBy { it.height },all.maxBy { it.height }).distinct() }
            if(sizes.isNotEmpty()) return RemoteViews(sizes.associateWith { dimensions ->
                render(context,Bundle(options).apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,dimensions.width.toInt())
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,dimensions.height.toInt())
                },size,tasks,reminders,p,sizes.size)
            })
        }
        val landscape=context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
        return render(context,Bundle(options).apply {
            if(landscape && options.containsKey(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)) putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH))
            if(!landscape && options.containsKey(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)) putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
        },size,tasks,reminders,p)
    }
    fun render(context: Context, options: Bundle, size: Int, tasks: List<TaskEntity>, reminders: List<ReminderEntity>, p: Preferences, variants: Int=1): RemoteViews {
        val today=LocalDate.now()
        val dark=p.theme=="DARK" || p.theme=="SYSTEM" && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val ink=if(dark) Color.rgb(244,238,223) else Color.rgb(36,36,33)
        val bg=if(dark) Color.rgb(25,25,24) else p.background.toInt()
        val accent=if(dark) ColorUtils.blendARGB(p.accent.toInt(),ink,.32f) else p.accent.toInt()
        val height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,150).coerceAtLeast(80)
        val width=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,320).coerceAtLeast(100)
        val density=context.resources.displayMetrics.density
        val fontScale=context.resources.configuration.fontScale.coerceAtLeast(.5f)
        val active=tasks.filter { it.active }
        val upcoming=active.filter { it.overdue() || if(size==0) it.covers(today) else (0L..6L).any { d -> it.covers(today.plusDays(d)) } }
            .sortedWith(compareBy<TaskEntity> { !it.overdue() }.thenBy { it.dueDate ?: "9999" })
        val weeks=if(size==2) CalendarLayout.weeks(YearMonth.from(today),active) else emptyList()
        val month=if(size==2) MonthWidgetLayout.calculate(width,height,weeks.size,fontScale,upcoming.size) else null
        val list=ListWidgetLayout.calculate(width,height,fontScale,upcoming.size)
        val padding=((month?.padding ?: list.padding)*density).toInt()
        val views=RemoteViews(context.packageName,R.layout.widget_root)
        views.setViewPadding(R.id.widget_content,padding,padding,padding,padding)
        views.setInt(R.id.widget_title,"setHeight",((month?.headingHeight ?: list.headingHeight)*density).toInt())
        views.setTextViewTextSize(R.id.widget_title,TypedValue.COMPLEX_UNIT_SP,month?.headingSp ?: list.headingSp)
        views.setTextViewTextSize(R.id.widget_footer,TypedValue.COMPLEX_UNIT_SP,month?.footerSp ?: list.footerSp)
        views.setImageViewResource(R.id.widget_background,R.drawable.widget_background)
        views.setInt(R.id.widget_background,"setColorFilter",bg)
        views.setInt(R.id.widget_background,"setImageAlpha",(p.widgetOpacity*255).toInt())
        views.setTextViewText(R.id.widget_title,when(size) {
            0 -> context.getString(R.string.widget_today_title,today.monthValue,today.dayOfMonth)
            1 -> context.getString(R.string.widget_upcoming_title)
            else -> if(month!!.narrow) context.getString(R.string.widget_month_title_narrow,today.monthValue)
                else context.getString(R.string.widget_month_title,today.year,today.monthValue)
        })
        views.setTextColor(R.id.widget_title,accent)
        views.setTextColor(R.id.widget_footer,ink)
        views.setOnClickPendingIntent(R.id.widget_title,open(context,date=today.toString()))
        views.setViewVisibility(R.id.widget_stamp,if(size!=2 && list.stampHeight>0) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_stamp,"${today.dayOfMonth.toString().padStart(2,'0')} / ${today.monthValue.toString().padStart(2,'0')}")
        views.setTextViewTextSize(R.id.widget_stamp,TypedValue.COMPLEX_UNIT_SP,list.stampSp)
        views.setTextColor(R.id.widget_stamp,ink)
        views.setInt(R.id.widget_stamp,"setHeight",(list.stampHeight*density).toInt())
        views.setOnClickPendingIntent(R.id.widget_stamp,open(context,date=today.toString()))
        views.removeAllViews(R.id.widget_items)
        views.removeAllViews(R.id.widget_calendar)
        if(month!=null) {
            val metrics=context.resources.displayMetrics
            val bitmapScale=minOf(density,sqrt(metrics.widthPixels.toFloat()*metrics.heightPixels*.65f/(month.contentWidth*month.weekHeight*weeks.size*variants.coerceAtLeast(1))))
            val weekdays=RemoteViews(context.packageName,R.layout.widget_weekdays)
            listOf(R.id.weekday1,R.id.weekday2,R.id.weekday3,R.id.weekday4,R.id.weekday5,R.id.weekday6,R.id.weekday7).forEach { weekdays.setTextColor(it,ink);weekdays.setTextViewTextSize(it,TypedValue.COMPLEX_UNIT_SP,month.weekdaySp) }
            views.addView(R.id.widget_calendar,weekdays)
            weeks.forEach { week ->
                val row=RemoteViews(context.packageName,R.layout.widget_week)
                row.setImageViewBitmap(R.id.widget_week_image,weekImage(week,today,ink,accent,month,bitmapScale))
                row.removeAllViews(R.id.widget_week_cells)
                (0..6).forEach { day ->
                    val cell=RemoteViews(context.packageName,R.layout.widget_day)
                    val date=week.start.plusDays(day.toLong())
                    cell.setContentDescription(R.id.widget_day,context.getString(R.string.widget_day_description,date.toString(),week.bars.count { day in it.start..it.end }))
                    cell.setOnClickPendingIntent(R.id.widget_day,open(context,date=date.toString()))
                    row.addView(R.id.widget_week_cells,cell)
                }
                views.addView(R.id.widget_calendar,row)
            }
        }
        val limit=month?.itemLimit ?: list.limit
        upcoming.take(limit).forEach { t ->
            val row=RemoteViews(context.packageName,R.layout.widget_item)
            val rowPadding=((if(month?.narrow==true) 4 else 6)*density).toInt()
            row.setViewPadding(R.id.widget_item_root,0,rowPadding,0,rowPadding)
            if(month==null) row.setInt(R.id.widget_item_root,"setMinimumHeight",(list.rowHeight*density).toInt())
            row.setTextViewTextSize(R.id.widget_item_text,TypedValue.COMPLEX_UNIT_SP,month?.itemSp ?: list.bodySp)
            row.setTextViewTextSize(R.id.widget_item_dot,TypedValue.COMPLEX_UNIT_SP,(month?.itemSp ?: list.bodySp)*.75f)
            row.setInt(R.id.widget_item_text,"setMaxLines",month?.itemLines ?: list.lines)
            val showMeta=month==null && list.rowHeight>=ceil(list.bodySp*fontScale*1.45f*list.lines+list.bodySp*.8f*fontScale+16).toInt()
            row.setViewVisibility(R.id.widget_item_meta,if(showMeta) View.VISIBLE else View.GONE)
            row.setTextViewText(R.id.widget_item_meta,t.category+" / "+(t.dueDate?.let { it+(t.dueTime?.let { time -> " $time" } ?: "") } ?: context.getString(R.string.widget_no_deadline)))
            row.setTextViewTextSize(R.id.widget_item_meta,TypedValue.COMPLEX_UNIT_SP,list.bodySp*.72f)
            row.setTextColor(R.id.widget_item_meta,ink)
            row.setTextViewText(R.id.widget_item_dot,context.getString(R.string.widget_item_marker))
            row.setTextViewText(R.id.widget_item_text,when {
                t.overdue() -> context.getString(R.string.widget_overdue_task,t.title)
                size==1 -> (t.dueDate?.takeLast(5) ?: "")+" "+t.title
                else -> t.title
            })
            row.setTextColor(R.id.widget_item_text,ink)
            row.setTextColor(R.id.widget_item_dot,t.color.toInt())
            row.setOnClickPendingIntent(R.id.widget_item_root,open(context,taskId=t.id))
            views.addView(R.id.widget_items,row)
        }
        if(size!=2 && upcoming.isEmpty()) {
            val empty=RemoteViews(context.packageName,R.layout.widget_empty)
            empty.setTextViewText(R.id.widget_empty_text,context.getString(R.string.widget_empty_message))
            empty.setTextViewTextSize(R.id.widget_empty_text,TypedValue.COMPLEX_UNIT_SP,(list.bodySp+3).coerceAtMost(24f))
            empty.setTextColor(R.id.widget_empty_text,ink)
            empty.setOnClickPendingIntent(R.id.widget_empty_text,open(context,date=today.toString()))
            views.addView(R.id.widget_items,empty)
        }
        val next=reminders.filter { r -> r.enabled && active.any { it.id==r.taskId } }.mapNotNull { r -> Rules.nextReminder(r,LocalDateTime.now())?.let { r to it } }.minByOrNull { it.second }
        val footer=when {
            size==0 && next!=null -> context.getString(R.string.widget_next_reminder,next.second.toLocalDate().toString().takeLast(5),next.second.toLocalTime().toString())
            upcoming.isEmpty() -> context.getString(R.string.widget_footer_empty)
            upcoming.size>limit -> context.getString(R.string.widget_footer_more,upcoming.size)
            else -> context.getString(R.string.widget_footer_count,upcoming.size)
        }
        views.setTextViewText(R.id.widget_footer,footer)
        views.setOnClickPendingIntent(R.id.widget_footer,if(size==0 && next!=null) open(context,taskId=next.first.taskId) else open(context,date=today.toString()))
        return views
    }
    private fun weekImage(week: CalendarWeek,today: LocalDate,ink: Int,accent: Int,layout: MonthWidgetLayout,scale: Float): Bitmap {
        val bitmap=Bitmap.createBitmap(ceil(layout.contentWidth*scale).toInt().coerceAtLeast(1),ceil(layout.weekHeight*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        bitmap.density=Bitmap.DENSITY_NONE
        val canvas=Canvas(bitmap);canvas.scale(scale,scale)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val cell=layout.contentWidth/7
        val dateArea=layout.dateDp*1.2f+2
        val desired=(week.bars.maxOfOrNull { it.lane+1 } ?: 1).coerceIn(1,4)
        val barHeight=if(cell>=28 && layout.weekHeight-dateArea>=22) ((layout.weekHeight-dateArea-13)/desired-3).coerceIn(9f,18f) else (layout.weekHeight-dateArea-1).coerceIn(2f,4f)
        val stride=barHeight+3
        val lanes=((layout.weekHeight-dateArea-11)/stride).toInt().coerceIn(1,4)
        (0..6).forEach { d ->
            val date=week.start.plusDays(d.toLong());val x=(d+.5f)*cell
            paint.color=ink;paint.alpha=22;paint.strokeWidth=.5f
            canvas.drawLine(d*cell,0f,d*cell,layout.weekHeight,paint)
            if(date==today) { paint.color=accent;paint.alpha=255;canvas.drawRect(x-layout.dateDp*.68f,1f,x+layout.dateDp*.68f,dateArea-1,paint) }
            paint.color=if(date==today) { if(ColorUtils.calculateLuminance(accent)>.179) Color.BLACK else Color.WHITE } else ink
            paint.alpha=if(date.month==today.month) 255 else 85
            paint.textSize=layout.dateDp;paint.typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD);paint.textAlign=Paint.Align.CENTER
            canvas.drawText(date.dayOfMonth.toString(),x,dateArea/2-(paint.ascent()+paint.descent())/2,paint)
        }
        paint.alpha=255
        week.bars.filter { it.lane<lanes }.forEach { bar ->
            paint.color=bar.task.color.toInt()
            val left=bar.start*cell+1;val right=(bar.end+1)*cell-1;val top=dateArea+bar.lane*stride
            canvas.drawRect(left,top,right,minOf(top+barHeight,layout.weekHeight-1),paint)
            if(barHeight>=9 && right-left>=45) {
                paint.color=if(ColorUtils.calculateLuminance(bar.task.color.toInt())>.179) Color.BLACK else Color.WHITE
                paint.textSize=(barHeight*.68f).coerceIn(7f,12f);paint.textAlign=Paint.Align.LEFT
                val title=bar.task.title;val count=paint.breakText(title,true,right-left-6,null)
                val text=if(count<title.length) title.take((count-1).coerceAtLeast(0))+"\u2026" else title
                canvas.drawText(text,left+3,top+barHeight/2-(paint.ascent()+paint.descent())/2,paint)
            }
        }
        (0..6).forEach { d ->
            val more=week.bars.count { it.lane>=lanes && d in it.start..it.end }
            if(more>0 && dateArea+lanes*stride+7<layout.weekHeight) { paint.color=ink;paint.textSize=8f;paint.textAlign=Paint.Align.CENTER;canvas.drawText("+$more",(d+.5f)*cell,layout.weekHeight-1,paint) }
        }
        return bitmap
    }
}
