package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ToolsOverviewTest {
    @Test
    @DisplayName("任意导入贡献按行自动排列，缺席和撤回不留空列，窄窗口重新排列")
    fun contributionsReflow() = runComposeUiTest {
        var imports by mutableStateOf(listOf(activeTool("local-import"), activeTool("import.beta"), activeTool("import.gamma")))
        var width by mutableStateOf(1200.dp)
        setContent {
            PixivDownloaderTheme("dark") {
                Box(Modifier.size(width, 1000.dp)) {
                    ToolsOverview(sample(fileImports = imports), ::resolve, {})
                }
            }
        }
        fun bounds(id: String) = onNodeWithTag("tools.entry.$id").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds("backfill").top, bounds("local-import").top, 1f)
        assertEquals(bounds("backfill").left, bounds("import.beta").left, 1f)
        assertTrue(bounds("import.beta").top >= bounds("backfill").bottom)
        onNodeWithTag("tools.entry.local-import").performScrollTo().performClick()
        onNodeWithTag("tools.import.workspace").assertIsDisplayed()
        runOnIdle { imports = imports.drop(1) }
        onNodeWithTag("tools.entry.local-import").assertDoesNotExist()
        onNodeWithTag("tools.import.workspace").assertDoesNotExist()
        assertEquals(bounds("backfill").top, bounds("import.beta").top, 1f)
        assertEquals(bounds("backfill").left, bounds("import.gamma").left, 1f)
        runOnIdle { width = 560.dp }
        assertEquals(bounds("backfill").left, bounds("import.beta").left, 1f)
        assertTrue(bounds("import.beta").top >= bounds("backfill").bottom)
        runOnIdle { imports = emptyList() }
        onNodeWithTag("tools.entry.import.beta").assertDoesNotExist()
        onNodeWithTag("tools.entry.classifier").performScrollTo().assertIsDisplayed()
    }

    @Test
    @DisplayName("工具分组布局与弹窗校验，收起执行后可重新查看结果")
    fun catalogueAndTaskFlow() = runComposeUiTest {
        val values = mutableStateMapOf("db" to "artworks.db", "proxy-host" to "127.0.0.", "proxy-port" to "7890", "delay" to "800", "limit" to "0")
        var activity by mutableStateOf<ToolActivity?>(null)
        val events = mutableListOf<Event>()
        setContent {
            PixivDownloaderTheme("light") {
                Box(Modifier.size(1200.dp, 860.dp).background(LocalExperiencePalette.current.surface)) {
                    ToolsOverview(sample(values, activity), ::resolve, { event ->
                        if (event.type() == EventType.CHANGE) values[event.nodeId().substringAfterLast('.')] = event.value().values().first()
                        else events += event
                    })
                }
            }
        }
        val left = onNodeWithTag("tools.entry.classifier").fetchSemanticsNode().boundsInRoot
        val right = onNodeWithTag("tools.entry.backfill").fetchSemanticsNode().boundsInRoot
        assertTrue(left.top > right.bottom)
        assertEquals(left.left, right.left, 1f)
        screenshot("overview")
        onNodeWithTag("tools.entry.backfill").performClick()
        onNodeWithTag("tools.sheet").assertIsDisplayed()
        onNodeWithTag("tools.backfill.run").assertIsDisplayed().performClick()
        assertTrue(events.isEmpty())
        onNodeWithTag("tools.backfill.proxy-host.field").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
        runOnIdle { values["proxy-host"] = "127.0.0.1" }
        onNodeWithTag("tools.advanced").performScrollTo().performClick()
        onNodeWithTag("tools.backfill.delay.field").assertIsDisplayed()
        screenshot("backfill")
        onNodeWithTag("tools.backfill.run").assertIsDisplayed().performClick()
        assertEquals("tools.backfill.run", events.single().nodeId())
        runOnIdle { activity = ToolActivity("backfill", true, false, TextToken.raw("Processing")) }
        onNodeWithTag("tools.minimize").performClick()
        onNodeWithTag("tools.sheet").assertDoesNotExist()
        onNodeWithTag("tools.current").performClick()
        onNodeWithTag("tools.minimize").assertIsDisplayed()
        runOnIdle { activity = ToolActivity("backfill", false, false, TextToken.raw("Processed 12 records")) }
        onNodeWithTag("tools.result").assertTextEquals("Processed 12 records")
        onNodeWithTag("tools.done").performClick()
        onNodeWithTag("tools.current").assertDoesNotExist()
        onNodeWithTag("tools.entry.backfill").performClick()
        onNodeWithTag("tools.result").assertDoesNotExist()
        onNodeWithTag("tools.backfill.run").assertIsDisplayed().assertIsEnabled()
        onNodeWithTag("tools.backfill.proxy-host.field").assertExists()
    }

    @Test
    @DisplayName("顶部只保留运行任务，多个导入和媒体任务各自结束后撤回，维护历史仍可查阅")
    fun activeTasks() = runComposeUiTest {
        var activity by mutableStateOf<ToolActivity?>(ToolActivity("backfill", true, false, TextToken.raw("Backfilling")))
        var imports by mutableStateOf(listOf(activeTool("import.first"), activeTool("import.second")))
        var media by mutableStateOf(listOf(activeTool("media.preview"), activeTool("media.convert")))
        setContent {
            PixivDownloaderTheme("dark") {
                Box(Modifier.size(1200.dp, 1000.dp)) {
                    ToolsOverview(sample(activity = activity, mediaTools = media, fileImports = imports), ::resolve, {})
                }
            }
        }
        onNodeWithTag("tools.current").assertExists()
        onNodeWithTag("tools.current.import.first").assertExists()
        onNodeWithTag("tools.current.import.second").assertExists()
        onNodeWithTag("tools.current.media.convert").assertExists()
        onNodeWithTag("tools.current.media.convert").performClick()
        onNodeWithTag("tools.media.tab.media.convert").assertIsSelected()
        onNodeWithTag("tools.media.back").performClick()
        onNodeWithTag("tools.entry.migration").assertDoesNotExist()
        runOnIdle {
            activity = ToolActivity("backfill", false, false, TextToken.raw("Completed"))
            imports = imports.drop(1)
            media = emptyList()
        }
        onNodeWithTag("tools.current").assertDoesNotExist()
        onNodeWithTag("tools.current.import.first").assertDoesNotExist()
        onNodeWithTag("tools.current.media.convert").assertDoesNotExist()
        onNodeWithTag("tools.current.import.second").assertExists()
        runOnIdle { imports = emptyList() }
        onNodeWithTag("tools.current.import.second").assertDoesNotExist()
        onNodeWithTag("tools.history.history.1").performScrollTo().assertIsDisplayed()
    }

    @Test
    @DisplayName("窄窗口和减少动态效果时仍能打开媒体管理与维护历史")
    fun narrowAndReducedMotion() = runComposeUiTest(effectContext = object : MotionDurationScale {
        override val scaleFactor = 0f
    }) {
        setContent { PixivDownloaderTheme("dark") { Box(Modifier.size(560.dp, 760.dp)) {
            ToolsOverview(sample(), ::resolve, {})
        } } }
        onNodeWithTag("tools.entry.media").performScrollTo().performClick()
        onNodeWithTag("status.ffmpeg.install").assertIsDisplayed()
        screenshot("media-dark")
        onNodeWithTag("tools.media.back").performClick()
        onNodeWithTag("tools.history.all").performScrollTo().performClick()
        onNodeWithTag("tools.history.detail.history.1").performClick()
        onNodeWithText("2").assertExists()
        onNodeWithTag("tools.sheet.close").performClick()
        onNodeWithTag("tools.sheet").assertDoesNotExist()
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val target = File("target/tools-ui/$name.png").apply { parentFile.mkdirs() }
        val tag = if (onAllNodesWithTag("tools.media.workspace").fetchSemanticsNodes().isNotEmpty()) "tools.media.workspace" else if (onAllNodesWithTag("tools.sheet").fetchSemanticsNodes().isEmpty()) "tools.overview" else "tools.sheet"
        ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", target)
    }

    companion object {
        private val messages = Properties().apply {
            ToolsOverviewTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!.reader(Charsets.UTF_8).use(::load)
        }
        private fun resolve(token: TextToken) = if (token.key().isBlank()) token.fallback() else messages.getProperty(token.key(), token.key())
        fun activeTool(id: String) = Group(
            id,
            TextToken.raw(id),
            Container(
                "$id.content",
                ContainerLayout.COLUMN,
                1,
                12,
                Alignment.START,
                listOf(
                    Progress("$id.progress", 0.25, false, TextToken.raw("Processing")),
                    Text("$id.state", TextToken.raw("Running"), TextStyle.BODY, true, false),
                    Button("$id.cancel", "$id.cancel", TextToken.raw("Cancel"), null, ButtonStyle.NORMAL, true),
                ),
            ),
            false,
        )
        fun sample(values: Map<String, String> = mapOf("db" to "artworks.db", "proxy-host" to "localhost", "proxy-port" to "7890", "delay" to "800", "limit" to "0"),
                           activity: ToolActivity? = null, mediaTools: List<DesktopUiNode> = emptyList(),
                           fileImports: List<DesktopUiNode> = emptyList()): DesktopUiNode.ToolsOverview {
            fun button(id: String) = Button(id, id, TextToken.raw(id.substringAfterLast('.')), null, ButtonStyle.NORMAL, true)
            fun column(id: String, children: List<DesktopUiNode>) = Container(id, ContainerLayout.COLUMN, 1, 12, Alignment.START, children)
            fun content(id: String, form: Form? = null) = Group("tools.$id", TextToken.raw(id), column("tools.$id.body", listOfNotNull(form, button("tools.$id.run"))), false)
            val rows = values.map { (key, value) -> FormRow("row.$key", TextToken.raw(key), null,
                TextInput("tools.backfill.$key", "tools.backfill.$key", TextToken.raw(key), null, if (key == "db") InputKind.FILE else InputKind.TEXT, value, 20, 1, true), null) } +
                listOf("proxy", "dry", "always-network").map { key -> FormRow("row.$key", TextToken.raw(key), null,
                    Toggle("tools.backfill.$key", "tools.backfill.$key", TextToken.raw(key), null, ToggleStyle.CHECKBOX, key == "proxy", true), null) }
            val media = Group("media", TextToken.raw("FFmpeg"), column("media.content", listOf(
                Text("status.ffmpeg.state", TextToken.raw("Ready"), TextStyle.BODY, true, false), button("status.ffmpeg.install"))), false)
            val history = Table("history", "history.selection", listOf("tool", "outcome", "start", "end", "processed").map { TableColumn(it, TextToken.raw(it), 80) },
                listOf(TableRow("history.1", listOf("Backfill", "Succeeded", "09:01", "09:02", "2"))), SelectionMode.SINGLE, emptyList(), false)
            return DesktopUiNode.ToolsOverview("tools.workspace", Text("backend", TextToken.raw("Service running"), TextStyle.CAPTION, true, false),
                listOf(content("classifier"), content("folder"), content("backfill", Form("form.backfill", FormStyle.COMPACT, null, rows)), content("migration")), media, mediaTools, fileImports, history, activity, null)
        }
    }
}
