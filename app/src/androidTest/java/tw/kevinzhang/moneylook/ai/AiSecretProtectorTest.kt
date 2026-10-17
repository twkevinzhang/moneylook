package tw.kevinzhang.moneylook.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiSecretProtectorTest {
    @Test fun isolatedSettingsRoundTripHasNoPlaintextAndRejectsCorruptedCiphertext() = kotlinx.coroutines.runBlocking {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("ai_test_credentials",android.content.Context.MODE_PRIVATE)
        val isolated=object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String,mode: Int): android.content.SharedPreferences = preferences
        }
        try {
            val store=AiSettingsStore(isolated,AiSecretProtector())
            val settings=AiSettings(AiProvider.CUSTOM,"test-model","https://test.example/v1","synthetic-api-secret","synthetic-id","synthetic-cf-secret")
            store.save(settings)
            assertFalse(preferences.all.toString().contains("synthetic-api-secret"))
            assertFalse(preferences.all.toString().contains("synthetic-cf-secret"))
            assertEquals(settings,(store.load() as AiSettingsLoadResult.Ready).settings)
            preferences.edit().putString("encrypted","corrupt").commit()
            assertEquals(AiSettingsLoadResult.CredentialsUnavailable,store.load())
        } finally { preferences.edit().clear().commit() }
    }
    @Test fun keystoreRoundTripUsesFreshIvAndRejectsTampering() {
        val protector=AiSecretProtector()
        val first=protector.encrypt("synthetic-api-token")
        val second=protector.encrypt("synthetic-api-token")
        assertNotEquals(first,second)
        assertEquals("synthetic-api-token",protector.decrypt(first))
        val bytes=android.util.Base64.decode(first,android.util.Base64.NO_WRAP)
        bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        try {
            protector.decrypt(android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
            fail("modified ciphertext accepted")
        } catch (_: java.security.GeneralSecurityException) { }
    }
}
