@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*

/** 导入弹窗复用工具表单；关闭只清除凭据，任务继续运行。 */
@Composable
internal fun FileImportWorkspace(
    tool: Group,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    back: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodes = descendants(tool).toList()
    val palette = LocalExperiencePalette.current
    val buttons = nodes.filterIsInstance<Button>()
    val leave = buttons.firstOrNull { it.id().endsWith(".leave") }
    val leavePage by rememberUpdatedState { leave?.let { emit(Event(EventType.ACTIVATE, it.id(), Value.empty())) } }
    DisposableEffect(tool.id()) { onDispose { leavePage() } }
    Box(modifier.fillMaxSize().testTag("tools.import.workspace")) {
        ToolPanelLayout(
            title = text(tool.title()),
            description = workspaceText(text, "quick-help"),
            icon = Icons.Default.FileDownload,
            close = back,
            closeLabel = workspaceText(text, "close"),
            tagPrefix = "tools.import",
            footer = {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolButton(
                        workspaceText(text, if (nodes.any { it is Progress }) "keep-running" else "close"),
                        "tools.import.close",
                        onClick = back,
                    )
                    buttons.filterNot { it === leave }.forEach { ToolAction(it, text, emit, primary = it.id().endsWith(".start")) }
                }
            },
        ) {
            nodes.filterIsInstance<Text>().filter { it.id().endsWith(".help") }.forEach {
                CupertinoText(text(it.text()), fontSize = 13.sp, color = palette.secondaryText)
            }
            nodes.filterIsInstance<Link>().forEach {
                ComposeDesktopUiNodeRenderer.Render(it, text, emit, Modifier.testTag(it.id()))
            }
            nodes.filterIsInstance<TextInput>().forEach { ToolField(it, text, emit) }
            nodes.filter { it is Choice || it is Toggle }.forEach {
                ComposeDesktopUiNodeRenderer.Render(it, text, emit, Modifier.fillMaxWidth())
            }
            nodes.filterIsInstance<Text>().filterNot { it.id().endsWith(".help") }.forEach {
                if (it.style() == TextStyle.ERROR) DesktopNotice(text(it.text()), it.style())
                else CupertinoText(
                    text(it.text()),
                    fontSize = if (it.style() == TextStyle.CAPTION) 11.sp else 13.sp,
                    color = palette.secondaryText,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            nodes.filterIsInstance<Progress>().forEach {
                DesktopLinearProgress(it.progress().toFloat().takeUnless { _ -> it.indeterminate() }, Modifier.fillMaxWidth().height(5.dp))
                CupertinoText(text(it.text()), fontSize = 12.sp, color = palette.secondaryText)
            }
        }
    }
}
