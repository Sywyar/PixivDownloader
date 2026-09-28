package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import top.sywyar.pixivdownload.guicompose.DocumentDialog
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.io.File
import java.util.Properties
import java.util.function.Function
import javax.imageio.ImageIO
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RepositoryImportLayoutTest {
    @Test
    @DisplayName("完整浅色弹窗随内容收紧并保留双模式草稿和信任确认")
    fun modesAndConfirmationFitNormalWindow() = checkLayout(1120, 900)

    @Test
    @DisplayName("完整深色弹窗保持紧凑的表单和常驻操作")
    fun modesAndConfirmationFitDarkWindow() = checkLayout(1120, 900, theme = "dark")

    @Test
    @DisplayName("窄窗口内两种填写方式与确认操作均可达")
    fun modesAndConfirmationFitNarrowWindow() = checkLayout(440, 520)

    @Test
    @DisplayName("长译文和放大文字不遮挡确认操作")
    fun longLabelsAndLargerTextKeepConfirmationReachable() = checkLayout(440, 520, "_en", 1.3f)

    @Test
    @DisplayName("低矮窗口保留标题和操作且表单可滚动")
    fun shortWindowKeepsActionsReachable() = checkLayout(900, 400)

    private fun checkLayout(width: Int, height: Int, localeSuffix: String = "", fontScale: Float = 1f, theme: String = "light") {
        val labels = Properties().apply {
            File("../pixivdownload-app/src/main/resources/i18n/messages$localeSuffix.properties")
                .reader(Charsets.UTF_8).use(::load)
        }
        val composeLabels = Properties().apply {
            File("src/main/resources/i18n/web/gui-compose$localeSuffix.properties")
                .reader(Charsets.UTF_8).use(::load)
        }
        runSkikoComposeUiTest(
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            val calls = HashMap(RepositoryImportInteractionTest.defaults())
            calls["message"] = Function { labels.getProperty(it[0] as String, it[0] as String) }
            calls["previewPluginRepository"] = Function { RepositoryImportInteractionTest.preview() }
            val model = DesktopConfigurationControllerTest.model(HashMap(), calls)
            synchronized(model) {
                model.dispatch(model.snapshot(), DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE,
                    "config.market.repository.add", DesktopUiNode.Value.empty()))
            }
            var snapshot by mutableStateOf(model.snapshot())
            val subscription = model.subscribeSnapshots { snapshot = it }
            try {
                setContent {
                    PixivDownloaderTheme(theme) {
                        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                            Box(Modifier.fillMaxSize()) {
                                snapshot.document().dialogs().firstOrNull()?.let { dialog ->
                                    DocumentDialog(
                                        dialog = dialog,
                                        text = { token ->
                                            if (token.key().isNullOrBlank()) token.fallback()
                                            else (if (token.namespace() == "gui-compose") composeLabels else labels)
                                                .getProperty(token.key(), token.fallback())
                                        },
                                        closeLabel = "Close",
                                        emit = { model.dispatch(snapshot, it) },
                                        documentRevision = snapshot.revision(),
                                    )
                                }
                            }
                        }
                    }
                }
                val descriptorLabel = labels.getProperty("gui.config.market.repo.mode.descriptor")
                fun screenshot(state: String) {
                    System.getenv("PIXIV_REPOSITORY_SCREENSHOTS")?.let { directory ->
                        val file = File(directory, "$state-$width-$height-$theme$localeSuffix.png")
                        file.parentFile.mkdirs()
                        ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", file)
                    }
                }
                onNodeWithText(descriptorLabel).assertIsDisplayed()
                onNodeWithText(labels.getProperty("gui.config.market.repo.import.preview")).assertIsDisplayed().assertIsNotEnabled()
                if (height == 900) {
                    val title = labels.getProperty("gui.config.market.repo.dialog.add.title")
                    val bounds = onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title))
                        .fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.height < height / 2, "未预览时不应占满窗口高度")
                }
                screenshot("entry")
                onNodeWithText(labels.getProperty("gui.config.market.repo.mode.manual")).assertIsDisplayed().performClick()
                val id = onNodeWithTag("config.market.repository.id.input").onChildren().filterToOne(hasSetTextAction())
                id.performScrollTo().performTextInput("draft-repository")
                screenshot("manual")
                onNodeWithText(descriptorLabel).performClick()
                onNodeWithText(labels.getProperty("gui.config.market.repo.mode.manual")).performClick()
                onNodeWithTag("config.market.repository.id.input").onChildren().filterToOne(hasSetTextAction())
                    .assertTextEquals("draft-repository")
                onNodeWithText(descriptorLabel).performClick()
                onNode(hasSetTextAction()).performScrollTo()
                onNode(hasSetTextAction()).performTextInput("https://repo.example/repository.json")
                onNodeWithText(labels.getProperty("gui.config.market.repo.import.preview")).performClick()
                waitUntil(timeoutMillis = 5000) {
                    !model.busy() && model.snapshot().document().toString().contains("sha256:")
                }
                val accept = onNodeWithText(labels.getProperty("gui.config.market.repo.import.accept"))
                accept.assertIsDisplayed().assertIsNotEnabled()
                val bounds = accept.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.bottom <= height && bounds.right <= width)
                screenshot("preview")
                val consent = onNode(isToggleable())
                consent.performScrollTo().assertIsOff().performClick()
                consent.assertIsOn()
                accept.assertIsEnabled()
                onNodeWithText(labels.getProperty("gui.config.market.repo.import.confirm")).performClick()
                consent.assertIsOff()
                accept.assertIsNotEnabled()
                consent.performClick()
                consent.assertIsOn()
                consent.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                consent.performKeyInput { pressKey(Key.Spacebar) }
                consent.assertIsOff()
                accept.assertIsNotEnabled()
                screenshot("consent")
                accept.performKeyInput { pressKey(Key.Escape) }
                waitUntil { model.snapshot().document().dialogs().isEmpty() }
            } finally {
                subscription.close()
                model.close()
            }
        }
    }
}
