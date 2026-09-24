package top.sywyar.pixivdownload.guicompose.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.ComposeDesktopUiNodeRenderer
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@DisplayName("卡片引导的布局、动画与可访问交互")
class OnboardingGuideHubTest {
    @Test
    @DisplayName("卡片从居中位置平滑左移，快速切换和反向返回均可打断动画")
    fun animatesAndInterruptsNavigation() = runComposeUiTest {
        mainClock.autoAdvance = false
        val events = mutableListOf<DesktopUiNode.Event>()
        setContent { Preview("light", 1120, hub(), events::add) }
        mainClock.advanceTimeBy(32)
        val initial = onNodeWithTag("hub.card.network").fetchSemanticsNode().boundsInRoot
        assertEquals(onNodeWithTag("preview").fetchSemanticsNode().boundsInRoot.center.x, initial.center.x, 1f)
        screenshot("overview")
        onNodeWithTag("hub.card.network").performClick()
        mainClock.advanceTimeBy(80)
        val partial = onNodeWithTag("hub.card.network").fetchSemanticsNode().boundsInRoot
        assertTrue(partial.left < initial.left)
        onNodeWithTag("hub.card.download").performClick()
        mainClock.advanceTimeBy(600)
        val final = onNodeWithTag("hub.card.network").fetchSemanticsNode().boundsInRoot
        assertTrue(final.left < partial.left)
        onNodeWithTag("hub.card.download").assertIsSelected()
        onNodeWithTag("hub.detail.download").assertIsDisplayed()
        screenshot("download")
        onNodeWithTag("open.download").performClick()
        mainClock.advanceTimeBy(32)
        assertEquals(listOf("open.download"), events.map { it.nodeId() })
        onNodeWithTag("hub.detail.download").assertExists()
        onNodeWithTag("hub.back.download").performClick()
        mainClock.advanceTimeBy(64)
        onNodeWithTag("hub.card.guide").performClick()
        mainClock.advanceTimeBy(600)
        onNodeWithTag("hub.detail.guide").assertIsDisplayed()
        onNodeWithTag("hub.back.guide").performKeyInput { pressKey(Key.Escape) }
        mainClock.advanceTimeBy(700)
        assertEquals(initial, onNodeWithTag("hub.card.network").fetchSemanticsNode().boundsInRoot)
        onNodeWithTag("hub.card.guide").assertIsFocused()
        onNodeWithTag("hub.detail.guide").assertDoesNotExist()
        onNodeWithTag("next").performClick()
        assertEquals(listOf("open.download", "next"), events.map { it.nodeId() })
    }

    @Test
    @DisplayName("小窗口显示独立详情，返回恢复卡片，深色界面与缩放均保持操作可达")
    fun supportsCompactDarkLayoutAndFontScaling() {
        for (fontScale in listOf(1f, 1.5f)) runComposeUiTest {
            mainClock.autoAdvance = false
            setContent { Preview("dark", 700, hub(), fontScale = fontScale) }
            mainClock.advanceTimeBy(32)
            onNodeWithTag("hub.card.network").performClick()
            mainClock.advanceTimeBy(600)
            onNodeWithTag("hub.card.network").assertDoesNotExist()
            onNodeWithTag("open.network").performScrollTo().assertIsDisplayed()
            onNodeWithTag("next").assertIsDisplayed()
            if (fontScale == 1f) screenshot("compact-network-dark")
            onNodeWithTag("hub.back.network").performScrollTo().performClick()
            mainClock.advanceTimeBy(600)
            onNodeWithTag("hub.card.network").assertIsDisplayed().assertIsFocused()
        }
    }

    @Test
    @DisplayName("减少动态效果时布局立即到位，示意图静止且不提供无效播放控件")
    fun reducesMotion() = runComposeUiTest(
        effectContext = object : MotionDurationScale { override val scaleFactor = 0f },
    ) {
        mainClock.autoAdvance = false
        setContent { Preview("light", 1120, hub()) }
        mainClock.advanceTimeBy(32)
        onNodeWithTag("hub.card.guide").performClick()
        mainClock.advanceTimeBy(32)
        onNodeWithTag("hub.detail.guide").assertIsDisplayed()
        onNodeWithTag("hub.demo.playback").assertDoesNotExist()
        screenshot("reduced-motion")
    }

    @Test
    @DisplayName("示意动画可以暂停、恢复和重播，动作不会触发浏览器或继续引导")
    fun pausesAndReplaysDemonstration() = runComposeUiTest {
        mainClock.autoAdvance = false
        val events = mutableListOf<DesktopUiNode.Event>()
        setContent { Preview("light", 1120, hub(), events::add) }
        mainClock.advanceTimeBy(32)
        onNodeWithTag("hub.card.download").performClick()
        mainClock.advanceTimeBy(600)
        onNodeWithContentDescription(resolve(hubToken("pause"))).performClick()
        mainClock.advanceTimeBy(4000)
        onNodeWithContentDescription(resolve(hubToken("play"))).assertExists().performClick()
        mainClock.advanceTimeBy(4000)
        onNodeWithContentDescription(resolve(hubToken("replay"))).assertExists().performClick()
        mainClock.advanceTimeBy(32)
        onNodeWithContentDescription(resolve(hubToken("pause"))).assertExists()
        assertTrue(events.isEmpty())
    }

