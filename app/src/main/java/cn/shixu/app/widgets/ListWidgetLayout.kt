package cn.shixu.app.widgets

import kotlin.math.ceil
import kotlin.math.min

data class ListWidgetLayout(val padding: Int,val headingSp: Float,val headingHeight: Int,val bodySp: Float,val footerSp: Float,val stampSp: Float,val stampHeight: Int,val rowHeight: Int,val lines: Int,val limit: Int) {
    companion object {
        fun calculate(width: Int,height: Int,fontScale: Float,count: Int): ListWidgetLayout {
            val padding=if(width<220 || height<160) 8 else 12
            val heading=(width/20f+height/55f).coerceIn(13f,24f)
            val headingHeight=ceil(heading*fontScale*1.45f).toInt()+8
            val footer=(width/32f+height/350f).coerceIn(10f,13f)
            val stamp=if(height>=260) (height*.16f).coerceIn(42f,82f).toInt() else 0
            val stampSp=min(width/8f/fontScale,stamp/fontScale*.65f).coerceIn(8f,46f)
            val body=min(width/10f,width/20f+height/60f).coerceIn(12f,21f)
            val lines=if(width<220 && height>=200) 2 else 1
            val available=(height-padding*2-headingHeight-stamp-ceil(footer*fontScale*2.8f).toInt()-10).coerceAtLeast(0)
            val minimum=ceil(body*fontScale*1.4f*lines).toInt()+8
            val limit=minOf(count,available/minimum,12).coerceAtLeast(0)
            val rowHeight=if(limit>0) (available/limit).coerceIn(minimum,maxOf(minimum,78)) else minimum
            return ListWidgetLayout(padding,heading,headingHeight,body,footer,stampSp,stamp,rowHeight,lines,limit)
        }
    }
}
