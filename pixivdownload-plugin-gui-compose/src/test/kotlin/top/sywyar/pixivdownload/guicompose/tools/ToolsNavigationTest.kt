package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import java.io.File
import java.util.Optional
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ToolsNavigationTest {
    @Test
    @DisplayName("工具导航与当前任务同源计数，跨页更新且任务结束后撤回提示")
    fun updatesAcrossPages() = runComposeUiTest {
        val base = ToolsOverviewTest.sample(
            activity = ToolActivity("backfill", true, false, TextToken.raw("Processing")),
            mediaTools = listOf(ToolsOverviewTest.activeTool("media.converter")),
            fileImports = listOf(ToolsOverviewTest.activeTool("import.first"), ToolsOverviewTest.activeTool("import.second")),
        )
        val ffmpeg = ToolsOverviewTest.activeTool("media")
        // 同一任务的多个进度节点只计一次；历史中的进度节点也不代表当前任务。
        val twoProgress = Group(
            "media",
            ffmpeg.title(),
            Container(
                "media.progresses",
                ContainerLayout.COLUMN,
                1,
                12,
                Alignment.START,
                listOf(ffmpeg.content(), Progress("media.extra", .5, false, TextToken.raw("Extra"))),
            ),
            false,
        )
        var tools by mutableStateOf(DesktopUiNode.ToolsOverview(
            base.id(),
            base.backend(),
            base.tools(),
            twoProgress,
            base.mediaTools(),
            base.fileImports(),
            ToolsOverviewTest.activeTool("history"),
            base.activity(),
            null,
        ))
        setContent {
            PixivDownloaderTheme("light") {
                Box(Modifier.size(1200.dp, 900.dp)) {
                    DesktopShell(document(tools), 1L, ::resolve, {})
                }
            }
        }
        fun count(value: Int) = onNodeWithTag("navigation.tools").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            resolve(TextToken("gui-compose", "gui.compose.tools.running-count", "", listOf(value.toString()))),
        ))
        count(5)
        onNodeWithTag("tools.overview").assertDoesNotExist()
        onNodeWithTag("navigation.tools").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithTag("navigation.tools").performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("tools.overview").assertIsDisplayed()
        onNodeWithTag("tools.current").assertExists()
        for (id in listOf("media", "media.converter", "import.first", "import.second")) {
            onNodeWithTag("tools.current.$id").assertExists()
        }
        screenshot("tools-selected-light")
        onNodeWithTag("navigation.home").performClick()
        runOnIdle { tools = ToolsOverviewTest.sample(fileImports = (1..12).map { ToolsOverviewTest.activeTool("import.$it") }) }
        count(12)
        screenshot("home-light")
        runOnIdle { tools = ToolsOverviewTest.sample(activity = ToolActivity("backfill", false, true, TextToken.raw("Failed"))) }
        onNodeWithTag("navigation.tools").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        onNodeWithTag("navigation.tools").performClick()
        onNodeWithTag("tools.current").assertDoesNotExist()
    }

    @Test
    @DisplayName("工具导航边框定长滚动，减少动态效果或失焦时静止，深色大字与窄窗口仍可操作")
    fun motionAndLayout() {
        for (reduced in listOf(false, true)) runComposeUiTest(
            effectContext = object : MotionDurationScale { override val scaleFactor = if (reduced) 0f else 1f },
        ) {
            mainClock.autoAdvance = false
            var focused by mutableStateOf(true)
            var running by mutableStateOf(true)
            val window = object : WindowInfo { override val isWindowFocused get() = focused }
            var accent = 0
            setContent {
                CompositionLocalProvider(LocalWindowInfo provides window, LocalDensity provides Density(1f, 1.5f)) {
                    PixivDownloaderTheme(if (reduced) "dark" else "light") {
                        accent = LocalExperiencePalette.current.link.toArgb()
                        Box(Modifier.size(700.dp, 600.dp)) {
                            DesktopShell(
                                document(ToolsOverviewTest.sample(
                                    activity = ToolActivity("backfill", running, false, TextToken.raw("Processing")),
                                )),
                                1L,
                                ::resolve,
                                {},
                            )
                        }
                    }
                }
            }
            fun borderPixels(): Set<Int> {
                val image = onNodeWithTag("navigation.tools").captureToImage().toAwtImage()
                return buildSet {
                    for (y in 0 until image.height) for (x in 0 until image.width) {
                        if ((x < 3 || y < 3 || x >= image.width - 3 || y >= image.height - 3) &&
                            image.getRGB(x, y) == accent) add(y * image.width + x)
                    }
                }
            }
            mainClock.advanceTimeBy(100)
            val first = borderPixels()
            assertTrue(first.isNotEmpty())
            mainClock.advanceTimeBy(900)
            val next = borderPixels()
            if (reduced) assertEquals(first, next) else assertNotEquals(first, next)
            assertTrue(kotlin.math.abs(first.size - next.size) < 16)
            screenshot("home-large-text-${if (reduced) "dark" else "light"}")
            runOnIdle { focused = false }
            mainClock.advanceTimeByFrame()
            val paused = borderPixels()
            mainClock.advanceTimeBy(900)
            assertEquals(paused, borderPixels())
            runOnIdle { running = false }
            mainClock.advanceTimeByFrame()
            assertTrue(borderPixels().isEmpty())
            onNodeWithTag("navigation.tools").performClick()
            mainClock.advanceTimeBy(240)
            onNodeWithTag("tools.overview").assertIsDisplayed()
        }
    }

    private fun document(tools: DesktopUiNode.ToolsOverview): DesktopUiDocument {
        val home = HomeOverviewTest.home()
        val homeContent = HomeOverview(
            home.id(),
            home.shortcuts(),
            home.tasks(),
            home.metrics(),
            home.tasksKnown(),
            Text("home.backend", home.backend().text(), home.backend().style(), true, false),
            home.backendStartingAt(),
            home.backendRecoveryMode(),
            home.system(),
        )
        return DesktopUiDocument(
            listOf(
                DesktopUiDocument.Page("home", TextToken.raw("Home"), DesktopUiIcon.HOME, homeContent),
                DesktopUiDocument.Page(
                    "tools",
                    TextToken.raw("Tools"),
                    DesktopUiIcon.TOOLS,
                    Surface("tools.surface", SurfaceStyle.PLAIN, Insets.NONE, true, tools),
                ),
            ),
            emptyList(),
            emptyList(),
            Optional.empty(),
            true,
        )
    }

    private fun resolve(token: TextToken) =
        if (token.key() == "desktop.ui.page.tools") "Tools" else HomeOverviewTest.resolve(token)

    private fun ComposeUiTest.screenshot(name: String) {
        val output = File("target/tools-navigation-ui").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "$name.png"))
    }
}
