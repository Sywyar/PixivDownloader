package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.web.DownloadGalleryCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadQueueCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadScheduleCapabilities;
import top.sywyar.pixivdownload.plugin.api.web.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.api.web.TabContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.List;
import java.util.Map;

import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.web.DownloadExtensionController;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DownloadExtensionController} 单测：下载页扩展点端点把 queueType / tab / ui-slot 三类合并后的不可变快照
 * 暴露给前端。重点验证新增的 ui-slot 视图：按 {@code order} 再 {@code slotId} 稳定排序、携带可信 owner、
 * 保留 target / moduleUrl / metadata，且只投影下载页稳定目标。
 */
@DisplayName("DownloadExtensionController 下载页扩展点端点")
class DownloadExtensionControllerTest {

    private static DownloadExtensionController controllerFor(PluginRegistry registry) {
        StaticResourceRegistry staticResources = new StaticResourceRegistry(registry);
        PluginOwnedWebAssetValidator validator = new PluginOwnedWebAssetValidator(staticResources);
        return new DownloadExtensionController(new DownloadExtensionRegistry(
                registry, staticResources, validator));
    }

    private static ResponseEntity<DownloadExtensionController.DownloadExtensionsView> responseFor(
            PluginRegistry registry) {
        return controllerFor(registry).extensions();
    }

    @Test
    @DisplayName("ui-slot 视图按 order→slotId 稳定排序并保留可信 owner 与扩展元数据")
    void uiSlotsSortedAndProjected() {
        PluginRegistry registry = new PluginRegistry(List.of(new DemoExtensionPlugin()));
        ResponseEntity<DownloadExtensionController.DownloadExtensionsView> response = responseFor(registry);
        DownloadExtensionController.DownloadExtensionsView view = response.getBody();

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(view).isNotNull();
        assertThat(view.epoch()).isNotBlank();
        assertThat(view.revision()).isEqualTo(1L);
        assertThat(view.uiSlots()).extracting(DownloadExtensionController.UiSlotView::slotId)
                .containsExactly("demo.m", "demo.a", "demo.z");   // order 5 → order 10（slotId a 先于 z）

        DownloadExtensionController.UiSlotView first = view.uiSlots().get(0);
        assertThat(first.target()).isEqualTo("cookie-tools");
        assertThat(first.moduleUrl()).isNull();                   // null moduleUrl（宿主内联）保真
        assertThat(first.owner().pluginId()).isEqualTo("demo");
        assertThat(first.owner().packageId()).isEqualTo("demo");
        assertThat(first.owner().generation()).isZero();
        assertThat(first.owner().publicationId()).isPositive();
        assertThat(view.uiSlots()).filteredOn(s -> s.slotId().equals("demo.z"))
                .singleElement()
                .satisfies(s -> assertThat(s.metadata()).containsExactlyEntriesOf(Map.of("k", "v")));

        // queueType / tab 仍正常暴露（回归）
        assertThat(view.queueTypes()).extracting(DownloadExtensionController.QueueTypeView::type).containsExactly("demo");
        assertThat(view.downloadTypes()).extracting(DownloadExtensionController.DownloadTypeView::type)
                .containsExactly("demo");
        assertThat(view.downloadTypes()).singleElement()
                .satisfies(type -> {
                    assertThat(type.pluginId()).isEqualTo("demo");
                    assertThat(type.contractVersion()).isEqualTo(1);
                    assertThat(type.acquisitionModes()).containsExactly("single-import");
                    assertThat(type.queue().clearAll()).isTrue();
                    assertThat(type.schedule().suspendWhenExecutorMissing()).isTrue();
                    assertThat(type.gallery().unifiedGallery()).isFalse();
                    assertThat(type.gallery().independentPage()).isTrue();
                    assertThat(type.legacyContract()).isFalse();
                    assertThat(type.owner().pluginId()).isEqualTo("demo");
                    assertThat(type.owner().publicationId()).isPositive();
                });
        assertThat(view.tabs()).extracting(DownloadExtensionController.TabView::tabId)
                .containsExactly("single-import");
        assertThat(view.tabs()).singleElement()
                .satisfies(tab -> assertThat(tab.supportedQueueTypes()).containsExactly("demo"));
    }

    @Test
    @DisplayName("旧六参数作品类型构造器在前端清单中显式标记兼容契约")
    void legacyQueueTypeConstructorIsProjected() {
        DownloadExtensionController.DownloadExtensionsView view = responseFor(
                new PluginRegistry(List.of(new LegacyExtensionPlugin()))).getBody();

        assertThat(view).isNotNull();
        assertThat(view.downloadTypes()).singleElement().satisfies(type -> {
            assertThat(type.type()).isEqualTo("legacy-demo");
            assertThat(type.acquisitionModes()).isEmpty();
            assertThat(type.legacyContract()).isTrue();
        });
    }

    @Test
    @DisplayName("novel 插件缺席时端点不暴露小说 UI 槽位")
    void builtInNovelUiSlotsExposed() {
        DownloadExtensionController.DownloadExtensionsView view = responseFor(
                new PluginRegistry(BuiltInPlugins.createAll())).getBody();
        assertThat(view).isNotNull();
        assertThat(view.uiSlots()).extracting(DownloadExtensionController.UiSlotView::target)
                .doesNotContain("kind-option-user", "kind-option-search", "kind-option-quick",
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
            return List.of(new QueueTypeContribution(
                    "demo", "demo", "demo", "demo.kind", 5, null,
                    new DownloadTypeDescriptor(
                            DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                            "demo", "demo", "demo", "demo.kind", 5,
                            "download", "neutral", null,
                            List.of(DownloadAcquisitionMode.SINGLE_IMPORT),
                            DownloadQueueCapabilities.full(),
                            DownloadScheduleCapabilities.notSaveable(),
                            List.of(), List.of(), List.of(), "demo",
                            new DownloadGalleryCapabilities(true, true, null, null))));
        }

        @Override
        public List<TabContribution> downloadTabs() {
            return List.of(new TabContribution("demo", "single-import", 5, List.of()));
        }

        @Override
        public List<WebUiSlotContribution> uiSlots() {
            return List.of(
                    new WebUiSlotContribution("demo", "demo.z", "settings-card", null, 10, Map.of("k", "v")),
                    new WebUiSlotContribution("demo", "demo.a", "import-hint", null, 10),
                    new WebUiSlotContribution("demo", "demo.m", "cookie-tools", null, 5));
        }
    }

    private static final class LegacyExtensionPlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            return "legacy-demo";
        }

        @Override
        public String displayName() {
            return "legacy-demo.label";
        }

        @Override
        public String description() {
            return "legacy-demo.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<QueueTypeContribution> queueTypes() {
            return List.of(new QueueTypeContribution(
                    "legacy-demo", "legacy-demo", "legacy-demo", "legacy-demo.kind", 15, null));
        }
    }
}
