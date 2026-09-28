@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import top.sywyar.pixivdownload.guicompose.model.ToolInputValidation

private val toolIds = listOf("classifier", "folder", "backfill", "migration")
private val toolIcons = listOf(Icons.Default.Collections, Icons.Default.FolderOpen, Icons.Default.Storage, Icons.Default.SyncAlt)

@Composable
internal fun ToolsOverview(
    node: DesktopUiNode.ToolsOverview,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalExperiencePalette.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreFocus by remember { mutableStateOf<String?>(null) }
    val anchors = remember { mutableMapOf<String, FocusRequester>() }
    val tools = node.tools().map { it as Group }
    val mediaTools = node.mediaTools().filterIsInstance<Group>()
    fun label(key: String) = workspaceText(text, key)
    fun select(id: String) {
        restoreFocus = id
        if (id == "classifier" || id == "folder") {
            val button = descendants(tools[toolIds.indexOf(id)]).filterIsInstance<Button>().first()
            if (button.enabled()) emit(Event(EventType.ACTIVATE, button.id(), Value.empty()))
        } else selected = id
    }
    fun activate(button: Button) {
        if (button.enabled()) emit(Event(EventType.ACTIVATE, button.id(), Value.empty()))
    }
    val activity = node.activity()
    val mediaProgress = descendants(node.media()).filterIsInstance<Progress>().firstOrNull()
        ?: mediaTools.asSequence().flatMap(::descendants).filterIsInstance<Progress>().firstOrNull { it.id().endsWith(".progress") }
    if (selected == "media") {
        FfmpegToolsWorkspace(node.media(), mediaTools, text, emit, { selected = null }, modifier)
    } else {
        BoxWithConstraints(modifier.fillMaxSize().testTag("tools.overview")) {
            val wide = maxWidth >= 760.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val inset = if (maxWidth < 600.dp) 24.dp else 44.dp
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = inset, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                ) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CupertinoText(text(TextToken.key("desktop.ui.page.tools")), Modifier.semantics { heading() },
                                fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            CupertinoText(label("subtitle"), fontSize = 13.sp, color = palette.secondaryText)
                        }
                        CupertinoText(text(node.backend().text()), fontSize = 12.sp, color = palette.secondaryText)
                    }
                    AnimatedVisibility(activity != null || mediaProgress != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        val id = if (mediaProgress != null) "media" else activity?.toolId().orEmpty()
                        ToolRow(
                            if (id == "media") label("media-title") else label("$id.title"),
                            if (id == "media") mediaProgress?.text()?.let(text).orEmpty() else activity?.message()?.let(text).orEmpty(),
                            label("show-task"), Icons.Default.Schedule, "tools.current", Modifier.fillMaxWidth(),
                        ) { select(id) }
                    }
                    @Composable fun category(indices: IntRange, title: String, modifier: Modifier = Modifier) {
                        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CupertinoText(title, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = palette.secondaryText, modifier = Modifier.semantics { heading() })
                            indices.forEach { index ->
                                val id = toolIds[index]
                                val focus = anchors.getOrPut(id) { FocusRequester() }
                                ToolRow(label("$id.title"), label("$id.description"), label("$id.impact"), toolIcons[index],
                                    "tools.entry.$id", Modifier.fillMaxWidth().focusRequester(focus)) { select(id) }
                            }
                        }
                    }
                    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        category(0..1, label("organize"), Modifier.weight(1f))
                        category(2..3, label("maintain"), Modifier.weight(1f))
                    } else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        category(0..1, label("organize"))
                        category(2..3, label("maintain"))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CupertinoText(label("media-group"), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = palette.secondaryText, modifier = Modifier.semantics { heading() })
                        val state = descendants(node.media()).filterIsInstance<Text>().firstOrNull { it.id() == "status.ffmpeg.state" }
                        ToolRow(label("media-title"), label("media-description"), mediaProgress?.text()?.let(text) ?: state?.text()?.let(text).orEmpty(),
                            Icons.Default.Movie, "tools.entry.media",
                            Modifier.fillMaxWidth().background(palette.secondarySurface.copy(alpha = .6f), RoundedCornerShape(16.dp))
                                .focusRequester(anchors.getOrPut("media") { FocusRequester() })) { select("media") }
                    }
                    val rows = (node.history() as? Table)?.rows().orEmpty()
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            CupertinoText(label("history"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                            if (rows.isNotEmpty()) ToolButton(label("history-all"), "tools.history.all") { select("history") }
                        }
                        if (rows.isEmpty()) CupertinoText(text(TextToken.key("gui.tools.history.empty")), fontSize = 13.sp, color = palette.secondaryText)
                        else rows.take(2).forEach { row ->
                            HistoryRow(row, "tools.history.${row.id()}") { select(row.id()) }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
    LaunchedEffect(selected) {
        if (selected == null && restoreFocus == "media") anchors["media"]?.requestFocus()
    }
    var retainedWorkspace by remember { mutableStateOf<ToolWorkspace?>(null) }
    node.workspace()?.let { retainedWorkspace = it }
    val workspace = node.workspace()
    val current = workspace?.toolId() ?: selected?.takeUnless { it == "media" }
    val close = {
        if (workspace != null) activate(workspace.close()) else selected = null
    }
    ToolsSheet(current, onDismiss = close, onClosed = {
        retainedWorkspace = null
        restoreFocus?.let { anchors[it]?.requestFocus() }
    }) { id ->
        when {
            id == "classifier" || id == "folder" -> retainedWorkspace?.let {
                ToolWorkspacePanel(it, text, emit, close)
            }
            id == "history" || id.startsWith("history.") -> ToolPanelLayout(
                label("history"), "", Icons.Default.History, close, label("close"),
                footer = { ToolButton(label("done"), "tools.history.done", primary = true, onClick = close) },
            ) { HistoryPanel(node.history(), id, text) { select(it) } }
            else -> {
                val index = toolIds.indexOf(id)
                ToolPanel(id,
                    label("$id.title"),
                    label("$id.description"),
                    toolIcons[index],
                    tools[index],
                    activity?.takeIf { it.toolId() == id }, node.backend(), text, emit, close)
            }
        }
    }
}

internal fun workspaceText(text: (TextToken) -> String, key: String): String =
    text(TextToken("gui-compose", "gui.compose.tools.workspace.$key", "", emptyList()))

internal fun descendants(node: DesktopUiNode): Sequence<DesktopUiNode> =
    sequenceOf(node) + node.childNodes().asSequence().flatMap(::descendants)

@Composable
private fun ToolRow(title: String, description: String, impact: String, icon: ImageVector, tag: String,
                    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalExperiencePalette.current
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scale by animateFloatAsState(if (pressed) .985f else 1f, spring(stiffness = 850f), label = "tool-press")
    val background by animateColorAsState(if (hover || pressed) palette.secondarySurface.copy(alpha = .65f) else Color.Transparent, tween(140))
    Row(modifier.testTag(tag).graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(14.dp)).background(background)
        .border(if (focused && keyboard) 2.dp else 0.dp, if (focused && keyboard) palette.link else Color.Transparent, RoundedCornerShape(14.dp))
        .pointerHoverIcon(PointerIcon.Hand).clickable(interaction, null, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            CupertinoText(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            CupertinoText(description, fontSize = 12.sp, color = palette.secondaryText)
            if (impact.isNotBlank()) CupertinoText(impact, fontSize = 11.sp, color = palette.secondaryText)
        }
        DesktopIcon(Icons.Default.ChevronRight, null, Modifier.size(17.dp), tint = palette.secondaryText)
    }
}

@Composable
internal fun ToolIcon(icon: ImageVector) {
    val palette = LocalExperiencePalette.current
    Box(Modifier.size(44.dp).background(palette.selection.copy(alpha = .65f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        DesktopIcon(icon, null, tint = palette.link)
    }
}

@Composable
internal fun ToolButton(label: String, tag: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalExperiencePalette.current
    CupertinoButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag).heightIn(min = 36.dp),
        colors = if (primary) CupertinoButtonDefaults.filledButtonColors(containerColor = palette.link, contentColor = palette.onAccent)
            else CupertinoButtonDefaults.grayButtonColors(containerColor = palette.secondarySurface, contentColor = palette.link),
        shape = RoundedCornerShape(9.dp)) { CupertinoText(label, fontSize = 13.sp) }
}

@Composable
private fun ToolsSheet(
    selected: String?,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    content: @Composable (String) -> Unit,
) {
    val visible = remember { MutableTransitionState(false) }
    var retained by remember { mutableStateOf<String?>(null) }
    if (selected != null) retained = selected
    LaunchedEffect(selected) { visible.targetState = selected != null }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && retained != null) { onClosed(); retained = null }
    }
    if (visible.currentState || visible.targetState) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
            usePlatformDefaultWidth = false, usePlatformInsets = false,
            scrimColor = Color.Black.copy(alpha = .18f),
        )) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                val availableHeight = maxHeight
                AnimatedVisibility(visible,
                    enter = fadeIn(tween(180)) + scaleIn(spring(dampingRatio = 1f, stiffness = 500f), initialScale = .97f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(180), targetScale = .98f)) {
                    val palette = LocalExperiencePalette.current
                    val width = when (retained) { "classifier" -> 1000.dp; "folder" -> 800.dp; else -> 580.dp }
                    Box(Modifier.widthIn(max = width).fillMaxWidth().heightIn(max = availableHeight)
                        .background(palette.surface, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
                        .testTag("tools.sheet").animateContentSize(spring(dampingRatio = 1f, stiffness = 550f))) {
                        AnimatedContent(retained,
                            transitionSpec = { fadeIn(tween(180, 40)) togetherWith fadeOut(tween(100)) },
                            label = "tool-detail") { current -> current?.let { content(it) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(row: TableRow, tag: String, onClick: () -> Unit) {
    val palette = LocalExperiencePalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
        .testTag(tag).padding(vertical = 13.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        DesktopIcon(Icons.Default.History, null, tint = palette.secondaryText)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CupertinoText(row.cells()[0], fontSize = 13.sp, fontWeight = FontWeight.Medium)
            CupertinoText(row.cells()[1], fontSize = 12.sp, color = palette.secondaryText)
        }
        CupertinoText(row.cells()[3], fontSize = 11.sp, color = palette.secondaryText)
    }
}

@Composable
private fun HistoryPanel(history: DesktopUiNode, id: String, text: (TextToken) -> String, select: (String) -> Unit) {
    val palette = LocalExperiencePalette.current
    val table = history as? Table
    val row = table?.rows()?.find { it.id() == id }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (row != null) {
            table.columns().forEachIndexed { index, column ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    CupertinoText(text(column.label()), fontSize = 12.sp, color = palette.secondaryText)
                    SelectionContainer { CupertinoText(row.cells()[index], fontSize = 14.sp) }
                }
            }
            ToolButton(workspaceText(text, "history-all"), "tools.history.back") { select("history") }
        } else table?.rows()?.forEach { entry -> HistoryRow(entry, "tools.history.detail.${entry.id()}") { select(entry.id()) } }
    }
}
