@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package top.sywyar.pixivdownload.guicompose.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import io.github.robinpcrd.cupertino.*
import io.github.robinpcrd.cupertino.theme.CupertinoTheme
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*

internal fun settingsText(key: String, vararg arguments: Any) = TextToken(
    GuiComposePlugin.ID, "gui.compose.settings.$key", "", arguments.map { it.toString() },
)

internal fun descendants(node: DesktopUiNode): Sequence<DesktopUiNode> =
    sequenceOf(node) + node.childNodes().asSequence().flatMap(::descendants)

@Composable
internal fun SettingsWorkspace(
    node: DesktopUiNode.SettingsWorkspace,
    text: (TextToken) -> String,
    emit: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalExperiencePalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var review by rememberSaveable { mutableStateOf(false) }
    var maintenance by remember { mutableStateOf(false) }
    var categoriesExpanded by remember { mutableStateOf(false) }
    var hintDismiss by remember { mutableIntStateOf(0) }
    val searchFocus = remember { FocusRequester() }
    val reviewFocus = remember { FocusRequester() }
    val categoryStates = rememberSaveableStateHolder()
    val secrets = remember(node.credentialRevision()) { mutableStateMapOf<String, Pair<Long, TextFieldValue>>() }
    val footer = node.footer().flatMap { descendants(it).toList() }
    val buttons = footer.filterIsInstance<Button>().associateBy { it.id() }
    val selected = node.tabs().firstOrNull { it.id() in node.categories().selectedIds() } ?: node.tabs().first()
    val changedRows = node.changes().map { it.rowId() }.toSet()
    val categoryChanges = node.locations().filter { it.rowId() in changedRows }.groupingBy { it.categoryId() }.eachCount()
    val save = buttons.getValue("config.save")
    fun activate(button: Button) { if (button.enabled()) emit(Event(EventType.ACTIVATE, button.id(), Value.empty())) }
    fun select(id: String) {
        query = ""
        emit(Event(EventType.SELECTION, node.categories().id(), Value.selection(id)))
    }
    val results = remember(query, node.locations(), node.tabs(), text) {
        searchSettings(query, node.locations(), node.tabs(), text)
    }
    CompositionLocalProvider(LocalHintDismiss provides hintDismiss) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("settings.workspace").onPreviewKeyEvent {
        when {
            it.type == KeyEventType.KeyDown && (it.isCtrlPressed || it.isMetaPressed) && it.key == Key.K -> {
                searchFocus.requestFocus(); true
            }
            it.type == KeyEventType.KeyDown && (it.isCtrlPressed || it.isMetaPressed) && it.key == Key.S -> {
                activate(save); true
            }
            else -> false
        }
    }.onKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
            query = ""
            hintDismiss++
            true
        } else false
    }) {
        val narrow = maxWidth < 680.dp
        val compact = maxHeight < 600.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 20.dp else 38.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(
                    top = if (compact) 12.dp else 26.dp,
                    bottom = if (compact) 12.dp else 28.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    CupertinoText(
                        text(TextToken(null, "desktop.ui.page.settings", "", emptyList())),
                        fontSize = if (compact) 22.sp else 30.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    CupertinoText(text(settingsText("subtitle")), fontSize = 13.sp, color = palette.secondaryText)
                }
                Box {
                    CupertinoIconButton({ maintenance = !maintenance }, modifier = Modifier.testTag("settings.maintenance")) {
                        DesktopIcon(Icons.Default.MoreHoriz, text(settingsText("maintenance")), tint = palette.secondaryText)
                    }
                    CupertinoDropdownMenu(maintenance, { maintenance = false }) {
                        listOf("config.open", "config.reset", "config.reload").mapNotNull(buttons::get).forEach { button ->
                            MenuPickerAction(title = { CupertinoText(text(button.label()), fontSize = 13.sp) },
                                onClick = { maintenance = false; activate(button) }, enabled = button.enabled(), isSelected = false)
                        }
                    }
                }
            }
            @Composable fun navigation(mod: Modifier) {
                Column(mod, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    @Composable fun search(searchModifier: Modifier) {
                        Box(searchModifier.focusRequester(searchFocus)) {
                            CupertinoTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                placeholder = { CupertinoText(text(settingsText("search")), fontSize = 12.sp, color = palette.secondaryText) },
                                leadingIcon = { DesktopIcon(Icons.Default.Search, null, tint = palette.secondaryText) },
                                modifier = Modifier.fillMaxWidth().testTag("settings.search")
                                    .semantics { contentDescription = text(settingsText("search")) },
                            )
                        }
                    }
                    if (narrow && compact) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                search(Modifier.fillMaxWidth())
                            }
                            Box(Modifier.weight(1f)) {
                                CupertinoButton(
                                    { categoriesExpanded = true },
                                    modifier = Modifier.fillMaxWidth().testTag("settings.categories"),
                                    colors = CupertinoButtonDefaults.plainButtonColors(contentColor = palette.link),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                ) {
                                    CupertinoText(text(selected.title()), fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    CategoryChanges(selected.id(), categoryChanges[selected.id()] ?: 0, text)
                                    DesktopIcon(Icons.Default.ExpandMore, null, Modifier.size(16.dp))
                                }
                                CupertinoDropdownMenu(categoriesExpanded, { categoriesExpanded = false }) {
                                    node.tabs().forEach { tab ->
                                        MenuPickerAction(
                                            title = {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    CupertinoText(text(tab.title()), fontSize = 13.sp)
                                                    CategoryChanges(tab.id(), categoryChanges[tab.id()] ?: 0, text)
                                                }
                                            },
                                            onClick = { categoriesExpanded = false; select(tab.id()) },
                                            isSelected = tab.id() == selected.id(),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        search(Modifier.fillMaxWidth())
                        if (narrow) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                node.tabs().forEach { tab -> Category(tab, tab.id() == selected.id(), categoryChanges[tab.id()] ?: 0, text, { select(tab.id()) }) }
                            }
                        } else {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                node.tabs().forEach { tab -> Category(tab, tab.id() == selected.id(), categoryChanges[tab.id()] ?: 0, text, { select(tab.id()) }, stretch = true) }
                            }
                        }
                    }
                }
            }
            @Composable fun content(mod: Modifier) {
                Column(mod) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (query.isNotBlank()) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings.results")) {
                                CupertinoText(text(settingsText("results", results.size)), fontSize = 13.sp, color = palette.secondaryText,
                                    modifier = Modifier.padding(bottom = 18.dp))
                                results.forEach { match ->
                                    val result = match.location
                                    CupertinoButton(onClick = { query = ""; activate(result.locate()) },
                                        modifier = Modifier.fillMaxWidth().testTag(result.locate().id()),
                                        colors = CupertinoButtonDefaults.plainButtonColors(contentColor = palette.text),
                                        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 8.dp)) {
                                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            CupertinoText(text(result.label()), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                            CupertinoText(text(node.tabs().first { it.id() == result.categoryId() }.title()),
                                                fontSize = 12.sp, color = palette.secondaryText)
                                            if (match.helpMatched) result.help()?.let { help ->
                                                CupertinoText(text(help), fontSize = 12.sp, color = palette.secondaryText)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            categoryStates.SaveableStateProvider(selected.id()) {
                                SettingsCategory(selected, node, secrets, text, emit)
                            }
                        }
                    }
                    val notice = footer.filterIsInstance<Text>().firstOrNull { it.id() == "config.notice" || it.id() == "config.notice.plugin" }
                    AnimatedVisibility(node.changes().isNotEmpty() || notice != null,
                        enter = fadeIn(tween(160)) + expandVertically(spring(dampingRatio = 1f)),
                        exit = fadeOut(tween(120)) + shrinkVertically(spring(dampingRatio = 1f))) {
                        Column(Modifier.fillMaxWidth().testTag("settings.savebar")) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.separator.copy(alpha = .45f)))
                            if (notice != null) CupertinoText(text(notice.text()), fontSize = 12.sp,
                                color = if (node.invalidRow().isNotEmpty()) palette.error else palette.secondaryText,
                                modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
                            if (node.changes().isNotEmpty()) {
                                FlowRow(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f).widthIn(min = 170.dp).align(Alignment.CenterVertically)) {
                                        CupertinoText(text(settingsText("unsaved", node.changes().size)), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        footer.filterIsInstance<Text>().firstOrNull { it.id() == "settings.impact.effect" }?.let {
                                            CupertinoText(text(it.text()), fontSize = 11.sp, color = palette.secondaryText)
                                        }
                                    }
                                    CupertinoButton({ review = true }, enabled = save.enabled(), modifier = Modifier.focusRequester(reviewFocus).testTag("settings.review"),
                                        colors = CupertinoButtonDefaults.plainButtonColors(contentColor = palette.link)) {
                                        CupertinoText(text(settingsText("review")), fontSize = 12.sp)
                                    }
                                    buttons["config.discard"]?.let { button -> SettingsButton(button, text) { activate(button) } }
                                    SettingsButton(save, text, primary = true) { activate(save) }
                                }
                            } else Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
            if (narrow) {
                navigation(Modifier.fillMaxWidth().padding(bottom = if (compact) 10.dp else 20.dp))
                content(Modifier.weight(1f).fillMaxWidth())
            } else Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                navigation(Modifier.width(200.dp).fillMaxHeight().padding(bottom = 24.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    content(Modifier.widthIn(max = 800.dp).fillMaxWidth().fillMaxHeight())
                }
            }
        }
    }
    }
    SettingsReview(review, node.changes(), text, { review = false }, { reviewFocus.requestFocus() })
}

