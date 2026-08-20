package top.sywyar.pixivdownload.guicompose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.shape.RoundedCornerShape
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSnapshot
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.concurrent.thread

internal object ComposeDesktopUi {
    fun launch(context: DesktopUiContext): DesktopUiSession {
        val trayAtLaunch = context.currentSnapshot().document().tray().isPresent && SystemTray.isSupported()
        val visible = mutableStateOf(!context.startupLaunch() || !trayAtLaunch)
        val message = mutableStateOf<UiMessage?>(null)
        val windowRef = AtomicReference<ComposeWindow>()
        val exit = AtomicReference<() -> Unit>()
        val failure = AtomicReference<Throwable>()
        val ready = CountDownLatch(1)

        val uiThread = thread(name = "pixivdownload-compose-ui", isDaemon = false) {
            try {
                application(exitProcessOnExit = false) {
                    exit.set { exitApplication() }
                    val observed = rememberDesktopDocument(context)
                    val document = observed.document()
                    val messages = remember(context, observed.revision()) { ComposeMessages(context) }
                    val tray = document.tray().orElse(null)
                    val trayAvailable = tray != null && SystemTray.isSupported()
                    val trayPopup = remember { mutableStateOf<TrayPopupRequest?>(null) }
                    if (tray != null && trayAvailable) {
                        val trayIcon = remember { TrayIcon(createTrayIcon()).apply { isImageAutoSize = true } }
                        SideEffect { trayIcon.toolTip = messages.resolve(tray.tooltip()) }
                        DisposableEffect(trayIcon) {
                            val activateListener = ActionListener { activateWindow(visible, windowRef) }
                            val popupListener = object : MouseAdapter() {
                                override fun mouseReleased(event: MouseEvent) {
                                    if (SwingUtilities.isRightMouseButton(event)) {
                                        val anchor = MouseInfo.getPointerInfo()?.location
                                            ?: Point(event.locationOnScreen)
                                        trayPopup.value = TrayPopupRequest(anchor)
                                    }
                                }
                            }
                            trayIcon.addActionListener(activateListener)
                            trayIcon.addMouseListener(popupListener)
                            SystemTray.getSystemTray().add(trayIcon)
                            onDispose {
                                SystemTray.getSystemTray().remove(trayIcon)
                                trayIcon.removeActionListener(activateListener)
                                trayIcon.removeMouseListener(popupListener)
                            }
                        }
                        trayPopup.value?.let { popup ->
                            TrayPopup(
                                tray = tray,
                                themePreference = context.themePreference(),
                                messages = messages,
                                request = popup,
                                onDismiss = { trayPopup.value = null },
                                onSelect = { item ->
                                    trayPopup.value = null
                                    when (item.role()) {
                                        DesktopUiDocument.TrayItemRole.ACTIVATE_WINDOW ->
                                            activateWindow(visible, windowRef)
                                        DesktopUiDocument.TrayItemRole.DISPATCH ->
                                            context.dispatchEvent(observed.revision(), DesktopUiNode.Event(
                                                DesktopUiNode.EventType.ACTIVATE,
                                                item.id(),
                                                DesktopUiNode.Value.empty(),
                                            ))
                                        DesktopUiDocument.TrayItemRole.SEPARATOR -> Unit
                                    }
                                },
                            )
                        }
                    }
                    val mainWindowState = rememberWindowState(width = 1120.dp, height = 760.dp)
                    Window(
                        onCloseRequest = {
                            if (trayAvailable) visible.value = false
                            else context.requestApplicationExit()
                        },
                        state = mainWindowState,
                        visible = visible.value,
                        title = context.applicationName(),
                    ) {
                        val composeWindow = window
                        val shortcutDispatcher = remember(context) { ComposeShortcutDispatcher(context) }
                        DisposableEffect(composeWindow) {
                            windowRef.set(composeWindow)
                            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                .addKeyEventDispatcher(shortcutDispatcher)
                            ready.countDown()
                            onDispose {
                                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                    .removeKeyEventDispatcher(shortcutDispatcher)
                                windowRef.compareAndSet(composeWindow, null)
                            }
                        }
                        PixivDownloaderTheme(context.themePreference()) {
                            Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                                ComposeDesktopRoot(context, observed, messages)
                                message.value?.let { current ->
                                    AlertDialog(
                                        onDismissRequest = { message.value = null },
                                        title = { Text(current.title) },
                                        text = { Text(current.message) },
                                        confirmButton = {
                                            TextButton(onClick = { message.value = null }) {
                                                Text(ComposeMessages(context).plugin("gui.compose.ok"))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (problem: Throwable) {
                failure.set(problem)
                ready.countDown()
            }
        }

        if (!ready.await(30, TimeUnit.SECONDS)) {
            exit.get()?.invoke()
            throw IllegalStateException("Timed out while starting the Compose desktop UI")
        }
        failure.get()?.let { throw unwrap(it) }
        return Session(visible, message, windowRef, exit, uiThread)
    }

    private fun unwrap(problem: Throwable): RuntimeException {
        val cause = if (problem is InvocationTargetException && problem.cause != null) problem.cause!! else problem
        return cause as? RuntimeException ?: IllegalStateException("Failed to start the Compose desktop UI", cause)
    }

    private data class UiMessage(val level: DesktopUiSession.MessageLevel, val title: String, val message: String)

    private class Session(
        private val visible: MutableState<Boolean>,
        private val message: MutableState<UiMessage?>,
        private val window: AtomicReference<ComposeWindow>,
        private val exit: AtomicReference<() -> Unit>,
        private val uiThread: Thread,
    ) : DesktopUiSession {
        override fun activate() = onUiThread {
            activateWindow(visible, window)
        }

        override fun showMessage(level: DesktopUiSession.MessageLevel?, title: String, message: String) = onUiThread {
            this.message.value = UiMessage(level ?: DesktopUiSession.MessageLevel.INFO, title, message)
            activate()
        }

        override fun close() {
            onUiThreadAndWait { exit.getAndSet(null)?.invoke() }
            if (Thread.currentThread() !== uiThread) {
                uiThread.join(TimeUnit.SECONDS.toMillis(30))
                check(!uiThread.isAlive) { "Compose desktop UI did not stop within 30 seconds" }
            }
        }

        private fun onUiThread(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
        }

        private fun onUiThreadAndWait(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }
    }
}

@Composable
private fun rememberDesktopDocument(context: DesktopUiContext): DesktopUiSnapshot {
    var observed by remember {
        mutableStateOf(context.currentSnapshot())
    }
    DisposableEffect(context) {
        val timer = Timer(250) {
            val snapshot = context.currentSnapshot()
            if (snapshot.revision() != observed.revision()) observed = snapshot
        }
        timer.start()
        onDispose(timer::stop)
    }
    return observed
}

private fun activateWindow(visible: MutableState<Boolean>, window: AtomicReference<ComposeWindow>) {
    visible.value = true
    window.get()?.apply {
        isVisible = true
        toFront()
        requestFocus()
    }
}

private fun createTrayIcon(): BufferedImage {
    val size = 32
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = java.awt.Color(63, 95, 208)
        graphics.fillOval(1, 1, size - 2, size - 2)
        graphics.color = java.awt.Color.WHITE
        graphics.font = java.awt.Font("Dialog", java.awt.Font.BOLD, 20)
        val metrics = graphics.fontMetrics
        graphics.drawString("P", (size - metrics.stringWidth("P")) / 2, (size + metrics.ascent) / 2 - 2)
    } finally {
        graphics.dispose()
    }
    return image
}

private data class TrayPopupRequest(val anchor: Point)

@Composable
private fun TrayPopup(
    tray: DesktopUiDocument.Tray,
    themePreference: String,
    messages: ComposeMessages,
    request: TrayPopupRequest,
    onDismiss: () -> Unit,
    onSelect: (DesktopUiDocument.TrayItem) -> Unit,
) {
    val height = tray.items().sumOf {
        if (it.role() == DesktopUiDocument.TrayItemRole.SEPARATOR) 9 else 44
    } + 24
    Window(
        onCloseRequest = onDismiss,
        state = rememberWindowState(width = 260.dp, height = height.dp),
        title = messages.resolve(tray.tooltip()),
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        onPreviewKeyEvent = {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                onDismiss()
                true
            } else false
        },
    ) {
        DisposableEffect(window, request) {
            val focusListener = object : WindowAdapter() {
                override fun windowLostFocus(event: WindowEvent) = onDismiss()
            }
            window.addWindowFocusListener(focusListener)
            SwingUtilities.invokeLater {
                if (window.isDisplayable) {
                    placeTrayPopup(window, request.anchor)
                    window.toFront()
                    window.requestFocus()
                }
            }
            onDispose { window.removeWindowFocusListener(focusListener) }
        }
        PixivDownloaderTheme(themePreference) {
            Box(Modifier.fillMaxSize().padding(8.dp)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 10.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        tray.items().forEach { item ->
                            if (item.role() == DesktopUiDocument.TrayItemRole.SEPARATOR) {
                                HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            } else {
                                TextButton(
                                    onClick = { onSelect(item) },
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                ) {
                                    Text(
                                        messages.resolve(item.label()),
                                        Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.labelLarge,
                                        textAlign = TextAlign.Start,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun placeTrayPopup(window: ComposeWindow, anchor: Point) {
    val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration }
        .firstOrNull { it.bounds.contains(anchor) }
        ?: window.graphicsConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    window.location = trayPopupOrigin(anchor, window.size, configuration.bounds, insets)
}

internal fun trayPopupOrigin(anchor: Point, popup: Dimension, screen: Rectangle, insets: Insets): Point {
    val left = screen.x + insets.left
    val top = screen.y + insets.top
    val right = screen.x + screen.width - insets.right
    val bottom = screen.y + screen.height - insets.bottom
    return Point(
        (anchor.x - popup.width).coerceIn(left, (right - popup.width).coerceAtLeast(left)),
        (anchor.y - popup.height).coerceIn(top, (bottom - popup.height).coerceAtLeast(top)),
    )
}

private class ComposeShortcutDispatcher(private val context: DesktopUiContext) : KeyEventDispatcher {
    private val indexes = mutableMapOf<String, Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.id != KeyEvent.KEY_PRESSED) return false
        val key = physicalKey(event.keyCode) ?: return false
        val pressed = DesktopUiDocument.KeyStroke(
            key, event.isAltDown, event.isControlDown, event.isShiftDown, event.isMetaDown,
        )
        var consume = false
        val snapshot = context.currentSnapshot()
        snapshot.document().shortcuts().forEach { shortcut ->
            val match = shortcut.advance(indexes[shortcut.id()] ?: 0, pressed)
            if (match.completed()) {
                context.dispatchEvent(snapshot.revision(), DesktopUiNode.Event(
                    DesktopUiNode.EventType.ACTIVATE,
                    shortcut.id(),
                    DesktopUiNode.Value.empty(),
                ))
                consume = consume || shortcut.consume()
            }
            indexes[shortcut.id()] = match.nextIndex()
        }
        return consume
    }

    private fun physicalKey(code: Int): String? = when {
        code in KeyEvent.VK_A..KeyEvent.VK_Z -> "Key${code.toChar()}"
        code in KeyEvent.VK_0..KeyEvent.VK_9 -> "Digit${code.toChar()}"
        code in KeyEvent.VK_F1..KeyEvent.VK_F12 -> "F${code - KeyEvent.VK_F1 + 1}"
        else -> when (code) {
            KeyEvent.VK_UP -> "ArrowUp"
            KeyEvent.VK_DOWN -> "ArrowDown"
            KeyEvent.VK_LEFT -> "ArrowLeft"
            KeyEvent.VK_RIGHT -> "ArrowRight"
            KeyEvent.VK_ENTER -> "Enter"
            KeyEvent.VK_ESCAPE -> "Escape"
            KeyEvent.VK_SPACE -> "Space"
            KeyEvent.VK_TAB -> "Tab"
            KeyEvent.VK_BACK_SPACE -> "Backspace"
            KeyEvent.VK_DELETE -> "Delete"
            KeyEvent.VK_INSERT -> "Insert"
            KeyEvent.VK_HOME -> "Home"
            KeyEvent.VK_END -> "End"
            KeyEvent.VK_PAGE_UP -> "PageUp"
            KeyEvent.VK_PAGE_DOWN -> "PageDown"
            else -> null
        }
    }
}

@Composable
private fun ComposeDesktopRoot(
    context: DesktopUiContext,
    snapshot: DesktopUiSnapshot,
    messages: ComposeMessages,
) {
    val document = snapshot.document()
    val documentRevision = snapshot.revision()
    val pageIds = document.pages().map { it.id() }
    var selected by rememberSaveable { mutableStateOf(pageIds.first()) }
    val activePage = selectedIdOrFirst(selected, pageIds)
    val pageStates = rememberSaveableStateHolder()
    val retainedPageIds = remember { linkedSetOf<String>() }
    LaunchedEffect(activePage) { selected = activePage }
    LaunchedEffect(pageIds) {
        removedPageIds(retainedPageIds, pageIds).forEach(pageStates::removeState)
        retainedPageIds.clear()
        retainedPageIds.addAll(pageIds)
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(
                MaterialTheme.colorScheme.background,
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f),
            )),
        ),
    ) {
        val compact = maxWidth < 960.dp
        val navigationWidth = if (compact) 128.dp else 220.dp
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NavigationPanel(
                applicationName = context.applicationName(),
                document = document,
                selected = activePage,
                compact = compact,
                messages = messages,
                modifier = Modifier.width(navigationWidth).fillMaxHeight(),
                onSelect = { selected = it },
            )
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    ControlCenterTopBar(
                        title = messages.resolve(document.pages().first { it.id() == activePage }.title()),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AnimatedContent(
                        targetState = activePage,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            val direction = if (pageIds.indexOf(targetState) >= pageIds.indexOf(initialState)) 1 else -1
                            (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { direction * it / 18 })
                                .togetherWith(fadeOut(tween(140)) +
                                        slideOutHorizontally(tween(200)) { -direction * it / 24 })
                        },
                        contentKey = { it },
                    ) { pageId ->
                        pageStates.SaveableStateProvider(pageId) {
                            ComposeDesktopUiNodeRenderer.Render(
                                document.pages().first { it.id() == pageId }.content(),
                                messages::resolve,
                                { event -> context.dispatchEvent(snapshot, event) },
                                Modifier.fillMaxSize(),
                                documentRevision,
                            )
                        }
                    }
                }
            }
        }
    }
    document.dialogs().forEach { dialog -> DocumentDialog(dialog, snapshot, messages, context) }
}

@Composable
private fun ControlCenterTopBar(
    title: String,
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun selectedIdOrFirst(selectedId: String, orderedIds: List<String>): String =
    selectedId.takeIf(orderedIds::contains) ?: orderedIds.first()

internal fun removedPageIds(previousIds: Set<String>, currentIds: Collection<String>): Set<String> =
    previousIds - currentIds.toSet()

@Composable
private fun NavigationPanel(
    applicationName: String,
    document: DesktopUiDocument,
    selected: String,
    compact: Boolean,
    messages: ComposeMessages,
    modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = modifier.selectableGroup().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        applicationInitials(applicationName),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (!compact) Text(
                applicationName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(Modifier.height(4.dp))
        document.pages().forEach { page ->
            val title = messages.resolve(page.title())
            val active = page.id() == selected
            val container by androidx.compose.animation.animateColorAsState(
                if (active) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
                tween(180),
            )
            val elevation by animateDpAsState(if (active) 1.dp else 0.dp, tween(180))
            val indicatorAlpha by animateFloatAsState(if (active) 1f else 0f, tween(160))
            Surface(
                modifier = Modifier.fillMaxWidth().height(40.dp)
                    .selectable(active, role = Role.Tab) { onSelect(page.id()) },
                shape = RoundedCornerShape(14.dp),
                color = container,
                tonalElevation = elevation,
            ) {
                Row(
                    Modifier.fillMaxSize().padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.width(3.dp).height(22.dp).alpha(indicatorAlpha)
                            .graphicsLayer { clip = true; shape = RoundedCornerShape(2.dp) }
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(
                        title,
                        Modifier.weight(1f),
                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

internal fun applicationInitials(name: String): String {
    val capitals = name.filter(Char::isUpperCase).take(2)
    return capitals.ifBlank { name.trim().take(2).uppercase() }.ifBlank { "UI" }
}

@Composable
private fun DocumentDialog(
    dialog: DesktopUiDocument.Dialog,
    snapshot: DesktopUiSnapshot,
    messages: ComposeMessages,
    context: DesktopUiContext,
) {
    Dialog(onDismissRequest = {
        if (dialog.dismissible()) context.dispatchEvent(snapshot, DesktopUiNode.Event(
            DesktopUiNode.EventType.ACTIVATE,
            dialog.id(),
            DesktopUiNode.Value.empty(),
        ))
    }) {
        val width = if (dialog.preferredWidth() > 0) Modifier.width(dialog.preferredWidth().dp)
            else Modifier.widthIn(min = 320.dp, max = 720.dp)
        val size = if (dialog.preferredHeight() > 0) width.height(dialog.preferredHeight().dp) else width
        Surface(size, shape = MaterialTheme.shapes.extraLarge, shadowElevation = 12.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(messages.resolve(dialog.title()), style = MaterialTheme.typography.titleLarge)
                ComposeDesktopUiNodeRenderer.Render(
                    dialog.content(), messages::resolve,
                    { event -> context.dispatchEvent(snapshot, event) }, Modifier.fillMaxWidth(),
                    snapshot.revision(),
                )
            }
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F5FD0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E7FF),
    onPrimaryContainer = Color(0xFF162B72),
    secondary = Color(0xFF2F6D76),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1EEF2),
    onSecondaryContainer = Color(0xFF123E45),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFE9ECF4),
    onSurfaceVariant = Color(0xFF454750),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC6C9D2),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C4FF),
    onPrimary = Color(0xFF0B2A85),
    primaryContainer = Color(0xFF2947B4),
    onPrimaryContainer = Color(0xFFE1E7FF),
    secondary = Color(0xFF9CD2DB),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF174E56),
    onSecondaryContainer = Color(0xFFB7EAF2),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E2E8),
    surface = Color(0xFF191B20),
    onSurface = Color(0xFFE3E2E8),
    surfaceVariant = Color(0xFF30323A),
    onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF90919B),
    outlineVariant = Color(0xFF44464F),
    error = Color(0xFFFFB4AB),
)

private val DesktopTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
)

private val DesktopShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
private fun PixivDownloaderTheme(themePreference: String, content: @Composable () -> Unit) {
    val dark = darkForThemePreference(themePreference, isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = DesktopTypography,
        shapes = DesktopShapes,
        content = content,
    )
}

internal fun darkForThemePreference(themePreference: String, systemDark: Boolean): Boolean =
    when (themePreference) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
