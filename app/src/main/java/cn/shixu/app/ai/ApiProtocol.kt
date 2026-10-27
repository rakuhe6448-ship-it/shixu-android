package cn.shixu.app.ai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

data class ModelOption(val id: String,val name: String=id)

object ApiProtocol {
    private fun base(value: String): HttpUrl {
        val url=value.trim().trimEnd('/').toHttpUrlOrNull() ?: error("API 地址格式不正确")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query==null && url.fragment==null) { "API 地址必须使用 HTTPS，且不能包含凭据、参数或片段" }
        return url.newBuilder().encodedPath(url.encodedPath.removeSuffix("/chat/completions").trimEnd('/').ifEmpty { "/" }).build()
    }
    fun chatEndpoint(value: String)=base(value).newBuilder().addPathSegments("chat/completions").build()
    fun modelsEndpoint(value: String)=base(value).newBuilder().addPathSegment("models").build()
    fun validKey(key: String) {
        require(key.isNotBlank()) { "请先填写 API Key" }
        require(key.length<=8192 && key.trim().all { it in ' '..'~' }) { "API Key 包含无效字符，请检查复制内容" }
    }
    fun models(body: String): List<ModelOption> {
        val data=JSONObject(body).optJSONArray("data") ?: error("模型列表格式不兼容，请手动填写模型名称")
        require(data.length()<=2000) { "服务返回的模型数量过多，请手动填写模型名称" }
        val result=(0 until data.length()).mapNotNull { index ->
            val row=data.optJSONObject(index) ?: return@mapNotNull null
            val id=(row.opt("id") as? String)?.trim() ?: return@mapNotNull null
            if(id.isBlank() || id.length>240 || id.any { it.isISOControl() }) return@mapNotNull null
            val name=(row.opt("name") as? String)?.trim()?.takeIf { it.isNotBlank() && it.length<=120 && it.none(Char::isISOControl) } ?: id
            ModelOption(id,name)
        }.distinctBy { it.id }.sortedBy { it.id.lowercase(java.util.Locale.ROOT) }
        require(result.isNotEmpty()) { "服务未返回可用模型，请手动填写模型名称" }
        return result
    }
}
