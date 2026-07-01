package top.sywyar.pixivdownload.plugin.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogAcquisitionService;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogEntry;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogManifest;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PluginMarketService} 安装状态投影单测：把受信 catalog 条目与<b>真实运行时安装状态</b>（mock 的
 * {@link PluginStatusService} 报告）交叉引用，端到端验证安装状态机（未安装 / 已安装 / 有更新 / 不兼容）、已安装版本、
 * 是否有更新、最新版本、兼容性原因、重启标记，以及已安装数量。安装状态来自后端真实状态，<b>不</b>由前端臆测。
 */
@DisplayName("PluginMarketService 安装状态投影（与运行时真实状态交叉引用）")
class PluginMarketInstallStatusTest {

    private final PluginCatalogService catalogService = mock(PluginCatalogService.class);
    private final PluginCatalogAcquisitionService acquisitionService = mock(PluginCatalogAcquisitionService.class);
    private final PluginStatusService statusService = mock(PluginStatusService.class);

    /**
     * catalog：a 最新 1.0.0（兼容）、b 最新 2.0.0（兼容）、c 最新 1.0.0 但要求核心 API 2.0（不兼容）、
     * d 最新 1.2.0（兼容，用于语义等价版本判定）、e 最新 1.0.0（兼容，用于本机版本更高判定）、f 无任何可安装版本制品。
     */
    private static PluginCatalogManifest catalog() {
        return new PluginCatalogManifest("1", null, List.of(
                entry("a", pkg("1.0.0", "1.0")),
                entry("b", pkg("2.0.0", "1.0"), pkg("1.0.0", "1.0")),
                entry("c", pkg("1.0.0", "2.0")),
                entry("d", pkg("1.2.0", "1.0")),
                entry("e", pkg("1.0.0", "1.0")),
                entry("f")));
    }

    private static PluginCatalogEntry entry(String id, PluginCatalogPackage... packages) {
        return new PluginCatalogEntry(id, id + ":name", null, null, List.of(packages));
    }

    private static PluginCatalogPackage pkg(String version, String requiredCoreApi) {
        return new PluginCatalogPackage(version, "https://x/" + version + ".jar", 100L, "ab", null, null,
                requiredCoreApi, List.of(), null, List.of(), "stable", false);
    }

    /** 已安装诊断（有描述符 → 视为已安装），描述符携带已安装版本。 */
    private static PluginDiagnostic installed(String id, String version) {
        PluginDescriptor descriptor = new PluginDescriptor(id, id, version,
                PluginApiRequirement.unspecified(), List.of(), null, "ns", id + ":name", null, null, null,
                PluginKind.FEATURE);
        return new PluginDiagnostic(id, PluginStatus.STARTED, descriptor, false, List.of());
    }

    private PluginMarketService service(PluginDiagnostic... installed) {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        when(catalogService.load(PluginRepository.OFFICIAL_ID)).thenReturn(catalog());
        when(statusService.report()).thenReturn(new PluginStatusReport(List.of(installed)));
        return new PluginMarketService(new PluginRepositoryRegistry(props), catalogService, acquisitionService,
                statusService);
    }

