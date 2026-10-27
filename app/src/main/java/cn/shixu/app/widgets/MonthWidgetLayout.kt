package cn.shixu.app.widgets

import kotlin.math.ceil
import kotlin.math.min

data class MonthWidgetLayout(
    val contentWidth: Float,val padding: Int,val headingSp: Float,val headingHeight: Int,
    val dateDp: Float,val weekdaySp: Float,val weekHeight: Float,val footerSp: Float,
    val itemSp: Float,val itemLines: Int,val itemLimit: Int,val narrow: Boolean
) {
    companion object {
        fun calculate(width: Int,height: Int,weeks: Int,fontScale: Float): MonthWidgetLayout {
            val narrow=width<240
            val padding=if(narrow) 8 else 12
            val contentWidth=(width-16-padding*2).coerceAtLeast(70).toFloat()
            val cell=contentWidth/7
            val headingSp=(width/21f).coerceIn(13f,20f)
            val headingHeight=ceil(headingSp*fontScale*1.5).toInt()+6
            val footerSp=if(narrow) 10f else 11f
            val footerSpace=ceil(footerSp*fontScale*2.8).toInt()+6
            val reserved=16+padding*2+headingHeight+footerSpace+20
            val available=(height-reserved).coerceAtLeast(weeks*20).toFloat()
            val rowCap=(cell*1.3f+28).coerceIn(42f,88f)
            val weekHeight=min(available/weeks,(available*(if(narrow) .72f else .86f)/weeks).coerceIn(24f,rowCap))
            val dateDp=min(cell*.65f,(cell*.4f).coerceIn(9f,17f)*(height/400f).coerceIn(.9f,1.15f)*fontScale)
            val weekdaySp=min(12f*fontScale,cell*.58f)/fontScale
            val itemSp=if(narrow) 12f else (width/24f).coerceIn(13f,16f)
            val lines=if(narrow) 2 else 1
            val itemHeight=ceil(itemSp*fontScale*1.5).toInt()*lines+(if(narrow) 8 else 12)
            val limit=((height-reserved-weeks*weekHeight)/itemHeight).toInt().coerceIn(0,8)
            return MonthWidgetLayout(contentWidth,padding,headingSp,headingHeight,dateDp,weekdaySp,weekHeight,footerSp,itemSp,lines,limit,narrow)
        }
    }
}
