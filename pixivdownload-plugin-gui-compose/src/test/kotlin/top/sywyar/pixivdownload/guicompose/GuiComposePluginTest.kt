package top.sywyar.pixivdownload.guicompose

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.jetbrains.skia.Image
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode
import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.ResourceBundle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Compose Multiplatform GUI 插件")
class GuiComposePluginTest {
    @Test
    @DisplayName("作为按需提供者完整拥有 Compose 桌面界面")
    fun exposesOptionalComposeProvider() {
        val plugin = GuiComposePlugin()

        assertEquals("gui-compose", plugin.id())
        assertFalse(plugin.defaultProvider())
        assertEquals("Compose Multiplatform GUI",
            ResourceBundle.getBundle("i18n.web.gui-compose", Locale.US).getString("plugin.name"))
    }

    @Test
    @DisplayName("桌面文档刷新订阅模型快照且不使用固定频率 Timer")
    fun subscribesToPublishedSnapshots() {
        val source = Files.readString(Path.of(
            "src/main/kotlin/top/sywyar/pixivdownload/guicompose/ComposeDesktopUi.kt",
        ))

        assertTrue("model.subscribeSnapshots" in source)
        assertFalse("Timer(" in source)
    }

    @Test
    @DisplayName("私有页面节点树覆盖每一种 Compose 渲染分派")
    fun validatesEveryPrivateNodeKind() {
        assertEquals(DesktopUiNode.Kind.entries.toSet(), DesktopUiNode.validateTree(completeTree()))
    }

    @Test
    @DisplayName("当前平台 Skiko 原生库可以真实初始化并解码图像")
    fun initializesCurrentPlatformNativeRuntime() {
        Image.makeFromEncoded(Base64.getDecoder().decode(PIXEL_GIF)).use { image ->
            assertEquals(1, image.width)
            assertEquals(1, image.height)
        }
    }

    @Test
    @DisplayName("长原始文本留在 Compose 内且不进入宿主文本契约")
    fun keepsLongRawTextInsideComposeProvider() {
        val host = Proxy.newProxyInstance(
            DesktopUiHost::class.java.classLoader,
            arrayOf(DesktopUiHost::class.java),
        ) { _, method, _ -> error("unexpected host call: ${method.name}") } as DesktopUiHost
        val context = DesktopUiContext(
            false, 6999, ".", Path.of("config.yaml"), host, emptyList(), { emptyList() },
            { error("raw text must not reach the host resolver") }, { "system" },
        )
        val licenseText = "license text\n".repeat(2_000)

        assertEquals(licenseText, ComposeMessages(context).resolve(raw(licenseText)))
    }

    @Test
    @DisplayName("现代托盘菜单以图标为锚点并保持在可用屏幕内")
    fun anchorsTrayPopupInsideUsableScreen() {
        val anchor = Point(1209, 795)
        val popup = Dimension(260, 262)
        val origin = trayPopupOrigin(anchor, popup, Rectangle(0, 0, 1600, 1000), Insets(0, 0, 0, 0))

        assertEquals(Point(949, 533), origin)
        assertEquals(anchor, Point(origin.x + popup.width, origin.y + popup.height))
        assertEquals(
            Point(1650, 880),
            trayPopupOrigin(
                Point(1910, 1070),
                Dimension(260, 160),
                Rectangle(0, 0, 1920, 1080),
                Insets(0, 0, 40, 0),
            ),
        )
    }

    @Test
    @DisplayName("自启动仅在托盘实际安装成功后隐藏主窗口")
    fun derivesStartupVisibilityFromInstalledTray() {
        assertFalse(windowVisibleForTrayState(true, true))
        assertTrue(windowVisibleForTrayState(true, false))
        assertTrue(windowVisibleForTrayState(false, true))
    }

