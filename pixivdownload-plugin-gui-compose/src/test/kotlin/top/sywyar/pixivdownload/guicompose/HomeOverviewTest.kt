package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.io.File
import java.text.MessageFormat
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@DisplayName("Compose 首页概览")
class HomeOverviewTest {
    @Test
    @DisplayName("快捷入口鼠标点击后释放焦点，键盘切换与激活保留焦点提示")
    fun releasesPointerFocusAndPreservesKeyboardNavigation() = runComposeUiTest {
        val events = mutableListOf<DesktopUiNode.Event>()
        setContent {
            PixivDownloaderTheme("light") {
                Box(Modifier.size(1000.dp, 800.dp)) {
                    ComposeDesktopUiNodeRenderer.Render(home(empty = true), ::resolve, events::add)
                }
            }
        }
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("shortcut.download").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("shortcut.download").assertIsFocused()
        assertEquals("shortcut.download", events.single().nodeId())
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("shortcut.images").assertIsFocused()
        for (symbol in listOf("download", "images", "book")) {
            runOnIdle { events.clear() }
            onNodeWithTag("shortcut.$symbol").performMouseInput { click() }
            onNodeWithTag("shortcut.$symbol").assertIsNotFocused()
            assertEquals("shortcut.$symbol", events.single().nodeId())
        }
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onAllNodes(isFocused()).assertCountEquals(1)
    }

