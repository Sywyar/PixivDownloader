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
