package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost
import java.nio.file.*
import java.text.MessageFormat
import java.util.Properties
import java.util.Locale
import java.util.function.Function
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FileImportInteractionTest {
    @Test
    @DisplayName("导入完成后确认回填，从首页或导入表单打开本轮进度或快速完成结果，收起后不重复跳转")
    fun startBackfillOpensProgress() {
        for (fromImport in listOf(false, true)) startBackfillOpensProgress(fromImport)
        startBackfillOpensProgress(false, true)
    }

    private fun startBackfillOpensProgress(fromImport: Boolean, completed: Boolean = false) = runSkikoComposeUiTest(size = Size(1200f, 900f)) {
        val fixture = DesktopFileImportControllerTest.Fixture()
        val labels = Properties().apply {
            Files.newBufferedReader(Path.of("../pixivdownload-app/src/main/resources/i18n/messages_en.properties")).use(::load)
        }
        val compose = Properties().apply {
            FileImportInteractionTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!
                .reader(Charsets.UTF_8).use(::load)
        }
        fun resolve(token: TextToken): String = if (token.key().isBlank()) token.fallback() else
            MessageFormat((if (token.namespace() == "gui-compose") compose else labels)
                .getProperty(token.key(), token.fallback()), Locale.US).format(token.arguments().toTypedArray())
        val id = java.util.UUID.randomUUID()
        val current = java.util.concurrent.atomic.AtomicReference<DesktopUiToolHost.BackfillTask>()
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val calls = mapOf<String, Function<Array<Any>, Any>>(
            "fileImportTools" to Function { fixture.tools.get() },
            "fileImportTool" to Function { fixture.source },
            "backfillTask" to Function { java.util.Optional.ofNullable(current.get()) },
            "backendSnapshot" to Function {
                top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.BackendSnapshot(
                    top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.BackendState.RUNNING, null)
            },
            "message" to Function { args ->
                MessageFormat(labels.getProperty(args[0].toString(), args[0].toString()), Locale.US)
                    .format(args.getOrNull(1) as? Array<*> ?: emptyArray<Any>())
            },
            "startBackfill" to Function {
                starts.incrementAndGet()
                current.set(DesktopUiToolHost.BackfillTask(
                    id, 0, if (completed) DesktopUiToolHost.BackfillState.COMPLETED else DesktopUiToolHost.BackfillState.RUNNING,
                    DesktopUiToolHost.BackfillPhase.NETWORK,
                    false, false, 1, if (completed) 2 else 0, 7, if (completed) 7 else 11, 7, 0, 0,
                    null, null, null, "fixture.html"))
                id
            },
        )
        DesktopConfigurationControllerTest.model(HashMap(), calls).use { model ->
            var snapshot by mutableStateOf(model.snapshot())
            model.subscribeSnapshots { snapshot = it }.use {
                setContent {
                    PixivDownloaderTheme(if (fromImport) "dark" else "light") {
                        DesktopShell(
                            document = snapshot.document(),
                            documentRevision = snapshot.revision(),
                            resolveText = ::resolve,
                            automaticBackfillNotice = snapshot.automaticBackfillNotice(),
                            dismissAutomaticBackfill = model::dismissAutomaticBackfill,
                            requestedBackfill = snapshot.requestedBackfill(),
                            consumeBackfillNavigation = model::consumeBackfillNavigation,
                            dispatch = { model.dispatch(snapshot, it) },
                        )
                        snapshot.document().dialogs().forEach { dialog ->
                            DocumentDialog(
                                dialog,
                                ::resolve,
                                labels.getProperty("desktop.ui.action.close"),
                                { model.dispatch(snapshot, it) },
                                snapshot.revision(),
                            )
                        }
                    }
                }
                if (fromImport) {
                    onNodeWithText(labels.getProperty("desktop.ui.page.tools")).performClick()
                    onNodeWithTag("tools.entry.${DesktopFileImportControllerTest.PREFIX}").performScrollTo().performClick()
                    onNodeWithTag("tools.import.workspace").assertIsDisplayed()
                } else {
                    onNodeWithTag("tools.overview").assertDoesNotExist()
                }
                fixture.completion.set(top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.Completion("done", 7))
                val start = labels.getProperty("gui.import.backfill-start")
                waitUntilExactlyOneExists(hasText(start), timeoutMillis = 5_000)
                onNodeWithText(start).assertIsDisplayed().performClick()
                waitUntilExactlyOneExists(hasTestTag("tools.result"), timeoutMillis = 5_000)
                onNodeWithTag("tools.result").assertIsDisplayed().assertTextEquals(labels.getProperty(
                    if (completed) "gui.tools.backfill.state.completed" else "gui.tools.backfill.phase.network"))
                onNodeWithTag(if (completed) "tools.done" else "tools.backfill.cancel").assertIsDisplayed()
                onNodeWithTag("tools.import.workspace").assertDoesNotExist()
                assertEquals(1, starts.get())
                val image = Path.of("target/tools-ui/import-backfill-$fromImport-$completed.png")
                Files.createDirectories(image.parent)
                ImageIO.write(captureToImage().toAwtImage(), "png", image.toFile())
                onNodeWithTag("tools.sheet.close").performClick()
                runOnIdle { model.rebuildStatus() }
                onNodeWithTag("tools.result").assertDoesNotExist()
                onNodeWithTag("tools.entry.backfill").assertExists()
                assertEquals(null, model.snapshot().requestedBackfill())
                assertEquals(1, starts.get())
            }
        }
    }

    @Test
    @DisplayName("导入完成提示在窄窗口放大文字后操作仍可见，稍后关闭且不启动回填")
    fun completionDialog() = runSkikoComposeUiTest(size = Size(460f, 600f), density = Density(1f, 1.25f)) {
        val fixture = DesktopFileImportControllerTest.Fixture()
        fixture.completion.set(top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.Completion("done", 125))
        val labels = Properties().apply {
            Files.newBufferedReader(Path.of("../pixivdownload-app/src/main/resources/i18n/messages.properties")).use(::load)
        }
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val calls = mapOf<String, Function<Array<Any>, Any>>(
            "fileImportTools" to Function { fixture.tools.get() },
            "fileImportTool" to Function { fixture.source },
            "startBackfill" to Function { starts.incrementAndGet(); java.util.UUID.randomUUID() },
        )
        DesktopConfigurationControllerTest.model(HashMap(), calls).use { model ->
            var snapshot by mutableStateOf(model.snapshot())
            model.subscribeSnapshots { snapshot = it }.use {
                setContent {
                    PixivDownloaderTheme("dark") {
                        snapshot.document().dialogs().firstOrNull()?.let { dialog ->
                            DocumentDialog(dialog, { token ->
                                MessageFormat(labels.getProperty(token.key(), token.fallback()))
                                    .format(token.arguments().toTypedArray())
                            }, labels.getProperty("desktop.ui.action.close"),
                                { model.dispatch(snapshot, it) }, snapshot.revision())
                        }
                    }
                }
                val start = labels.getProperty("gui.import.backfill-start")
                val later = labels.getProperty("gui.import.backfill-later")
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(start).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText(start).assertIsDisplayed()
                onNodeWithText(later).assertIsDisplayed()
                assertEquals(0, starts.get())
                val image = Path.of("target/tools-ui/import-completion.png")
                Files.createDirectories(image.parent)
                ImageIO.write(captureToImage().toAwtImage(), "png", image.toFile())
                onNodeWithText(later).performClick()
                onNodeWithText(start).assertDoesNotExist()
                assertEquals(0, starts.get())
            }
        }
    }

    @Test
    @DisplayName("窄窗口放大文字下，历史导入表单可操作且提交不会显示凭据")
    fun compactWorkspace() = workspace(600, 680, 1.25f, "dark")

    @Test
    @DisplayName("宽窗口中导入随快捷工具排列，关闭弹窗后后台任务继续")
    fun wideWorkspace() = workspace(1200, 900, 1f, "light")

    @Test
    @DisplayName("中文长标题在深色工具页与导入工作区完整呈现")
    fun chineseWorkspace() = workspace(1200, 900, 1f, "dark", Locale.SIMPLIFIED_CHINESE)

    private fun workspace(width: Int, height: Int, fontScale: Float, theme: String, locale: Locale = Locale.US) = runSkikoComposeUiTest(
        size = Size(width.toFloat(), height.toFloat()),
        density = Density(1f, fontScale),
    ) {
        val messages = Properties()
        val suffix = if (locale == Locale.SIMPLIFIED_CHINESE) "" else "_en"
        Files.newBufferedReader(Path.of("../pixivdownload-app/src/main/resources/i18n/messages$suffix.properties")).use { messages.load(it) }
        val compose = Properties()
        javaClass.getResourceAsStream("/i18n/web/gui-compose$suffix.properties")!!.reader(Charsets.UTF_8).use { compose.load(it) }
        val importer = Properties()
        Files.newBufferedReader(Path.of("../pixivdownload-plugin-pixiv-batch-downloader-import/src/main/resources/i18n/web/pixiv-batch-downloader-import$suffix.properties")).use { importer.load(it) }
        fun resolve(token: TextToken) = if (token.key().isBlank()) token.fallback() else
            MessageFormat((if (token.namespace() == "gui-compose") compose else messages).getProperty(token.key(), token.key()), locale)
                .format(token.arguments().toTypedArray())
        val fixture = DesktopFileImportControllerTest.Fixture()
        fixture.tools.set(listOf(top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool(
            fixture.tool.identity(),
            top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.Description(
                top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.raw(importer.getProperty("history.title")),
                top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.raw(importer.getProperty("history.help")),
                true,
                top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.raw(importer.getProperty("history.recommendation")),
            ),
        )))
        val localType = top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports::class.java
        val localService = java.lang.reflect.Proxy.newProxyInstance(localType.classLoader, arrayOf(localType)) { _, method, _ ->
            if (method.name == "presets") emptyList<Any>() else error("Unexpected local import operation: ${method.name}")
        }
        val overrides = mapOf<String, Function<Array<Any>, Any>>(
            "localArtworkImports" to Function { java.util.Optional.of(localService) },
            "parseLocalImportPresets" to Function { emptyList<Any>() },
            "fileImportTools" to Function { fixture.tools.get() },
            "fileImportTool" to Function { fixture.source },
            "message" to Function { messages.getProperty(it[0].toString(), it[0].toString()) },
        )
        DesktopConfigurationControllerTest.model(HashMap(), overrides).use { model ->
            var snapshot by mutableStateOf(model.snapshot())
            model.subscribeSnapshots { snapshot = it }.use {
                setContent {
                    PixivDownloaderTheme(theme) {
                        Box(Modifier.size(width.dp, height.dp).background(LocalExperiencePalette.current.surface)) {
                            ComposeDesktopUiNodeRenderer.Render(snapshot.document().pages().first { it.id() == "tools" }.content(),
                                ::resolve, { model.dispatch(snapshot, it) })
                            snapshot.document().dialogs().firstOrNull()?.let { dialog ->
                                DocumentDialog(dialog, ::resolve, "Close", { model.dispatch(snapshot, it) }, snapshot.revision())
                            }
                        }
                    }
                }
                val prefix = DesktopFileImportControllerTest.PREFIX
                onNodeWithTag("tools.entry.migration").assertDoesNotExist()
                if (width >= 1000) {
                    val classifier = onNodeWithTag("tools.entry.classifier").fetchSemanticsNode().boundsInRoot
                    val backfill = onNodeWithTag("tools.entry.backfill").fetchSemanticsNode().boundsInRoot
                    val startup = onNodeWithTag("tools.entry.startup").fetchSemanticsNode().boundsInRoot
                    val history = onNodeWithTag("tools.entry.$prefix").fetchSemanticsNode().boundsInRoot
                    assertEquals(startup.left, history.left, 1f)
                    assertEquals(backfill.width, history.width, 1f)
                    assertEquals(backfill.left, classifier.left, 1f)
                    assertTrue(classifier.top >= history.bottom)
                    assertTrue(history.top >= backfill.bottom)
                }
                fun capture(tag: String, state: String) {
                    val file = Path.of("target/tools-ui/history-$width-${locale.language}-$state.png")
                    Files.createDirectories(file.parent)
                    ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", file.toFile())
                }
                capture("tools.overview", "overview")
                onNodeWithTag("tools.entry.local-import").performScrollTo().performClick()
                onNodeWithTag("local-import.templates").assertDoesNotExist()
                onNodeWithTag("local-import.presets").performScrollTo().performClick()
                waitUntilExactlyOneExists(hasText(messages.getProperty("local-import.save")), timeoutMillis = 5_000)
                onNodeWithText(messages.getProperty("local-import.save")).assertIsDisplayed()
                val presetImage = Path.of("target/tools-ui/presets-$width-${locale.language}.png")
                ImageIO.write(captureToImage().toAwtImage(), "png", presetImage.toFile())
                onNode(hasText(messages.getProperty("local-import.cancel")) and isEnabled()).performClick()
                onNodeWithTag("local-import.templates").assertDoesNotExist()
                capture("tools.import.workspace", "local-import-collapsed")
                onNodeWithTag("local-import.options").assertTextContains(messages.getProperty("local-import.options-required"))
                val missingTemplate = messages.getProperty("local-import.error.IMPORT_NO_TEMPLATES")
                repeat(2) {
                    onNodeWithTag("local-import.scan").assertIsEnabled().performClick()
                    waitUntilExactlyOneExists(hasText(missingTemplate), timeoutMillis = 5_000)
                    onNodeWithTag("local-import.templates").assertIsDisplayed().assertIsFocused()
                    onNodeWithTag("local-import.templates.label").assertTextEquals(messages.getProperty("local-import.templates-required"))
                    onNodeWithTag("local-import.templates.field").assert(
                        SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Error, missingTemplate))
                    capture("tools.import.workspace", "local-import-required")
                    if (it == 0) {
                        onNodeWithTag("local-import.options").performScrollTo().performClick()
                        onNodeWithTag("local-import.templates").assertDoesNotExist()
                    }
                }
                onNodeWithTag("local-import.templates").performTextInput("D:/{artwork_id}/")
                onNodeWithText(missingTemplate).assertDoesNotExist()
                onNodeWithTag("local-import.templates.field").assert(
                    SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
                capture("tools.import.workspace", "local-import")
                onNodeWithTag("tools.import.sheet.close").performClick()
                onNodeWithTag("tools.entry.$prefix").performScrollTo().performClick()
                onNodeWithText(importer.getProperty("history.recommendation")).performScrollTo().assertIsDisplayed()
                onNodeWithTag("$prefix.file").performScrollTo().performTextInput("/records.json")
                onNodeWithTag("$prefix.root").performScrollTo().performTextInput("/original")
                onNodeWithTag("$prefix.credential").performScrollTo().performTextInput("private-cookie")
                onNodeWithTag("tools.sheet").assertIsDisplayed()
                onNodeWithTag("tools.import.sheet.close").assertIsDisplayed().performTouchInput { click() }
                onNodeWithTag("tools.overview").assertIsDisplayed()
                onNodeWithTag("tools.entry.$prefix").assertIsFocused().performClick()
                onNodeWithTag("$prefix.file").performScrollTo().assertTextEquals("/records.json")
                onNodeWithTag("$prefix.credential").performScrollTo().assertTextEquals("")
                onNodeWithTag("$prefix.credential").performTextInput("private-cookie")
                onNodeWithTag("$prefix.inspect").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("$prefix.start").fetchSemanticsNodes().any { SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Disabled, Unit).matches(it).not() } }
                assertEquals(0, fixture.starts.get())
                onNodeWithTag("$prefix.start").assertIsDisplayed().assertIsEnabled()
                capture("tools.import.workspace", "workspace")
                onNodeWithTag("$prefix.start").performClick()
                waitUntil(timeoutMillis = 5_000) { fixture.starts.get() == 1 }
                onNodeWithTag("$prefix.credential").assertTextEquals("")
                onNodeWithTag("tools.import.sheet.close")
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                    .performKeyInput { pressKey(Key.Enter) }
                onNodeWithTag("tools.current.$prefix").assertIsDisplayed()
                capture("tools.overview", "running")
                assertEquals(top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.Phase.RUNNING, fixture.state.get().phase())
                onNodeWithTag("tools.current.$prefix").performClick()
                onNodeWithTag("$prefix.cancel").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { fixture.state.get().phase() == top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.Phase.CANCELLED }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("$prefix.inspect").fetchSemanticsNodes().any { SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Disabled).matches(it) } }
                onNodeWithTag("tools.import.sheet.close")
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                    .performKeyInput { pressKey(Key.Escape) }
                onNodeWithTag("tools.current.$prefix").assertDoesNotExist()
                onNodeWithTag("tools.entry.$prefix").assertIsFocused()
                onNodeWithTag("tools.entry.$prefix").performClick()
                runOnIdle { fixture.tools.set(emptyList()); model.rebuild() }
                waitUntilExactlyOneExists(hasTestTag("tools.overview"))
                onNodeWithTag("tools.entry.$prefix").assertDoesNotExist()
                onNodeWithTag("tools.entry.backfill").assertIsFocused()
            }
        }
    }
}
