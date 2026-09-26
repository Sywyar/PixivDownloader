package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.settings.SettingsWorkspace
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import top.sywyar.pixivdownload.plugin.api.gui.*
import java.io.File
import java.text.MessageFormat
import java.util.Properties
import java.util.function.Function
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SettingsWorkspaceTest {
    @Test
    @DisplayName("路径和文本设置紧凑对齐，窄窗口换行后仍可编辑和浏览文件")
    fun compactSettingsRows() = runComposeUiTest {
        val model = createModel(mutableMapOf())
        var snapshot by mutableStateOf(model.snapshot())
        var width by mutableStateOf(1150.dp)
        val subscription = model.subscribeSnapshots { snapshot = it }
        try {
            setContent { PixivDownloaderTheme("light") {
                Box(Modifier.size(width, 820.dp).background(LocalExperiencePalette.current.surface)) {
                    SettingsWorkspace(workspace(snapshot), ::resolve, { model.dispatch(snapshot, it) })
                }
            } }
            onNodeWithTag("settings.category.download").performClick()
            onAllNodesWithText(resolve(TextToken.key("gui.config.group.download"))).assertCountEquals(2)
            capture("downloads-wide")
            for (key in listOf("download.root-folder", "sample.executable", "sample.address")) {
                val label = onNodeWithTag("config.app.$key.row.label").fetchSemanticsNode().boundsInRoot
                val input = onNodeWithContentDescription(key).fetchSemanticsNode().boundsInRoot
                assertTrue(input.left > label.right, "$key should share a row with its label")
                assertTrue(kotlin.math.abs(input.center.y - label.center.y) < 8, "$key should align vertically")
            }
            val browse = messages.getProperty("gui.compose.browse")
            onAllNodesWithText(browse).assertCountEquals(2)
            for (index in 0..1) {
                onAllNodesWithText(browse)[index].assertIsDisplayed()
                assertTrue(onAllNodesWithText(browse)[index].fetchSemanticsNode().boundsInRoot.width > 24)
            }
            runOnIdle { width = 530.dp }
            onNodeWithContentDescription("download.root-folder").performScrollTo()
            capture("downloads-narrow")
            val label = onNodeWithTag("config.app.download.root-folder.row.label").fetchSemanticsNode().boundsInRoot
            val input = onNodeWithContentDescription("download.root-folder").fetchSemanticsNode().boundsInRoot
            assertTrue(input.top >= label.bottom)
            assertTrue(input.top - label.bottom < 24, "Stacked fields should keep their label nearby")
            onNodeWithContentDescription("download.root-folder").performTextReplacement("D:/New folder")
            onNodeWithTag("config.save").assertIsDisplayed()
        } finally { subscription.close(); model.close() }
    }

    @Test
    @DisplayName("真实设置模型支持主题预览、跨分类草稿、搜索定位和统一保存")
    fun settingsFlow() = runComposeUiTest {
        val stored = mutableMapOf<String, String>()
        val model = createModel(stored)
        var snapshot by mutableStateOf(model.snapshot())
        val subscription = model.subscribeSnapshots { snapshot = it }
        try {
            setContent { PixivDownloaderTheme(model.themePreference()) {
                Box(Modifier.size(1150.dp, 820.dp).background(LocalExperiencePalette.current.surface)) {
                    SettingsWorkspace(workspace(snapshot), ::resolve, { model.dispatch(snapshot, it) })
                }
            } }
            onNodeWithTag("settings.savebar").assertDoesNotExist()
            capture("appearance")
            onNodeWithTag("settings.theme.dark").performClick()
            onNodeWithTag("settings.savebar").assertExists()
            onNodeWithTag("settings.category.interface.changes", useUnmergedTree = true).assertExists()
            assertEquals("dark", model.themePreference())
            assertNotEquals("dark", stored["app.theme"])
            onNodeWithTag("settings.category.download").performClick()
            onNodeWithContentDescription("download.root-folder").performTextReplacement("D:/Artwork")
            onNodeWithTag("settings.category.interface").performClick()
            onNodeWithTag("settings.theme.dark").assertIsSelected()
            onNodeWithContentDescription(resolve(settingsKey("search"))).performTextInput("port")
            onNodeWithTag("config.app.server.port.row.locate").performClick()
            onNodeWithTag("config.app.server.port.row").assertIsDisplayed()
            onNodeWithContentDescription("server.port").performTextReplacement("70000")
            onNodeWithTag("config.save").performClick()
            waitUntil(timeoutMillis = 5000) { !model.busy() && workspace(model.snapshot()).invalidRow().isNotEmpty() }
            assertNotEquals("70000", stored["server.port"])
            onNodeWithTag("config.app.server.port.row").assertIsDisplayed()
            capture("network-error")
            onNodeWithContentDescription("server.port").performTextReplacement("18080")
            onNodeWithTag("settings.review").performClick()
            onNodeWithTag("settings.review.dialog").assertExists()
            onNodeWithText("D:/Downloads → D:/Artwork").assertExists()
            onNodeWithContentDescription(resolve(settingsKey("close"))).performClick()
            onNodeWithTag("config.save").performClick()
            waitUntil(timeoutMillis = 5000) { !model.busy() && workspace(model.snapshot()).changes().isEmpty() }
            assertEquals("18080", stored["server.port"])
            assertEquals("D:/Artwork", stored["download.root-folder"])
            assertEquals("dark", stored["app.theme"])
        } finally { subscription.close(); model.close() }
    }

    @Test
    @DisplayName("窄窗口和减少动态效果下字段提示可悬停、键盘关闭且保存入口可达")
    fun narrowHints() = runComposeUiTest(effectContext = object : MotionDurationScale { override val scaleFactor = 0f }) {
        val model = createModel(mutableMapOf())
        var snapshot by mutableStateOf(model.snapshot())
        val subscription = model.subscribeSnapshots { snapshot = it }
        try {
            setContent { PixivDownloaderTheme("light") {
                Box(Modifier.size(530.dp, 700.dp).background(LocalExperiencePalette.current.surface)) {
                    SettingsWorkspace(workspace(snapshot), ::resolve, { model.dispatch(snapshot, it) })
                }
            } }
            val label = onNodeWithTag("interface.language.row.label")
            onNodeWithTag("interface.language.row.label.hint").assertDoesNotExist()
            label.performMouseInput { moveTo(center) }
            waitUntil(timeoutMillis = 2000) { onAllNodesWithTag("interface.language.row.label.hint").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("interface.language.row.label.hint").assertIsDisplayed()
            label.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            label.performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("interface.language.row.label.hint").assertDoesNotExist()
            onNodeWithTag("settings.theme.dark").performScrollTo().performClick()
            onNodeWithTag("config.save").assertIsDisplayed()
            capture("narrow")
        } finally { subscription.close(); model.close() }
    }

    private fun createModel(stored: MutableMap<String, String>): ComposeDesktopUiModel {
        val fields = listOf(
            GuiConfigFieldContribution("download.root-folder", GuiConfigGroups.DOWNLOAD, "Download folder", "Folder for new downloads",
                GuiConfigFieldType.PATH_DIR, "D:/Downloads", 1, false, GuiConfigEffect.HOT_RELOAD),
            GuiConfigFieldContribution("sample.executable", GuiConfigGroups.DOWNLOAD, "Executable", "Optional executable path",
                GuiConfigFieldType.PATH_FILE, "", 2, false, GuiConfigEffect.HOT_RELOAD),
            GuiConfigFieldContribution("sample.address", GuiConfigGroups.DOWNLOAD, "Address", "Service address",
                GuiConfigFieldType.STRING, "localhost", 3, false, GuiConfigEffect.HOT_RELOAD),
            GuiConfigFieldContribution("sample.parallel", GuiConfigGroups.DOWNLOAD, "Parallel downloads", "Maximum parallel downloads",
                GuiConfigFieldType.INT, "4", 4, false, GuiConfigEffect.HOT_RELOAD),
            GuiConfigFieldContribution("sample.flat", GuiConfigGroups.DOWNLOAD, "Flat folders", "Keep downloads in one folder",
                GuiConfigFieldType.BOOL, "false", 5, false, GuiConfigEffect.HOT_RELOAD),
            GuiConfigFieldContribution("server.port", GuiConfigGroups.SERVER, "Server port", "Port used by the local download service",
                GuiConfigFieldType.PORT, "8080", 1, false, GuiConfigEffect.BACKEND_RESTART),
        )
        return DesktopConfigurationControllerTest.model(stored, mapOf(
            "coreConfigFields" to Function { fields },
            "coreConfigGroups" to Function { listOf(
                GuiConfigGroupContribution(GuiConfigGroups.DOWNLOAD, "gui.config.group.download", null, 1, true),
                GuiConfigGroupContribution(GuiConfigGroups.SERVER, "Local service", null, 2, true),
            ) },
            "validateCoreConfigValue" to Function { null },
            "guiPostJson" to Function { DesktopUiHost.GuiResponse.unreachable() },
        ))
    }

    private fun workspace(snapshot: DesktopUiSnapshot) = top.sywyar.pixivdownload.guicompose.settings.descendants(
        snapshot.document().pages().first { it.id() == "settings" }.content()).filterIsInstance<DesktopUiNode.SettingsWorkspace>().first()

    private fun ComposeUiTest.capture(name: String) {
        val image = onNodeWithTag("settings.workspace").captureToImage().toAwtImage()
        val file = File("build/reports/settings-workspace/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }

    private fun settingsKey(key: String) = TextToken(GuiComposePlugin.ID, "gui.compose.settings.$key", "", emptyList())
    private val messages = Properties().apply {
        SettingsWorkspaceTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!.reader(Charsets.UTF_8).use(::load)
    }
    private val app = Properties().apply {
        File("../pixivdownload-app/src/main/resources/i18n/messages_en.properties").reader(Charsets.UTF_8).use(::load)
    }
    private fun resolve(token: TextToken): String = MessageFormat.format(
        messages.getProperty(token.key(), app.getProperty(token.key(), token.fallback().ifBlank { token.key() })), *token.arguments().toTypedArray(),
    )
}
