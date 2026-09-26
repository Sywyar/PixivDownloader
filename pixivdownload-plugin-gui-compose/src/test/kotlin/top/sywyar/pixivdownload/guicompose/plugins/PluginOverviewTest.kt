package top.sywyar.pixivdownload.guicompose.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.PluginEntry
import java.io.File
import java.text.MessageFormat
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
@DisplayName("插件紧凑列表与详情")
class PluginOverviewTest {
    @Test
    @DisplayName("宽窗口双列，详情打开后单列，刷新保留选择并在撤回后关闭")
    fun reflowsAndRetainsSelectionAcrossRefresh() = runComposeUiTest {
        var node by mutableStateOf(sample())
        setContent { PixivDownloaderTheme("light") {
            Box(Modifier.size(1200.dp, 900.dp)) { PluginOverview(node, ::resolve, {}) }
        } }
        val first = onNodeWithTag("plugins.row.plugin-0")
        val second = onNodeWithTag("plugins.row.plugin-1")
        val left = first.fetchSemanticsNode().boundsInRoot
        val right = second.fetchSemanticsNode().boundsInRoot
        assertEquals(left.top, right.top, 1f)
        assertTrue(right.left > left.right)
        assertTrue(left.height <= 72)
        first.performClick()
        onNodeWithTag("plugins.detail.title").assertTextEquals("Gallery")
        assertTrue(second.fetchSemanticsNode().boundsInRoot.top > first.fetchSemanticsNode().boundsInRoot.top)
        second.performClick()
        onNodeWithTag("plugins.detail.title").assertTextEquals("Plugin 1")
        first.performClick()
        runOnIdle {
            node = sample(node.plugins().map { if (it.id() == "plugin-0") entry(0, name = "New gallery name") else it })
        }
        onNodeWithTag("plugins.detail.title").assertTextEquals("New gallery name")
        onNodeWithTag("plugins.detail.title").performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("plugins.detail").assertDoesNotExist()
        first.assertIsFocused()
        first.performClick()
        runOnIdle { node = sample(node.plugins().filter { it.id() != "plugin-0" }) }
        onNodeWithTag("plugins.detail").assertDoesNotExist()
    }

    @Test
    @DisplayName("切换单列保留点击位置与滚动边界，再次点击选中项收起详情恢复双列")
    fun anchorsClickedRowDuringReflow() {
        for ((start, target) in listOf(0 to 0, 0 to 1, 0 to 11, 50 to 60, 50 to 61, 68 to 79)) runComposeUiTest {
            setContent { PixivDownloaderTheme("light") {
                Box(Modifier.size(1200.dp, 900.dp)) { PluginOverview(sample((0..79).map(::entry)), ::resolve, {}) }
            } }
            val list = onNodeWithTag("plugins.list")
            list.performScrollToIndex(start)
            val row = onNodeWithTag("plugins.row.plugin-$target")
            val before = row.fetchSemanticsNode().boundsInRoot
            val viewport = list.fetchSemanticsNode().boundsInRoot
            row.performMouseInput { click(Offset(width / 3f, height * .65f)) }
            onNodeWithTag("plugins.detail.title").assertTextEquals(if (target == 0) "Gallery" else "Plugin $target")
            val after = row.fetchSemanticsNode().boundsInRoot
            val desiredScroll = target * after.height - (before.top - viewport.top)
            val scroll = desiredScroll.coerceIn(0f, (80 * after.height - viewport.height).coerceAtLeast(0f))
            assertEquals(viewport.top + target * after.height - scroll, after.top, 1f, "Plugin $target from $start")
            row.assertIsDisplayed()
            row.performMouseInput { click() }
            onNodeWithTag("plugins.detail").assertDoesNotExist()
            row.assertIsDisplayed().assertIsNotSelected()
            val restored = row.fetchSemanticsNode().boundsInRoot
            val sibling = onNodeWithTag("plugins.row.plugin-${target xor 1}").fetchSemanticsNode().boundsInRoot
            assertEquals(restored.top, sibling.top, 1f)
            assertTrue(restored.width < after.width)
        }
    }

