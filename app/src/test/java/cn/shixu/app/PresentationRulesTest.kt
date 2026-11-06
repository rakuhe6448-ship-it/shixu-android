package cn.shixu.app

import cn.shixu.app.ai.*
import cn.shixu.app.data.*
import cn.shixu.app.widgets.ListWidgetLayout
import cn.shixu.app.widgets.MonthWidgetLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class PresentationRulesTest {
    @Test fun overlapHasPriorityOverGlobalColorUsage() {
        val candidate=TaskEntity(title="新事项",startDate="2030-11-01",dueDate="2030-11-08")
        val free=TaskPalette.colors[1]
        val occupied=TaskPalette.colors.filterNot { it==free }.map { TaskEntity(title="重叠",dueDate="2030-11-06",color=it) }
        val elsewhere=(1..20).map { TaskEntity(title="其他日期",dueDate="2030-12-01",color=free) }
        assertEquals(free,TaskPalette.next(occupied+elsewhere,candidate))
    }
    @Test fun successiveTasksUseTheWholePaletteBeforeReusingAColor() {
        val tasks=mutableListOf<TaskEntity>()
        repeat(TaskPalette.colors.size) {
            val t=TaskEntity(title="事项$it",dueDate="2030-11-06")
            tasks.add(t.copy(color=TaskPalette.next(tasks,t)))
        }
        assertEquals(TaskPalette.colors.size,tasks.map { it.color }.distinct().size)
        assertEquals(tasks.first().color,TaskPalette.next(tasks,TaskEntity(title="第九件",dueDate="2030-11-06")))
    }
    @Test fun aiBatchUsesDifferentLocalColorsAndPreservesExplicitColor() {
        fun op(title: String,color: Long?=null)=JSONObject().put("target","NEW").put("action","PATCH").put("fields",JSONObject().put("title",title).put("dueDate","2030-11-06").also { if(color!=null) it.put("color",color) })
        val raw=JSONObject().put("protocolVersion",2).put("operations",JSONArray().put(op("开会")).put(op("购物")).put(op("散步",0xFF123456)))
        val result=DraftEngine.normalize(raw,"source",ConversationContext(emptyList(),null,emptyList())).map { DraftContent.bundle(it.draft).task }
        assertNotEquals(result[0].color,result[1].color)
        assertEquals(0xFF123456,result[2].color)
    }
    @Test fun tallMonthUsesItsAvailableHeightAndChangesDateSize() {
        val short=MonthWidgetLayout.calculate(320,300,6,1f,0)
        val tall=MonthWidgetLayout.calculate(320,700,6,1f,0)
        assertTrue(tall.weekHeight>short.weekHeight)
        assertTrue(tall.dateDp>short.dateDp)
        assertEquals(0,tall.itemLimit)
        val used=tall.padding*2+tall.headingHeight+ceil(tall.footerSp*2.8).toInt()+6+20+6*tall.weekHeight
        assertEquals(700f,used,.1f)
        assertTrue(tall.dateDp<tall.contentWidth/7)
    }
    @Test fun narrowMonthAndLargeSystemFontFitTheHeightBudget() {
        for(w in listOf(100,140,320,600)) for(h in listOf(260,450,700)) {
            val extreme=MonthWidgetLayout.calculate(w,h,6,2f,10)
            assertTrue(extreme.weekHeight.isFinite() && extreme.dateDp>0)
            assertTrue(extreme.dateDp*1.2f+2<=extreme.weekHeight)
        }
        val layout=MonthWidgetLayout.calculate(140,260,6,2f,10)
        val used=layout.padding*2+layout.headingHeight+ceil(layout.footerSp*2f*2.8).toInt()+6+20+6*layout.weekHeight
        assertTrue(used<=260.1f)
        assertTrue(layout.dateDp<=layout.contentWidth/7*.65f)
        assertEquals(2,layout.itemLines)
    }
    @Test fun listTextScalesWithHeightAndWidthWhileRowsStayBounded() {
        val small=ListWidgetLayout.calculate(160,140,1f,20)
        val large=ListWidgetLayout.calculate(320,420,1f,20)
        val tall=ListWidgetLayout.calculate(160,420,1f,20)
        assertTrue(large.headingSp>small.headingSp)
        assertTrue(large.bodySp>small.bodySp)
        assertTrue(tall.headingSp>small.headingSp)
        assertTrue(large.limit in 1..12)
        assertTrue(large.stampHeight>0)
        assertEquals(2,tall.lines)
    }
    @Test fun stockThemeUpgradesButCustomColorsRemain() {
        val stock=Preferences.from(listOf(SettingEntity("accent",0xFF66785B.toString()),SettingEntity("background",0xFFF7F8F2.toString())))
        assertEquals(Preferences().accent,stock.accent)
        assertEquals(Preferences().background,stock.background)
        val custom=Preferences.from(listOf(SettingEntity("accent",0xFF123456.toString()),SettingEntity("background",0xFFEEEEEE.toString())))
        assertEquals(0xFF123456,custom.accent);assertEquals(0xFFEEEEEE,custom.background)
    }
}
