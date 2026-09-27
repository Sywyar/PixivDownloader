package top.sywyar.pixivdownload.guicompose.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.sywyar.pixivdownload.guicompose.*
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.io.File
import java.text.MessageFormat
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
@DisplayName("自动化时间线与轻量详情")
class AutomationOverviewTest {
    @Test
    @DisplayName("同刻计划聚合且密集排布不重叠，不推测新的运行时间")
    fun groupsConfirmedTimesAndAvoidsCollisions() {
        val node = sample()
        val points = planPoints(node.plans(), node.observedAt(), node.observedAt() + 86_400_000)
        assertEquals(3, points.first { it.plans.size > 1 }.plans.size)
        assertFalse(points.any { p -> p.plans.any { it.id() == "paused" } })
        for (width in listOf(800f, 1200f, 1800f)) {
            val positions = placePlans(points, node.observedAt(), node.observedAt() + 86_400_000, width, 170f)
            positions.forEachIndexed { i, a -> positions.drop(i + 1).forEach { b ->
                if (a.lane == b.lane) assertTrue(a.left + 170 <= b.left || b.left + 170 <= a.left)
            } }
        }
        assertTrue(planPoints(node.plans(), node.observedAt() + 172_800_000, node.observedAt() + 259_200_000).isEmpty())
    }

    @Test
    @DisplayName("浮层在窗口四角和窄窗口内保持可见")
    fun keepsDetailsWithinWindow() {
        for (window in listOf(IntSize(1440, 900), IntSize(390, 844))) {
            val content = IntSize(minOf(window.width, 384), 370)
            for (anchor in listOf(Rect(0f, 0f, 170f, 84f),
                Rect(window.width - 170f, window.height - 84f, window.width.toFloat(), window.height.toFloat()))) {
                val offset = detailOffset(anchor, window, content, 20)
                assertTrue(offset.x >= 0 && offset.y >= 0)
                assertTrue(offset.x + content.width <= window.width && offset.y + content.height <= window.height)
            }
        }
    }

    @Test
    @DisplayName("悬停延迟后预览，移入详情保持，点击固定并通过 Esc 返回")
    fun previewsPinsAndRestoresFocus() = runComposeUiTest {
        mainClock.autoAdvance = false
        val node = sample()
        val card = "automation.card.time:${node.plans().first().nextRuns().first()}"
        setContent { PixivDownloaderTheme("light") { Box(Modifier.size(1200.dp, 900.dp)) { AutomationOverview(node, ::resolve, {}) } } }
        mainClock.advanceTimeBy(500)
        onNodeWithTag(card).performMouseInput { enter(center) }
        mainClock.advanceTimeBy(100)
        onNodeWithTag("automation.detail").assertDoesNotExist()
        onNodeWithTag(card).performMouseInput { exit() }
        mainClock.advanceTimeBy(500)
        onNodeWithTag("automation.detail").assertDoesNotExist()
        onNodeWithTag(card).performMouseInput { enter(center) }
        mainClock.advanceTimeBy(450)
        onNodeWithTag("automation.detail").assertIsDisplayed()
        onNodeWithTag("automation.detail.title").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(400)
        onNodeWithTag("automation.detail").assertIsDisplayed()
        onNodeWithTag("automation.detail.pin").assertDoesNotExist()
        onNodeWithTag(card).performClick()
        mainClock.advanceTimeBy(400)
        onNodeWithTag("automation.detail.pin").assertDoesNotExist()
        onNodeWithTag("automation.detail.title").performKeyInput { pressKey(Key.Escape) }
        mainClock.advanceTimeBy(400)
        onNodeWithTag("automation.detail").assertDoesNotExist()
        onNodeWithTag(card).assertIsFocused()
    }

