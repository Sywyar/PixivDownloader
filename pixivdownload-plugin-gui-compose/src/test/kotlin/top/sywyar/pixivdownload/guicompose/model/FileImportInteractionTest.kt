package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import java.nio.file.*
import java.text.MessageFormat
import java.util.Properties
import java.util.function.Function
import javax.imageio.ImageIO
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FileImportInteractionTest {
    @Test
    @DisplayName("窄窗口放大文字下，历史导入表单可操作且提交不会显示凭据")
    fun compactWorkspace() = runSkikoComposeUiTest(size = Size(600f, 680f), density = Density(1f, 1.25f)) {
        val messages = Properties()
        Files.newBufferedReader(Path.of("../pixivdownload-app/src/main/resources/i18n/messages_en.properties")).use { messages.load(it) }
        fun resolve(token: TextToken) = if (token.key().isBlank()) token.fallback() else
            MessageFormat(messages.getProperty(token.key(), token.fallback())).format(token.arguments().toTypedArray())
        val fixture = DesktopFileImportControllerTest.Fixture()
        val overrides = mapOf<String, Function<Array<Any>, Any>>(
            "fileImportTools" to Function { fixture.tools.get() },
            "fileImportTool" to Function { fixture.source },
            "message" to Function { messages.getProperty(it[0].toString(), it[0].toString()) },
        )
        DesktopConfigurationControllerTest.model(HashMap(), overrides).use { model ->
            var snapshot by mutableStateOf(model.snapshot())
            model.subscribeSnapshots { snapshot = it }.use {
                setContent {
                    PixivDownloaderTheme("dark") {
                        Box(Modifier.size(600.dp, 680.dp).background(LocalExperiencePalette.current.surface)) {
                            ComposeDesktopUiNodeRenderer.Render(snapshot.document().pages().first { it.id() == "tools" }.content(),
                                ::resolve, { model.dispatch(snapshot, it) })
                        }
                    }
                }
                val prefix = DesktopFileImportControllerTest.PREFIX
                onNodeWithTag("tools.entry.$prefix").performScrollTo().performClick()
                onNodeWithTag("$prefix.file").performScrollTo().performTextInput("/records.json")
                onNodeWithTag("$prefix.root").performScrollTo().performTextInput("/original")
                onNodeWithTag("$prefix.credential").performScrollTo().performTextInput("private-cookie")
                onNodeWithTag("$prefix.inspect").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("$prefix.start").fetchSemanticsNodes().any { SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Disabled, Unit).matches(it).not() } }
                assertEquals(0, fixture.starts.get())
                onNodeWithTag("$prefix.start").assertIsDisplayed().assertIsEnabled()
                val output = Path.of("target/tools-ui/history-compose.png")
                Files.createDirectories(output.parent)
                ImageIO.write(onNodeWithTag("tools.import.workspace").captureToImage().toAwtImage(), "png", output.toFile())
                onNodeWithTag("$prefix.start").performClick()
                waitUntil(timeoutMillis = 5_000) { fixture.starts.get() == 1 }
                onNodeWithTag("$prefix.credential").assertTextEquals("")
                onNodeWithTag("$prefix.cancel").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { fixture.state.get().phase() == top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.Phase.CANCELLED }
            }
        }
    }
}
