@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.settings

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import kotlinx.coroutines.delay

internal val LocalHintDismiss = staticCompositionLocalOf { 0 }

@Composable
internal fun SettingsCategory(
    tab: Tab,
    workspace: SettingsWorkspace,
    secrets: MutableMap<String, Pair<Long, TextFieldValue>>,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val scroll = rememberScrollState()
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(tab.id()) { entrance.animateTo(1f, tween(180)) }
    Box(Modifier.fillMaxSize().testTag("settings.content")) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 14.dp, bottom = 30.dp)
            .graphicsLayer { alpha = entrance.value; translationY = 5.dp.toPx() * (1 - entrance.value) }) {
            Row(Modifier.padding(bottom = 26.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(palette.selection, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    DesktopIcon(categoryIcon(tab.id()), null, tint = palette.link, modifier = Modifier.size(23.dp))
                }
                CupertinoText(text(tab.title()), fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
            }
            CompositionLocalProvider(LocalHintDismiss provides (LocalHintDismiss.current + scroll.value)) {
                SettingsContent(tab.content(), workspace, secrets, text, emit)
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = LocalScrollbarStyle.current.copy(thickness = 5.dp, unhoverColor = palette.separator.copy(alpha = .5f)))
    }
}

@Composable
private fun SettingsContent(
    node: DesktopUiNode,
    workspace: SettingsWorkspace,
    secrets: MutableMap<String, Pair<Long, TextFieldValue>>,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    when (node) {
        is Scroll -> SettingsContent(node.content(), workspace, secrets, text, emit)
        is Surface -> SettingsContent(node.content(), workspace, secrets, text, emit)
        is Container -> if (node.layout() == ContainerLayout.COLUMN) {
            val sectionTitle = node.children().filterIsInstance<Text>().firstOrNull { it.id() == node.id() + ".title" }
            val sectionHelp = node.children().filterIsInstance<Text>().firstOrNull { it.id() == node.id() + ".help" }
            Column(Modifier.fillMaxWidth()) {
                node.children().filter { it != sectionHelp || sectionTitle == null }.forEach { child -> key(child.id()) {
                    if (child == sectionTitle && sectionHelp != null) Box(Modifier.padding(vertical = 10.dp)) {
                        SettingsFieldLabel(text(sectionTitle.text()), text(sectionHelp.text()), sectionTitle.id())
                    } else SettingsContent(child, workspace, secrets, text, emit)
                } }
            }
        } else ComposeDesktopUiNodeRenderer.Render(node, text, emit, Modifier.fillMaxWidth())
        is Group -> {
            var expanded by rememberSaveable(node.id()) { mutableStateOf(!node.collapsible()) }
            LaunchedEffect(workspace.locatedRow()) {
                if (descendants(node).filterIsInstance<Form>().any { form -> form.rows().any { it.id() == workspace.locatedRow() } }) expanded = true
            }
            Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
                if (node.collapsible()) CupertinoButton({ expanded = !expanded }, contentPadding = PaddingValues(vertical = 10.dp),
                    colors = CupertinoButtonDefaults.plainButtonColors(contentColor = palette.secondaryText)) {
                    CupertinoText((if (expanded) "▾  " else "▸  ") + text(node.title()), fontSize = 13.sp)
                } else CupertinoText(text(node.title()), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 7.dp).semantics { heading() })
                androidx.compose.animation.AnimatedVisibility(expanded,
                    enter = androidx.compose.animation.fadeIn(tween(140)) + androidx.compose.animation.expandVertically(spring(dampingRatio = 1f)),
                    exit = androidx.compose.animation.fadeOut(tween(100)) + androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f))) {
                    SettingsContent(node.content(), workspace, secrets, text, emit)
                }
            }
        }
        is Form -> node.rows().forEach { row -> key(row.id()) { SettingsRow(row, workspace, secrets, text, emit) } }
        is Text -> CupertinoText(text(node.text()), fontSize = if (node.style() == TextStyle.HEADING) 14.sp else 12.sp,
            color = palette.secondaryText, modifier = Modifier.padding(vertical = 8.dp))
        is Button -> Box(Modifier.padding(vertical = 8.dp)) {
            SettingsButton(node, text) { emit(Event(EventType.ACTIVATE, node.id(), Value.empty())) }
        }
        else -> ComposeDesktopUiNodeRenderer.Render(node, text, emit, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SettingsRow(
    row: FormRow,
    workspace: SettingsWorkspace,
    secrets: MutableMap<String, Pair<Long, TextFieldValue>>,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val requester = remember { BringIntoViewRequester() }
    val located = remember { Animatable(0f) }
    val control = row.content()
    val rowTargets = listOf(row.id()) + descendants(control).mapNotNull {
        when (it) { is Toggle -> it.bindingId(); is TextInput -> it.bindingId(); is Choice -> it.bindingId(); else -> null }
    }.map { "$it.row" }.toList()
    val dirty = workspace.changes().any { it.rowId() in rowTargets }
    val invalid = workspace.invalidRow() in rowTargets
    val primaryInput = descendants(control).filterIsInstance<TextInput>().firstOrNull()
    val theme = (control as? Choice)?.takeIf { it.bindingId() == "interface.theme" }
    val textEntry = primaryInput?.inputKind() in listOf(InputKind.TEXT, InputKind.FILE, InputKind.DIRECTORY, InputKind.PASSWORD)
    LaunchedEffect(workspace.locatedRow(), invalid) {
        if (workspace.locatedRow() in rowTargets) {
            requester.bringIntoView()
            located.snapTo(1f)
            located.animateTo(0f, tween(1400, 200))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().bringIntoViewRequester(requester).testTag(row.id())
        .background(palette.selection.copy(alpha = located.value * .8f), RoundedCornerShape(8.dp))
        .then(if (invalid) Modifier.border(1.dp, palette.error, RoundedCornerShape(8.dp)) else Modifier)) {
        val stacked = theme != null || control is Container || control is Dock ||
            primaryInput?.inputKind() == InputKind.MULTILINE || maxWidth < if (textEntry) 600.dp else 390.dp
        val controlWidth = when {
            control is Toggle -> 44.dp
            textEntry -> (maxWidth * .6f).coerceAtMost(480.dp)
            primaryInput != null -> 112.dp
            else -> 240.dp
        }
        @Composable fun label() {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                val help = listOfNotNull(row.help()?.let(text), (row.trailing() as? Text)?.text()?.let(text))
                    .filter(String::isNotBlank).distinct().joinToString("\n")
                SettingsFieldLabel(text(row.label()), help, row.id() + ".label")
                if (dirty) Box(Modifier.size(5.dp).background(palette.link, RoundedCornerShape(3.dp)).semantics {
                    contentDescription = text(settingsText("modified"))
                })
            }
        }
        @Composable fun input(modifier: Modifier = Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (theme != null) ThemeChoices(theme, text, emit)
                else SettingsControl(control, secrets, text, emit, Modifier.fillMaxWidth())
            }
        }
        Column(Modifier.fillMaxWidth()) {
            if (stacked) Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                label(); input(Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(1f)) { label() }
                input(Modifier.width(controlWidth))
            }
            if (row.trailing() != null && row.trailing() !is Text) ComposeDesktopUiNodeRenderer.Render(row.trailing(), text, emit)
            if (invalid) CupertinoText(text(settingsText("invalid")), color = palette.error, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp).semantics { error(text(settingsText("invalid"))) })
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.separator.copy(alpha = .35f)))
        }
    }
}