    @Test
    @DisplayName("同刻分组切换详情，事实刷新保留选择，来源撤回关闭浮层")
    fun keepsSelectionWhileFactsRefresh() = runComposeUiTest {
        var node by mutableStateOf(sample())
        val original = node
        val point = planPoints(node.plans(), node.observedAt(), node.observedAt() + 86_400_000).first { it.plans.size > 1 }
        setContent { PixivDownloaderTheme("light") { Box(Modifier.size(1200.dp, 900.dp)) { AutomationOverview(node, ::resolve, {}) } } }
        onNodeWithTag("automation.card.${point.key}").performScrollTo().performClick()
        onNodeWithTag("automation.detail.plan.group-a").performClick()
        onNodeWithTag("automation.detail.title").assertTextEquals("Weekly collection")
        runOnIdle {
            node = DesktopUiNode.AutomationOverview(original.id(), original.observedAt(), true,
                original.plans().map { p -> if (p.id() == "group-a") copyPlan(p, "Updated collection") else p },
                original.sources(), original.management())
        }
        onNodeWithTag("automation.detail.title").assertTextEquals("Updated collection")
        onNodeWithTag("automation.detail.technical").performClick()
        onNode(hasText("source") and hasAnyAncestor(hasTestTag("automation.detail"))).assertIsDisplayed()
        onNodeWithTag("automation.detail.back").performClick()
        onNodeWithTag("automation.detail.plan.group-b").assertIsDisplayed()
        runOnIdle { node = DesktopUiNode.AutomationOverview(original.id(), original.observedAt(), true, emptyList(), emptyList(), emptyList()) }
        onNodeWithTag("automation.detail").assertDoesNotExist()
    }

    @Test
    @DisplayName("其他计划可以展开详情，工作台动作只由宿主派发")
    fun showsUnscheduledPlansAndDispatchesManagement() = runComposeUiTest {
        val events = mutableListOf<DesktopUiNode.Event>()
        setContent { PixivDownloaderTheme("light") { Box(Modifier.size(1200.dp, 900.dp)) { AutomationOverview(sample(), ::resolve, events::add) } } }
        onNodeWithTag("automation.card.paused").performScrollTo().performClick()
        onNodeWithTag("automation.detail.title").assertTextEquals("Weekend collection")
        onNodeWithTag("automation.detail.manage").performClick()
        assertEquals("manage", events.single().nodeId())
        onNodeWithTag("automation.detail.close").performClick()
        onNodeWithTag("automation.other-toggle").performClick()
        onNodeWithTag("automation.card.paused").assertDoesNotExist()
        onNodeWithTag("automation.other-toggle").performClick()
        onNodeWithTag("automation.card.paused").assertExists()
    }

    @Test
    @DisplayName("固定浮层后一次鼠标点击可切换计划，点击外部关闭")
    fun switchesPinnedDetailsWithOnePointerClick() = runComposeUiTest {
        val node = sample()
        val first = "automation.card.time:${node.plans().first().nextRuns().first()}"
        val last = "automation.card.time:${node.plans().first { it.id() == "morning" }.nextRuns().first()}"
        setContent { PixivDownloaderTheme("light") { Box(Modifier.size(1200.dp, 900.dp)) { AutomationOverview(node, ::resolve, {}) } } }
        onNodeWithTag(first).performMouseInput { click() }
        onNodeWithTag("automation.detail.title").assertTextEquals("Artist updates")
        onNodeWithTag(last).performMouseInput { click() }
        onNodeWithTag("automation.detail.title").assertTextEquals("Morning picks")
        onNodeWithText("Automation").performMouseInput { click() }
        onNodeWithTag("automation.detail").assertDoesNotExist()
    }

    @Test
    @DisplayName("读取失败与空计划分别呈现，不生成虚构时间节点")
    fun distinguishesMissingFactsFromEmptyPlans() = runComposeUiTest {
        val sample = sample()
        var node by mutableStateOf(DesktopUiNode.AutomationOverview("overview", sample.observedAt(), false,
            emptyList(), emptyList(), emptyList()))
        setContent { PixivDownloaderTheme("light") { Box(Modifier.size(900.dp, 700.dp)) { AutomationOverview(node, ::resolve, {}) } } }
        onNodeWithTag("automation.axis").assertDoesNotExist()
        onNodeWithText(messages.getProperty("gui.compose.automation.unavailable")).assertIsDisplayed()
        runOnIdle { node = DesktopUiNode.AutomationOverview("overview", sample.observedAt(), true,
            emptyList(), sample.sources(), emptyList()) }
        onNodeWithText(messages.getProperty("gui.compose.automation.no-tasks")).assertIsDisplayed()
        onNodeWithTag("automation.axis").assertDoesNotExist()
    }

