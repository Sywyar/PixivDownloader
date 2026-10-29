@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*

/** 导入页面使用现有工具表单与固定底部动作，返回不停止后台任务。 */
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
    val leave = buttons.first { it.id().endsWith(".leave") }
    val close = {
        emit(Event(EventType.ACTIVATE, leave.id(), Value.empty()))
        back()
    }
    val leavePage by rememberUpdatedState { emit(Event(EventType.ACTIVATE, leave.id(), Value.empty())) }
    DisposableEffect(tool.id()) { onDispose { leavePage() } }
    Box(modifier.fillMaxSize().testTag("tools.import.workspace")) {
        ToolPanelLayout(
            title = text(tool.title()),
            description = "",
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            close = close,
            closeLabel = text(leave.label()),
            tagPrefix = "tools.import",
            footer = {
                buttons.filterNot { it === leave }.forEach { ToolAction(it, text, emit, primary = it.id().endsWith(".start")) }
            },
        ) {
            nodes.filterIsInstance<TextInput>().forEach { ToolField(it, text, emit) }
            nodes.filterIsInstance<Text>().forEach {
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
