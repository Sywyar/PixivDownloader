package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import io.github.robinpcrd.cupertino.theme.CupertinoColors
import io.github.robinpcrd.cupertino.theme.systemYellow
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.io.File
import java.io.StringReader
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties
import java.util.ResourceBundle
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@DisplayName("Compose 首页概览")
class HomeOverviewTest {
    @Test
    @DisplayName("恢复模式在浅深色和窄窗口显示黄灯及完整提示，恢复正常后提示消失")
    fun recoveryIndicatorAndHintFollowCurrentState() {
        for ((theme, width) in listOf("light" to 1000, "dark" to 1000, "light" to 440, "dark" to 440)) {
            runComposeUiTest {
                var recovery by mutableStateOf(true)
                val palette = experiencePalette(theme == "dark")
                setContent {
                    PixivDownloaderTheme(theme) {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                            Box(Modifier.size(width.dp, 800.dp).background(palette.surface)) {
                                HomeOverview(home(empty = true, recovery = recovery), ::resolve, {})
                            }
                        }
                    }
                }
                onNodeWithTag("home.backend.state").assertTextEquals(resolveKey("recovery"))
                onNodeWithTag("home.backend.hint").assertIsDisplayed().assertTextEquals(resolveKey("recovery.hint"))
                val statusBounds = onNodeWithTag("home.backend").fetchSemanticsNode().boundsInRoot
                val hintBounds = onNodeWithTag("home.backend.hint").fetchSemanticsNode().boundsInRoot
                assertTrue(hintBounds.left >= statusBounds.left && hintBounds.right <= statusBounds.right)
                fun indicatorColor() = onNodeWithTag("home.backend.indicator").captureToImage().toPixelMap().let {
                    it[it.width / 2, it.height / 2]
                }
                assertEquals(CupertinoColors.systemYellow(theme == "dark", false), indicatorColor())
                System.getenv("PIXIV_HOME_SCREENSHOTS")?.let { directory ->
                    val output = File(directory).apply { mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "recovery-$theme-$width.png"))
                }
                runOnIdle { recovery = false }
                onNodeWithText(resolveKey("recovery.hint")).assertDoesNotExist()
                onNodeWithTag("home.backend.state").assertTextEquals("Service running")
                assertEquals(palette.success, indicatorColor())
            }
        }
    }

    @Test
    @DisplayName("网络和插件状态显示本实例端口，普通模式不增加端口信息")
    fun systemStatusShowsPortOnlyWhenProvided() = runComposeUiTest {
        var port by mutableStateOf<Int?>(8124)
        setContent {
            PixivDownloaderTheme("light") {
                Box(Modifier.size(1000.dp, 800.dp)) {
                    HomeOverview(home(empty = true, port = port), ::resolve, {})
                }
            }
        }
        onNodeWithTag("home.system.expand").performScrollTo().performClick()
        onNodeWithText("gui.status.label.port 8124").performScrollTo().assertIsDisplayed()
        runOnIdle { port = null }
        onNodeWithText("gui.status.label.port 8124").assertDoesNotExist()
    }

    @Test
    @DisplayName("启动超过预期后显示按秒刷新的计时，提示与运行状态切换不移动首页内容")
    fun startupStatusTicksWithoutRebuildingTheHomeDocument() = runComposeUiTest {
        var snapshot by mutableStateOf(home(empty = true))
        // 失焦暂停独立的提示轮换，避免虚拟时钟推进改变被比较的提示文字。
        val window = object : WindowInfo { override val isWindowFocused = false }
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                PixivDownloaderTheme("light") {
                    Box(Modifier.size(1000.dp, 800.dp)) {
                        HomeOverview(snapshot, ::resolve, {})
                    }
                }
            }
        }
        fun status() = onNodeWithTag("home.backend.state").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        fun assertStatusAtRightEdge() {
            val bounds = onNodeWithTag("home.backend").fetchSemanticsNode().boundsInRoot
            val statusBounds = onNodeWithTag("home.backend.state").fetchSemanticsNode().boundsInRoot
            assertEquals(bounds.right, statusBounds.right)
        }
        assertStatusAtRightEdge()
        runOnIdle { snapshot = home(empty = true, startingAt = System.currentTimeMillis() - 18_000L) }
        val hint = resolveKey("starting.slow")
        onNodeWithText(hint).assertDoesNotExist()
        onNodeWithTag("home.backend.state").assertTextEquals(resolve(snapshot.backend().text()))
        assertStatusAtRightEdge()
        val positions = listOf("home.greeting", "home.tip", "shortcut.download").associateWith {
            onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
        }
        waitUntil(timeoutMillis = 3_500) { onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty() }
        val first = status()
        assertStatusAtRightEdge()
        assertNotEquals(resolve(snapshot.backend().text()), first)
        waitUntil(timeoutMillis = 1_500) { status() != first }
        positions.forEach { (tag, bounds) -> assertEquals(bounds, onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot) }
        runOnIdle { snapshot = home(empty = true) }
        onNodeWithText(hint).assertDoesNotExist()
        positions.forEach { (tag, bounds) -> assertEquals(bounds, onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot) }
        onNodeWithTag("home.backend.state").assertTextEquals("Service running")
        assertStatusAtRightEdge()
    }

    @Test
    @DisplayName("提示每分钟淡入淡出轮换且不连续重复，失焦暂停并支持减少动态效果")
    fun rotatesTipsEveryMinuteWhileFocused() {
        for (scale in listOf(1f, 0f)) runComposeUiTest(
            effectContext = object : androidx.compose.ui.MotionDurationScale { override val scaleFactor = scale },
        ) {
            mainClock.autoAdvance = false
            var focused by mutableStateOf(true)
            val window = object : WindowInfo { override val isWindowFocused get() = focused }
            setContent {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    PixivDownloaderTheme("light") {
                        Box(Modifier.size(1000.dp, 800.dp)) {
                            HomeOverview(home(empty = true), ::resolve, {})
                        }
                    }
                }
            }
            fun visibleTips() = onNodeWithTag("home.tip").fetchSemanticsNode()
                .config[SemanticsProperties.Text].map { it.text }
            mainClock.advanceTimeBy(600)
            val first = visibleTips().single()
            mainClock.advanceTimeBy(59_000)
            assertEquals(listOf(first), visibleTips())
            mainClock.advanceTimeBy(448)
            assertEquals(if (scale == 0f) 1 else 2, visibleTips().size)
            mainClock.advanceTimeBy(400)
            val second = visibleTips().single()
            assertNotEquals(first, second)
            runOnIdle { focused = false }
            mainClock.advanceTimeBy(120_000)
            assertEquals(listOf(second), visibleTips())
            runOnIdle { focused = true }
            mainClock.advanceTimeBy(32)
            assertEquals(listOf(second), visibleTips())
            mainClock.advanceTimeBy(60_400)
            assertNotEquals(second, visibleTips().single())
        }
    }

    @Test
    @DisplayName("提示自动读取非空条目，新增键无需连续编号或修改代码列表")
    fun discoversTipsFromProperties() {
        val source = """
            # A comment is not a tip.
            tip.named=An ordinary tip
            tip.42=A sparse numbered tip
            tip.empty=
            metadata=Not a tip
        """.trimIndent()
        assertEquals(setOf("tip.named", "tip.42"), HomeTips.readKeys(StringReader(source)).toSet())
        assertEquals(setOf("tip.named", "tip.42", "tip.new"),
            HomeTips.readKeys(StringReader(source + "\ntip.new=A new tip")).toSet())
        assertTrue(HomeTips.readKeys(StringReader("# No tips\ntip.empty= ")).isEmpty())
    }

    @Test
    @DisplayName("独立提示资源可以翻译，切页返回与切换语言保留同一条提示")
    fun preservesSelectedTipAcrossNavigationAndLanguageChanges() = runComposeUiTest {
        var visible by mutableStateOf(true)
        var language by mutableStateOf(Locale.US)
        var selectedKey: String? = null
        val bundleName = GuiComposePlugin().i18n().single { it.namespace() == HomeTips.NAMESPACE }.baseName()
        setContent {
            val stateHolder = rememberSaveableStateHolder()
            PixivDownloaderTheme("light") {
                Box(Modifier.size(1000.dp, 800.dp)) {
                    if (visible) stateHolder.SaveableStateProvider("home") {
                        HomeOverview(
                            node = home(empty = true),
                            text = { token ->
                                if (token.namespace() == HomeTips.NAMESPACE) {
                                    selectedKey = token.key()
                                    ResourceBundle.getBundle(bundleName, language).getString(token.key())
                                } else resolve(token)
                            },
                            emit = {},
                        )
                    }
                }
            }
        }
        onNodeWithTag("home.tip").assertIsDisplayed()
        val initialKey = checkNotNull(selectedKey)
        assertTrue(initialKey in HomeTips.keys)
        onNodeWithTag("home.tip").assertTextEquals(ResourceBundle.getBundle(bundleName, Locale.US).getString(initialKey))
        runOnIdle { visible = false }
        onNodeWithTag("home.tip").assertDoesNotExist()
        runOnIdle { language = Locale.JAPAN; visible = true }
        onNodeWithTag("home.tip").assertTextEquals(ResourceBundle.getBundle(bundleName, Locale.JAPAN).getString(initialKey))
        assertEquals(initialKey, selectedKey)
    }

    @Test
    @DisplayName("问候语覆盖全天，并在各时段边界切换")
    fun selectsGreetingForEachTimeOfDay() {
        for (hour in 0..23) {
            val expected = listOf(
                "night", "night", "night", "night", "night",
                "morning", "morning", "morning", "morning", "morning", "morning",
                "noon", "noon", "afternoon", "afternoon", "afternoon", "afternoon", "afternoon",
                "evening", "evening", "evening", "evening", "evening", "evening",
            )[hour]
            assertEquals("greeting.$expected", homeGreetingKey(hour), "hour=$hour")
        }
    }

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
        val titleLeft = onNodeWithTag("home.greeting").fetchSemanticsNode().boundsInRoot.left
        val tipText = onNodeWithTag("home.tip").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        assertTrue(HomeTips.keys.any { tips.getString(it) == tipText })
        runOnIdle { current = home(progress = .73) }
        onNodeWithText("Task 3").assertExists()
        onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(.73f, 0f..1f)))
            .assertCountEquals(3)
        assertEquals(titleLeft, onNodeWithTag("home.greeting").fetchSemanticsNode().boundsInRoot.left)
        onNodeWithTag("home.tip").assertTextEquals(tipText)
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
        private val tips = ResourceBundle.getBundle(HomeTips.BASE_NAME, Locale.US)
        private val messages = Properties().apply {
            HomeOverviewTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!
                .reader(Charsets.UTF_8).use(::load)
        }

        fun resolve(token: DesktopUiNode.TextToken): String =
            if (token.key().isBlank()) token.fallback()
            else if (token.namespace() == HomeTips.NAMESPACE) tips.getString(token.key())
            else if (token.key() == "desktop.ui.page.home") "Home"
            else MessageFormat.format(messages.getProperty(token.key(), token.key()), *token.arguments().toTypedArray())

        private fun resolveKey(suffix: String, vararg args: Any): String =
            MessageFormat.format(messages.getProperty("gui.compose.home.$suffix"), *args)
        private fun raw(value: String) = DesktopUiNode.TextToken.raw(value)
        fun home(progress: Double = .42, empty: Boolean = false, known: Boolean = true, metricCount: Int = 4, startingAt: Long = 0L, port: Int? = null, recovery: Boolean = false): DesktopUiNode.HomeOverview {
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
                DesktopUiNode.Text("backend", raw(when {
                    startingAt > 0L -> "Starting..."
                    recovery -> resolveKey("recovery")
                    else -> "Service running"
                }), if (recovery) DesktopUiNode.TextStyle.WARNING else DesktopUiNode.TextStyle.SUCCESS, true, false),
                startingAt,
                recovery,
                DesktopUiNode.HomeSystem(raw("Configured"), raw("127.0.0.1:7890"), raw("8 running / 9 total"), port),
            )
        }
    }
}
