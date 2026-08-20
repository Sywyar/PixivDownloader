package top.sywyar.pixivdownload.guicompose

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.jetbrains.skia.Image
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiCapability
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode
import java.awt.Dimension
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.util.Base64
import java.util.Locale
import java.util.ResourceBundle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Compose Multiplatform GUI 插件")
class GuiComposePluginTest {
    @Test
    @DisplayName("作为按需桌面提供者显式声明稳定节点与语义能力")
    fun exposesOptionalCompleteRenderer() {
        val plugin = GuiComposePlugin()

        assertEquals("gui-compose", plugin.id())
        assertFalse(plugin.defaultProvider())
        assertEquals(setOf(
            DesktopUiNode.Kind.CONTAINER, DesktopUiNode.Kind.DOCK, DesktopUiNode.Kind.SURFACE,
            DesktopUiNode.Kind.GROUP, DesktopUiNode.Kind.FORM, DesktopUiNode.Kind.TABS,
            DesktopUiNode.Kind.SCROLL, DesktopUiNode.Kind.SPLIT, DesktopUiNode.Kind.TEXT,
            DesktopUiNode.Kind.IMAGE, DesktopUiNode.Kind.SEPARATOR, DesktopUiNode.Kind.SPACER,
            DesktopUiNode.Kind.PROGRESS, DesktopUiNode.Kind.TEXT_INPUT, DesktopUiNode.Kind.TOGGLE,
            DesktopUiNode.Kind.CHOICE, DesktopUiNode.Kind.NUMBER_INPUT, DesktopUiNode.Kind.TABLE,
            DesktopUiNode.Kind.TREE, DesktopUiNode.Kind.BUTTON, DesktopUiNode.Kind.LINK,
        ), plugin.supportedNodeKinds())
        assertEquals(setOf(
            DesktopUiCapability.SPLIT_USER_RESIZABLE,
            DesktopUiCapability.TABLE_LARGE_DATA_SCROLL,
            DesktopUiCapability.INPUT_NUMERIC,
            DesktopUiCapability.INPUT_PATH_FILE,
            DesktopUiCapability.INPUT_PATH_DIRECTORY,
            DesktopUiCapability.SELECTION_MULTIPLE,
        ), plugin.supportedCapabilities())
        assertEquals("Compose Multiplatform GUI",
            ResourceBundle.getBundle("i18n.web.gui-compose", Locale.US).getString("plugin.name"))
    }

    @Test
    @DisplayName("完整声明式节点树通过稳定契约校验")
    fun validatesEverySupportedNodeKind() {
        assertEquals(GuiComposePlugin().supportedNodeKinds(), DesktopUiNode.validateTree(completeTree()))
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
    @DisplayName("桌面品牌缩写兼容 CamelCase 与本地化名称")
    fun derivesCompactApplicationInitials() {
        assertEquals("PD", applicationInitials("PixivDownloader"))
        assertEquals("像素", applicationInitials("像素下载器"))
        assertEquals("UI", applicationInitials("  "))
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
            DesktopUiNode.Surface("surface", DesktopUiNode.SurfaceStyle.CARD,
                DesktopUiNode.Insets.all(8), true, text("surface.text")),
            DesktopUiNode.Group("group", raw("Group"), text("group.text")),
            DesktopUiNode.Form("form", DesktopUiNode.FormStyle.RESPONSIVE, raw(":"), listOf(
                DesktopUiNode.FormRow("form.row", raw("Field"), raw("Help"),
                    input("form.input", DesktopUiNode.InputKind.NUMBER), text("form.trailing")),
            )),
            DesktopUiNode.Tabs("tabs", listOf(DesktopUiNode.Tab("tab", raw("Tab"), text("tab.text")))),
            DesktopUiNode.Scroll("scroll", text("scroll.text")),
            DesktopUiNode.Split("split", DesktopUiNode.Axis.HORIZONTAL, .5, text("split.first"), text("split.second")),
            DesktopUiNode.Image("image", DesktopUiNode.ImageData("image/gif", PIXEL_GIF), raw("Pixel"), 16, 16, DesktopUiNode.ScaleMode.FIT),
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
