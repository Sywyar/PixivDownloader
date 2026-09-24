@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose.onboarding

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.robinpcrd.cupertino.*
import top.sywyar.pixivdownload.guicompose.DesktopIcon
import top.sywyar.pixivdownload.guicompose.LocalExperiencePalette
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode

@Composable
internal fun OnboardingGuideHub(
    node: DesktopUiNode.OnboardingHub,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
    modifier: Modifier = Modifier,
    render: @Composable (DesktopUiNode, Modifier) -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val scope = rememberCoroutineScope()
    val reducedMotion = scope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    var selectedId by remember(node.id()) { mutableStateOf<String?>(null) }
    var lastSelectedId by remember(node.id()) { mutableStateOf<String?>(null) }
    val focus = remember(node.cards().map { it.id() }) { node.cards().associate { it.id() to FocusRequester() } }
    val expanded = selectedId != null
    val expansion by animateFloatAsState(
        if (expanded) 1f else 0f,
        if (reducedMotion) snap() else spring(dampingRatio = 1f, stiffness = 360f),
        label = "hub-expansion",
    )
    fun message(key: String) = text(hubToken(key))
    fun select(id: String) {
        lastSelectedId = id
        selectedId = id
    }
    fun back() {
        selectedId = null
    }
    LaunchedEffect(expanded) {
        if (!expanded && lastSelectedId in focus) {
            withFrameNanos { }
            focus[lastSelectedId]?.requestFocus()
        }
    }
    LaunchedEffect(node.cards().map { it.id() }) {
        if (node.cards().none { it.id() == selectedId }) selectedId = null
    }
    LaunchedEffect(node.cards().map { it.settings()?.validationAttempt() }) {
        node.cards().firstOrNull {
            it.settings()?.let { settings -> settings.validationAttempt() > 0 && settings.invalid() } == true
        }?.let { select(it.id()) }
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)
            .onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyUp && expanded) {
                    back()
                    true
                } else false
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CupertinoText(
            message("title"),
            modifier = Modifier.hubEntrance(0, reducedMotion),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.text,
        )
        Spacer(Modifier.height(8.dp))
        CupertinoText(message("intro"), modifier = Modifier.hubEntrance(40, reducedMotion), fontSize = 13.sp, color = palette.secondaryText)
        Spacer(Modifier.height(16.dp))
        BoxWithConstraints(Modifier.weight(1f).widthIn(max = 1040.dp).fillMaxWidth()) {
            val compact = maxWidth < 780.dp
            val expandedWidth = 280.dp
            val cardWidth = minOf(400.dp, maxWidth)
            if (compact) {
                AnimatedContent(
                    selectedId,
                    transitionSpec = {
                        val direction = if (targetState == null) -1 else 1
                        (fadeIn(tween(180)) + slideInHorizontally(tween(280)) {
                            if (reducedMotion) 0 else direction * 24
                        }) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(220)) {
                            if (reducedMotion) 0 else -direction * 24
                        })
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "hub-compact-navigation",
                ) { id ->
                    val card = node.cards().find { it.id() == id }
                    if (card == null) {
                        CardList(
                            node.cards(),
                            selectedId,
                            focus,
                            text,
                            ::select,
                            Modifier.fillMaxSize(),
                            reducedMotion,
                            cardWidth,
                        )
                    } else {
                        GuideDetail(card, text, emit, reducedMotion, ::back, Modifier.fillMaxSize())
                    }
                }
            } else {
                CardList(
                    node.cards(),
                    selectedId,
                    focus,
                    text,
                    ::select,
                    Modifier.zIndex(1f).fillMaxHeight().offset {
                        IntOffset((((maxWidth - cardWidth) / 2) * (1 - expansion)).roundToPx(), 0)
                    }.width(cardWidth + (expandedWidth - cardWidth) * expansion),
                    reducedMotion,
                )
                androidx.compose.animation.AnimatedVisibility(
                    expanded,
                    enter = fadeIn(tween(220)) + slideInHorizontally(tween(320)) { if (reducedMotion) 0 else 32 },
                    exit = fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { if (reducedMotion) 0 else 24 },
                    modifier = Modifier.padding(start = expandedWidth + 32.dp).fillMaxSize(),
                ) {
                    Crossfade(lastSelectedId, animationSpec = tween(180), label = "hub-detail") { id ->
                        node.cards().find { it.id() == id }?.let { card ->
                            GuideDetail(card, text, emit, reducedMotion, ::back, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        Row(
            Modifier.widthIn(max = 1040.dp).fillMaxWidth().padding(top = 12.dp).hubEntrance(160, reducedMotion),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).animateContentSize(tween(180))) {
                Crossfade(node.notice(), animationSpec = tween(160), label = "hub-notice") { notice ->
                    notice?.let { render(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
            }
            HubButton(node.next(), text, emit,
                Modifier.widthIn(min = 96.dp).testTag(node.next().id()).semantics { liveRegion = LiveRegionMode.Polite },
                busy = node.submitting(), reducedMotion = reducedMotion)
        }
    }
}

@Composable
private fun CardList(
    cards: List<DesktopUiNode.OnboardingCard>,
    selectedId: String?,
    focus: Map<String, FocusRequester>,
    text: (DesktopUiNode.TextToken) -> String,
    select: (String) -> Unit,
    modifier: Modifier,
    reducedMotion: Boolean,
    cardWidth: androidx.compose.ui.unit.Dp? = null,
) {
    BoxWithConstraints(modifier) {
        val maximumCardWidth = cardWidth ?: maxWidth
        Column(
            Modifier.fillMaxWidth().selectableGroup().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            cards.forEachIndexed { index, card ->
                key(card.id()) {
                    GuideCard(
                        card,
                        selectedId == card.id(),
                        text,
                        { select(card.id()) },
                        Modifier.widthIn(max = maximumCardWidth).fillMaxWidth()
                            .hubEntrance(60 + index * 40, reducedMotion)
                            .focusRequester(focus.getValue(card.id()))
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideCard(
    card: DesktopUiNode.OnboardingCard,
    selected: Boolean,
    text: (DesktopUiNode.TextToken) -> String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val palette = LocalExperiencePalette.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(stiffness = 800f), label = "card-press")
    val background by animateColorAsState(
        if (selected) palette.accent.copy(alpha = .09f) else if (hovered) palette.secondarySurface else palette.surface,
        tween(120),
        label = "card-background",
    )
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.testTag("hub.card." + card.id())
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(background)
            .border(if (focused) 2.dp else 1.dp, if (focused || selected) palette.accent else palette.separator, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .selectable(selected, interactions, null, role = Role.Tab, onClick = onClick)
            .padding(18.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DesktopIcon(
            when (card.topic()) {
                DesktopUiNode.OnboardingTopic.NETWORK -> Icons.Default.Language
                DesktopUiNode.OnboardingTopic.DOWNLOAD -> Icons.Default.Download
                DesktopUiNode.OnboardingTopic.GUIDE -> Icons.Default.GridView
                DesktopUiNode.OnboardingTopic.ANIMATION -> Icons.Default.Movie
            },
            null,
            Modifier.size(28.dp),
            palette.accent,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            CupertinoText(text(card.title()), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            CupertinoText(
                text(card.summary()),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.secondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DesktopIcon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), palette.secondaryText)
    }
}

@Composable
private fun GuideDetail(
    card: DesktopUiNode.OnboardingCard,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
    reducedMotion: Boolean,
    back: () -> Unit,
    modifier: Modifier,
) {
    val palette = LocalExperiencePalette.current
    val backFocus = remember(card.id()) { FocusRequester() }
    LaunchedEffect(card.id()) { backFocus.requestFocus() }
    BoxWithConstraints(modifier.testTag("hub.detail." + card.id())) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CupertinoButton(
                    onClick = back,
                    colors = CupertinoButtonDefaults.plainButtonColors(contentColor = palette.secondaryText),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.focusRequester(backFocus).testTag("hub.back." + card.id()),
                ) {
                    DesktopIcon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp), palette.secondaryText)
                    Spacer(Modifier.width(6.dp))
                    CupertinoText(text(hubToken("back")), fontSize = 13.sp)
                }
                AnimatedVisibility(card.opened(), enter = fadeIn(tween(160)) + scaleIn(tween(220), initialScale = .9f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DesktopIcon(Icons.Default.Check, null, Modifier.size(14.dp), palette.secondaryText)
                        Spacer(Modifier.width(4.dp))
                        CupertinoText(text(hubToken("opened")), fontSize = 12.sp, color = palette.secondaryText)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HubButton(card.open(), text, emit, Modifier.testTag(card.open().id()), external = true)
                CupertinoText(
                    text(hubToken("browser")),
                    fontSize = 12.sp,
                    color = palette.secondaryText,
                )
            }
            OnboardingDemo(card.topic(), text, reducedMotion, Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CupertinoText(text(card.title()), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                CupertinoText(text(card.description()), fontSize = 13.sp, lineHeight = 21.sp, color = palette.secondaryText)
            }
            card.settings()?.let { OnboardingProxyForm(it, text, emit) }
        }
    }
}

@Composable
private fun HubButton(
    button: DesktopUiNode.Button,
    text: (DesktopUiNode.TextToken) -> String,
    emit: (DesktopUiNode.Event) -> Unit,
    modifier: Modifier,
    external: Boolean = false,
    busy: Boolean = false,
    reducedMotion: Boolean = false,
) {
    val palette = LocalExperiencePalette.current
    CupertinoButton(
        onClick = { emit(DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, button.id(), DesktopUiNode.Value.empty())) },
        enabled = button.enabled(),
        colors = CupertinoButtonDefaults.filledButtonColors(contentColor = palette.onAccent),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        if (busy && !reducedMotion) {
            CupertinoActivityIndicator(Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        Crossfade(button.label(), animationSpec = tween(140), label = "hub-button-label") {
            CupertinoText(text(it), fontSize = 14.sp)
        }
        if (external) {
            Spacer(Modifier.width(8.dp))
            DesktopIcon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(15.dp), palette.onAccent)
        }
    }
}

@Composable
private fun Modifier.hubEntrance(delay: Int, reducedMotion: Boolean): Modifier {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val appearance by animateFloatAsState(
        if (visible) 1f else 0f,
        if (reducedMotion) snap() else tween(300, delayMillis = delay, easing = FastOutSlowInEasing),
        label = "hub-entrance",
    )
    return graphicsLayer {
        alpha = appearance
        translationY = if (reducedMotion) 0f else (1 - appearance) * 12.dp.toPx()
    }
}

internal fun hubToken(key: String) = DesktopUiNode.TextToken(
    "gui-compose",
    "gui.compose.onboarding.hub.$key",
    "",
    emptyList(),
)