    private static PluginMarketEntryView entryOf(PluginMarketView view, String pluginId) {
        return view.entries().stream().filter(e -> e.pluginId().equals(pluginId)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("未安装：报告无该 id → NOT_INSTALLED、installedVersion=null、updateAvailable=false")
    void notInstalled() {
        PluginMarketView view = service().catalog(PluginRepository.OFFICIAL_ID);

        PluginMarketEntryView a = entryOf(view, "a");
        assertThat(a.installStatus()).isEqualTo(MarketInstallStatus.NOT_INSTALLED);
        assertThat(a.installedVersion()).isNull();
        assertThat(a.updateAvailable()).isFalse();
        assertThat(a.compatible()).isTrue();
        assertThat(a.latestVersion()).isEqualTo("1.0.0");
        assertThat(view.installedCount()).isZero();
    }

    @Test
    @DisplayName("已安装且最新：版本等于 latest → INSTALLED、installedVersion 在场、updateAvailable=false")
    void installedUpToDate() {
        PluginMarketView view = service(installed("a", "1.0.0")).catalog(PluginRepository.OFFICIAL_ID);

        PluginMarketEntryView a = entryOf(view, "a");
        assertThat(a.installStatus()).isEqualTo(MarketInstallStatus.INSTALLED);
        assertThat(a.installedVersion()).isEqualTo("1.0.0");
        assertThat(a.updateAvailable()).isFalse();
        assertThat(view.installedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("已安装但有更高兼容版本 → UPDATE_AVAILABLE、updateAvailable=true、installedVersion=旧版")
    void updateAvailable() {
        PluginMarketView view = service(installed("b", "1.0.0")).catalog(PluginRepository.OFFICIAL_ID);

        PluginMarketEntryView b = entryOf(view, "b");
        assertThat(b.installStatus()).isEqualTo(MarketInstallStatus.UPDATE_AVAILABLE);
        assertThat(b.installedVersion()).isEqualTo("1.0.0");
        assertThat(b.latestVersion()).isEqualTo("2.0.0");
        assertThat(b.updateAvailable()).isTrue();
        assertThat(view.installedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("未安装且最新版本要求更高核心 API → INCOMPATIBLE、compatible=false、compatibilityReason=要求版本")
    void incompatible() {
        PluginMarketView view = service().catalog(PluginRepository.OFFICIAL_ID);

        PluginMarketEntryView c = entryOf(view, "c");
        assertThat(c.installStatus()).isEqualTo(MarketInstallStatus.INCOMPATIBLE);
        assertThat(c.compatible()).isFalse();
        assertThat(c.compatibilityReason()).isEqualTo("2.0");
        assertThat(c.updateAvailable()).isFalse();
    }

    @Test
    @DisplayName("已安装版本与最新版本语义等价（1.2 vs 1.2.0）→ INSTALLED、不提示更新")
    void semanticallyEquivalentVersionIsNotUpdate() {
        PluginMarketView view = service(installed("d", "1.2")).catalog(PluginRepository.OFFICIAL_ID);

        PluginMarketEntryView d = entryOf(view, "d");
        assertThat(d.latestVersion()).isEqualTo("1.2.0");
        assertThat(d.installedVersion()).isEqualTo("1.2");
        assertThat(d.installStatus()).isEqualTo(MarketInstallStatus.INSTALLED);
        assertThat(d.updateAvailable()).isFalse();
    }

    @Test
    @DisplayName("本机版本高于市场最新版本（2.0.0 vs 1.0.0）→ 保持 INSTALLED、不提示更新")
    void localVersionHigherThanCatalogStaysInstalled() {
        PluginMarketView view = service(installed("e", "2.0.0")).catalog(PluginRepository.OFFICIAL_ID);

        PluginMarketEntryView e = entryOf(view, "e");
        assertThat(e.latestVersion()).isEqualTo("1.0.0");
        assertThat(e.installedVersion()).isEqualTo("2.0.0");
        assertThat(e.installStatus()).isEqualTo(MarketInstallStatus.INSTALLED);
        assertThat(e.updateAvailable()).isFalse();
    }

    @Test
    @DisplayName("未安装且无任何可安装版本制品 → UNAVAILABLE（不可安装）、latestVersion=null、不计入已安装数")
    void entryWithNoInstallableVersionIsUnavailable() {
        PluginMarketView view = service().catalog(PluginRepository.OFFICIAL_ID);

        PluginMarketEntryView f = entryOf(view, "f");
        assertThat(f.packages()).isEmpty();
        assertThat(f.latestVersion()).isNull();
        assertThat(f.installStatus()).isEqualTo(MarketInstallStatus.UNAVAILABLE);
        assertThat(f.updateAvailable()).isFalse();
    }

    @Test
    @DisplayName("已安装数量 = INSTALLED + UPDATE_AVAILABLE（不计未安装 / 不兼容）")
    void installedCountCountsInstalledAndUpdatable() {
        PluginMarketView view = service(installed("a", "1.0.0"), installed("b", "1.0.0"))
                .catalog(PluginRepository.OFFICIAL_ID);

        assertThat(entryOf(view, "a").installStatus()).isEqualTo(MarketInstallStatus.INSTALLED);
        assertThat(entryOf(view, "b").installStatus()).isEqualTo(MarketInstallStatus.UPDATE_AVAILABLE);
        assertThat(entryOf(view, "c").installStatus()).isEqualTo(MarketInstallStatus.INCOMPATIBLE);
        assertThat(view.installedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("无描述符的诊断（必选但未安装 / 加载失败）不计为已安装")
    void descriptorlessDiagnosticIsNotInstalled() {
        PluginDiagnostic missingRequired = new PluginDiagnostic("a", PluginStatus.MISSING_REQUIRED, null, true, List.of());
        PluginMarketView view = service(missingRequired).catalog(PluginRepository.OFFICIAL_ID);

        assertThat(entryOf(view, "a").installStatus()).isEqualTo(MarketInstallStatus.NOT_INSTALLED);
        assertThat(view.installedCount()).isZero();
    }

    @Test
    @DisplayName("pluginDetail 也投影安装状态 + 即时激活标记")
    void pluginDetailProjectsInstallStatusAndRestartFlag() {
        PluginMarketEntryView b = service(installed("b", "1.0.0"))
                .pluginDetail(PluginRepository.OFFICIAL_ID, "b");

        assertThat(b.installStatus()).isEqualTo(MarketInstallStatus.UPDATE_AVAILABLE);
        assertThat(b.packages()).isNotEmpty();
        assertThat(b.packages()).allSatisfy(pkg -> assertThat(pkg.effectiveAfterRestart()).isFalse());
    }
}
