@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.plugins

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.CupertinoText
import kotlinx.coroutines.launch
import top.sywyar.pixivdownload.guicompose.DesktopIcon
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.PluginEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun PluginOverview(
    node: DesktopUiNode.PluginOverview,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalExperiencePalette.current
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    val searchFocus = remember { FocusRequester() }
    val anchors = remember { mutableMapOf<String, FocusRequester>() }
    var query by rememberSaveable { mutableStateOf("") }
    var attention by rememberSaveable { mutableStateOf(false) }
    var builtInExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreId by remember { mutableStateOf<String?>(null) }
    fun label(key: String, vararg args: String) =
        text(DesktopUiNode.TextToken("gui-compose", "gui.compose.plugins.$key", "", args.toList()))
    fun app(key: String) = text(DesktopUiNode.TextToken.key(key))
    fun code(prefix: String, value: String): String {
        if (value.isBlank()) return label("unknown")
        if (prefix == "gui.plugins.verification." && value == "VERIFIED_COMMUNITY") return label("community-signature")
        val key = prefix + value
        return app(key).takeUnless { it == key }.orEmpty().ifBlank { value }
    }
    fun activate(button: DesktopUiNode.Button) {
        if (button.enabled()) emit(DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE,
            button.id(), DesktopUiNode.Value.empty()))
    }
    val matches = node.plugins().filter { plugin ->
        (!attention || plugin.needsAttention()) && (query.isBlank() ||
            listOf(plugin.name(), plugin.description(), plugin.id()).any { it.contains(query.trim(), ignoreCase = true) })
    }
    val builtIn = matches.filter { it.builtIn() }
    val showBuiltIn = builtInExpanded || query.isNotBlank() || attention
    val shown = matches.filter { !it.builtIn() } + if (showBuiltIn) builtIn else emptyList()
    val selected = node.plugins().find { it.id() == selectedId }
    var lastDetail by remember { mutableStateOf<PluginEntry?>(null) }
    SideEffect { if (selected != null) lastDetail = selected }
    LaunchedEffect(node.plugins()) {
        if (selectedId != null && selected == null) {
            selectedId = null
            searchFocus.requestFocus()
        }
    }
    LaunchedEffect(query, attention) { grid.scrollToItem(0) }
    fun close() { restoreId = selectedId; selectedId = null }

    BoxWithConstraints(modifier.fillMaxSize().testTag("plugins.overview")
        .onPreviewKeyEvent {
            when {
                it.type != KeyEventType.KeyDown -> false
                it.key == Key.Escape && selectedId != null -> { close(); true }
                it.key == Key.F && (it.isCtrlPressed || it.isMetaPressed) -> { searchFocus.requestFocus(); true }
                else -> false
            }
        }) {
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val wide = maxWidth >= 820.dp * scale
        val inset = if (maxWidth < 600.dp) 24.dp else 44.dp
        val detailSpace by animateDpAsState(
            if (wide && selected != null) 320.dp * scale + 24.dp else 0.dp,
            spring(dampingRatio = 1f, stiffness = 650f), label = "plugin-list-width")
        Column(Modifier.fillMaxSize().padding(start = inset, end = inset, top = 32.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).widthIn(min = 160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CupertinoText(app("desktop.ui.page.plugins"), Modifier.semantics { heading() },
                        fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    CupertinoText(if (node.observedAt().isBlank()) label("welcome") else label("summary",
                        node.plugins().size.toString(), node.plugins().count { it.statusCode() == "STARTED" }.toString(),
                        node.plugins().count { it.needsAttention() }.toString()), fontSize = 13.sp, color = palette.secondaryText)
                }
                PluginAction(label("manage"), "plugins.manage", Icons.AutoMirrored.Filled.OpenInNew,
                    enabled = node.manage().enabled()) { activate(node.manage()) }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                BasicTextField(query, { query = it }, singleLine = true, interactionSource = interaction,
                    textStyle = TextStyle(fontSize = 13.sp, color = palette.text), cursorBrush = SolidColor(palette.link),
                    modifier = Modifier.weight(1f, fill = false).widthIn(min = 180.dp, max = 320.dp)
                        .focusRequester(searchFocus).testTag("plugins.search")
                        .semantics { contentDescription = label("search") }
                        .background(palette.secondarySurface, RoundedCornerShape(9.dp))
                        .border(if (focused) 2.dp else 0.dp,
                            if (focused) palette.accent else palette.secondarySurface, RoundedCornerShape(9.dp)),
                    decorationBox = { input ->
                        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DesktopIcon(Icons.Default.Search, null, Modifier.size(16.dp), palette.secondaryText)
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) CupertinoText(label("search"), color = palette.secondaryText, fontSize = 13.sp)
                                input()
                            }
                            if (query.isNotEmpty()) PluginAction(label("clear"), "plugins.clear", Icons.Default.Close, iconOnly = true) {
                                query = ""; searchFocus.requestFocus()
                            }
                        }
                    })
                Row(Modifier.clip(RoundedCornerShape(9.dp)).background(palette.secondarySurface).padding(3.dp)) {
                    listOf(false, true).forEach { needsAttention ->
                        val chosen = attention == needsAttention
                        CupertinoText(label(if (needsAttention) "attention" else "all"),
                            Modifier.testTag(if (needsAttention) "plugins.attention" else "plugins.all")
                                .clip(RoundedCornerShape(7.dp)).background(if (chosen) palette.surface else palette.secondarySurface)
                                .clickable(role = Role.Tab) { attention = needsAttention }
                                .semantics { this.selected = chosen }.padding(horizontal = 12.dp, vertical = 7.dp),
                            fontSize = 12.sp, color = if (chosen) palette.text else palette.secondaryText)
                    }
                }
                PluginAction(label("refresh"), "plugins.refresh", Icons.Default.Refresh, iconOnly = true,
                    enabled = node.refresh().enabled()) { activate(node.refresh()) }
            }
            if (node.recoveryMode()) CupertinoText(app("gui.plugins.recovery"), fontSize = 12.sp, color = palette.warning)
            if (node.noticeKey().isNotBlank()) CupertinoText(app(node.noticeKey()), Modifier.testTag("plugins.notice"),
                fontSize = 13.sp, color = palette.secondaryText)

            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (wide || selected == null) BoxWithConstraints(Modifier.fillMaxSize().padding(end = detailSpace)) {
                    val columns = if (selected == null && maxWidth >= 740.dp * scale) 2 else 1
                    val opacity = remember { Animatable(1f) }
                    LaunchedEffect(columns) { opacity.snapTo(.7f); opacity.animateTo(1f, tween(180)) }
                    LaunchedEffect(restoreId, columns) {
                        restoreId?.let { id ->
                            val index = shown.indexOfFirst { it.id() == id }
                            if (index >= 0) {
                                val itemIndex = index + if (shown[index].builtIn()) 1 else 0
                                if (grid.layoutInfo.visibleItemsInfo.none { it.index == itemIndex })
                                    grid.scrollToItem(itemIndex)
                                withFrameNanos { }
                                anchors[id]?.requestFocus()
                            } else searchFocus.requestFocus()
                            restoreId = null
                        }
                    }
                    if (matches.isEmpty() && node.noticeKey().isBlank()) Column(
                        Modifier.fillMaxSize().testTag("plugins.empty"), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        DesktopIcon(if (query.isNotBlank()) Icons.Default.SearchOff else Icons.Default.CheckCircle,
                            null, Modifier.size(28.dp), palette.secondaryText)
                        Spacer(Modifier.height(12.dp))
                        CupertinoText(label(if (query.isNotBlank()) "no-results" else "no-attention"), fontSize = 13.sp,
                            color = palette.secondaryText)
                    }
                    LazyVerticalGrid(GridCells.Fixed(columns), state = grid,
                        modifier = Modifier.fillMaxSize().testTag("plugins.list").graphicsLayer { alpha = opacity.value },
                        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        fun LazyGridScope.entries(entries: List<PluginEntry>) {
                            items(entries, key = { "plugin:${it.id()}" }) { plugin ->
                                val focus = remember { FocusRequester() }
                                DisposableEffect(plugin.id()) {
                                    anchors[plugin.id()] = focus
                                    onDispose { if (anchors[plugin.id()] === focus) anchors.remove(plugin.id()) }
                                }
                                PluginRow(plugin, selectedId == plugin.id(), ::label,
                                    code("gui.plugins.status.", plugin.statusCode()),
                                    modifier = Modifier.focusRequester(focus).onPreviewKeyEvent {
                                        val step = when (it.key) {
                                            Key.DirectionDown -> columns
                                            Key.DirectionUp -> -columns
                                            Key.DirectionLeft -> -1
                                            Key.DirectionRight -> 1
                                            else -> 0
                                        }
                                        if (it.type == KeyEventType.KeyDown && step != 0) {
                                            val nextIndex = (shown.indexOf(plugin) + step).coerceIn(0, shown.lastIndex)
                                            val next = shown[nextIndex]
                                            scope.launch {
                                                val itemIndex = nextIndex + if (next.builtIn()) 1 else 0
                                                if (grid.layoutInfo.visibleItemsInfo.none { it.index == itemIndex })
                                                    grid.animateScrollToItem(itemIndex)
                                                withFrameNanos { }
                                                anchors[next.id()]?.requestFocus()
                                            }
                                            true
                                        } else false
                                    },
                                ) {
                                    if (selectedId == plugin.id()) close()
                                    else {
                                        if (wide && columns > 1) {
                                            grid.layoutInfo.visibleItemsInfo.find { it.key == "plugin:${plugin.id()}" }?.let {
                                                // Anchor the selected row across the next column remeasurement.
                                                grid.requestScrollToItem(it.index, -it.offset.y)
                                            }
                                        }
                                        selectedId = plugin.id()
                                    }
                                }
                            }
                        }
                        entries(matches.filter { !it.builtIn() })
                        if (builtIn.isNotEmpty()) {
                            item(key = "built-in-section", span = { GridItemSpan(maxLineSpan) }) {
                                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)
                                    .testTag("plugins.built-in").clip(RoundedCornerShape(6.dp))
                                    .clickable(role = Role.Button, enabled = query.isBlank() && !attention) { builtInExpanded = !builtInExpanded }
                                    .semantics { stateDescription = label(if (showBuiltIn) "expanded" else "collapsed") }
                                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    DesktopIcon(if (showBuiltIn) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                        null, Modifier.size(16.dp), palette.secondaryText)
                                    CupertinoText(label("built-in", builtIn.size.toString()), fontSize = 12.sp, color = palette.secondaryText)
                                }
                            }
                            if (showBuiltIn) entries(builtIn)
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                androidx.compose.animation.AnimatedVisibility(selected != null, enter = fadeIn(tween(180)) + expandHorizontally(
                    spring(dampingRatio = 1f, stiffness = 650f), expandFrom = Alignment.End),
                    exit = fadeOut(tween(120)) + shrinkHorizontally(spring(dampingRatio = 1f, stiffness = 650f)),
                    modifier = Modifier.align(Alignment.CenterEnd).then(if (wide) Modifier else Modifier.fillMaxSize())) {
                    (selected ?: lastDetail)?.let { plugin ->
                        AnimatedContent(plugin, contentKey = { it.id() },
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) },
                            label = "plugin-detail") { current ->
                            PluginDetails(current, node.observedAt(), ::label, ::app, ::code,
                                wide, selected != null && node.manage().enabled(),
                                { if (selected != null) activate(node.manage()) }, ::close,
                                if (wide) Modifier.width(320.dp * scale).fillMaxHeight() else Modifier.fillMaxSize())
                        }
                    }
                }
            }
            if (node.observedAt().isNotBlank()) CupertinoText(label("observed", pluginTimestamp(node.observedAt())),
                fontSize = 11.sp, color = palette.secondaryText)
        }
    }
}

