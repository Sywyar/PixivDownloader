package top.sywyar.pixivdownload.plugin.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.PluginStatusService;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogAcquisitionService;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogEntry;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogManifest;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService;
import top.sywyar.pixivdownload.plugin.catalog.model.CatalogPresentationToken;
import top.sywyar.pixivdownload.plugin.catalog.model.PluginCatalogCategory;
import top.sywyar.pixivdownload.plugin.catalog.model.PluginCatalogMarketMeta;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PluginMarketService} 单测：真实 {@link PluginRepositoryRegistry}（配置 → 仓库列表）+ mock 的清单读取 / 安装编排，
 * 端到端验证 DTO 投影（仓库 / 分类计数 / 市场元数据净化）、主开关 / 未知 / 禁用仓库语义，以及<b>按 repositoryId</b> 的安装委托。
 * 不联网（catalog 读取 / 下载全 mock）。
 */
@DisplayName("PluginMarketService 插件市场 DTO 投影与受控解析")
class PluginMarketServiceTest {

    private final PluginCatalogService catalogService = mock(PluginCatalogService.class);
    private final PluginCatalogAcquisitionService acquisitionService = mock(PluginCatalogAcquisitionService.class);
    private final PluginStatusService statusService = mock(PluginStatusService.class);

    private PluginMarketService service(PluginCatalogProperties properties) {
        // 默认无已安装插件（空状态报告）→ 全部条目投影为未安装；安装状态的交叉引用细节见 PluginMarketInstallStatusTest。
        when(statusService.report()).thenReturn(PluginStatusReport.empty());
        return new PluginMarketService(new PluginRepositoryRegistry(properties), catalogService, acquisitionService,
                statusService);
    }

