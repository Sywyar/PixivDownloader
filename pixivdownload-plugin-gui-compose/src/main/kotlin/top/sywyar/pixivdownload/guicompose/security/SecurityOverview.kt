@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.security

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.robinpcrd.cupertino.*
import kotlinx.coroutines.delay
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import top.sywyar.pixivdownload.guicompose.tools.ToolIcon
import top.sywyar.pixivdownload.guicompose.tools.ToolNotice
import top.sywyar.pixivdownload.guicompose.tools.ToolPanelLayout

internal fun securityText(text: (TextToken) -> String, key: String, vararg args: String) =
    text(TextToken("gui-compose", "gui.compose.security.$key", "", args.toList()))

@Composable
internal fun SecurityOverview(
    node: SecurityOverview,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalExperiencePalette.current
    fun label(key: String) = securityText(text, key)
    fun activate(name: String) {
        node.actions().firstOrNull { it.id() == "security.$name" && it.enabled() }?.let {
            emit(Event(EventType.ACTIVATE, it.id(), Value.empty()))
        }
    }
    val anchors = remember { mutableMapOf<String, FocusRequester>() }
    var previousPanel by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf(false) }
    var observedSuccess by remember { mutableLongStateOf(node.successRevision()) }
    LaunchedEffect(node.successRevision()) {
        if (node.successRevision() != observedSuccess) {
            observedSuccess = node.successRevision()
            toast = true
            delay(5_000)
            toast = false
        }
    }
    val latestClose by rememberUpdatedState({ activate("close") })
    DisposableEffect(Unit) { onDispose { latestClose() } }
    Box(modifier.fillMaxSize().testTag(node.id())) {
        val scroll = rememberScrollState()
        BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(scroll)) {
            val inset = if (maxWidth < 600.dp) 20.dp else 48.dp
            Column(
                Modifier.widthIn(max = 1096.dp).fillMaxWidth().align(Alignment.TopCenter)
                    .padding(horizontal = inset, vertical = 28.dp),
            ) {
                CupertinoText(text(TextToken.key("desktop.ui.page.security")), fontSize = 32.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                CupertinoText(label("subtitle"), Modifier.padding(top = 8.dp, bottom = 38.dp),
                    fontSize = 14.sp, color = palette.secondaryText)
                @Composable fun group(title: String, content: @Composable ColumnScope.() -> Unit) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 30.dp)) {
                        CupertinoText(label(title), Modifier.padding(bottom = 12.dp).semantics { heading() },
                            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.secondaryText)
                        content()
                    }
                }
                @Composable fun row(name: String, icon: ImageVector, description: String, action: String) {
                    val focus = anchors.getOrPut(name) { FocusRequester() }
                    SecurityRow(label("$name.title"), description, label(action), icon,
                        false, !node.busy(), focus, "security.$name") {
                        previousPanel = name
                        toast = false
                        activate(name)
                    }
                }
                group("account-group") {
                    row("password", Icons.Default.Key, label("password.description"), "password.action")
                }
                group("sessions-group") {
                    row("sessions", Icons.Default.Web, label("sessions.description"), "sessions.action")
                    CupertinoText(label("sessions.hint"), Modifier.padding(start = 68.dp, top = 4.dp),
                        fontSize = 11.sp, color = palette.secondaryText)
                }
                group("access-group") {
                    node.navigation().forEach { entry ->
                        key(entry.id()) {
                            val focus = remember { FocusRequester() }
                            SecurityRow(text(entry.label()), entry.help()?.let(text).orEmpty(),
                                text(TextToken("gui-compose", "gui.compose.home.shortcut.open", "", emptyList())),
                                desktopIcon(entry.icon()), true, entry.enabled(), focus, entry.id()) {
                                toast = false
                                emit(Event(EventType.ACTIVATE, entry.id(), Value.empty()))
                            }
                            Box(Modifier.padding(start = 68.dp, end = 12.dp).fillMaxWidth().height(1.dp)
                                .background(palette.controlBorder.copy(alpha = .3f)))
                        }
                    }
                    row("connection", Icons.Default.Lock, label("connection.description"), "connection.action")
                }
                SecurityError(node.notice()?.takeIf { node.panel().isEmpty() }?.let(text))
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        AnimatedVisibility(toast, Modifier.align(Alignment.BottomCenter).padding(20.dp),
            enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
            Box(Modifier.widthIn(max = 520.dp).background(palette.secondarySurface, RoundedCornerShape(12.dp)).padding(16.dp)
                .testTag("security.success").semantics { liveRegion = LiveRegionMode.Polite }) {
                CupertinoText(label(node.successOperation() + ".success"), fontSize = 13.sp, color = palette.text)
            }
        }
    }
    SecuritySheet(node.panel(), !node.busy(), { activate("close") }, {
        anchors[previousPanel]?.requestFocus()
    }) { panel ->
        when (panel) {
            "password" -> SecurityPasswordPanel(node, text, emit)
            "connection" -> SecurityConnectionPanel(node, text, emit)
            "sessions" -> {
                val cancelFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { cancelFocus.requestFocus() }
                ToolPanelLayout(label("sessions.title"), label("sessions.dialog-description"), Icons.Default.Logout,
                    { activate("close") }, label("close"), !node.busy(), "security",
                    footer = {
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecurityButton(label("cancel"), "security.cancel", !node.busy(), modifier = Modifier.focusRequester(cancelFocus)) { activate("close") }
                            SecurityButton(label(if (node.busy()) "working" else "sessions.action"), "security.logout",
                                node.actions().first { it.id() == "security.logout" }.enabled(), primary = true, destructive = true) { activate("logout") }
                        }
                    }) {
                    CupertinoText(label("sessions.confirm"), fontSize = 14.sp)
                    for (key in listOf("sessions.effect-login", "sessions.effect-password", "sessions.effect-download", "sessions.effect-invites")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DesktopIcon(Icons.Default.Check, null, Modifier.size(16.dp), tint = palette.link)
                            CupertinoText(label(key), fontSize = 12.sp, color = palette.secondaryText)
                        }
                    }
                    SecurityError(node.notice()?.let(text))
                }
            }
        }
    }
}

