package cn.shixu.app.ai

import cn.shixu.app.data.*
import org.json.JSONObject
import java.time.LocalDate

/** Optional planning dates are user decisions, never model-generated defaults. */
object ProposalChoices {
    fun category(title: String): String = when {
        listOf("作业","复习","学习","考试","论文","课程","背单词","读书").any(title::contains) -> "学习"
        listOf("项目","工作","汇报","客户","会议","报告","需求","交付").any(title::contains) -> "工作"
        listOf("买","取快递","缴费","健身","运动","旅行","体检","吃药","生日").any(title::contains) -> "生活"
        else -> "日常"
    }
    fun mayNeedStart(title: String): Boolean = listOf("作业","复习","备考","论文","计划","项目","准备","学习","课程","活动","训练","旅行").any(title::contains)
    fun pending(proposal: String): List<TaskEntity> = JSONObject(proposal).getJSONArray("operations").objects()
        .filter { it.optBoolean("needsStartDate",false) }.map { JsonCodec.readBundle(it.getJSONObject("bundle")).task }

    fun resolve(proposal: String, choices: Map<String,String?>): String {
        val envelope=JSONObject(proposal)
        val operations=envelope.getJSONArray("operations").objects()
        val required=operations.filter { it.optBoolean("needsStartDate",false) }
        require(choices.keys == required.map { JsonCodec.readBundle(it.getJSONObject("bundle")).task.id }.toSet()) { "请先选择每个事项的开始日期，或选择仅设截止日期" }
        required.forEach { op ->
            require(op.getString("action") in setOf("CREATE","UPDATE")) { "此操作不支持开始日期" }
            val original=JsonCodec.readBundle(op.getJSONObject("bundle"))
            require(original.task.startDate==null && original.task.dueDate!=null) { "开始日期建议已失效" }
            val date=choices[original.task.id]?.let { LocalDate.parse(it).toString() }
            val bundle=original.copy(task=original.task.copy(startDate=date))
            Rules.validate(bundle.task,bundle.reminders)
            op.put("bundle",JsonCodec.bundle(bundle)).put("needsStartDate",false).put("startDateChoice",date ?: JSONObject.NULL)
        }
        return envelope.toString()
    }
}