@Composable
private fun PluginRow(plugin: PluginEntry, selected: Boolean, label: (String, Array<out String>) -> String,
                      status: String, modifier: Modifier, open: () -> Unit) {
    val palette = LocalExperiencePalette.current
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focusManager = LocalFocusManager.current
    val background by animateColorAsState(when {
        selected -> palette.selection
        pressed || hover -> palette.secondarySurface
        else -> palette.surface
    }, tween(120))
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("plugins.row.${plugin.id()}")
        .clip(shape).background(background)
        .border(if (focused) 2.dp else 0.dp, if (focused) palette.accent else background, shape)
        .onPointerEvent(PointerEventType.Press) { focusManager.clearFocus() }
        .clickable(interaction, indication = null, role = Role.Button, onClick = open)
        .semantics { this.selected = selected }
        .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        PluginSymbol(plugin)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CupertinoText(plugin.name(), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CupertinoText(plugin.description().ifBlank { plugin.id() }, fontSize = 11.sp,
                color = palette.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.widthIn(max = 116.dp), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CupertinoText(status, fontSize = 11.sp, maxLines = 2,
                color = if (plugin.runtimeFailed()) palette.error else palette.secondaryText)
            if (plugin.verificationFailed() || plugin.verificationStatus() in listOf("UNVERIFIED_LOCAL", "UNSIGNED_ALLOWED"))
                CupertinoText(label(if (plugin.verificationFailed()) "verification-issue" else "unverified", emptyArray()),
                    fontSize = 10.sp, color = if (plugin.verificationFailed()) palette.warning else palette.secondaryText)
        }
    }
}

