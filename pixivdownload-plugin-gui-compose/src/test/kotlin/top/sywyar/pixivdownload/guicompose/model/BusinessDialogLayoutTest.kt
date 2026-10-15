package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.DocumentDialog
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost
import java.io.File
import java.util.Properties
import java.util.function.Function
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BusinessDialogLayoutTest {
    @Test
    @DisplayName("受信密钥、分类设置、目录迁移和长消息弹窗的操作不随正文滚出视口")
    fun realDialogBodiesKeepActionsVisible() {
        val labels = Properties().apply {
            File("../pixivdownload-app/src/main/resources/i18n/messages_en.properties")
                .reader(Charsets.UTF_8).use(::load)
        }
        for ((width, height) in listOf(900 to 400, 440 to 520)) {
            for (kind in listOf("trusted-key", "classifier", "directory", "message")) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                    val calls = HashMap(RepositoryImportInteractionTest.defaults())
                    calls["guiGet"] = Function { args ->
                        if (args[0] == "path-prefixes") DesktopUiHost.GuiResponse(
                            true, 200, DesktopUiHost.GuiValue.of(mapOf(
                                "prefixes" to (1..8).map { mapOf("id" to it, "path" to "D:/" + "directory/".repeat(20) + it) },
                            )), "", false,
                        ) else DesktopUiHost.GuiResponse.unreachable()
                    }
                    val stored = HashMap<String, String>()
                    val model = DesktopConfigurationControllerTest.model(stored, calls)
                    DesktopToolsControllerTest.awaitMediaReady(model)
                    fun activate(id: String) = model.dispatch(model.snapshot(), DesktopUiNode.Event(
                        DesktopUiNode.EventType.ACTIVATE, id, DesktopUiNode.Value.empty(),
                    ))
                    val actionKeys = when (kind) {
                        "trusted-key" -> {
                            activate("config.market.repository.add")
                            activate("config.market.repository.trusted.add")
                            "gui.config.market.repo.dialog.ok" to "gui.config.market.repo.dialog.cancel"
                        }
                        "classifier" -> {
                            activate("tools.image-classifier.open")
                            activate("classifier.settings")
                            "gui.button.save" to "desktop.ui.action.cancel"
                        }
                        "directory" -> {
                            model.openDirectoryMigration()
                            "desktop.ui.action.apply" to "desktop.ui.action.cancel"
                        }
                        else -> {
                            model.showDialog("notice", "gui.dialog.info.title",
                                DesktopUiNode.TextToken.raw("A long diagnostic line\n".repeat(40)),
                                DesktopUiDocument.DialogStyle.INFO)
                            model.rebuild()
                            "desktop.ui.action.close" to "desktop.ui.action.close"
                        }
                    }
                    var snapshot by mutableStateOf(model.snapshot())
                    val subscription = model.subscribeSnapshots { snapshot = it }
                    try {
                        setContent {
                            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                                PixivDownloaderTheme(if (width == 900) "light" else "dark") {
                                    Box(Modifier.fillMaxSize()) {
                                        snapshot.document().dialogs().firstOrNull()?.let { dialog ->
                                            DocumentDialog(
                                                dialog = dialog,
                                                text = { labels.getProperty(it.key(), it.fallback()) },
                                                closeLabel = "Close dialog",
                                                emit = { model.dispatch(snapshot, it) },
                                                documentRevision = snapshot.revision(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        waitUntil(conditionDescription = "打开 $kind 弹窗", timeoutMillis = 5000) {
                            snapshot.document().dialogs().isNotEmpty() && !model.busy()
                        }
                        val primary = onNodeWithText(labels.getProperty(actionKeys.first))
                        primary.assertIsDisplayed()
                        val before = primary.fetchSemanticsNode().boundsInRoot
                        val body = onNode(SemanticsMatcher("正文可滚动") {
                            (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue() ?: 0f) > 0f
                        })
                        body.performTouchInput { swipeUp() }
                        assertEquals(before, primary.fetchSemanticsNode().boundsInRoot)
                        onNodeWithText(labels.getProperty(actionKeys.second)).assertIsDisplayed().performClick()
                        waitForIdle()
                        assertTrue(stored.isEmpty())
                        assertTrue(snapshot.document().dialogs().none { it.id() == when (kind) {
                            "trusted-key" -> "config.market.trusted-key.dialog"
                            "classifier" -> "classifier.settings.dialog"
                            "directory" -> "migrate.directory"
                            else -> "notice"
                        } })
                    } finally {
                        subscription.close()
                        model.close()
                    }
                }
            }
        }
    }
}
