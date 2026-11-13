package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import top.sywyar.pixivdownload.guicompose.settings.SettingsWorkspace
import top.sywyar.pixivdownload.plugin.api.gui.*
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Preset
import java.io.File
import java.text.MessageFormat
import java.util.*
import java.util.function.Function
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ImportPresetEditorInteractionTest {
    @Test
    @DisplayName("路径双击编辑与取消不影响其他行，新增聚焦，确认只更新设置草稿")
    fun editPathsInSettings() {
        for ((width, height, theme) in listOf(Triple(1000, 760, "dark"), Triple(460, 460, "light"))) {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1f, 1.25f)) {
                val stored = hashMapOf<String, String>()
                val paths = listOf("D:/作品/{artwork_id}/", "E:/作者/{author_id}/{artwork_id}/", "F:/" + "长路径/".repeat(15) + "{artwork_id}/")
                var formatted: List<Preset>? = null
                val model = fixture(stored, paths) { formatted = it }
                var snapshot by mutableStateOf(model.snapshot())
                val subscription = model.subscribeSnapshots { snapshot = it }
                fun activate(id: String) = model.dispatch(model.snapshot(), Event(EventType.ACTIVATE, id, Value.empty()))
                fun open() = activate("config.app.local-import.presets.input")
                fun row(index: Int) = onNodeWithTag("import-presets.field.$index.template")
                fun input(index: Int) = onNode(hasSetTextAction() and hasTestTag("import-presets.field.$index.template"))
                fun button(key: String) = onNodeWithText(labels.getProperty("local-import.$key"))
                try {
                    setContent {
                        PixivDownloaderTheme(theme) {
                            Box(Modifier.fillMaxSize().background(LocalExperiencePalette.current.surface)) {
                                val page = snapshot.document().pages().first { it.id() == "settings" }
                                val workspace = top.sywyar.pixivdownload.guicompose.settings.descendants(page.content()).filterIsInstance<SettingsWorkspace>().first()
                                SettingsWorkspace(workspace, ::text, { model.dispatch(snapshot, it) })
                                snapshot.document().dialogs().firstOrNull()?.let { dialog ->
                                    DocumentDialog(dialog, ::text, "Close", { model.dispatch(snapshot, it) }, snapshot.revision())
                                }
                            }
                        }
                    }
                    runOnIdle { open() }
                    onNodeWithText(labels.getProperty("gui.config.directory.variables")).performScrollTo().performClick()
                    onNodeWithText(labels.getProperty("local-import.template-kind-help")).performScrollTo().assertIsDisplayed()
                    val rulesImage = File("target/preset-ui/rules-$theme.png").apply { parentFile.mkdirs() }
                    ImageIO.write(captureToImage().toAwtImage(), "png", rulesImage)
                    onNodeWithText(labels.getProperty("gui.config.directory.variables")).performScrollTo().performClick()
                    row(1).performScrollTo().performMouseInput { click() }
                    mainClock.advanceTimeBy(1000)
                    row(1).assertIsSelected()
                    input(1).assertDoesNotExist()
                    row(1).performMouseInput { doubleClick() }
                    input(1).performTextReplacement("E:/cancelled/{artwork_id}/")
                    input(1).performKeyInput { pressKey(Key.Escape) }
                    row(1).assertTextContains(paths[1]).assertIsFocused()
                    input(1).assertDoesNotExist()
                    row(1).performKeyInput { pressKey(Key.F2) }
                    input(1).performScrollTo().performTextReplacement("E:/changed/{artwork_id}/")
                    input(1).performKeyInput { pressKey(Key.Enter) }
                    onNodeWithTag("import-presets.field.0.remove").performScrollTo().performClick()
                    row(1).performScrollTo().assertTextContains("E:/changed/{artwork_id}/").assertIsFocused()
                    row(2).performScrollTo().assertTextContains(paths[2])
                    button("add").assertIsDisplayed().performClick()
                    input(3).performScrollTo().assertIsFocused().performTextInput("G:/{artwork_id}/")
                    button("cancel").assertIsDisplayed().performClick()
                    runOnIdle { assertNull(formatted); assertTrue(stored.isEmpty()); open() }
                    row(0).performScrollTo().assertTextContains(paths[0])
                    onNodeWithTag("import-presets.field.1.remove").performScrollTo().performClick()
                    button("add").performClick()
                    button("apply").performClick()
                    onNodeWithText(labels.getProperty("local-import.error.INVALID_IMPORT_PRESET")).performScrollTo().assertIsDisplayed()
                    row(3).performScrollTo().performMouseInput { doubleClick() }
                    input(3).performTextInput("G:/{artwork_id}/")
                    row(0).performScrollTo()
                    button("apply").assertIsDisplayed()
                    val image = File("target/preset-ui/editor-$theme.png").apply { parentFile.mkdirs() }
                    ImageIO.write(captureToImage().toAwtImage(), "png", image)
                    button("apply").performClick()
                    runOnIdle {
                        assertEquals(listOf(paths[0], paths[2], "G:/{artwork_id}/"), formatted!!.map { it.template() })
                        assertTrue(stored.isEmpty())
                    }
                    runOnIdle { activate("config.save") }
                    waitUntil(timeoutMillis = 5000) { !model.busy() && stored.containsKey("local-import.presets") }
                    assertTrue(stored.containsKey("local-import.presets"))
                } finally { subscription.close(); model.close() }
            }
        }
    }

    companion object {
        private val labels = Properties().apply {
            File("../pixivdownload-app/src/main/resources/i18n/messages_en.properties").reader(Charsets.UTF_8).use(::load)
            File("src/main/resources/i18n/web/gui-compose_en.properties").reader(Charsets.UTF_8).use(::load)
        }
        fun text(token: TextToken): String = if (token.key().isBlank()) token.fallback() else
            MessageFormat(labels.getProperty(token.key(), token.fallback().ifBlank { token.key() }), Locale.US).format(token.arguments().toTypedArray())

        fun fixture(stored: MutableMap<String, String>, paths: List<String>, formatted: (List<Preset>) -> Unit): ComposeDesktopUiModel {
            val original = paths.map(::Preset)
            var draft = original
            val field = GuiConfigFieldContribution("local-import.presets", "download", "Presets", "",
                GuiConfigFieldType.PATH_TEMPLATES, "original", 0, false, GuiConfigEffect.BACKEND_RESTART)
            return DesktopConfigurationControllerTest.model(stored, mapOf(
                "coreConfigFields" to Function<Array<Any>, Any> { listOf(field) },
                "coreConfigGroups" to Function<Array<Any>, Any> { listOf(GuiConfigGroupContribution("download", "Download", 0)) },
                "parseLocalImportPresets" to Function<Array<Any>, Any> { if (it[0] == "original") original else draft },
                "formatLocalImportPresets" to Function<Array<Any>, Any> {
                    @Suppress("UNCHECKED_CAST")
                    val entries = it[0] as List<Preset>
                    draft = entries; formatted(entries); "draft"
                },
                "validateCoreConfigValue" to Function<Array<Any>, Any?> { null },
            ))
        }
    }
}
