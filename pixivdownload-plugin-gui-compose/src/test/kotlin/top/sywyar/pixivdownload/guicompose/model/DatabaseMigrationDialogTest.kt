package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.DocumentDialog
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import top.sywyar.pixivdownload.guicompose.settings.SettingsWorkspace
import top.sywyar.pixivdownload.guicompose.settings.descendants
import top.sywyar.pixivdownload.plugin.api.gui.*
import java.io.File
import java.net.URI
import java.text.MessageFormat
import java.util.Optional
import java.util.Properties
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@DisplayName("数据库迁移确认弹窗布局")
class DatabaseMigrationDialogTest {
    @Test
    @DisplayName("普通窗口的长清单可滚动，取消保留草稿且确认只提交一次")
    fun regularWindow() = migrationDialog(1100, 740, "light", 1f)

    @Test
    @DisplayName("窄矮深色窗口放大文字后，确认和取消仍完整显示且不随清单滚动")
    fun compactWindow() = migrationDialog(560, 400, "dark", 1.3f)

    @Test
    @DisplayName("中文深色窗口的数据清单可扫读，展开与刷新保留上下文")
    fun chineseWindow() = migrationDialog(1100, 740, "dark", 1f, Locale.SIMPLIFIED_CHINESE)

    private fun migrationDialog(width: Int, height: Int, theme: String, fontScale: Float, locale: Locale = Locale.ENGLISH) = runSkikoComposeUiTest(
        size = Size(width.toFloat(), height.toFloat()),
        effectContext = object : MotionDurationScale { override val scaleFactor = 0f },
    ) {
        val suffix = if (locale == Locale.SIMPLIFIED_CHINESE) "" else "_en"
        val app = Properties().apply {
            File("../pixivdownload-app/src/main/resources/i18n/messages$suffix.properties").reader(Charsets.UTF_8).use(::load)
        }
        val messages = Properties().apply {
            DatabaseMigrationDialogTest::class.java.getResourceAsStream("/i18n/web/gui-compose$suffix.properties")!!
                .reader(Charsets.UTF_8).use(::load)
        }
        fun resolve(token: TextToken): String = MessageFormat(
            messages.getProperty(token.key(), app.getProperty(token.key(), token.fallback().ifBlank { token.key() })),
            locale,
        ).format(token.arguments().toTypedArray())
        fun capture(name: String) {
            System.getenv("PIXIV_DIALOG_SCREENSHOTS")?.let { directory ->
                val file = File(directory, "$name-${locale.language}.png")
                file.parentFile.mkdirs()
                ImageIO.write(captureToImage().toAwtImage(), "png", file)
            }
        }
        val stored = mutableMapOf("database.jdbc-url" to "source")
        val starts = AtomicInteger()
        val migrationStatus = AtomicReference("SUCCEEDED")
        val activeTarget = "sqlite: D:/Documents/Project/long-workspace-directory/database-infrastructure/data/pixiv_download.db"
        val settings = object : DesktopDatabaseSettings {
            override fun providers() = emptyList<DesktopDatabaseSettings.ProviderChoice>()
            override fun prepare(updates: Map<String, String>) = updates
            override fun check(updates: Map<String, String>) = throw AssertionError("Unexpected connection check")
            override fun state() = DesktopDatabaseSettings.State(activeTarget, activeTarget, emptyList(), false)
            override fun migrationState() = DesktopDatabaseSettings.MigrationState(
                migrationStatus.get(),
                "",
                listOf("main", "private:media-library", "private:import-history").map { owner ->
                    DesktopDatabaseSettings.MigrationStore(owner, "previous", "source",
                        if (migrationStatus.get() == "SUCCEEDED" || owner == "main") "COMMITTED" else "UNKNOWN", emptyList())
                },
            )
            override fun migrationGuide() = URI.create("https://example.invalid/guide")
            override fun previewMigration(updates: Map<String, String>) = DesktopDatabaseSettings.MigrationPlan(
                "fixture-plan",
                true,
                listOf("main", "private:media-library", "private:import-history").map { owner ->
                    DesktopDatabaseSettings.MigrationStore(
                        owner,
                        "sqlite: D:/Documents/Project/long-workspace-directory/database-infrastructure/data/$owner/plugin.db",
                        "postgresql: localhost:5432/migration_fixture [owner_0123456789abcdef0123456789abcdef]",
                        "REPLACE_TARGET",
                        listOf("search_index", "cleanup_trigger"),
                    )
                },
                "",
            )
            override fun startMigration(token: String, saveOtherSettings: DesktopUiHost.IoOperation) {
                assertEquals("fixture-plan", token)
                saveOtherSettings.run()
                starts.incrementAndGet()
            }
        }
        val field = GuiConfigFieldContribution(
            "database.jdbc-url",
            GuiConfigGroups.DATABASE,
            "database.jdbc-url",
            "",
            null,
            GuiConfigFieldType.STRING,
            "",
            0,
            false,
            GuiConfigEffect.PROCESS_RESTART,
            emptyList(),
            emptyList(),
            emptyList(),
            null,
            null,
        )
        val model = DesktopConfigurationControllerTest.model(
            stored,
            mapOf<String, Function<Array<Any>, Any?>>(
                "coreConfigGroups" to Function { listOf(GuiConfigGroupContribution(GuiConfigGroups.DATABASE, "Database", 1)) },
                "coreConfigFields" to Function { listOf(field) },
                "validateCoreConfigValue" to Function { null },
                "databaseSettings" to Function { Optional.of(settings) },
                "message" to Function { args -> app.getProperty(args[0] as String, args[0] as String) },
            ),
        )
        var snapshot by mutableStateOf(model.snapshot())
        val subscription = model.subscribeSnapshots { snapshot = it }
        try {
            model.dispatch(snapshot, Event(EventType.CHANGE, "config.app.database.jdbc-url.input", Value.text("target")))
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    PixivDownloaderTheme(theme) {
                        Box(Modifier.fillMaxSize().background(LocalExperiencePalette.current.surface)) {
                            val workspace = descendants(snapshot.document().pages().first { it.id() == "settings" }.content())
                                .filterIsInstance<DesktopUiNode.SettingsWorkspace>().first()
                            SettingsWorkspace(workspace, ::resolve, { model.dispatch(snapshot, it) })
                            snapshot.document().dialogs().forEach { dialog ->
                                DocumentDialog(
                                    dialog = dialog,
                                    text = ::resolve,
                                    closeLabel = "Close dialog",
                                    emit = { model.dispatch(snapshot, it) },
                                    documentRevision = snapshot.revision(),
                                )
                            }
                        }
                    }
                }
            }
            val confirm = app.getProperty("gui.config.database.migration.confirm")
            val cancel = app.getProperty("desktop.ui.action.cancel")
            val workspace = descendants(snapshot.document().pages().first { it.id() == "settings" }.content())
                .filterIsInstance<DesktopUiNode.SettingsWorkspace>().first()
            val databaseTab = workspace.tabs().first { tab -> descendants(tab.content()).any { it.id() == "database.check" } }
            if (width >= 760) onNodeWithTag("settings.category.${databaseTab.id()}").performClick()
            else {
                onNodeWithTag("settings.categories").performClick()
                onNodeWithText(resolve(databaseTab.title())).performClick()
            }
            val history = app.getProperty("gui.config.database.history")
            val inspect = app.getProperty("gui.config.database.inspect")
            val close = app.getProperty("desktop.ui.action.close")
            onNodeWithText(history).assertDoesNotExist()
            onNodeWithText(activeTarget).assertDoesNotExist()
            onNodeWithText(app.getProperty("gui.config.database.notice")).assertDoesNotExist()
            onNodeWithText(app.getProperty("gui.config.database.migration.status.succeeded")).assertDoesNotExist()
            onNodeWithText(app.getProperty("gui.config.database.refresh")).assertDoesNotExist()
            onNodeWithText(app.getProperty("gui.config.database.guide")).assertDoesNotExist()
            onNodeWithText(app.getProperty("gui.config.database.check")).performScrollTo().assertIsDisplayed()
            capture("settings-$width")
            onNodeWithText(inspect).performScrollTo().performClick()
            waitUntil(timeoutMillis = 5000) { !model.busy() && model.snapshot().document().dialogs().isNotEmpty() }
            onNodeWithText(activeTarget).performScrollTo().assertIsDisplayed()
            onNodeWithText(close).assertIsDisplayed()
            onNodeWithText(app.getProperty("gui.config.database.migration.details"), substring = true).assertDoesNotExist()
            onAllNodesWithText(app.getProperty("gui.config.database.migration.phase.committed"), substring = true)
                .assertCountEquals(3)
            if (width >= 760) {
                onNodeWithText(MessageFormat.format(app.getProperty("gui.config.database.migration.plugin"), "import-history"))
                    .assertIsDisplayed()
            }
            capture("details-$width-collapsed")
            onNodeWithTag("database.details.store.main.toggle").performScrollTo().performClick()
            onNodeWithText(MessageFormat.format(app.getProperty("gui.config.database.migration.source"), "previous"))
                .performScrollTo().assertIsDisplayed()
            capture("details-$width-expanded")
            onNodeWithTag("database.details.store.main.toggle")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .performKeyInput { pressKey(Key.Enter) }
            onNodeWithText(MessageFormat.format(app.getProperty("gui.config.database.migration.source"), "previous"))
                .assertDoesNotExist()
            onNodeWithTag("database.details.store.main.toggle").performKeyInput { pressKey(Key.Enter) }
            onNodeWithText(app.getProperty("gui.config.database.refresh")).assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 5000) { !model.busy() }
            onNodeWithText(MessageFormat.format(app.getProperty("gui.config.database.migration.source"), "previous"))
                .performScrollTo().assertIsDisplayed()
            onNodeWithText(close).assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 5000) { model.snapshot().document().dialogs().isEmpty() }
            assertEquals("source", stored["database.jdbc-url"])
            onNodeWithText("target").performScrollTo().assertIsDisplayed()
            repeat(2) { attempt ->
                onNodeWithTag("config.save").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5000) { !model.busy() && model.snapshot().document().dialogs().isNotEmpty() }
                onAllNodesWithText(app.getProperty("gui.config.database.migration.phase.replace-target"), substring = true)
                    .assertCountEquals(3)
                val confirmNode = onNodeWithText(confirm).assertIsDisplayed().assertIsEnabled()
                onNodeWithText(cancel).assertIsDisplayed().assertIsEnabled()
                val before = confirmNode.fetchSemanticsNode().boundsInRoot
                assertTrue(before.top >= 0 && before.bottom <= height && before.left >= 0 && before.right <= width, "$before")
                onNodeWithText("search_index", substring = true).assertDoesNotExist()
                capture("migration-$width-$attempt-collapsed")
                onAllNodesWithText(app.getProperty("gui.config.database.migration.details"), substring = true)
                    .onFirst().performScrollTo().performClick()
                onNodeWithText("search_index", substring = true).performScrollTo().assertIsDisplayed()
                val body = onNode(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
                        .and(hasAnyAncestor(isDialog()))
                        .and(SemanticsMatcher("Content overflows") {
                            it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f
                        }),
                )
                body.performTouchInput { swipeUp() }
                assertTrue(body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0)
                assertEquals(before, confirmNode.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
                capture("migration-$width-$attempt-expanded")
                assertEquals("source", stored["database.jdbc-url"])
                assertEquals(0, starts.get())
                if (attempt == 0) onNodeWithText(cancel).performClick() else confirmNode.performClick()
                waitUntil(timeoutMillis = 5000) { !model.busy() && model.snapshot().document().dialogs().isEmpty() }
                assertEquals(attempt, starts.get())
                onNodeWithText(app.getProperty("gui.config.database.migration.ready")).assertDoesNotExist()
            }
            migrationStatus.set("FAILED")
            onNodeWithText(inspect).performScrollTo().performClick()
            waitUntil(timeoutMillis = 5000) { !model.busy() && model.snapshot().document().dialogs().isNotEmpty() }
            onNodeWithText(app.getProperty("gui.config.database.refresh")).performClick()
            waitUntil(timeoutMillis = 5000) { !model.busy() }
            onAllNodes(
                hasText(app.getProperty("gui.config.database.migration.phase.unknown"))
                    .and(hasAnyAncestor(isDialog())),
            ).onFirst().performScrollTo().assertIsDisplayed()
            capture("details-$width-partial-failure")
            onNodeWithText(close).performClick()
            waitUntil(timeoutMillis = 5000) { model.snapshot().document().dialogs().isEmpty() }
            onNodeWithText(app.getProperty("gui.config.database.migration.status.pending")).assertDoesNotExist()
            onNodeWithText(app.getProperty("gui.config.database.migration.main")).performScrollTo().assertIsDisplayed()
            onAllNodesWithText(app.getProperty("gui.config.database.migration.phase.unknown"))
                .onLast().performScrollTo().assertIsDisplayed()
            capture("settings-$width-partial-failure")
        } finally {
            subscription.close()
            model.close()
        }
    }

}
