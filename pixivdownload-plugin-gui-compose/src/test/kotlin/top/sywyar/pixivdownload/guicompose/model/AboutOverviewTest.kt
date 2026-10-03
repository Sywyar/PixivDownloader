@file:Suppress("DEPRECATION")

package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.ComposeDesktopUiNodeRenderer
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.experiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import java.io.File
import java.text.MessageFormat
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AboutOverviewTest {
    @Test
    @DisplayName("关于页展开后复制完整可见平台数据，更新与项目链接保持可操作")
    fun copyPlatformInformation() = runComposeUiTest {
        val node = sample()
        val clipboard = MemoryClipboard()
        val events = mutableListOf<Event>()
        setContent {
            PixivDownloaderTheme("light") {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Box(Modifier.size(1100.dp, 940.dp).background(LocalExperiencePalette.current.surface)) {
                        ComposeDesktopUiNodeRenderer.Render(node, ::resolve, events::add)
                    }
                }
            }
        }
        screenshot("overview", "about.overview")
        onNodeWithTag("about.project").performClick()
        assertEquals("about.project", events.last().nodeId())
        onNodeWithTag("about.update.check").performClick()
        assertEquals("about.update.check", events.last().nodeId())
        onNodeWithTag("about.platform.copy").assertDoesNotExist()
        onNodeWithTag("about.platform.toggle").performScrollTo().performClick()
        onNodeWithTag("about.platform.copy").performScrollTo().performClick()
        node.facts().forEach {
            if (it.expandable()) {
                onNodeWithTag("about.platform.${it.id()}").assertDoesNotExist()
                val nested = "- ${resolve(it.label())}:\n" +
                    resolve(it.value()).lines().joinToString("\n") { entry -> "  - $entry" }
                assertTrue(clipboard.getText()!!.text.contains(nested))
            } else {
                onNodeWithTag("about.platform.${it.id()}").assertTextEquals(resolve(it.value()))
                assertTrue(clipboard.getText()!!.text.contains("- ${resolve(it.label())}: ${resolve(it.value())}"))
            }
        }
        onNodeWithTag("about.platform.plugins.toggle").performScrollTo()
        val label = onNodeWithTag("about.platform.plugins.label").getUnclippedBoundsInRoot()
        val versionLabel = onNodeWithTag("about.platform.version.label").getUnclippedBoundsInRoot()
        val group = onNodeWithTag("about.platform.plugins.group").getUnclippedBoundsInRoot()
        val version = onNodeWithTag("about.platform.version").getUnclippedBoundsInRoot()
        assertEquals(versionLabel.left, label.left)
        assertEquals(version.left, group.left)
        assertTrue(label.right < group.left)
        val toggle = onNodeWithTag("about.platform.plugins.toggle").getUnclippedBoundsInRoot()
        assertTrue(toggle.right - toggle.left < group.right - group.left)
        onNodeWithTag("about.platform.plugins.group").performTouchInput { click(centerRight) }
        onNodeWithTag("about.platform.plugins").assertDoesNotExist()
        screenshot("plugins-collapsed", "about.overview")
        onNodeWithTag("about.platform.plugins.toggle").performClick()
        resolve(node.facts().first { it.id() == "plugins" }.value()).lines().forEachIndexed { index, entry ->
            onNodeWithTag("about.platform.plugins.entry.$index").assertTextEquals(entry)
        }
        val list = onNodeWithTag("about.platform.plugins").getUnclippedBoundsInRoot()
        assertTrue(list.left >= group.left && list.right <= group.right)
        onNodeWithTag("about.platform.plugins.toggle").assertIsFocused()
        screenshot("plugins-expanded", "about.overview")
        onNodeWithTag("about.platform.plugins.toggle").performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("about.platform.plugins").assertDoesNotExist()
        onNodeWithTag("about.platform.plugins.toggle").assertIsFocused()
        assertTrue(clipboard.getText()!!.text.startsWith("### "))
        assertTrue(clipboard.getText()!!.text.contains(node.applicationName()))
        onNodeWithTag("about.platform.copy-status").assertExists()
        screenshot("platform", "about.overview")
        onNodeWithTag("about.platform.toggle").performScrollTo().performClick()
        onNodeWithTag("about.platform.copy").assertDoesNotExist()
    }

    @Test
    @DisplayName("窄窗口与减少动态效果下法律正文可滚动，关闭弹窗恢复触发器焦点")
    fun readersAndReducedMotion() = runComposeUiTest(
        effectContext = object : MotionDurationScale { override val scaleFactor = 0f },
    ) {
        val node = sample()
        setContent {
            PixivDownloaderTheme("dark") {
                Box(Modifier.size(360.dp, 580.dp).background(LocalExperiencePalette.current.surface)) {
                    ComposeDesktopUiNodeRenderer.Render(node, ::resolve, {})
                }
            }
        }
        onNodeWithTag("about.license").performScrollTo().performClick()
        onNodeWithTag("about.reader.close").assertIsFocused()
        onNodeWithTag("about.reader.done").assertIsDisplayed()
        onNodeWithTag("about.reader.content").assertTextEquals(node.license())
        screenshot("license-dark", "about.reader")
        onNodeWithTag("about.reader.done").performClick()
        onNodeWithTag("about.reader").assertDoesNotExist()
        onNodeWithTag("about.license").assertIsFocused()
        onNodeWithTag("about.disclaimer").performScrollTo().performClick()
        onNodeWithTag("about.reader.content").assertTextEquals(resolve(node.disclaimer()))
        screenshot("disclaimer-dark", "about.reader")
        onNodeWithTag("about.reader.close").performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("about.reader").assertDoesNotExist()
        onNodeWithTag("about.disclaimer").assertIsFocused()
        onNodeWithTag("about.platform.toggle").performScrollTo().performClick()
        onNodeWithTag("about.platform.plugins.toggle").performScrollTo().performClick()
        onNodeWithTag("about.platform.plugins").assertExists()
        val label = onNodeWithTag("about.platform.plugins.label").getUnclippedBoundsInRoot()
        val group = onNodeWithTag("about.platform.plugins.group").getUnclippedBoundsInRoot()
        assertEquals(label.left, group.left)
        assertTrue(label.bottom < group.top)
        onNodeWithTag("about.platform.plugins.entry.2").performScrollTo()
        screenshot("plugins-dark-narrow", "about.overview")
        onNodeWithTag("about.platform.copy").performScrollTo().assertIsDisplayed()
        screenshot("platform-dark-narrow", "about.overview")
    }

    @Test
    @DisplayName("放大文字与高对比度下插件详情留在信息项内，连续切换可逆且仍能复制全部")
    fun pluginDisclosureAtLargeText() = runComposeUiTest {
        val node = sample()
        val clipboard = MemoryClipboard()
        setContent {
            PixivDownloaderTheme("light") {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 1.6f),
                    LocalExperiencePalette provides experiencePalette(false, true),
                    LocalClipboardManager provides clipboard,
                ) {
                    Box(Modifier.size(600.dp, 650.dp).background(LocalExperiencePalette.current.surface)) {
                        ComposeDesktopUiNodeRenderer.Render(node, ::resolve, {})
                    }
                }
            }
        }
        onNodeWithTag("about.platform.toggle").performScrollTo().performClick()
        onNodeWithTag("about.platform.plugins.toggle").performScrollTo()
        mainClock.autoAdvance = false
        onNodeWithTag("about.platform.plugins.toggle").performClick()
        mainClock.advanceTimeBy(64)
        onNodeWithTag("about.platform.plugins.toggle").performKeyInput { pressKey(Key.Spacebar) }
        mainClock.advanceTimeBy(64)
        onNodeWithTag("about.platform.plugins.toggle").performKeyInput { pressKey(Key.Enter) }
        mainClock.autoAdvance = true
        onNodeWithTag("about.platform.plugins.toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, messages.getProperty("gui.compose.expanded")))
        onNodeWithTag("about.platform.plugins").assertExists()
        val group = onNodeWithTag("about.platform.plugins.group").getUnclippedBoundsInRoot()
        val label = onNodeWithTag("about.platform.plugins.label").getUnclippedBoundsInRoot()
        assertTrue(label.bottom < group.top)
        onNodeWithTag("about.platform.plugins.entry.0").performScrollTo()
        val entry = onNodeWithTag("about.platform.plugins.entry.0").getUnclippedBoundsInRoot()
        assertTrue(entry.left >= group.left && entry.right <= group.right)
        onNodeWithTag("about.platform.plugins.entry.2").performScrollTo()
        screenshot("plugins-large-text", "about.overview")
        onNodeWithTag("about.platform.copy").performScrollTo().performClick()
        resolve(node.facts().first { it.id() == "plugins" }.value()).lines()
            .forEach { assertTrue(clipboard.getText()!!.text.contains(it)) }
    }

    @Test
    @DisplayName("剪贴板不可用时保留平台信息并显示可恢复的失败提示")
    fun clipboardFailure() = runComposeUiTest {
        val clipboard = object : ClipboardManager {
            override fun getText(): AnnotatedString? = null
            override fun setText(annotatedString: AnnotatedString) { throw IllegalStateException("Clipboard unavailable") }
        }
        val node = sample()
        setContent {
            PixivDownloaderTheme("light") {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Box(Modifier.size(800.dp, 800.dp)) {
                        ComposeDesktopUiNodeRenderer.Render(node, ::resolve, {})
                    }
                }
            }
        }
        onNodeWithTag("about.platform.toggle").performScrollTo().performClick()
        onNodeWithTag("about.platform.copy").performScrollTo().performClick()
        onNodeWithTag("about.platform.copy-status").assertTextEquals(messages.getProperty("gui.compose.about.copy-failed"))
        onNodeWithTag("about.platform.version").assertTextEquals(resolve(node.facts().first { it.id() == "version" }.value()))
    }

    private fun ComposeUiTest.screenshot(name: String, tag: String) {
        val file = File("target/about-ui/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", file)
    }

    private class MemoryClipboard : ClipboardManager {
        private var value: AnnotatedString? = null
        override fun getText() = value
        override fun setText(annotatedString: AnnotatedString) { value = annotatedString }
    }

    companion object {
        private val messages = Properties().apply {
            AboutOverviewTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!
                .reader(Charsets.UTF_8).use(::load)
            File("../pixivdownload-app/src/main/resources/i18n/messages_en.properties")
                .reader(Charsets.UTF_8).use(::load)
        }
        private fun resolve(token: TextToken): String = if (token.key().isBlank()) token.fallback()
            else MessageFormat.format(messages.getProperty(token.key(), token.key()), *token.arguments().toTypedArray())

        private fun sample(): AboutOverview =
            DesktopConfigurationControllerTest.model(HashMap(), mapOf(
                "applicationVersion" to java.util.function.Function { _: Array<Any>? -> "2.3.4-fixture" },
            )).use {
                it.rebuild()
                val model = DesktopAboutViewTest.overview(it)
                val image = ImageData("image/x-icon",
                    File("../pixivdownload-app/src/main/resources/static/favicon.ico").readBytes())
                AboutOverview(
                    model.id(),
                    Image("about.icon", image, TextToken.raw("Application icon"), 80, 80, ScaleMode.FIT),
                    model.applicationName(),
                    model.version(),
                    model.checkUpdate(),
                    model.updateState(),
                    model.updates(),
                    model.links(),
                    listOf(AboutMaintainer(
                        Image("fixture.avatar", image, TextToken.raw("Avatar"), 38, 38, ScaleMode.FILL),
                        Link("fixture.profile", "fixture.open", TextToken.raw("Example maintainer"), null, true),
                        TextToken.key("desktop.ui.about.maintainer.role.author-core"),
                    )),
                    model.disclaimer(),
                    File("../LICENSE").readText(Charsets.UTF_8),
                    model.facts().map { fact ->
                        if (fact.id() == "plugins") AboutFact(
                            fact.id(), fact.label(),
                            TextToken.raw((1..14).joinToString("\n") { index ->
                                "fixture-plugin-with-a-long-identifier-$index-2.3.4-dev.ab123456.dirty(8.2.0)"
                            }),
                            true
                        ) else fact
                    },
                )
            }
    }
}
