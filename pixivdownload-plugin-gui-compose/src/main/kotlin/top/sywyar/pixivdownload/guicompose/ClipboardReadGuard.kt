package top.sywyar.pixivdownload.guicompose

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

@Composable
internal fun GuardClipboardReads(content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val guarded = remember(clipboard) {
        if (clipboard is ClipboardReadGuard) clipboard
        else ClipboardReadGuard(clipboard) { java.awt.Toolkit.getDefaultToolkit().beep() }
    }
    CompositionLocalProvider(LocalClipboard provides guarded, content = content)
}

/** 系统剪贴板被其它进程占用时保留输入内容，不让一次粘贴失败关闭整个界面。 */
internal class ClipboardReadGuard(
    private val delegate: Clipboard,
    private val unavailable: () -> Unit,
) : Clipboard by delegate {
    override suspend fun getClipEntry(): ClipEntry? = try {
        delegate.getClipEntry()
    } catch (_: IllegalStateException) {
        unavailable()
        null
    }
}
