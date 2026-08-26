package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@DisplayName("Compose 控制中心通用布局")
class ComposeControlCenterLayoutTest {
    @Test
    @DisplayName("工具弹窗沿用父窗口尺寸，普通弹窗使用声明尺寸")
    fun sizesDocumentDialogs() {
        val parentSize = DpSize(1120.dp, 760.dp)
        val content = text("content", "Content")
        val toolDialog = DesktopUiDocument.Dialog(
            "tool", DesktopUiNode.TextToken.raw("Tool"), DesktopUiDocument.DialogStyle.INFO,
            content, "tool.close", true, 0, 0, true,
        )
        val compactDialog = DesktopUiDocument.Dialog(
            "compact", DesktopUiNode.TextToken.raw("Compact"), DesktopUiDocument.DialogStyle.INFO,
            content, "compact.close", true, 440, 0, false,
        )

        assertEquals(parentSize, dialogWindowSize(toolDialog, parentSize))
        assertEquals(DpSize(440.dp, 300.dp), dialogWindowSize(compactDialog, parentSize))
    }

    @Test
    @DisplayName("浅色与深色主题使用 Material 3 Baseline 配色")
    fun usesMaterialBaselineColorSchemes() {
        val lightColors = desktopColorScheme(false)
        val darkColors = desktopColorScheme(true)
        val baselineLight = lightColorScheme()
        val baselineDark = darkColorScheme()

        assertEquals(baselineLight.primary, lightColors.primary)
        assertEquals(baselineLight.primaryContainer, lightColors.primaryContainer)
        assertEquals(baselineLight.surface, lightColors.surface)
        assertEquals(baselineLight.surfaceContainerHighest, lightColors.surfaceContainerHighest)
        assertEquals(baselineDark.primary, darkColors.primary)
        assertEquals(baselineDark.primaryContainer, darkColors.primaryContainer)
        assertEquals(baselineDark.surface, darkColors.surface)
        assertEquals(baselineDark.surfaceContainerHighest, darkColors.surfaceContainerHighest)
    }

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