    @Test
    @DisplayName("详情关闭按钮与图标同排靠右，不额外占用顶部一行")
    fun placesCloseBesideDetailIcon() = runComposeUiTest {
        setContent { PixivDownloaderTheme("light") {
            Box(Modifier.size(1200.dp, 900.dp)) { PluginOverview(sample(), ::resolve, {}) }
        } }
        onNodeWithTag("plugins.row.plugin-0").performClick()
        val header = onNodeWithTag("plugins.detail.header").fetchSemanticsNode().boundsInRoot
        val close = onNodeWithTag("plugins.close").fetchSemanticsNode().boundsInRoot
        assertEquals(48f, header.height, 1f)
        assertEquals(header.center.y, close.center.y, 1f)
        assertEquals(header.right, close.right, 1f)
        onNodeWithTag("plugins.close").performClick()
        onNodeWithTag("plugins.detail").assertDoesNotExist()
    }

    @Test
    @DisplayName("搜索包括折叠的内置项与插件 ID，来源未验证不混入运行异常")
    fun searchesAllEntriesAndSeparatesVerificationFromRuntime() = runComposeUiTest {
        setContent { PixivDownloaderTheme("light") {
            Box(Modifier.size(1200.dp, 900.dp)) { PluginOverview(sample(), ::resolve, {}) }
        } }
        onNodeWithTag("plugins.row.built-in").assertDoesNotExist()
        onNodeWithTag("plugins.attention").performClick()
        onNodeWithTag("plugins.row.plugin-0").assertDoesNotExist()
        onNodeWithTag("plugins.row.plugin-1").assertIsDisplayed()
        onNodeWithTag("plugins.row.plugin-2").assertIsDisplayed()
        onNodeWithTag("plugins.all").performClick()
        onNodeWithTag("plugins.search").performTextInput("built-in")
        onNodeWithTag("plugins.row.built-in").assertIsDisplayed()
        onNodeWithTag("plugins.row.built-in").performClick()
        onNodeWithTag("plugins.detail.title").assertTextEquals("Runtime")
        onNodeWithTag("plugins.close").performClick()
        onNodeWithTag("plugins.search").performTextReplacement("no such plugin")
        onNodeWithTag("plugins.empty").assertIsDisplayed()
        onNodeWithTag("plugins.clear").performClick()
        onNodeWithTag("plugins.row.plugin-0").assertIsDisplayed()
        onNodeWithTag("plugins.row.built-in").assertDoesNotExist()
    }

    @Test
    @DisplayName("管理与刷新派发既有受控动作，忙碌时不重复派发")
    fun emitsOnlyEnabledActions() = runComposeUiTest {
        val events = mutableListOf<DesktopUiNode.Event>()
        var enabled by mutableStateOf(true)
        setContent { PixivDownloaderTheme("light") {
            Box(Modifier.size(1200.dp, 900.dp)) { PluginOverview(sample(enabled = enabled), ::resolve, events::add) }
        } }
        onNodeWithTag("plugins.manage").performClick()
        onNodeWithTag("plugins.refresh").performClick()
        onNodeWithTag("plugins.row.plugin-0").performClick()
        onNodeWithTag("plugins.detail.manage").performClick()
        assertEquals(listOf("plugins.manage", "plugins.refresh", "plugins.manage"), events.map { it.nodeId() })
        runOnIdle { enabled = false }
        onNodeWithTag("plugins.refresh").assertIsNotEnabled()
        onNodeWithTag("plugins.detail.manage").assertIsNotEnabled()
    }

