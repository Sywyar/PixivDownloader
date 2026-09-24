package top.sywyar.pixivdownload.guicompose.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.semantics.SemanticsProperties
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
import java.text.MessageFormat
import java.util.Properties
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@DisplayName("账户表单的渐进展开与校验动效")
class OnboardingAccountFormTest {
    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    @Suppress("DEPRECATION")
    @DisplayName("输入法组词期间暂停展开和校验，提交候选后才重新计算停顿时间")
    fun waitsForImeCompositionToCommit() = runComposeUiTest {
        mainClock.autoAdvance = false
        var inputMethod: PlatformTextInputMethodRequest? = null
        setContent {
            InterceptPlatformTextInput(
                interceptor = { request, nextHandler ->
                    inputMethod = request
                    nextHandler.startInputMethod(request)
                },
            ) { Preview("light", account()) }
        }
        mainClock.advanceTimeBy(400)
        onNodeWithTag("username").performClick()
        mainClock.advanceTimeBy(32)
        runOnIdle { checkNotNull(inputMethod).onEditCommand(listOf(SetComposingTextCommand("guan", 1))) }
        mainClock.advanceTimeBy(2000)
        onNodeWithTag("password").assertDoesNotExist()
        runOnIdle { checkNotNull(inputMethod).onEditCommand(listOf(FinishComposingTextCommand())) }
        mainClock.advanceTimeBy(1800)
        onNodeWithTag("password").performClick()
        mainClock.advanceTimeBy(32)
        runOnIdle { checkNotNull(inputMethod).onEditCommand(listOf(SetComposingTextCommand("sample", 1))) }
        mainClock.advanceTimeBy(2000)
        assertFalse(onNodeWithTag("password").fetchSemanticsNode().config.contains(SemanticsProperties.Error))
        onNodeWithTag("finish").assertDoesNotExist()
        runOnIdle { checkNotNull(inputMethod).onEditCommand(listOf(FinishComposingTextCommand())) }
        mainClock.advanceTimeBy(1800)
        assertTrue(onNodeWithTag("password").fetchSemanticsNode().config.contains(SemanticsProperties.Error))
    }

    @Test
    @DisplayName("用户名保留，密码向下展开且整组在每一帧保持居中，最后只能手动提交")
    fun expandsAroundTheCenterWithoutSubmitting() {
        for (theme in listOf("light", "dark")) runComposeUiTest {
            mainClock.autoAdvance = false
            val events = mutableListOf<DesktopUiNode.Event>()
            setContent { Preview(theme, account(), events::add) }
            mainClock.advanceTimeBy(400)
            val initial = onNodeWithTag("username").fetchSemanticsNode().boundsInRoot
            val usernameLabel = onNodeWithText(resolve(account().username().label())).fetchSemanticsNode().boundsInRoot
            assertTrue(usernameLabel.bottom < initial.top)
            onNodeWithTag("username").assertTextEquals("")
            assertEquals(300f, onNodeWithTag("onboarding.account.form").fetchSemanticsNode().boundsInRoot.center.y, 1f)
            onNodeWithTag("password").assertDoesNotExist()
            screenshot("$theme-username")

            onNodeWithTag("username").performTextInput("Administrator")
            mainClock.advanceTimeBy(540)
            val nearStart = onNodeWithTag("username").fetchSemanticsNode().boundsInRoot
            mainClock.advanceTimeBy(160)
            val halfway = onNodeWithTag("username").fetchSemanticsNode().boundsInRoot
            val form = onNodeWithTag("onboarding.account.form").fetchSemanticsNode().boundsInRoot
            assertEquals(300f, form.center.y, 1f)
            assertTrue(halfway.top < nearStart.top)
            screenshot("$theme-expanding")
            mainClock.advanceTimeBy(400)
            val user = onNodeWithTag("username").fetchSemanticsNode().boundsInRoot
            val password = onNodeWithTag("password").fetchSemanticsNode().boundsInRoot
            val passwordLabel = onNodeWithText(resolve(account().password().label())).fetchSemanticsNode().boundsInRoot
            assertTrue(user.top < halfway.top)
            assertTrue(passwordLabel.top > user.bottom)
            assertTrue(passwordLabel.bottom < password.top)
            onNodeWithTag("password").assertTextEquals("")
            assertEquals(300f, onNodeWithTag("onboarding.account.form").fetchSemanticsNode().boundsInRoot.center.y, 1f)
            onNodeWithTag("username").assertTextEquals("Administrator")
            onNodeWithTag("finish").assertDoesNotExist()
            screenshot("$theme-password")

            onNodeWithTag("password").performTextInput("Sample-password-92")
            mainClock.advanceTimeBy(1800)
            onNodeWithTag("finish").assertIsEnabled()
            assertEquals(300f, onNodeWithTag("onboarding.account.form").fetchSemanticsNode().boundsInRoot.center.y, 1f)
            assertFalse(events.any { it.type() == DesktopUiNode.EventType.ACTIVATE })
            screenshot("$theme-ready")
            onNodeWithTag("finish").performClick()
            assertEquals(1, events.count { it.type() == DesktopUiNode.EventType.ACTIVATE })
        }
    }

