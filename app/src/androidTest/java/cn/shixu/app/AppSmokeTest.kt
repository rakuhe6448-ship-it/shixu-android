package cn.shixu.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import cn.shixu.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

class AppSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ShiXuApp
    @Before fun clear() = runBlocking {
        app.db.withTransaction { app.repository.dao.clearDraftLinks();app.repository.dao.clearDrafts();app.repository.dao.clearLinks();app.repository.dao.clearMessages();app.repository.dao.clearHistory();app.repository.dao.clearReminders();app.repository.dao.clearTasks() }
        compose.runOnIdle { val vm=ViewModelProvider(compose.activity)[PlannerViewModel::class.java]; vm.page=0;vm.detailsId=null;vm.editor=null;vm.startDateMessage=null;vm.finishPermissionIntro(manualOnly=true) }
        compose.waitUntil(10000) { ViewModelProvider(compose.activity)[PlannerViewModel::class.java].permissionIntroComplete }
    }
    @Test fun phoneAlarmCardHandsOffToInstalledClock() {
        val answer=runBlocking {
            val repo=app.repository
            val source=MessageEntity(role="user",text="设置7点31分手机闹钟");repo.putMessage(source)
            val raw=JSONObject().put("protocolVersion",2).put("operations",JSONArray().put(JSONObject().put("action","SET_ALARM").put("alarm",JSONObject().put("time","07:31").put("label","时序联调测试"))))
            repo.acceptAiResult(raw,source.id,repo.conversationContext(source),"")
        }
        compose.onNodeWithText("AI 聊天",useUnmergedTree=true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("确认并打开手机时钟").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("确认并打开手机时钟").performClick()
        compose.waitUntil(10000) { runBlocking { app.repository.dao.message(answer.id)?.state=="APPLIED" } }
        assertTrue(runBlocking { app.repository.dao.tasks().isEmpty() && app.repository.dao.reminders().isEmpty() })
    }
    @Test fun manualCreateCompleteAndRestoreThroughRealUi() {
        compose.onNodeWithText("新建事项",useUnmergedTree=true).performClick()
        compose.onNodeWithText("想安排什么？").performTextInput("手动流程验收")
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.waitUntil(10000) { runBlocking { app.repository.dao.tasks().any { it.title=="手动流程验收" } } }
        compose.onNodeWithText("待办",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("标记完成").performClick()
        compose.waitUntil(10000) { runBlocking { app.repository.dao.tasks().single().completedAt!=null } }
        compose.onNodeWithText("已完成 1").performClick()
        compose.onNodeWithText("手动流程验收").performClick()
        compose.onNodeWithText("重新打开").performClick()
        compose.waitUntil(10000) { runBlocking { app.repository.dao.tasks().single().active } }
    }
    @Test fun calendarChatSettingsAndDarkThemeRender() = runBlocking {
        val dao=app.repository.dao
        val month=YearMonth.now(); val today=LocalDate.now()
        val examples=listOf(
            TaskEntity(title="流体输配管网 · 课程作业",startDate=month.atDay(3).toString(),dueDate=month.atDay(11).toString(),category="学习",color=0xFF91A786),
            TaskEntity(title="读书计划 · 每天一点",startDate=month.atDay(8).toString(),dueDate=month.atDay(22).toString(),category="生活",color=0xFF94B8CE),
            TaskEntity(title="小组项目",startDate=month.atDay(12).toString(),dueDate=month.atDay(25).toString(),category="学习",color=0xFFB3A2C9),
            TaskEntity(title="提交课程论文",dueDate=today.toString(),category="学习",color=0xFFD4A58D),
            TaskEntity(title="今晚的组会",dueDate=today.toString(),dueTime="21:00",category="工作",color=0xFFD0BC73)
        )
        examples.forEach { app.repository.save(TaskBundle(it,emptyList()),null) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("流体输配管网 · 课程作业",substring=true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("calendar-light")
        val source=MessageEntity(role="user",text="这个作业延期到 28 号了。")
        dao.putMessage(source);dao.putLink(MessageTask(source.id,examples.first().id))
        val raw=JSONObject().put("operations",JSONArray().put(JSONObject().put("action","UPDATE").put("taskId",examples.first().id).put("version",1).put("changes",JSONObject().put("dueDate",month.atDay(28).toString()))))
        val proposal=cn.shixu.app.ai.ProposalValidator.normalize(raw,source.id,listOf(TaskBundle(examples.first(),emptyList())),setOf(examples.first().id))!!
        dao.putMessage(MessageEntity(role="assistant",text="请核对调整后的日期，确认后保存。",quoteId=source.id,proposal=proposal,state="PENDING"))
        compose.onNodeWithText("AI 聊天",useUnmergedTree=true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("确认执行").fetchSemanticsNodes().isNotEmpty() }
        screenshot("chat-proposal")
        compose.onNodeWithText("确认执行").performClick()
        compose.waitUntil(10000) { daoValue { dao.task(examples.first().id)?.dueDate==month.atDay(28).toString() } }
        compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
        compose.onNodeWithText("AI 连接",substring=true).assertIsDisplayed()
        screenshot("settings")
        app.repository.saveSettings(Preferences(theme="DARK"))
        compose.onNodeWithText("排期",useUnmergedTree=true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("已保存到排期，提醒已同步").fetchSemanticsNodes().isEmpty() }
        screenshot("calendar-dark")
        app.repository.saveSettings(Preferences())
    }
    @Test fun permissionIntroGatesMainUiAndManualChoicePersists() {
        compose.runOnIdle { ViewModelProvider(compose.activity)[PlannerViewModel::class.java].showPermissionIntro() }
        compose.onNodeWithText("先让提醒准备好").assertIsDisplayed()
        compose.onNodeWithText("新建事项").assertDoesNotExist()
        compose.onNodeWithText("暂时只用排期").performScrollTo().performClick()
        compose.onNodeWithText("仅使用排期").performClick()
        compose.waitUntil(10000) { ViewModelProvider(compose.activity)[PlannerViewModel::class.java].permissionIntroComplete }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("新建事项",useUnmergedTree=true).assertIsDisplayed()
    }
    @Test fun apiServicePickerUsesSeparateProfilesAndGenericStartsBlank() = runBlocking {
        app.repository.dao.clearSettings()
        app.repository.saveSettings(Preferences())
        compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
        compose.onNodeWithText("OpenAI 兼容").assertIsSelected()
        compose.onNodeWithText("API 地址（HTTPS）").assertTextContains("")
        compose.onNodeWithText("DeepSeek",useUnmergedTree=true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("https://api.deepseek.com/v1",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("api-preset")
        compose.onNodeWithText("OpenAI 兼容",useUnmergedTree=true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("https://api.deepseek.com/v1",useUnmergedTree=true).fetchSemanticsNodes().isEmpty() }
        screenshot("api-compatible")
    }
    @Test fun aiStartDateDialogRequiresDecisionAndSavesRealCategoryAndSpan() = runBlocking {
        val source=MessageEntity(role="user",text="下周交作业")
        app.repository.putMessage(source)
        val due=LocalDate.now().plusDays(7).toString()
        val op=JSONObject().put("target","NEW").put("action","PATCH").put("startDateAdvice","ASK").put("fields",JSONObject().put("title","流体作业").put("dueDate",due).put("category","学习"))
        val card=app.repository.acceptAiResult(JSONObject().put("protocolVersion",2).put("operations",JSONArray().put(op)),source.id,app.repository.conversationContext(source),"核对安排")
        compose.runOnIdle { ViewModelProvider(compose.activity)[PlannerViewModel::class.java].page=3 }
        compose.waitUntil(10000) { compose.onAllNodesWithText("确认执行").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("确认执行").performClick()
        compose.onNodeWithText("确认并保存").assertIsNotEnabled()
        compose.onNodeWithText("今天开始").performScrollTo().performClick()
        screenshot("ai-start-date")
        compose.onNodeWithText("确认并保存").performClick()
        compose.waitUntil(10000) { daoValue { app.repository.dao.tasks().size==1 } }
        val saved=app.repository.dao.tasks().single()
        assertEquals(LocalDate.now().toString(),saved.startDate);assertEquals("学习",saved.category)
        assertEquals("APPLIED",app.repository.dao.message(card.id)!!.state)
    }
    private fun daoValue(block: suspend ()->Boolean) = runBlocking { block() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Compose semantics can be ready one compositor frame before UiAutomation sees the pixels.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(700)
        val file=File(app.getExternalFilesDir(null),"screenshots/$name.png").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().also { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
    }
}