@Composable
private fun SettingsControl(node: DesktopUiNode, secrets: MutableMap<String, Pair<Long, TextFieldValue>>, text: (TextToken) -> String, emit: (Event) -> Unit, modifier: Modifier) {
    when {
        node is TextInput && node.inputKind() == InputKind.PASSWORD -> {
            LaunchedEffect(node.stateRevision()) {
                if (secrets[node.id()]?.first != node.stateRevision()) secrets.remove(node.id())
            }
            CupertinoTextField(value = secrets[node.id()]?.takeIf { it.first == node.stateRevision() }?.second ?: TextFieldValue(), onValueChange = {
                secrets[node.id()] = node.stateRevision() to it
                emit(Event(EventType.CHANGE, node.id(), Value.text(it.text)))
            }, enabled = node.enabled(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = modifier.testTag(node.id()).semantics { contentDescription = text(node.label()) })
        }
        node is Toggle -> ComposeDesktopUiNodeRenderer.FormContent(
            Toggle(node.id(), node.bindingId(), node.label(), node.help(), ToggleStyle.SWITCH, node.selected(), node.enabled()), text, emit, modifier)
        node is Dock && descendants(node).any { it is TextInput && it.inputKind() == InputKind.PASSWORD } -> {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                node.center()?.let { SettingsControl(it, secrets, text, emit, Modifier.fillMaxWidth()) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    node.bottom()?.let { ComposeDesktopUiNodeRenderer.Render(it, text, emit) }
                    node.end()?.let { ComposeDesktopUiNodeRenderer.Render(it, text, emit) }
                }
            }
        }
        node is Container -> Column(modifier) {
            node.children().forEach { child ->
                if (child is Toggle) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SettingsFieldLabel(text(child.label()), child.help()?.let(text).orEmpty(), child.id() + ".label") }
                    SettingsControl(child, secrets, text, emit, Modifier.width(44.dp))
                } else SettingsControl(child, secrets, text, emit, Modifier.fillMaxWidth())
            }
        }
        else -> ComposeDesktopUiNodeRenderer.FormContent(node, text, emit, modifier.testTag(node.id()))
    }
}

