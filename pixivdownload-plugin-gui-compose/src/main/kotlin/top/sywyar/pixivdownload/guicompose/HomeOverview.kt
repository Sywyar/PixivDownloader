@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode

@Composable
internal fun HomeOverview(
    node: DesktopUiNode.HomeOverview,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalExperiencePalette.current
    val scroll = rememberScrollState()
    var tasksExpanded by rememberSaveable { mutableStateOf(false) }
    var systemExpanded by rememberSaveable { mutableStateOf(false) }
    var entered by rememberSaveable { mutableStateOf(false) }
    var tip by rememberSaveable { mutableStateOf(HomeTips.keys.randomOrNull()) }
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        hour = LocalTime.now().hour
        while (windowFocused) {
            delay(60_000)
            hour = LocalTime.now().hour
            HomeTips.keys.filter { it != tip }.randomOrNull()?.let { tip = it }
        }
    }
    LaunchedEffect(Unit) { entered = true }
    fun label(suffix: String, vararg args: Any) =
        text(DesktopUiNode.TextToken("gui-compose", "gui.compose.home.$suffix", "", args.map(Any::toString)))
    val shortcuts = node.shortcuts().filter { it.symbol() != "chart-bar" }
    val statistics = node.shortcuts().filter { it.symbol() == "chart-bar" }

    Box(modifier.testTag("home.overview")) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scroll)) {
            val narrow = maxWidth < 620.dp
            Column(
                Modifier.widthIn(max = 1080.dp).fillMaxWidth().align(Alignment.TopCenter)
                    .padding(horizontal = if (narrow) 24.dp else 44.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                HomeEntrance(entered, 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Crossfade(
                                targetState = label(homeGreetingKey(hour)),
                                modifier = Modifier.weight(1f).testTag("home.greeting")
                                    .semantics(mergeDescendants = true) { heading() },
                                animationSpec = tween(180),
                                label = "home-greeting",
                            ) { greeting ->
                                CupertinoText(
                                    text = greeting,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.text,
                                )
                            }
                            if (!narrow) BackendStatus(node, text, Modifier.width(240.dp))
                        }
                        tip?.let { key ->
                            Crossfade(
                                targetState = text(DesktopUiNode.TextToken(HomeTips.NAMESPACE, key, "", emptyList())),
                                modifier = Modifier.testTag("home.tip")
                                    .semantics(mergeDescendants = true) {}
                                    .animateContentSize(tween(250)),
                                animationSpec = tween(300),
                                label = "home-tip",
                            ) { message ->
                                CupertinoText(
                                    text = message,
                                    color = palette.secondaryText,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp,
                                )
                            }
                        }
                        if (narrow) BackendStatus(node, text, Modifier.fillMaxWidth())
                    }
                }
                HomeEntrance(entered, 35) {
                    if (shortcuts.isEmpty()) {
                        CupertinoText(text(DesktopUiNode.TextToken.key("desktop.ui.home.quick-start.empty")),
                            color = palette.secondaryText, fontSize = 13.sp)
                    } else {
                        val columns = if (narrow) 1 else 3
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            shortcuts.chunked(columns).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { shortcut ->
                                        key(shortcut.button().id()) {
                                            HomeShortcut(shortcut, text, emit, Modifier.weight(1f))
                                        }
                                    }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                HomeEntrance(entered, 70) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(label("running"), Modifier.weight(1f))
                            if (node.tasks().size > 2) HomeTextButton(
                                if (tasksExpanded) label("collapse") else label("show-all", node.tasks().size),
                                "home.tasks.expand", { tasksExpanded = !tasksExpanded },
                            )
                        }
                        if (node.tasks().isEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                DesktopIcon(Icons.Default.Schedule, null, Modifier.size(20.dp), palette.secondaryText)
                                CupertinoText(label(if (node.tasksKnown()) "idle" else "tasks-unavailable"),
                                    fontSize = 13.sp, color = palette.secondaryText)
                            }
                        } else {
                            Column {
                                node.tasks().take(2).forEach { task ->
                                    key(task.id()) { HomeTask(task, text) }
                                }
                                HomeDisclosure(tasksExpanded) {
                                    Column {
                                        node.tasks().drop(2).forEach { task ->
                                            key(task.id()) { HomeTask(task, text) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                HomeEntrance(entered, 105) {
                    HomeMetrics(node.metrics(), statistics, text, emit)
                }
                HomeEntrance(entered, 140) {
                    Column {
                        CupertinoHorizontalDivider(color = palette.separator.copy(alpha = .6f))
                        val rotation by animateFloatAsState(if (systemExpanded) 90f else 0f,
                            spring(dampingRatio = 1f, stiffness = 600f), label = "system-chevron")
                        Row(
                            Modifier.fillMaxWidth().testTag("home.system.expand")
                                .clip(RoundedCornerShape(4.dp)).pointerHoverIcon(PointerIcon.Hand)
                                .clickable(role = Role.Button) { systemExpanded = !systemExpanded }
                                .semantics { stateDescription = label(if (systemExpanded) "expanded" else "collapsed") }
                                .padding(vertical = 21.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            DesktopIcon(Icons.Default.Language, null, Modifier.size(16.dp), palette.secondaryText)
                            CupertinoText(label("system"), Modifier.weight(1f),
                                fontSize = 12.sp, color = palette.secondaryText)
                            DesktopIcon(Icons.Default.ChevronRight, null,
                                Modifier.size(14.dp).graphicsLayer { rotationZ = rotation }, palette.secondaryText)
                        }
                        HomeDisclosure(systemExpanded) {
                            HomeSystem(node, text, narrow)
                        }
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

internal fun homeGreetingKey(hour: Int): String = when (hour) {
    in 5..10 -> "greeting.morning"
    in 11..12 -> "greeting.noon"
    in 13..17 -> "greeting.afternoon"
    in 18..23 -> "greeting.evening"
    else -> "greeting.night"
}

@Composable
private fun HomeEntrance(visible: Boolean, delay: Int, content: @Composable () -> Unit) {
    val fraction by animateFloatAsState(if (visible) 1f else 0f,
        tween(280, delayMillis = delay), label = "home-entrance")
    Box(Modifier.fillMaxWidth().graphicsLayer {
        alpha = fraction
        translationY = (1f - fraction) * 8.dp.toPx()
    }) { content() }
}

@Composable
private fun HomeDisclosure(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 600f)) + fadeIn(tween(160)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f)) + fadeOut(tween(120)),
    ) { content() }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    CupertinoText(title, modifier.semantics { heading() }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        color = LocalExperiencePalette.current.text)
}

@Composable
private fun BackendStatus(
    node: DesktopUiNode.HomeOverview,
    text: (DesktopUiNode.TextToken) -> String,
    modifier: Modifier,
) {
    val backend = node.backend()
    val startedAt = node.backendStartingAt()
    var elapsed by remember(startedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (startedAt > 0L) {
            elapsed = ((System.currentTimeMillis() - startedAt) / 1_000L).coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    fun label(suffix: String, vararg args: Any) = text(
        DesktopUiNode.TextToken("gui-compose", "gui.compose.home.$suffix", "", args.map(Any::toString)),
    )
    val slow = startedAt > 0L && elapsed >= 20L
    val palette = LocalExperiencePalette.current
    val color = when (backend.style()) {
        DesktopUiNode.TextStyle.SUCCESS -> palette.success
        DesktopUiNode.TextStyle.ERROR -> palette.error
        DesktopUiNode.TextStyle.WARNING -> palette.warning
        else -> palette.secondaryText
    }
    Column(modifier.testTag("home.backend"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            CupertinoText(
                if (slow) label("starting", elapsed) else text(backend.text()),
                Modifier.testTag("home.backend.state"),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.secondaryText,
            )
        }
        // 始终测量完整提示，为文字缩放和长译文预留空间，显隐不会推动首页。
        CupertinoText(
            label("starting.slow"),
            Modifier.fillMaxWidth().testTag("home.backend.hint")
                .graphicsLayer { alpha = if (slow) 1f else 0f }
                .then(if (slow) Modifier else Modifier.clearAndSetSemantics {}),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = palette.secondaryText,
        )
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun HomeShortcut(
    shortcut: DesktopUiNode.HomeShortcut,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
    modifier: Modifier,
) {
    val button = shortcut.button()
    val palette = LocalExperiencePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val scale by animateFloatAsState(if (pressed) .98f else 1f,
        spring(dampingRatio = 1f, stiffness = 900f), label = "shortcut-press")
    val primary = shortcut.symbol() == "download"
    val background by animateColorAsState(
        if (hovered || pressed) palette.secondarySurface else if (primary) palette.selection.copy(alpha = .6f)
        else palette.surface, tween(120), label = "shortcut-background",
    )
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier.testTag(button.id()).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(background)
            .border(if (focused) 2.dp else 1.dp,
                if (focused) palette.accent else if (primary) palette.accent.copy(alpha = .25f) else palette.separator.copy(alpha = .6f), shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Release, PointerEventPass.Final) {
                if (focused) focusManager.clearFocus()
            }
            .clickable(interaction, null, button.enabled(), role = Role.Button) { emitActivation(button, emit) }
            .padding(16.dp).heightIn(min = 62.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DesktopIcon(when (shortcut.symbol()) {
            "download" -> Icons.Default.Download
            "images" -> Icons.Default.PhotoLibrary
            "book" -> Icons.AutoMirrored.Filled.MenuBook
            else -> Icons.AutoMirrored.Filled.OpenInNew
        }, null, Modifier.size(24.dp), palette.accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            CupertinoText(text(button.label()), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            button.help()?.let {
                CupertinoText(text(it), fontSize = 12.sp, lineHeight = 17.sp,
                    color = palette.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        DesktopIcon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(13.dp), palette.secondaryText)
    }
}

private fun emitActivation(button: DesktopUiNode.Button, emit: (DesktopUiNode.Event) -> Unit) {
    if (button.enabled()) emit(DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, button.id(), DesktopUiNode.Value.empty()))
}

@Composable
private fun HomeTextButton(label: String, id: String, onClick: () -> Unit, external: Boolean = false) {
    Row(Modifier.testTag(id).clip(RoundedCornerShape(6.dp))
        .pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        CupertinoText(label, fontSize = 12.sp, color = LocalExperiencePalette.current.link)
        if (external) DesktopIcon(Icons.AutoMirrored.Filled.OpenInNew, null,
            Modifier.size(12.dp), LocalExperiencePalette.current.link)
    }
}

@Composable
private fun HomeTask(task: DesktopUiNode.HomeTask, text: (DesktopUiNode.TextToken) -> String) {
    val palette = LocalExperiencePalette.current
    Column(Modifier.fillMaxWidth().testTag(task.id()).padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(palette.secondarySurface, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                DesktopIcon(Icons.Default.Schedule, null, Modifier.size(18.dp), palette.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CupertinoText(text(task.title()), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.text)
                val supporting = text(task.supporting())
                if (supporting.isNotBlank()) CupertinoText(supporting, fontSize = 12.sp, color = palette.secondaryText)
            }
            CupertinoText(text(task.status()), Modifier.widthIn(max = 120.dp), fontSize = 12.sp, color = palette.secondaryText)
        }
        val freshness = task.freshness()?.let(text).orEmpty()
        if (freshness.isNotBlank()) CupertinoText(freshness, fontSize = 12.sp, color = palette.warning)
        task.progress()?.let { progress ->
            val animated by animateFloatAsState(progress.toFloat(), tween(300), label = "task-progress")
            Box(Modifier.padding(start = 48.dp).fillMaxWidth().height(3.dp)
                .clip(CircleShape).background(palette.secondarySurface)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.toFloat(), 0f..1f) }) {
                Box(Modifier.fillMaxWidth(animated).fillMaxHeight().background(palette.accent))
            }
        }
    }
}

@Composable
private fun HomeMetrics(
    metrics: List<DesktopUiNode.HomeMetric>,
    statistics: List<DesktopUiNode.HomeShortcut>,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val scope = rememberCoroutineScope()
    fun label(suffix: String, vararg args: Any) =
        text(DesktopUiNode.TextToken("gui-compose", "gui.compose.home.$suffix", "", args.map(Any::toString)))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 24.dp
        val minimumWidth = 152.dp * LocalDensity.current.fontScale
        val capacity = ((maxWidth + gap) / (minimumWidth + gap)).toInt()
            .coerceIn(1, metrics.size.coerceAtLeast(1))
        val pages = metrics.chunked(capacity)
        val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
        val showFreshness = metrics.any { it.freshness() != null }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(label("metrics"), Modifier.weight(1f))
                statistics.forEach { shortcut ->
                    HomeTextButton(
                        if (statistics.size == 1) label("view-statistics") else text(shortcut.button().label()),
                        shortcut.button().id(),
                        { emitActivation(shortcut.button(), emit) },
                        external = true,
                    )
                }
                if (pages.size > 1) {
                    CupertinoIconButton(
                        modifier = Modifier.testTag("home.metrics.previous"),
                        enabled = pager.targetPage > 0,
                        onClick = { scope.launch { pager.animateScrollToPage(pager.targetPage - 1) } },
                    ) {
                        DesktopIcon(Icons.Default.ChevronLeft, label("metrics-previous"), Modifier.size(16.dp))
                    }
                    CupertinoText(
                        label("metrics-page", pager.currentPage + 1, pages.size),
                        Modifier.testTag("home.metrics.page"),
                        fontSize = 12.sp,
                        color = palette.secondaryText,
                    )
                    CupertinoIconButton(
                        modifier = Modifier.testTag("home.metrics.next"),
                        enabled = pager.targetPage < pages.lastIndex,
                        onClick = { scope.launch { pager.animateScrollToPage(pager.targetPage + 1) } },
                    ) {
                        DesktopIcon(Icons.Default.ChevronRight, label("metrics-next"), Modifier.size(16.dp))
                    }
                }
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().testTag("home.metrics.pager").padding(top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Top,
            ) { page ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    pages.getOrNull(page).orEmpty().forEach { metric ->
                        key(metric.id()) {
                            Column(Modifier.weight(1f).testTag(metric.id()), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                                ComposeDesktopUiNodeRenderer.HintedTitle(text(metric.supporting())) {
                                    CupertinoText(
                                        text(metric.title()),
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        minLines = 2,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = palette.secondaryText,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    CupertinoText(
                                        text(metric.value()),
                                        Modifier.weight(1f, fill = false).alignByBaseline(),
                                        fontSize = 28.sp,
                                        lineHeight = 34.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = (-.6).sp,
                                        color = palette.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    metric.unit()?.let {
                                        CupertinoText(text(it), Modifier.alignByBaseline(), fontSize = 13.sp, color = palette.secondaryText)
                                    }
                                }
                                // 各页保留相同的文字槽位，避免长标题或过期提示使下方状态区在翻页时跳动。
                                if (showFreshness) CupertinoText(
                                    metric.freshness()?.let(text).orEmpty(),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    minLines = 2,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = palette.warning,
                                )
                            }
                        }
                    }
                    repeat(capacity - pages.getOrNull(page).orEmpty().size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HomeSystem(node: DesktopUiNode.HomeOverview, text: (DesktopUiNode.TextToken) -> String, narrow: Boolean) {
    val palette = LocalExperiencePalette.current
    fun label(suffix: String) = text(DesktopUiNode.TextToken("gui-compose", "gui.compose.home.$suffix", "", emptyList()))
    val items = listOf(
        Triple("service", text(node.backend().text()), node.system().port()?.let {
            text(DesktopUiNode.TextToken.key("gui.status.label.port")) + " " + it
        }),
        Triple("proxy", text(node.system().proxy()), node.system().endpoint()?.let(text)),
        Triple("plugins", text(node.system().plugins()), null),
    )
    Column(Modifier.fillMaxWidth().padding(start = if (narrow) 0.dp else 25.dp,
        top = 5.dp, bottom = 23.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        @Composable fun item(index: Int, modifier: Modifier = Modifier) {
            val (name, value, detail) = items[index]
            Column(modifier.testTag("home.system.$name"), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                CupertinoText(label("system.$name"), fontSize = 11.sp, color = palette.secondaryText)
                Column {
                    CupertinoText(value, fontSize = 12.sp, lineHeight = 21.sp, color = palette.text)
                    detail?.let { CupertinoText(it, fontSize = 11.sp, lineHeight = 20.sp, color = palette.secondaryText) }
                }
            }
        }
        if (narrow) items.indices.forEach { item(it, Modifier.fillMaxWidth()) }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            items.indices.forEach { item(it, Modifier.weight(1f)) }
        }
        CupertinoText(label("system.note"), Modifier.testTag("home.system.note"),
            fontSize = 11.sp, lineHeight = 20.sp, color = palette.secondaryText)
    }
}
