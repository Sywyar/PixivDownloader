@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package top.sywyar.pixivdownload.guicompose.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import io.github.robinpcrd.cupertino.theme.CupertinoTheme
import top.sywyar.pixivdownload.guicompose.ComposeDesktopUiNodeRenderer
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*

@Composable
internal fun RepositoryEditorContent(
    editor: RepositoryEditor,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = editor.modes()
    var selected by rememberSaveable(editor.id()) { mutableStateOf(modes.initialSelectedId()) }
    val tabs = modes.tabs()
    val active = tabs.firstOrNull { it.id() == selected } ?: tabs.first()
    val dock = active.content() as Dock
    val state = rememberSaveableStateHolder()
    val palette = LocalExperiencePalette.current
    Column(modifier.testTag("repository.editor"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CupertinoSegmentedControl(
            selectedTabIndex = tabs.indexOf(active),
            modifier = Modifier.fillMaxWidth(),
            colors = CupertinoSegmentedControlDefaults.colors(
                containerColor = palette.secondarySurface,
                indicatorColor = palette.surface,
            ),
        ) {
            tabs.forEach { tab ->
                CupertinoSegmentedControlTab(
                    onClick = { selected = tab.id() },
                    isSelected = tab == active,
                    modifier = Modifier.heightIn(min = 36.dp).semantics { this.selected = tab == active },
                ) {
                    CupertinoText(
                        text(tab.title()),
                        Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        style = CupertinoTheme.typography.subhead,
                        fontWeight = if (tab == active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            state.SaveableStateProvider(active.id()) {
                val scroll = rememberScrollState()
                Column(
                    Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RepositoryFields((dock.center() as Scroll).content(), text, emit)
                }
                VerticalScrollbar(
                    rememberScrollbarAdapter(scroll),
                    Modifier.matchParentSize().wrapContentWidth(Alignment.End),
                )
            }
        }
        CupertinoHorizontalDivider(color = palette.separator)
        FlowRow(
            Modifier.fillMaxWidth().testTag("repository.actions"),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val actions = (dock.bottom() as Container).children().filterIsInstance<Button>()
            actions.asReversed().forEach { action ->
                ComposeDesktopUiNodeRenderer.Render(
                    Button(
                        action.id(),
                        action.actionId(),
                        action.label(),
                        action.help(),
                        if (action == actions.first()) ButtonStyle.PRIMARY else ButtonStyle.NORMAL,
                        action.enabled(),
                    ),
                    text,
                    emit,
                    Modifier.testTag(action.id()),
                )
            }
        }
    }
}

@Composable
private fun RepositoryFields(
    node: DesktopUiNode,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    when (node) {
        is Container -> if (node.layout() == ContainerLayout.COLUMN) {
            node.children().forEach { child -> key(child.id()) { RepositoryFields(child, text, emit) } }
        } else ComposeDesktopUiNodeRenderer.Render(node, text, emit, Modifier.fillMaxWidth())
        is Form -> node.rows().forEach { row -> key(row.id()) {
            val field = row.content()
            if (field is Toggle) {
                ComposeDesktopUiNodeRenderer.Render(field, text, emit, Modifier.fillMaxWidth())
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                CupertinoText(
                    text(row.label()),
                    Modifier.testTag(row.id() + ".label"),
                    fontSize = 13.sp,
                    fontWeight = if (field is Text) FontWeight.Normal else FontWeight.Medium,
                    color = if (field is Text) palette.secondaryText else palette.text,
                )
                if (field is Text) RepositoryFields(field, text, emit)
                else ComposeDesktopUiNodeRenderer.FormContent(field, text, emit, Modifier.fillMaxWidth().testTag(field.id()))
                row.help()?.let { CupertinoText(text(it), fontSize = 12.sp, color = palette.secondaryText) }
            }
        } }
        is Text -> {
            val error = node.style() == TextStyle.ERROR
            SelectionContainer {
                CupertinoText(
                    text(node.text()),
                    Modifier.fillMaxWidth().testTag(node.id()).semantics {
                        if (error) error(text(node.text()))
                        if (node.style() == TextStyle.HEADING) heading()
                    },
                    fontSize = if (node.style() == TextStyle.HEADING) 15.sp else 13.sp,
                    fontWeight = if (node.style() == TextStyle.HEADING) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = if (node.style() == TextStyle.CODE) FontFamily.Monospace else FontFamily.Default,
                    color = when (node.style()) {
                        TextStyle.ERROR -> palette.error
                        TextStyle.CAPTION -> palette.secondaryText
                        else -> palette.text
                    },
                )
            }
        }
        is Group -> {
            var expanded by rememberSaveable(node.id()) { mutableStateOf(!node.collapsible()) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CupertinoButton(
                    onClick = { expanded = !expanded },
                    colors = CupertinoButtonDefaults.plainButtonColors(contentColor = palette.text),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    modifier = Modifier.testTag(node.id()).semantics { selected = expanded },
                ) {
                    CupertinoText((if (expanded) "▾  " else "▸  ") + text(node.title()), fontSize = 13.sp)
                }
                if (expanded) RepositoryFields(node.content(), text, emit)
            }
        }
        is Table -> node.rows().forEach { row ->
            Column(
                Modifier.fillMaxWidth()
                    .background(if (row.id() in node.selectedRowIds()) palette.selection else palette.surface)
                    .selectable(
                        selected = row.id() in node.selectedRowIds(),
                        enabled = node.enabled(),
                        role = Role.RadioButton,
                        onClick = { emit(Event(EventType.SELECTION, node.id(), Value.selection(row.id()))) },
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                row.cells().forEachIndexed { index, value ->
                    CupertinoText(
                        text(node.columns()[index].label()) + ": " + value,
                        fontSize = 13.sp,
                        fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal,
                        color = if (index == 0) palette.text else palette.secondaryText,
                    )
                }
            }
        }
        else -> ComposeDesktopUiNodeRenderer.Render(node, text, emit, Modifier.fillMaxWidth())
    }
}
