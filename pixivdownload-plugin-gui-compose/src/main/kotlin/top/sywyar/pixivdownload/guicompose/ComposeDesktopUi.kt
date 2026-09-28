@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import io.github.robinpcrd.cupertino.*
import io.github.robinpcrd.cupertino.theme.*

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cn.longzhengyi.windowsdecoration.BorderlessTitleBarScaffold
import cn.longzhengyi.windowsdecoration.windowhelper.windowCloseButton
import cn.longzhengyi.windowsdecoration.windowhelper.windowDragArea
import cn.longzhengyi.windowsdecoration.windowhelper.windowMaximizeButton
import cn.longzhengyi.windowsdecoration.windowhelper.windowMinimizeButton
import org.slf4j.LoggerFactory
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.WindowStateSnapshot
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession
import top.sywyar.pixivdownload.guicompose.model.DesktopUiSnapshot
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import top.sywyar.pixivdownload.guicompose.model.ComposeDesktopUiModel
import java.awt.AWTException
import java.awt.Dimension
import java.awt.Frame
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
import kotlin.concurrent.thread
import kotlin.math.roundToInt

internal object ComposeDesktopUi {
    private val log = LoggerFactory.getLogger(ComposeDesktopUi::class.java)

    fun launch(context: DesktopUiContext): DesktopUiSession {
        val savedWindowState = context.host().loadWindowState().orElse(null)
        val model = ComposeDesktopUiModel(
            context.serverPort(),
            context.rootFolder(),
            context.configPath(),
            context.selectedProviderId(),
            context.host(),
            context::currentPluginSnapshots,
        )
        val trayExpectedAtLaunch = model.snapshot().document().tray().isPresent && SystemTray.isSupported()
        val visible = mutableStateOf(windowVisibleForTrayState(context.startupLaunch(), trayExpectedAtLaunch))
        val message = mutableStateOf<UiMessage?>(null)
        val windowRef = AtomicReference<ComposeWindow>()
        val exit = AtomicReference<() -> Unit>()
        val failure = AtomicReference<Throwable>()
        val ready = CountDownLatch(1)

        val uiThread = thread(name = "pixivdownload-compose-ui", isDaemon = false) {
            try {
                application(exitProcessOnExit = false) {
                    exit.set { exitApplication() }
                    val observed = rememberDesktopDocument(model)
                    val document = observed.document()
                    val messages = remember(context, observed.revision()) { ComposeMessages(context) }
                    val tray = document.tray().orElse(null)
                    val traySupported = tray != null && SystemTray.isSupported()
                    val trayInstalled = remember { mutableStateOf(false) }
                    val trayPopup = remember { mutableStateOf<TrayPopupRequest?>(null) }
                    if (tray != null && traySupported) {
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
                            val systemTray = SystemTray.getSystemTray()
                            var installed = false
                            try {
                                systemTray.add(trayIcon)
                                installed = true
                                trayInstalled.value = true
                            } catch (failure: AWTException) {
                                visible.value = windowVisibleForTrayState(context.startupLaunch(), false)
                                log.warn(context.host().message(
                                    "gui.tray.log.install-failed", failure.message ?: failure.javaClass.simpleName,
                                ))
                            }
                            onDispose {
                                if (installed) systemTray.remove(trayIcon)
                                trayInstalled.value = false
                                trayIcon.removeActionListener(activateListener)
                                trayIcon.removeMouseListener(popupListener)
                            }
                        }
                        trayPopup.value?.let { popup ->
                            TrayPopup(
                                tray = tray,
                                themePreference = model.themePreference(),
                                messages = messages,
                                request = popup,
                                onDismiss = { trayPopup.value = null },
                                onSelect = { item ->
                                    trayPopup.value = null
                                    when (item.role()) {
                                        DesktopUiDocument.TrayItemRole.ACTIVATE_WINDOW ->
                                            activateWindow(visible, windowRef)

                                        DesktopUiDocument.TrayItemRole.DISPATCH ->
                                            model.dispatch(
                                                observed.revision(), DesktopUiNode.Event(
                                                    DesktopUiNode.EventType.ACTIVATE,
                                                    item.id(),
                                                    DesktopUiNode.Value.empty(),
                                                )
                                            )

                                        DesktopUiDocument.TrayItemRole.SEPARATOR -> Unit
                                    }
                                },
                            )
                        }
                    }
                    val restoredWindowSize = restoredWindowSize(savedWindowState)
                    val mainWindowState = rememberWindowState(size = restoredWindowSize)
                    val normalWindowSize = remember { mutableStateOf(restoredWindowSize) }
                    SideEffect {
                        if (mainWindowState.placement == WindowPlacement.Floating) {
                            normalWindowSize.value = mainWindowState.size
                        }
                    }
                    val saveMainWindowState = {
                        val size = if (mainWindowState.placement == WindowPlacement.Floating) {
                            mainWindowState.size
                        } else {
                            normalWindowSize.value
                        }
                        context.host().saveWindowState(persistedWindowState(
                            size,
                            mainWindowState.placement != WindowPlacement.Floating,
                        ))
                    }
                    DisposableEffect(mainWindowState) {
                        onDispose { saveMainWindowState() }
                    }
                    val closeMainWindow = {
                        saveMainWindowState()
                        if (trayInstalled.value) visible.value = false
                        else context.requestApplicationExit()
                    }
                    Window(
                        onCloseRequest = closeMainWindow,
                        state = mainWindowState,
                        visible = visible.value,
                        title = context.host().applicationName(),
                    ) {
                        val composeWindow = window
                        RestoreSavedWindowPlacement(composeWindow, savedWindowState)
                        val shortcutDispatcher = remember(model) { ComposeShortcutDispatcher(model) }
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
                        PixivDownloaderTheme(model.themePreference()) {
                            Column(Modifier.fillMaxSize()) {
                                if (isWindows()) {
                                    WindowsTitleBar(
                                        title = context.host().applicationName(),
                                        windowState = mainWindowState,
                                        minimizeLabel = messages.plugin("gui.compose.window.minimize"),
                                        maximizeLabel = messages.plugin("gui.compose.window.maximize"),
                                        restoreLabel = messages.plugin("gui.compose.window.restore"),
                                        closeLabel = messages.plugin("gui.compose.window.close"),
                                        onClose = closeMainWindow,
                                    )
                                }
                                CupertinoSurface(Modifier.weight(1f).fillMaxWidth(), color = Color.Transparent) {
                                    ComposeDesktopRoot(
                                        context, model, observed, messages,
                                    )
                                    message.value?.let { current ->
                                        DesktopMessageDialog(
                                            title = current.title,
                                            message = current.message,
                                            confirmLabel = messages.plugin("gui.compose.ok"),
                                            onDismiss = { message.value = null },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (problem: Throwable) {
                failure.set(problem)
                val launched = ready.count == 0L
                ready.countDown()
                if (problem is VirtualMachineError || problem is ThreadDeath) throw problem
                if (launched) context.reportFailure(problem)
            }
        }

        val session = Session(visible, message, windowRef, exit, uiThread, model)
        try {
            check(ready.await(30, TimeUnit.SECONDS)) { "Timed out while starting the Compose desktop UI" }
            failure.get()?.let { throw unwrap(it) }
            return session
        } catch (problem: Throwable) {
            try {
                session.close()
            } catch (cleanup: Throwable) {
                if (cleanup is VirtualMachineError || cleanup is ThreadDeath) throw cleanup
                problem.addSuppressed(cleanup)
            }
            if (problem is InterruptedException) Thread.currentThread().interrupt()
            throw problem
        }
    }

    private fun unwrap(problem: Throwable): Throwable {
        val cause = if (problem is InvocationTargetException && problem.cause != null) problem.cause!! else problem
        return cause
    }

    private data class UiMessage(val level: DesktopUiSession.MessageLevel, val title: String, val message: String)

    private class Session(
        private val visible: MutableState<Boolean>,
        private val message: MutableState<UiMessage?>,
        private val window: AtomicReference<ComposeWindow>,
        private val exit: AtomicReference<() -> Unit>,
        private val uiThread: Thread,
        private val model: ComposeDesktopUiModel,
    ) : DesktopUiSession {
        override fun activate() = onUiThread {
            activateWindow(visible, window)
        }

        override fun showMessage(level: DesktopUiSession.MessageLevel?, title: String, message: String) = onUiThread {
            this.message.value = UiMessage(level ?: DesktopUiSession.MessageLevel.INFO, title, message)
            activate()
        }

        override fun close() {
            model.close()
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
private fun rememberDesktopDocument(model: ComposeDesktopUiModel): DesktopUiSnapshot {
    var observed by remember(model) {
        mutableStateOf(model.snapshot())
    }
    DisposableEffect(model) {
        val subscription = DesktopSnapshotObserver(observed, model::subscribeSnapshots) { snapshot ->
            SwingUtilities.invokeLater {
                observed = snapshot
            }
        }
        onDispose(subscription::close)
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
        if (it.role() == DesktopUiDocument.TrayItemRole.SEPARATOR) 9 else 48
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
                CupertinoSurface(
                    modifier = Modifier.fillMaxSize(),
                    shape = CupertinoTheme.shapes.extraLarge,
                    color = LocalExperiencePalette.current.background,
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        tray.items().forEach { item ->
                            if (item.role() == DesktopUiDocument.TrayItemRole.SEPARATOR) {
                                CupertinoHorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            } else {
                                CupertinoButton(
                                    onClick = { onSelect(item) },
                                    colors = CupertinoButtonDefaults.plainButtonColors(contentColor = LocalExperiencePalette.current.text),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    CupertinoText(messages.resolve(item.label()), Modifier.weight(1f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
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

internal fun windowVisibleForTrayState(startupLaunch: Boolean, trayInstalled: Boolean): Boolean =
    !startupLaunch || !trayInstalled

private class ComposeShortcutDispatcher(
    private val model: ComposeDesktopUiModel,
) : KeyEventDispatcher {
    private val indexes = mutableMapOf<String, Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.id != KeyEvent.KEY_PRESSED) return false
        val key = physicalKey(event.keyCode) ?: return false
        val pressed = DesktopUiDocument.KeyStroke(
            key, event.isAltDown, event.isControlDown, event.isShiftDown, event.isMetaDown,
        )
        var consume = false
        val snapshot = model.snapshot()
        snapshot.document().shortcuts().forEach { shortcut ->
            val match = shortcut.advance(indexes[shortcut.id()] ?: 0, pressed)
            if (match.completed()) {
                model.dispatch(
                    snapshot.revision(), DesktopUiNode.Event(
                        DesktopUiNode.EventType.ACTIVATE,
                        shortcut.id(),
                        DesktopUiNode.Value.empty(),
                    )
                )
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ComposeDesktopRoot(
    context: DesktopUiContext,
    model: ComposeDesktopUiModel,
    snapshot: DesktopUiSnapshot,
    messages: ComposeMessages,
) {
    val document = snapshot.document()
    DesktopShell(
        document = document,
        documentRevision = snapshot.revision(),
        resolveText = messages::resolve,
        dispatch = { event -> model.dispatch(snapshot, event) },
    )
    document.dialogs().forEach { dialog ->
        DocumentDialog(
            dialog = dialog,
            text = messages::resolve,
            closeLabel = messages.plugin("gui.compose.window.close"),
            emit = { event -> model.dispatch(snapshot, event) },
            documentRevision = snapshot.revision(),
        )
    }
}

/**
 * 根页面外壳：导航与当前页面。文档关闭导航时只渲染首个页面，使引导向导独占窗口。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalAnimationApi::class)
@Composable
internal fun DesktopShell(
    document: DesktopUiDocument,
    documentRevision: Long,
    resolveText: (DesktopUiNode.TextToken) -> String,
    dispatch: (DesktopUiNode.Event) -> Unit,
) {
    val pageIds = document.pages().map { it.id() }
    var selected by rememberSaveable { mutableStateOf(pageIds.first()) }
    val activePage = activePageId(document, selected)
    val pageStates = rememberSaveableStateHolder()
    val sceneIds = document.pages().map(::pageSceneId)
    val retainedSceneIds = remember { linkedSetOf<String>() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(activePage) { selected = activePage }
    LaunchedEffect(sceneIds) {
        removedPageIds(retainedSceneIds, sceneIds).forEach(pageStates::removeState)
        retainedSceneIds.clear()
        retainedSceneIds.addAll(sceneIds)
    }

    CupertinoSurface(Modifier.fillMaxSize(), color = LocalExperiencePalette.current.surface) {
        Row(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                document.navigationVisible(),
                enter = expandHorizontally(tween(280)) + fadeIn(tween(180, delayMillis = 80)),
                exit = shrinkHorizontally(tween(220)) + fadeOut(tween(150)),
            ) {
                NavigationPanel(
                    document = document,
                    selected = activePage,
                    resolveText = resolveText,
                    modifier = Modifier.width(DesktopLayout.sidebarWidth).fillMaxHeight(),
                    onSelect = { focusManager.clearFocus(); selected = it },
                )
            }
            val currentPage = document.pages().first { it.id() == activePage }
            val transition = updateTransition(currentPage, label = "desktop-page")
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // 保留退场的向导步骤；其它页面只在可见或退场时组合内容。
                (document.pages() + transition.currentState).distinctBy(::pageSceneId).forEach { page ->
                    val sceneId = pageSceneId(page)
                    val active = sceneId == pageSceneId(currentPage)
                    key(sceneId) {
                        transition.AnimatedVisibility(
                            visible = { pageSceneId(it) == sceneId },
                            modifier = Modifier.fillMaxSize().zIndex(if (active) 1f else 0f),
                            enter = fadeIn(tween(180)),
                            exit = fadeOut(tween(180)),
                        ) {
                            pageStates.SaveableStateProvider(sceneId) {
                                // 退场页面只保留画面，不能向新文档派发旧操作。
                                val interaction = if (active) Modifier else Modifier
                                    .clearAndSetSemantics {}
                                    .onPreviewKeyEvent { true }
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                        }
                                    }
                                ComposeDesktopUiNodeRenderer.Render(
                                    page.content(), resolveText, { if (active) dispatch(it) },
                                    Modifier.fillMaxSize().focusProperties { canFocus = active }.then(interaction), documentRevision,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun pageSceneId(page: DesktopUiDocument.Page): String =
    page.id() + ":" + ((page.content() as? DesktopUiNode.Surface)?.content()?.id() ?: page.content().id())

internal fun activePageId(document: DesktopUiDocument, selectedId: String): String =
    if (document.navigationVisible()) selectedIdOrFirst(selectedId, document.pages().map { it.id() })
    else document.pages().first().id()

internal fun selectedIdOrFirst(selectedId: String, orderedIds: List<String>): String =
    selectedId.takeIf(orderedIds::contains) ?: orderedIds.first()

internal fun removedPageIds(previousIds: Set<String>, currentIds: Collection<String>): Set<String> =
    previousIds - currentIds.toSet()

@Composable
private fun NavigationPanel(
    document: DesktopUiDocument,
    selected: String,
    resolveText: (DesktopUiNode.TextToken) -> String,
    modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        document.pages().forEachIndexed { index, page ->
            if (index == 4) CupertinoHorizontalDivider(Modifier.padding(vertical = 8.dp))
            val active = page.id() == selected
            val background by animateColorAsState(
                if (active) LocalExperiencePalette.current.selection else Color.Transparent,
                tween(140), label = "navigation-selection",
            )
            CupertinoSurface(shape = CupertinoTheme.shapes.small,
                color = background,
                modifier = Modifier.fillMaxWidth().selectable(active, role = Role.Tab) { onSelect(page.id()) }) {
                Row(Modifier.heightIn(min = DesktopLayout.navigationHeight).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DesktopIcon(desktopIcon(page.icon()), null, Modifier.size(18.dp))
                    CupertinoText(resolveText(page.title()), style = CupertinoTheme.typography.body)
                }
            }
        }
    }
}

@Composable
private fun FrameWindowScope.WindowsTitleBar(
    title: String,
    windowState: WindowState,
    minimizeLabel: String,
    maximizeLabel: String,
    restoreLabel: String,
    closeLabel: String,
    onClose: () -> Unit,
) {
    BorderlessTitleBarScaffold(windowState) {
        CupertinoSurface(
            color = LocalExperiencePalette.current.surface,
            contentColor = LocalExperiencePalette.current.text,
        ) {
            Row(
                Modifier.fillMaxWidth().height(40.dp)
                    .windowDragArea(helper),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CupertinoText(
                    title,
                    Modifier.weight(1f).padding(start = 16.dp),
                    style = CupertinoTheme.typography.subhead.copy(
                        fontSize = 14.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                WindowCaptionButton(
                    icon = Icons.Default.Minimize,
                    label = minimizeLabel,
                    onClick = { minimize() },
                    modifier = Modifier.width(46.dp).fillMaxHeight().windowMinimizeButton(helper),
                )
                WindowCaptionButton(
                    icon = if (isMaximized) Icons.Default.FilterNone else Icons.Default.CropSquare,
                    label = if (isMaximized) restoreLabel else maximizeLabel,
                    onClick = { toggleMaximize() },
                    modifier = Modifier.width(46.dp).fillMaxHeight().windowMaximizeButton(helper),
                )
                WindowCaptionButton(
                    icon = Icons.Default.Close,
                    label = closeLabel,
                    onClick = onClose,
                    modifier = Modifier.width(46.dp).fillMaxHeight().windowCloseButton(helper),
                    close = true,
                )
            }
        }
    }
}

private fun isWindows(): Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DocumentDialog(
    dialog: DesktopUiDocument.Dialog,
    text: (DesktopUiNode.TextToken) -> String,
    closeLabel: String,
    emit: (DesktopUiNode.Event) -> Unit,
    documentRevision: Long,
) {
    val title = text(dialog.title())
    val focusRequester = remember(dialog.id()) { FocusRequester() }
    val dismiss = {
        if (dialog.dismissible()) emit(
            DesktopUiNode.Event(
                DesktopUiNode.EventType.ACTIVATE,
                dialog.id(),
                DesktopUiNode.Value.empty(),
            )
        )
    }
    // 使用与 Cupertino 提示框相同的模态层，避免创建带系统标题栏的独立窗口。
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            dismissOnBackPress = dialog.dismissible(),
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            usePlatformInsets = false,
            scrimColor = CupertinoDialogsDefaults.ScrimColor,
        ),
    ) {
        LaunchedEffect(dialog.id()) { focusRequester.requestFocus() }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
                .onKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                        dismiss()
                        true
                    } else false
                }
                .focusRequester(focusRequester)
                .focusable()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val targetSize = dialogWindowSize(dialog, DpSize(maxWidth, maxHeight))
            val editor = (dialog.content() as? DesktopUiNode.Surface)?.content() as? DesktopUiNode.RepositoryEditor
            CupertinoSurface(
                modifier = (if (editor != null) Modifier.width(targetSize.width).heightIn(max = targetSize.height)
                    else Modifier.size(targetSize))
                    .border(1.dp, LocalExperiencePalette.current.separator, CupertinoTheme.shapes.medium)
                    .semantics { paneTitle = title },
                shape = CupertinoTheme.shapes.medium,
                color = LocalExperiencePalette.current.surface,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CupertinoText(
                            text = title,
                            modifier = Modifier.weight(1f).semantics { heading() },
                            style = CupertinoTheme.typography.title2,
                        )
                        if (dialog.dismissible()) {
                            CupertinoIconButton(onClick = dismiss) {
                                DesktopIcon(Icons.Default.Close, contentDescription = closeLabel)
                            }
                        }
                    }
                    ComposeDesktopUiNodeRenderer.Render(
                        root = editor ?: dialog.content(),
                        textResolver = text,
                        eventSink = emit,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = editor == null),
                        documentRevision = documentRevision,
                    )
                }
            }
        }
    }
}

internal fun dialogWindowSize(dialog: DesktopUiDocument.Dialog, parentWindowSize: DpSize): DpSize =
    if (dialog.parentSized()) parentWindowSize else DpSize(
        (dialog.preferredWidth().takeIf { it > 0 } ?: 400).dp.coerceAtMost(parentWindowSize.width),
        (dialog.preferredHeight().takeIf { it > 0 } ?: 300).dp.coerceAtMost(parentWindowSize.height),
    )

internal fun restoredWindowSize(state: WindowStateSnapshot?): DpSize =
    if (state == null) DpSize(1120.dp, 760.dp) else DpSize(state.width().dp, state.height().dp)

@Composable
internal fun RestoreSavedWindowPlacement(
    window: ComposeWindow,
    state: WindowStateSnapshot?,
) {
    DisposableEffect(window, state?.maximized()) {
        if (state?.maximized() != true) return@DisposableEffect onDispose {}

        var restored = false
        val restore = {
            if (!restored) {
                restored = true
                window.extendedState = Frame.MAXIMIZED_BOTH
            }
        }
        val listener = object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) {
                SwingUtilities.invokeLater(restore)
            }
        }
        window.addWindowListener(listener)
        if (window.isShowing) SwingUtilities.invokeLater(restore)
        onDispose { window.removeWindowListener(listener) }
    }
}

internal fun persistedWindowState(size: DpSize, maximized: Boolean): WindowStateSnapshot =
    WindowStateSnapshot(
        size.width.value.roundToInt().coerceIn(1, 32_768),
        size.height.value.roundToInt().coerceIn(1, 32_768),
        maximized,
    )

internal fun experiencePalette(dark: Boolean, highContrast: Boolean = false): ExperiencePalette = when {
    dark && highContrast -> ExperienceTokens.highContrastDark
    highContrast -> ExperienceTokens.highContrastLight
    dark -> ExperienceTokens.dark
    else -> ExperienceTokens.light
}

internal object DesktopLayout {
    val sidebarWidth = 160.dp
    val navigationHeight = 40.dp
    val controlHeight = 36.dp
    val numberWidth = 140.dp
    val choiceWidth = 280.dp
    val formWidth = 720.dp
}

private val DesktopTypography = Typography(
    title1 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    title2 = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    headline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    callout = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    footnote = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    subhead = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
)

private val DesktopShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
internal fun PixivDownloaderTheme(themePreference: String, content: @Composable () -> Unit) {
    val dark = darkForThemePreference(themePreference, isSystemInDarkTheme())
    val toolkit = remember { Toolkit.getDefaultToolkit() }
    var highContrast by remember { mutableStateOf(toolkit.getDesktopProperty("win.highContrast.on") == true) }
    DisposableEffect(toolkit) {
        val listener = java.beans.PropertyChangeListener { highContrast = it.newValue == true }
        toolkit.addPropertyChangeListener("win.highContrast.on", listener)
        onDispose { toolkit.removePropertyChangeListener("win.highContrast.on", listener) }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExperiencePalette provides experiencePalette(dark, highContrast)) {
        CupertinoTheme(
            colorScheme = desktopColorScheme(dark, highContrast),
            typography = DesktopTypography,
            shapes = DesktopShapes,
            content = content,
        )
    }
}

internal fun desktopColorScheme(dark: Boolean, highContrast: Boolean = false): ColorScheme {
    val p = experiencePalette(dark, highContrast)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        accent = p.link, label = p.text, secondaryLabel = p.secondaryText,
        tertiaryLabel = p.secondaryText, quaternaryLabel = p.secondaryText,
        systemFill = p.controlBorder, secondarySystemFill = p.separator,
        tertiarySystemFill = p.secondarySurface, quaternarySystemFill = p.secondarySurface,
        placeholderText = p.secondaryText, separator = p.separator, opaqueSeparator = p.controlBorder,
        link = p.link, systemBackground = p.surface, secondarySystemBackground = p.background,
        tertiarySystemBackground = p.secondarySurface, systemGroupedBackground = p.background,
        secondarySystemGroupedBackground = p.surface, tertiarySystemGroupedBackground = p.secondarySurface,
    )
}

internal fun darkForThemePreference(themePreference: String, systemDark: Boolean): Boolean =
    when (themePreference) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
