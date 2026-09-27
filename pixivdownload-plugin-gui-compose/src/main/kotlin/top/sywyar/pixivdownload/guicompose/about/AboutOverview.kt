@file:OptIn(
    io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@file:Suppress("DEPRECATION")

package top.sywyar.pixivdownload.guicompose.about

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.robinpcrd.cupertino.*
import kotlinx.coroutines.delay
import top.sywyar.pixivdownload.guicompose.DesktopIcon
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*

internal fun aboutText(text: (TextToken) -> String, key: String): String =
    text(TextToken("gui-compose", "gui.compose.about.$key", "", emptyList()))

internal fun platformReport(node: AboutOverview, text: (TextToken) -> String): String {
    fun line(value: String) = value.replace('\r', ' ').replace('\n', ' ')
    return buildString {
        appendLine("### ${aboutText(text, "technical")}")
        appendLine()
        appendLine(line(node.applicationName()))
        appendLine()
        node.facts().forEach { appendLine("- ${line(text(it.label()))}: ${line(text(it.value()))}") }
    }.trimEnd()
}

@Composable
internal fun AboutOverview(
    node: AboutOverview,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    modifier: Modifier = Modifier,
    render: @Composable (DesktopUiNode, Modifier) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    fun label(key: String) = aboutText(text, key)
    fun activate(id: String) = emit(Event(EventType.ACTIVATE, id, Value.empty()))
    val scroll = rememberScrollState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var reader by rememberSaveable { mutableStateOf("") }
    val disclaimerFocus = remember { FocusRequester() }
    val licenseFocus = remember { FocusRequester() }
    var lastReader by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    var copyState by remember { mutableStateOf("") }
    var copyRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(copyRevision) {
        if (copyState == "copied") { delay(3000); copyState = "" }
    }
    Box(modifier.fillMaxSize().testTag(node.id())) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val inset = if (maxWidth < 600.dp) 20.dp else 40.dp
            val top = if (maxHeight < 700.dp) 28.dp else 54.dp
            Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.widthIn(max = 800.dp).fillMaxWidth().padding(horizontal = inset).padding(top = top, bottom = 36.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        node.icon()?.let { render(it, Modifier.padding(bottom = 18.dp)) }
                        CupertinoText(
                            node.applicationName(),
                            Modifier.fillMaxWidth().semantics { heading() },
                            fontSize = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        CupertinoText(
                            text(TextToken.key("desktop.ui.about.description")),
                            Modifier.fillMaxWidth().padding(top = 11.dp),
                            fontSize = 14.sp,
                            color = palette.secondaryText,
                            textAlign = TextAlign.Center
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            CupertinoText(
                                text(TextToken("", "gui.about.version", "", listOf(node.version()))),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            AnimatedContent(
                                node.updateState(),
                                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                                modifier = Modifier.heightIn(min = 20.dp).testTag("about.update.status")
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            ) { state ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (state == AboutUpdateState.CHECKING) CupertinoActivityIndicator(Modifier.size(13.dp))
                                    if (state == AboutUpdateState.CURRENT) DesktopIcon(Icons.Default.Check, null, Modifier.size(14.dp), tint = palette.success)
                                    CupertinoText(
                                        label("update.${state.name.lowercase(java.util.Locale.ROOT)}"),
                                        fontSize = 12.sp,
                                        color = when (state) {
                                            AboutUpdateState.ERROR -> palette.error
                                            AboutUpdateState.CURRENT -> palette.success
                                            AboutUpdateState.AVAILABLE -> palette.link
                                            else -> palette.secondaryText
                                        }
                                    )
                                }
                            }
                        }
                        AboutButton(
                            if (node.updateState() == AboutUpdateState.CHECKING) label("checking")
                            else if (node.updateState() == AboutUpdateState.ERROR) label("retry")
                            else text(node.checkUpdate().label()),
                            node.checkUpdate().id(),
                            node.checkUpdate().enabled(),
                        ) { activate(node.checkUpdate().id()) }
                    }
                    for (update in node.updates()) {
                        Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                            render(if (update is Surface) update.content() else update, Modifier.fillMaxWidth())
                        }
                    }
                    AboutDivider()
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val icons = listOf(Icons.Default.Code, Icons.Default.MenuBook, Icons.Default.FileDownload)
                        node.links().forEachIndexed { index, link ->
                            AboutAction(link.id(), link.enabled(), { activate(link.id()) }) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    DesktopIcon(icons.getOrElse(index) { Icons.Default.Link }, null, Modifier.size(17.dp), tint = palette.link)
                                    CupertinoText(text(link.label()), fontSize = 13.sp, color = palette.link)
                                    DesktopIcon(Icons.Default.OpenInNew, null, Modifier.size(12.dp), tint = palette.secondaryText)
                                }
                            }
                        }
                    }
                    Column(Modifier.padding(top = 32.dp, bottom = 29.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CupertinoText(
                            text(TextToken.key("desktop.ui.about.contributors.title")),
                            Modifier.semantics { heading() },
                            fontSize = 12.sp,
                            color = palette.secondaryText
                        )
                        if (node.maintainers().isEmpty()) CupertinoText(
                            text(TextToken.key("desktop.ui.about.maintainers.load-failed")),
                            fontSize = 12.sp,
                            color = palette.secondaryText
                        )
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val twoColumns = maxWidth >= 480.dp
                            val width = if (twoColumns) (maxWidth - 16.dp) / 2 else maxWidth
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                node.maintainers().forEach { person ->
                                    AboutAction(person.link().id(), person.link().enabled(), { activate(person.link().id()) }, Modifier.width(width)) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            render(person.avatar(), Modifier)
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                CupertinoText(text(person.link().label()), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                                CupertinoText(text(person.role()), fontSize = 12.sp, color = palette.secondaryText)
                                            }
                                            DesktopIcon(Icons.Default.OpenInNew, null, Modifier.size(13.dp), tint = palette.secondaryText)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AboutDivider()
                    AboutInfoRow(
                        text(TextToken.key("gui.about.disclaimer.title")),
                        Icons.Default.Shield,
                        "about.disclaimer",
                        Modifier.focusRequester(disclaimerFocus)
                    ) { lastReader = "disclaimer"; reader = lastReader }
                    AboutInfoRow(
                        label("license"),
                        Icons.Default.Description,
                        "about.license",
                        Modifier.focusRequester(licenseFocus),
                        detail = "GNU AGPL v3.0"
                    ) {
                        lastReader = "license"; reader = lastReader
                    }
                    AboutInfoRow(
                        label("technical"),
                        Icons.Default.Code,
                        "about.platform.toggle",
                        Modifier.semantics { stateDescription = text(TextToken(
                            "gui-compose",
                            if (expanded) "gui.compose.expanded" else "gui.compose.collapsed",
                            "",
                            emptyList()
                        )) },
                        expanded = expanded
                    ) { expanded = !expanded }
                    AnimatedVisibility(
                        expanded,
                        enter = expandVertically(tween(260)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(240)) + fadeOut(tween(130))
                    ) {
                        Column(Modifier.fillMaxWidth().padding(top = 19.dp, bottom = 10.dp).testTag("about.platform")) {
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    node.facts().forEach { fact ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                            CupertinoText(
                                                text(fact.label()),
                                                Modifier.widthIn(max = 160.dp).weight(.38f),
                                                fontSize = 12.sp,
                                                color = palette.secondaryText
                                            )
                                            CupertinoText(text(fact.value()), Modifier.weight(.62f).testTag("about.platform.${fact.id()}"), fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            Row(
                                Modifier.padding(top = 19.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AboutButton(label("copy"), "about.platform.copy", icon = Icons.Default.ContentCopy) {
                                    copyState = try { clipboard.setText(AnnotatedString(platformReport(node, text))); "copied" } catch (_: Exception) { "copy-failed" }
                                    copyRevision++
                                }
                            }
                            AnimatedContent(copyState, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }) { state ->
                                if (state.isNotEmpty()) CupertinoText(
                                    label(state),
                                    Modifier.padding(top = 9.dp).testTag("about.platform.copy-status").semantics { liveRegion = LiveRegionMode.Polite },
                                    fontSize = 12.sp,
                                    color = if (state == "copy-failed") palette.error else palette.secondaryText
                                )
                            }
                        }
                    }
                    CupertinoText(label("thanks"), Modifier.padding(top = 22.dp), fontSize = 11.sp, color = palette.secondaryText)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    AboutReader(
        reader,
        { reader = "" },
        {
        if (lastReader == "disclaimer") disclaimerFocus.requestFocus() else licenseFocus.requestFocus()
    },
        node,
        text
    )
}

@Composable
private fun AboutDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(LocalExperiencePalette.current.separator.copy(alpha = .5f)))

@Composable
private fun AboutAction(
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val background by animateColorAsState(if (hovered && enabled) palette.secondarySurface else Color.Transparent, tween(120))
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier.testTag(tag).background(background, shape)
        .border(2.dp, if (focused && keyboard) palette.link else Color.Transparent, shape)
        .graphicsLayer { alpha = if (enabled) 1f else .5f }
        .hoverable(interactions).clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) { content() }
}

@Composable
private fun AboutButton(
    label: String,
    tag: String,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val palette = LocalExperiencePalette.current
    CupertinoButton(
        onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 36.dp).testTag(tag),
        shape = RoundedCornerShape(9.dp),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp),
        colors = CupertinoButtonDefaults.filledButtonColors(containerColor = palette.secondarySurface, contentColor = palette.link)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) DesktopIcon(icon, null, Modifier.size(15.dp))
            CupertinoText(label, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AboutInfoRow(
    title: String,
    icon: ImageVector,
    tag: String,
    modifier: Modifier = Modifier,
    detail: String = "",
    expanded: Boolean? = null,
    onClick: () -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val angle by animateFloatAsState(if (expanded == true) 90f else 0f, tween(220))
    AboutAction(tag, onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 57.dp).padding(horizontal = 2.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DesktopIcon(icon, null, Modifier.size(18.dp), tint = palette.secondaryText)
            CupertinoText(title, Modifier.weight(1f), fontSize = 14.sp)
            if (detail.isNotBlank()) CupertinoText(detail, fontSize = 12.sp, color = palette.secondaryText)
            DesktopIcon(
                Icons.Default.ChevronRight,
                null,
                Modifier.size(15.dp).graphicsLayer { rotationZ = angle },
                tint = palette.secondaryText
            )
        }
    }
    AboutDivider()
}

@Composable
private fun AboutReader(
    panel: String,
    close: () -> Unit,
    onClosed: () -> Unit,
    node: AboutOverview,
    text: (TextToken) -> String,
) {
    val palette = LocalExperiencePalette.current
    val visible = remember { MutableTransitionState(false) }
    var retained by remember { mutableStateOf("") }
    LaunchedEffect(panel) { if (panel.isNotEmpty()) retained = panel; visible.targetState = panel.isNotEmpty() }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && retained.isNotEmpty()) { retained = ""; onClosed() }
    }
    if (visible.currentState || visible.targetState) Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false, scrimColor = Color.Black.copy(alpha = .18f)),
    ) {
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { closeFocus.requestFocus() }
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            val availableHeight = maxHeight
            AnimatedVisibility(
                visible,
                enter = fadeIn(tween(180)) + scaleIn(tween(230), initialScale = .985f),
                exit = fadeOut(tween(140)) + scaleOut(tween(180), targetScale = .985f)
            ) {
                val license = retained == "license"
                val title = if (license) aboutText(text, "license") else text(TextToken.key("gui.about.disclaimer.title"))
                Column(Modifier.widthIn(max = 660.dp).fillMaxWidth().heightIn(max = availableHeight)
                    .background(palette.surface, RoundedCornerShape(22.dp)).testTag("about.reader")
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false
                    }) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 28.dp, end = 18.dp, top = 24.dp, bottom = 21.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CupertinoText(title, Modifier.semantics { heading(); paneTitle = title }, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            CupertinoText(
                                if (license) "GNU Affero General Public License · Version 3" else aboutText(text, "disclaimer-intro"),
                                fontSize = 12.sp,
                                color = palette.secondaryText
                            )
                        }
                        CupertinoIconButton(close, modifier = Modifier.focusRequester(closeFocus).testTag("about.reader.close")) {
                            DesktopIcon(Icons.Default.Close, aboutText(text, "close"), tint = palette.secondaryText)
                        }
                    }
                    val scroll = rememberScrollState()
                    Box(Modifier.weight(1f, fill = false)) {
                        SelectionContainer {
                            CupertinoText(
                                if (license) node.license() else text(node.disclaimer()),
                                Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 28.dp).padding(bottom = 25.dp)
                                    .testTag("about.reader.content"),
                                fontSize = if (license) 12.sp else 14.sp,
                                lineHeight = if (license) 22.sp else 26.sp,
                                fontFamily = if (license) FontFamily.Monospace else FontFamily.Default,
                                color = if (license) palette.secondaryText else palette.text
                            )
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.matchParentSize().wrapContentWidth(Alignment.End))
                    }
                    AboutDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                        AboutButton(aboutText(text, "done"), "about.reader.done", onClick = close)
                    }
                }
            }
        }
    }
}
