package cn.shixu.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ApiProviders {
    const val COMPATIBLE="COMPATIBLE"
    const val DEEPSEEK="DEEPSEEK"
    val labels=linkedMapOf(COMPATIBLE to "OpenAI 兼容",DEEPSEEK to "DeepSeek")
    fun preset(provider: String)=ApiProfile(endpoint=if(provider==DEEPSEEK) "https://api.deepseek.com/v1" else "")
}
data class ApiProfile(val endpoint: String="",val model: String="",val vision: Boolean=false,val jsonMode: Boolean=true) {
    fun entries(provider: String)=mapOf("endpoint" to endpoint,"model" to model,"vision" to vision.toString(),"jsonMode" to jsonMode.toString()).mapKeys { "apiProfile.$provider.${it.key}" }
}
data class Preferences(
    val endpoint: String = "",
    val model: String = "",
    val vision: Boolean = false,
    val jsonMode: Boolean = true,
    val personality: String = "STANDARD",
    val customPrompt: String = "",
    val theme: String = "SYSTEM",
    val accent: Long = 0xFF66785B,
    val background: Long = 0xFFF7F8F2,
    val widgetOpacity: Float = .94f,
    val provider: String = ApiProviders.COMPATIBLE
) {
    fun apiProfile()=ApiProfile(endpoint,model,vision,jsonMode)
    fun withProfile(provider: String,profile: ApiProfile)=copy(provider=provider,endpoint=profile.endpoint,model=profile.model,vision=profile.vision,jsonMode=profile.jsonMode)
    fun entries(): Map<String, String> = mapOf("provider" to provider,"endpoint" to endpoint,"model" to model,"vision" to vision.toString(),"jsonMode" to jsonMode.toString(),"personality" to personality,"customPrompt" to customPrompt,"theme" to theme,"accent" to accent.toString(),"background" to background.toString(),"widgetOpacity" to widgetOpacity.toString())
    companion object {
        fun from(entries: List<SettingEntity>): Preferences {
            val m = entries.associate { it.key to it.value }
            val d = Preferences()
            val legacyEndpoint=m["endpoint"].orEmpty()
            val provider=m["provider"]?.takeIf { it in ApiProviders.labels } ?: if(runCatching { java.net.URI(legacyEndpoint).host }.getOrNull()=="api.deepseek.com") ApiProviders.DEEPSEEK else ApiProviders.COMPATIBLE
            val endpoint=if(provider==ApiProviders.DEEPSEEK && legacyEndpoint.trimEnd('/')=="https://api.deepseek.com") "https://api.deepseek.com/v1" else legacyEndpoint
            return Preferences(endpoint,m["model"] ?: d.model,m["vision"]?.toBooleanStrictOrNull() ?: false,m["jsonMode"]?.toBooleanStrictOrNull() ?: true,m["personality"] ?: d.personality,m["customPrompt"] ?: "",m["theme"] ?: d.theme,m["accent"]?.toLongOrNull() ?: d.accent,m["background"]?.toLongOrNull() ?: d.background,m["widgetOpacity"]?.toFloatOrNull()?.coerceIn(0f,1f) ?: d.widgetOpacity,provider)
        }
    }
}

class KeyVault(private val context: Context) {
    private fun storage(provider: String): android.content.SharedPreferences {
        require(provider in ApiProviders.labels) { "未知 API 类型" }
        return context.getSharedPreferences("private_api_key_$provider",Context.MODE_PRIVATE)
    }
    @Synchronized fun migrateLegacy(provider: String) {
        val legacy=context.getSharedPreferences("private_api_key",Context.MODE_PRIVATE)
        val ciphertext=legacy.getString("ciphertext",null) ?: return
        val target=storage(provider)
        if(!target.contains("ciphertext")) check(target.edit().putString("ciphertext",ciphertext).putString("iv",legacy.getString("iv","")).commit()) { "API Key 配置迁移失败" }
        check(legacy.edit().clear().commit()) { "API Key 配置迁移失败" }
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("shixu.api", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("shixu.api",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(provider: String): String {
        val storage=storage(provider)
        val encrypted = storage.getString("ciphertext", null) ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(storage.getString("iv", ""),Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted,Base64.NO_WRAP)),Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("API Key 无法解密，请在设置中重新填写") }
    }
    fun write(provider: String,value: String) {
        val storage=storage(provider)
        if (value.isBlank()) { check(storage.edit().clear().commit()) { "API Key 清除失败" }; return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        check(storage.edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).putString("ciphertext",Base64.encodeToString(cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)).commit()) { "API Key 保存失败" }
    }
}

object Personality {
    val styles = linkedMapOf("STANDARD" to "标准助手", "ROYAL" to "御前小管家", "CAT" to "宠物猫", "BUTLER" to "沉稳管家", "CUSTOM" to "自定义")
    fun prompt(p: Preferences): String = when (p.personality) {
        "ROYAL" -> "使用轻松的宫廷侍从语气，不扮演固定历史人物。"
        "CAT" -> "使用温柔活泼的小猫语气。"
        "BUTLER" -> "使用沉稳简洁的管家语气。"
        "CUSTOM" -> p.customPrompt.take(2000)
        else -> "简洁、友好、准确。"
    }
    fun notification(p: Preferences, t: TaskEntity): String {
        val detail = t.dueDate?.let { "截止：$it${t.dueTime?.let { time -> " $time" } ?: ""}" } ?: "记得处理这件事"
        return when (p.personality) {
            "ROYAL" -> "陛下，${t.title}该留意啦。$detail"
            "CAT" -> "喵～${t.title}，记得啦！$detail"
            "BUTLER" -> "事项提醒：${t.title}。$detail"
            else -> "${t.title} · $detail"
        }
    }
}