    @Test
    @DisplayName("可展开快捷入口解析文本令牌并派发声明式按钮事件")
    fun resolvesAndActivatesExpandableFabItem() = runComposeUiTest {
        val title = DesktopUiNode.TextToken.key("desktop.ui.home.quick-start.title")
        val itemLabel = DesktopUiNode.TextToken(
            "sample", "navigation.search", "Search", emptyList(),
        )
        val button = DesktopUiNode.Button(
            "quick.search.button", "quick.search.open", itemLabel, null,
            DesktopUiNode.ButtonStyle.NORMAL, true,
        )
        val action = DesktopUiNode.Container(
            "quick", DesktopUiNode.ContainerLayout.COLUMN, 1, 8, DesktopUiNode.Alignment.START,
            listOf(
                DesktopUiNode.Text("quick.title", title, DesktopUiNode.TextStyle.HEADING, false, false),
                DesktopUiNode.Container(
                    "quick.grid", DesktopUiNode.ContainerLayout.GRID, 2, 8, DesktopUiNode.Alignment.STRETCH,
                    listOf(
                        DesktopUiNode.Container(
                            "quick.search", DesktopUiNode.ContainerLayout.ROW, 1, 8,
                            DesktopUiNode.Alignment.CENTER,
                            listOf(
                                DesktopUiNode.Icon(
                                    "quick.search.icon", DesktopUiIcon.OPEN, DesktopUiTone.INFO, itemLabel,
                                ),
                                button,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val events = mutableListOf<DesktopUiNode.Event>()
        val menu = checkNotNull(expandableFabMenu(action))
        setContent {
            MaterialTheme {
                ExpandableFab(
                    menu,
                    { token ->
                        when (token.key()) {
                            "desktop.ui.home.quick-start.title" -> "Quick start"
                            "navigation.search" -> "Search artworks"
                            else -> token.fallback()
                        }
                    },
                    events::add,
                )
            }
        }

        onNodeWithContentDescription("Quick start").performClick()
        onNodeWithText("Search artworks").performClick()
        waitForIdle()

        assertEquals(DesktopUiNode.EventType.ACTIVATE, events.single().type())
        assertEquals(button.id(), events.single().nodeId())
    }

    @Test
    @DisplayName("无界高度中的标签页仍显示内容")
    fun rendersTabContentWithoutBoundedHeight() = runComposeUiTest {
        val tabs = DesktopUiNode.Tabs(
            "tabs", listOf(DesktopUiNode.Tab("details", DesktopUiNode.TextToken.raw("Details"),
                text("content", "Visible content"))),
        )
        setContent {
            MaterialTheme {
                ComposeDesktopUiNodeRenderer.Render(tabs, { it.fallback() }, {})
            }
        }

        assertTrue(onNodeWithText("Visible content").fetchSemanticsNode().boundsInRoot.height > 0f)
    }

    @Test
    @DisplayName("文本链接不引入按钮容器高度")
    fun keepsTextLinkAtTextHeight() = runComposeUiTest {
        val content = DesktopUiNode.Container(
            "links", DesktopUiNode.ContainerLayout.COLUMN, 1, 0, DesktopUiNode.Alignment.START,
            listOf(
                text("plain", "Plain text"),
                DesktopUiNode.Link("link", "open", DesktopUiNode.TextToken.raw("Text link"), null, true),
            ),
        )
        setContent {
            MaterialTheme {
                ComposeDesktopUiNodeRenderer.Render(content, { it.fallback() }, {})
            }
        }

        assertEquals(
            onNodeWithText("Plain text").fetchSemanticsNode().boundsInRoot.height,
            onNodeWithText("Text link").fetchSemanticsNode().boundsInRoot.height,
        )
    }

    @Test
    @DisplayName("数值微调字段支持直接键入完整数值")
    fun typesCompleteSpinnerValue() = runComposeUiTest {
        val events = mutableListOf<DesktopUiNode.Event>()
        val input = DesktopUiNode.NumberInput(
            "port", "port.value", DesktopUiNode.TextToken.raw("Port"), null,
            DesktopUiNode.NumberStyle.SPINNER, 6999, 1, 65535, 1, true,
        )
        setContent {
            MaterialTheme {
                ComposeDesktopUiNodeRenderer.Render(input, { it.fallback() }, events::add)
            }
        }

        onNode(hasSetTextAction()).performTextReplacement("8080")
        waitForIdle()

        assertEquals("8080", events.single().value().values().single())
        onNodeWithText("+").assertDoesNotExist()
        onNodeWithText("−").assertDoesNotExist()
    }

    @Test
    @DisplayName("环形进度按等宽尺寸渲染")
    fun rendersCircularProgress() = runComposeUiTest {
        val progress = DesktopUiNode.Progress(
            "storage", .25, false, null, DesktopUiNode.ProgressStyle.CIRCULAR,
        )
        setContent {
            MaterialTheme {
                Box(Modifier.width(200.dp)) {
                    ComposeDesktopUiNodeRenderer.Render(progress, { it.fallback() }, {})
                }
            }
        }

        val bounds = onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width, bounds.height)
    }

    @Test
    @DisplayName("纵向时间线按步骤排列并把状态保持在尾侧")
    fun laysOutVerticalTimeline() = runComposeUiTest {
        val timeline = DesktopUiNode.Timeline(
            "interlock",
            listOf(
                DesktopUiNode.TimelineItem(
                    DesktopUiNode.TextToken.raw("Resource check"),
                    DesktopUiNode.TextToken.raw("Backend and tool lock"),
                    DesktopUiNode.TextToken.raw("Passed"),
                    DesktopUiNode.TimelineState.COMPLETE,
                ),
                DesktopUiNode.TimelineItem(
                    DesktopUiNode.TextToken.raw("Stop backend"),
                    DesktopUiNode.TextToken.raw("Release SQLite"),
                    DesktopUiNode.TextToken.raw("As needed"),
                    DesktopUiNode.TimelineState.IDLE,
                ),
                DesktopUiNode.TimelineItem(
                    DesktopUiNode.TextToken.raw("Execute"),
                    DesktopUiNode.TextToken.raw("Run and record"),
                    DesktopUiNode.TextToken.raw("Running"),
                    DesktopUiNode.TimelineState.ACTIVE,
                ),
            ),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.width(420.dp)) {
                    ComposeDesktopUiNodeRenderer.Render(timeline, { it.fallback() }, {})
                }
            }
        }

        assertTrue(top("Stop backend") > top("Resource check"))
        assertTrue(top("Execute") > top("Stop backend"))
        assertTrue(left("Passed") > left("Resource check"))
        assertTrue(left("Running") > left("Execute"))
    }

    @Test
    @DisplayName("自动化时间线按未来时刻横向定位并错开相邻任务")
    fun laysOutAutomationScheduleTimeline() = runComposeUiTest {
        val hour = 60L * 60L * 1_000L
        val timeline = DesktopUiNode.ScheduleTimeline(
            "schedule", 0L, hour, 24L * hour,
            listOf(
                DesktopUiNode.ScheduleTimelineItem(
                    2L * hour, DesktopUiNode.TextToken.raw("02:00"),
                    DesktopUiNode.TextToken.raw("Early job"), DesktopUiNode.TextToken.raw("Every hour"),
                ),
                DesktopUiNode.ScheduleTimelineItem(
                    2L * hour + 60_000L, DesktopUiNode.TextToken.raw("02:01"),
                    DesktopUiNode.TextToken.raw("Nearby job"), DesktopUiNode.TextToken.raw("Every hour"),
                ),
                DesktopUiNode.ScheduleTimelineItem(
                    22L * hour + 48L * 60_000L, DesktopUiNode.TextToken.raw("22:48"),
                    DesktopUiNode.TextToken.raw("Later job"), DesktopUiNode.TextToken.raw("Every day"),
                ),
            ),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.width(600.dp)) {
                    ComposeDesktopUiNodeRenderer.Render(
                        timeline,
                        { it.fallback() },
                        {},
                        Modifier.testTag("schedule-timeline"),
                    )
                }
            }
        }

        val timelineBounds = onNodeWithTag("schedule-timeline").fetchSemanticsNode().boundsInRoot
        val earlyBounds = onNodeWithText("Early job").fetchSemanticsNode().boundsInRoot
        val laterBounds = onNodeWithText("Later job").fetchSemanticsNode().boundsInRoot
        val axisWidth = timelineBounds.width - earlyBounds.width
        assertEquals(timelineBounds.left + axisWidth * (2f / 24f), earlyBounds.left, 1f)
        assertEquals(timelineBounds.left + axisWidth * (22.8f / 24f), laterBounds.left, 1f)
        assertTrue(left("Later job") > left("Early job"))
        assertTrue(topUnmerged("Nearby job") > topUnmerged("Early job"))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.top(label: String): Float =
        onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top

    private fun androidx.compose.ui.test.ComposeUiTest.left(label: String): Float =
        onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left

    private fun androidx.compose.ui.test.ComposeUiTest.topUnmerged(label: String): Float =
        onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun androidx.compose.ui.test.ComposeUiTest.page(description: String) = onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description),
    )

    private fun text(id: String, value: String) = DesktopUiNode.Text(
        id, DesktopUiNode.TextToken.raw(value), DesktopUiNode.TextStyle.BODY, false, false,
    )
}
