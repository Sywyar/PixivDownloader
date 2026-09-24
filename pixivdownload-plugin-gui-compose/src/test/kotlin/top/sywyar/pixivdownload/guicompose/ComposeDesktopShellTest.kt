package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@DisplayName("Compose 根页面外壳")
class ComposeDesktopShellTest {
    @Test
    @DisplayName("引导进行中隐藏导航并让向导占满窗口")
    fun hidesNavigationWhileOnboardingIsIncomplete() = runComposeUiTest {
        setContent {
            PixivDownloaderTheme("light") {
                Box(Modifier.size(600.dp, 400.dp)) {
                    DesktopShell(document(false), 1L, { it.fallback() }, {})
                }
            }
        }

        onNodeWithText("Settings").assertDoesNotExist()
        assertEquals(0f, onNodeWithText("Wizard").fetchSemanticsNode().boundsInRoot.left, 1f)
    }

    @Test
    @DisplayName("引导结束后恢复导航并让出侧栏宽度")
    fun showsNavigationAfterOnboardingCompletes() = runComposeUiTest {
        setContent {
            PixivDownloaderTheme("light") {
                Box(Modifier.size(600.dp, 400.dp)) {
                    DesktopShell(document(true), 1L, { it.fallback() }, {})
                }
            }
        }

        onNodeWithText("Settings").assertExists()
        assertEquals(
            DesktopLayout.sidebarWidth.value,
            onNodeWithText("Wizard").fetchSemanticsNode().boundsInRoot.left,
            1f,
        )
    }

    @Test
    @DisplayName("向导步骤切换后旧页面退出且导航保持隐藏")
    fun transitionsBetweenOnboardingSteps() = runComposeUiTest {
        mainClock.autoAdvance = false
        var current by mutableStateOf(document(false))
        setContent {
            PixivDownloaderTheme("light") {
                Box(Modifier.size(600.dp, 400.dp)) {
                    DesktopShell(current, 1L, { it.fallback() }, {})
                }
            }
        }
        onNodeWithText("Wizard").assertExists()
        runOnIdle { current = document(false, text("account", "Account setup")) }
        mainClock.advanceTimeBy(240)
        onNodeWithText("Wizard").assertDoesNotExist()
        onNodeWithText("Account setup").assertExists()
        onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test
    @DisplayName("完成引导时淡出卡片页并展开导航，减少动态效果时直接到位")
    fun animatesCompletionAndHonorsReducedMotion() {
        for (scale in listOf(1f, 0f)) runComposeUiTest(
            effectContext = object : androidx.compose.ui.MotionDurationScale { override val scaleFactor = scale },
        ) {
            mainClock.autoAdvance = false
            var current by mutableStateOf(document(false))
            setContent {
                PixivDownloaderTheme("light") {
                    Box(Modifier.size(600.dp, 400.dp)) {
                        DesktopShell(current, 1L, { it.fallback() }, {})
                    }
                }
            }
            mainClock.advanceTimeBy(32)
            runOnIdle { current = document(true, text("home.ready", "Ready")) }
            mainClock.advanceTimeBy(80)
            val inset = onNodeWithText("Ready").fetchSemanticsNode().boundsInRoot.left
            if (scale > 0) assertTrue(inset > 0 && inset < DesktopLayout.sidebarWidth.value)
            else assertEquals(DesktopLayout.sidebarWidth.value, inset, 1f)
            mainClock.advanceTimeBy(400)
            onNodeWithText("Wizard").assertDoesNotExist()
            onNodeWithText("Settings").assertExists()
            assertEquals(DesktopLayout.sidebarWidth.value, onNodeWithText("Ready").fetchSemanticsNode().boundsInRoot.left, 1f)
        }
    }

    private fun document(
        navigationVisible: Boolean,
        content: DesktopUiNode = text("wizard", "Wizard"),
    ) = DesktopUiDocument(
        listOf(
            DesktopUiDocument.Page("home", raw("Home"), content),
            DesktopUiDocument.Page("settings", raw("Settings"), text("settings.body", "Settings body")),
        ),
        listOf(),
        listOf(),
        Optional.empty(),
        navigationVisible,
    )

    private fun text(id: String, value: String) = DesktopUiNode.Text(
        id, DesktopUiNode.TextToken.raw(value), DesktopUiNode.TextStyle.BODY, false, false,
    )

    private fun raw(value: String) = DesktopUiNode.TextToken.raw(value)
}
