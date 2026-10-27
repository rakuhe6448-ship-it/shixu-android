package cn.shixu.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import cn.shixu.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class ApiProfileIntegrationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun separatelySavedProfilesSurviveSwitching() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,PlannerDatabase::class.java).build()
        try {
            val repo=Repository(db)
            repo.saveSettings(Preferences(endpoint="https://custom.example/v1",model="custom"))
            assertEquals("https://api.deepseek.com/v1",repo.apiProfile(ApiProviders.DEEPSEEK).endpoint)
            repo.saveSettings(Preferences().withProfile(ApiProviders.DEEPSEEK,ApiProviders.preset(ApiProviders.DEEPSEEK).copy(model="selected-model")))
            assertEquals("custom",repo.apiProfile(ApiProviders.COMPATIBLE).model)
            assertEquals("https://custom.example/v1",repo.apiProfile(ApiProviders.COMPATIBLE).endpoint)
            assertEquals("selected-model",repo.apiProfile(ApiProviders.DEEPSEEK).model)
        } finally { db.close() }
    }
    @Test fun encryptedKeysAreIsolatedAndLegacyCiphertextMigrates() {
        // Instrumentation uses synthetic keys only, and restores preferences afterward.
        val names=listOf("private_api_key","private_api_key_COMPATIBLE","private_api_key_DEEPSEEK")
        val stores=names.map { context.getSharedPreferences(it,0) }
        val snapshots=stores.map { it.all.toMap() }
        try {
            stores.forEach { it.edit().clear().commit() }
            val vault=KeyVault(context)
            vault.write(ApiProviders.COMPATIBLE,"test-generic")
            vault.write(ApiProviders.DEEPSEEK,"test-preset")
            assertEquals("test-generic",vault.read(ApiProviders.COMPATIBLE));assertEquals("test-preset",vault.read(ApiProviders.DEEPSEEK))
            val legacy=stores[0];val preset=stores[2]
            legacy.edit().putString("iv",preset.getString("iv",null)).putString("ciphertext",preset.getString("ciphertext",null)).commit()
            preset.edit().clear().commit()
            vault.migrateLegacy(ApiProviders.DEEPSEEK)
            assertEquals("test-preset",vault.read(ApiProviders.DEEPSEEK));assertEquals("test-generic",vault.read(ApiProviders.COMPATIBLE))
            assertTrue(legacy.all.isEmpty());assertFalse(preset.getString("ciphertext","")!!.contains("test-preset"))
        } finally { stores.zip(snapshots).forEach { (store,snapshot) -> store.edit().clear().apply { snapshot.forEach { (key,value) -> putString(key,value as String) } }.commit() } }
    }
}
