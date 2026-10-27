package cn.shixu.app

import cn.shixu.app.ai.*
import cn.shixu.app.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ApiProtocolTest {
    @Test fun endpointsPreserveVersionAndAcceptFullChatUrl() {
        assertEquals("https://service.example/v1/models",ApiProtocol.modelsEndpoint("https://service.example/v1/").toString())
        assertEquals("https://service.example/v1/models",ApiProtocol.modelsEndpoint("https://service.example/v1/chat/completions").toString())
        assertEquals("https://service.example/chat/completions",ApiProtocol.chatEndpoint("https://service.example").toString())
        assertEquals("https://service.example/api/v1/chat/completions",ApiProtocol.chatEndpoint("https://service.example/api/v1").toString())
        assertThrows(IllegalArgumentException::class.java) { ApiProtocol.modelsEndpoint("http://service.example/v1") }
        assertThrows(IllegalArgumentException::class.java) { ApiProtocol.modelsEndpoint("https://user:secret@service.example/v1") }
    }
    @Test fun modelsAreValidatedDeduplicatedAndSorted() {
        val models=ApiProtocol.models("""{"data":[{"id":"z"},{"id":"a","name":"模型 A"},{"id":"z"},{"id":99},{"id":""}]}""")
        assertEquals(listOf("a","z"),models.map { it.id });assertEquals("模型 A",models.first().name)
        assertThrows(IllegalArgumentException::class.java) { ApiProtocol.models("""{"data":[]}""") }
    }
    @Test fun genericIsBlankAndLegacyPresetMigratesWithoutBrandingPersona() {
        assertEquals("",Preferences().endpoint)
        assertEquals("https://api.deepseek.com/v1",ApiProviders.preset(ApiProviders.DEEPSEEK).endpoint)
        val legacy=Preferences.from(listOf(SettingEntity("endpoint","https://api.deepseek.com"),SettingEntity("model","saved-model")))
        assertEquals(ApiProviders.DEEPSEEK,legacy.provider);assertEquals("https://api.deepseek.com/v1",legacy.endpoint);assertEquals("saved-model",legacy.model)
        assertEquals(ApiProviders.COMPATIBLE,Preferences.from(listOf(SettingEntity("endpoint","https://custom.example/v1"))).provider)
        assertEquals("沉稳管家",Personality.styles["BUTLER"])
    }
    @Test fun modelRequestUsesAuthenticatedGetAndExpectedPath() = runBlocking {
        val client=AiClient(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("GET",chain.request().method);assertEquals("/v1/models",chain.request().url.encodedPath)
            assertEquals("Bearer test-key",chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("""{"data":[{"id":"chat-model"}]}""".toResponseBody()).build()
        }.build())
        assertEquals("chat-model",client.models("https://service.example/v1","test-key").single().id)
    }
    @Test fun unsupportedListHasManualFallbackAndRateLimitRetriesOnlyOnce() = runBlocking {
        val count=AtomicInteger()
        fun client(code: Int)=AiClient(OkHttpClient.Builder().addInterceptor { chain ->
            count.incrementAndGet();Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("failure").body("{}".toResponseBody()).build()
        }.build())
        try { client(404).models("https://service.example/v1","key");fail() } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("手动填写")) }
        assertEquals(1,count.get());count.set(0)
        try { client(429).models("https://service.example/v1","key");fail() } catch(e: ApiException) { assertEquals(429,e.status) }
        assertEquals(2,count.get());count.set(0)
        try { client(401).models("https://service.example/v1","key");fail() } catch(e: ApiException) { assertEquals(401,e.status) }
        assertEquals(1,count.get())
    }
}