    @Test
    @DisplayName("多选值按文档顺序完整投射")
    fun projectsMultipleSelectionsInDocumentOrder() {
        val items = listOf(
            DesktopUiNode.TreeItem("root", raw("Root"), listOf(
                DesktopUiNode.TreeItem("child", raw("Child"), emptyList()),
            )),
            DesktopUiNode.TreeItem("last", raw("Last"), emptyList()),
        )

        assertEquals(
            listOf("root", "child", "last"),
            ComposeDesktopUiNodeRenderer.treeItemIds(items),
        )
        assertEquals(listOf("root"), ComposeDesktopUiNodeRenderer.treeBranchIds(items))
        assertEquals(
            listOf("root", "last"),
            ComposeDesktopUiNodeRenderer.selectedIdsInDocumentOrder(
                ComposeDesktopUiNodeRenderer.treeItemIds(items),
                listOf("last", "root"),
            ),
        )
    }

    @Test
    @DisplayName("分栏拖动比例保持在可用范围并处理零尺寸")
    fun boundsResizableSplitWeight() {
        assertEquals(.7f, ComposeDesktopUiNodeRenderer.resizedSplitWeight(.5f, 20f, 100), .0001f)
        assertEquals(.9f, ComposeDesktopUiNodeRenderer.resizedSplitWeight(.8f, 50f, 100), .0001f)
        assertEquals(.1f, ComposeDesktopUiNodeRenderer.resizedSplitWeight(.2f, -50f, 100), .0001f)
        assertEquals(.5f, ComposeDesktopUiNodeRenderer.resizedSplitWeight(.5f, 20f, 0), .0001f)
    }

    @Test
    @DisplayName("数值控件输出始终限制并对齐到最小值步长")
    fun alignsNumericControlValues() {
        assertEquals(0, ComposeDesktopUiNodeRenderer.alignedNumberValue(-5, 0, 10, 3))
        assertEquals(0, ComposeDesktopUiNodeRenderer.alignedNumberValue(2, 0, 10, 3))
        assertEquals(3, ComposeDesktopUiNodeRenderer.alignedNumberValue(3, 0, 10, 3))
        assertEquals(9, ComposeDesktopUiNodeRenderer.alignedNumberValue(10, 0, 10, 3))
        assertEquals(9, ComposeDesktopUiNodeRenderer.alignedNumberValue(99, 0, 10, 3))
    }

    @Test
    @DisplayName("密码短暂状态只随宿主状态代际换代")
    fun scopesPasswordStateToHostGeneration() {
        val first = input("password", DesktopUiNode.InputKind.PASSWORD, 4)
        val refreshed = input("password", DesktopUiNode.InputKind.PASSWORD, 4)
        val cleared = input("password", DesktopUiNode.InputKind.PASSWORD, 5)

        assertEquals(
            ComposeDesktopUiNodeRenderer.textInputStateKey(first),
            ComposeDesktopUiNodeRenderer.textInputStateKey(refreshed),
        )
        assertFalse(
            ComposeDesktopUiNodeRenderer.textInputStateKey(first) ==
                ComposeDesktopUiNodeRenderer.textInputStateKey(cleared),
        )
    }

    @Test
    @DisplayName("动态重排按稳定标识保持选择并在删除后回退")
    fun preservesStableSelectionAcrossReordering() {
        assertEquals("details", selectedIdOrFirst("details", listOf("details", "overview")))
        assertEquals("overview", selectedIdOrFirst("removed", listOf("overview", "details")))
        assertEquals(setOf("removed"), removedPageIds(setOf("overview", "removed"), listOf("overview", "added")))
    }

    @Test
    @DisplayName("共享主题偏好映射为 Material 明暗外观")
    fun mapsSharedThemePreferenceToMaterialAppearance() {
        assertFalse(darkForThemePreference("light", true))
        assertTrue(darkForThemePreference("dark", false))
        assertTrue(darkForThemePreference("system", true))
        assertFalse(darkForThemePreference("unsupported", false))
    }

