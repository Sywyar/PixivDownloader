@file:OptIn(io.github.robinpcrd.cupertino.ExperimentalCupertinoApi::class)

package top.sywyar.pixivdownload.guicompose

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.robinpcrd.cupertino.*
import io.github.robinpcrd.cupertino.theme.CupertinoTheme

/** 启动与运行通知共用桌面阅读宽度，正文滚动时确认操作仍可见。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopMessageDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
) {
    val palette = LocalExperiencePalette.current
    val focus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            usePlatformInsets = false,
            scrimColor = CupertinoDialogsDefaults.ScrimColor,
        ),
    ) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(24.dp).onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                    onDismiss()
                    true
                } else false
            },
            contentAlignment = Alignment.Center,
        ) {
            CupertinoSurface(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .testTag("desktop.message").semantics { paneTitle = title },
                shape = CupertinoTheme.shapes.medium,
                color = palette.surface,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    CupertinoText(
                        title,
                        Modifier.semantics { heading() },
                        style = CupertinoTheme.typography.title2,
                        color = palette.text,
                    )
                    Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        val scroll = rememberScrollState()
                        SelectionContainer {
                            CupertinoText(
                                message,
                                Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 14.dp)
                                    .testTag("desktop.message.body"),
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                color = palette.text,
                            )
                        }
                        Box(Modifier.matchParentSize()) {
                            VerticalScrollbar(
                                rememberScrollbarAdapter(scroll),
                                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            )
                        }
                    }
                    CupertinoButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End).focusRequester(focus),
                    ) { CupertinoText(confirmLabel) }
                }
            }
        }
    }
}