@Composable
private fun Category(tab: Tab, active: Boolean, changed: Int, text: (TextToken) -> String, select: () -> Unit, stretch: Boolean = false) {
    val palette = LocalExperiencePalette.current
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(active) { if (active) requester.bringIntoView() }
    CupertinoButton(select,
        colors = CupertinoButtonDefaults.plainButtonColors(contentColor = if (active) palette.link else palette.text),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = (if (stretch) Modifier.fillMaxWidth() else Modifier).bringIntoViewRequester(requester).background(if (active) palette.selection else androidx.compose.ui.graphics.Color.Transparent,
            RoundedCornerShape(9.dp)).semantics { selected = active }.testTag("settings.category.${tab.id()}")) {
        Row(if (stretch) Modifier.fillMaxWidth() else Modifier, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopIcon(categoryIcon(tab.id()), null, tint = if (active) palette.link else palette.secondaryText,
                modifier = Modifier.size(17.dp))
            CupertinoText(text(tab.title()), fontSize = 13.sp, modifier = if (stretch) Modifier.weight(1f) else Modifier,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            CategoryChanges(tab.id(), changed, text)
        }
    }
}

@Composable
private fun CategoryChanges(id: String, changed: Int, text: (TextToken) -> String) {
    if (changed > 0) CupertinoText(
        changed.toString(),
        fontSize = 11.sp,
        color = LocalExperiencePalette.current.link,
        modifier = Modifier.testTag("settings.category.$id.changes")
            .semantics { contentDescription = text(settingsText("unsaved", changed)) },
    )
}

internal fun categoryIcon(id: String) = when (id) {
    "interface" -> Icons.Default.Tune
    "download" -> Icons.Default.Download
    "runtime-network" -> Icons.Default.Language
    "access-control" -> Icons.Default.Security
    "automation-maintenance" -> Icons.Default.Schedule
    else -> Icons.Default.Extension
}

@Composable
internal fun SettingsButton(button: Button, text: (TextToken) -> String, primary: Boolean = false, onClick: () -> Unit) {
    val palette = LocalExperiencePalette.current
    CupertinoButton(onClick, enabled = button.enabled(), modifier = Modifier.testTag(button.id()),
        shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        colors = if (primary) CupertinoButtonDefaults.filledButtonColors(containerColor = palette.link, contentColor = palette.onAccent)
        else CupertinoButtonDefaults.grayButtonColors(containerColor = palette.secondarySurface, contentColor = palette.text)) {
        if (primary && !button.enabled()) {
            CupertinoActivityIndicator(Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
        }
        CupertinoText(text(button.label()), fontSize = 13.sp)
    }
}

@Composable
private fun SettingsReview(visible: Boolean, changes: List<SettingChange>, text: (TextToken) -> String, dismiss: () -> Unit, closed: () -> Unit) {
    val transition = remember { MutableTransitionState(false) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { transition.targetState = visible; if (visible) opened = true }
    LaunchedEffect(transition.currentState, transition.isIdle) {
        if (opened && transition.isIdle && !transition.currentState && !visible) { opened = false; closed() }
    }
    if (transition.currentState || transition.targetState) Dialog(onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, usePlatformInsets = false,
            scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .18f))) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            val height = maxHeight
            AnimatedVisibility(transition,
                enter = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 1f, stiffness = 550f), initialScale = .97f),
                exit = fadeOut(tween(120)) + scaleOut(tween(150), targetScale = .98f)) {
                val palette = LocalExperiencePalette.current
                Column(Modifier.widthIn(max = 500.dp).fillMaxWidth().heightIn(max = height)
                    .background(palette.surface, RoundedCornerShape(20.dp)).padding(24.dp).testTag("settings.review.dialog")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CupertinoText(text(settingsText("review")), Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        CupertinoIconButton(dismiss) { DesktopIcon(Icons.Default.Close, text(settingsText("close"))) }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 14.dp)) {
                        changes.forEach { change ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                CupertinoText(text(change.label()), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                if (change.before() != null && change.after() != null) CupertinoText(
                                    "${text(change.before()).ifBlank { "—" }} → ${text(change.after()).ifBlank { "—" }}",
                                    fontSize = 12.sp, color = palette.secondaryText)
                                CupertinoText(text(change.effect()), fontSize = 11.sp, color = palette.secondaryText)
                            }
                        }
                    }
                }
            }
        }
    }
}
