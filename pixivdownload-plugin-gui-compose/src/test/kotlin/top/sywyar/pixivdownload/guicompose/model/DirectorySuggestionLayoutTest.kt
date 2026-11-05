package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import javax.imageio.ImageIO
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import top.sywyar.pixivdownload.guicompose.DocumentDialog
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.DesktopShell
import java.io.File
import java.util.Properties
import java.util.function.Function
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DirectorySuggestionLayoutTest {
    @Test
    @DisplayName("不同窗口与字体大小下路径先可见，展开说明保留草稿且保存操作始终可达")
    fun actionsRemainVisibleInSmallWindows() {
        data class Scenario(val width: Int, val height: Int, val suffix: String, val scale: Float, val dark: Boolean)
        for ((width, height, suffix, scale, dark) in listOf(
            Scenario(1120, 760, "", 1f, true),
            Scenario(900, 400, "_en", 1f, false),
            Scenario(440, 620, "_en", 1.25f, true),
            Scenario(900, 650, "", 1.5f, false),
        )) runSkikoComposeUiTest(
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            val labels = Properties().apply {
                File("../pixivdownload-app/src/main/resources/i18n/messages$suffix.properties")
                    .reader(Charsets.UTF_8).use(::load)
            }
            val requests = java.util.concurrent.LinkedBlockingQueue<Any>()
            val pluginLabels = Properties().apply {
                File("../pixivdownload-plugin-pixiv-batch-downloader-import/src/main/resources/i18n/web/pixiv-batch-downloader-import$suffix.properties")
                    .reader(Charsets.UTF_8).use(::load)
            }
            val composeLabels = Properties().apply {
                File("src/main/resources/i18n/web/gui-compose$suffix.properties").reader(Charsets.UTF_8).use(::load)
            }
            val calls = HashMap<String, Function<Array<Any>, Any>>()
            calls["guiPostJson"] = Function {
                requests.add(it[1])
                DesktopUiHost.GuiResponse(true, 200, DesktopUiHost.GuiValue.of(mapOf("code" to "SAVED")), "", false)
            }
            val model = DesktopConfigurationControllerTest.model(HashMap(), calls)
            model.directorySuggestions.refresh(DesktopDirectorySuggestionControllerTest.snapshot())
            var snapshot by mutableStateOf(model.snapshot())
            val subscription = model.subscribeSnapshots { snapshot = it }
            val resolve: (top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken) -> String = {
                when {
                    it.key() == "root.help" -> pluginLabels.getProperty("config.root.help")
                    it.namespace() == "gui-compose" -> composeLabels.getProperty(it.key(), it.fallback())
                    else -> labels.getProperty(it.key(), it.fallback())
                }
            }
            try {
                setContent {
                    PixivDownloaderTheme(if (dark) "dark" else "light") {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                        Box(Modifier.fillMaxSize()) {
                            DesktopShell(snapshot.document(), snapshot.revision(), resolve) { model.dispatch(snapshot, it) }
                            snapshot.document().dialogs().firstOrNull()?.let { dialog ->
                                DocumentDialog(dialog,
                                    resolve, labels.getProperty("desktop.ui.action.close"),
                                    { model.dispatch(snapshot, it) }, snapshot.revision())
                            }
                        }
                        }
                    }
                }
                onNodeWithText(labels.getProperty("desktop.ui.action.cancel")).assertIsDisplayed()
                val directoryField = hasSetTextAction() and hasAnyAncestor(hasTestTag("directory-suggestion.content"))
                val input = onNode(directoryField).assertIsDisplayed()
                val inputBounds = input.fetchSemanticsNode().boundsInRoot
                assertTrue(inputBounds.height > 25 && inputBounds.width > 60)
                onNodeWithTag("directory-suggestion.guidance").assertDoesNotExist()
                input.performTextReplacement("D:/chosen")
                System.getenv("PIXIV_DIALOG_SCREENSHOTS")?.let { directory ->
                    val file = File(directory, "directory-$width-$height-collapsed.png")
                    file.parentFile.mkdirs()
                    ImageIO.write(captureToImage().toAwtImage(), "png", file)
                }
                onNodeWithTag("directory-suggestion.details").performScrollTo().performClick()
                onNodeWithTag("directory-suggestion.guidance").assertExists()
                onNode(directoryField).assertTextEquals("D:/chosen")
                onNodeWithText(labels.getProperty("gui.directory-suggestion.confirm")).assertIsDisplayed()
                System.getenv("PIXIV_DIALOG_SCREENSHOTS")?.let { directory ->
                    ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "directory-$width-$height-expanded.png"))
                }
                assertTrue(requests.isEmpty())
                onNodeWithText(labels.getProperty("gui.directory-suggestion.confirm"))
                    .assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5000) { requests.isNotEmpty() }
                assertEquals("D:/chosen", (requests.first() as Map<*, *>)["directory"])
            } finally {
                subscription.close()
                model.close()
            }
        }
    }
}