    @Test
    @DisplayName("浅色深色与窄窗口保持布局边界，减少动态效果保留全部交互")
    fun rendersThemesAndReducedMotion() {
        for ((theme, width, single) in listOf(Triple("light", 1200, false), Triple("dark", 1200, false),
            Triple("light", 440, false), Triple("light", 1200, true))) runComposeUiTest(
            effectContext = object : androidx.compose.ui.MotionDurationScale { override val scaleFactor = 0f },
        ) {
            val original = sample()
            val node = if (single) DesktopUiNode.AutomationOverview(original.id(), original.observedAt(), true,
                original.plans().filter { it.id() == "artist" }, original.sources(), original.management()) else original
            val suffix = if (single) "-single" else ""
            setContent { PixivDownloaderTheme(theme) {
                Box(Modifier.size(width.dp, 950.dp).background(LocalExperiencePalette.current.surface)) { AutomationOverview(node, ::resolve, {}) }
            } }
            onNodeWithTag("automation.axis").assertIsDisplayed()
            onNodeWithText(messages.getProperty("desktop.ui.automation.timeline.title")).assertDoesNotExist()
            val axis = onNodeWithTag("automation.axis").fetchSemanticsNode().boundsInRoot
            val toolbar = onNodeWithTag("automation.toolbar").fetchSemanticsNode().boundsInRoot
            val canvas = onNodeWithTag("automation.canvas").fetchSemanticsNode().boundsInRoot
            assertTrue(toolbar.bottom <= axis.top)
            assertTrue(axis.width >= canvas.width)
            if (single) assertEquals(canvas.height, axis.height, 1f)
            System.getenv("PIXIV_AUTOMATION_SCREENSHOTS")?.let { output ->
                File(output).mkdirs()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "automation-$theme-$width$suffix.png"))
            }
            val point = "time:${node.plans().first().nextRuns().first()}"
            onNodeWithTag("automation.card.$point").performClick()
            onNodeWithTag("automation.detail").assertIsDisplayed()
            onNodeWithTag("automation.detail.technical").performClick()
            onNodeWithTag("automation.detail").assertIsDisplayed()
            System.getenv("PIXIV_AUTOMATION_SCREENSHOTS")?.let { output ->
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png",
                    File(output, "details-$theme-$width$suffix.png"))
            }
            onNodeWithTag("automation.detail.close").performClick()
            onNodeWithTag("automation.detail").assertDoesNotExist()
        }
    }

    companion object {
        private val messages = Properties().apply {
            File("../pixivdownload-app/src/main/resources/i18n/messages_en.properties").reader(Charsets.UTF_8).use(::load)
            AutomationOverviewTest::class.java.getResourceAsStream("/i18n/web/gui-compose_en.properties")!!
                .reader(Charsets.UTF_8).use(::load)
        }
        fun resolve(token: DesktopUiNode.TextToken): String =
            if (token.key().isBlank()) token.fallback()
            else MessageFormat.format(messages.getProperty(token.key(), token.key().substringAfterLast('.')), *token.arguments().toTypedArray())
        private fun copyPlan(p: DesktopUiNode.AutomationPlan, title: String) = DesktopUiNode.AutomationPlan(
            p.id(), p.owner(), p.taskId(), DesktopUiNode.TextToken.raw(title), p.trigger(), p.status(), p.lastResult(),
            p.nextRuns(), p.observedAt(), p.availability(), p.actionId())
        fun sample(): DesktopUiNode.AutomationOverview {
            val start = System.currentTimeMillis()
            fun plan(id: String, title: String, hour: Double?, status: String = "IDLE", result: String = "OK") =
                DesktopUiNode.AutomationPlan(id, "source", id, DesktopUiNode.TextToken.raw(title),
                    DesktopUiNode.TextToken.raw("Every day"), status, result,
                    hour?.let { listOf(start + (it * 3_600_000).toLong()) } ?: emptyList(), start, "AVAILABLE", "manage")
            return DesktopUiNode.AutomationOverview("automation.overview", start, true,
                listOf(plan("following", "Artist updates", 0.0, "RUNNING"), plan("bookmarks", "Bookmarks", .4, "QUEUED"),
                    plan("artist", "Favorite artist", 2.0), plan("group-a", "Weekly collection", 4.0),
                    plan("group-b", "Daily ranking", 4.0), plan("group-c", "Manga updates", 4.0),
                    plan("novel", "Novel updates", 8.0, result = "ERROR"), plan("night", "Night collection", 14.0),
                    plan("morning", "Morning picks", 22.0), plan("paused", "Weekend collection", null, "SUSPENDED")),
                listOf(DesktopUiNode.AutomationSource("source", "AVAILABLE", start)),
                listOf(DesktopUiNode.Button("manage", "manage", DesktopUiNode.TextToken.raw("Manage"), null,
                    DesktopUiNode.ButtonStyle.NORMAL, true)))
        }
    }
}
