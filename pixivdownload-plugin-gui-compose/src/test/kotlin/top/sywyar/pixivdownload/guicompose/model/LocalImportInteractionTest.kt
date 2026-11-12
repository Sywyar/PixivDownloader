package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import top.sywyar.pixivdownload.guicompose.tools.FileImportWorkspace
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.*
import java.nio.file.*
import java.text.MessageFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LocalImportInteractionTest {
    @Test
    @DisplayName("真实导入表单支持多行 ID、预设和刷新，窄矮窗口大字体仍可确认与取消")
    fun importsFromRenderedForm() = runSkikoComposeUiTest(size = Size(480f, 560f), density = Density(1f, 1.25f)) {
        val labels = Properties().apply {
            Files.newBufferedReader(Path.of("../pixivdownload-app/src/main/resources/i18n/messages_en.properties")).use(::load)
        }
        val compose = Properties().apply {
            LocalImportInteractionTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!.reader(Charsets.UTF_8).use(::load)
        }
        fun text(token: TextToken): String = if (token.key().isBlank()) token.fallback() else MessageFormat(
            (if (token.namespace() == "gui-compose") compose else labels).getProperty(token.key(), token.key()), Locale.US
        ).format(token.arguments().toTypedArray())
        val captured = AtomicReference<Request>()
        val variableFailure = AtomicReference<VariableException>()
        val phase = AtomicReference(Phase.READY)
        val id = UUID.randomUUID()
        val service = object : LocalArtworkImports {
            override fun presets() = listOf(Preset("Pictures", "D:/{artworkId}/", true))
            override fun scan(request: Request): Snapshot {
                variableFailure.get()?.let { throw it }
                captured.set(request)
                return status(id)
            }
            override fun status(token: UUID) = Snapshot(id, phase.get(), 1, 1, 0, 0, 0, 0, false, emptyList())
            override fun items(token: UUID, offset: Int, limit: Int) = listOf(Item(42, listOf(File("D:/42/pic.png", null, emptyMap())), Disposition.NEW, ""))
            override fun start(token: UUID): Snapshot { assertEquals(id, token); phase.set(Phase.IMPORTING); return status(id) }
            override fun cancel(token: UUID): Snapshot { phase.set(Phase.CANCELLED); return status(id) }
        }
        val stored = HashMap<String, String>()
        val opened = AtomicReference<java.net.URI>()
        DesktopConfigurationControllerTest.model(stored, mapOf(
            "localArtworkImports" to Function<Array<Any>, Any> { Optional.of(service) },
            "message" to Function<Array<Any>, Any> { labels.getProperty(it[0].toString(), it[0].toString()) },
            "openExternalUri" to Function<Array<Any>, Any?> { opened.set(it[0] as java.net.URI); null },
        )).use { model ->
            var snapshot by mutableStateOf(model.snapshot())
            model.subscribeSnapshots { snapshot = it }.use {
                setContent {
                    PixivDownloaderTheme("dark") {
                        val current = snapshot
                        val tool = DesktopMediaToolsControllerTest.nodes(model).filter { it.id() == "local-import" }.findFirst().get() as Group
                        FileImportWorkspace(tool, ::text, { model.dispatch(current, it) }, {})
                    }
                }
                for (variable in listOf("foo", "ai")) {
                    val failure = VariableException(variable, variable == "ai")
                    variableFailure.set(failure)
                    onNodeWithTag("local-import.templates").performScrollTo()
                        .performTextReplacement("D:/{artwork_id}_{${variable}}.png")
                    onNodeWithTag("local-import.scan").assertIsDisplayed().performClick()
                    val error = MessageFormat(labels.getProperty("local-import.error.${failure.message}"), Locale.US)
                        .format(arrayOf("{$variable}"))
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText(error).performScrollTo().assertIsDisplayed()
                    onNodeWithTag("local-import.start").assertIsNotEnabled()
                    val errorImage = Path.of("target/tools-ui/local-import-variable-$variable.png")
                    Files.createDirectories(errorImage.parent)
                    ImageIO.write(captureToImage().toAwtImage(), "png", errorImage.toFile())
                }
                variableFailure.set(null)
                onNodeWithTag("local-import.templates").performScrollTo().performTextClearance()
                onNodeWithTag("local-import.ids").performScrollTo().performTextInput("42\n43")
                onNodeWithTag("local-import.guide").performScrollTo().assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { opened.get() != null }
                assertEquals(labels.getProperty("local-import.guide-url"), opened.get().toString())
                onNodeWithTag("local-import.ids").performScrollTo().assertTextContains("42\n43")
                onNodeWithText("Pictures").performScrollTo().performClick()
                onNodeWithText(labels.getProperty("local-import.refresh")).performScrollTo().performClick()
                onNodeWithTag("local-import.scan").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { captured.get() != null }
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("local-import.start") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(listOf(42L, 43L), captured.get().artworkIds())
                assertEquals(listOf("Pictures"), captured.get().presetNames())
                assertTrue(captured.get().refreshIndex())
                val image = Path.of("target/tools-ui/local-template-import.png")
                Files.createDirectories(image.parent)
                ImageIO.write(captureToImage().toAwtImage(), "png", image.toFile())
                onNodeWithTag("local-import.start").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("local-import.cancel") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("local-import.cancel").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { phase.get() == Phase.CANCELLED }
                assertTrue(stored.isEmpty())
            }
        }
    }
}
