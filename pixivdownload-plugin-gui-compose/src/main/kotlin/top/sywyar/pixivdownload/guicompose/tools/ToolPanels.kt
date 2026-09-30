@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.tools

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import io.github.robinpcrd.cupertino.theme.CupertinoTheme
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.ToolInputValidation
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*

@Composable
internal fun ToolPanelLayout(
    title: String,
    description: String,
    icon: ImageVector,
    close: () -> Unit,
    closeLabel: String,
    closeEnabled: Boolean = true,
    tagPrefix: String = "tools",
    footer: @Composable RowScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalExperiencePalette.current
    Column(Modifier.fillMaxWidth().onPreviewKeyEvent {
        if (closeEnabled && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
            close()
            true
        } else false
    }) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, top = 26.dp, end = 18.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ToolIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CupertinoText(title, Modifier.semantics { heading(); paneTitle = title }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                if (description.isNotBlank()) CupertinoText(description, fontSize = 12.sp, color = palette.secondaryText)
            }
            CupertinoIconButton(close, enabled = closeEnabled, modifier = Modifier.testTag("$tagPrefix.sheet.close")) {
                DesktopIcon(Icons.Default.Close, closeLabel, tint = palette.secondaryText)
            }
        }
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f, fill = false)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 28.dp, end = 28.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = body,
            )
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.matchParentSize().wrapContentWidth(Alignment.End))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.controlBorder.copy(alpha = .35f)))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp).testTag("$tagPrefix.footer"),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            content = footer,
        )
    }
}

