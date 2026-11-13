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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*

private val toolIds = listOf("classifier", "folder", "backfill", "migration", "startup")
private val toolIcons = listOf(Icons.Default.Collections, Icons.Default.FolderOpen, Icons.Default.Storage, Icons.Default.SyncAlt, Icons.Default.Speed)

internal val LocalOpenBackfill = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalRegisterToolsExit = staticCompositionLocalOf<(((() -> Boolean)?) -> Unit)> { {} }

@Composable
internal fun ToolsOverview(
    node: DesktopUiNode.ToolsOverview,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalExperiencePalette.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var mediaSection by rememberSaveable { mutableStateOf<String?>(null) }
    var openTaskResult by rememberSaveable { mutableStateOf(false) }
    val openBackfill = LocalOpenBackfill.current
    LaunchedEffect(openBackfill) {
        if (openBackfill != null) {
            openTaskResult = true
            selected = "backfill"
            openBackfill()
        }
    }
    var restoreFocus by remember { mutableStateOf<String?>(null) }
    val anchors = remember { mutableMapOf<String, FocusRequester>() }
    val tools = node.tools().map { it as Group }
    val mediaTools = node.mediaTools().filterIsInstance<Group>()
    val fileImports = node.fileImports().filterIsInstance<Group>()
    val selectedImport = fileImports.firstOrNull { it.id() == selected }
    LaunchedEffect(selected, fileImports.map { it.id() }) {
        anchors.keys.removeAll { id -> id !in toolIds && id != "media" && fileImports.none { it.id() == id } }
        if (selected != null && selected !in toolIds && selected != "media" &&
            selected != "history" && selected?.startsWith("history.") != true && selectedImport == null) {
            restoreFocus = "backfill"
            selected = null
        }
    }
    fun label(key: String) = workspaceText(text, key)
    fun select(id: String, section: String? = null) {
        restoreFocus = id
        openTaskResult = false
        if (id == "media") mediaSection = section
        if (id == "classifier" || id == "folder") {
            val button = descendants(tools[toolIds.indexOf(id)]).filterIsInstance<Button>().first()
            if (button.enabled()) emit(Event(EventType.ACTIVATE, button.id(), Value.empty()))
        } else selected = id
    }
    fun activate(button: Button) {
        if (button.enabled()) emit(Event(EventType.ACTIVATE, button.id(), Value.empty()))
    }
    val activity = node.activity()
    val runningActivity = activity?.takeIf { it.running() }
    val activeMedia = activeToolProgress(sequenceOf(node.media()) + mediaTools.asSequence()).toList()
    val activeImports = activeToolProgress(fileImports.asSequence()).toList()
    val activeStartup = activeToolProgress(tools.asSequence().filter { it.id() == "tools.startup" }).toList()
    val mediaProgress = activeMedia.firstOrNull()?.second
    val workspace = node.workspace()
    fun leaveUtility(): Boolean {
        if (workspace != null) {
            if (!workspace.close().enabled()) return false
            activate(workspace.close())
        }
        if (selected == "media") {
            descendants(node.media()).filterIsInstance<Button>().firstOrNull { it.id() == "status.ffmpeg.leave" }?.let(::activate)
        }
        selected = null
        return true
    }
    val exit by rememberUpdatedState { leaveUtility() }
    val registerExit = LocalRegisterToolsExit.current
    DisposableEffect(registerExit) {
        registerExit { exit() }
        onDispose { registerExit(null) }
    }
    if (workspace != null) {
        Box(modifier.fillMaxSize().testTag("tools.utility.workspace")) {
            ToolWorkspacePanel(workspace, text, emit, { activate(workspace.close()) })
        }
    } else if (selected == "media") {
        FfmpegToolsWorkspace(node.media(), mediaTools, text, emit, { leaveUtility() }, modifier, mediaSection)
    } else {
        BoxWithConstraints(modifier.fillMaxSize().testTag("tools.overview")) {
            val wide = maxWidth >= 760.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val inset = if (maxWidth < 600.dp) 24.dp else 44.dp
            val itemWidth = if (wide) (maxWidth - inset * 2 - 20.dp) / 2 else maxWidth - inset * 2
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = inset, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        CupertinoText(
                            text(TextToken.key("desktop.ui.page.tools")),
                            Modifier.weight(1f).semantics { heading() },
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        CupertinoText(text(node.backend().text()), fontSize = 12.sp, color = palette.secondaryText)
                    }
                    if (runningActivity != null || activeMedia.isNotEmpty() || activeImports.isNotEmpty() || activeStartup.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            activeStartup.forEach { (tool, progress) ->
                                ToolRow(text(tool.title()), text(progress.text()), label("show-task"), Icons.Default.Speed,
                                    "tools.current.startup", Modifier.fillMaxWidth(), running = true) { select("startup") }
                            }
                            runningActivity?.let { current ->
                                ToolRow(
                                    title = label("${current.toolId()}.title"),
                                    description = text(current.message()),
                                    impact = label("show-task"),
                                    icon = Icons.Default.Schedule,
                                    tag = "tools.current",
                                    modifier = Modifier.fillMaxWidth(),
                                    running = true,
                                ) { select(current.toolId()) }
                            }
                            activeMedia.forEach { (tool, progress) ->
                                key(tool.id()) {
                                    ToolRow(
                                        title = if (tool === node.media()) label("media-title") else text(tool.title()),
                                        description = text(progress.text()),
                                        impact = label("show-task"),
                                        icon = Icons.Default.Movie,
                                        tag = "tools.current.${tool.id()}",
                                        modifier = Modifier.fillMaxWidth(),
                                        running = true,
                                    ) { select("media", if (tool === node.media()) "ffmpeg" else tool.id()) }
                                }
                            }
                            activeImports.forEach { (tool, progress) ->
                                key(tool.id()) {
                                    ToolRow(
                                        title = text(tool.title()),
                                        description = text(progress.text()),
                                        impact = label("show-task"),
                                        icon = Icons.Default.FileDownload,
                                        tag = "tools.current.${tool.id()}",
                                        modifier = Modifier.fillMaxWidth(),
                                        running = true,
                                    ) { select(tool.id()) }
                                }
                            }
                        }
                    }
                    @Composable fun category(quick: Boolean) {
                        val indices = if (quick) listOf(2, 4).filter { it < tools.size } else listOf(0, 1)
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CupertinoText(
                                label(if (quick) "quick" else "utilities"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.secondaryText,
                                modifier = Modifier.semantics { heading() },
                            )
                            FlowRow(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                maxItemsInEachRow = if (wide) 2 else 1,
                            ) {
                                indices.forEach { index ->
                                    val id = toolIds[index]
                                    val focus = anchors.getOrPut(id) { FocusRequester() }
                                    ToolRow(
                                        if (id == "startup") text(tools[index].title()) else label("$id.title"),
                                        if (id == "startup") text(TextToken.key("gui.tools.startup-cache.description")) else label("$id.description"),
                                        when (id) {
                                            "startup" -> text(TextToken.key("gui.tools.startup-cache.impact"))
                                            "classifier" -> ""
                                            else -> label("$id.impact")
                                        },
                                        toolIcons[index],
                                        "tools.entry.$id",
                                        Modifier.width(itemWidth).focusRequester(focus),
                                    ) { select(id) }
                                }
                                if (quick) fileImports.forEach { tool ->
                                    key(tool.id()) {
                                        val help = descendants(tool).filterIsInstance<Text>().firstOrNull { it.id().endsWith(".help") }
                                        ToolRow(
                                            title = text(tool.title()),
                                            description = help?.text()?.let(text).orEmpty(),
                                            impact = "",
                                            icon = Icons.Default.FileDownload,
                                            tag = "tools.entry.${tool.id()}",
                                            modifier = Modifier.width(itemWidth)
                                                .focusRequester(anchors.getOrPut(tool.id()) { FocusRequester() }),
                                        ) { select(tool.id()) }
                                    }
                                }
                                if (!quick) {
                                    val state = descendants(node.media()).filterIsInstance<Text>().firstOrNull { it.id() == "status.ffmpeg.state" }
                                    ToolRow(
                                        label("media-title"),
                                        label("media-description"),
                                        mediaProgress?.text()?.let(text) ?: state?.text()?.let(text).orEmpty(),
                                        Icons.Default.Movie,
                                        "tools.entry.media",
                                        Modifier.width(itemWidth).focusRequester(anchors.getOrPut("media") { FocusRequester() }),
                                    ) { select("media") }
                                }
                            }
                        }
                    }
                    category(true)
                    category(false)
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
    LaunchedEffect(selected, workspace?.toolId()) {
        if (selected == null && workspace == null) restoreFocus?.let { anchors[it]?.requestFocus() }
    }
    val current = selected?.takeIf {
        it == selectedImport?.id() || it in toolIds || it == "history" || it.startsWith("history.")
    }
    val close = { selected = null }
    ToolsSheet(current, onDismiss = close, onClosed = {
        restoreFocus?.let { anchors[it]?.requestFocus() }
    }) { id ->
        val importTool = fileImports.firstOrNull { it.id() == id }
        when {
            importTool != null -> FileImportWorkspace(importTool, text, emit, close)
            id == "startup" -> StartupCachePanel(tools[toolIds.indexOf(id)], text, emit, close)
            id == "history" || id.startsWith("history.") -> ToolPanelLayout(
                label("history"), "", Icons.Default.History, close, label("close"),
                footer = { ToolButton(label("done"), "tools.history.done", primary = true, onClick = close) },
            ) { HistoryPanel(node.history(), id, text) { select(it) } }
            id in toolIds -> {
                val index = toolIds.indexOf(id)
                ToolPanel(id,
                    label("$id.title"),
                    label("$id.description"),
                    toolIcons[index],
                    tools[index],
                    activity?.takeIf { it.toolId() == id }, node.backend(), text, emit, close, openTaskResult)
            }
        }
    }
}

private fun activeToolProgress(nodes: Sequence<DesktopUiNode>) =
    nodes.filterIsInstance<Group>().mapNotNull { tool ->
        descendants(tool).filterIsInstance<Progress>().firstOrNull()?.let { tool to it }
    }

internal fun DesktopUiNode.ToolsOverview.runningTaskCount(): Int =
    (if (activity()?.running() == true) 1 else 0) +
        activeToolProgress(sequenceOf(media()) + mediaTools().asSequence() + fileImports().asSequence()
            + tools().asSequence().filter { it.id() == "tools.startup" }).count()

internal fun workspaceText(text: (TextToken) -> String, key: String): String =
    text(TextToken("gui-compose", "gui.compose.tools.workspace.$key", "", emptyList()))

internal fun descendants(node: DesktopUiNode): Sequence<DesktopUiNode> =
    sequenceOf(node) + node.childNodes().asSequence().flatMap(::descendants)

@Composable
private fun ToolRow(title: String, description: String, impact: String, icon: ImageVector, tag: String,
                    modifier: Modifier = Modifier, running: Boolean = false, onClick: () -> Unit) {
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
        .toolRunningBorder(running)
        .semantics {
            if (running) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }
        .pointerHoverIcon(PointerIcon.Hand).clickable(interaction, null, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CupertinoText(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (description.isNotBlank()) ComposeDesktopUiNodeRenderer.HintedTitle(description) {
                CupertinoText(
                    description,
                    fontSize = if (running) 12.sp else 11.sp,
                    lineHeight = 16.sp,
                    color = palette.secondaryText,
                    maxLines = if (running) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                    val width = if (retained in toolIds || retained == "history" ||
                        retained?.startsWith("history.") == true) 580.dp else 720.dp
                    Box(Modifier.widthIn(max = width).fillMaxWidth().heightIn(max = availableHeight)
                        .background(palette.surface, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
                        .testTag("tools.sheet").animateContentSize(spring(dampingRatio = 1f, stiffness = 550f))) {
                        AnimatedContent(retained,
                            transitionSpec = { fadeIn(tween(180, 40)) togetherWith fadeOut(tween(100)) },
                            label = "tool-detail") { current ->
                            GuardClipboardReads { current?.let { content(it) } }
                        }
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
