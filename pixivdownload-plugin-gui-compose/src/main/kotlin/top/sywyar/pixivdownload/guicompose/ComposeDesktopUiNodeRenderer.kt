@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose

import io.github.robinpcrd.cupertino.*
import io.github.robinpcrd.cupertino.theme.*

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image as SkiaImage
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.DesktopImageClassifierSupport
import javax.swing.JFileChooser
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** Compose 插件私有页面节点的 Compose Multiplatform renderer。 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalCupertinoApi::class)
object ComposeDesktopUiNodeRenderer {
    // ponytail: 单路预览解码控制累计缓冲；提高并发前需重新测量整页内存。
    private val previewDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val LocalDocumentRevision = staticCompositionLocalOf { 0L }
    private val scheduleTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    @Composable
    fun Render(
        root: DesktopUiNode,
        textResolver: (DesktopUiNode.TextToken) -> String,
        eventSink: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier = Modifier,
        documentRevision: Long = 0L,
    ) {
        remember(root) { DesktopUiNode.validateTree(root) }
        CompositionLocalProvider(LocalDocumentRevision provides documentRevision) {
            Node(root, textResolver, eventSink, modifier)
        }
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
            is DesktopUiNode.AdaptiveGrid -> AdaptiveGrid(node, text, emit, modifier)
            is DesktopUiNode.PagedRow -> PagedRow(node, text, emit, modifier)
            is DesktopUiNode.Dock -> Dock(node, text, emit, modifier)
            is DesktopUiNode.Surface -> SurfaceNode(node, text, emit, modifier)
            is DesktopUiNode.Group -> Group(node, text, emit, modifier)
            is DesktopUiNode.Form -> Form(node, text, emit, modifier)
            is DesktopUiNode.Tabs -> Tabs(node, text, emit, modifier)
            is DesktopUiNode.Scroll -> ScrollNode(node, text, emit, modifier)
            is DesktopUiNode.Split -> Split(node, text, emit, modifier)
            is DesktopUiNode.Text -> StyledText(node, text, modifier)
            is DesktopUiNode.Icon -> IconNode(node, text, modifier)
            is DesktopUiNode.Image -> ImageNode(node, text, modifier)
            is DesktopUiNode.LocalImage -> LocalImageNode(node, text, modifier)
            is DesktopUiNode.Separator -> if (node.axis() == DesktopUiNode.Axis.HORIZONTAL) {
                CupertinoHorizontalDivider(modifier.fillMaxWidth())
            } else {
                CupertinoVerticalDivider(modifier.fillMaxHeight().width(1.dp))
            }
            is DesktopUiNode.Spacer -> Spacer(modifier.size(node.width().dp, node.height().dp))
            is DesktopUiNode.Progress -> Progress(node, text, modifier)
            is DesktopUiNode.Timeline -> Timeline(node, text, modifier)
            is DesktopUiNode.ScheduleTimeline -> ScheduleTimeline(node, text, modifier)
            is DesktopUiNode.TextInput -> TextInput(node, text, emit, modifier)
            is DesktopUiNode.Toggle -> Toggle(node, text, emit, modifier)
            is DesktopUiNode.Choice -> Choice(node, text, emit, modifier)
            is DesktopUiNode.NumberInput -> NumberInput(node, text, emit, modifier)
            is DesktopUiNode.Table -> Table(node, text, emit, modifier)
            is DesktopUiNode.Tree -> Tree(node, text, emit, modifier)
            is DesktopUiNode.Button -> ActionButton(node, text, emit, modifier)
            is DesktopUiNode.Link -> CupertinoText(
                resolve(node.label(), text),
                modifier = modifier.hand(node.enabled()).clickable(
                    enabled = node.enabled(),
                    role = Role.Button,
                    onClick = { emit(activate(node.id(), node.actionId())) },
                ),
                color = if (node.enabled()) LocalExperiencePalette.current.link
                    else LocalExperiencePalette.current.text.copy(alpha = .38f),
                style = CupertinoTheme.typography.body,
                textDecoration = TextDecoration.Underline,
            )
        }
    }

    @Composable
    private fun ScrollNode(
        node: DesktopUiNode.Scroll,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        key(node.id()) {
            ScrollableContent(modifier) { Node(node.content(), text, emit, Modifier.fillMaxWidth()) }
        }
    }

    @Composable
    private fun ScrollableContent(
        modifier: Modifier,
        fillViewport: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        BoxWithConstraints(modifier) {
            if (!constraints.hasBoundedHeight) {
                Box(Modifier.fillMaxWidth()) { content() }
                return@BoxWithConstraints
            }
            val state = rememberScrollState()
            if (!fillViewport) {
                Box(Modifier.fillMaxWidth().verticalScroll(state).padding(end = 12.dp)) { content() }
                return@BoxWithConstraints
            }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().verticalScroll(state).padding(end = 12.dp)) { content() }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(state),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(7.dp)
                        .padding(vertical = 5.dp),
                    style = LocalScrollbarStyle.current.copy(
                        thickness = 7.dp,
                        shape = RoundedCornerShape(4.dp),
                        unhoverColor = LocalExperiencePalette.current.separator.copy(alpha = .6f),
                        hoverColor = LocalExperiencePalette.current.link.copy(alpha = .72f),
                    ),
                )
            }
        }
    }

    @Composable
    private fun Dock(
        node: DesktopUiNode.Dock,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
        formContent: Boolean = false,
    ) {
        @Composable fun child(value: DesktopUiNode, childModifier: Modifier = Modifier) {
            if (formContent && value !is DesktopUiNode.Toggle) FormContent(value, text, emit, childModifier)
            else Node(value, text, emit, childModifier)
        }
        if (node.top() == null && node.bottom() == null) {
            BoxWithConstraints(modifier.fillMaxWidth()) {
                val stacked = node.center() != null && (node.start() != null || node.end() != null) &&
                    maxWidth < 760.dp
                if (stacked) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(node.gap().dp),
                    ) {
                        node.start()?.let { child(it, Modifier.fillMaxWidth()) }
                        node.center()?.let { child(it, Modifier.fillMaxWidth()) }
                        node.end()?.let { child(it, Modifier.fillMaxWidth()) }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(node.gap().dp),
                    ) {
                        node.start()?.let { child(it) }
                        node.center()?.let { Box(Modifier.weight(1f)) { child(it, Modifier.fillMaxWidth()) } }
                        if (node.center() == null && node.start() != null && node.end() != null) {
                            Spacer(Modifier.weight(1f))
                        }
                        node.end()?.let { child(it) }
                    }
                }
            }
            return
        }
        BoxWithConstraints(modifier) {
            val boundedHeight = constraints.hasBoundedHeight
            val bottomLimit = if (boundedHeight) {
                (maxHeight - 112.dp).coerceAtLeast(maxHeight * .5f)
            } else null
            Column(
                if (boundedHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(node.gap().dp),
            ) {
                node.top()?.let { child(it, Modifier.fillMaxWidth()) }
                Row(
                    if (boundedHeight) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(node.gap().dp),
                ) {
                    node.start()?.let { child(it, if (boundedHeight) Modifier.fillMaxHeight() else Modifier) }
                    if (node.center() != null) {
                        Box(Modifier.weight(1f)) {
                            child(node.center(), if (boundedHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                        }
                    } else if (node.start() != null && node.end() != null) {
                        Spacer(Modifier.weight(1f))
                    }
                    node.end()?.let { child(it, if (boundedHeight) Modifier.fillMaxHeight() else Modifier) }
                }
                node.bottom()?.let { bottom ->
                    if (bottomLimit == null) {
                        child(bottom, Modifier.fillMaxWidth())
                    } else {
                        ScrollableContent(
                            Modifier.fillMaxWidth().heightIn(max = bottomLimit),
                            fillViewport = false,
                        ) { child(bottom, Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }

    @Composable
    private fun SurfaceNode(
        node: DesktopUiNode.Surface,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val insets = node.padding()
        val contentModifier = when {
            node.fillWidth() && node.fillHeight() -> Modifier.fillMaxSize()
            node.fillWidth() -> Modifier.fillMaxWidth()
            node.fillHeight() -> Modifier.fillMaxHeight()
            else -> Modifier
        }.padding(
            start = insets.start().dp,
            top = insets.top().dp,
            end = insets.end().dp,
            bottom = insets.bottom().dp,
        )
        val sized = when {
            node.fillWidth() && node.fillHeight() -> modifier.fillMaxSize()
            node.fillWidth() -> modifier.fillMaxWidth()
            node.fillHeight() -> modifier.fillMaxHeight()
            else -> modifier
        }
        val actionId = node.actionId()
        val interactive = if (actionId == null) sized else sized.hand(true).clickable(
            role = Role.Button,
            onClick = { emit(activate(node.id(), actionId)) },
        )
        if (node.style() == DesktopUiNode.SurfaceStyle.PLAIN) {
            Box(interactive) { Node(node.content(), text, emit, contentModifier) }
            return
        }
        val cardModifier = interactive
        val content: @Composable () -> Unit = { Node(node.content(), text, emit, contentModifier) }
        val containerColor = when (node.style()) {
            DesktopUiNode.SurfaceStyle.CARD -> LocalExperiencePalette.current.surface
            DesktopUiNode.SurfaceStyle.MUTED -> LocalExperiencePalette.current.secondarySurface
            else -> LocalExperiencePalette.current.secondarySurface
        }
        CupertinoSurface(
            modifier = if (node.style() == DesktopUiNode.SurfaceStyle.CARD)
                cardModifier.border(1.dp, LocalExperiencePalette.current.separator, CupertinoTheme.shapes.large)
                else cardModifier,
            shape = CupertinoTheme.shapes.large,
            color = containerColor,
            contentColor = LocalExperiencePalette.current.text,
            content = content,
        )
    }

    @Composable
    private fun Container(
        node: DesktopUiNode.Container,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
        formContent: Boolean = false,
    ) {
        @Composable fun child(value: DesktopUiNode, childModifier: Modifier = Modifier) {
            if (formContent && value !is DesktopUiNode.Toggle) FormContent(value, text, emit, childModifier)
            else Node(value, text, emit, childModifier)
        }
        val gap = node.gap().dp
        when (node.layout()) {
            DesktopUiNode.ContainerLayout.COLUMN -> Column(
                modifier,
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalAlignment = horizontal(node.alignment()),
            ) { node.children().forEach {
                child(it, if (it is DesktopUiNode.Button || it is DesktopUiNode.Link) Modifier
                    else childModifier(node.alignment()))
            } }
            DesktopUiNode.ContainerLayout.ROW -> Row(
                modifier,
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = vertical(node.alignment()),
            ) { node.children().forEach { child(it) } }
            DesktopUiNode.ContainerLayout.FLOW -> FlowRow(
                modifier,
                horizontalArrangement = Arrangement.spacedBy(gap, horizontal(node.alignment())),
                verticalArrangement = Arrangement.spacedBy(gap),
                itemVerticalAlignment = vertical(node.alignment()),
            ) { node.children().forEach { child(it) } }
            DesktopUiNode.ContainerLayout.GRID -> Column(
                modifier,
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                node.children().chunked(node.columns()).forEach { row ->
                    EqualHeightRow(node.columns(), gap, Modifier.fillMaxWidth(), row) { index, childModifier ->
                        if (index < row.size) child(row[index], childModifier)
                        else Spacer(childModifier)
                    }
                }
            }
        }
    }

    @Composable
    private fun AdaptiveGrid(
        node: DesktopUiNode.AdaptiveGrid,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        BoxWithConstraints(modifier) {
            val columns = adaptiveGridColumnCount(
                maxWidth.value.roundToInt(),
                node.minimumColumnWidth(),
                node.maximumColumns(),
                node.horizontalGap(),
                node.children().size,
            )
            Column(verticalArrangement = Arrangement.spacedBy(node.verticalGap().dp)) {
                node.children().chunked(columns).forEach { row ->
                    EqualHeightRow(columns, node.horizontalGap().dp, Modifier.fillMaxWidth(), row) {
                            index, childModifier ->
                        if (index < row.size) Node(row[index], text, emit, childModifier)
                        else Spacer(childModifier)
                    }
                }
            }
        }
    }

    @Composable
    private fun EqualHeightRow(
        count: Int,
        gap: Dp,
        modifier: Modifier,
        measurementKey: Any,
        content: @Composable (Int, Modifier) -> Unit,
    ) {
        BoxWithConstraints(modifier) {
            val density = LocalDensity.current
            var minimumHeight by remember(count, maxWidth, density.density, density.fontScale, measurementKey) {
                mutableStateOf(0)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(count) { index ->
                    Box(
                        Modifier.weight(1f)
                            .heightIn(min = with(density) { minimumHeight.toDp() })
                            .onSizeChanged { if (it.height > minimumHeight) minimumHeight = it.height },
                        propagateMinConstraints = true,
                    ) {
                        content(index, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    @Composable
    private fun PagedRow(
        node: DesktopUiNode.PagedRow,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val pageCount = maxOf(1, (node.children().size + node.itemsPerPage() - 1) / node.itemsPerPage())
        val pagerState = rememberPagerState(pageCount = { pageCount })
        val scope = rememberCoroutineScope()
        fun move(delta: Int) {
            val target = (pagerState.currentPage + delta).coerceIn(0, pageCount - 1)
            if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
        }
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = pageCount > 1,
            modifier = modifier.fillMaxWidth()
                .semantics { stateDescription = "${pagerState.currentPage + 1}/$pageCount" }
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { move(-1); true }
                        Key.DirectionRight -> { move(1); true }
                        else -> false
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val delta = change.scrollDelta
                    val primary = if (abs(delta.x) > abs(delta.y)) delta.x else delta.y
                    if (primary != 0f) {
                        move(if (primary > 0f) 1 else -1)
                        change.consume()
                    }
                },
        ) { page ->
            val children = node.children().drop(page * node.itemsPerPage()).take(node.itemsPerPage())
            EqualHeightRow(node.itemsPerPage(), node.gap().dp, Modifier.fillMaxWidth(), children) {
                    index, childModifier ->
                if (index < children.size) Node(children[index], text, emit, childModifier)
                else Spacer(childModifier)
            }
        }
    }

    internal fun adaptiveGridColumnCount(
        availableWidth: Int,
        minimumColumnWidth: Int,
        maximumColumns: Int,
        gap: Int,
        itemCount: Int,
    ): Int = if (itemCount <= 0) 1 else ((availableWidth + gap) / (minimumColumnWidth + gap))
        .coerceIn(1, minOf(maximumColumns, itemCount))

    @Composable
    private fun Group(node: DesktopUiNode.Group, text: (DesktopUiNode.TextToken) -> String,
                      emit: (DesktopUiNode.Event) -> Unit, modifier: Modifier) {
        var expanded by rememberSaveable(node.id()) { mutableStateOf(!node.collapsible()) }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (node.collapsible()) {
                CupertinoButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp),
                    colors = CupertinoButtonDefaults.plainButtonColors(contentColor = LocalExperiencePalette.current.text),
                    modifier = Modifier.semantics { stateDescription = text(DesktopUiNode.TextToken(
                        GuiComposePlugin.ID, if (expanded) "gui.compose.expanded" else "gui.compose.collapsed", "", emptyList())) }) {
                    CupertinoText(if (expanded) "▾" else "▸", Modifier.padding(end = 8.dp))
                    CupertinoText(resolve(node.title(), text), style = CupertinoTheme.typography.headline)
                }
            } else CupertinoText(resolve(node.title(), text), style = CupertinoTheme.typography.headline)
            if (expanded) Node(node.content(), text, emit, Modifier.fillMaxWidth())
        }
    }

    @Composable
    private fun Form(
        node: DesktopUiNode.Form,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        Box(modifier) {
            BoxWithConstraints(Modifier.widthIn(max = DesktopLayout.formWidth).fillMaxWidth()) {
                val narrow = maxWidth < 540.dp
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    node.rows().forEach { row ->
                        val help = row.help()?.let { resolve(it, text) }.orEmpty()
                        @Composable fun label() {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                CupertinoText(resolve(row.label(), text), style = CupertinoTheme.typography.body,
                                    fontWeight = FontWeight.Medium)
                                if (help.isNotBlank()) CupertinoText(help, style = CupertinoTheme.typography.footnote,
                                    color = LocalExperiencePalette.current.secondaryText)
                            }
                        }
                        @Composable fun field() {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val width = when (val content = row.content()) {
                                    is DesktopUiNode.TextInput -> when (content.inputKind()) {
                                        DesktopUiNode.InputKind.NUMBER, DesktopUiNode.InputKind.TIME -> DesktopLayout.numberWidth
                                        DesktopUiNode.InputKind.PASSWORD -> DesktopLayout.choiceWidth
                                        else -> DesktopLayout.formWidth
                                    }
                                    is DesktopUiNode.Choice -> if (content.choiceStyle() == DesktopUiNode.ChoiceStyle.COMBO_BOX)
                                        DesktopLayout.choiceWidth else DesktopLayout.formWidth
                                    is DesktopUiNode.NumberInput -> if (content.numberStyle() == DesktopUiNode.NumberStyle.SPINNER)
                                        DesktopLayout.numberWidth else DesktopLayout.formWidth
                                    else -> DesktopLayout.formWidth
                                }
                                FormContent(row.content(), text, emit, Modifier.widthIn(max = width).fillMaxWidth())
                                row.trailing()?.let { Node(it, text, emit) }
                            }
                        }
                        if (narrow) Column(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) { label(); field() }
                        else Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.width(240.dp)) { label() }
                            Box(Modifier.weight(1f)) { field() }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FormContent(
        node: DesktopUiNode,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        when (node) {
            is DesktopUiNode.TextInput -> TextInput(node, text, emit, modifier, false)
            is DesktopUiNode.Toggle -> Toggle(node, text, emit, modifier, false)
            is DesktopUiNode.Choice -> Choice(node, text, emit, modifier, false)
            is DesktopUiNode.NumberInput -> NumberInput(node, text, emit, modifier, false)
            is DesktopUiNode.Container -> Container(node, text, emit, modifier, true)
            is DesktopUiNode.Dock -> Dock(node, text, emit, modifier, true)
            else -> Node(node, text, emit, modifier)
        }
    }

    private fun usesCompactFormRow(node: DesktopUiNode): Boolean = when (node) {
        is DesktopUiNode.Toggle -> true
        is DesktopUiNode.Choice -> node.choiceStyle() == DesktopUiNode.ChoiceStyle.COMBO_BOX
        is DesktopUiNode.NumberInput -> node.numberStyle() == DesktopUiNode.NumberStyle.SPINNER
        is DesktopUiNode.TextInput -> node.inputKind() == DesktopUiNode.InputKind.NUMBER ||
            node.inputKind() == DesktopUiNode.InputKind.TIME
        else -> false
    }

    @Composable
    private fun Tabs(
        node: DesktopUiNode.Tabs,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val tabIds = node.tabs().map { it.id() }
        var selectedId by rememberSaveable(node.id()) {
            mutableStateOf(node.initialSelectedId()?.takeIf { it in tabIds } ?: tabIds.first())
        }
        val activeTabId = selectedIdOrFirst(selectedId, tabIds)
        val selectedIndex = tabIds.indexOf(activeTabId)
        val tabStates = rememberSaveableStateHolder()
        val retainedIds = remember(node.id()) { linkedSetOf<String>() }
        LaunchedEffect(tabIds) {
            removedPageIds(retainedIds, tabIds).forEach(tabStates::removeState)
            retainedIds.clear()
            retainedIds.addAll(tabIds)
        }
        LaunchedEffect(activeTabId) { selectedId = activeTabId }
        BoxWithConstraints(modifier) {
            val boundedHeight = constraints.hasBoundedHeight
            Column(if (boundedHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
                CupertinoSegmentedControl(
                    selectedTabIndex = selectedIndex,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CupertinoSegmentedControlDefaults.colors(
                        containerColor = LocalExperiencePalette.current.secondarySurface,
                        indicatorColor = LocalExperiencePalette.current.surface,
                    ),
                ) {
                    node.tabs().forEach { tab ->
                        CupertinoSegmentedControlTab(
                            onClick = { selectedId = tab.id() },
                            isSelected = activeTabId == tab.id(),
                            modifier = Modifier.heightIn(min = DesktopLayout.controlHeight)
                                .semantics { selected = activeTabId == tab.id() },
                        ) {
                            CupertinoText(resolve(tab.title(), text), Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                style = CupertinoTheme.typography.subhead,
                                fontWeight = if (activeTabId == tab.id()) FontWeight.SemiBold else FontWeight.Medium)
                        }
                    }
                }
                Box((if (boundedHeight) Modifier.weight(1f) else Modifier)
                    .fillMaxWidth().padding(top = 12.dp)) {
                    tabStates.SaveableStateProvider(activeTabId) {
                        Node(
                            node.tabs().first { it.id() == activeTabId }.content(), text, emit,
                            if (boundedHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Split(
        node: DesktopUiNode.Split,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        var firstWeight by rememberSaveable(node.id()) {
            mutableStateOf(node.resizeWeight().toFloat().coerceIn(.1f, .9f))
        }
        BoxWithConstraints(modifier) {
            val splitWidth = constraints.maxWidth
            val splitHeight = constraints.maxHeight
            if (node.axis() == DesktopUiNode.Axis.HORIZONTAL) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(firstWeight)) { Node(node.first(), text, emit, Modifier.fillMaxSize()) }
                    CupertinoVerticalDivider(
                        Modifier.fillMaxHeight().width(9.dp)
                            .pointerInput(node.id(), splitWidth) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    firstWeight = resizedSplitWeight(firstWeight, drag.x, splitWidth)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        color = LocalExperiencePalette.current.separator,
                    )
                    Box(Modifier.weight(1f - firstWeight)) {
                        Node(node.second(), text, emit, Modifier.fillMaxSize())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(firstWeight)) { Node(node.first(), text, emit, Modifier.fillMaxSize()) }
                    CupertinoHorizontalDivider(
                        Modifier.fillMaxWidth().height(9.dp)
                            .pointerInput(node.id(), splitHeight) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    firstWeight = resizedSplitWeight(firstWeight, drag.y, splitHeight)
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = LocalExperiencePalette.current.separator,
                    )
                    Box(Modifier.weight(1f - firstWeight)) {
                        Node(node.second(), text, emit, Modifier.fillMaxSize())
                    }
                }
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
            DesktopUiNode.TextStyle.TITLE -> CupertinoTheme.typography.title1
            DesktopUiNode.TextStyle.HEADING -> CupertinoTheme.typography.headline
            DesktopUiNode.TextStyle.EMPHASIS -> CupertinoTheme.typography.body.copy(fontWeight = FontWeight.SemiBold)
            DesktopUiNode.TextStyle.ERROR -> CupertinoTheme.typography.headline.copy(fontWeight = FontWeight.SemiBold)
            DesktopUiNode.TextStyle.CAPTION -> CupertinoTheme.typography.footnote
            DesktopUiNode.TextStyle.CODE -> CupertinoTheme.typography.body.copy(fontFamily = FontFamily.Monospace)
            else -> CupertinoTheme.typography.body
        }
        val color = when (node.style()) {
            DesktopUiNode.TextStyle.SUCCESS -> LocalExperiencePalette.current.success
            DesktopUiNode.TextStyle.WARNING -> LocalExperiencePalette.current.warning
            DesktopUiNode.TextStyle.ERROR -> LocalExperiencePalette.current.error
            DesktopUiNode.TextStyle.SECONDARY,
            DesktopUiNode.TextStyle.CAPTION -> LocalExperiencePalette.current.secondaryText
            else -> Color.Unspecified
        }
        val content: @Composable () -> Unit = {
            val value = resolve(node.text(), text).let {
                if (node.style() == DesktopUiNode.TextStyle.BULLET) "• $it" else it
            }
            CupertinoText(value, modifier = modifier, style = style, color = color,
                softWrap = node.wrap(), textAlign = when (node.textAlignment()) {
                    DesktopUiNode.TextAlignment.START -> TextAlign.Start
                    DesktopUiNode.TextAlignment.CENTER -> TextAlign.Center
                    DesktopUiNode.TextAlignment.END -> TextAlign.End
                })
        }
        if (node.selectable()) SelectionContainer(content = content) else content()
    }

    @Composable
    private fun IconNode(
        node: DesktopUiNode.Icon,
        text: (DesktopUiNode.TextToken) -> String,
        modifier: Modifier,
    ) {
        DesktopIcon(
            desktopIcon(node.icon()),
            contentDescription = resolve(node.accessibleLabel(), text),
            modifier = modifier,
            tint = toneColor(node.tone()),
        )
    }

    @Composable
    private fun toneColor(tone: DesktopUiTone): Color = when (tone) {
        DesktopUiTone.DEFAULT -> LocalExperiencePalette.current.text
        DesktopUiTone.SUCCESS -> LocalExperiencePalette.current.success
        DesktopUiTone.INFO -> LocalExperiencePalette.current.link
        DesktopUiTone.WARNING -> LocalExperiencePalette.current.warning
        DesktopUiTone.ERROR -> LocalExperiencePalette.current.error
    }

    @Composable
    private fun LocalImageNode(
        node: DesktopUiNode.LocalImage,
        text: (DesktopUiNode.TextToken) -> String,
        modifier: Modifier,
    ) {
        var size by remember(node.path()) { mutableStateOf(IntSize.Zero) }
        val data by produceState<DesktopUiNode.ImageData?>(null, node.path(), size) {
            value = null
            if (size.width > 0 && size.height > 0) {
                value = withContext(previewDispatcher) {
                    DesktopImageClassifierSupport.materializeImage(node.path(), size.width, size.height).orElse(null)
                }
            }
        }
        Box(modifier.size(node.preferredWidth().dp, node.preferredHeight().dp).onSizeChanged { size = it }) {
            val image = data
            if (image == null) {
                Text(resolve(node.altText(), text))
            } else {
                ImageNode(
                    DesktopUiNode.Image(node.id(), image, node.altText(), node.preferredWidth(),
                        node.preferredHeight(), DesktopUiNode.ScaleMode.FIT),
                    text,
                    Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Composable
    private fun ImageNode(
        node: DesktopUiNode.Image,
        text: (DesktopUiNode.TextToken) -> String,
        modifier: Modifier,
    ) {
        val source = remember(node.image()) { runCatching { SkiaImage.makeFromEncoded(node.image().bytes()) }.getOrNull() }
        if (source == null) {
            CupertinoText(resolve(node.altText(), text), modifier = modifier)
            return
        }
        DisposableEffect(source) { onDispose(source::close) }
        val bitmap = remember(source) { source.toComposeImageBitmap() }
        val description = resolve(node.altText(), text)
        val scale = when (node.scaleMode()) {
            DesktopUiNode.ScaleMode.NONE -> ContentScale.None
            DesktopUiNode.ScaleMode.FILL -> ContentScale.FillBounds
            DesktopUiNode.ScaleMode.FIT -> ContentScale.Fit
        }
        val imageModifier = modifier.size(node.preferredWidth().dp, node.preferredHeight().dp).let {
            if (node.shape() == DesktopUiNode.ImageShape.CIRCLE) it.clip(CircleShape) else it
        }
        Image(
            bitmap,
            contentDescription = description,
            contentScale = scale,
            modifier = imageModifier.semantics { contentDescription = description },
        )
    }

    @Composable
    private fun Progress(
        node: DesktopUiNode.Progress,
        text: (DesktopUiNode.TextToken) -> String,
        modifier: Modifier,
    ) {
        if (node.progressStyle() == DesktopUiNode.ProgressStyle.CIRCULAR) {
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                if (node.indeterminate()) CupertinoActivityIndicator(Modifier.size(44.dp))
                else DesktopCircularProgress(
                    node.progress().toFloat(),
                    Modifier.size(44.dp),
                )
                node.text()?.let {
                    CupertinoText(
                        resolve(it, text),
                        Modifier.padding(start = 8.dp),
                        style = CupertinoTheme.typography.footnote,
                    )
                }
            }
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (node.indeterminate()) CupertinoActivityIndicator(Modifier.size(20.dp))
                else DesktopLinearProgress(node.progress().toFloat(), Modifier.fillMaxWidth().height(5.dp))
                node.text()?.let { CupertinoText(resolve(it, text), style = CupertinoTheme.typography.footnote) }
            }
        }
    }

    @Composable
    private fun Timeline(
        node: DesktopUiNode.Timeline,
        text: (DesktopUiNode.TextToken) -> String,
        modifier: Modifier,
    ) {
        Box(modifier.fillMaxWidth()) {
            Box(
                Modifier.matchParentSize().padding(start = 14.dp, top = 16.dp, bottom = 16.dp),
            ) {
                Spacer(
                    Modifier.fillMaxHeight().width(2.dp)
                        .background(LocalExperiencePalette.current.separator),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                node.items().forEachIndexed { index, item ->
                    val markerBackground = when (item.state()) {
                        DesktopUiNode.TimelineState.COMPLETE -> LocalExperiencePalette.current.successSurface
                        DesktopUiNode.TimelineState.ACTIVE -> LocalExperiencePalette.current.selection
                        DesktopUiNode.TimelineState.IDLE -> LocalExperiencePalette.current.secondarySurface
                    }
                    val markerForeground = when (item.state()) {
                        DesktopUiNode.TimelineState.COMPLETE -> LocalExperiencePalette.current.success
                        DesktopUiNode.TimelineState.ACTIVE -> LocalExperiencePalette.current.text
                        DesktopUiNode.TimelineState.IDLE -> LocalExperiencePalette.current.secondaryText
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 54.dp)
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                                .background(markerBackground),
                            contentAlignment = Alignment.Center,
                        ) {
                            CupertinoText(
                                (index + 1).toString(),
                                color = markerForeground,
                                style = CupertinoTheme.typography.caption1,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Column(
                            Modifier.weight(1f).padding(start = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            CupertinoText(
                                resolve(item.title(), text),
                                style = CupertinoTheme.typography.body,
                                fontWeight = FontWeight.SemiBold,
                            )
                            CupertinoText(
                                resolve(item.detail(), text),
                                color = LocalExperiencePalette.current.secondaryText,
                                style = CupertinoTheme.typography.footnote,
                            )
                        }
                        CupertinoText(
                            resolve(item.status(), text),
                            Modifier.padding(start = 8.dp),
                            color = markerForeground,
                            style = CupertinoTheme.typography.caption2,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    private data class SchedulePlacement(val left: Dp, val lane: Int)

    @Composable
    private fun ScheduleTimeline(
        node: DesktopUiNode.ScheduleTimeline,
        text: (DesktopUiNode.TextToken) -> String,
        modifier: Modifier,
    ) {
        BoxWithConstraints(modifier.fillMaxWidth().height(204.dp)) {
            val timelineWidth = maxWidth
            val itemWidth = minOf(timelineWidth, maxOf(104.dp, minOf(150.dp, timelineWidth * .28f)))
            val axisWidth = maxOf(0.dp, timelineWidth - itemWidth)
            val itemWidthFraction = if (timelineWidth.value == 0f) 1f else itemWidth.value / timelineWidth.value
            val placements = remember(node, timelineWidth) {
                val laneEnds = FloatArray(3) { -1f }
                node.items().map { item ->
                    val point = ((item.at() - node.startAt()).toDouble() /
                        (node.endAt() - node.startAt()).toDouble()).toFloat().coerceIn(0f, 1f)
                    val left = point * (1f - itemWidthFraction)
                    val lane = laneEnds.indices.firstOrNull { laneEnds[it] + .015f <= left }
                        ?: laneEnds.indices.minBy { laneEnds[it] }
                    laneEnds[lane] = left + itemWidthFraction
                    SchedulePlacement(timelineWidth * left, lane)
                }
            }
            val axisY = 28.dp
            val tickCount = if (axisWidth < 480.dp) 6 else 12
            val tickLabelWidth = minOf(48.dp, axisWidth / tickCount.toFloat())
            repeat(tickCount) { index ->
                val fraction = index.toFloat() / tickCount
                val tickX = axisWidth * fraction
                val labelX = (tickX - tickLabelWidth / 2f)
                    .coerceIn(0.dp, timelineWidth - tickLabelWidth)
                CupertinoText(
                    scheduleTimeFormatter.format(
                        Instant.ofEpochMilli(node.startAt() + index * (24L * 60L * 60L * 1_000L / tickCount)),
                    ),
                    Modifier.offset(x = labelX).width(tickLabelWidth),
                    color = LocalExperiencePalette.current.secondaryText,
                    style = CupertinoTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Spacer(
                    Modifier.offset(x = tickX.coerceAtMost(timelineWidth - 1.dp), y = axisY - 3.dp)
                        .width(1.dp).height(7.dp).background(LocalExperiencePalette.current.separator),
                )
            }
            Spacer(
                Modifier.offset(y = axisY).width(axisWidth).height(1.dp)
                    .background(LocalExperiencePalette.current.separator),
            )
            val nowFraction = ((node.nowAt() - node.startAt()).toDouble() /
                (node.endAt() - node.startAt()).toDouble()).toFloat().coerceIn(0f, 1f)
            val nowX = axisWidth * nowFraction
            Spacer(
                Modifier.offset(x = nowX, y = axisY).width(2.dp).height(168.dp)
                    .background(LocalExperiencePalette.current.link),
            )
            Spacer(
                Modifier.offset(
                    x = (nowX - 3.dp).coerceIn(0.dp, timelineWidth - 7.dp),
                    y = axisY - 3.dp,
                ).size(7.dp).clip(CircleShape).background(LocalExperiencePalette.current.link),
            )
            node.items().forEachIndexed { index, item ->
                val placement = placements[index]
                val containerColor = when (placement.lane) {
                    0 -> LocalExperiencePalette.current.selection
                    1 -> LocalExperiencePalette.current.warningSurface
                    else -> LocalExperiencePalette.current.successSurface
                }
                val contentColor = when (placement.lane) {
                    0 -> LocalExperiencePalette.current.text
                    1 -> LocalExperiencePalette.current.warning
                    else -> LocalExperiencePalette.current.success
                }
                CupertinoSurface(
                    Modifier.offset(x = placement.left, y = 42.dp + 52.dp * placement.lane.toFloat())
                        .width(itemWidth).semantics(mergeDescendants = true) {},
                    color = containerColor,
                    shape = CupertinoTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CupertinoText(
                                resolve(item.time(), text),
                                color = contentColor,
                                style = CupertinoTheme.typography.caption1,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            CupertinoText(
                                resolve(item.title(), text),
                                Modifier.weight(1f),
                                color = contentColor,
                                style = CupertinoTheme.typography.footnote,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        CupertinoText(
                            resolve(item.detail(), text),
                            color = contentColor.copy(alpha = .78f),
                            style = CupertinoTheme.typography.caption2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TextInput(
        node: DesktopUiNode.TextInput,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
        includeLabel: Boolean = true,
    ) {
        val documentRevision = LocalDocumentRevision.current
        val password = node.inputKind() == DesktopUiNode.InputKind.PASSWORD
        val inputState = if (password) {
            remember(textInputStateKey(node)) { mutableStateOf(TextFieldValue()) }
        } else {
            rememberSaveable(node.id(), stateSaver = TextFieldValue.Saver) {
                mutableStateOf(TextFieldValue(node.value()))
            }
        }
        var value by inputState
        if (!password) LaunchedEffect(documentRevision, node.value()) {
            if (value.text != node.value()) {
                val length = node.value().length
                value = TextFieldValue(node.value(), TextRange(
                    value.selection.start.coerceAtMost(length), value.selection.end.coerceAtMost(length)))
            }
        }
        fun update(next: TextFieldValue) {
            val changed = value.text != next.text
            value = next
            if (changed) emit(change(node.id(), node.bindingId(), DesktopUiNode.Value.text(next.text)))
        }
        val content: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CompactTextInput(
                    value = value,
                    onValueChange = ::update,
                    enabled = node.enabled(),
                    singleLine = node.inputKind() != DesktopUiNode.InputKind.MULTILINE,
                    visualTransformation = if (node.inputKind() == DesktopUiNode.InputKind.PASSWORD)
                        PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.weight(1f)
                        .semantics { contentDescription = resolve(node.label(), text) }
                        .then(if (node.inputKind() == DesktopUiNode.InputKind.MULTILINE)
                            Modifier.heightIn(min = 88.dp) else Modifier.heightIn(min = DesktopLayout.controlHeight)),
                )
                if (node.inputKind() == DesktopUiNode.InputKind.FILE
                    || node.inputKind() == DesktopUiNode.InputKind.DIRECTORY) {
                    CupertinoButton(
                colors = CupertinoButtonDefaults.grayButtonColors(contentColor = LocalExperiencePalette.current.text),
                border = BorderStroke(1.dp, LocalExperiencePalette.current.controlBorder),
                        shape = CupertinoTheme.shapes.small,
                        onClick = { choosePath(node.inputKind(), value.text)?.let { update(TextFieldValue(it, TextRange(it.length))) } },
                        enabled = node.enabled(),
                        modifier = Modifier.padding(start = 8.dp).hand(node.enabled()),
                    ) { CupertinoText(text(DesktopUiNode.TextToken(
                        GuiComposePlugin.ID, "gui.compose.browse", "Browse...", emptyList(),
                    ))) }
                }
            }
        }
        if (includeLabel) Labeled(resolve(node.label(), text), help(node.help(), text), modifier, content)
        else Box(modifier) { content() }
    }

    @Composable
    private fun CompactTextInput(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit,
                                 enabled: Boolean, singleLine: Boolean,
                                 visualTransformation: androidx.compose.ui.text.input.VisualTransformation,
                                 modifier: Modifier, invalid: Boolean = false,
                                 keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
                                 errorMessage: String = "") {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        CupertinoTextField(value = value, onValueChange = onValueChange, enabled = enabled,
            singleLine = singleLine, visualTransformation = visualTransformation,
            interactionSource = interaction,
            keyboardOptions = keyboardOptions,
            isError = invalid,
            textStyle = CupertinoTheme.typography.body.copy(color = if (enabled)
                LocalExperiencePalette.current.text else LocalExperiencePalette.current.secondaryText),
            modifier = modifier.semantics { if (invalid && errorMessage.isNotEmpty()) error(errorMessage) }
                .border(if (focused) 2.dp else 1.dp,
                    if (invalid) LocalExperiencePalette.current.error else if (focused) LocalExperiencePalette.current.link
                    else LocalExperiencePalette.current.controlBorder, CupertinoTheme.shapes.small)
                .background(LocalExperiencePalette.current.surface, CupertinoTheme.shapes.small),
        )
    }

    internal fun textInputStateKey(node: DesktopUiNode.TextInput): Pair<String, Long?> =
        node.id() to node.stateRevision().takeIf { node.inputKind() == DesktopUiNode.InputKind.PASSWORD }

    @Composable
    private fun Toggle(
        node: DesktopUiNode.Toggle,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
        includeLabel: Boolean = true,
    ) {
        val documentRevision = LocalDocumentRevision.current
        var checked by remember(node.id()) { mutableStateOf(node.selected()) }
        LaunchedEffect(documentRevision, node.selected()) { checked = node.selected() }
        Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            val update: (Boolean) -> Unit = {
                checked = it
                emit(change(node.id(), node.bindingId(), DesktopUiNode.Value.bool(it)))
            }
            if (node.toggleStyle() == DesktopUiNode.ToggleStyle.SWITCH) {
                CupertinoSwitch(checked, update, enabled = node.enabled(),
                    colors = CupertinoSwitchDefaults.colors(
                        thumbColor = LocalExperiencePalette.current.onAccent,
                        checkedTrackColor = LocalExperiencePalette.current.link,
                        uncheckedTrackColor = LocalExperiencePalette.current.controlBorder,
                    ),
                    modifier = Modifier.semantics { contentDescription = resolve(node.label(), text) })
            } else {
                CupertinoCheckBox(checked, update, enabled = node.enabled(),
                    modifier = Modifier.semantics { contentDescription = resolve(node.label(), text) })
            }
            if (includeLabel) HintedTitle(help(node.help(), text)) {
                CupertinoText(resolve(node.label(), text), style = CupertinoTheme.typography.body)
            }
        }
    }

    @Composable
    private fun Choice(
        node: DesktopUiNode.Choice,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
        includeLabel: Boolean = true,
    ) {
        val documentRevision = LocalDocumentRevision.current
        val selected = remember(node.id()) { mutableStateListOf<String>().apply { addAll(node.selectedIds()) } }
        LaunchedEffect(documentRevision, node.selectedIds()) {
            selected.clear()
            selected.addAll(node.selectedIds())
        }
        fun choose(id: String) {
            if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE) {
                selected.clear(); selected.add(id)
            } else if (!selected.remove(id)) selected.add(id)
            val value = if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE)
                DesktopUiNode.Value.selection(selected.firstOrNull()) else DesktopUiNode.Value.selections(
                    selectedIdsInDocumentOrder(node.options().map { it.id() }, selected),
                )
            emit(selection(node.id(), node.bindingId(), value))
        }
        val content: @Composable () -> Unit = {
            when (node.choiceStyle()) {
                DesktopUiNode.ChoiceStyle.COMBO_BOX -> ComboChoice(node, selected.firstOrNull(), text, ::choose)
                DesktopUiNode.ChoiceStyle.RADIO_BUTTONS -> Column {
                    node.options().forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().hand(node.enabled() && option.enabled()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DesktopRadioButton(selected.contains(option.id()), { choose(option.id()) },
                                enabled = node.enabled() && option.enabled(),
                                modifier = Modifier.semantics { contentDescription = resolve(option.label(), text) })
                            CupertinoText(resolve(option.label(), text), style = CupertinoTheme.typography.body)
                        }
                    }
                }
                DesktopUiNode.ChoiceStyle.CHECK_BOXES -> Column {
                    node.options().forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().hand(node.enabled() && option.enabled()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CupertinoCheckBox(selected.contains(option.id()), { choose(option.id()) },
                                enabled = node.enabled() && option.enabled(),
                                modifier = Modifier.semantics { contentDescription = resolve(option.label(), text) })
                            CupertinoText(resolve(option.label(), text), style = CupertinoTheme.typography.body)
                        }
                    }
                }
                DesktopUiNode.ChoiceStyle.LIST -> Column {
                    node.options().forEach { option ->
                        val active = selected.contains(option.id())
                        Row(Modifier.fillMaxWidth().heightIn(min = DesktopLayout.navigationHeight)
                            .clip(CupertinoTheme.shapes.small)
                            .background(if (active) LocalExperiencePalette.current.selection else Color.Transparent)
                            .selectable(active, enabled = node.enabled() && option.enabled(), role = Role.Tab) { choose(option.id()) }
                            .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CupertinoText(resolve(option.label(), text), style = CupertinoTheme.typography.body,
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        if (includeLabel) Labeled(resolve(node.label(), text), help(node.help(), text), modifier, content)
        else Box(modifier) { content() }
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
            CupertinoButton(
                colors = CupertinoButtonDefaults.grayButtonColors(contentColor = LocalExperiencePalette.current.text),
                border = BorderStroke(1.dp, LocalExperiencePalette.current.controlBorder),
                shape = CupertinoTheme.shapes.small,
                onClick = { expanded = true },
                enabled = node.enabled(),
                modifier = Modifier.widthIn(max = DesktopLayout.choiceWidth).fillMaxWidth().heightIn(min = DesktopLayout.controlHeight)
                    .hand(node.enabled()).semantics {
                        contentDescription = resolve(node.label(), text)
                        stateDescription = label
                    },
            ) {
                CupertinoText(
                    label.ifBlank { "…" },
                    Modifier.weight(1f),
                    style = CupertinoTheme.typography.body,
                )
                DesktopIcon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
            CupertinoDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                node.options().forEach { option ->
                    MenuPickerAction(
                        isSelected = selectedId == option.id(),
                        title = { CupertinoText(resolve(option.label(), text)) },
                        enabled = option.enabled(),
                        onClick = { choose(option.id()); expanded = false },
                    )
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
        includeLabel: Boolean = true,
    ) {
        val documentRevision = LocalDocumentRevision.current
        var value by remember(node.id()) { mutableStateOf(node.value()) }
        LaunchedEffect(documentRevision, node.value()) { value = node.value() }
        fun update(next: Long) {
            value = alignedNumberValue(next, node.minimum(), node.maximum(), node.step())
            emit(change(node.id(), node.bindingId(), DesktopUiNode.Value.number(value)))
        }
        val content: @Composable () -> Unit = {
            if (node.numberStyle() == DesktopUiNode.NumberStyle.SLIDER) {
                val lastAligned = alignedNumberValue(
                    node.maximum().toLong(), node.minimum(), node.maximum(), node.step())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CupertinoSlider(
                        value.toFloat(),
                        onValueChange = { update(it.roundToInt().toLong()) },
                        valueRange = node.minimum().toFloat()..
                            (if (lastAligned > node.minimum()) lastAligned else node.maximum()).toFloat(),
                        steps = ((lastAligned.toLong() - node.minimum()) / node.step() - 1)
                            .coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                        enabled = node.enabled() && lastAligned > node.minimum(),
                        modifier = Modifier.weight(1f)
                            .semantics { contentDescription = resolve(node.label(), text) },
                    )
                    CupertinoText(value.toString(), Modifier.padding(start = 8.dp))
                }
            } else {
                var draft by remember(node.id()) { mutableStateOf(TextFieldValue(node.value().toString())) }
                LaunchedEffect(node.value()) {
                    if (draft.text != node.value().toString()) draft = TextFieldValue(node.value().toString())
                }
                CompactTextInput(
                    draft,
                    onValueChange = { next ->
                        if (!isIntegerDraft(next.text, node.minimum())) return@CompactTextInput
                        draft = next
                        numericInputValue(next.text, node.minimum(), node.maximum(), node.step())?.let {
                            value = it
                            emit(change(node.id(), node.bindingId(), DesktopUiNode.Value.number(it)))
                        }
                    },
                    enabled = node.enabled(),
                    invalid = draft.text.isNotEmpty() &&
                        numericInputValue(draft.text, node.minimum(), node.maximum(), node.step()) == null,
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    errorMessage = text(DesktopUiNode.TextToken(GuiComposePlugin.ID, "gui.compose.number-invalid", "",
                        listOf(node.minimum().toString(), node.maximum().toString(), node.step().toString()))),
                    modifier = Modifier
                        .widthIn(max = DesktopLayout.numberWidth).fillMaxWidth().heightIn(min = DesktopLayout.controlHeight)
                        .semantics { contentDescription = resolve(node.label(), text) }
                        .onFocusChanged {
                            if (!it.isFocused &&
                                numericInputValue(draft.text, node.minimum(), node.maximum(), node.step()) == null
                            ) draft = TextFieldValue(node.value().toString())
                        },
                )
            }
        }
        if (includeLabel) Labeled(resolve(node.label(), text), help(node.help(), text), modifier, content)
        else Box(modifier) { content() }
    }

    @Composable
    private fun Table(
        node: DesktopUiNode.Table,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val documentRevision = LocalDocumentRevision.current
        val selected = remember(node.id()) {
            mutableStateListOf<String>().apply { addAll(node.selectedRowIds()) }
        }
        LaunchedEffect(documentRevision, node.selectedRowIds()) {
            selected.clear()
            selected.addAll(node.selectedRowIds())
        }
        val listState = rememberLazyListState()
        val tableWidth = node.columns().sumOf {
            if (it.preferredWidth() > 0) it.preferredWidth() else 160
        }.dp
        fun choose(id: String) {
            if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE) { selected.clear(); selected.add(id) }
            else if (!selected.remove(id)) selected.add(id)
            val value = if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE)
                DesktopUiNode.Value.selection(selected.firstOrNull()) else DesktopUiNode.Value.selections(
                    selectedIdsInDocumentOrder(node.rows().map { it.id() }, selected),
                )
            emit(selection(node.id(), node.bindingId(), value))
        }
        CupertinoSurface(
            modifier = modifier.border(1.dp, LocalExperiencePalette.current.separator, CupertinoTheme.shapes.medium),
            shape = CupertinoTheme.shapes.medium,
            color = LocalExperiencePalette.current.surface,
        ) {
            Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth)) {
                Row(Modifier.fillMaxWidth().background(LocalExperiencePalette.current.secondarySurface)) {
                    node.columns().forEach { column ->
                        CupertinoText(
                            resolve(column.label(), text),
                            Modifier.width(columnWidth(column)).padding(horizontal = 10.dp, vertical = 9.dp),
                            style = CupertinoTheme.typography.subhead,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 360.dp)) {
                    LazyColumn(Modifier.fillMaxWidth().padding(end = 12.dp), state = listState) {
                        items(node.rows(), key = { it.id() }) { row ->
                            val active = selected.contains(row.id())
                            Row(
                                Modifier.fillMaxWidth().background(if (active)
                                    LocalExperiencePalette.current.selection else Color.Transparent)
                                    .hand(node.enabled())
                                    .clickable(enabled = node.enabled()) { choose(row.id()) },
                            ) {
                                row.cells().forEachIndexed { index, cell ->
                                    CupertinoText(
                                        cell,
                                        Modifier.width(columnWidth(node.columns()[index]))
                                            .padding(horizontal = 10.dp, vertical = 9.dp),
                                        style = CupertinoTheme.typography.body,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
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
        val documentRevision = LocalDocumentRevision.current
        val selected = remember(node.id()) { mutableStateListOf<String>().apply { addAll(node.selectedIds()) } }
        val branchIds = remember(node.items()) { treeBranchIds(node.items()) }
        var expandedIds by rememberSaveable(node.id()) { mutableStateOf(arrayListOf<String>()) }
        LaunchedEffect(documentRevision, node.selectedIds()) {
            selected.clear()
            selected.addAll(node.selectedIds())
        }
        LaunchedEffect(documentRevision, branchIds) {
            expandedIds = ArrayList(expandedIds.filter(branchIds::contains))
        }
        fun choose(id: String) {
            if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE) { selected.clear(); selected.add(id) }
            else if (!selected.remove(id)) selected.add(id)
            val value = if (node.selectionMode() == DesktopUiNode.SelectionMode.SINGLE)
                DesktopUiNode.Value.selection(selected.firstOrNull()) else DesktopUiNode.Value.selections(
                    selectedIdsInDocumentOrder(treeItemIds(node.items()), selected),
            )
            emit(selection(node.id(), node.bindingId(), value))
        }
        fun toggle(id: String) {
            expandedIds = ArrayList(if (expandedIds.contains(id)) expandedIds - id else expandedIds + id)
        }
        Column(
            modifier = modifier.background(
                LocalExperiencePalette.current.background,
                CupertinoTheme.shapes.medium,
            ).padding(4.dp),
        ) {
            node.items().forEach {
                TreeItem(it, 0, branchIds.isNotEmpty(), selected, expandedIds, node.enabled(), text, ::choose, ::toggle)
            }
        }
    }

    @Composable
    private fun TreeItem(
        item: DesktopUiNode.TreeItem,
        depth: Int,
        hasDisclosureColumn: Boolean,
        selected: List<String>,
        expandedIds: List<String>,
        enabled: Boolean,
        text: (DesktopUiNode.TextToken) -> String,
        choose: (String) -> Unit,
        toggle: (String) -> Unit,
    ) {
        val label = resolve(item.label(), text)
        val branch = item.children().isNotEmpty()
        val expanded = expandedIds.contains(item.id())
        val leading: (@Composable () -> Unit)? = if (!hasDisclosureColumn) null else ({
            if (branch) {
                CupertinoIconButton(
                    onClick = { toggle(item.id()) },
                    enabled = enabled,
                    modifier = Modifier.size(36.dp).semantics {
                        contentDescription = label
                        stateDescription = if (expanded) "−" else "+"
                    },
                ) { CupertinoText(if (expanded) "▾" else "▸", textAlign = TextAlign.Center) }
            } else {
                Spacer(Modifier.size(36.dp))
            }
        })
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = (depth * 18).dp)
                .clip(CupertinoTheme.shapes.small)
                .background(if (selected.contains(item.id())) LocalExperiencePalette.current.selection else Color.Transparent)
                .hand(enabled).selectable(selected.contains(item.id()), enabled = enabled, role = Role.Tab) { choose(item.id()) }
                .heightIn(min = DesktopLayout.navigationHeight).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            CupertinoText(label, fontWeight = if (selected.contains(item.id())) FontWeight.SemiBold else FontWeight.Normal)
        }
        if (expanded) item.children().forEach {
            TreeItem(it, depth + 1, hasDisclosureColumn, selected, expandedIds, enabled, text, choose, toggle)
        }
    }

    internal fun selectedIdsInDocumentOrder(
        availableIds: List<String>,
        selectedIds: Collection<String>,
    ): List<String> = availableIds.filter(selectedIds::contains)

    internal fun treeItemIds(items: List<DesktopUiNode.TreeItem>): List<String> {
        val ids = mutableListOf<String>()
        fun visit(item: DesktopUiNode.TreeItem) {
            ids += item.id()
            item.children().forEach(::visit)
        }
        items.forEach(::visit)
        return ids
    }

    internal fun treeBranchIds(items: List<DesktopUiNode.TreeItem>): List<String> {
        val ids = mutableListOf<String>()
        fun visit(item: DesktopUiNode.TreeItem) {
            if (item.children().isNotEmpty()) ids += item.id()
            item.children().forEach(::visit)
        }
        items.forEach(::visit)
        return ids
    }

    internal fun resizedSplitWeight(current: Float, delta: Float, extent: Int): Float =
        if (extent <= 0) current else (current + delta / extent).coerceIn(.1f, .9f)

    internal fun alignedNumberValue(value: Long, minimum: Int, maximum: Int, step: Int): Int {
        val bounded = value.coerceIn(minimum.toLong(), maximum.toLong())
        val steps = (bounded - minimum) / step
        return (minimum.toLong() + steps * step).toInt()
    }

    internal fun isIntegerDraft(value: String, minimum: Int): Boolean {
        if (value.isEmpty()) return true
        val digits = if (minimum < 0 && value.startsWith('-')) value.drop(1) else value
        return digits.isNotEmpty() && digits.all(Char::isDigit)
    }

    internal fun numericInputValue(
        value: String,
        minimum: Int,
        maximum: Int,
        step: Int,
    ): Int? {
        val parsed = value.toLongOrNull() ?: return null
        if (parsed !in minimum.toLong()..maximum.toLong()) return null
        return parsed.toInt().takeIf { (parsed - minimum) % step == 0L }
    }

    @Composable
    private fun ActionButton(
        node: DesktopUiNode.Button,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val click = { emit(activate(node.id(), node.actionId())) }
        val content: @Composable RowScope.() -> Unit = {
            node.icon()?.let { DesktopIcon(desktopIcon(it), null, Modifier.padding(end = 7.dp).size(18.dp)) }
            CupertinoText(
                resolve(node.label(), text),
                style = CupertinoTheme.typography.subhead,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (node.buttonStyle()) {
            DesktopUiNode.ButtonStyle.NORMAL -> CupertinoButton(
                colors = CupertinoButtonDefaults.grayButtonColors(contentColor = LocalExperiencePalette.current.text),
                border = BorderStroke(1.dp, LocalExperiencePalette.current.controlBorder),
                shape = CupertinoTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                modifier = modifier.heightIn(min = DesktopLayout.controlHeight).hand(node.enabled()),
                enabled = node.enabled(),
                onClick = click,
                content = content,
            )
            DesktopUiNode.ButtonStyle.PRIMARY -> CupertinoButton(
                colors = CupertinoButtonDefaults.filledButtonColors(contentColor = LocalExperiencePalette.current.onAccent),
                shape = CupertinoTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                modifier = modifier.heightIn(min = DesktopLayout.controlHeight).hand(node.enabled()),
                enabled = node.enabled(),
                onClick = click,
                content = content,
            )
            DesktopUiNode.ButtonStyle.DANGER -> CupertinoButton(
                shape = CupertinoTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                modifier = modifier.heightIn(min = DesktopLayout.controlHeight).hand(node.enabled()),
                enabled = node.enabled(),
                colors = CupertinoButtonDefaults.filledButtonColors(containerColor = LocalExperiencePalette.current.error, contentColor = LocalExperiencePalette.current.errorSurface),
                onClick = click,
                content = content,
            )
        }
    }

    @Composable
    private fun Labeled(label: String, help: String, modifier: Modifier, content: @Composable () -> Unit) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            CupertinoText(label, style = CupertinoTheme.typography.body, fontWeight = FontWeight.Medium)
            content()
            if (help.isNotBlank()) CupertinoText(
                help,
                style = CupertinoTheme.typography.footnote,
                color = LocalExperiencePalette.current.secondaryText,
            )
        }
    }

    @Composable
    private fun HintedTitle(help: String, content: @Composable () -> Unit) {
        if (help.isBlank()) {
            content()
            return
        }
        TooltipArea(
            tooltip = {
                CupertinoSurface(shape = CupertinoTheme.shapes.small, shadowElevation = 4.dp) {
                    CupertinoText(help, Modifier.widthIn(max = 320.dp).padding(8.dp))
                }
            },
        ) {
            Box(Modifier.semantics { contentDescription = help }) {
                content()
            }
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

    private fun Modifier.hand(enabled: Boolean): Modifier =
        if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

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
        DesktopUiNode.EventType.ACTIVATE, nodeId, DesktopUiNode.Value.empty(),
    )

    private fun change(nodeId: String, bindingId: String, value: DesktopUiNode.Value) = DesktopUiNode.Event(
        DesktopUiNode.EventType.CHANGE, nodeId, value,
    )

    private fun selection(nodeId: String, bindingId: String, value: DesktopUiNode.Value) = DesktopUiNode.Event(
        DesktopUiNode.EventType.SELECTION, nodeId, value,
    )
}

internal fun desktopIcon(icon: DesktopUiIcon): ImageVector = when (icon) {
    DesktopUiIcon.HOME -> Icons.Default.Home
    DesktopUiIcon.AUTOMATION -> Icons.Default.Schedule
    DesktopUiIcon.PLUGIN -> Icons.Default.Extension
    DesktopUiIcon.TOOLS -> Icons.Default.Build
    DesktopUiIcon.SECURITY -> Icons.Default.Security
    DesktopUiIcon.SETTINGS -> Icons.Default.Settings
    DesktopUiIcon.ABOUT, DesktopUiIcon.INFO -> Icons.Default.Info
    DesktopUiIcon.DOWNLOAD -> Icons.Default.Download
    DesktopUiIcon.QUEUE -> Icons.Default.Queue
    DesktopUiIcon.STORAGE -> Icons.Default.Storage
    DesktopUiIcon.STATISTICS -> Icons.Default.BarChart
    DesktopUiIcon.TASK -> Icons.AutoMirrored.Filled.Assignment
    DesktopUiIcon.SUCCESS -> Icons.Default.CheckCircle
    DesktopUiIcon.WARNING -> Icons.Default.Warning
    DesktopUiIcon.ERROR -> Icons.Default.Error
    DesktopUiIcon.OPEN -> Icons.AutoMirrored.Filled.OpenInNew
}