@Composable
private fun PluginDetails(plugin: PluginEntry, observed: String, label: (String, Array<out String>) -> String,
                          app: (String) -> String, code: (String, String) -> String, wide: Boolean,
                          canManage: Boolean, manage: () -> Unit, close: () -> Unit, modifier: Modifier) {
    val palette = LocalExperiencePalette.current
    var technical by rememberSaveable(plugin.id()) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(plugin.id()) { closeFocus.requestFocus() }
    fun l(key: String) = label(key, emptyArray())
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(palette.secondarySurface)
        .testTag("plugins.detail").padding(20.dp)) {
        Row(Modifier.fillMaxWidth().testTag("plugins.detail.header"),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PluginSymbol(plugin, large = true)
            PluginAction(l(if (wide) "close" else "back"), "plugins.close",
                if (wide) Icons.Default.Close else Icons.Default.ChevronLeft, iconOnly = wide,
                modifier = Modifier.focusRequester(closeFocus), onClick = close)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 20.dp, end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                CupertinoText(plugin.name(), Modifier.testTag("plugins.detail.title").semantics { heading() },
                    fontSize = 23.sp, fontWeight = FontWeight.Bold)
                CupertinoText(plugin.description().ifBlank { l("no-description") }, fontSize = 13.sp, color = palette.secondaryText)
                DetailFact(l("runtime"), code("gui.plugins.status.", plugin.statusCode()), plugin.runtimeFailed())
                DetailFact(l("verification"), code("gui.plugins.verification.", plugin.verificationStatus()), plugin.verificationFailed())
                CupertinoText(l(when {
                    plugin.verificationFailed() -> "verification-help"
                    plugin.verificationStatus() in listOf("UNVERIFIED_LOCAL", "UNSIGNED_ALLOWED") -> "unverified-help"
                    plugin.verificationStatus().startsWith("VERIFIED_") -> "verified-help"
                    else -> "unknown-help"
                }), fontSize = 12.sp, color = palette.secondaryText)
                DetailFact(l("version"), plugin.version().ifBlank { l("unknown") })
                DetailFact(l("source"), code("gui.plugins.source.", plugin.source()))
                if (plugin.required()) CupertinoText(app("gui.plugins.tag.required"), fontSize = 12.sp, color = palette.secondaryText)
                PluginAction(l("technical"), "plugins.technical",
                    if (technical) Icons.Default.ExpandMore else Icons.Default.ChevronRight) { technical = !technical }
                AnimatedVisibility(technical, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            DetailFact(l("identifier"), plugin.id())
                            if (plugin.managed() && plugin.phaseCode().isNotBlank()) DetailFact(l("phase"), code("gui.plugins.phase.", plugin.phaseCode()))
                            if (plugin.verificationDiagnosticCode().isNotBlank()) DetailFact(l("diagnostic"), plugin.verificationDiagnosticCode())
                            if (plugin.lastVerifiedAt().isNotBlank()) DetailFact(l("verified-at"), pluginTimestamp(plugin.lastVerifiedAt()))
                            if (observed.isNotBlank()) DetailFact(l("observed-at"), pluginTimestamp(observed))
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        Spacer(Modifier.height(16.dp))
        PluginAction(l("manage"), "plugins.detail.manage", Icons.AutoMirrored.Filled.OpenInNew, enabled = canManage, onClick = manage)
    }
}

@Composable
private fun DetailFact(label: String, value: String, error: Boolean = false) {
    val palette = LocalExperiencePalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CupertinoText(label, fontSize = 11.sp, color = palette.secondaryText)
        CupertinoText(value, fontSize = 13.sp, color = if (error) palette.error else palette.text)
    }
}

