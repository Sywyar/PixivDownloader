package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.DocumentDialog
import top.sywyar.pixivdownload.guicompose.DesktopShell
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost
import java.io.File
import java.text.MessageFormat
import java.util.Properties
import java.util.concurrent.LinkedBlockingQueue
import java.util.function.Function
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SymbolicOrphanDialogTest {
    @Test
    @DisplayName("启动下载记录修复弹窗在原报告尺寸及窄矮放大文字下保持操作可见")
    fun startupRepairKeepsActionsVisible() {
        for ((width, height, scale) in listOf(Triple(1120, 634, 1f), Triple(440, 360, 1.5f))) {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                val labels = Properties().apply {
                    File("../pixivdownload-app/src/main/resources/i18n/messages_en.properties")
                        .reader(Charsets.UTF_8).use(::load)
                }
                val requests = LinkedBlockingQueue<Any>()
                fun resolve(token: DesktopUiNode.TextToken): String = MessageFormat.format(
                    labels.getProperty(token.key(), token.fallback()),
                    *token.arguments().toTypedArray(),
                )
                val path = "D:/saved-artworks/" + "long-directory/".repeat(12)
                val calls = mapOf<String, Function<Array<Any>, Any>>(
                    "guiGet" to Function { args ->
                        if (args[0] == "path-prefixes") response(mapOf(
                            "symbolicOrphan" to true, "symbolicOrphanSuggestedPath" to path,
                        )) else DesktopUiHost.GuiResponse.unreachable()
                    },
                    "guiPostJson" to Function { args ->
                        assertEquals("path-prefixes/pin", args[0])
                        requests.add(args[1])
                        response(mapOf("success" to true))
                    },
                )
                val stored = HashMap<String, String>()
                val model = DesktopConfigurationControllerTest.model(stored, calls)
                var snapshot by mutableStateOf(model.snapshot())
                val subscription = model.subscribeSnapshots { snapshot = it }
                try {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                            PixivDownloaderTheme(if (scale == 1f) "light" else "dark") {
                                Box(Modifier.fillMaxSize()) {
                                    DesktopShell(
                                        snapshot.document(), snapshot.revision(), ::resolve,
                                        { model.dispatch(snapshot, it) },
                                    )
                                    snapshot.document().dialogs().firstOrNull()?.let { dialog ->
                                        DocumentDialog(
                                            dialog = dialog,
                                            text = ::resolve,
                                            closeLabel = "Close",
                                            emit = { model.dispatch(snapshot, it) },
                                            documentRevision = snapshot.revision(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    waitUntil(timeoutMillis = 8000) {
                        snapshot.document().dialogs().firstOrNull()?.id() == "symbolic-orphan"
                    }
                    waitForIdle()
                    System.getenv("PIXIV_DIALOG_SCREENSHOTS")?.let { directory ->
                        val file = File(directory, "startup-repair-$width.png")
                        file.parentFile.mkdirs()
                        ImageIO.write(captureToImage().toAwtImage(), "png", file)
                    }
                    val confirm = onNodeWithText(labels.getProperty("desktop.ui.action.confirm"))
                    val cancel = onNodeWithText(labels.getProperty("desktop.ui.action.cancel"))
                    confirm.assertIsDisplayed()
                    cancel.assertIsDisplayed()
                    cancel.performKeyInput { pressKey(Key.Escape) }
                    confirm.assertIsDisplayed()
                    cancel.performClick()
                    onNodeWithText(labels.getProperty("desktop.ui.action.no")).assertIsDisplayed().performClick()
                    waitForIdle()
                    onNodeWithContentDescription(labels.getProperty("gui.migrate-dir.column.original"))
                        .assertTextEquals(path)
                    val before = confirm.fetchSemanticsNode().boundsInRoot
                    onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
                        and hasAnyDescendant(hasSetTextAction()))
                        .performTouchInput { swipeUp() }
                    assertEquals(before, confirm.fetchSemanticsNode().boundsInRoot)
                    assertTrue(requests.isEmpty())
                    confirm.performClick()
                    waitUntil(timeoutMillis = 5000) { requests.isNotEmpty() }
                    assertEquals(mapOf("path" to path), requests.single())
                    assertTrue(stored.isEmpty())
                } finally {
                    subscription.close()
                    model.close()
                }
            }
        }
    }

    private fun response(values: Map<String, Any>) = DesktopUiHost.GuiResponse(
        true, 200, DesktopUiHost.GuiValue.of(values), "", false,
    )
}
