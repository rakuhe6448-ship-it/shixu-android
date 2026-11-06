package cn.shixu.app.ai

import cn.shixu.app.data.objects
import org.json.JSONArray
import org.json.JSONObject

data class DraftQuestion(val field: String,val text: String,val requestedValue: String?=null) {
    fun json()=JSONObject().put("field",field).put("text",text).put("requestedValue",requestedValue ?: JSONObject.NULL)
    companion object {
        private val fields=setOf("TITLE","DATE","REPEAT","REMINDER_TIME","DETAILS")
        fun read(array: JSONArray): List<DraftQuestion> {
            require(array.length()<=8) { "单个草稿的待补充问题过多" }
            val result=array.objects().map { o ->
                require(o.keys().asSequence().all { it in setOf("field","text","requestedValue") })
                require(o.opt("field") is String && o.getString("field") in fields && o.opt("text") is String)
                val text=o.getString("text").trim();require(text.isNotEmpty() && text.length<=400)
                val value=if(o.isNull("requestedValue")) null else { require(o.opt("requestedValue") is String);o.getString("requestedValue").also { require(it.length<=400) } }
                DraftQuestion(o.getString("field"),text,value)
            }
            require(result.map { it.field }.distinct().size==result.size) { "待补充字段重复" }
            return result
        }
    }
}