    @Test
    @DisplayName("宿主刷新保留所选卡片，贡献撤回后返回概览")
    fun retainsSelectionAcrossRefreshAndHandlesWithdrawal() = runComposeUiTest {
        mainClock.autoAdvance = false
        var node by mutableStateOf(hub())
        setContent { Preview("light", 1120, node) }
        mainClock.advanceTimeBy(32)
        onNodeWithTag("hub.card.guide").performClick()
        mainClock.advanceTimeBy(500)
        runOnIdle { node = hub() }
        mainClock.advanceTimeBy(32)
        onNodeWithTag("hub.detail.guide").assertExists()
        runOnIdle { node = DesktopUiNode.OnboardingHub("hub", hub().cards().take(2), hub().next(), null) }
        mainClock.advanceTimeBy(700)
        onNodeWithTag("hub.detail.guide").assertDoesNotExist()
        onNodeWithTag("hub.card.network").assertIsDisplayed()
    }

    @Test
    @DisplayName("代理草稿在切换卡片后保留，由底部继续统一提交")
    fun retainsProxyDraftWhenSwitchingCards() = runComposeUiTest {
        var proxyHost by mutableStateOf("localhost")
        val actions = mutableListOf<String>()
        setContent {
            Preview("light", 1120, hub(proxyHost), { event ->
                if (event.type() == DesktopUiNode.EventType.CHANGE) proxyHost = event.value().values().first()
                else actions += event.nodeId()
            })
        }
        mainClock.advanceTimeBy(32)
        onNodeWithTag("hub.card.network").performClick()
        mainClock.advanceTimeBy(600)
        onNodeWithContentDescription("Proxy host").performScrollTo().performTextReplacement("edited.proxy")
        mainClock.advanceTimeBy(32)
        onNodeWithTag("hub.card.download").performClick()
        mainClock.advanceTimeBy(300)
        onNodeWithTag("hub.card.network").performClick()
        mainClock.advanceTimeBy(300)
        onNodeWithContentDescription("Proxy host").performScrollTo().assertTextEquals("edited.proxy")
        onNodeWithTag("next").performClick()
        assertEquals(listOf("next"), actions)
        screenshot("network-form")
    }

