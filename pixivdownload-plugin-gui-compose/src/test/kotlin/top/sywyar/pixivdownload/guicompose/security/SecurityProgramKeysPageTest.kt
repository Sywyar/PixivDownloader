package top.sywyar.pixivdownload.guicompose.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
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
class SecurityProgramKeysPageTest {
    @Test
    @DisplayName("程序访问密钥占据安全页内容区，返回与 Esc 回到概览，撤销确认浮在列表之上")
    fun listPage() = runComposeUiTest(effectContext = object : MotionDurationScale { override val scaleFactor = 0f }) {
        var view by mutableStateOf(keys("list"))
        val events = mutableListOf<Event>()
        setContent { PixivDownloaderTheme("dark") {
            Box(Modifier.size(1100.dp, 760.dp).background(LocalExperiencePalette.current.background).testTag("frame")) {
                SecurityOverview(overview(view), ::resolve, { events += it })
            }
        } }
        onNodeWithTag("program.keys.page").assertExists()
        onNodeWithTag("security.sheet").assertDoesNotExist()
        onNodeWithTag("security.password").assertDoesNotExist()
        onNodeWithTag("program.keys.leave").performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        onAllNodesWithTag("program.keys.row").assertCountEquals(2)
        onNodeWithText("Public HTTP allowed").assertExists()
        screenshot("program-keys-list-dark")
        onNodeWithTag("program.keys.edit.$FIRST").performClick()
        assertEquals("program.keys.edit.$FIRST", events.last().nodeId())
        onNodeWithTag("program.keys.prompt.$FIRST").assertTextEquals(security.getProperty("instructions.title")).performClick()
        assertEquals("program.keys.prompt.$FIRST", events.last().nodeId())
        onNodeWithTag("program.keys.leave").performKeyInput { pressKey(Key.Escape) }
        assertEquals("security.close", events.last().nodeId())
        runOnIdle { view = keys("revoke") }
        onNodeWithTag("security.sheet").assertIsDisplayed()
        onNodeWithTag("program.keys.cancel").assertIsFocused()
        onAllNodesWithTag("program.keys.row").assertCountEquals(2)
        onNodeWithTag("program.keys.edit.$FIRST").assertIsNotEnabled()
        screenshot("program-keys-revoke-dark")
        onNodeWithTag("program.keys.revoke").performClick()
        assertEquals("program.keys.revoke", events.last().nodeId())
        runOnIdle { view = keys("list", rows = emptyList()) }
        onNodeWithTag("security.sheet").assertDoesNotExist()
        onNodeWithTag("program.keys.empty").assertExists()
        onNodeWithText(security.getProperty("empty.description")).assertExists()
        onNodeWithTag("program.keys.empty.create").performClick()
        assertEquals("program.keys.create", events.last().nodeId())
        onNodeWithTag("program.keys.leave").performClick()
        assertEquals("security.close", events.last().nodeId())
    }

