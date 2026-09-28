package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.ComposeDesktopUiNodeRenderer
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool
import java.io.File
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties
import java.util.function.Function
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MediaToolsInteractionTest {
    @Test
    @DisplayName("中文工具目录与媒体工作面板衔接，格式变更必须重新预览")
    fun chineseWorkspace() = exercise("light", Locale.SIMPLIFIED_CHINESE, 1200, 860, 1f)

    @Test
    @DisplayName("窄窗口深色与放大文字下，媒体主操作可见且支持键盘关闭与任务恢复")
    fun compactWorkspace() = exercise("dark", Locale.US, 560, 680, 1.25f)

    private fun exercise(theme: String, locale: Locale, width: Int, height: Int, fontScale: Float) = runSkikoComposeUiTest(
        size = Size(width.toFloat(), height.toFloat()),
        density = Density(1f, fontScale),
        effectContext = object : MotionDurationScale { override val scaleFactor = 0f },
    ) {
        val messages = messages(locale)
        fun resolve(token: DesktopUiNode.TextToken): String {
            if (token.key().isBlank()) return token.fallback()
            val value = messages.getProperty(token.key(), token.fallback())
            return MessageFormat(value, locale).format(token.arguments().toTypedArray())
        }
        val fixture = DesktopMediaToolsControllerTest.Fixture()
        fixture.tools.set(listOf(DesktopMediaTool(
            fixture.tool.identity(),
            DesktopMediaTool.Description(DesktopUiText("batch", "media.tools.title", "", emptyList()), "batch", "original", "webp"),
        )))
        val overrides = mapOf<String, Function<Array<Any>, Any>>(
            "mediaTools" to Function { fixture.tools.get() },
            "mediaTool" to Function { fixture.source },
            "message" to Function { messages.getProperty(it[0].toString(), it[0].toString()) },
        )
        DesktopConfigurationControllerTest.model(HashMap(), overrides).use { model ->
            var snapshot by mutableStateOf(model.snapshot())
            val subscription = model.subscribeSnapshots { snapshot = it }
            val prefix = "media.sample.1"
            try {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        PixivDownloaderTheme(theme) {
                            Box(Modifier.size(width.dp, height.dp).background(LocalExperiencePalette.current.surface)) {
                                val page = snapshot.document().pages().first { it.id() == "tools" }
                                ComposeDesktopUiNodeRenderer.Render(page.content(), ::resolve, { model.dispatch(snapshot, it) })
                            }
                        }
                    }
                }
                onNodeWithTag(prefix + ".ids").assertDoesNotExist()
                onNodeWithTag("tools.entry.media").performScrollTo()
                screenshot("catalogue-$theme", "tools.overview")
                onNodeWithTag("tools.entry.media").performClick()
                onNodeWithTag(prefix + ".preview").assertIsDisplayed().assertIsEnabled()
                onNodeWithTag(prefix + ".preview").performClick()
                waitUntil(timeoutMillis = 5_000) { fixture.request.get() != null }
                onNodeWithTag(prefix + ".start").assertIsDisplayed()
                assertEquals(0, fixture.starts.get())
                onNodeWithContentDescription(messages.getProperty("media.image-formats.label")).performScrollTo().performClick()
                onNodeWithText("PNG").performClick()
                onNodeWithText("PNG").performKeyInput { pressKey(Key.Escape) }
                onNodeWithText("PNG").assertDoesNotExist()
                onNodeWithTag(prefix + ".start").assertDoesNotExist()
                waitForIdle()
                val selectedFormats = DesktopMediaToolsControllerTest.nodes(model).filter { it.id() == prefix + ".images" }.findFirst().get() as DesktopUiNode.Choice
                assertTrue(selectedFormats.selectedIds().contains("png"), selectedFormats.selectedIds().toString())
                onNodeWithTag(prefix + ".preview").assertIsDisplayed().assertIsEnabled()
                screenshot("form-$theme", "tools.media.workspace")
                onNodeWithTag(prefix + ".preview").performClick()
                waitUntil(timeoutMillis = 5_000) { fixture.request.get().imageFormats().contains("png") }
                onNodeWithText("42 / 0  source.jpg").performScrollTo().assertIsDisplayed()
                screenshot("preview-$theme", "tools.media.workspace")
                onNodeWithTag(prefix + ".start").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { fixture.starts.get() == 1 }
                onNodeWithTag(prefix + ".cancel").assertIsDisplayed()
                screenshot("running-$theme", "tools.media.workspace")
                onNodeWithTag(prefix + ".cancel").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                onNodeWithTag("tools.media.workspace").performKeyInput { pressKey(Key.Escape) }
                onNodeWithTag("tools.media.workspace").assertDoesNotExist()
                assertEquals("running", fixture.state.get().state())
                onNodeWithTag("tools.entry.media").performClick()
                onNodeWithTag(prefix + ".cancel").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { fixture.state.get().state() == "cancelled" }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(prefix + ".preview").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("tools.media.tab.ffmpeg").performClick()
                onNodeWithTag("tools.media.advanced").performScrollTo().performClick()
                onNodeWithTag("tools.ffmpeg.path").performScrollTo().assertIsDisplayed()
                onNodeWithText(messages.getProperty("gui.config.field.ffmpeg.executable-path.help")).assertDoesNotExist()
                onNodeWithTag(prefix + ".results.toggle").assertDoesNotExist()
                onNodeWithTag(prefix + ".check").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("ffmpeg (system)").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("ffmpeg (system)").performScrollTo().assertIsDisplayed()
                screenshot("capabilities-$theme", "tools.media.workspace")
                onNodeWithTag(prefix + ".results.toggle")
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
                    .performScrollTo().performClick()
                onNodeWithText("ffmpeg (system)").assertDoesNotExist()
                onNodeWithText(messages.getProperty("media.capability.png")).assertDoesNotExist()
                runOnIdle { model.rebuild() }
                onNodeWithTag(prefix + ".results").assertDoesNotExist()
                onNodeWithTag(prefix + ".check").assertIsDisplayed().assertIsEnabled()
                screenshot("capabilities-collapsed-$theme", "tools.media.workspace")
                onNodeWithTag(prefix + ".results.toggle")
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
                    .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                    .performKeyInput { pressKey(Key.Enter) }
                onNodeWithText("ffmpeg (system)").performScrollTo().assertIsDisplayed()
                onNodeWithTag(prefix + ".results.toggle").performScrollTo().performClick()
                onNodeWithTag(prefix + ".check").performScrollTo().performClick()
                onNodeWithText("ffmpeg (system)").performScrollTo().assertIsDisplayed()
                runOnIdle { fixture.tools.set(emptyList()); model.rebuild() }
                onNodeWithTag("tools.media.workspace").assertExists()
                onNodeWithTag("tools.media.tab.$prefix").assertDoesNotExist()
                onNodeWithTag("tools.media.back").performClick()
                onNodeWithTag("tools.entry.media").assertIsDisplayed()
                assertTrue(fixture.request.get().repairThumbnails())
            } finally { subscription.close() }
        }
    }

    private fun ComposeUiTest.screenshot(name: String, tag: String) {
        val file = File("target/tools-ui/media-$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", file)
    }

    private fun messages(locale: Locale) = Properties().apply {
        val suffix = if (locale == Locale.SIMPLIFIED_CHINESE) "" else "_en"
        MediaToolsInteractionTest::class.java.getResourceAsStream("/i18n/web/gui-compose$suffix.properties")!!
            .reader(Charsets.UTF_8).use(::load)
        File("../pixivdownload-plugin-download-workbench/src/main/resources/i18n/web/batch$suffix.properties")
            .reader(Charsets.UTF_8).use(::load)
        File("../pixivdownload-app/src/main/resources/i18n/messages$suffix.properties").takeIf { it.exists() }
            ?.reader(Charsets.UTF_8)?.use(::load)
    }
}