@Composable
private fun PluginSymbol(plugin: PluginEntry, large: Boolean = false) {
    val palette = LocalExperiencePalette.current
    val tint = palette.link
    val icon = when (plugin.iconKey().trim().lowercase(java.util.Locale.ROOT)) {
        "download", "arrow-down-tray" -> Icons.Default.Download
        "image", "images", "photo", "gallery" -> Icons.Default.PhotoLibrary
        "book", "book-open" -> Icons.AutoMirrored.Filled.MenuBook
        "mail", "envelope" -> Icons.Default.MailOutline
        "bell", "notification" -> Icons.Default.NotificationsNone
        "language", "translate" -> Icons.Default.Translate
        "sparkles", "wand-magic-sparkles" -> Icons.Default.AutoAwesome
        "audio-lines", "volume", "volume-high", "headphones", "headphone" -> Icons.Default.Headphones
        "chart", "bar-chart" -> Icons.Default.BarChart
        "palette", "monitor" -> Icons.Default.DesktopWindows
        "shield", "shield-check" -> Icons.Default.Security
        "tool", "wrench", "tools", "screwdriver-wrench" -> Icons.Default.Build
        "settings", "gear" -> Icons.Default.Settings
        else -> Icons.Default.Extension
    }
    Box(Modifier.size(if (large) 48.dp else 34.dp)
        .background(tint.copy(alpha = .09f), RoundedCornerShape(if (large) 13.dp else 9.dp)),
        contentAlignment = Alignment.Center) {
        DesktopIcon(icon, null, Modifier.size(if (large) 25.dp else 18.dp), tint)
    }
}

@Composable
private fun PluginAction(label: String, tag: String, icon: ImageVector, iconOnly: Boolean = false,
                         enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalExperiencePalette.current
    Row(modifier.testTag(tag).clip(RoundedCornerShape(7.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DesktopIcon(icon, null, Modifier.size(16.dp), if (enabled) palette.link else palette.secondaryText)
        if (!iconOnly) CupertinoText(label, fontSize = 12.sp, color = if (enabled) palette.link else palette.secondaryText)
    }
}

private fun pluginTimestamp(raw: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.parse(raw))
}.getOrDefault(raw)