    @Test
    @DisplayName("大量插件可滚动并用方向键浏览，连续切换详情不保留错误条目")
    fun browsesLargeListsAndInterruptsTransitions() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { PixivDownloaderTheme("light") {
            Box(Modifier.size(1200.dp, 900.dp)) { PluginOverview(sample((0..79).map(::entry)), ::resolve, {}) }
        } }
        mainClock.advanceTimeBy(400)
        onNodeWithTag("plugins.list").performScrollToKey("plugin:plugin-60")
        mainClock.advanceTimeByFrame()
        onNodeWithTag("plugins.row.plugin-60").performClick()
        mainClock.advanceTimeBy(500)
        onNodeWithTag("plugins.detail.title").assertTextEquals("Plugin 60")
        onNodeWithTag("plugins.detail.title").performKeyInput { pressKey(Key.Escape) }
        mainClock.advanceTimeBy(500)
        onNodeWithTag("plugins.row.plugin-60").assertIsFocused()
        onNodeWithTag("plugins.row.plugin-60").performKeyInput { pressKey(Key.DirectionDown) }
        mainClock.advanceTimeBy(400)
        onNodeWithTag("plugins.row.plugin-62").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        mainClock.advanceTimeBy(80)
        onNodeWithTag("plugins.close").performClick()
        mainClock.advanceTimeBy(100)
        onNodeWithTag("plugins.list").performScrollToKey("plugin:plugin-0")
        mainClock.advanceTimeByFrame()
        onNodeWithTag("plugins.row.plugin-0").performClick()
        mainClock.advanceTimeBy(600)
        onNodeWithTag("plugins.detail.title").assertTextEquals("Gallery")
    }

    @Test
    @DisplayName("深色窄窗口与大字体保持详情可达，减少动态效果时仍可返回")
    fun adaptsToThemeWidthAndFontScale() {
        for ((theme, width, scale) in listOf(Triple("light", 1200, 1f), Triple("dark", 1200, 1f),
            Triple("light", 420, 1f), Triple("light", 800, 2f))) runComposeUiTest(
            effectContext = object : MotionDurationScale { override val scaleFactor = 0f },
        ) {
            setContent { PixivDownloaderTheme(theme) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    Box(Modifier.size(width.dp, 900.dp).background(LocalExperiencePalette.current.surface)) {
                        PluginOverview(sample(), ::resolve, {})
                    }
                }
            } }
            onNodeWithTag("plugins.row.plugin-0").assertIsDisplayed()
            capture("plugins-$theme-$width-$scale")
            onNodeWithTag("plugins.row.plugin-0").performClick()
            onNodeWithTag("plugins.detail.title").assertTextEquals("Gallery")
            capture("plugins-detail-default-$theme-$width-$scale")
            onNodeWithTag("plugins.technical").performScrollTo().performClick()
            onNodeWithText("PROVENANCE_MISSING").performScrollTo().assertIsDisplayed()
            capture("plugins-detail-$theme-$width-$scale")
            onNodeWithTag("plugins.close").performClick()
            onNodeWithTag("plugins.row.plugin-0").assertIsDisplayed()
        }
    }

    @Test
    @DisplayName("已运行但来源未验证不算失败，已验签但崩溃仍需关注")
    fun preservesIndependentStatusDimensions() {
        assertFalse(entry(0).needsAttention())
        assertTrue(entry(1).runtimeFailed())
        assertFalse(entry(1).verificationFailed())
        assertTrue(entry(2).verificationFailed())
        assertFalse(entry(2).runtimeFailed())
        assertTrue(entry(1).needsAttention())
        assertTrue(entry(2).needsAttention())
    }

    private fun ComposeUiTest.capture(name: String) {
        System.getenv("PIXIV_PLUGINS_SCREENSHOTS")?.let { directory ->
            File(directory).mkdirs()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
        }
    }

    companion object {
        private val messages = Properties().apply {
            File("../pixivdownload-app/src/main/resources/i18n/messages_en.properties").reader(Charsets.UTF_8).use(::load)
            PluginOverviewTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!
                .reader(Charsets.UTF_8).use(::load)
        }
        fun resolve(token: DesktopUiNode.TextToken): String =
            if (token.key().isBlank()) token.fallback()
            else MessageFormat.format(messages.getProperty(token.key(), token.key()), *token.arguments().toTypedArray())

        private fun entry(index: Int, name: String = if (index == 0) "Gallery" else "Plugin $index") = PluginEntry(
            "plugin-$index", name, "Browse and organize your collection", "images",
            when (index % 3) { 0 -> "green"; 1 -> "orange"; else -> "blue" }, "external",
            if (index == 1) "CRASHED" else "STARTED", "STARTED", true, false, "1.2.3",
            when (index) { 0 -> "UNVERIFIED_LOCAL"; 2 -> "INVALID_SIGNATURE"; else -> "VERIFIED_OFFICIAL" },
            if (index == 0) "PROVENANCE_MISSING" else "", "")

        private fun sample(plugins: List<PluginEntry> = (0..11).map(::entry) + PluginEntry(
            "built-in", "Runtime", "Application runtime", "settings", "neutral", "built-in", "STARTED",
            "", false, true, "1.2.3", "VERIFIED_OFFICIAL", "", ""), enabled: Boolean = true): DesktopUiNode.PluginOverview {
            fun button(id: String) = DesktopUiNode.Button(id, id, DesktopUiNode.TextToken.raw(id),
                null, DesktopUiNode.ButtonStyle.NORMAL, enabled)
            return DesktopUiNode.PluginOverview("plugins.overview", plugins, "2025-01-02T00:00:00Z", "", false,
                button("plugins.refresh"), button("plugins.manage"))
        }
    }
}
