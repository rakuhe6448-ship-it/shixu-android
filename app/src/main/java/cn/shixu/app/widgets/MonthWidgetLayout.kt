package cn.shixu.app.widgets

import kotlin.math.ceil
import kotlin.math.min

data class MonthWidgetLayout(
    val contentWidth: Float,val padding: Int,val headingSp: Float,val headingHeight: Int,
    val dateDp: Float,val weekdaySp: Float,val weekHeight: Float,val footerSp: Float,
    val itemSp: Float,val itemLines: Int,val itemLimit: Int,val narrow: Boolean
) {
    companion object {
        fun calculate(width: Int,height: Int,weeks: Int,fontScale: Float,items: Int=3): MonthWidgetLayout {
            val narrow=width<240
            val padding=if(narrow) 8 else 12
            val contentWidth=(width-padding*2).coerceAtLeast(70).toFloat()
            val cell=contentWidth/7
            val headingSp=(width/20f+height/55f).coerceIn(13f,maxOf(13f,min(24f,width/7.5f)))
            val headingHeight=ceil(headingSp*fontScale*1.5).toInt()+6
            val footerSp=(width/32f+height/300f).coerceIn(10f,13f)
            val footerSpace=ceil(footerSp*fontScale*2.8).toInt()+6
            val reserved=padding*2+headingHeight+footerSpace+20
            val available=(height-reserved).coerceAtLeast(weeks*12).toFloat()
            val itemSp=(width/22f+height/100f).coerceIn(11f,maxOf(11f,min(18f,width/10f)))
            val lines=if(narrow) 2 else 1
            val itemHeight=ceil(itemSp*fontScale*1.5).toInt()*lines+(if(narrow) 8 else 12)
            val limit=minOf(items.coerceIn(0,3),(available*.27f/itemHeight).toInt())
            val weekHeight=((available-limit*itemHeight)/weeks.coerceAtLeast(1)).coerceAtLeast(12f)
            val dateDp=min(cell*.64f,min(weekHeight*.34f,26f)*fontScale).coerceAtMost(weekHeight*.52f)
            val weekdaySp=min(13f*fontScale,cell*.56f)/fontScale
            return MonthWidgetLayout(contentWidth,padding,headingSp,headingHeight,dateDp,weekdaySp,weekHeight,footerSp,itemSp,lines,limit,narrow)
        }
    }
}
