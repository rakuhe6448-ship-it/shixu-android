package cn.shixu.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.shixu.app.ai.Attachments
import cn.shixu.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupIntegrationTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiXuApp
    @Before fun before() = runBlocking { app.db.withTransaction { app.repository.dao.clearDraftLinks();app.repository.dao.clearDrafts();app.repository.dao.clearLinks();app.repository.dao.clearMessages();app.repository.dao.clearHistory();app.repository.dao.clearReminders();app.repository.dao.clearTasks() } }
    @Test fun roundTripIncludesImagesLinksHistoryAndKeepsLocalConnection() = runBlocking {
        val repo=app.repository
        val task=TaskEntity(title="备份验收",dueDate="2030-11-06")
        repo.save(TaskBundle(task,emptyList()),null)
        val imageName=UUID.randomUUID().toString()+".jpg"
        val bitmap=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888)
        Attachments.file(app,imageName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,80,it) };bitmap.recycle()
        val message=MessageEntity(role="user",text="识别公告",sharedText="外部分享的公告文字",imagePaths=JSONArray(listOf(imageName)).toString())
        repo.putMessage(message);repo.dao.putLink(MessageTask(message.id,task.id))
        repo.saveSettings(Preferences(provider=ApiProviders.DEEPSEEK,endpoint="https://api.deepseek.com/v1",model="preset-model"))
        repo.saveSettings(Preferences(endpoint="https://original.example/v1",model="local-model"))
        val backup=Backup(app);val file=File(app.cacheDir,"roundtrip.json");val uri=Uri.fromFile(file)
        backup.export(uri)
        val exported=JSONObject(file.readText())
        assertFalse(exported.toString().contains("apiKey"))
        // Exercise the previous backup format while retaining the current local profiles.
        exported.put("version",1);exported.remove("drafts");exported.remove("draftLinks")
        exported.getJSONObject("settings").put("endpoint","https://untrusted.example/v1").put("model","imported-model").put("provider",ApiProviders.DEEPSEEK).put("apiProfile.DEEPSEEK.endpoint","https://untrusted.example/v1")
        file.writeText(exported.toString())
        val data=backup.read(uri)
        repo.save(TaskBundle(TaskEntity(title="将被替换"),emptyList()),null)
        backup.restore(data)
        assertEquals(1,repo.dao.tasks().size)
        assertEquals(task.id,repo.dao.linksFor(message.id).single().taskId)
        assertEquals(1,repo.dao.historyFor(task.id).size)
        assertEquals("外部分享的公告文字",repo.dao.message(message.id)!!.sharedText)
        val restoredName=JSONArray(repo.dao.message(message.id)!!.imagePaths).getString(0)
        assertTrue(Attachments.file(app,restoredName).exists())
        val p=Preferences.from(repo.dao.settings())
        assertEquals("https://original.example/v1",p.endpoint)
        assertEquals("local-model",p.model)
        assertEquals(ApiProviders.COMPATIBLE,p.provider)
        assertEquals("https://api.deepseek.com/v1",repo.apiProfile(ApiProviders.DEEPSEEK).endpoint)
        assertEquals("preset-model",repo.apiProfile(ApiProviders.DEEPSEEK).model)
        repo.saveSettings(Preferences())
    }
    @Test fun invalidImportDoesNotReplaceExistingData() = runBlocking {
        val task=TaskEntity(title="保留原有数据")
        app.repository.save(TaskBundle(task,emptyList()),null)
        val backup=Backup(app);val file=File(app.cacheDir,"invalid.json");val uri=Uri.fromFile(file)
        backup.export(uri)
        val root=JSONObject(file.readText());root.getJSONArray("tasks").put(root.getJSONArray("tasks").getJSONObject(0));file.writeText(root.toString())
        try { backup.read(uri); fail("Duplicate IDs accepted") } catch(_: IllegalArgumentException) { }
        assertEquals(task.id,app.repository.dao.tasks().single().id)
    }
    @Test fun pendingDraftRoundTripRetainsLinksButRequiresNewConfirmation() = runBlocking {
        val repo=app.repository
        val source=MessageEntity(role="user",text="抢报名额");repo.putMessage(source)
        val raw=JSONObject().put("protocolVersion",2).put("operations",JSONArray().put(JSONObject().put("target","NEW").put("action","PATCH").put("fields",JSONObject().put("title","抢报名额").put("dueDate","2030-11-02").put("dueTime","15:00"))))
        val answer=repo.acceptAiResult(raw,source.id,repo.conversationContext(source),"核对")
        val id=repo.dao.drafts().single().id
        val backup=Backup(app);val file=File(app.cacheDir,"draft-roundtrip.json")
        backup.export(Uri.fromFile(file));backup.restore(backup.read(Uri.fromFile(file)))
        assertTrue(repo.dao.tasks().isEmpty());assertTrue(repo.dao.reminders().isEmpty())
        assertEquals("CONFLICT",repo.dao.draft(id)!!.status)
        assertEquals(id,repo.dao.draftsForMessage(source.id).single().draftId)
        assertEquals("DISMISSED",repo.dao.message(answer.id)!!.state)
        val renewed=repo.previewDraft(id)
        repo.applyProposal(renewed.id)
        assertEquals(1,repo.dao.tasks().size)
    }
    @Test fun phoneAlarmBackupRestoresHistoryAndNeverReplaysClockActions() = runBlocking {
        val repo=app.repository
        val source=MessageEntity(role="user",text="设置7点手机闹钟");repo.putMessage(source)
        val raw=JSONObject().put("protocolVersion",2).put("operations",JSONArray().put(JSONObject().put("action","SET_ALARM").put("alarm",JSONObject().put("time","07:00"))))
        val answer=repo.acceptAiResult(raw,source.id,repo.conversationContext(source),"")
        val backup=Backup(app);val uri=Uri.fromFile(File(app.cacheDir,"clock-roundtrip.json"))
        backup.export(uri);backup.restore(backup.read(uri))
        assertEquals("DISMISSED",repo.dao.message(answer.id)!!.state)
        assertEquals("07:00",cn.shixu.app.ai.PhoneAlarm.fromProposal(repo.dao.message(answer.id)!!.proposal!!)!!.time)
        try { repo.handoffPhoneAlarm(answer.id) { fail("Imported clock action executed") };fail("Imported action accepted") } catch(_: IllegalStateException) { }
        assertTrue(repo.dao.tasks().isEmpty());assertTrue(repo.dao.reminders().isEmpty())
    }
}
