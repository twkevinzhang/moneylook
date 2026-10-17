package tw.kevinzhang.moneylook.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tw.kevinzhang.moneylook.ai.*
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme

@RunWith(AndroidJUnit4::class)
class AiSettingsInteractionTest {
    @get:Rule val compose=createComposeRule()
    @Test fun customEndpointAndAccessFieldsAreEditableAndBusyTestCannotRepeat() {
        var state by mutableStateOf(AiSettingsUiState(AiSettings(AiProvider.CUSTOM,"test-model","https://vm.example/v1","test-token")))
        var calls=0
        compose.setContent { MoneylookTheme { AiSettingsScreen(state,{state=state.copy(settings=it)},{},{calls++;state=state.copy(busy=true)},{}) } }
        compose.onNodeWithText("AI分類設定").assertExists()
        val preview=compose.onRoot().captureToImage().asAndroidBitmap()
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        context.cacheDir.resolve("ai-settings-preview.png").outputStream().use { preview.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        compose.onNodeWithText("Base URL").performTextReplacement("https://vm.example/proxy/v1")
        compose.onNodeWithText("Client ID").performScrollTo().performTextInput("client-id")
        compose.onNodeWithText("Client Secret").performScrollTo().performTextInput("client-secret")
        compose.onNodeWithText("測試連線").performScrollTo().performClick()
        compose.onNodeWithText("測試連線").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1,calls)
            assertEquals("https://vm.example/proxy/v1",state.settings.baseUrl)
            assertEquals("client-secret",state.settings.accessClientSecret)
        }
    }
}
