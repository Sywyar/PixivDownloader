package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DownloadExtensionController} 单测：下载页扩展点端点把 queueType / tab / ui-slot 三类合并后的不可变快照
 * 暴露给前端。重点验证新增的 ui-slot 视图：按 {@code order} 再 {@code slotId} 稳定排序、刻意不含 {@code pluginId}、
 * 保留 target / moduleUrl / metadata，且某插件停用后其槽位从快照消失（经注册中心 unregister）。
 */
@DisplayName("DownloadExtensionController 下载页扩展点端点")
class DownloadExtensionControllerTest {

    private static DownloadExtensionController controllerFor(PluginRegistry registry) {
        return new DownloadExtensionController(
                new QueueTypeRegistry(registry), new DownloadTabRegistry(registry), new WebUiSlotRegistry(registry));
    }

    @Test
    @DisplayName("ui-slot 视图按 order→slotId 稳定排序、不含 pluginId、保留 target/moduleUrl/metadata")
    void uiSlotsSortedAndProjected() {
        PluginRegistry registry = new PluginRegistry(List.of(new DemoExtensionPlugin()));
        DownloadExtensionController.DownloadExtensionsView view = controllerFor(registry).extensions();

        assertThat(view.uiSlots()).extracting(DownloadExtensionController.UiSlotView::slotId)
                .containsExactly("demo.m", "demo.a", "demo.z");   // order 5 → order 10（slotId a 先于 z）

        DownloadExtensionController.UiSlotView first = view.uiSlots().get(0);
        assertThat(first.target()).isEqualTo("anchor-m");
        assertThat(first.moduleUrl()).isNull();                   // null moduleUrl（宿主内联）保真
        assertThat(view.uiSlots()).filteredOn(s -> s.slotId().equals("demo.z"))
                .singleElement()
                .satisfies(s -> assertThat(s.metadata()).containsExactlyEntriesOf(Map.of("k", "v")));

        // queueType / tab 仍正常暴露（回归）
        assertThat(view.queueTypes()).extracting(DownloadExtensionController.QueueTypeView::type).containsExactly("demo");
        assertThat(view.tabs()).extracting(DownloadExtensionController.TabView::tabId).containsExactly("demo-tab");
    }

    @Test
    @DisplayName("内置插件装配：小说下载页 8 个 UI 槽位经端点暴露")
    void builtInNovelUiSlotsExposed() {
        DownloadExtensionController.DownloadExtensionsView view =
                controllerFor(new PluginRegistry(BuiltInPlugins.createAll())).extensions();
        assertThat(view.uiSlots()).extracting(DownloadExtensionController.UiSlotView::target)
                .contains("kind-option-user", "kind-option-search", "kind-option-quick",
                        "quick-actions-bookmarks", "quick-actions-mine",
                        "import-hint", "search-filter", "settings-card");
    }

    /** 合成扩展点插件：声明一个作品类型 + 一个标签页 + 三个乱序 UI 槽位（验证端点排序 / 投影）。 */
    private static final class DemoExtensionPlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            return "demo";
        }

        @Override
        public String displayName() {
            return "demo.label";
        }

        @Override
        public String description() {
            return "demo.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<QueueTypeContribution> queueTypes() {
            return List.of(new QueueTypeContribution("demo", "demo", "demo.kind", 5, "/demo/qt.js"));
        }

        @Override
        public List<TabContribution> downloadTabs() {
            return List.of(new TabContribution("demo", "demo-tab", 5, List.of("demo")));
        }

        @Override
        public List<WebUiSlotContribution> uiSlots() {
            return List.of(
                    new WebUiSlotContribution("demo", "demo.z", "anchor-z", "/demo/z.js", 10, Map.of("k", "v")),
                    new WebUiSlotContribution("demo", "demo.a", "anchor-a", "/demo/a.js", 10),
                    new WebUiSlotContribution("demo", "demo.m", "anchor-m", null, 5));
        }
    }
}
