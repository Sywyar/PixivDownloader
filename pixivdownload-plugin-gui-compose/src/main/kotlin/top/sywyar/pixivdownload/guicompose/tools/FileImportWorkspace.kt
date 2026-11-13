@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    val presets = buttons.firstOrNull { it.id() == "local-import.presets" }
    val local = tool.id() == "local-import"
    val inputs = nodes.filterIsInstance<TextInput>()
    val templateRequired = inputs.any { it.label().key() == "local-import.templates-required" }
    val templateError = nodes.filterIsInstance<Text>().firstOrNull {
        local && it.id() == "local-import.notice" && it.text().key() == "local-import.error.IMPORT_NO_TEMPLATES"
    }?.text()?.let(text)
    var optionsExpanded by rememberSaveable(tool.id()) { mutableStateOf(false) }
    var scanAttempt by remember(tool.id()) { mutableIntStateOf(0) }
    LaunchedEffect(templateError) { if (templateError != null) optionsExpanded = true }
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
                    buttons.filterNot { it === leave || it === presets }.forEach {
                        ToolAction(it, text, { event ->
                            if (local && it.id() == "local-import.scan") {
                                scanAttempt++
                                if (templateError != null) optionsExpanded = true
                            }
                            emit(event)
                        }, primary = if (local)
                            if (buttons.any { button -> button.id().endsWith(".start") && button.enabled() }) it.id().endsWith(".start")
                            else it.id().endsWith(".scan")
                        else it.id().endsWith(".start"))
                    }
                }
            },
        ) {
            nodes.filterIsInstance<Text>().filter { it.id().endsWith(".help") }.forEach {
                CupertinoText(text(it.text()), fontSize = 13.sp,
                    color = if (it.style() == TextStyle.INFO) palette.link else palette.secondaryText)
            }
            nodes.filterIsInstance<Link>().forEach {
                ComposeDesktopUiNodeRenderer.Render(it, text, emit, Modifier.testTag(it.id()))
            }
            if (local) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets?.let { ToolAction(it, text, emit) }
                    nodes.filterIsInstance<Text>().filter { it.id() in setOf("local-import.saved", "local-import.preset-paths") }.forEach {
                        CupertinoText(text(it.text()), fontSize = 12.sp,
                            color = if (it.style() == TextStyle.WARNING) palette.warning else palette.secondaryText,
                            modifier = Modifier.testTag(it.id()))
                    }
                }
                if (inputs.isNotEmpty()) ToolDisclosure(text(TextToken.key(
                    if (templateRequired) "local-import.options-required" else "local-import.options"
                )), "local-import.options", optionsExpanded) {
                    optionsExpanded = !optionsExpanded
                }
            }
            if (!local || optionsExpanded) Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                inputs.forEach {
                    ToolField(it, text, emit, hintInTitle = true,
                        error = templateError.takeIf { _ -> it.id() == "local-import.templates" },
                        attempt = scanAttempt, focusError = true)
                }
                nodes.filter { it is Choice || it is Toggle }.forEach {
                    ComposeDesktopUiNodeRenderer.Render(it, text, emit, Modifier.fillMaxWidth())
                }
            }
            nodes.filterIsInstance<Text>().filterNot { it.id().endsWith(".help") ||
                    it.id() in setOf("local-import.saved", "local-import.preset-paths") ||
                    (templateError != null && it.id() == "local-import.notice") }.forEach {
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