@Composable
internal fun SettingsFieldLabel(label: String, help: String, tag: String) {
    val palette = LocalExperiencePalette.current
    var hover by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var popupHover by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val dismissEpoch = LocalHintDismiss.current
    LaunchedEffect(dismissEpoch) { visible = false; dismissed = true; popupHover = false }
    LaunchedEffect(hover, focused, popupHover) {
        if (hover || focused || popupHover) {
            if (!dismissed) { if (!visible && !focused) delay(400); visible = true }
        } else { delay(160); visible = false; dismissed = false }
    }
    val opacity by animateFloatAsState(if (visible && help.isNotBlank()) 1f else 0f, tween(140), label = "setting-hint")
    Box {
        CupertinoText(label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag(tag)
                .border(1.dp, if (focused) palette.link else Color.Transparent, RoundedCornerShape(3.dp))
                .onPointerEvent(PointerEventType.Enter) { hover = true; dismissed = false }
                .onPointerEvent(PointerEventType.Exit) { hover = false }
                .onFocusChanged { focused = it.isFocused; if (focused) dismissed = false }
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { visible = false; dismissed = true; popupHover = false; true } else false
                }.semantics { if (help.isNotBlank()) contentDescription = "$label. $help" }
                .focusable(help.isNotBlank()))
        if (visible && help.isNotBlank() || opacity > 0f) Popup(
            popupPositionProvider = remember { object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                    val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    val above = anchorBounds.top - popupContentSize.height - 6
                    val y = (if (above >= 0) above else anchorBounds.bottom + 6)
                        .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                    return IntOffset(x, y)
                }
            } },
            properties = PopupProperties(focusable = false),
        ) {
            CupertinoSurface(Modifier.widthIn(max = 310.dp).graphicsLayer { alpha = opacity }
                .onPointerEvent(PointerEventType.Enter) { popupHover = true }
                .onPointerEvent(PointerEventType.Exit) { popupHover = false }
                .testTag("$tag.hint"), color = palette.surface, shadowElevation = 6.dp, shape = RoundedCornerShape(9.dp)) {
                CupertinoText(help, Modifier.padding(12.dp), fontSize = 12.sp, lineHeight = 19.sp, color = palette.secondaryText)
            }
        }
    }
}

@Composable
private fun ThemeChoices(node: Choice, text: (TextToken) -> String, emit: (Event) -> Unit) {
    val palette = LocalExperiencePalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        node.options().filter { it.id() in setOf("system", "light", "dark") }.forEach { option ->
            val active = option.id() in node.selectedIds()
            Column(Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                .clickable(enabled = node.enabled() && option.enabled(), role = Role.RadioButton) {
                    emit(Event(EventType.SELECTION, node.id(), Value.selection(option.id())))
                }.testTag("settings.theme.${option.id()}").semantics { selected = active }
                .padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val dark = option.id() == "dark"
                val preview = if (dark) Color(0xff222731) else Color.White
                Column(Modifier.fillMaxWidth().height(94.dp).clip(RoundedCornerShape(8.dp)).background(preview)
                    .border(if (active) 2.dp else 1.dp, if (active) palette.link else palette.controlBorder, RoundedCornerShape(8.dp))) {
                    Box(Modifier.fillMaxWidth().height(16.dp).background(if (dark) Color(0xff353b47) else Color(0xffe9edf3)))
                    Row(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxHeight().weight(.28f).background(if (dark) Color(0xff2c3340) else Color(0xfff3f5f8)).padding(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            repeat(3) { Box(Modifier.fillMaxWidth().height(4.dp).background(if (it == 0) palette.link.copy(alpha = .5f) else palette.secondaryText.copy(alpha = .3f))) }
                        }
                        Column(Modifier.weight(.72f).fillMaxHeight().background(if (option.id() == "system") Color(0xff222731) else preview).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.fillMaxWidth(.6f).height(5.dp).background(palette.secondaryText.copy(alpha = .5f)))
                            repeat(2) { Box(Modifier.fillMaxWidth().height(13.dp).background(palette.secondaryText.copy(alpha = .15f), RoundedCornerShape(3.dp))) }
                        }
                    }
                }
                CupertinoText(text(option.label()), fontSize = 12.sp, color = if (active) palette.link else palette.secondaryText,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (node.options().any { it.id() !in setOf("system", "light", "dark") }) {
        ComposeDesktopUiNodeRenderer.FormContent(node, text, emit, Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}
