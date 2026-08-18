package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Slider
import androidx.compose.material.Switch
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Image as SkiaImage
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode
import java.util.EnumSet
import javax.swing.JFileChooser
import kotlin.math.roundToInt

/** Compose Multiplatform renderer for the complete stable desktop node vocabulary. */
object ComposeDesktopUiNodeRenderer {
    fun supportedKinds(): Set<DesktopUiNode.Kind> =
        EnumSet.allOf(DesktopUiNode.Kind::class.java).toSet()

    @Composable
    fun Render(
        root: DesktopUiNode,
        textResolver: (DesktopUiNode.TextToken) -> String,
        eventSink: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        remember(root) { DesktopUiNode.validateTree(root) }
        Node(root, textResolver, eventSink, modifier)
    }

    @Composable
    private fun Node(
        node: DesktopUiNode,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        when (node) {
            is DesktopUiNode.Container -> Container(node, text, emit, modifier)
            is DesktopUiNode.Group -> Card(modifier, border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = .2f))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(resolve(node.title(), text), fontWeight = FontWeight.Bold)
                    Node(node.content(), text, emit, Modifier.fillMaxWidth())
                }
            }
            is DesktopUiNode.Tabs -> Tabs(node, text, emit, modifier)
            is DesktopUiNode.Scroll -> Box(modifier.verticalScroll(rememberScrollState())) {
                Node(node.content(), text, emit, Modifier.fillMaxWidth())
            }
            is DesktopUiNode.Split -> Split(node, text, emit, modifier)
            is DesktopUiNode.Text -> StyledText(node, text, modifier)
            is DesktopUiNode.Image -> ImageNode(node, text, modifier)
            is DesktopUiNode.Separator -> if (node.axis() == DesktopUiNode.Axis.HORIZONTAL) {
                Divider(modifier.fillMaxWidth())
            } else {
                Divider(modifier.fillMaxHeight().width(1.dp))
            }
            is DesktopUiNode.Spacer -> Spacer(modifier.size(node.width().dp, node.height().dp))
            is DesktopUiNode.Progress -> Progress(node, text, modifier)
            is DesktopUiNode.TextInput -> TextInput(node, text, emit, modifier)
            is DesktopUiNode.Toggle -> Toggle(node, text, emit, modifier)
            is DesktopUiNode.Choice -> Choice(node, text, emit, modifier)
            is DesktopUiNode.NumberInput -> NumberInput(node, text, emit, modifier)
            is DesktopUiNode.Table -> Table(node, text, emit, modifier)
            is DesktopUiNode.Tree -> Tree(node, text, emit, modifier)
            is DesktopUiNode.Button -> ActionButton(node, text, emit, modifier)
            is DesktopUiNode.Link -> TextButton(
                modifier = modifier,
                enabled = node.enabled(),
                onClick = { emit(activate(node.id(), node.actionId())) },
            ) { Text(resolve(node.label(), text)) }
        }
    }

    @Composable
    private fun Container(
        node: DesktopUiNode.Container,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val gap = node.gap().dp
        when (node.layout()) {
            DesktopUiNode.ContainerLayout.COLUMN -> Column(
                modifier,
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalAlignment = horizontal(node.alignment()),
            ) { node.children().forEach { Node(it, text, emit, childModifier(node.alignment())) } }
            DesktopUiNode.ContainerLayout.ROW -> Row(
                modifier,
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = vertical(node.alignment()),
            ) { node.children().forEach { Node(it, text, emit) } }
            DesktopUiNode.ContainerLayout.FLOW -> Row(
                modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = vertical(node.alignment()),
            ) { node.children().forEach { Node(it, text, emit) } }
            DesktopUiNode.ContainerLayout.GRID -> Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
                node.children().chunked(node.columns()).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { child -> Box(Modifier.weight(1f)) { Node(child, text, emit, Modifier.fillMaxWidth()) } }
                        repeat(node.columns() - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Tabs(
        node: DesktopUiNode.Tabs,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        var selected by remember(node.id()) { mutableStateOf(0) }
        Column(modifier) {
            TabRow(selectedTabIndex = selected) {
                node.tabs().forEachIndexed { index, tab ->
                    Tab(selected == index, onClick = { selected = index }, text = { Text(resolve(tab.title(), text)) })
                }
            }
            Node(node.tabs()[selected].content(), text, emit, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }

    @Composable
    private fun Split(
        node: DesktopUiNode.Split,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val firstWeight = node.resizeWeight().toFloat().coerceIn(.01f, .99f)
        val secondWeight = 1f - firstWeight
        if (node.axis() == DesktopUiNode.Axis.HORIZONTAL) {
            Row(modifier) {
                Box(Modifier.weight(firstWeight)) { Node(node.first(), text, emit, Modifier.fillMaxSize()) }
                Divider(Modifier.fillMaxHeight().width(1.dp))
                Box(Modifier.weight(secondWeight)) { Node(node.second(), text, emit, Modifier.fillMaxSize()) }
            }
        } else {
            Column(modifier) {
                Box(Modifier.weight(firstWeight)) { Node(node.first(), text, emit, Modifier.fillMaxSize()) }
                Divider(Modifier.fillMaxWidth())
                Box(Modifier.weight(secondWeight)) { Node(node.second(), text, emit, Modifier.fillMaxSize()) }
            }
        }
    }

    @Composable
    private fun StyledText(
        node: DesktopUiNode.Text,
        text: (DesktopUiNode.TextToken) -> String,
        modifier: Modifier,
    ) {
        val style = when (node.style()) {
            DesktopUiNode.TextStyle.TITLE -> MaterialTheme.typography.h5.copy(fontWeight = FontWeight.Bold)
            DesktopUiNode.TextStyle.HEADING -> MaterialTheme.typography.h6.copy(fontWeight = FontWeight.Bold)
            DesktopUiNode.TextStyle.CAPTION -> MaterialTheme.typography.caption
            DesktopUiNode.TextStyle.CODE -> MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace)
            else -> MaterialTheme.typography.body1
        }
        val color = when (node.style()) {
            DesktopUiNode.TextStyle.SUCCESS -> Color(0xFF087F5B)
            DesktopUiNode.TextStyle.WARNING -> Color(0xFFB26A00)
            DesktopUiNode.TextStyle.ERROR -> MaterialTheme.colors.error
            else -> Color.Unspecified
        }
        val content: @Composable () -> Unit = {
            Text(resolve(node.text(), text), modifier = modifier, style = style, color = color)
        }
        if (node.selectable()) SelectionContainer(content = content) else content()
    }

    @Composable
    private fun ImageNode(
        node: DesktopUiNode.Image,
        text: (DesktopUiNode.TextToken) -> String,
        modifier: Modifier,
    ) {
        val source = remember(node.image()) { SkiaImage.makeFromEncoded(node.image().bytes()) }
        DisposableEffect(source) { onDispose(source::close) }
        val bitmap = remember(source) { source.toComposeImageBitmap() }
        val description = resolve(node.altText(), text)
        val scale = when (node.scaleMode()) {
            DesktopUiNode.ScaleMode.NONE -> ContentScale.None
            DesktopUiNode.ScaleMode.FILL -> ContentScale.FillBounds
            DesktopUiNode.ScaleMode.FIT -> ContentScale.Fit
        }
        Image(
            bitmap,
            contentDescription = description,
            contentScale = scale,
            modifier = modifier.size(node.preferredWidth().dp, node.preferredHeight().dp)
                .semantics { contentDescription = description },
        )
    }

    @Composable
    private fun Progress(
        node: DesktopUiNode.Progress,
        text: (DesktopUiNode.TextToken) -> String,
        modifier: Modifier,
    ) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (node.indeterminate()) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(node.progress().toFloat(), Modifier.fillMaxWidth())
            node.text()?.let { Text(resolve(it, text), style = MaterialTheme.typography.caption) }
        }
    }

    @Composable
    private fun TextInput(
        node: DesktopUiNode.TextInput,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        var value by remember(node.id(), node.value()) { mutableStateOf(node.value()) }
        fun update(next: String) {
            value = next
            emit(change(node.id(), node.bindingId(), DesktopUiNode.Value.text(next)))
        }
        Labeled(resolve(node.label(), text), help(node.help(), text), modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = ::update,
                    enabled = node.enabled(),
                    singleLine = node.inputKind() != DesktopUiNode.InputKind.MULTILINE,
                    visualTransformation = if (node.inputKind() == DesktopUiNode.InputKind.PASSWORD)
                        PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.weight(1f).widthIn(min = (node.columns() * 8).dp),
                )
                if (node.inputKind() == DesktopUiNode.InputKind.FILE
                    || node.inputKind() == DesktopUiNode.InputKind.DIRECTORY) {
                    OutlinedButton(
                        onClick = { choosePath(node.inputKind(), value)?.let(::update) },
                        enabled = node.enabled(),
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text(text(DesktopUiNode.TextToken.key("gui.button.browse"))) }
                }
            }
        }
    }

    @Composable
    private fun Toggle(
        node: DesktopUiNode.Toggle,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        var checked by remember(node.id(), node.selected()) { mutableStateOf(node.selected()) }
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            val update: (Boolean) -> Unit = {
                checked = it
                emit(change(node.id(), node.bindingId(), DesktopUiNode.Value.bool(it)))
            }
            if (node.toggleStyle() == DesktopUiNode.ToggleStyle.SWITCH) {
                Switch(checked, update, enabled = node.enabled())
            } else {
                Checkbox(checked, update, enabled = node.enabled())
            }
            Text(resolve(node.label(), text), Modifier.padding(start = 8.dp))
        }
    }

    @Composable
    private fun Choice(
        node: DesktopUiNode.Choice,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val selected = remember(node.id(), node.selectedIds()) { mutableStateListOf<String>().apply { addAll(node.selectedIds()) } }
        fun choose(id: String) {
            if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE) {
                selected.clear(); selected.add(id)
            } else if (!selected.remove(id)) selected.add(id)
            val value = if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE)
                DesktopUiNode.Value.selection(selected.firstOrNull()) else DesktopUiNode.Value.selections(selected.toList())
            emit(selection(node.id(), node.bindingId(), value))
        }
        Labeled(resolve(node.label(), text), help(node.help(), text), modifier) {
            when (node.choiceStyle()) {
                DesktopUiNode.ChoiceStyle.COMBO_BOX -> ComboChoice(node, selected.firstOrNull(), text, ::choose)
                DesktopUiNode.ChoiceStyle.RADIO_BUTTONS -> Column {
                    node.options().forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected.contains(option.id()), { choose(option.id()) },
                                enabled = node.enabled() && option.enabled())
                            Text(resolve(option.label(), text))
                        }
                    }
                }
                DesktopUiNode.ChoiceStyle.CHECK_BOXES -> Column {
                    node.options().forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(selected.contains(option.id()), { choose(option.id()) },
                                enabled = node.enabled() && option.enabled())
                            Text(resolve(option.label(), text))
                        }
                    }
                }
                DesktopUiNode.ChoiceStyle.LIST -> Column {
                    node.options().forEach { option ->
                        val active = selected.contains(option.id())
                        Text(
                            resolve(option.label(), text),
                            modifier = Modifier.fillMaxWidth()
                                .clickable(enabled = node.enabled() && option.enabled()) { choose(option.id()) }
                                .padding(8.dp),
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (option.enabled()) Color.Unspecified else MaterialTheme.colors.onSurface.copy(alpha = .4f),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ComboChoice(
        node: DesktopUiNode.Choice,
        selectedId: String?,
        text: (DesktopUiNode.TextToken) -> String,
        choose: (String) -> Unit,
    ) {
        var expanded by remember(node.id()) { mutableStateOf(false) }
        val label = node.options().firstOrNull { it.id() == selectedId }?.let { resolve(it.label(), text) }.orEmpty()
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = node.enabled()) { Text(label.ifBlank { "…" }) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                node.options().forEach { option ->
                    DropdownMenuItem(
                        enabled = option.enabled(),
                        onClick = { choose(option.id()); expanded = false },
                    ) { Text(resolve(option.label(), text)) }
                }
            }
        }
    }

    @Composable
    private fun NumberInput(
        node: DesktopUiNode.NumberInput,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        var value by remember(node.id(), node.value()) { mutableStateOf(node.value()) }
        fun update(next: Int) {
            value = next.coerceIn(node.minimum(), node.maximum())
            emit(change(node.id(), node.bindingId(), DesktopUiNode.Value.number(value)))
        }
        Labeled(resolve(node.label(), text), help(node.help(), text), modifier) {
            if (node.numberStyle() == DesktopUiNode.NumberStyle.SLIDER) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value.toFloat(),
                        onValueChange = { update(it.roundToInt()) },
                        valueRange = node.minimum().toFloat()..node.maximum().toFloat(),
                        steps = ((node.maximum() - node.minimum()) / node.step() - 1).coerceAtLeast(0),
                        enabled = node.enabled(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(value.toString(), Modifier.padding(start = 8.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ update(value - node.step()) }, enabled = node.enabled() && value > node.minimum()) { Text("−") }
                    Text(value.toString(), Modifier.padding(horizontal = 12.dp), fontSize = 16.sp)
                    OutlinedButton({ update(value + node.step()) }, enabled = node.enabled() && value < node.maximum()) { Text("+") }
                }
            }
        }
    }

    @Composable
    private fun Table(
        node: DesktopUiNode.Table,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val selected = remember(node.id(), node.selectedRowIds()) {
            mutableStateListOf<String>().apply { addAll(node.selectedRowIds()) }
        }
        fun choose(id: String) {
            if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE) { selected.clear(); selected.add(id) }
            else if (!selected.remove(id)) selected.add(id)
            val value = if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE)
                DesktopUiNode.Value.selection(selected.firstOrNull()) else DesktopUiNode.Value.selections(selected.toList())
            emit(selection(node.id(), node.bindingId(), value))
        }
        Column(modifier.horizontalScroll(rememberScrollState())) {
            Row {
                node.columns().forEach { column ->
                    Text(resolve(column.label(), text), Modifier.width(columnWidth(column)).padding(8.dp), fontWeight = FontWeight.Bold)
                }
            }
            Divider()
            node.rows().forEach { row ->
                Row(Modifier.clickable(enabled = node.enabled()) { choose(row.id()) }) {
                    row.cells().forEachIndexed { index, cell ->
                        Text(cell, Modifier.width(columnWidth(node.columns()[index])).padding(8.dp),
                            fontWeight = if (selected.contains(row.id())) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }

    @Composable
    private fun Tree(
        node: DesktopUiNode.Tree,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val selected = remember(node.id(), node.selectedIds()) { mutableStateListOf<String>().apply { addAll(node.selectedIds()) } }
        fun choose(id: String) {
            if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE) { selected.clear(); selected.add(id) }
            else if (!selected.remove(id)) selected.add(id)
            val value = if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE)
                DesktopUiNode.Value.selection(selected.firstOrNull()) else DesktopUiNode.Value.selections(selected.toList())
            emit(selection(node.id(), node.bindingId(), value))
        }
        Column(modifier) {
            node.items().forEach { TreeItem(it, 0, selected, node.enabled(), text, ::choose) }
        }
    }

    @Composable
    private fun TreeItem(
        item: DesktopUiNode.TreeItem,
        depth: Int,
        selected: List<String>,
        enabled: Boolean,
        text: (DesktopUiNode.TextToken) -> String,
        choose: (String) -> Unit,
    ) {
        Text(
            resolve(item.label(), text),
            modifier = Modifier.fillMaxWidth().clickable(enabled) { choose(item.id()) }
                .padding(start = (depth * 20).dp, top = 6.dp, bottom = 6.dp),
            fontWeight = if (selected.contains(item.id())) FontWeight.Bold else FontWeight.Normal,
        )
        item.children().forEach { TreeItem(it, depth + 1, selected, enabled, text, choose) }
    }

    @Composable
    private fun ActionButton(
        node: DesktopUiNode.Button,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val colors = when (node.buttonStyle()) {
            DesktopUiNode.ButtonStyle.DANGER -> ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
            DesktopUiNode.ButtonStyle.PRIMARY -> ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary)
            else -> ButtonDefaults.buttonColors()
        }
        Button(
            modifier = modifier,
            enabled = node.enabled(),
            colors = colors,
            onClick = { emit(activate(node.id(), node.actionId())) },
        ) { Text(resolve(node.label(), text)) }
    }

    @Composable
    private fun Labeled(label: String, help: String, modifier: Modifier, content: @Composable () -> Unit) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
            content()
            if (help.isNotBlank()) Text(help, style = MaterialTheme.typography.caption)
        }
    }

    private fun resolve(token: DesktopUiNode.TextToken, resolver: (DesktopUiNode.TextToken) -> String): String =
        resolver(token).ifBlank { token.fallback().ifBlank { token.key() } }

    private fun help(token: DesktopUiNode.TextToken?, resolver: (DesktopUiNode.TextToken) -> String): String =
        token?.let { resolve(it, resolver) }.orEmpty()

    private fun horizontal(alignment: DesktopUiNode.Alignment): Alignment.Horizontal = when (alignment) {
        DesktopUiNode.Alignment.CENTER -> Alignment.CenterHorizontally
        DesktopUiNode.Alignment.END -> Alignment.End
        else -> Alignment.Start
    }

    private fun vertical(alignment: DesktopUiNode.Alignment): Alignment.Vertical = when (alignment) {
        DesktopUiNode.Alignment.CENTER -> Alignment.CenterVertically
        DesktopUiNode.Alignment.END -> Alignment.Bottom
        else -> Alignment.Top
    }

    private fun childModifier(alignment: DesktopUiNode.Alignment): Modifier =
        if (alignment == DesktopUiNode.Alignment.STRETCH) Modifier.fillMaxWidth() else Modifier

    private fun columnWidth(column: DesktopUiNode.TableColumn) =
        (if (column.preferredWidth() > 0) column.preferredWidth() else 160).dp

    private fun choosePath(kind: DesktopUiNode.InputKind, value: String): String? {
        val chooser = JFileChooser(value.ifBlank { "." })
        chooser.fileSelectionMode = if (kind == DesktopUiNode.InputKind.DIRECTORY)
            JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
            chooser.selectedFile.absolutePath else null
    }

    private fun activate(nodeId: String, actionId: String) = DesktopUiNode.Event(
        DesktopUiNode.EventType.ACTIVATE, nodeId, actionId, DesktopUiNode.Value.empty(),
    )

    private fun change(nodeId: String, bindingId: String, value: DesktopUiNode.Value) = DesktopUiNode.Event(
        DesktopUiNode.EventType.CHANGE, nodeId, bindingId, value,
    )

    private fun selection(nodeId: String, bindingId: String, value: DesktopUiNode.Value) = DesktopUiNode.Event(
        DesktopUiNode.EventType.SELECTION, nodeId, bindingId, value,
    )
}
