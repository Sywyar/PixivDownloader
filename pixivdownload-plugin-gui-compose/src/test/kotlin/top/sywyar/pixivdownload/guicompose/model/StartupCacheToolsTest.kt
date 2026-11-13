package top.sywyar.pixivdownload.guicompose.model

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.PixivDownloaderTheme
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*
import top.sywyar.pixivdownload.guicompose.tools.ToolsOverview
import top.sywyar.pixivdownload.guicompose.tools.descendants
import top.sywyar.pixivdownload.plugin.api.gui.DesktopStartupCache
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StartupCacheToolsTest {
    @Test
    @DisplayName("真实工具模型在窄窗口可开始和取消训练，收起面板不取消宿主任务")
    fun lifecycle() = runComposeUiTest {
        val id = UUID.randomUUID()
        val starts = AtomicInteger()
        val cancels = AtomicInteger()
        var current = DesktopStartupCache.Snapshot(null, DesktopStartupCache.State.IDLE,
            DesktopStartupCache.Phase.FINISHED, DesktopStartupCache.Failure.NONE, 0)
        val service = object : DesktopStartupCache {
            override fun snapshot() = current
            override fun start(): UUID {
                starts.incrementAndGet()
                current = DesktopStartupCache.Snapshot(id, DesktopStartupCache.State.RUNNING,
                    DesktopStartupCache.Phase.RECORDING, DesktopStartupCache.Failure.NONE, 3)
                return id
            }
            override fun cancel(execution: UUID): Boolean {
                assertEquals(id, execution)
                cancels.incrementAndGet()
                current = DesktopStartupCache.Snapshot(id, DesktopStartupCache.State.STOPPING,
                    DesktopStartupCache.Phase.RECORDING, DesktopStartupCache.Failure.NONE, 3)
                return true
            }
        }
        val overrides = hashMapOf<String, Function<Array<Any>, Any?>>(
            "startupCache" to Function { Optional.of(service) },
        )
        DesktopConfigurationControllerTest.model(hashMapOf(), overrides).use { model ->
            var observed by mutableStateOf(model.snapshot())
            model.subscribeSnapshots { observed = it }.use {
                setContent {
                    PixivDownloaderTheme("light") {
                        Box(Modifier.size(580.dp, 650.dp)) {
                            val tools = descendants(observed.document().pages().first { it.id() == "tools" }.content())
                                .filterIsInstance<top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ToolsOverview>().first()
                            ToolsOverview(tools, { it.key() }, { model.dispatch(observed, it) })
                        }
                    }
                }
                onNodeWithTag("tools.entry.startup").performScrollTo().performClick()
                onNodeWithTag("tools.startup.run").assertIsDisplayed().assertIsEnabled().performClick()
                assertEquals(1, starts.get())
                onNodeWithTag("tools.startup.cancel").assertIsDisplayed().assertIsEnabled()
                onNodeWithTag("tools.sheet.close").performClick()
                assertEquals(0, cancels.get())
                onNodeWithTag("tools.current.startup").performScrollTo().performClick()
                onNodeWithTag("tools.startup.cancel").performClick()
                assertEquals(1, cancels.get())
                onNodeWithTag("tools.startup.cancel").assertIsNotEnabled()
            }
        }
    }
}