    private static PluginCatalogProperties enabledWithCustom() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setManifestUrl("https://legacy.example/manifest.json"); // 折成 configured 兼容仓库（默认仓库）
        PluginCatalogProperties.RepositoryConfig custom = new PluginCatalogProperties.RepositoryConfig();
        custom.setId("community");
        custom.setManifestUrl("https://community.example/manifest.json");
        custom.setEnabled(false); // 禁用项仍在列表中用于状态展示
        props.getRepositories().add(custom);
        return props;
    }

    @Test
    @DisplayName("repositories：主开关状态 + 核心 API 版本 + 默认仓库 id + 官方/内嵌/兼容/默认标记")
    void repositoriesProjection() {
        PluginMarketRepositoriesView view = service(enabledWithCustom()).repositories();

        assertThat(view.enabled()).isTrue();
        assertThat(view.coreApiVersion()).isEqualTo(PluginApiVersion.VERSION);
        assertThat(view.defaultRepositoryId()).isEqualTo(PluginRepository.LEGACY_CONFIGURED_ID);
        assertThat(view.repositories()).extracting(PluginMarketRepositoryView::repositoryId)
                .containsExactly(PluginRepository.OFFICIAL_ID, PluginRepository.LEGACY_CONFIGURED_ID, "community");

        PluginMarketRepositoryView official = view.repositories().get(0);
        assertThat(official.official()).isTrue();
        assertThat(official.builtIn()).isTrue();
        assertThat(official.defaultRepository()).isFalse();
        assertThat(official.proxyPolicySupported()).isTrue();

        PluginMarketRepositoryView configured = view.repositories().get(1);
        assertThat(configured.legacy()).isTrue();
        assertThat(configured.defaultRepository()).isTrue();

        PluginMarketRepositoryView community = view.repositories().get(2);
        assertThat(community.official()).isFalse();
        assertThat(community.builtIn()).isFalse();
        assertThat(community.enabled()).isFalse();
        verifyNoInteractions(catalogService, acquisitionService);
    }

    @Test
    @DisplayName("repositories：主开关关闭仍列出仓库（enabled=false），不联网")
    void repositoriesWhenMasterOff() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(false);
        PluginMarketRepositoriesView view = service(props).repositories();

        assertThat(view.enabled()).isFalse();
        assertThat(view.repositories()).extracting(PluginMarketRepositoryView::repositoryId)
                .contains(PluginRepository.OFFICIAL_ID);
        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("catalog：分类计数（聚合 all 在首 + 全部已知分类的派生计数）+ 市场元数据净化（图标/颜色 token、主页外链）")
    void catalogProjectionWithCategoryCountsAndSanitization() {
        PluginCatalogMarketMeta unsafe = new PluginCatalogMarketMeta(
                Map.of("zh", "示例"), Map.of(), Map.of(), "author", "community",
                "download", List.of("tag"), "javascript:alert(1)", "MIT",
                4.5, 10, 1000L, null, null, "1.0.0", "2026-06-01",
                "<script>", "rgb(1,2,3)", true, false);
        PluginCatalogMarketMeta translateMeta = new PluginCatalogMarketMeta(
                Map.of(), Map.of(), Map.of(), null, null, "translate", List.of(),
                null, null, null, null, null, null, null, null, null, null, null, false, false);
        PluginCatalogManifest manifest = new PluginCatalogManifest("1", null, List.of(
                new PluginCatalogEntry("a", "a:name", null, unsafe, List.of(
                        new PluginCatalogPackage("1.0.0", "https://x/a.jar", 100L, "ab", null, null,
                                "1.0", List.of(), null, List.of(), "stable", false))),
                new PluginCatalogEntry("b", "b:name", null, translateMeta, List.of()),
                new PluginCatalogEntry("c", "c:name", null, null, List.of()))); // null market → utility 回退
        when(catalogService.load(PluginRepository.OFFICIAL_ID)).thenReturn(manifest);

        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        PluginMarketView view = service(props).catalog(PluginRepository.OFFICIAL_ID);

        assertThat(view.enabled()).isTrue();
        assertThat(view.repositoryId()).isEqualTo(PluginRepository.OFFICIAL_ID);
        assertThat(view.coreApiVersion()).isEqualTo(PluginApiVersion.VERSION);
        // 分类计数：all 在首 = 3；download=1 / translate=1 / utility=1（null market 回退）；其余分类计 0。
        assertThat(view.categories().get(0).category()).isEqualTo(PluginCatalogCategory.AGGREGATE_ID);
        assertThat(view.categories().get(0).count()).isEqualTo(3);
        assertThat(view.categories()).anySatisfy(c -> {
            if (c.category().equals("download")) assertThat(c.count()).isEqualTo(1);
        });
        assertThat(view.categories()).anySatisfy(c -> {
            if (c.category().equals(PluginCatalogCategory.FALLBACK.id())) assertThat(c.count()).isGreaterThanOrEqualTo(1);
        });
        // 市场元数据净化：非法图标 / 颜色 token 回退默认、javascript: 主页外链 → null。
        PluginMarketMetaView market = view.entries().get(0).market();
        assertThat(market.iconToken()).isEqualTo(CatalogPresentationToken.DEFAULT_ICON);
        assertThat(market.colorToken()).isEqualTo(CatalogPresentationToken.DEFAULT_COLOR);
        assertThat(market.homepageUrl()).isNull();
        assertThat(view.entries().get(0).latestVersion()).isEqualTo("1.0.0");
        // 无已安装插件（空状态报告）→ 有版本制品的条目（a）未安装、无版本制品的条目（b/c）不可安装；已安装数 0。
        assertThat(view.installedCount()).isZero();
        assertThat(view.entries()).extracting(PluginMarketEntryView::installStatus)
                .containsExactly(MarketInstallStatus.NOT_INSTALLED, MarketInstallStatus.UNAVAILABLE,
                        MarketInstallStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("catalog：主开关关闭 → disabled 视图（enabled=false + 空），不读清单")
    void catalogDisabledWhenMasterOff() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(false);

        PluginMarketView view = service(props).catalog(null);

        assertThat(view.enabled()).isFalse();
        assertThat(view.entries()).isEmpty();
        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("catalog：未知 repositoryId → UNKNOWN_REPOSITORY（不读清单）")
    void catalogUnknownRepository() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);

        PluginCatalogException ex = catchThrowableOfType(
                () -> service(props).catalog("ghost"), PluginCatalogException.class);
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.UNKNOWN_REPOSITORY);
        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("catalog：目标仓库被禁用 → REPOSITORY_DISABLED（不读清单）")
    void catalogDisabledRepository() {
        PluginMarketService service = service(enabledWithCustom());

        PluginCatalogException ex = catchThrowableOfType(
                () -> service.catalog("community"), PluginCatalogException.class);
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.REPOSITORY_DISABLED);
        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("pluginDetail：未知插件 id → UNKNOWN_PLUGIN")
    void pluginDetailUnknown() {
        when(catalogService.load(PluginRepository.OFFICIAL_ID))
                .thenReturn(new PluginCatalogManifest("1", null, List.of()));
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);

        PluginCatalogException ex = catchThrowableOfType(
                () -> service(props).pluginDetail(PluginRepository.OFFICIAL_ID, "ghost"), PluginCatalogException.class);
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.UNKNOWN_PLUGIN);
    }

    @Test
    @DisplayName("install：按 repositoryId + pluginId + version 委托受信安装编排（仅受控标识、绝不传 URL）")
    void installDelegatesByRepositoryId() {
        PluginInstallReport report = new PluginInstallReport(PluginInstallOutcome.INSTALLED, true, true,
                "demo", "1.0.0", null, List.of(), List.of(), List.of());
        when(acquisitionService.install("official", "demo", "1.0.0")).thenReturn(report);
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);

        PluginInstallReport result = service(props).install("official", "demo", "1.0.0");

        assertThat(result.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        verify(acquisitionService).install("official", "demo", "1.0.0");
    }
}
