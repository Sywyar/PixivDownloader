package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
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
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution
import java.io.File
import java.lang.reflect.Proxy
import java.text.MessageFormat
import java.util.Properties
import java.util.function.Function
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SettingsWorkspaceTest {
    @Test
    @DisplayName("浅色设置在目标字段旁查询候选，下拉选择可连续切换并适应窄窗口")
    fun lightFieldSelection() = fieldSelection("light")

    @Test
    @DisplayName("深色设置在目标字段旁查询候选，下拉选择可连续切换并适应窄窗口")
    fun darkFieldSelection() = fieldSelection("dark")

    private fun fieldSelection(theme: String) = runComposeUiTest {
        val model = DesktopConfigurationActionTest.model(
            AtomicReference(listOf(DesktopConfigurationActionTest.source("demo.value", false))),
            DesktopConfigurationActionTest::response,
        )
        var snapshot by mutableStateOf(model.snapshot())
        var width by mutableStateOf(1150.dp)
        val subscription = model.subscribeSnapshots { snapshot = it }
        try {
            setContent {
                PixivDownloaderTheme(theme) {
                    Box(Modifier.size(width, 820.dp).background(LocalExperiencePalette.current.surface)) {
                        SettingsWorkspace(
                            workspace(snapshot),
                            ::resolve,
                            { model.dispatch(snapshot, it) },
                        )
                    }
                }
            }
            onNodeWithTag("settings.category.config.demo").performClick()
            val row = onNodeWithTag("config.demo.demo.value.row", useUnmergedTree = true)
            val input = onNodeWithTag("config.demo.demo.value.input", useUnmergedTree = true)
            val query = onNodeWithText("action.get")
            query.assertIsDisplayed().assert(hasAnyAncestor(hasTestTag("config.demo.demo.value.row")))
            val inputBounds = input.fetchSemanticsNode().boundsInRoot
            val queryBounds = query.fetchSemanticsNode().boundsInRoot
            assertTrue(queryBounds.left >= inputBounds.right)
            assertTrue(queryBounds.center.y in inputBounds.top..inputBounds.bottom)
            query.performClick()
            waitUntil(timeoutMillis = 5000) { !model.busy() }
            val choice = onNode(
                hasContentDescription("action.get") and hasAnyAncestor(hasTestTag("config.demo.demo.value.row")),
            )
            choice.assertIsDisplayed().performClick()
            onNodeWithText("test/2+测试").performClick()
            onNodeWithContentDescription("demo.value").assertTextEquals("test/2+测试")
            capture("field-selection-$theme-wide")
            runOnIdle { width = 360.dp }
            row.assertIsDisplayed()
            query.assertIsDisplayed()
            assertTrue(query.fetchSemanticsNode().boundsInRoot.top >= input.fetchSemanticsNode().boundsInRoot.bottom)
            choice.assertIsDisplayed().performClick()
            onNodeWithText("test/3+测试").performClick()
            onNodeWithContentDescription("demo.value").assertTextEquals("test/3+测试")
            capture("field-selection-$theme-narrow")
            onNodeWithContentDescription("demo.value").performTextReplacement("custom")
            choice.assertDoesNotExist()
        } finally { subscription.close(); model.close() }
    }

    @Test
    @DisplayName("浅色设置密码框可辨认，草稿可测试且显式清除后重置")
    fun lightCredentialInput() = credentialInput("light")

    @Test
    @DisplayName("深色设置密码框可辨认，草稿可测试且显式清除后重置")
    fun darkCredentialInput() = credentialInput("dark")

    private fun credentialInput(theme: String) = runComposeUiTest {
        val field = GuiConfigFieldContribution(
            "sample.secret",
            "sample",
            "sample.secret.label",
            GuiConfigFieldType.PASSWORD,
            "",
            1,
        )
        val action = GuiConfigActionContribution(
            "probe",
            "sample.probe",
            "sample-probe",
            1,
            listOf(GuiConfigActionPayloadField("secret", field.key())),
        )
        val section = GuiConfigSectionContribution(
            "sample.connection",
            "sample",
            "",
            "",
            "sample",
            GuiConfigSectionLayout.FIELD_LIST,
            1,
            listOf(GuiConfigFieldLayoutContribution(field.key(), "", "", 1)),
            listOf(action),
            emptyList(),
        )
        val source = DesktopUiPluginSnapshot(
            "sample",
            false,
            "sample",
            1,
            false,
            "sample",
            "sample.title",
            emptyList(),
            listOf(GuiConfigContribution(
                listOf(GuiConfigGroupContribution("sample", "sample.title", "sample", 1, true)),
                listOf(field),
                listOf(section),
            )),
            emptyList(),
            listOf(WebRouteContribution.gui("/api/gui/sample-probe")),
            emptyList(),
        )
        val config = Proxy.newProxyInstance(
            DesktopUiHost.ConfigFile::class.java.classLoader,
            arrayOf(DesktopUiHost.ConfigFile::class.java),
        ) { _, method, _ ->
            check(method.name == "readAll") { "Unexpected config write" }
            emptyMap<String, String>()
        } as DesktopUiHost.ConfigFile
        val request = AtomicReference<Map<*, *>>()
        val cleared = AtomicReference<Map<*, *>>()
        val model = DesktopConfigurationControllerTest.model(
            mutableMapOf(),
            mapOf(
                "pluginConfig" to Function { config },
                "updateCredentials" to Function { args ->
                    cleared.set(args[1] as Map<*, *>)
                    null
                },
                "guiPostJson" to Function { args ->
                    request.set(args[1] as Map<*, *>)
                    DesktopUiHost.GuiResponse.unreachable()
                },
            ),
        ) { listOf(source) }
        var snapshot by mutableStateOf(model.snapshot())
        val subscription = model.subscribeSnapshots { snapshot = it }
        try {
            setContent {
                PixivDownloaderTheme(theme) {
                    Box(Modifier.size(1150.dp, 820.dp).background(LocalExperiencePalette.current.surface)) {
                        SettingsWorkspace(
                            workspace(snapshot),
                            ::resolve,
                            { model.dispatch(snapshot, it) },
                        )
                    }
                }
            }
            onNodeWithTag("settings.category.config.sample").performClick()
            val input = onNodeWithTag("config.sample.sample.secret.input", useUnmergedTree = true)
            input.assertIsDisplayed().assertIsEnabled()
            val image = input.captureToImage().toAwtImage()
            val edge = java.awt.Color(image.getRGB(0, image.height / 2))
            val center = java.awt.Color(image.getRGB(image.width / 2, image.height / 2))
            assertTrue(kotlin.math.abs(edge.red - center.red) > 12, "Empty credential input needs a visible boundary")
            capture("credential-$theme")
            val editor = onNodeWithContentDescription("sample.secret")
            editor.performTextInput("test-draft-secret")
            editor.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            assertFalse(workspace(model.snapshot()).toString().contains("test-draft-secret"))
            onNodeWithTag("settings.category.interface").performClick()
            onNodeWithTag("settings.category.config.sample").performClick()
            onNodeWithText("sample.probe").performClick()
            waitUntil(timeoutMillis = 5000) { request.get() != null && !model.busy() }
            assertEquals("test-draft-secret", request.get()["secret"])
            onNodeWithText(resolve(TextToken.key("desktop.ui.config.clear-secret"))).performClick()
            waitUntil(timeoutMillis = 5000) { cleared.get() != null && !model.busy() }
            assertEquals(mapOf("sample.secret" to ""), cleared.get())
            editor.assertTextEquals("")
        } finally { subscription.close(); model.close() }
    }

    @Test
    @DisplayName("设置搜索支持标题和说明命中，显示匹配上下文并定位字段")
    fun searchExplainsSecondaryMatchesAndLocatesFields() = runComposeUiTest {
        val model = createModel(mutableMapOf())
        var snapshot by mutableStateOf(model.snapshot())
        val subscription = model.subscribeSnapshots { snapshot = it }
        try {
            setContent { PixivDownloaderTheme("light") {
                Box(Modifier.size(1150.dp, 820.dp).background(LocalExperiencePalette.current.surface)) {
                    SettingsWorkspace(workspace(snapshot), ::resolve, { model.dispatch(snapshot, it) })
                }
            } }
            onNodeWithContentDescription(resolve(settingsKey("search"))).performTextInput("port")
            val titleResult = onNodeWithTag("config.app.server.port.row.locate")
            titleResult.assertIsDisplayed()
            titleResult.performClick()
            onNodeWithTag("config.app.server.port.row").assertIsDisplayed()
            onNodeWithContentDescription(resolve(settingsKey("search"))).performTextInput("service")
            onNodeWithText("Service address").assertIsDisplayed()
            capture("search-help")
            onNodeWithTag("config.app.sample.address.row.locate").performClick()
            onNodeWithTag("config.app.sample.address.row").assertIsDisplayed()
        } finally { subscription.close(); model.close() }
    }

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