    @Test
    @DisplayName("代理输入停顿半秒后校验并抖动，修改与关闭代理清除错误，小窗口纵向排列")
    fun validatesProxyWithAnimatedFeedbackAndAdaptiveLayout() {
        for (width in listOf(700, 360)) runComposeUiTest {
            mainClock.autoAdvance = false
            var host by mutableStateOf("proxy.local")
            var port by mutableStateOf("8080")
            var enabled by mutableStateOf(true)
            setContent {
                PixivDownloaderTheme("light") {
                    Box(Modifier.size(width.dp, 700.dp)) {
                        OnboardingProxyForm(proxySettings(host, port, enabled = enabled), ::resolve) {
                            when (it.nodeId()) {
                                "proxy.host" -> host = it.value().values().first()
                                "proxy.port" -> port = it.value().values().first()
                                "proxy.enabled" -> enabled = it.value().values().first().toBoolean()
                            }
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(32)
            val hostBounds = onNodeWithTag("proxy.host").fetchSemanticsNode().boundsInRoot
            val portBounds = onNodeWithTag("proxy.port").fetchSemanticsNode().boundsInRoot
            val labelBounds = onNodeWithText("Proxy host").fetchSemanticsNode().boundsInRoot
            assertTrue(labelBounds.bottom <= hostBounds.top)
            if (width == 700) {
                assertEquals(hostBounds.top, portBounds.top, 1f)
                assertTrue(hostBounds.width > portBounds.width)
            } else assertTrue(portBounds.top > hostBounds.bottom)
            onNodeWithTag("proxy.port").performTextReplacement("70000")
            mainClock.advanceTimeBy(400)
            onNodeWithTag("proxy.port").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
            mainClock.advanceTimeBy(160)
            onNodeWithTag("proxy.port").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            assertTrue(kotlin.math.abs(onNodeWithTag("proxy.port").fetchSemanticsNode().boundsInRoot.left - portBounds.left) > 1f)
            onNodeWithTag("proxy.port").performTextReplacement("8181")
            mainClock.advanceTimeBy(32)
            onNodeWithTag("proxy.port").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
            mainClock.advanceTimeBy(900)
            onNodeWithTag("proxy.port").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
            onNodeWithTag("proxy.host").performTextReplacement("127.0.0.")
            mainClock.advanceTimeBy(1000)
            onNodeWithTag("proxy.host").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.Error, resolve(hubToken("network.invalid-host")),
            ))
            onNodeWithTag("proxy.host").performTextReplacement("127.0.0.1")
            mainClock.advanceTimeBy(1000)
            onNodeWithTag("proxy.host").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
            onNodeWithTag("proxy.host").performTextClearance()
            mainClock.advanceTimeBy(1000)
            onNodeWithTag("proxy.host").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            onNodeWithTag("proxy.enabled").performClick()
            mainClock.advanceTimeBy(500)
            onNodeWithTag("proxy.host").assertIsNotEnabled()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        }
    }

    @Test
    @DisplayName("从其它卡片继续时自动展开有误的代理输入并聚焦")
    fun revealsInvalidProxyOnContinue() = runComposeUiTest {
        var attempt by mutableIntStateOf(0)
        setContent {
            Preview("light", 1120, hub(proxyPort = "invalid", attempt = attempt), {
                if (it.nodeId() == "next") attempt++
            })
        }
        onNodeWithTag("hub.card.download").performClick()
        mainClock.advanceTimeBy(600)
        onNodeWithTag("next").performClick()
        mainClock.advanceTimeBy(700)
        onNodeWithTag("hub.card.network").assertIsSelected()
        onNodeWithTag("proxy.port").assertIsFocused()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        screenshot("network-invalid")
    }

    @Composable
    private fun Preview(
        theme: String,
        width: Int,
        node: DesktopUiNode.OnboardingHub,
        emit: (DesktopUiNode.Event) -> Unit = {},
        fontScale: Float = 1f,
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(1f, fontScale),
            LocalWindowInfo provides object : WindowInfo { override val isWindowFocused = true },
        ) {
            PixivDownloaderTheme(theme) {
                Box(Modifier.size(width.dp, 740.dp).background(LocalExperiencePalette.current.surface).testTag("preview")) {
                    ComposeDesktopUiNodeRenderer.Render(node, ::resolve, emit, Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val output = File("build/reports/ui/hub-$name.png")
        output.parentFile.mkdirs()
        org.jetbrains.skia.Image.makeFromBitmap(onNodeWithTag("preview").captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { output.writeBytes(it.bytes) }
        }
    }

    private val messages = Properties().apply {
        OnboardingGuideHubTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!
            .reader(Charsets.UTF_8).use(::load)
    }
    private val coreMessages = Properties().apply {
        File("../pixivdownload-app/src/main/resources/i18n/messages_en.properties")
            .reader(Charsets.UTF_8).use(::load)
    }
    private fun resolve(token: DesktopUiNode.TextToken) =
        messages.getProperty(token.key(), coreMessages.getProperty(token.key(), token.fallback()))

    private fun hub(proxyHost: String = "localhost", proxyPort: String = "8080", attempt: Int = 0) = DesktopUiNode.OnboardingHub(
        "hub",
        DesktopUiNode.OnboardingTopic.entries.map { topic ->
            val id = topic.name.lowercase()
            DesktopUiNode.OnboardingCard(
                id, topic, if (id == "guide") raw("Browse works") else hubToken("$id.title"),
                if (id == "guide") raw("View and organize works in your local gallery") else hubToken("$id.summary"),
                if (id == "guide") raw("Search by title or artist and filter the results.") else hubToken("$id.body"),
                DesktopUiNode.Button("open.$id", "open.$id", raw("Open $id"), null, DesktopUiNode.ButtonStyle.PRIMARY, true),
                if (topic == DesktopUiNode.OnboardingTopic.NETWORK) proxySettings(proxyHost, proxyPort, attempt) else null, false,
            )
        },
        DesktopUiNode.Button("next", "next", hubToken("continue"), null, DesktopUiNode.ButtonStyle.PRIMARY, true),
        null,
    )

    private fun proxySettings(
        host: String,
        port: String = "8080",
        attempt: Int = 0,
        enabled: Boolean = true,
    ) = DesktopUiNode.OnboardingProxySettings(
        DesktopUiNode.Toggle("proxy.enabled", "proxy.enabled", raw("Enable proxy"), null,
            DesktopUiNode.ToggleStyle.SWITCH, enabled, true),
        DesktopUiNode.TextInput("proxy.host", "proxy.host", raw("Proxy host"), null,
            DesktopUiNode.InputKind.TEXT, host, 18, 1, enabled),
        DesktopUiNode.TextInput("proxy.port", "proxy.port", raw("Proxy port"), null,
            DesktopUiNode.InputKind.NUMBER, port, 18, 1, enabled),
        attempt,
    )

    private fun raw(value: String) = DesktopUiNode.TextToken.raw(value)
}
