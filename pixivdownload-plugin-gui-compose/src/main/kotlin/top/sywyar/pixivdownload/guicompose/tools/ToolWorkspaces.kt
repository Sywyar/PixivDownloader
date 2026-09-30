@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.CupertinoText
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*

@Composable
internal fun ToolWorkspacePanel(
    workspace: ToolWorkspace,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    close: () -> Unit,
) {
    val classifier = workspace.toolId() == "classifier"
    val id = workspace.toolId()
    fun label(key: String) = workspaceText(text, key)
    val nodes = descendants(workspace.content()).toList()
    val buttons = nodes.filterIsInstance<Button>().associateBy { it.id() }
    val palette = LocalExperiencePalette.current
    ToolPanelLayout(
        title = label("$id.title"),
        description = label("$id.description"),
        icon = if (classifier) Icons.Default.Collections else Icons.Default.FolderOpen,
        close = close,
        closeLabel = label("close"),
        closeEnabled = workspace.close().enabled(),
        footer = {
            if (classifier) {
                buttons["classifier.previous"]?.let { ToolAction(it, text, emit) }
                buttons["classifier.skip"]?.let { ToolAction(it, text, emit) }
                Spacer(Modifier.weight(1f))
                buttons["classifier.classify"]?.let { ToolAction(it, text, emit, primary = true) }
            } else {
                CupertinoText(label("folder.close-help"), Modifier.weight(1f), fontSize = 11.sp, color = palette.secondaryText)
                ToolButton(label("done"), "tools.active.close", enabled = workspace.close().enabled(), onClick = close)
                if (nodes.filterIsInstance<Table>().first().selectedRowIds().isNotEmpty()) {
                    buttons["tools.folder.update"]?.let { ToolAction(it, text, emit, true) }
                }
            }
        },
    ) {
        if (!workspace.close().enabled()) DesktopLinearProgress(null, Modifier.fillMaxWidth().height(4.dp))
        if (classifier) ClassifierWorkspace(workspace, nodes, buttons, text, emit)
        else FolderWorkspace(nodes, buttons, text, emit)
    }
}

