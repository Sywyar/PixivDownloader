package top.sywyar.pixivdownload.guicompose.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import java.io.File
import java.text.MessageFormat
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SecurityOverviewTest {
    @Test
    @DisplayName("可选安全入口随贡献出现和撤回，点击分派贡献动作")
    fun contributedNavigation() = runComposeUiTest {
        val entry = Button("security.navigation.sample.manage", "security.navigation.sample.manage",
            TextToken.raw("Shared access"), TextToken.raw("Control shared access"), ButtonStyle.NORMAL, true,
            top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon.SECURITY)
        var navigation by mutableStateOf<List<Button>>(emptyList())
        val events = mutableListOf<Event>()
        setContent { PixivDownloaderTheme("light") {
            Box(Modifier.size(900.dp, 800.dp)) {
                SecurityOverview(sample("", navigation = navigation), ::resolve, { events += it })
            }
        } }
        onNodeWithTag(entry.id()).assertDoesNotExist()
        runOnIdle { navigation = listOf(entry) }
        onNodeWithTag(entry.id()).performScrollTo().performClick()
        assertEquals(entry.id(), events.last().nodeId())
        runOnIdle { navigation = emptyList() }
        onNodeWithTag(entry.id()).assertDoesNotExist()
        onNodeWithTag("security.connection").assertExists()
    }

    @Test
    @DisplayName("密码停顿校验不提前标错确认框，错误恢复后可提交并清空秘密")
    fun passwordFlow() = runComposeUiTest {
        var panel by mutableStateOf("")
        var notice by mutableStateOf<TextToken?>(null)
        var revision by mutableLongStateOf(0)
        var success by mutableLongStateOf(0)
        var errorField by mutableStateOf("")
        val events = mutableListOf<Event>()
        setContent { PixivDownloaderTheme("light") {
            Box(Modifier.size(1100.dp, 820.dp).background(LocalExperiencePalette.current.surface)) {
                SecurityOverview(sample(panel, notice = notice, errorField = errorField, revision = revision, success = success), ::resolve, {
                    events += it
                    if (it.type() == EventType.CHANGE) { notice = null; errorField = "" }
                    if (it.nodeId() == "security.password") panel = "password"
                    if (it.nodeId() == "security.close") { panel = ""; revision++ }
                })
            }
        } }
        screenshot("overview", "security.overview")
        onNodeWithTag("security.password").performClick()
        onNodeWithTag("security.current.input").assertIsFocused().performTextInput("old-password")
        onNodeWithTag("security.new.input").performTextInput("short")
        mainClock.advanceTimeBy(600)
        onNodeWithTag("security.new.input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        onNodeWithTag("security.confirm.input").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        screenshot("password-error")
        onNodeWithTag("security.new.input").performTextReplacement("new-password")
        onNodeWithTag("security.confirm.input").performTextInput("new-password")
        mainClock.advanceTimeBy(600)
        onNodeWithTag("security.submit").performClick()
        assertEquals("security.submit", events.last().nodeId())
        runOnIdle { notice = key("invalid-current"); errorField = "current" }
        onNodeWithTag("security.current.input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        onNodeWithTag("security.new.input").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.InputText, androidx.compose.ui.text.AnnotatedString("new-password")))
        runOnIdle { notice = null; errorField = ""; panel = ""; revision++; success++ }
        onNodeWithTag("security.sheet").assertDoesNotExist()
        onNodeWithTag("security.password").performClick()
        onNodeWithTag("security.current.input").assertTextEquals("")
        onNodeWithTag("security.new.input").assertTextEquals("")
    }

    @Test
    @DisplayName("深色弹窗支持减少动态效果、滚动表单和安全取消焦点")
    fun narrowDialogs() = runComposeUiTest(effectContext = object : MotionDurationScale { override val scaleFactor = 0f }) {
        var panel by mutableStateOf("")
        var busy by mutableStateOf(false)
        val values = mutableStateMapOf("domain" to "localhost", "port" to "8080", "https" to "false")
        setContent { PixivDownloaderTheme("dark") {
            Box(Modifier.size(430.dp, 580.dp)) {
                SecurityOverview(sample(panel, busy, values), ::resolve, { event ->
                    if (event.type() == EventType.CHANGE) values[event.nodeId().removePrefix("security.").removeSuffix(".input")] = event.value().values().first()
                    else when (event.nodeId()) {
                        "security.connection" -> panel = "connection"
                        "security.sessions" -> panel = "sessions"
                        "security.close" -> panel = ""
                    }
                })
            }
        } }
        onNodeWithTag("security.connection").performScrollTo().performClick()
        onNodeWithTag("security.save").assertIsDisplayed()
        screenshot("connection-dark")
        onNodeWithTag("security.certificate.expand").performScrollTo().performClick()
        onNodeWithTag("security.private-key.input").performScrollTo().assertIsDisplayed()
        onNodeWithTag("security.save").assertIsDisplayed()
        runOnIdle { busy = true }
        onNodeWithTag("security.sheet.close").assertIsNotEnabled()
        onNodeWithTag("security.save").assertIsNotEnabled()
        runOnIdle { busy = false }
        onNodeWithTag("security.cancel").performClick()
        onNodeWithTag("security.sessions").performScrollTo().performClick()
        onNodeWithTag("security.cancel").assertIsFocused()
        screenshot("sessions-dark")
    }

    @Test
    @DisplayName("紧凑中文连接表单可滚动且保存操作始终可见")
    fun compactChineseForm() = runComposeUiTest {
        val chinese = Properties().apply {
            SecurityOverviewTest::class.java.getResourceAsStream("/i18n/web/gui-compose.properties")!!.reader(Charsets.UTF_8).use(::load)
        }
        setContent { PixivDownloaderTheme("light") {
            Box(Modifier.size(350.dp, 410.dp).background(LocalExperiencePalette.current.surface)
                .testTag("security.compact")) {
                SecurityConnectionPanel(sample("connection", values = mapOf("domain" to "localhost", "port" to "8080")),
                    { token -> chinese.getProperty(token.key(), token.key()) }, {})
            }
        } }
        onNodeWithTag("security.save").assertIsDisplayed()
        onNodeWithTag("security.certificate.expand").performScrollTo().performClick()
        onNodeWithTag("security.private-key.input").performScrollTo().assertIsDisplayed()
        onNodeWithTag("security.save").assertIsDisplayed()
        screenshot("connection-compact-chinese", "security.compact")
    }

    private fun ComposeUiTest.screenshot(name: String, tag: String = "security.sheet") {
        val target = File("target/security-ui/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", target)
    }

    companion object {
        private fun key(name: String) = TextToken("gui-compose", "gui.compose.security.$name", "", emptyList())
        private val messages = Properties().apply {
            SecurityOverviewTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!.reader(Charsets.UTF_8).use(::load)
        }
        private fun resolve(token: TextToken): String = when {
            token.key() == "desktop.ui.page.security" -> "Security"
            token.key().isBlank() -> token.fallback()
            else -> MessageFormat.format(messages.getProperty(token.key(), token.key()), *token.arguments().toTypedArray())
        }
        private fun sample(panel: String, busy: Boolean = false, values: Map<String, String> = emptyMap(),
            notice: TextToken? = null, errorField: String = "", revision: Long = 0, success: Long = 0,
            navigation: List<Button> = emptyList()): DesktopUiNode.SecurityOverview {
            val inputs = listOf("current", "new", "confirm", "domain", "port", "certificate", "private-key").map {
                TextInput("security.$it.input", "security.$it", key(it), null,
                    if (it in listOf("current", "new", "confirm")) InputKind.PASSWORD else InputKind.TEXT,
                    values[it].orEmpty(), 24, 1, !busy, revision)
            }
            val actions = listOf("password", "sessions", "connection", "close", "submit", "logout", "save").map {
                Button("security.$it", "security.$it", key(it), null, ButtonStyle.NORMAL, !busy)
            }
            return DesktopUiNode.SecurityOverview("security.overview", panel, busy, 8, inputs,
                Toggle("security.https", "security.https", key("https"), null, ToggleStyle.SWITCH, values["https"] == "true", !busy),
                actions, notice, errorField, revision, success, "password", "http://localhost:8080", false, navigation)
        }
    }
}