    @Test
    @DisplayName("编辑页的来源单选与权限分组可操作，矮窗口中底部动作保持可见")
    fun editorPage() = runComposeUiTest(effectContext = object : MotionDurationScale { override val scaleFactor = 0f }) {
        var view by mutableStateOf(keys("edit", any = false, selected = emptySet()))
        val events = mutableListOf<Event>()
        val clipboard = object : ClipboardManager {
            private var content: AnnotatedString? = null
            override fun getText() = content
            override fun setText(annotatedString: AnnotatedString) { content = annotatedString }
        }
        setContent { PixivDownloaderTheme("light") {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                Box(Modifier.size(620.dp, 520.dp).background(LocalExperiencePalette.current.background).testTag("frame")) {
                    SecurityOverview(overview(view), ::resolve, { events += it })
                }
            }
        } }
        onNodeWithTag("program.keys.networks").assertExists()
        onNodeWithTag("program.keys.review").assertIsDisplayed()
        onNodeWithTag("program.keys.cancel").assertIsDisplayed()
        onNodeWithTag("program.keys.source.any").performScrollTo().performClick()
        assertEquals(EventType.CHANGE, events.last().type())
        assertEquals("program.keys.any", events.last().nodeId())
        onNodeWithTag(PERMISSION).assertDoesNotExist()
        onNodeWithTag("program.keys.group.core.expand").performScrollTo().performClick()
        onNodeWithTag(PERMISSION).performScrollTo().performClick()
        assertEquals(PERMISSION, events.last().nodeId())
        onNodeWithTag("program.keys.review").assertIsDisplayed()
        screenshot("program-keys-edit-light-compact")
        onNodeWithTag("program.keys.cancel").performKeyInput { pressKey(Key.Escape) }
        assertEquals("program.keys.cancel", events.last().nodeId())
        runOnIdle { view = keys("review", selected = setOf(PERMISSION)) }
        onNodeWithTag("program.keys.review").assertExists()
        onNodeWithText("Read status").assertExists()
        onNodeWithTag("program.keys.back").assertTextContains("Back to edit").performClick()
        assertEquals("program.keys.back", events.last().nodeId())
        screenshot("program-keys-review-light-compact")
        runOnIdle { view = keys("created", secret = "pdk_example_secret_value") }
        onNodeWithTag("program.keys.secret").assertTextEquals("pdk_example_secret_value")
        onNodeWithTag("program.keys.footer").assert(hasAnyDescendant(hasTestTag("program.keys.copy")).not())
        onNodeWithTag("program.keys.copy").assertIsDisplayed().assertContentDescriptionContains("Copy key").performClick()
        assertEquals("program.keys.copy", events.last().nodeId())
        val prompt = MessageFormat.format(security.getProperty("created.instructions"), referenceUrl)
        onNodeWithTag("program.keys.instructions").assertTextEquals(prompt)
        onNodeWithTag("program.keys.copy-instructions").performScrollTo().performClick()
        onNodeWithText(security.getProperty("instructions.copied")).assertExists()
        assertEquals(prompt, clipboard.getText()?.text)
        assertTrue(prompt.contains(referenceUrl))
        assertFalse(clipboard.getText()!!.text.contains("pdk_example_secret_value"))
        onNodeWithTag("program.keys.copy").performScrollTo().performClick()
        runOnIdle { view = keys("created", secret = "pdk_example_secret_value", notice = sec("copied")) }
        onNodeWithTag("program.keys.copy.notice").assertTextEquals("Key copied.")
        onNodeWithTag("program.keys.copy-instructions").assertTextContains(security.getProperty("copy.instructions"))
        onNodeWithTag("security.error").assertDoesNotExist()
        onNodeWithTag("program.keys.verify").performScrollTo()
            .assertTextContains("Bearer \$PIXIV_PROGRAM_KEY\" $SELF_URL", substring = true)
            .assert(hasText("pdk_example_secret_value", substring = true).not())
        screenshot("program-keys-created-light-compact")
        runOnIdle { view = keys("created", secret = "pdk_example_secret_value", prompt = null) }
        onNodeWithText(security.getProperty("instructions.unavailable")).assertExists()
        onNodeWithTag("program.keys.copy-instructions").assertDoesNotExist()
        onNodeWithTag("program.keys.copy").performScrollTo().performClick()
        assertEquals("program.keys.copy", events.last().nodeId())
    }

    @Test
    @DisplayName("已有密钥的提示词页不显示秘密，可复制提示词，完成与 Esc 返回列表")
    fun promptPage() = runComposeUiTest(effectContext = object : MotionDurationScale { override val scaleFactor = 0f }) {
        val events = mutableListOf<Event>()
        val clipboard = object : ClipboardManager {
            private var content: AnnotatedString? = null
            override fun getText() = content
            override fun setText(annotatedString: AnnotatedString) { content = annotatedString }
        }
        val view = keys("prompt", prompt = TextToken("security", "instructions.restricted", "", listOf(referenceUrl)))
        setContent { PixivDownloaderTheme("light") {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                Box(Modifier.size(620.dp, 520.dp).background(LocalExperiencePalette.current.background).testTag("frame")) {
                    SecurityOverview(overview(view), ::resolve, { events += it })
                }
            }
        } }
        onNodeWithTag("program.keys.secret").assertDoesNotExist()
        onNodeWithTag("program.keys.verify").assertDoesNotExist()
        onNodeWithText("arta").assertExists()
        val prompt = MessageFormat.format(security.getProperty("instructions.restricted"), referenceUrl)
        onNodeWithTag("program.keys.instructions").assertTextEquals(prompt)
        onNodeWithTag("program.keys.copy-instructions").performScrollTo().performClick()
        assertEquals(prompt, clipboard.getText()?.text)
        onNodeWithTag("program.keys.cancel").assertTextEquals(security.getProperty("done")).performClick()
        assertEquals("program.keys.cancel", events.last().nodeId())
        onNodeWithTag("program.keys.leave").performKeyInput { pressKey(Key.Escape) }
        assertEquals("program.keys.cancel", events.last().nodeId())
        screenshot("program-keys-prompt-light-compact")
    }

    @Test
    fun createdPageShowsTheChinesePromptAndBothCopyActionsInDarkMode() = runComposeUiTest {
        val messages = Properties().apply {
            File("../pixivdownload-app/src/main/resources/i18n/web/security.properties").reader(Charsets.UTF_8).use(::load)
        }
        val view = keys("created", secret = "example-program-key")
        setContent { PixivDownloaderTheme("dark") {
            Box(Modifier.size(980.dp, 690.dp).background(LocalExperiencePalette.current.background).testTag("frame")) {
                SecurityOverview(overview(view), { resolve(it, messages) }, {})
            }
        } }
        onNodeWithTag("program.keys.copy").assertIsDisplayed()
        onNodeWithTag("program.keys.instructions").assertTextEquals(MessageFormat.format(messages.getProperty("created.instructions"), referenceUrl))
        onNodeWithTag("program.keys.copy-instructions").assertIsDisplayed()
        onNodeWithTag("program.keys.cancel").assertIsDisplayed()
        screenshot("program-keys-created-dark-zh")
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val target = File("target/security-ui/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onNodeWithTag("frame").captureToImage().toAwtImage(), "png", target)
    }

    companion object {
        private const val referenceUrl = "https://studio.example.test:7443/api/openapi.json"
        private const val SELF_URL = "https://studio.example.test:7443/api/security/program-keys/self"
        private const val FIRST = "11111111-1111-1111-1111-111111111111"
        private const val PERMISSION = "program.keys.permission.status"
        private val security = Properties().apply {
            File("../pixivdownload-app/src/main/resources/i18n/web/security_en.properties").reader(Charsets.UTF_8).use(::load)
        }
        private val compose = Properties().apply {
            SecurityProgramKeysPageTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!
                .reader(Charsets.UTF_8).use(::load)
        }
        private fun resolve(token: TextToken, messages: Properties = security): String {
            val pattern = when {
                token.key().isBlank() -> return token.fallback()
                token.namespace() == "security" -> messages.getProperty(token.key(), token.key())
                token.key() == "desktop.ui.page.security" -> "Security"
                else -> compose.getProperty(token.key(), token.key())
            }
            return if (token.arguments().isEmpty()) pattern else MessageFormat.format(pattern, *token.arguments().toTypedArray())
        }
        private fun sec(key: String) = TextToken("security", key, "", emptyList())

        private fun keys(mode: String, any: Boolean = true, selected: Set<String> = setOf(PERMISSION),
                         rows: List<ProgramKeyRow>? = null, secret: String = "", notice: TextToken? = null,
                         prompt: TextToken? = TextToken("security", "created.instructions", "", listOf(referenceUrl))): ProgramKeysView {
            val editable = mode == "edit"
            val inputs = listOf(
                TextInput("program.keys.name", "program.keys.name", sec("name"), null, InputKind.TEXT, "Backup script", 24, 1, editable, 0),
                TextInput("program.keys.networks", "program.keys.networks", sec("source.addresses"), null, InputKind.MULTILINE,
                    "192.168.1.0/24", 24, 4, editable, 0))
            fun permission(id: String, label: String, owner: String) = Toggle(id, id, TextToken.raw(label), TextToken.raw(owner),
                ToggleStyle.CHECKBOX, id in selected, editable)
            val groups = listOf(
                ProgramPermissionGroup("program.keys.group.core", TextToken.raw("Core"), listOf(
                    permission(PERMISSION, "Read status", "Core"),
                    permission("program.keys.permission.downloads", "Create downloads", "Core"))),
                ProgramPermissionGroup("program.keys.group.ai", TextToken.raw("AI models"), listOf(
                    permission("program.keys.permission.models", "Read models", "AI models"))))
            val list = rows ?: listOf(
                ProgramKeyRow(FIRST, "arta", 6, true, emptyList(), 1_794_600_000_000, 0, "HTTPS_REQUIRED",
                    "program.keys.edit.$FIRST", "program.keys.revoke.$FIRST", "program.keys.transport.$FIRST", sec("transport.REVIEWED"),
                    "program.keys.prompt.$FIRST"),
                ProgramKeyRow("2", "NAS sync", 2, false, listOf("192.168.1.0/24", "2001:db8::/64"), 1_794_000_000_000,
                    1_794_650_000_000, "HTTP_ALLOWED", "program.keys.edit.2", "program.keys.revoke.2",
                    "program.keys.transport.2", sec("transport.HTTPS_REQUIRED"), "program.keys.prompt.2"))
            return ProgramKeysView(mode, if (mode in listOf("list", "revoke", "transport")) list else emptyList(), inputs, groups,
                Toggle("program.keys.any", "program.keys.any", sec("source.any"), null, ToggleStyle.CHECKBOX, any, editable),
                secret, prompt, if (mode == "created") SELF_URL else "", notice, if (mode == "revoke" || mode == "prompt") "arta" else "", "HTTP_ALLOWED",
                false, true, true)
        }

        private fun overview(view: ProgramKeysView): DesktopUiNode.SecurityOverview {
            val mode = view.mode()
            val ids = buildList {
                addAll(listOf("security.password", "security.sessions", "security.program-keys", "security.connection", "security.close"))
                addAll(listOf("refresh", "create", "cancel", "review", "save", "back", "revoke", "confirm", "copy", "group.core", "group.ai")
                    .map { "program.keys.$it" })
                view.keys().forEach { addAll(listOf(it.editAction(), it.revokeAction(), it.transportAction(), it.promptAction())) }
            }
            val enabled = { id: String -> when {
                id.startsWith("program.keys.edit.") || id.startsWith("program.keys.revoke.") || id.startsWith("program.keys.transport.")
                        || id.startsWith("program.keys.prompt.") -> mode == "list"
                id == "program.keys.review" -> mode == "edit"
                id == "program.keys.save" || id == "program.keys.back" -> mode == "review"
                id == "program.keys.revoke" -> mode == "revoke"
                id == "program.keys.copy" -> view.secret().isNotEmpty()
                else -> true
            } }
            val actions = ids.map { Button(it, it, TextToken.raw(it), null, ButtonStyle.NORMAL, enabled(it)) }
            return DesktopUiNode.SecurityOverview("security.overview", "program-keys", false, 8, emptyList(),
                Toggle("security.https", "security.https", TextToken.raw("https"), null, ToggleStyle.SWITCH, false, true),
                actions, null, "", 0, 0, "", "", false, emptyList(), view)
        }
    }
}
