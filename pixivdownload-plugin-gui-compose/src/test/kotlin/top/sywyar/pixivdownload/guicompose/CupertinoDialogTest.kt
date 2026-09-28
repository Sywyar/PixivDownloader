package top.sywyar.pixivdownload.guicompose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import io.github.robinpcrd.cupertino.CupertinoAlertDialog
import io.github.robinpcrd.cupertino.CupertinoButton
import io.github.robinpcrd.cupertino.CupertinoText
import io.github.robinpcrd.cupertino.ExperimentalCupertinoApi
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.awt.Window
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCupertinoApi::class)
@DisplayName("Cupertino 提示框运行契约")
class CupertinoDialogTest {
    @Test
    @DisplayName("桌面通知按阅读宽度显示，低矮窗口内长正文可滚动且确认始终可达")
    fun desktopMessageFitsViewportAndKeepsConfirmationVisible() {
        for ((width, height) in listOf(1000 to 720, 440 to 320)) runSkikoComposeUiTest(
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            var open by mutableStateOf(true)
            var message by mutableStateOf(List(40) { "Unmanaged table: example_table_$it" }.joinToString("\n"))
            setContent {
                PixivDownloaderTheme(if (width == 1000) "light" else "dark") {
                    Box(Modifier.size(width.dp, height.dp)) {
                        if (open) DesktopMessageDialog(
                            title = "Database structure",
                            message = message,
                            confirmLabel = "OK",
                            onDismiss = { open = false },
                        )
                    }
                }
            }
            val dialog = onNodeWithTag("desktop.message").fetchSemanticsNode().boundsInRoot
            assertTrue(dialog.width <= width && dialog.height <= height, "dialog=$dialog, window=$width x $height")
            if (width == 1000) assertTrue(dialog.width >= 560, "桌面通知应有可读宽度")
            val body = onNodeWithTag("desktop.message.body")
            body.performTouchInput { swipeUp() }
            assertTrue(body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0)
            System.getenv("PIXIV_DIALOG_SCREENSHOTS")?.let { directory ->
                val file = File(directory, "message-$width.png")
                file.parentFile.mkdirs()
                ImageIO.write(captureToImage().toAwtImage(), "png", file)
            }
            onNodeWithText("OK").assertIsDisplayed().performClick()
            onNodeWithText("Database structure").assertDoesNotExist()
            runOnIdle { message = "Ready"; open = true }
            if (width == 1000) assertTrue(onNodeWithTag("desktop.message").fetchSemanticsNode().boundsInRoot.height < height / 2)
            onNodeWithText("OK").performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("Database structure").assertDoesNotExist()
        }
    }

    @Test
    @DisplayName("业务弹窗在模态层显示，保留确认动作，关闭按钮和 Escape 发出取消事件")
    fun documentDialogKeepsActionsAndDismissalInsideTheWindow() = runComposeUiTest {
        var open by mutableStateOf(false)
        val events = mutableListOf<DesktopUiNode.Event>()
        setContent {
            PixivDownloaderTheme("dark") {
                CupertinoButton(onClick = { open = true }) { CupertinoText("Open") }
                if (open) DocumentDialog(
                    dialog = documentDialog(dismissible = true),
                    text = { it.fallback() },
                    closeLabel = "Close dialog",
                    emit = { events.add(it); open = false },
                    documentRevision = 1L,
                )
            }
        }
        onNodeWithText("Open").performClick()
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertExists()
        onNodeWithText("Confirm changes").assertExists()
        runOnIdle {
            assertTrue(Window.getWindows().filterIsInstance<java.awt.Dialog>()
                .none { it.isShowing && !it.isUndecorated }, "业务确认框不得产生系统标题栏")
        }
        onNodeWithText("Confirm").performClick()
        assertEquals("confirm", events.last().nodeId())
        onNodeWithText("Open").performClick()
        onNodeWithContentDescription("Close dialog").performClick()
        assertEquals("confirmation", events.last().nodeId())
        onNodeWithText("Open").performClick()
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).performMouseInput {
            click(Offset(2f, 2f))
        }
        onNodeWithText("Confirm changes").assertExists()
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).performKeyInput {
            pressKey(Key.Escape)
        }
        onNodeWithText("Confirm changes").assertDoesNotExist()
        assertEquals(listOf("confirm", "confirmation", "confirmation"), events.map { it.nodeId() })
    }

    @Test
    @DisplayName("不可取消的业务弹窗忽略 Escape 且不显示关闭按钮，仍允许显式操作")
    fun documentDialogRespectsNonDismissibleState() = runComposeUiTest {
        var open by mutableStateOf(true)
        val events = mutableListOf<DesktopUiNode.Event>()
        setContent {
            PixivDownloaderTheme("light") {
                if (open) DocumentDialog(
                    dialog = documentDialog(dismissible = false),
                    text = { it.fallback() },
                    closeLabel = "Close dialog",
                    emit = { events.add(it); open = false },
                    documentRevision = 1L,
                )
            }
        }
        onNodeWithContentDescription("Close dialog").assertDoesNotExist()
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).performKeyInput {
            pressKey(Key.Escape)
        }
        onNodeWithText("Confirm changes").assertExists()
        assertTrue(events.isEmpty())
        onNodeWithText("Confirm").performClick()
        assertEquals("confirm", events.single().nodeId())
    }

    private fun documentDialog(dismissible: Boolean) = DesktopUiDocument.Dialog(
        "confirmation",
        DesktopUiNode.TextToken.raw("Confirm changes"),
        DesktopUiDocument.DialogStyle.QUESTION,
        DesktopUiNode.Button(
            "confirm", "confirm", DesktopUiNode.TextToken.raw("Confirm"), null,
            DesktopUiNode.ButtonStyle.PRIMARY, true,
        ),
        "dismiss",
        dismissible,
        480,
        300,
        false,
    )

    @Test
    @DisplayName("深色主题中的提示框可显示中文正文并由确认动作关闭")
    fun opensAndDismissesAlert() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent {
            PixivDownloaderTheme("dark") {
                CupertinoButton(onClick = { open = true }) { CupertinoText("显示提示") }
                if (open) CupertinoAlertDialog(
                    onDismissRequest = { open = false },
                    title = { CupertinoText("操作提示") },
                    message = { CupertinoText("中文正文与 keyboard input") },
                    buttons = { action(onClick = { open = false }, title = { CupertinoText("确认") }) },
                )
            }
        }
        onNodeWithText("显示提示").performClick()
        onNodeWithText("中文正文与 keyboard input").assertExists()
        onNodeWithText("确认").performClick()
        waitForIdle()
        onNodeWithText("操作提示").assertDoesNotExist()
    }
}
