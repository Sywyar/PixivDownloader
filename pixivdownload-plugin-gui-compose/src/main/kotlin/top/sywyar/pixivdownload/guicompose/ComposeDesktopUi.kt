package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.AlertDialog
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

internal object ComposeDesktopUi {
    fun launch(context: DesktopUiContext): DesktopUiSession {
        val visible = mutableStateOf(true)
        val message = mutableStateOf<UiMessage?>(null)
        val windowRef = AtomicReference<ComposeWindow>()
        val exit = AtomicReference<() -> Unit>()
        val failure = AtomicReference<Throwable>()
        val ready = CountDownLatch(1)

        thread(name = "pixivdownload-compose-ui", isDaemon = false) {
            try {
                application(exitProcessOnExit = false) {
                    exit.set { exitApplication() }
                    Window(
                        onCloseRequest = { visible.value = false },
                        visible = visible.value,
                        title = context.host().applicationName(),
                    ) {
                        val composeWindow = window
                        DisposableEffect(composeWindow) {
                            windowRef.set(composeWindow)
                            ready.countDown()
                            onDispose { windowRef.compareAndSet(composeWindow, null) }
                        }
                        MaterialTheme {
                            Surface(Modifier.fillMaxSize()) {
                                ComposeDesktopRoot(context)
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
        return Session(visible, message, windowRef, exit)
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
    ) : DesktopUiSession {
        override fun activate() = onUiThread {
            visible.value = true
            window.get()?.apply {
                isVisible = true
                toFront()
                requestFocus()
            }
        }

        override fun showMessage(level: DesktopUiSession.MessageLevel?, title: String, message: String) = onUiThread {
            this.message.value = UiMessage(level ?: DesktopUiSession.MessageLevel.INFO, title, message)
            activate()
        }

        override fun close() = onUiThread { exit.getAndSet(null)?.invoke() }

        private fun onUiThread(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
        }
    }
}

@Composable
private fun ComposeDesktopRoot(context: DesktopUiContext) {
    var document by remember { mutableStateOf(context.currentDocument()) }
    var selected by remember { mutableStateOf(document.pages().first().kind()) }
    val messages = remember(context) { ComposeMessages(context) }

    LaunchedEffect(context) {
        while (true) {
            delay(1_000)
            document = context.currentDocument()
            if (document.pages().none { it.kind() == selected }) selected = document.pages().first().kind()
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(190.dp).fillMaxHeight().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            document.pages().forEach { page ->
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selected = page.kind() },
                ) {
                    Text(messages.get(page.titleI18nKey()))
                }
            }
        }
        Divider(Modifier.fillMaxHeight().width(1.dp))
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
            PendingDeclarativePage(document.pages().first { it.kind() == selected }, messages)
        }
    }
}

@Composable
private fun PendingDeclarativePage(page: DesktopUiDocument.Page, messages: ComposeMessages) {
    val node = remember(page) {
        DesktopUiNode.Container(
            "page.${page.kind().name.lowercase()}",
            DesktopUiNode.ContainerLayout.COLUMN,
            1,
            12,
            DesktopUiNode.Alignment.STRETCH,
            listOf(
                DesktopUiNode.Text(
                    "page.${page.kind().name.lowercase()}.title",
                    DesktopUiNode.TextToken.raw(messages.get(page.titleI18nKey())),
                    DesktopUiNode.TextStyle.TITLE,
                    true,
                    false,
                ),
                DesktopUiNode.Text(
                    "page.${page.kind().name.lowercase()}.pending",
                    DesktopUiNode.TextToken.raw(messages.plugin("gui.compose.page.pending")),
                    DesktopUiNode.TextStyle.WARNING,
                    true,
                    true,
                ),
                DesktopUiNode.Text(
                    "page.${page.kind().name.lowercase()}.detail",
                    DesktopUiNode.TextToken.raw(messages.plugin("gui.compose.page.pending.detail")),
                    DesktopUiNode.TextStyle.BODY,
                    true,
                    true,
                ),
            ),
        )
    }
    ComposeDesktopUiNodeRenderer.Render(node, messages::resolve, eventSink = { })
}
