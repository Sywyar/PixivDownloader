package top.sywyar.pixivdownload.guicompose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image as SkiaImage
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode
import javax.swing.JFileChooser
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Compose Multiplatform renderer for the complete stable desktop node vocabulary. */
@OptIn(ExperimentalComposeUiApi::class)
object ComposeDesktopUiNodeRenderer {
    private val LocalDocumentRevision = staticCompositionLocalOf { 0L }

    fun supportedKinds(): Set<DesktopUiNode.Kind> =
        setOf(
            DesktopUiNode.Kind.CONTAINER, DesktopUiNode.Kind.ADAPTIVE_GRID, DesktopUiNode.Kind.PAGED_ROW,
            DesktopUiNode.Kind.DOCK, DesktopUiNode.Kind.SURFACE,
            DesktopUiNode.Kind.GROUP, DesktopUiNode.Kind.FORM, DesktopUiNode.Kind.TABS,
            DesktopUiNode.Kind.SCROLL, DesktopUiNode.Kind.SPLIT, DesktopUiNode.Kind.TEXT, DesktopUiNode.Kind.ICON,
            DesktopUiNode.Kind.IMAGE, DesktopUiNode.Kind.SEPARATOR, DesktopUiNode.Kind.SPACER,
            DesktopUiNode.Kind.PROGRESS, DesktopUiNode.Kind.TEXT_INPUT, DesktopUiNode.Kind.TOGGLE,
            DesktopUiNode.Kind.CHOICE, DesktopUiNode.Kind.NUMBER_INPUT, DesktopUiNode.Kind.TABLE,
            DesktopUiNode.Kind.TREE, DesktopUiNode.Kind.BUTTON, DesktopUiNode.Kind.LINK,
        )

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
            is DesktopUiNode.Group -> Card(
                modifier = modifier.animateContentSize(tween(180)),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f),
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        resolve(node.title(), text),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Node(node.content(), text, emit, Modifier.fillMaxWidth())
                }
            }
            is DesktopUiNode.Form -> Form(node, text, emit, modifier)
            is DesktopUiNode.Tabs -> Tabs(node, text, emit, modifier)
            is DesktopUiNode.Scroll -> ScrollNode(node, text, emit, modifier)
            is DesktopUiNode.Split -> Split(node, text, emit, modifier)
            is DesktopUiNode.Text -> StyledText(node, text, modifier)
            is DesktopUiNode.Icon -> Icon(node, text, modifier)
            is DesktopUiNode.Image -> ImageNode(node, text, modifier)
            is DesktopUiNode.Separator -> if (node.axis() == DesktopUiNode.Axis.HORIZONTAL) {
                HorizontalDivider(modifier.fillMaxWidth())
            } else {
                VerticalDivider(modifier.fillMaxHeight().width(1.dp))
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
                modifier = modifier.hand(node.enabled()),
                enabled = node.enabled(),
                onClick = { emit(activate(node.id(), node.actionId())) },
            ) { Text(resolve(node.label(), text), style = MaterialTheme.typography.labelLarge) }
        }
    }

    @Composable
    private fun ScrollNode(
        node: DesktopUiNode.Scroll,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        ScrollableContent(modifier) { Node(node.content(), text, emit, Modifier.fillMaxWidth()) }
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
                        unhoverColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f),
                        hoverColor = MaterialTheme.colorScheme.primary.copy(alpha = .72f),
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
        if (node.style() == DesktopUiNode.SurfaceStyle.PLAIN) {
            Box(sized) { Node(node.content(), text, emit, contentModifier) }
            return
        }
        val dark = MaterialTheme.colorScheme.background.luminance() < .5f
        val accent = when (node.style()) {
            DesktopUiNode.SurfaceStyle.INFO -> if (dark) Color(0xFFAEC6FF) else Color(0xFF3F67C6)
            DesktopUiNode.SurfaceStyle.SUCCESS -> if (dark) Color(0xFF70D7B5) else Color(0xFF168262)
            DesktopUiNode.SurfaceStyle.WARNING -> if (dark) Color(0xFFFFC56B) else Color(0xFF9A6500)
            DesktopUiNode.SurfaceStyle.ERROR -> MaterialTheme.colorScheme.error
            DesktopUiNode.SurfaceStyle.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.outlineVariant
        }
        val background = when (node.style()) {
            DesktopUiNode.SurfaceStyle.CARD -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .34f)
            DesktopUiNode.SurfaceStyle.MUTED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)
            else -> accent.copy(alpha = .09f)
        }
        androidx.compose.material3.Surface(
            modifier = sized.animateContentSize(tween(180)),
            color = background,
            shape = MaterialTheme.shapes.large,
            tonalElevation = if (node.style() == DesktopUiNode.SurfaceStyle.CARD) 1.dp else 0.dp,
            border = BorderStroke(1.dp, accent.copy(alpha = if (
                node.style() == DesktopUiNode.SurfaceStyle.CARD) .42f else .58f)),
        ) { Node(node.content(), text, emit, contentModifier) }
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
            ) { node.children().forEach { child(it, childModifier(node.alignment())) } }
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
            DesktopUiNode.ContainerLayout.GRID -> Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
                node.children().chunked(node.columns()).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { value -> Box(Modifier.weight(1f)) { child(value, Modifier.fillMaxWidth()) } }
                        repeat(node.columns() - row.size) { Spacer(Modifier.weight(1f)) }
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(node.horizontalGap().dp)) {
                        row.forEach { child ->
                            Box(Modifier.weight(1f)) { Node(child, text, emit, Modifier.fillMaxWidth()) }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(node.gap().dp)) {
                children.forEach { child ->
                    Box(Modifier.weight(1f)) { Node(child, text, emit, Modifier.fillMaxWidth()) }
                }
                repeat(node.itemsPerPage() - children.size) { Spacer(Modifier.weight(1f)) }
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
    private fun Form(
        node: DesktopUiNode.Form,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val suffix = node.labelSuffix()?.let { resolve(it, text) }.orEmpty()
        BoxWithConstraints(modifier) {
            val stacked = when (node.formStyle()) {
                DesktopUiNode.FormStyle.RESPONSIVE,
                DesktopUiNode.FormStyle.COMPACT -> maxWidth < 760.dp
                DesktopUiNode.FormStyle.KEY_VALUE -> false
            }
            val labelWidth = when (node.formStyle()) {
                DesktopUiNode.FormStyle.RESPONSIVE -> 164.dp
                DesktopUiNode.FormStyle.COMPACT -> 132.dp
                DesktopUiNode.FormStyle.KEY_VALUE -> 126.dp
            }
            val labelWeight = if (node.formStyle() == DesktopUiNode.FormStyle.KEY_VALUE)
                FontWeight.Normal else FontWeight.Medium
            val labelColor = if (node.formStyle() == DesktopUiNode.FormStyle.KEY_VALUE)
                MaterialTheme.colorScheme.onSurface.copy(alpha = .6f) else Color.Unspecified
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                node.rows().forEach { row ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (stacked) {
                            Text(
                                resolve(row.label(), text) + suffix,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = labelWeight,
                                color = labelColor,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.weight(1f)) {
                                    FormContent(row.content(), text, emit, Modifier.fillMaxWidth())
                                }
                                row.trailing()?.let { Node(it, text, emit) }
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    resolve(row.label(), text) + suffix,
                                    Modifier.width(labelWidth),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = labelWeight,
                                    color = labelColor,
                                )
                                Box(Modifier.weight(1f)) {
                                    FormContent(row.content(), text, emit, Modifier.fillMaxWidth())
                                }
                                row.trailing()?.let { Node(it, text, emit) }
                            }
                        }
                        val help = row.help()?.let { resolve(it, text) }.orEmpty()
                        if (help.isNotBlank()) {
                            if (stacked) Text(help, style = MaterialTheme.typography.bodySmall)
                            else Row(Modifier.fillMaxWidth()) {
                                Spacer(Modifier.width(labelWidth))
                                Text(help, Modifier.padding(start = 8.dp).weight(1f),
                                    style = MaterialTheme.typography.bodySmall)
                            }
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

    @Composable
    private fun Tabs(
        node: DesktopUiNode.Tabs,
        text: (DesktopUiNode.TextToken) -> String,
        emit: (DesktopUiNode.Event) -> Unit,
        modifier: Modifier,
    ) {
        val tabIds = node.tabs().map { it.id() }
        var selectedId by rememberSaveable(node.id()) { mutableStateOf(tabIds.first()) }
        val activeTabId = selectedIdOrFirst(selectedId, tabIds)
        val selectedIndex = tabIds.indexOf(activeTabId)
        LaunchedEffect(activeTabId) { selectedId = activeTabId }
        Column(modifier) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedIndex,
                edgePadding = 4.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .36f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                divider = {},
            ) {
                node.tabs().forEach { tab ->
                    Tab(activeTabId == tab.id(), onClick = { selectedId = tab.id() }, text = {
                        Text(
                            resolve(tab.title(), text),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (activeTabId == tab.id()) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    })
                }
            }
            AnimatedContent(
                targetState = activeTabId,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
                transitionSpec = {
                    val direction = if (tabIds.indexOf(targetState) >= tabIds.indexOf(initialState)) 1 else -1
                    (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { direction * it / 24 })
                        .togetherWith(fadeOut(tween(120)) +
                                slideOutHorizontally(tween(180)) { -direction * it / 30 })
                },
                contentKey = { it },
            ) { tabId ->
                Node(node.tabs().first { it.id() == tabId }.content(), text, emit, Modifier.fillMaxSize())
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
                    VerticalDivider(
                        Modifier.fillMaxHeight().width(9.dp)
                            .pointerInput(node.id(), splitWidth) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    firstWeight = resizedSplitWeight(firstWeight, drag.x, splitWidth)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Box(Modifier.weight(1f - firstWeight)) {
                        Node(node.second(), text, emit, Modifier.fillMaxSize())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(firstWeight)) { Node(node.first(), text, emit, Modifier.fillMaxSize()) }
                    HorizontalDivider(
                        Modifier.fillMaxWidth().height(9.dp)
                            .pointerInput(node.id(), splitHeight) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    firstWeight = resizedSplitWeight(firstWeight, drag.y, splitHeight)
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
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
            DesktopUiNode.TextStyle.TITLE -> MaterialTheme.typography.headlineSmall
            DesktopUiNode.TextStyle.HEADING -> MaterialTheme.typography.titleLarge
            DesktopUiNode.TextStyle.EMPHASIS -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            DesktopUiNode.TextStyle.CAPTION -> MaterialTheme.typography.bodySmall
            DesktopUiNode.TextStyle.CODE -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            else -> MaterialTheme.typography.bodyMedium
        }
        val dark = MaterialTheme.colorScheme.background.luminance() < .5f
        val color = when (node.style()) {
            DesktopUiNode.TextStyle.SUCCESS -> if (dark) Color(0xFF70D7B5) else Color(0xFF168262)
            DesktopUiNode.TextStyle.WARNING -> if (dark) Color(0xFFFFC56B) else Color(0xFF9A6500)
            DesktopUiNode.TextStyle.ERROR -> MaterialTheme.colorScheme.error
            DesktopUiNode.TextStyle.SECONDARY,
            DesktopUiNode.TextStyle.CAPTION -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> Color.Unspecified
        }
        val content: @Composable () -> Unit = {
            val value = resolve(node.text(), text).let {
                if (node.style() == DesktopUiNode.TextStyle.BULLET) "• $it" else it
            }
            Text(value, modifier = modifier, style = style, color = color,
                softWrap = node.wrap(), textAlign = when (node.textAlignment()) {
                    DesktopUiNode.TextAlignment.START -> TextAlign.Start
                    DesktopUiNode.TextAlignment.CENTER -> TextAlign.Center
                    DesktopUiNode.TextAlignment.END -> TextAlign.End
                })
        }
        if (node.selectable()) SelectionContainer(content = content) else content()
    }

    @Composable
    private fun Icon(
        node: DesktopUiNode.Icon,
        text: (DesktopUiNode.TextToken) -> String,
        modifier: Modifier,
    ) {
        Text(
            iconGlyph(node.icon()),
            modifier.semantics { contentDescription = resolve(node.accessibleLabel(), text) },
            color = toneColor(node.tone()),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }

    private fun iconGlyph(icon: DesktopUiIcon): String = when (icon) {
        DesktopUiIcon.HOME -> "⌂"
        DesktopUiIcon.AUTOMATION -> "◷"
        DesktopUiIcon.PLUGIN -> "⧉"
        DesktopUiIcon.TOOLS -> "⚒"
        DesktopUiIcon.SECURITY -> "◆"
        DesktopUiIcon.SETTINGS -> "⚙"
        DesktopUiIcon.ABOUT, DesktopUiIcon.INFO -> "i"
        DesktopUiIcon.DOWNLOAD -> "↓"
        DesktopUiIcon.QUEUE -> "≡"
        DesktopUiIcon.STORAGE -> "▣"
        DesktopUiIcon.STATISTICS -> "▥"
        DesktopUiIcon.TASK -> "•"
        DesktopUiIcon.SUCCESS -> "✓"
        DesktopUiIcon.WARNING -> "!"
        DesktopUiIcon.ERROR -> "×"
        DesktopUiIcon.OPEN -> "↗"
    }

    @Composable
    private fun toneColor(tone: DesktopUiTone): Color = when (tone) {
        DesktopUiTone.DEFAULT -> MaterialTheme.colorScheme.onSurface
        DesktopUiTone.SUCCESS -> MaterialTheme.colorScheme.tertiary
        DesktopUiTone.INFO -> MaterialTheme.colorScheme.primary
        DesktopUiTone.WARNING -> MaterialTheme.colorScheme.secondary
        DesktopUiTone.ERROR -> MaterialTheme.colorScheme.error
    }

    @Composable
    private fun ImageNode(
        node: DesktopUiNode.Image,
        text: (DesktopUiNode.TextToken) -> String,
        modifier: Modifier,
    ) {
        val source = remember(node.image()) { runCatching { SkiaImage.makeFromEncoded(node.image().bytes()) }.getOrNull() }
        if (source == null) {
            Text(resolve(node.altText(), text), modifier = modifier)
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
            if (node.indeterminate()) LinearProgressIndicator(
                Modifier.fillMaxWidth().height(5.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            else LinearProgressIndicator(
                { node.progress().toFloat() },
                Modifier.fillMaxWidth().height(5.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            node.text()?.let { Text(resolve(it, text), style = MaterialTheme.typography.bodySmall) }
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
        var value by remember(textInputStateKey(node)) { mutableStateOf(if (password) "" else node.value()) }
        if (!password) LaunchedEffect(documentRevision, node.value()) { value = node.value() }
        fun update(next: String) {
            value = next
            emit(change(node.id(), node.bindingId(), DesktopUiNode.Value.text(next)))
        }
        val content: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = ::update,
                    enabled = node.enabled(),
                    singleLine = node.inputKind() != DesktopUiNode.InputKind.MULTILINE,
                    visualTransformation = if (node.inputKind() == DesktopUiNode.InputKind.PASSWORD)
                        PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                        .widthIn(min = (node.columns() * 8).coerceAtMost(320).dp)
                        .then(if (node.inputKind() == DesktopUiNode.InputKind.MULTILINE)
                            Modifier.heightIn(min = 88.dp) else Modifier.height(48.dp)),
                )
                if (node.inputKind() == DesktopUiNode.InputKind.FILE
                    || node.inputKind() == DesktopUiNode.InputKind.DIRECTORY) {
                    OutlinedButton(
                        onClick = { choosePath(node.inputKind(), value)?.let(::update) },
                        enabled = node.enabled(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.padding(start = 8.dp).hand(node.enabled()),
                    ) { Text(text(DesktopUiNode.TextToken(
                        GuiComposePlugin.ID, "gui.compose.browse", "Browse...", emptyList(),
                    ))) }
                }
            }
        }
        if (includeLabel) Labeled(resolve(node.label(), text), help(node.help(), text), modifier, content)
        else Box(modifier) { content() }
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
                Switch(checked, update, enabled = node.enabled())
            } else {
                Checkbox(checked, update, enabled = node.enabled())
            }
            if (includeLabel) Text(resolve(node.label(), text), style = MaterialTheme.typography.bodyMedium)
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
                            RadioButton(selected.contains(option.id()), { choose(option.id()) },
                                enabled = node.enabled() && option.enabled())
                            Text(resolve(option.label(), text), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                DesktopUiNode.ChoiceStyle.CHECK_BOXES -> Column {
                    node.options().forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().hand(node.enabled() && option.enabled()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(selected.contains(option.id()), { choose(option.id()) },
                                enabled = node.enabled() && option.enabled())
                            Text(resolve(option.label(), text), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                DesktopUiNode.ChoiceStyle.LIST -> Column {
                    node.options().forEach { option ->
                        val active = selected.contains(option.id())
                        androidx.compose.material3.Surface(
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                resolve(option.label(), text),
                                modifier = Modifier.fillMaxWidth().hand(node.enabled() && option.enabled())
                                    .clickable(enabled = node.enabled() && option.enabled()) { choose(option.id()) }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (option.enabled()) Color.Unspecified
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = .4f),
                            )
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
            OutlinedButton(
                onClick = { expanded = true },
                enabled = node.enabled(),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.widthIn(min = 180.dp, max = 360.dp).hand(node.enabled()),
            ) {
                Text(
                    label.ifBlank { "…" },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("⌄", Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                node.options().forEach { option ->
                    DropdownMenuItem(
                        text = { Text(resolve(option.label(), text)) },
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
                    Slider(
                        value.toFloat(),
                        onValueChange = { update(it.roundToInt().toLong()) },
                        valueRange = node.minimum().toFloat()..
                            (if (lastAligned > node.minimum()) lastAligned else node.maximum()).toFloat(),
                        steps = ((lastAligned.toLong() - node.minimum()) / node.step() - 1)
                            .coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                        enabled = node.enabled() && lastAligned > node.minimum(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(value.toString(), Modifier.padding(start = 8.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val canDecrease = node.enabled() && value > node.minimum()
                    val lastAligned = alignedNumberValue(
                        node.maximum().toLong(), node.minimum(), node.maximum(), node.step())
                    val canIncrease = node.enabled() && value < lastAligned
                    OutlinedButton(
                        { update(value.toLong() - node.step()) },
                        enabled = canDecrease,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.hand(canDecrease),
                    ) { Text("−") }
                    Text(
                        value.toString(),
                        Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedButton(
                        { update(value.toLong() + node.step()) },
                        enabled = canIncrease,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.hand(canIncrease),
                    ) { Text("+") }
                }
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
        androidx.compose.material3.Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth)) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))) {
                    node.columns().forEach { column ->
                        Text(
                            resolve(column.label(), text),
                            Modifier.width(columnWidth(column)).padding(horizontal = 10.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelLarge,
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
                                    MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .hand(node.enabled())
                                    .clickable(enabled = node.enabled()) { choose(row.id()) },
                            ) {
                                row.cells().forEachIndexed { index, cell ->
                                    Text(
                                        cell,
                                        Modifier.width(columnWidth(node.columns()[index]))
                                            .padding(horizontal = 10.dp, vertical = 9.dp),
                                        style = MaterialTheme.typography.bodyMedium,
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
        LaunchedEffect(documentRevision, node.selectedIds()) {
            selected.clear()
            selected.addAll(node.selectedIds())
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
        Column(
            modifier = modifier.background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .24f),
                MaterialTheme.shapes.medium,
            ).padding(4.dp),
        ) {
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
            modifier = Modifier.fillMaxWidth().background(
                if (selected.contains(item.id())) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                MaterialTheme.shapes.small,
            ).hand(enabled).clickable(enabled) { choose(item.id()) }
                .padding(start = (depth * 18 + 10).dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected.contains(item.id())) FontWeight.SemiBold else FontWeight.Normal,
        )
        item.children().forEach { TreeItem(it, depth + 1, selected, enabled, text, choose) }
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

    internal fun resizedSplitWeight(current: Float, delta: Float, extent: Int): Float =
        if (extent <= 0) current else (current + delta / extent).coerceIn(.1f, .9f)

    internal fun alignedNumberValue(value: Long, minimum: Int, maximum: Int, step: Int): Int {
        val bounded = value.coerceIn(minimum.toLong(), maximum.toLong())
        val steps = (bounded - minimum) / step
        return (minimum.toLong() + steps * step).toInt()
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
            Text(
                resolve(node.label(), text),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (node.buttonStyle()) {
            DesktopUiNode.ButtonStyle.NORMAL -> FilledTonalButton(
                modifier = modifier.heightIn(min = 40.dp).hand(node.enabled()),
                enabled = node.enabled(),
                shape = MaterialTheme.shapes.medium,
                onClick = click,
                content = content,
            )
            DesktopUiNode.ButtonStyle.PRIMARY -> Button(
                modifier = modifier.heightIn(min = 40.dp).hand(node.enabled()),
                enabled = node.enabled(),
                shape = MaterialTheme.shapes.medium,
                onClick = click,
                content = content,
            )
            DesktopUiNode.ButtonStyle.DANGER -> Button(
                modifier = modifier.heightIn(min = 40.dp).hand(node.enabled()),
                enabled = node.enabled(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                onClick = click,
                content = content,
            )
        }
    }

    @Composable
    private fun Labeled(label: String, help: String, modifier: Modifier, content: @Composable () -> Unit) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            content()
            if (help.isNotBlank()) Text(
                help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
