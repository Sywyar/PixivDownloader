package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.*
import java.io.File
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AutomaticBackfillNoticeTest {
    @Test
    @DisplayName("倒计时经过真实展示时间后只关闭一次，进度修订不重新计时")
    fun dismissesWithoutResettingOnRevision() = runComposeUiTest {
        mainClock.autoAdvance = false
        var task by mutableStateOf(task())
        var dismissals = 0
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides focusedWindow) {
                PixivDownloaderTheme("light") {
                    AutomaticBackfillNotice(task, ::text, {}, { dismissals++ })
                }
            }
        }
        mainClock.advanceTimeBy(4_000)
        onNodeWithTag("backfill.notice").assertIsDisplayed()
        runOnIdle { task = task.copyRevision(1) }
        mainClock.advanceTimeBy(4_000)
        runOnIdle { assertEquals(0, dismissals) }
        mainClock.advanceTimeBy(3_000)
        runOnIdle { assertEquals(1, dismissals) }
        mainClock.advanceTimeBy(20_000)
        runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    @DisplayName("悬停、键盘焦点、窗口失焦和遮挡暂停倒计时，恢复后继续剩余时间")
    fun pausesWhileReadingOrHidden() = runComposeUiTest {
        mainClock.autoAdvance = false
        var focused by mutableStateOf(true)
        var visible by mutableStateOf(true)
        val task = task()
        val window = object : WindowInfo { override val isWindowFocused get() = focused }
        var dismissals = 0
        lateinit var focusManager: FocusManager
        setContent {
            focusManager = LocalFocusManager.current
            CompositionLocalProvider(LocalWindowInfo provides window) {
                PixivDownloaderTheme("dark") {
                    Box(Modifier.fillMaxSize()) {
                        AutomaticBackfillNotice(task, ::text, {}, { dismissals++ }, visible = visible)
                    }
                }
            }
        }
        mainClock.advanceTimeBy(4_000)
        onNodeWithTag("backfill.notice").performMouseInput { enter(center) }
        mainClock.advanceTimeBy(20_000)
        runOnIdle { assertEquals(0, dismissals) }
        onNodeWithTag("backfill.notice").performMouseInput { exit() }
        onNodeWithTag("backfill.notice.view").performSemanticsAction(SemanticsActions.RequestFocus)
        mainClock.advanceTimeBy(20_000)
        runOnIdle { assertEquals(0, dismissals); focusManager.clearFocus(force = true); focused = false }
        mainClock.advanceTimeBy(20_000)
        runOnIdle { assertEquals(0, dismissals); focused = true; visible = false }
        mainClock.advanceTimeBy(20_000)
        onNodeWithTag("backfill.notice").assertDoesNotExist()
        runOnIdle { assertEquals(0, dismissals); visible = true }
        mainClock.advanceTimeBy(4_000)
        runOnIdle { assertEquals(0, dismissals) }
        mainClock.advanceTimeBy(3_000)
        runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    @DisplayName("关闭动态效果不加速倒计时，失败提示保持显示")
    fun reducedMotionAndFailure() = runComposeUiTest(
        effectContext = object : MotionDurationScale { override val scaleFactor = 0f },
    ) {
        mainClock.autoAdvance = false
        var state by mutableStateOf(BackfillState.RUNNING)
        val id = UUID.randomUUID()
        var dismissals = 0
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides focusedWindow) {
                PixivDownloaderTheme("dark") {
                    AutomaticBackfillNotice(task(id = id, state = state), ::text, {}, { dismissals++ })
                }
            }
        }
        mainClock.advanceTimeBy(4_000)
        runOnIdle { assertEquals(0, dismissals); state = BackfillState.FAILED }
        mainClock.advanceTimeBy(30_000)
        onNodeWithTag("backfill.notice").assertIsDisplayed()
        onNodeWithTag("backfill.notice.countdown").assertDoesNotExist()
        runOnIdle { assertEquals(0, dismissals) }
    }

    @Test
    @DisplayName("执行换代和移除释放旧倒计时，旧提示不会关闭新执行")
    fun replacementAndDisposal() = runComposeUiTest {
        mainClock.autoAdvance = false
        var task by mutableStateOf<BackfillTask?>(task())
        val dismissed = mutableListOf<UUID>()
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides focusedWindow) {
                PixivDownloaderTheme("light") {
                    AutomaticBackfillNotice(task, ::text, {}, { dismissed += it })
                }
            }
        }
        mainClock.advanceTimeBy(7_000)
        runOnIdle { task = task() }
        mainClock.advanceTimeBy(5_000)
        runOnIdle { assertEquals(emptyList(), dismissed); task = null }
        mainClock.advanceTimeBy(30_000)
        runOnIdle { assertEquals(emptyList(), dismissed) }
    }

    @Test
    @DisplayName("遵守辅助功能延长的阅读时长")
    fun accessibilityTimeout() = runComposeUiTest {
        mainClock.autoAdvance = false
        var dismissals = 0
        val accessibility = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(
                originalTimeoutMillis: Long,
                containsIcons: Boolean,
                containsText: Boolean,
                containsControls: Boolean,
            ) = originalTimeoutMillis * 3
        }
        setContent {
            CompositionLocalProvider(
                LocalWindowInfo provides focusedWindow,
                LocalAccessibilityManager provides accessibility,
            ) {
                PixivDownloaderTheme("light") {
                    AutomaticBackfillNotice(task(), ::text, {}, { dismissals++ })
                }
            }
        }
        mainClock.advanceTimeBy(20_000)
        runOnIdle { assertEquals(0, dismissals) }
        mainClock.advanceTimeBy(11_000)
        runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    @DisplayName("入场中可立即操作，退场保留画面但不重复响应，结束后释放")
    fun actionsDuringMotion() = runComposeUiTest {
        mainClock.autoAdvance = false
        val original = task()
        var current by mutableStateOf<BackfillTask?>(original)
        val viewed = mutableListOf<UUID>()
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides focusedWindow) {
                PixivDownloaderTheme("dark") {
                    AutomaticBackfillNotice(current, ::text, { viewed += it; current = null }, {})
                }
            }
        }
        mainClock.advanceTimeBy(80)
        onNodeWithTag("backfill.notice.view").assertIsEnabled().performClick()
        mainClock.advanceTimeByFrame()
        runOnIdle { assertEquals(listOf(original.executionId()), viewed) }
        onNodeWithTag("backfill.notice").assertExists()
        onNodeWithTag("backfill.notice.view").assertDoesNotExist()
        onNodeWithTag("backfill.notice").performMouseInput { click(center) }
        mainClock.advanceTimeBy(300)
        onNodeWithTag("backfill.notice").assertDoesNotExist()
        runOnIdle { assertEquals(listOf(original.executionId()), viewed) }
    }

    @Test
    @DisplayName("遮挡后反向过渡从当前位置继续，进度修订不重播入场")
    fun reversesWithoutRestarting() = runComposeUiTest {
        mainClock.autoAdvance = false
        var current by mutableStateOf(task())
        var visible by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides focusedWindow) {
                PixivDownloaderTheme("dark") {
                    AutomaticBackfillNotice(current, ::text, {}, {}, visible = visible)
                }
            }
        }
        val hidden = onNodeWithTag("backfill.notice").fetchSemanticsNode().boundsInRoot
        mainClock.advanceTimeBy(1_000)
        val settled = onNodeWithTag("backfill.notice").fetchSemanticsNode().boundsInRoot
        runOnIdle { visible = false }
        mainClock.advanceTimeBy(64)
        val outgoing = onNodeWithTag("backfill.notice").fetchSemanticsNode().boundsInRoot
        assertTrue(outgoing.top > settled.top)
        runOnIdle { visible = true; current = current.copyRevision(1) }
        mainClock.advanceTimeByFrame()
        val reversing = onNodeWithTag("backfill.notice").fetchSemanticsNode().boundsInRoot
        assertTrue(reversing.top > settled.top && reversing.top < hidden.top,
            "Reversal must stay between the endpoints: hidden=$hidden, settled=$settled, outgoing=$outgoing, reversing=$reversing")
        onNodeWithTag("backfill.notice.view").assertIsEnabled()
        mainClock.advanceTimeBy(1_000)
        assertEquals(settled, onNodeWithTag("backfill.notice").fetchSemanticsNode().boundsInRoot)
        runOnIdle { current = current.copyRevision(2) }
        mainClock.advanceTimeBy(80)
        assertEquals(settled, onNodeWithTag("backfill.notice").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    @DisplayName("入场不消耗阅读预算，自动收起仍经过退场过渡")
    fun readingStartsAfterEntrance() = runComposeUiTest {
        mainClock.autoAdvance = false
        var current by mutableStateOf<BackfillTask?>(task())
        var dismissals = 0
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides focusedWindow) {
                PixivDownloaderTheme("light") {
                    AutomaticBackfillNotice(current, ::text, {}, { dismissals++; current = null })
                }
            }
        }
        mainClock.advanceTimeBy(10_000)
        runOnIdle { assertEquals(0, dismissals) }
        mainClock.advanceTimeUntil(2_000) { dismissals == 1 }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("backfill.notice").assertExists()
        mainClock.advanceTimeBy(300)
        onNodeWithTag("backfill.notice").assertDoesNotExist()
    }

    private fun task(
        id: UUID = UUID.randomUUID(),
        revision: Long = 0,
        state: BackfillState = BackfillState.RUNNING,
    ) = BackfillTask(id, revision, state, BackfillPhase.NETWORK, true, false,
        1, 0, 1, 3, 1, 1, 0, null, null, null, "fixture.html")

    private fun BackfillTask.copyRevision(revision: Long) = task(executionId(), revision, state())

    companion object {
        private val focusedWindow = object : WindowInfo { override val isWindowFocused = true }
        private val messages = Properties().apply {
            File("../pixivdownload-app/src/main/resources/i18n/messages_en.properties")
                .reader(Charsets.UTF_8).use(::load)
        }
        private fun text(token: TextToken) = MessageFormat(messages.getProperty(token.key(), token.key()), Locale.US)
            .format(token.arguments().toTypedArray())
    }
}