@Composable
private fun ClassifierWorkspace(
    workspace: ToolWorkspace,
    nodes: List<DesktopUiNode>,
    buttons: Map<String, Button>,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    fun label(key: String) = workspaceText(text, key)
    val input = nodes.filterIsInstance<TextInput>().first()
    ToolField(input, text, emit, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        buttons["classifier.open"]?.let { ToolAction(it, text, emit, true) }
        buttons["classifier.settings"]?.let { ToolAction(it, text, emit) }
        buttons["classifier.refresh"]?.let { ToolAction(it, text, emit) }
        Spacer(Modifier.weight(1f))
        nodes.filterIsInstance<Text>().firstOrNull { it.id() == "classifier.server" }?.let {
            CupertinoText(text(it.text()), fontSize = 11.sp, color = if (it.style() == TextStyle.WARNING) palette.warning else palette.secondaryText)
        }
    }
    val thumbnails = (nodes.first { it.id() == "classifier.thumbnails" } as Container).children().filterIsInstance<Surface>()
    val categories = nodes.filterIsInstance<Surface>().filter { it.id().startsWith("classifier.category.") }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("tools.classifier.workspace")) {
        val narrow = maxWidth < 660.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        @Composable fun previews(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (thumbnails.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 200.dp).background(palette.secondarySurface.copy(alpha = .45f), RoundedCornerShape(12.dp)).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    ) {
                        DesktopIcon(Icons.Default.Collections, null, Modifier.size(36.dp), tint = palette.secondaryText)
                        CupertinoText(text(TextToken.key("gui.image-classifier.thumbnail.empty")), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        CupertinoText(label("classifier.empty-help"), fontSize = 12.sp, color = palette.secondaryText)
                    }
                } else {
                    AnimatedContent(thumbnails, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) }, label = "classifier-previews") { retained ->
                        BoxWithConstraints {
                            val count = if (maxWidth < 450.dp) 3 else 4
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                retained.chunked(count).forEach { images ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        images.forEach { item ->
                                            val children = descendants(item).toList()
                                            val image = children.firstOrNull { it is DesktopUiNode.Image || it is LocalImage }
                                            val action = children.filterIsInstance<Button>().first()
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Box(
                                                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(9.dp))
                                                        .background(palette.secondarySurface).testTag(action.id())
                                                        .clickable(enabled = action.enabled(), role = Role.Button) { emit(Event(EventType.ACTIVATE, action.id(), Value.empty())) },
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    if (image != null) ComposeDesktopUiNodeRenderer.Render(image, text, emit, Modifier.fillMaxSize())
                                                    else DesktopIcon(Icons.Default.ImageNotSupported, text(action.label()), tint = palette.secondaryText)
                                                }
                                                CupertinoText(text(action.label()), fontSize = 11.sp, color = palette.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        repeat(count - images.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    buttons["classifier.group.previous"]?.let { ToolAction(it, text, emit) }
                    nodes.filterIsInstance<Text>().firstOrNull { it.id() == "classifier.group.position" }?.let { CupertinoText(text(it.text()), fontSize = 12.sp, color = palette.secondaryText) }
                    buttons["classifier.group.next"]?.let { ToolAction(it, text, emit) }
                }
            }
        }
        @Composable fun targets(modifier: Modifier) {
            Column(modifier.background(palette.secondarySurface.copy(alpha = .4f), RoundedCornerShape(12.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CupertinoText(text(TextToken.key("gui.image-classifier.section.categories")), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (categories.isEmpty()) {
                    CupertinoText(text(TextToken.key("gui.image-classifier.validation.target-folders-not-configured")), fontSize = 12.sp, color = palette.secondaryText)
                    buttons["classifier.settings"]?.let { ToolAction(it, text, emit) }
                } else categories.forEach { category ->
                    val children = descendants(category).toList()
                    val action = children.filterIsInstance<Button>().first()
                    val selected = workspace.selectedTarget() == "target.${category.id().substringAfterLast('.')}"
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (selected) palette.selection else palette.surface)
                            .selectable(selected, enabled = action.enabled(), role = Role.RadioButton) { emit(Event(EventType.ACTIVATE, action.id(), Value.empty())) }
                            .testTag(action.id()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        CupertinoText(text(action.label()), fontSize = 13.sp, color = if (selected) palette.link else palette.text)
                        children.filterIsInstance<Text>().firstOrNull()?.let { CupertinoText(text(it.text()), fontSize = 11.sp, color = palette.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        if (narrow) Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            previews(Modifier.fillMaxWidth())
            targets(Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            previews(Modifier.weight(1f))
            targets(Modifier.width(220.dp))
        }
    }
    nodes.filterIsInstance<Text>().firstOrNull { it.id() == "classifier.notice" }?.let { ToolNotice(text(it.text())) }
}

@Composable
private fun FolderWorkspace(
    nodes: List<DesktopUiNode>,
    buttons: Map<String, Button>,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    fun label(key: String) = workspaceText(text, key)
    val db = nodes.filterIsInstance<TextInput>().first { it.id() == "tools.folder.db" }
    var attempted by remember { mutableStateOf(false) }
    ToolField(db, text, emit, error = if (attempted && db.value().isBlank()) text(TextToken.key("gui.tools.validation.database-path.required")) else null, focusError = true)
    buttons["tools.folder.check"]?.let { button ->
        ToolAction(button, text, emit, true) {
            attempted = true
            if (db.value().isNotBlank()) emit(Event(EventType.ACTIVATE, button.id(), Value.empty()))
        }
    }
    ToolFlow(listOf(label("folder.check"), label("folder.select"), label("folder.repair")))
    nodes.filterIsInstance<Text>().firstOrNull { it.id() == "tools.folder.notice" }?.let { ToolNotice(text(it.text())) }
    val table = nodes.filterIsInstance<Table>().first()
    if (table.rows().isNotEmpty()) {
        val scroll = rememberLazyListState()
        Box(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            LazyColumn(Modifier.fillMaxWidth().testTag("tools.folder.results"), state = scroll, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(table.rows(), key = { it.id() }) { row ->
                    val selected = row.id() in table.selectedRowIds()
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (selected) palette.selection else palette.secondarySurface.copy(alpha = .5f))
                            .selectable(selected, enabled = table.enabled(), role = Role.RadioButton) { emit(Event(EventType.SELECTION, table.id(), Value.selection(row.id()))) }
                            .testTag(row.id()).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            DesktopIcon(Icons.Default.FolderOff, null, Modifier.size(20.dp), tint = palette.secondaryText)
                            CupertinoText(row.cells()[1], Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            CupertinoText(row.cells()[0], fontSize = 11.sp, color = palette.secondaryText)
                        }
                        CupertinoText(row.cells()[3], fontSize = 12.sp, color = palette.secondaryText)
                        CupertinoText("${row.cells()[2]} · ${row.cells()[4]}", fontSize = 11.sp, color = palette.secondaryText)
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.matchParentSize().wrapContentWidth(Alignment.End))
        }
    }
    AnimatedVisibility(table.selectedRowIds().isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                nodes.filterIsInstance<Text>().firstOrNull { it.id() == "tools.folder.selected-id" }?.let { SelectionContainer(Modifier.weight(1f)) { CupertinoText(text(it.text()), fontSize = 13.sp) } }
                buttons["tools.folder.copy"]?.let { ToolAction(it, text, emit) }
            }
            nodes.filterIsInstance<TextInput>().firstOrNull { it.id() == "tools.folder.new-path" }?.let { ToolField(it, text, emit) }
        }
    }
}