    @Test
    @DisplayName("展开任务后刷新数据保持展开，快捷入口派发原节点事件")
    fun keepsExpansionAcrossRefreshesAndDispatchesShortcut() = runComposeUiTest {
        var current by mutableStateOf(home())
        val events = mutableListOf<DesktopUiNode.Event>()
        setContent {
            PixivDownloaderTheme("light") {
                Box(Modifier.size(1000.dp, 1000.dp)) {
                    ComposeDesktopUiNodeRenderer.Render(current, ::resolve, events::add)
                }
            }
        }
        onNodeWithText("Task 3").assertDoesNotExist()
        onNodeWithTag("home.tasks.expand").performClick()
        onNodeWithText("Task 3").assertExists()
        val titleLeft = onNodeWithText("Home").fetchSemanticsNode().boundsInRoot.left
        runOnIdle { current = home(progress = .73) }
        onNodeWithText("Task 3").assertExists()
        onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(.73f, 0f..1f)))
            .assertCountEquals(3)
        assertEquals(titleLeft, onNodeWithText("Home").fetchSemanticsNode().boundsInRoot.left)
        onNodeWithTag("shortcut.download").performClick()
        assertEquals("shortcut.download", events.single().nodeId())
        onNodeWithTag("home.system.expand").performScrollTo().performClick()
        onNodeWithText("127.0.0.1:7890").assertExists()
        onNodeWithTag("home.system.expand").performClick()
        onNodeWithText("127.0.0.1:7890").assertDoesNotExist()
    }

    @Test
    @DisplayName("指标单行分页，数据刷新保留当前页，来源撤回后页码保持有效")
    fun pagesMetricsWithoutResettingOnRefresh() = runComposeUiTest {
        var current by mutableStateOf(home(empty = true, known = false, metricCount = 7))
        setContent {
            PixivDownloaderTheme("light") {
                Box(Modifier.size(1000.dp, 800.dp)) {
                    ComposeDesktopUiNodeRenderer.Render(current, ::resolve, {})
                }
            }
        }
        onNodeWithText(resolveKey("tasks-unavailable")).assertExists()
        runOnIdle { current = home(empty = true, known = true, metricCount = 7) }
        onNodeWithText(resolveKey("tasks-unavailable")).assertDoesNotExist()
        onNodeWithText(resolveKey("idle")).assertExists()
        assertTrue(onNodeWithText("Metric 0").fetchSemanticsNode().boundsInRoot.bottom <=
            onNodeWithText("1250").fetchSemanticsNode().boundsInRoot.top)
        onNodeWithTag("metric.4").assertIsDisplayed()
        onNodeWithTag("metric.6").assertDoesNotExist()
        onNodeWithTag("home.metrics.previous").assertIsNotEnabled()
        val footerTop = onNodeWithTag("home.system.expand").fetchSemanticsNode().boundsInRoot.top
        onNodeWithTag("home.metrics.next").performClick()
        onNodeWithTag("metric.6").assertIsDisplayed()
        onNodeWithTag("home.metrics.next").assertIsNotEnabled()
        assertEquals(footerTop, onNodeWithTag("home.system.expand").fetchSemanticsNode().boundsInRoot.top)
        runOnIdle { current = home(empty = true, metricCount = 12) }
        onNodeWithTag("metric.6").assertIsDisplayed()
        onNodeWithTag("home.metrics.page").assertTextEquals(resolveKey("metrics-page", 2, 3))
        onNodeWithTag("home.metrics.next").performClick()
        onNodeWithTag("metric.11").assertIsDisplayed()
        runOnIdle { current = home(empty = true, metricCount = 1) }
        onNodeWithTag("metric.0").assertIsDisplayed()
        onNodeWithTag("metric.6").assertDoesNotExist()
        onNodeWithTag("home.metrics.next").assertDoesNotExist()
        runOnIdle { current = home(empty = true, metricCount = 0) }
        onNodeWithTag("metric.0").assertDoesNotExist()
    }

    @Test
    @DisplayName("宽度决定每页容量，窄窗口仍只显示一行并可访问所有指标")
    fun adaptsMetricCapacityToWidth() = runComposeUiTest {
        var width by mutableStateOf(1000.dp)
        setContent {
            PixivDownloaderTheme("light") {
                Box(Modifier.size(width, 800.dp)) {
                    ComposeDesktopUiNodeRenderer.Render(home(empty = true, metricCount = 5), ::resolve, {})
                }
            }
        }
        onNodeWithTag("metric.4").assertIsDisplayed()
        onNodeWithTag("home.metrics.next").assertDoesNotExist()
        val firstTop = onNodeWithTag("metric.0").fetchSemanticsNode().boundsInRoot.top
        assertEquals(firstTop, onNodeWithTag("metric.4").fetchSemanticsNode().boundsInRoot.top)
        runOnIdle { width = 440.dp }
        onNodeWithTag("home.metrics.page").assertTextEquals(resolveKey("metrics-page", 1, 3))
        onNodeWithTag("metric.1").assertIsDisplayed()
        onNodeWithTag("metric.2").assertDoesNotExist()
        onNodeWithTag("home.metrics.next").performScrollTo().performClick()
        onNodeWithTag("home.metrics.next").performClick()
        onNodeWithTag("metric.4").assertIsDisplayed()
        runOnIdle { width = 1000.dp }
        onNodeWithTag("metric.0").assertIsDisplayed()
        onNodeWithTag("metric.4").assertIsDisplayed()
        onNodeWithTag("home.metrics.next").assertDoesNotExist()
    }

    @Test
    @DisplayName("指标翻页可反向中断，减少动态效果时直接到达目标页")
    fun reversesPagingAndSupportsReducedMotion() {
        for (scale in listOf(1f, 0f)) runComposeUiTest(
            effectContext = object : androidx.compose.ui.MotionDurationScale { override val scaleFactor = scale },
        ) {
            mainClock.autoAdvance = false
            setContent {
                PixivDownloaderTheme("light") {
                    Box(Modifier.size(1000.dp, 800.dp)) {
                        ComposeDesktopUiNodeRenderer.Render(home(empty = true, metricCount = 12), ::resolve, {})
                    }
                }
            }
            mainClock.advanceTimeBy(600)
            onNodeWithTag("home.metrics.next").performTouchInput { click() }
            mainClock.advanceTimeBy(64)
            if (scale == 0f) onNodeWithTag("metric.6").assertIsDisplayed()
            onNodeWithTag("home.metrics.previous").performTouchInput { click() }
            mainClock.advanceTimeBy(1000)
            onNodeWithTag("home.metrics.page").assertTextEquals(resolveKey("metrics-page", 1, 3))
            onNodeWithTag("metric.0").assertIsDisplayed()
        }
    }

    @Test
    @DisplayName("展开动画可以反向中断，减少动态效果时直接完成")
    fun reversesDisclosureAndSupportsReducedMotion() {
        for (scale in listOf(1f, 0f)) runComposeUiTest(
            effectContext = object : androidx.compose.ui.MotionDurationScale { override val scaleFactor = scale },
        ) {
            mainClock.autoAdvance = false
            setContent {
                PixivDownloaderTheme("light") {
                    Box(Modifier.size(1000.dp, 1000.dp)) {
                        ComposeDesktopUiNodeRenderer.Render(home(metricCount = 5), ::resolve, {})
                    }
                }
            }
            mainClock.advanceTimeBy(600)
            onNodeWithTag("home.tasks.expand").performClick()
            mainClock.advanceTimeBy(64)
            if (scale == 0f) onNodeWithText("Task 3").assertIsDisplayed()
            onNodeWithTag("home.tasks.expand").performClick()
            mainClock.advanceTimeBy(600)
            onNodeWithText("Task 3").assertDoesNotExist()
            onNodeWithTag("home.tasks.expand").performClick()
            mainClock.advanceTimeBy(600)
            onNodeWithText("Task 3").assertIsDisplayed()
        }
    }

    @Test
    @DisplayName("浅色深色和窄窗口布局均可访问，并保存可选的实际渲染截图")
    fun rendersResponsiveThemes() {
        for ((theme, width) in listOf("light" to 1100, "dark" to 1100, "light" to 440)) runComposeUiTest {
            setContent {
                PixivDownloaderTheme(theme) {
                    Box(Modifier.size(width.dp, 800.dp).background(LocalExperiencePalette.current.surface)) {
                        ComposeDesktopUiNodeRenderer.Render(home(metricCount = 5), ::resolve, {})
                    }
                }
            }
            onNodeWithTag("shortcut.download").assertIsDisplayed()
            onNodeWithTag("shortcut.images").assertIsDisplayed()
            val root = onNodeWithTag("home.overview").fetchSemanticsNode().boundsInRoot
            val card = onNodeWithTag("shortcut.book").fetchSemanticsNode().boundsInRoot
            assertTrue(card.right <= root.right && card.left >= root.left)
            onNodeWithTag("home.system.expand").performScrollTo().performClick()
            onNodeWithTag("home.system.note").performScrollTo()
            val service = onNodeWithTag("home.system.service").fetchSemanticsNode().boundsInRoot
            val proxy = onNodeWithTag("home.system.proxy").fetchSemanticsNode().boundsInRoot
            if (width > 620) assertEquals(service.top, proxy.top)
            else assertTrue(proxy.top >= service.bottom)
            System.getenv("PIXIV_HOME_SCREENSHOTS")?.let { directory ->
                val output = File(directory).apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "home-$theme-$width.png"))
            }
            onNodeWithTag("home.system.expand").performScrollTo().assertIsDisplayed()
        }
    }

    companion object {
        private val messages = Properties().apply {
            HomeOverviewTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!
                .reader(Charsets.UTF_8).use(::load)
        }

        fun resolve(token: DesktopUiNode.TextToken): String =
            if (token.key().isBlank()) token.fallback()
            else if (token.key() == "desktop.ui.page.home") "Home"
            else MessageFormat.format(messages.getProperty(token.key(), token.key()), *token.arguments().toTypedArray())

        private fun resolveKey(suffix: String, vararg args: Any): String =
            MessageFormat.format(messages.getProperty("gui.compose.home.$suffix"), *args)
        private fun raw(value: String) = DesktopUiNode.TextToken.raw(value)
        fun home(progress: Double = .42, empty: Boolean = false, known: Boolean = true, metricCount: Int = 4): DesktopUiNode.HomeOverview {
            val shortcuts = listOf("download", "images", "book", "chart-bar").map { symbol ->
                DesktopUiNode.HomeShortcut(
                    DesktopUiNode.Button("shortcut.$symbol", "open.$symbol", raw(when (symbol) {
                        "download" -> "Download"
                        "images" -> "Collection"
                        "book" -> "Books"
                        else -> "Statistics"
                    }), raw("Open in your browser"), DesktopUiNode.ButtonStyle.NORMAL, true), symbol,
                )
            }
            return DesktopUiNode.HomeOverview("home.overview", shortcuts,
                if (empty) emptyList() else (1..3).map {
                    DesktopUiNode.HomeTask("task.$it", raw("Task $it"), raw("Preparing your collection"),
                        raw("Running"), progress, null)
                },
                (0 until metricCount).map {
                    DesktopUiNode.HomeMetric("metric.$it", raw("Metric $it"), raw((1250 + it * 123).toString()),
                        if (it == 3) raw("GB") else null, raw("From connected sources"), null)
                }, known,
                DesktopUiNode.Text("backend", raw("Service running"), DesktopUiNode.TextStyle.SUCCESS, true, false),
                DesktopUiNode.HomeSystem(raw("Configured"), raw("127.0.0.1:7890"), raw("8 running / 9 total")),
            )
        }
    }
}
