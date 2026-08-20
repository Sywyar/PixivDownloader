package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@DisplayName("Compose 控制中心通用布局")
class ComposeControlCenterLayoutTest {
    @Test
    @DisplayName("自适应网格按可用宽度退化列数")
    fun adaptsGridColumnsToAvailableWidth() = runComposeUiTest {
        var width by mutableStateOf(620.dp)
        val grid = DesktopUiNode.AdaptiveGrid(
            "grid", 180, 4, 12, 12,
            listOf(text("one", "One"), text("two", "Two"), text("three", "Three")),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.width(width)) {
                    ComposeDesktopUiNodeRenderer.Render(grid, { it.fallback() }, {})
                }
            }
        }

        assertEquals(top("One"), top("Two"))
        assertEquals(top("One"), top("Three"))

        runOnIdle { width = 360.dp }
        waitForIdle()
        assertTrue(top("Two") > top("One"))
        assertTrue(top("Three") > top("Two"))
    }

    @Test
    @DisplayName("分页横排保留四格宽度并提供键盘、滚轮、拖拽与无障碍翻页")
    fun pagesFourItemsWithKeyboardAndAccessibility() = runComposeUiTest {
        val row = DesktopUiNode.PagedRow(
            "pages", 4, 12,
            listOf(
                text("one", "One"), text("two", "Two"), text("three", "Three"),
                text("four", "Four"), text("five", "Five"),
            ),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.width(800.dp)) {
                    ComposeDesktopUiNodeRenderer.Render(row, { it.fallback() }, {})
                }
            }
        }

        val firstPage = page("1/2")
        firstPage.assertExists()
        val firstItemWidth = onNodeWithText("One").fetchSemanticsNode().boundsInRoot.width
        firstPage.performSemanticsAction(SemanticsActions.RequestFocus)
        firstPage.performKeyInput { pressKey(Key.DirectionRight) }
        page("2/2").assertExists()

        val lastItemWidth = onNodeWithText("Five").fetchSemanticsNode().boundsInRoot.width
        assertEquals(firstItemWidth, lastItemWidth)
        page("2/2").performKeyInput { pressKey(Key.DirectionRight) }
        page("2/2").assertExists()

        page("2/2").performKeyInput { pressKey(Key.DirectionLeft) }
        page("1/2").performMouseInput { scroll(1f) }
        page("2/2").assertExists()

        page("2/2").performKeyInput { pressKey(Key.DirectionLeft) }
        page("1/2").performTouchInput { swipeLeft() }
        page("2/2").assertExists()
    }

    @Test
    @DisplayName("受控图标暴露解析后的无障碍名称")
    fun exposesResolvedIconDescription() = runComposeUiTest {
        val icon = DesktopUiNode.Icon(
            "home", DesktopUiIcon.HOME, DesktopUiTone.INFO, DesktopUiNode.TextToken.raw("Home"),
        )
        setContent {
            MaterialTheme {
                ComposeDesktopUiNodeRenderer.Render(icon, { it.fallback() }, {})
            }
        }

        onNodeWithContentDescription("Home").assertExists()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.top(label: String): Float =
        onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top

    private fun androidx.compose.ui.test.ComposeUiTest.page(description: String) = onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description),
    )

    private fun text(id: String, value: String) = DesktopUiNode.Text(
        id, DesktopUiNode.TextToken.raw(value), DesktopUiNode.TextStyle.BODY, false, false,
    )
}
