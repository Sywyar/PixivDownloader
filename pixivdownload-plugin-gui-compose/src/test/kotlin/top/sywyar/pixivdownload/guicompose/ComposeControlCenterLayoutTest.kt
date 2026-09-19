package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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
    @DisplayName("放大文字下可展开详情、读到数字错误并滚动到末字段，保存仍可见")
    fun keepsLongSettingsAccessibleWithLargerText() = runComposeUiTest {
        val rows = (1..14).map { index -> DesktopUiNode.FormRow(
            "field.$index", DesktopUiNode.TextToken.raw("Setting $index"),
            DesktopUiNode.TextToken.raw("Long configuration explanation with its conditions and original units."),
            DesktopUiNode.TextInput("value.$index", "value.$index", DesktopUiNode.TextToken.raw("Value $index"),
                null, DesktopUiNode.InputKind.TEXT, "D:/Downloads/long-folder-name/$index", 32, 1, true), null) }
        val fields = DesktopUiNode.Container("fields", DesktopUiNode.ContainerLayout.COLUMN, 1, 12,
            DesktopUiNode.Alignment.STRETCH, listOf(
                DesktopUiNode.NumberInput("amount", "amount", DesktopUiNode.TextToken.raw("Amount"), null,
                    DesktopUiNode.NumberStyle.SPINNER, 2, 0, 10, 1, true),
                DesktopUiNode.Form("form", DesktopUiNode.FormStyle.RESPONSIVE, null, rows), text("last", "Last field")))
        val group = DesktopUiNode.Group("details", DesktopUiNode.TextToken.raw("Details"), fields, true)
        val content = DesktopUiNode.Dock("settings", 8, null,
            DesktopUiNode.Scroll("scroll", group),
            DesktopUiNode.Button("save", "save", DesktopUiNode.TextToken.raw("Save"), null,
                DesktopUiNode.ButtonStyle.PRIMARY, false), null, null)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.size(480.dp, 400.dp)) {
                        ComposeDesktopUiNodeRenderer.Render(content,
                            { if (it.key() == "gui.compose.number-invalid") "Number outside allowed range" else it.fallback() }, {})
                    }
                }
            }
        }
        onNodeWithText("Details").performClick()
        onNodeWithContentDescription("Amount").performTextReplacement("99")
        assertEquals("Number outside allowed range",
            onNodeWithContentDescription("Amount").fetchSemanticsNode().config[SemanticsProperties.Error])
        onNodeWithText("Last field").performScrollTo().assertIsDisplayed()
        onNodeWithText("Save").assertIsDisplayed()
    }

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
    @DisplayName("浅深色与增强对比度使用共享语义色，并保持正文可读")
    fun usesSharedSemanticColorSchemes() {
        for (dark in listOf(false, true)) for (contrast in listOf(false, true)) {
            val colors = desktopColorScheme(dark, contrast)
            val palette = experiencePalette(dark, contrast)
            assertEquals(palette.surface, colors.surface)
            assertEquals(palette.success, colors.tertiary)
            assertEquals(palette.warning, colors.secondary)
            for (foreground in listOf(colors.onSurface, colors.onSurfaceVariant, colors.primary,
                colors.tertiary, colors.secondary, colors.error)) {
                for (background in listOf(colors.surface, colors.background, colors.surfaceVariant)) {
                    val levels = listOf(foreground.luminance(), background.luminance()).sorted()
                    assertTrue((levels.last() + .05f) / (levels.first() + .05f) >= 4.5f)
                }
            }
        }
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
    @DisplayName("自适应网格在内容缩短后重新测量当前行高")
    fun shrinksGridRowsWhenContentShrinks() = runComposeUiTest {
        var expanded by mutableStateOf(true)
        setContent {
            MaterialTheme {
                val value = if (expanded) "Tall\nline 2\nline 3\nline 4\nline 5" else "Short"
                val card = DesktopUiNode.Surface(
                    "dynamic", DesktopUiNode.SurfaceStyle.PLAIN, DesktopUiNode.Insets.all(8),
                    true, "dynamic.open", text("dynamic.text", value),
                )
                Box(Modifier.width(620.dp)) {
                    ComposeDesktopUiNodeRenderer.Render(
                        DesktopUiNode.AdaptiveGrid(
                            "grid", 180, 2, 12, 12,
                            listOf(card, text("peer", "Peer")),
                        ),
                        { it.fallback() },
                        {},
                    )
                }
            }
        }

        val expandedHeight = onNode(hasClickAction().and(hasText("Tall", substring = true)))
            .fetchSemanticsNode().boundsInRoot.height
        runOnIdle { expanded = false }
        waitForIdle()

        val compactHeight = onNode(hasClickAction().and(hasText("Short")))
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue(compactHeight < expandedHeight)
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
    @DisplayName("首页直接显示快捷入口，并派发声明式按钮事件")
    fun resolvesAndActivatesVisibleQuickStart() = runComposeUiTest {
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
        setContent {
            MaterialTheme {
                ComposeDesktopUiNodeRenderer.Render(
                    action,
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
    @DisplayName("错误提示使用更醒目的大号文本")
    fun emphasizesErrorText() = runComposeUiTest {
        val content = DesktopUiNode.Container(
            "notices", DesktopUiNode.ContainerLayout.COLUMN, 1, 8, DesktopUiNode.Alignment.START,
            listOf(
                text("body", "Regular notice"),
                DesktopUiNode.Text(
                    "error", DesktopUiNode.TextToken.raw("Important error"),
                    DesktopUiNode.TextStyle.ERROR, true, false,
                ),
            ),
        )
        setContent {
            MaterialTheme {
                ComposeDesktopUiNodeRenderer.Render(content, { it.fallback() }, {})
            }
        }

        assertTrue(
            onNodeWithText("Important error").fetchSemanticsNode().boundsInRoot.height >
                onNodeWithText("Regular notice").fetchSemanticsNode().boundsInRoot.height,
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
    @DisplayName("表单帮助持续可见，独立紧凑开关保留提示")
    fun showsFormHelpWithoutHover() = runComposeUiTest {
        var compactOnly by mutableStateOf(false)
        val form = DesktopUiNode.Form(
            "settings",
            DesktopUiNode.FormStyle.RESPONSIVE,
            null,
            listOf(
                DesktopUiNode.FormRow(
                    "setting.row",
                    DesktopUiNode.TextToken.raw("Setting"),
                    DesktopUiNode.TextToken.raw("Setting hint"),
                    text("setting.value", "Value"),
                    null,
                ),
            ),
        )
        val compact = DesktopUiNode.Toggle(
            "compact.toggle",
            "compact.toggle",
            DesktopUiNode.TextToken.raw("Compact setting"),
            DesktopUiNode.TextToken.raw("Compact hint"),
            DesktopUiNode.ToggleStyle.CHECKBOX,
            false,
            true,
        )
        setContent {
            MaterialTheme {
                Box(Modifier.width(620.dp)) {
                    ComposeDesktopUiNodeRenderer.Render(
                        if (compactOnly) compact else form,
                        { it.fallback() },
                        {},
                    )
                }
            }
        }

        onNodeWithText("Setting hint").assertExists()
        onNodeWithText("Compact hint").assertDoesNotExist()

        runOnIdle { compactOnly = true }
        waitForIdle()
        onNodeWithText("Setting hint").assertDoesNotExist()
        onNodeWithText("Compact hint").assertDoesNotExist()

        onNodeWithText("Compact setting").performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText("Compact hint").assertExists()
    }

    @Test
    @DisplayName("设置字段保持相邻对齐且短值宽度受限")
    fun boundsShortFieldsAndKeepsLabelsAdjacent() = runComposeUiTest {
        val form = DesktopUiNode.Form(
            "settings",
            DesktopUiNode.FormStyle.RESPONSIVE,
            null,
            listOf(
                DesktopUiNode.FormRow(
                    "toggle.row", DesktopUiNode.TextToken.raw("Toggle title"), null,
                    DesktopUiNode.Toggle(
                        "toggle", "toggle", DesktopUiNode.TextToken.raw("Toggle"), null,
                        DesktopUiNode.ToggleStyle.CHECKBOX, false, true,
                    ), null,
                ),
                DesktopUiNode.FormRow(
                    "choice.row", DesktopUiNode.TextToken.raw("Choice title"), null,
                    DesktopUiNode.Choice(
                        "choice", "choice", DesktopUiNode.TextToken.raw("Choice"), null,
                        DesktopUiNode.ChoiceStyle.COMBO_BOX, DesktopUiNode.SelectionMode.SINGLE,
                        listOf(DesktopUiNode.Option("auto", DesktopUiNode.TextToken.raw("Auto"), true)),
                        listOf("auto"), true,
                    ), null,
                ),
                DesktopUiNode.FormRow(
                    "number.row", DesktopUiNode.TextToken.raw("Number title"), null,
                    DesktopUiNode.TextInput(
                        "number", "number", DesktopUiNode.TextToken.raw("Number"), null,
                        DesktopUiNode.InputKind.NUMBER, "8080", 8, 1, true,
                    ), null,
                ),
                DesktopUiNode.FormRow(
                    "spinner.row", DesktopUiNode.TextToken.raw("Spinner title"), null,
                    DesktopUiNode.NumberInput(
                        "spinner", "spinner", DesktopUiNode.TextToken.raw("Spinner"), null,
                        DesktopUiNode.NumberStyle.SPINNER, 4, 0, 10, 1, true,
                    ), null,
                ),
                DesktopUiNode.FormRow(
                    "time.row", DesktopUiNode.TextToken.raw("Time title"), null,
                    DesktopUiNode.TextInput(
                        "time", "time", DesktopUiNode.TextToken.raw("Time"), null,
                        DesktopUiNode.InputKind.TIME, "10:00", 5, 1, true,
                    ), null,
                ),
                DesktopUiNode.FormRow(
                    "text.row", DesktopUiNode.TextToken.raw("Text title"), null,
                    DesktopUiNode.TextInput(
                        "text", "text", DesktopUiNode.TextToken.raw("Text"), null,
                        DesktopUiNode.InputKind.TEXT, "Downloads", 32, 1, true,
                    ), null,
                ),
            ),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.width(620.dp)) {
                    ComposeDesktopUiNodeRenderer.Render(form, { it.fallback() }, {})
                }
            }
        }

        for ((label, value) in listOf("Choice title" to "Auto", "Number title" to "8080",
            "Spinner title" to "4", "Time title" to "10:00", "Text title" to "Downloads")) {
            val title = onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            val field = onNodeWithText(value).fetchSemanticsNode().boundsInRoot
            assertTrue(field.left > title.right)
            assertTrue(kotlin.math.abs(field.top - title.top) < 24f)
        }
        val number = onNodeWithContentDescription("Number").fetchSemanticsNode().boundsInRoot
        assertTrue(number.width <= 160f)

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

    private fun androidx.compose.ui.test.ComposeUiTest.centerY(label: String): Float =
        onNodeWithText(label).fetchSemanticsNode().boundsInRoot.center.y

    private fun androidx.compose.ui.test.ComposeUiTest.toggleCenterY(): Float = onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
    ).fetchSemanticsNode().boundsInRoot.center.y

    private fun androidx.compose.ui.test.ComposeUiTest.page(description: String) = onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description),
    )

    private fun text(id: String, value: String) = DesktopUiNode.Text(
        id, DesktopUiNode.TextToken.raw(value), DesktopUiNode.TextStyle.BODY, false, false,
    )
}
