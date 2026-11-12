package top.sywyar.pixivdownload.guicompose.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.robinpcrd.cupertino.*
import io.github.robinpcrd.cupertino.theme.CupertinoTheme
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode

/** 平时只显示路径，双击或 F2 编辑；稳定行身份保证删除不会串行。 */
@OptIn(ExperimentalCupertinoApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ImportPresetListContent(
    node: DesktopUiNode.ImportPresetList,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
    modifier: Modifier,
) {
    val inputs = node.inputs()
    val ids = inputs.map { it.id() }
    var previousIds by remember(node.id()) { mutableStateOf(ids) }
    var selectedId by remember(node.id()) { mutableStateOf<String?>(null) }
    var editingId by remember(node.id()) { mutableStateOf<String?>(null) }
    val palette = LocalExperiencePalette.current
    LaunchedEffect(ids) {
        val added = ids.firstOrNull { it !in previousIds }
        if (added != null) {
            selectedId = added
            editingId = added
        } else if (selectedId != null && selectedId !in ids) {
            val index = previousIds.indexOf(selectedId).coerceAtMost(ids.lastIndex)
            selectedId = ids.getOrNull(index)
            editingId = null
        }
        previousIds = ids
    }
    Column(modifier.fillMaxWidth().testTag(node.id()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CupertinoText(text(node.title()), style = CupertinoTheme.typography.subhead)
        Column(Modifier.fillMaxWidth()) {
            if (inputs.isEmpty()) CupertinoText(
                text(node.emptyText()),
                Modifier.padding(vertical = 12.dp),
                style = CupertinoTheme.typography.body,
                color = palette.secondaryText,
            )
            inputs.forEachIndexed { index, input ->
                key(input.id()) {
                    val removal = node.removals()[index]
                    val selected = selectedId == input.id()
                    val editing = editingId == input.id()
                    val rowFocus = remember { FocusRequester() }
                    val editorFocus = remember { FocusRequester() }
                    var focused by remember { mutableStateOf(false) }
                    var editorHadFocus by remember { mutableStateOf(false) }
                    var original by remember { mutableStateOf(input.value()) }
                    var value by remember { mutableStateOf(TextFieldValue(input.value())) }
                    fun change(next: String) = emit(DesktopUiNode.Event(
                        DesktopUiNode.EventType.CHANGE,
                        input.id(),
                        DesktopUiNode.Value.text(next),
                    ))
                    LaunchedEffect(editing) {
                        editorHadFocus = false
                        if (editing) {
                            original = input.value()
                            value = TextFieldValue(original, TextRange(original.length))
                            editorFocus.requestFocus()
                        }
                    }
                    LaunchedEffect(selected, editing, ids) {
                        if (selected && !editing) rowFocus.requestFocus()
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val rowModifier = Modifier.weight(1f).testTag(input.id())
                            .background(if (selected) palette.selection else Color.Transparent)
                        if (editing) {
                            BasicTextField(
                                value = value,
                                onValueChange = { value = it; change(it.text) },
                                singleLine = true,
                                textStyle = CupertinoTheme.typography.body.copy(color = palette.text),
                                cursorBrush = SolidColor(palette.link),
                                modifier = rowModifier.border(1.dp, palette.link)
                                    .focusRequester(editorFocus)
                                    .onFocusChanged {
                                        if (it.isFocused) editorHadFocus = true
                                        else if (editorHadFocus && editingId == input.id()) editingId = null
                                    }
                                    .onPreviewKeyEvent {
                                        if (it.key == Key.Escape || it.key == Key.Enter) {
                                            if (it.type == KeyEventType.KeyUp) {
                                                if (it.key == Key.Escape) change(original)
                                                editingId = null
                                            }
                                            true
                                        } else false
                                    }
                                    .semantics { contentDescription = text(input.label()) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        } else {
                            CupertinoText(
                                text = input.value(),
                                style = CupertinoTheme.typography.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = rowModifier
                                    .border(1.dp, if (focused) palette.link else Color.Transparent)
                                    .focusRequester(rowFocus)
                                    .onFocusChanged { focused = it.isFocused }
                                    .onKeyEvent {
                                        if ((it.key == Key.F2 || it.key == Key.Enter) && it.type == KeyEventType.KeyUp) {
                                            selectedId = input.id(); editingId = input.id(); true
                                        } else false
                                    }
                                    .combinedClickable(
                                        onClick = { selectedId = input.id(); editingId = null; rowFocus.requestFocus() },
                                        onDoubleClick = { selectedId = input.id(); editingId = input.id() },
                                    )
                                    .semantics { this.selected = selected }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        CupertinoIconButton(
                            onClick = { emit(DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, removal.id(), DesktopUiNode.Value.empty())) },
                            modifier = Modifier.size(32.dp).testTag(removal.id()),
                        ) {
                            DesktopIcon(Icons.Default.RemoveCircleOutline, contentDescription = text(removal.label()))
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.separator))
                }
            }
        }
    }
}
