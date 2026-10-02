package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import top.sywyar.pixivdownload.guicompose.DocumentDialog
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import java.io.File
import java.util.Properties
import java.util.function.Function
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DirectorySuggestionLayoutTest {
    @Test
    @DisplayName("低矮与窄窗口保留目录确认及取消按钮，确认派发保存请求")
    fun actionsRemainVisibleInSmallWindows() {
        for ((width, height) in listOf(900 to 400, 440 to 520)) runSkikoComposeUiTest(
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            val labels = Properties().apply {
                File("../pixivdownload-app/src/main/resources/i18n/messages_en.properties")
                    .reader(Charsets.UTF_8).use(::load)
            }
            val requests = java.util.concurrent.LinkedBlockingQueue<Any>()
            val calls = HashMap<String, Function<Array<Any>, Any>>()
            calls["guiPostJson"] = Function {
                requests.add(it[1])
                DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(mapOf("code" to "SAVED")), "", false)
            }
            val model = DesktopConfigurationControllerTest.model(HashMap(), calls)
            model.directorySuggestions.refresh(DesktopDirectorySuggestionControllerTest.snapshot())
            var snapshot by mutableStateOf(model.snapshot())
            val subscription = model.subscribeSnapshots { snapshot = it }
            try {
                setContent {
                    PixivDownloaderTheme(if (width == 900) "dark" else "light") {
                        Box(Modifier.fillMaxSize()) {
                            snapshot.document().dialogs().firstOrNull()?.let { dialog ->
                                DocumentDialog(dialog,
                                    { labels.getProperty(it.key(), it.fallback()) }, "Close",
                                    { model.dispatch(snapshot, it) }, snapshot.revision())
                            }
                        }
                    }
                }
                onNodeWithText(labels.getProperty("desktop.ui.action.cancel")).assertIsDisplayed()
                assertTrue(requests.isEmpty())
                onNodeWithText(labels.getProperty("gui.directory-suggestion.confirm"))
                    .assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5000) { requests.isNotEmpty() }
            } finally {
                subscription.close()
                model.close()
            }
        }
    }
}
