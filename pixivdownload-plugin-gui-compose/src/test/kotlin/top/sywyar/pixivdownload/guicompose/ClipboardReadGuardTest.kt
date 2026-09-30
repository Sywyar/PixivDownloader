package top.sywyar.pixivdownload.guicompose

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ClipboardReadGuardTest {
    @Test
    @DisplayName("剪贴板占用时提示且不粘贴，释放后可再次读取，写失败仍交给调用方处理")
    fun busyClipboard() = runBlocking {
        var busy = true
        var notices = 0
        val entry = ClipEntry(java.awt.datatransfer.StringSelection("example"))
        val delegate = object : Clipboard {
            override val nativeClipboard: Any = Any()
            override suspend fun getClipEntry(): ClipEntry? {
                check(!busy)
                return entry
            }
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                error("write unavailable")
            }
        }
        val guarded = ClipboardReadGuard(delegate) { notices++ }
        assertNull(guarded.getClipEntry())
        assertEquals(1, notices)
        busy = false
        assertSame(entry, guarded.getClipEntry())
        assertEquals(1, notices)
        assertFailsWith<IllegalStateException> { guarded.setClipEntry(entry) }
        assertSame(delegate.nativeClipboard, guarded.nativeClipboard)
    }
}