    @Test
    @DisplayName("停顿计时随输入重新开始，用户名清空时保留密码草稿但禁止完成")
    fun restartsIdleTimerAndKeepsRevealedFields() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { Preview("light", account()) }
        mainClock.advanceTimeBy(400)
        onNodeWithTag("username").performTextInput("Adm")
        mainClock.advanceTimeBy(300)
        onNodeWithTag("username").performTextInput("in")
        mainClock.advanceTimeBy(300)
        onNodeWithTag("password").assertDoesNotExist()
        mainClock.advanceTimeBy(900)
        onNodeWithTag("password").performTextInput("Sample-password-92")
        mainClock.advanceTimeBy(1800)
        onNodeWithTag("finish").assertIsEnabled()
        onNodeWithTag("username").performTextClearance()
        mainClock.advanceTimeBy(1800)
        onNodeWithTag("password").assertExists()
        onNodeWithTag("finish").assertIsNotEnabled()
        onNodeWithTag("username").performTextInput("Admin")
        mainClock.advanceTimeBy(1800)
        onNodeWithTag("finish").assertIsEnabled()
    }

    @Test
    @DisplayName("短密码停顿后变红并衰减抖动，继续输入立即取消错误且不自动提交")
    fun shakesInvalidPasswordAndRecoversOnEdit() = runComposeUiTest {
        mainClock.autoAdvance = false
        val events = mutableListOf<DesktopUiNode.Event>()
        setContent { Preview("light", account(), events::add) }
        mainClock.advanceTimeBy(400)
        onNodeWithTag("username").performTextInput("Admin")
        mainClock.advanceTimeBy(1800)
        val field = onNodeWithTag("password")
        field.performTextInput("short")
        val originalX = field.fetchSemanticsNode().boundsInRoot.center.x
        mainClock.advanceTimeBy(532)
        val samples = (1..10).map {
            mainClock.advanceTimeBy(32)
            field.fetchSemanticsNode().boundsInRoot.center.x
        }
        assertTrue(samples.any { abs(it - originalX) >= 6f })
        assertTrue(samples.any { it > originalX } && samples.any { it < originalX })
        mainClock.advanceTimeBy(200)
        assertEquals(originalX, field.fetchSemanticsNode().boundsInRoot.center.x, .5f)
        assertTrue(field.fetchSemanticsNode().config.contains(SemanticsProperties.Error))
        val pixels = field.captureToImage().toPixelMap()
        assertTrue(pixels[1, pixels.height / 2].red > pixels[1, pixels.height / 2].green + .2f)
        screenshot("light-short-password")
        onNodeWithTag("finish").assertDoesNotExist()

        field.performTextInput("-password-92")
        mainClock.advanceTimeBy(32)
        assertFalse(field.fetchSemanticsNode().config.contains(SemanticsProperties.Error))
        mainClock.advanceTimeBy(1800)
        onNodeWithTag("finish").assertIsEnabled()
        field.performTextClearance()
        mainClock.advanceTimeBy(1800)
        assertTrue(field.fetchSemanticsNode().config.contains(SemanticsProperties.Error))
        onNodeWithTag("finish").assertIsNotEnabled()
        assertFalse(events.any { it.type() == DesktopUiNode.EventType.ACTIVATE })
    }

    @Test
    @DisplayName("停用动效时仍显示校验反馈，错误框不发生位移")
    fun respectsDisabledMotion() = runComposeUiTest(effectContext = object : MotionDurationScale {
        override val scaleFactor = 0f
    }) {
        mainClock.autoAdvance = false
        setContent { Preview("dark", account()) }
        mainClock.advanceTimeBy(64)
        onNodeWithTag("username").performTextInput("Admin")
        mainClock.advanceTimeBy(1400)
        val field = onNodeWithTag("password")
        field.performTextInput("short")
        val x = field.fetchSemanticsNode().boundsInRoot.center.x
        mainClock.advanceTimeBy(532)
        repeat(15) {
            mainClock.advanceTimeBy(32)
            assertEquals(x, field.fetchSemanticsNode().boundsInRoot.center.x, .5f)
        }
        assertTrue(field.fetchSemanticsNode().config.contains(SemanticsProperties.Error))
    }

    @Test
    @DisplayName("刷新和提交失败保留密码，显式修订会清空密码并撤销完成状态")
    fun preservesDraftAcrossRefreshAndClearsOnRevision() = runComposeUiTest {
        mainClock.autoAdvance = false
        var node by mutableStateOf(account())
        setContent { Preview("light", node) }
        mainClock.advanceTimeBy(400)
        onNodeWithTag("username").performTextInput("Admin")
        mainClock.advanceTimeBy(1800)
        onNodeWithTag("password").performTextInput("Sample-password-92")
        mainClock.advanceTimeBy(1800)
        runOnIdle { node = account(busy = true, username = "Admin") }
        mainClock.advanceTimeBy(600)
        onNodeWithTag("finish").assertIsNotEnabled()
        screenshot("light-submitting")
        runOnIdle { node = account(username = "Admin", notice = "Unable to create account") }
        mainClock.advanceTimeBy(1800)
        onNodeWithTag("finish").assertIsEnabled()
        screenshot("light-submit-failed")
        runOnIdle { node = account(revision = 1, username = "Admin") }
        mainClock.advanceTimeBy(1800)
        onNodeWithTag("finish").assertDoesNotExist()
        assertEquals("", onNodeWithTag("password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Composable
    private fun Preview(theme: String, node: DesktopUiNode.AccountSetup, emit: (DesktopUiNode.Event) -> Unit = {}) {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            PixivDownloaderTheme(theme) {
                Box(Modifier.size(800.dp, 600.dp).background(LocalExperiencePalette.current.surface).testTag("preview")) {
                    ComposeDesktopUiNodeRenderer.Render(node, ::resolve, emit, Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val output = File("build/reports/ui/account-$name.png")
        output.parentFile.mkdirs()
        org.jetbrains.skia.Image.makeFromBitmap(onNodeWithTag("preview").captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { output.writeBytes(it.bytes) }
        }
    }

    private val messages = Properties().apply {
        OnboardingAccountFormTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!
            .reader(Charsets.UTF_8).use(::load)
    }

    private fun resolve(token: DesktopUiNode.TextToken): String =
        MessageFormat.format(messages.getProperty(token.key(), token.fallback()), *token.arguments().toTypedArray())

    private fun account(
        busy: Boolean = false,
        revision: Long = 0,
        username: String = "",
        notice: String = "",
    ) = DesktopUiNode.AccountSetup(
        "account",
        DesktopUiNode.TextInput("username", "username", DesktopUiNode.TextToken.raw("Administrator username"), null,
            DesktopUiNode.InputKind.TEXT, username, 18, 1, !busy),
        DesktopUiNode.TextInput("password", "password", DesktopUiNode.TextToken.raw("Administrator password"), null,
            DesktopUiNode.InputKind.PASSWORD, "", 18, 1, !busy, revision),
        DesktopUiNode.Button("finish", "finish", DesktopUiNode.TextToken.raw("Finish"), null,
            DesktopUiNode.ButtonStyle.PRIMARY, !busy),
        8,
        12,
        busy,
        false,
        notice.takeIf(String::isNotEmpty)?.let {
            DesktopUiNode.Text("notice", DesktopUiNode.TextToken.raw(it), DesktopUiNode.TextStyle.ERROR, true, false)
        },
    )
}