@Composable
internal fun ToolField(
    node: TextInput,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    modifier: Modifier = Modifier,
    label: String = text(node.label()),
    help: String? = node.help()?.let(text),
    error: String? = null,
    attempt: Int = 0,
    focusError: Boolean = false,
) {
    val palette = LocalExperiencePalette.current
    var value by rememberSaveable(node.id(), stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(node.value())) }
    LaunchedEffect(node.value()) {
        if (value.text != node.value()) value = TextFieldValue(node.value(), TextRange(value.selection.end.coerceAtMost(node.value().length)))
    }
    fun update(next: TextFieldValue) {
        val changed = next.text != value.text
        value = next
        if (changed) emit(Event(EventType.CHANGE, node.id(), Value.text(next.text)))
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(attempt, error) {
        if (error != null) {
            if (focusError) focus.requestFocus()
            if (coroutineContext[MotionDurationScale]?.scaleFactor != 0f) shake.animateTo(0f, keyframes {
                durationMillis = 320
                -7f at 40; 6f at 90; -4f at 150; 3f at 210; 0f at 320
            })
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CupertinoText(label, Modifier.testTag("${node.id()}.label"), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(
            Modifier.fillMaxWidth().testTag("${node.id()}.field")
                .graphicsLayer { translationX = with(density) { shake.value.dp.toPx() } }
                .background(if (error != null) palette.error.copy(alpha = .06f) else palette.secondarySurface.copy(alpha = .45f), RoundedCornerShape(9.dp))
                .border(if (focused) 2.dp else 1.dp, when { error != null -> palette.error; focused -> palette.link; else -> palette.controlBorder.copy(alpha = .5f) }, RoundedCornerShape(9.dp))
                .semantics { if (error != null) error(error) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = ::update,
                enabled = node.enabled(),
                singleLine = true,
                interactionSource = interaction,
                cursorBrush = SolidColor(palette.link),
                textStyle = CupertinoTheme.typography.body.copy(fontSize = 13.sp, color = palette.text),
                modifier = Modifier.weight(1f).focusRequester(focus).testTag(node.id())
                    .semantics { contentDescription = label }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            )
            if (node.inputKind() == InputKind.FILE || node.inputKind() == InputKind.DIRECTORY) {
                CupertinoIconButton(
                    onClick = {
                        ComposeDesktopUiNodeRenderer.choosePath(node.inputKind(), value.text)?.let { update(TextFieldValue(it, TextRange(it.length))) }
                    },
                    enabled = node.enabled(),
                    modifier = Modifier.size(38.dp).testTag("${node.id()}.browse"),
                ) {
                    DesktopIcon(Icons.Default.FolderOpen, text(TextToken("gui-compose", "gui.compose.browse", "", emptyList())), Modifier.size(18.dp), tint = palette.link)
                }
            }
        }
        if (error != null) CupertinoText(error, fontSize = 11.sp, color = palette.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (!help.isNullOrBlank()) CupertinoText(help, fontSize = 11.sp, color = palette.secondaryText)
    }
}

@Composable
internal fun ToolNotice(message: String, warning: Boolean = false) {
    val palette = LocalExperiencePalette.current
    Row(
        Modifier.fillMaxWidth().background(if (warning) palette.warning.copy(alpha = .08f) else palette.secondarySurface.copy(alpha = .55f), RoundedCornerShape(10.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DesktopIcon(Icons.Default.Info, null, Modifier.size(17.dp), tint = if (warning) palette.warning else palette.link)
        CupertinoText(message, fontSize = 12.sp, color = palette.secondaryText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
internal fun ToolAction(
    button: Button,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    primary: Boolean = false,
    label: String = text(button.label()),
    onClick: (() -> Unit)? = null,
) {
    ToolButton(label, button.id(), primary, button.enabled()) {
        if (onClick != null) onClick() else emit(Event(EventType.ACTIVATE, button.id(), Value.empty()))
    }
}

@Composable
internal fun ToolDisclosure(label: String, tag: String, expanded: Boolean, onClick: () -> Unit) {
    val palette = LocalExperiencePalette.current
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, tween(200), label = "disclosure")
    Row(
        Modifier.testTag(tag).clickable(role = Role.Button, onClick = onClick)
            .semantics {
                if (expanded) collapse { onClick(); true } else expand { onClick(); true }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DesktopIcon(Icons.Default.ChevronRight, null, Modifier.size(15.dp).graphicsLayer { rotationZ = rotation }, tint = palette.link)
        CupertinoText(label, fontSize = 12.sp, color = palette.link)
    }
}

@Composable
private fun ToolSwitch(node: Toggle, label: String, help: String, emit: (Event) -> Unit) {
    val palette = LocalExperiencePalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            CupertinoText(label, fontSize = 13.sp)
            CupertinoText(help, fontSize = 11.sp, color = palette.secondaryText)
        }
        CupertinoSwitch(
            checked = node.selected(),
            onCheckedChange = { emit(Event(EventType.CHANGE, node.id(), Value.bool(it))) },
            enabled = node.enabled(),
            colors = CupertinoSwitchDefaults.colors(checkedTrackColor = palette.link),
            modifier = Modifier.testTag(node.id()).semantics { contentDescription = label },
        )
    }
}

@Composable
internal fun ToolPanel(
    id: String,
    title: String,
    description: String,
    icon: ImageVector,
    group: Group,
    activity: ToolActivity?,
    backend: Text,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    close: () -> Unit,
) {
    val palette = LocalExperiencePalette.current
    fun label(key: String) = workspaceText(text, key)
    val nodes = descendants(group).toList()
    val buttons = nodes.filterIsInstance<Button>()
    val form = nodes.filterIsInstance<Form>().firstOrNull()
    val progress = nodes.filterIsInstance<Progress>().firstOrNull()
    var showForm by remember(id) { mutableStateOf(false) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    val errors = if (form != null && id in listOf("backfill", "migration")) ToolInputValidation.errors(id, form.rows().associate { row ->
        row.content().id() to when (val field = row.content()) { is TextInput -> field.value(); is Toggle -> field.selected().toString(); else -> "" }
    }) else emptyMap()
    LaunchedEffect(activity?.running()) { if (activity?.running() == true) showForm = false }
    val phase = when { progress != null || activity?.running() == true -> "running"; activity != null && !showForm -> "result"; else -> "form" }
    ToolPanelLayout(title, description, icon, close, label("close"), footer = {
        when (phase) {
            "running" -> {
                CupertinoText(label("running-background"), Modifier.weight(1f), fontSize = 11.sp, color = palette.secondaryText)
                buttons.firstOrNull { it.id().endsWith(".cancel") }?.let { ToolAction(it, text, emit) }
                ToolButton(label("keep-running"), "tools.minimize", onClick = close)
            }
            "result" -> {
                buttons.firstOrNull { it.id().endsWith(".log") }?.let { ToolAction(it, text, emit) }
                Spacer(Modifier.weight(1f))
                ToolButton(label("edit"), "tools.edit") { showForm = true }
                ToolButton(label("done"), "tools.done", primary = true, onClick = close)
            }
            else -> {
                ToolButton(text(TextToken.key("desktop.ui.action.cancel")), "tools.cancel", onClick = close)
                buttons.firstOrNull { it.id().endsWith(".run") }?.let { button ->
                    val dry = form?.rows()?.map { it.content() }?.filterIsInstance<Toggle>()?.any { it.id().endsWith(".dry") && it.selected() } == true
                    ToolAction(button, text, emit, true, label(if (dry) "start-dry" else "$id.start")) {
                        if (errors.isNotEmpty()) attempt++ else emit(Event(EventType.ACTIVATE, button.id(), Value.empty()))
                    }
                }
            }
        }
    }) {
        AnimatedContent(phase, transitionSpec = { fadeIn(tween(200, 60)) togetherWith fadeOut(tween(120)) }, label = "tool-phase") { state ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (state != "form") {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        ToolIcon(if (state == "running") icon else if (activity!!.failed()) Icons.Default.ErrorOutline else Icons.Default.CheckCircleOutline)
                        SelectionContainer {
                            CupertinoText(progress?.text()?.let(text) ?: activity?.message()?.let(text).orEmpty(),
                                fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.testTag("tools.result").semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        if (state == "running") DesktopLinearProgress(progress?.takeUnless { it.indeterminate() }?.progress()?.toFloat(), Modifier.fillMaxWidth().height(5.dp))
                        ToolNotice(text(backend.text()))
                    }
                } else {
                    if (form != null) ToolForm(form, id, text, emit, if (attempt > 0) errors else emptyMap(), attempt)
                    if (id == "migration") ToolFlow(listOf(label("migration.read"), label("migration.write"), label("migration.restore")))
                    ToolNotice(label("$id.notice"), id == "migration")
                }
            }
        }
    }
}

@Composable
internal fun ToolFlow(labels: List<String>) {
    val palette = LocalExperiencePalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            if (index > 0) DesktopIcon(Icons.Default.ChevronRight, null, Modifier.size(12.dp), tint = palette.secondaryText)
            Box(Modifier.weight(1f).background(palette.secondarySurface.copy(alpha = .55f), RoundedCornerShape(9.dp)).padding(horizontal = 6.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                CupertinoText(label, fontSize = 11.sp, color = palette.secondaryText)
            }
        }
    }
}

@Composable
private fun ToolForm(form: Form, id: String, text: (TextToken) -> String, emit: (Event) -> Unit, errors: Map<String, String>, attempt: Int) {
    fun label(key: String) = workspaceText(text, key)
    fun row(suffix: String) = form.rows().first { it.content().id().endsWith(".$suffix") }
    var advanced by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(attempt) { if (errors.keys.any { it.endsWith(".delay") || it.endsWith(".limit") }) advanced = true }
    @Composable fun field(row: FormRow, modifier: Modifier = Modifier) {
        val node = row.content() as TextInput
        val key = errors[node.id()]
        val error = key?.let { text(if (it.startsWith("gui.compose.")) TextToken("gui-compose", it, "", emptyList()) else TextToken.key(it)) }
        ToolField(node, text, emit, modifier,
            label = when { node.id().endsWith(".proxy-host") -> label("proxy-host"); node.id().endsWith(".proxy-port") -> label("proxy-port"); else -> text(row.label()) },
            help = if (node.id().endsWith(".root")) label("migration.root-help") else row.help()?.let(text),
            error = error, attempt = attempt, focusError = node.id() == form.rows().firstOrNull { it.content().id() in errors }?.content()?.id())
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        form.rows().filter { it.content().id().endsWith(".db") || it.content().id().endsWith(".root") }.forEach { field(it) }
        if (id == "backfill") {
            val proxy = row("proxy").content() as Toggle
            ToolSwitch(proxy, text(proxy.label()), label("proxy-help"), emit)
            AnimatedVisibility(proxy.selected(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    field(row("proxy-host"), Modifier.weight(1f))
                    field(row("proxy-port"), Modifier.width(105.dp))
                }
            }
            Column {
                ToolDisclosure(label("advanced"), "tools.advanced", advanced) { advanced = !advanced }
                AnimatedVisibility(advanced, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        field(row("delay"), Modifier.weight(1f))
                        field(row("limit"), Modifier.weight(1f))
                    }
                }
            }
            ToolSwitch(row("dry").content() as Toggle, label("dry-title"), label("dry-help"), emit)
        }
    }
}