    private fun completeTree(): DesktopUiNode = DesktopUiNode.Container(
        "root", DesktopUiNode.ContainerLayout.COLUMN, 1, 4, DesktopUiNode.Alignment.STRETCH,
        listOf(
            DesktopUiNode.Dock("dock", 4, text("dock.top"), text("dock.center"), null, null, null),
            DesktopUiNode.AdaptiveGrid("adaptive", 160, 4, 8, 8, listOf(text("adaptive.text"))),
            DesktopUiNode.PagedRow("paged", 4, 8, listOf(text("paged.text"))),
            DesktopUiNode.Surface("surface", DesktopUiNode.SurfaceStyle.CARD,
                DesktopUiNode.Insets.all(8), true, "surface.open", text("surface.text")),
            DesktopUiNode.Group("group", raw("Group"), text("group.text")),
            DesktopUiNode.Form("form", DesktopUiNode.FormStyle.RESPONSIVE, raw(":"), listOf(
                DesktopUiNode.FormRow("form.row", raw("Field"), raw("Help"),
                    input("form.input", DesktopUiNode.InputKind.NUMBER), text("form.trailing")),
            )),
            DesktopUiNode.Tabs("tabs", listOf(DesktopUiNode.Tab("tab", raw("Tab"), text("tab.text")))),
            DesktopUiNode.Scroll("scroll", text("scroll.text")),
            DesktopUiNode.Split("split", DesktopUiNode.Axis.HORIZONTAL, .5, text("split.first"), text("split.second")),
            DesktopUiNode.Icon("icon", DesktopUiIcon.HOME, DesktopUiTone.INFO, raw("Home")),
            DesktopUiNode.Image("image", DesktopUiNode.ImageData("image/gif", PIXEL_GIF), raw("Pixel"),
                16, 16, DesktopUiNode.ScaleMode.FILL, DesktopUiNode.ImageShape.CIRCLE),
            DesktopUiNode.Separator("separator", DesktopUiNode.Axis.HORIZONTAL),
            DesktopUiNode.Spacer("spacer", 4, 4),
            DesktopUiNode.Progress("progress", .5, false, raw("Half")),
            input("input.text", DesktopUiNode.InputKind.TEXT),
            input("input.password", DesktopUiNode.InputKind.PASSWORD),
            DesktopUiNode.Toggle("toggle", "toggle.value", raw("Toggle"), null, DesktopUiNode.ToggleStyle.SWITCH, true, true),
            DesktopUiNode.Choice("choice", "choice.value", raw("Choice"), null,
                DesktopUiNode.ChoiceStyle.LIST, DesktopUiNode.SelectionMode.MULTIPLE,
                listOf(DesktopUiNode.Option("one", raw("One"), true)), listOf("one"), true),
            DesktopUiNode.NumberInput("number", "number.value", raw("Number"), null,
                DesktopUiNode.NumberStyle.SLIDER, 5, 0, 10, 1, true),
            DesktopUiNode.Table("table", "table.rows",
                listOf(DesktopUiNode.TableColumn("name", raw("Name"), 100)),
                listOf(DesktopUiNode.TableRow("row", listOf("One"))),
                DesktopUiNode.SelectionMode.SINGLE, listOf("row"), true),
            DesktopUiNode.Tree("tree", "tree.items",
                listOf(DesktopUiNode.TreeItem("item", raw("Item"), emptyList())),
                DesktopUiNode.SelectionMode.SINGLE, listOf("item"), true),
            DesktopUiNode.Button("button", "action.run", raw("Run"), null, DesktopUiNode.ButtonStyle.PRIMARY, true),
            DesktopUiNode.Link("link", "action.help", raw("Help"), null, true),
        ),
    )

    private fun input(id: String, kind: DesktopUiNode.InputKind) = DesktopUiNode.TextInput(
        id, "$id.value", raw(id), null, kind, "", 20, 1, true,
    )

    private fun input(id: String, kind: DesktopUiNode.InputKind, stateRevision: Long) = DesktopUiNode.TextInput(
        id, "$id.value", raw(id), null, kind, "", 20, 1, true, stateRevision,
    )

    private fun text(id: String) = DesktopUiNode.Text(id, raw(id), DesktopUiNode.TextStyle.BODY, true, false)
    private fun raw(value: String) = DesktopUiNode.TextToken.raw(value)

    companion object {
        private const val PIXEL_GIF = "R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
    }
}