@Composable
private fun SecurityRow(
    title: String,
    description: String,
    action: String,
    icon: ImageVector,
    external: Boolean,
    enabled: Boolean,
    focus: FocusRequester,
    tag: String,
    onClick: () -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scale by animateFloatAsState(if (pressed) .993f else 1f, spring(dampingRatio = 1f, stiffness = 850f))
    val background by animateColorAsState(if (hovered) palette.secondarySurface.copy(alpha = .65f) else Color.Transparent, tween(140))
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 530.dp
        Row(
            Modifier.fillMaxWidth().focusRequester(focus).testTag(tag)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .background(background, shape).border(2.dp, if (focused && keyboard) palette.link else Color.Transparent, shape)
                .hoverable(interaction).clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CupertinoText(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (description.isNotBlank()) CupertinoText(description, fontSize = 12.sp, color = palette.secondaryText)
                if (narrow) CupertinoText(action, fontSize = 12.sp, color = palette.link)
            }
            if (!narrow) CupertinoText(action, fontSize = 12.sp, color = palette.link)
            DesktopIcon(if (external) Icons.Default.OpenInNew else Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = palette.link)
        }
    }
}

@Composable
internal fun SecurityButton(
    label: String,
    tag: String,
    enabled: Boolean,
    primary: Boolean = false,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalExperiencePalette.current
    CupertinoButton(onClick, enabled = enabled, modifier = modifier.heightIn(min = 36.dp).testTag(tag),
        colors = CupertinoButtonDefaults.filledButtonColors(
            containerColor = if (primary) if (destructive) palette.error else palette.link else palette.secondarySurface,
            contentColor = if (primary) palette.onAccent else palette.link),
        shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        CupertinoText(label, fontSize = 13.sp)
    }
}

@Composable
internal fun SecurityError(message: String?) {
    AnimatedVisibility(!message.isNullOrBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val palette = LocalExperiencePalette.current
        Box(Modifier.fillMaxWidth().background(palette.errorSurface, RoundedCornerShape(10.dp)).padding(12.dp)
            .testTag("security.error").semantics { liveRegion = LiveRegionMode.Polite }) {
            CupertinoText(message.orEmpty(), fontSize = 12.sp, color = palette.error)
        }
    }
}

@Composable
private fun SecuritySheet(
    panel: String,
    dismissible: Boolean,
    close: () -> Unit,
    onClosed: () -> Unit,
    content: @Composable (String) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val visible = remember { MutableTransitionState(false) }
    var retained by remember { mutableStateOf("") }
    LaunchedEffect(panel) {
        if (panel.isNotEmpty()) retained = panel
        visible.targetState = panel.isNotEmpty()
    }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && retained.isNotEmpty()) {
            retained = ""
            onClosed()
        }
    }
    if (visible.currentState || visible.targetState) Dialog(
        onDismissRequest = { if (dismissible) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false, scrimColor = Color.Black.copy(alpha = .18f)),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            val available = maxHeight
            AnimatedVisibility(visible,
                enter = fadeIn(tween(180)) + scaleIn(spring(dampingRatio = 1f, stiffness = 500f), initialScale = .97f),
                exit = fadeOut(tween(140)) + scaleOut(tween(180), targetScale = .97f)) {
                Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = available)
                    .background(palette.surface, RoundedCornerShape(23.dp)).testTag("security.sheet")) {
                    content(retained)
                }
            }
        }
    }
}
