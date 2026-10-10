package top.sywyar.pixivdownload.plugin.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogAcquisitionService;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogEntry;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogManifest;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogMarketMeta;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogDetailPage;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogPage;
import top.sywyar.pixivdownload.plugin.catalog.page.PluginCatalogPageQuery;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    @Test
    @DisplayName("市场使用磁盘版本和摘要，同时单独显示仍驻留的旧运行版本；事务变化丢弃内容判断")
    void storedArtifactIsIndependentOfRunningVersion() {
        service(installed("b", "1.0.0"));
        var installer = mock(top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller.class);
        var lifecycle = mock(top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator.class);
        var descriptor = installed("b", "2.0.0").descriptor();
        var artifact = new top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot(
                new top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin(descriptor,
                        java.nio.file.Path.of("plugins/b.jar")), 100, "a".repeat(64),
                top.sywyar.pixivdownload.plugin.runtime.install.provenance.ProvenanceSnapshotState.ABSENT, null, 0);
        when(installer.snapshotInstalledWithProvenance(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(
                new top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot(List.of(artifact), false));
        when(statusService.recoveryGateSnapshot()).thenReturn(
                top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginRecoveryGateSnapshot.safe(
                        top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.success()));
        when(statusService.report(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new PluginStatusReport(List.of(installed("b", "1.0.0"))));
        var props = new PluginCatalogProperties();
        props.setEnabled(true);
        var market = new PluginMarketService(new PluginRepositoryRegistry(props), catalogService,
                acquisitionService, statusService, null, null, installer, lifecycle);
        var card = entryOf(market.catalog(PluginRepository.OFFICIAL_ID), "b");
        var detail = market.pluginDetail(PluginRepository.OFFICIAL_ID, "b");
        assertThat(card.installation()).isEqualTo(detail.installation());
        assertThat(card.installedVersion()).isEqualTo("2.0.0");
        assertThat(card.installation().sha256()).isEqualTo("a".repeat(64));
        assertThat(card.installation().runtimeVersion()).isEqualTo("1.0.0");
        assertThat(card.installation().source()).isEqualTo("unknown");
        when(lifecycle.lifecycleMutationEpoch()).thenReturn(0L, 2L);
        assertThat(entryOf(market.catalog(PluginRepository.OFFICIAL_ID), "b").installation().state()).isEqualTo("UNKNOWN");
        when(lifecycle.lifecycleMutationEpoch()).thenReturn(3L);
        org.mockito.Mockito.clearInvocations(installer);
        assertThat(market.pluginDetail(PluginRepository.OFFICIAL_ID, "b").installation().state()).isEqualTo("UNKNOWN");
        org.mockito.Mockito.verify(installer, org.mockito.Mockito.never()).snapshotInstalledWithProvenance(
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * catalog：a 最新 1.0.0（兼容）、b 最新 2.0.0（兼容）、c 最新 1.0.0 但要求SDK 2.0（不兼容）、
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
        return new PluginCatalogEntry(id, id, "plugin.name", null, null, List.of(packages));
    }

    private static PluginCatalogPackage pkg(String version, String requiredSdk) {
        return new PluginCatalogPackage(version, "https://x/" + version + ".jar", 100L, "ab", null, null,
                requiredSdk, List.of(), null, List.of(), "stable", false);
    }

    /** 已安装诊断（有描述符 → 视为已安装），描述符携带已安装版本。 */
    private static PluginDiagnostic installed(String id, String version) {
        PluginDescriptor descriptor = new PluginDescriptor(id, id, version,
                VersionRequirement.unspecified(), List.of(), null, "ns", id + ":name", null, null, null,
                PluginKind.FEATURE);
        return new PluginDiagnostic(id, PluginStatus.STARTED, descriptor, false, List.of());
    }

    private PluginMarketService service(PluginDiagnostic... installed) {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        PluginCatalogManifest catalog = catalog();
        when(catalogService.loadPage(any(PluginRepository.class), any(PluginCatalogPageQuery.class)))
                .thenReturn(new PluginCatalogPage(
                        "manifest-v1", catalog.entries(), null, (long) catalog.entries().size(), Map.of(), false));
        PluginCatalogEntry detail = catalog.entries().stream()
                .filter(entry -> entry.pluginId().equals("b"))
                .findFirst().orElseThrow();
        when(catalogService.loadEntryPage(any(PluginRepository.class), eq("b"), isNull(), eq(24)))
                .thenReturn(new PluginCatalogDetailPage(
                        detail, "manifest-v1", null, (long) detail.packages().size(), false));
        when(statusService.report()).thenReturn(new PluginStatusReport(List.of(installed)));
        return new PluginMarketService(new PluginRepositoryRegistry(props), catalogService, acquisitionService,
                statusService);
    }

    private static PluginMarketEntryView entryOf(PluginMarketView view, String pluginId) {
        return view.entries().stream().filter(e -> e.pluginId().equals(pluginId)).findFirst().orElseThrow();
    }

    private static PluginMarketPackageView projected(String version, boolean compatible, String revocation) {
        return new PluginMarketPackageView(version, 100, "digest", true,
                compatible ? null : "999.0", compatible, false, List.of(), null, List.of(), "stable", false,
                top.sywyar.pixivdownload.plugin.verification.PluginVerificationProjector.builtInOfficial()
                        .withRevocation(revocation),
                !List.of("REVOKED", "YANKED", "NOT_CHECKED").contains(revocation));
    }

    @Test
    @DisplayName("稳定用户不跟随预发布，显式安装的预发布只跟随同渠道及正式版，详情保留其它版本")
    void recommendationsRespectInstalledChannel() {
        var versions = List.of(projected("9.0.0-beta.2", true, "CLEAR"),
                projected("10.0.0-nightly.20260101.1.1", true, "CLEAR"), projected("8.0.0", true, "CLEAR"));
        for (String installed : new String[] {null, "7.0.0"}) {
            var stable = PluginMarketEntryView.from(entry("example"), installed != null, installed, versions);
            assertThat(stable.recommendedVersion()).isEqualTo("8.0.0");
            assertThat(stable.latestVersion()).isEqualTo("8.0.0");
            assertThat(stable.packages()).hasSize(3);
        }
        var beta = PluginMarketEntryView.from(entry("example"), true, "9.0.0-beta.1", versions);
        assertThat(beta.recommendedVersion()).isEqualTo("9.0.0-beta.2");
        var nightly = PluginMarketEntryView.from(entry("example"), true, "10.0.0-nightly.20251231.1.1", versions);
        assertThat(nightly.recommendedVersion()).isEqualTo("10.0.0-nightly.20260101.1.1");
        var released = PluginMarketEntryView.from(entry("example"), true, "9.0.0-beta.1",
                List.of(projected("9.0.0", true, "CLEAR"), versions.get(0)));
        assertThat(released.recommendedVersion()).isEqualTo("9.0.0");
        var previewOnly = PluginMarketEntryView.from(entry("example"), true, "7.0.0", List.of(versions.get(0)));
        assertThat(previewOnly.recommendedVersion()).isNull();
        assertThat(previewOnly.updateAvailable()).isFalse();
        assertThat(previewOnly.latestVersion()).isEqualTo("9.0.0-beta.2");
        assertThat(previewOnly.installStatus()).isEqualTo(MarketInstallStatus.NO_RECOMMENDATION);
        assertThat(previewOnly.assuranceLevel()).isEqualTo(versions.get(0).verification().assuranceLevel());
    }

    @Test
    @DisplayName("只有不兼容预发布时保留真实 SDK 限制，不宣称存在可安装版本")
    void incompatiblePrereleaseHasNoRecommendation() {
        var view = PluginMarketEntryView.from(entry("example"), false, null,
                List.of(projected("9.0.0-rc.1", false, "CLEAR")));
        assertThat(view.recommendedVersion()).isNull();
        assertThat(view.installStatus()).isEqualTo(MarketInstallStatus.INCOMPATIBLE);
        assertThat(view.compatible()).isFalse();
    }

    @Test
    @DisplayName("最新版不兼容时选择版本号最高的安全兼容旧版，并据推荐版本判断更新")
    void selectsHighestCompatibleOlderVersion() {
        var versions = List.of(projected("8.0", false, "CLEAR"), projected("6.0", true, "CLEAR"),
                projected("7.0", true, "YANKED"), projected("5.0", true, "CLEAR"));
        var selected = PluginMarketEntryView.from(entry("example"), true, "5.0", versions);
        assertThat(selected.latestVersion()).isEqualTo("8.0");
        assertThat(selected.recommendedVersion()).isEqualTo("6.0");
        assertThat(selected.compatibilityReason()).isEqualTo("999.0");
        assertThat(selected.compatible()).isTrue();
        assertThat(selected.installStatus()).isEqualTo(MarketInstallStatus.UPDATE_AVAILABLE);
        var newerInstalled = PluginMarketEntryView.from(entry("example"), true, "7.5", versions);
        assertThat(newerInstalled.updateAvailable()).isFalse();
        assertThat(newerInstalled.installStatus()).isEqualTo(MarketInstallStatus.INSTALLED);
    }

    @Test
    @DisplayName("最新版兼容时仍选最新版，受限旧版和未知签名不能成为自动推荐")
    void preservesLatestAndRejectsUnsafeFallbacks() {
        var latest = PluginMarketEntryView.from(entry("example"), false, null,
                List.of(projected("8.0", true, "CLEAR"), projected("6.0", true, "CLEAR")));
        assertThat(latest.recommendedVersion()).isEqualTo("8.0");
        var unsigned = PluginMarketPackageView.from(new PluginRepositoryRegistry(new PluginCatalogProperties())
                .defaultRepository().orElseThrow(), pkg("5.0", null));
        var unavailable = PluginMarketEntryView.from(entry("example"), false, null,
                List.of(projected("8.0", false, "CLEAR"), projected("7.0", true, "REVOKED"),
                        projected("6.0", true, "NOT_CHECKED"), unsigned));
        assertThat(unavailable.recommendedVersion()).isNull();
        assertThat(unavailable.compatible()).isFalse();
        assertThat(unavailable.installStatus()).isEqualTo(MarketInstallStatus.INCOMPATIBLE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("分页市场从完整同代版本选择 SDK 及渠道兼容的旧版，卡片摘要不回传全部历史")
    void selectsCompatibleVersionBeyondSummaryPage(boolean prerelease) {
        var props = new PluginCatalogProperties();
        var registry = mock(PluginRepositoryRegistry.class);
        var repository = org.mockito.Mockito.spy(new PluginRepositoryRegistry(props).defaultRepository().orElseThrow());
        when(repository.pagedCatalog()).thenReturn(true);
        when(registry.featureEnabled()).thenReturn(true);
        when(registry.find("official")).thenReturn(java.util.Optional.of(repository));
        var signature = new top.sywyar.pixivdownload.plugin.signature.SignatureMetadata(
                1, "Ed25519", repository.trustedKeys().get(0).keyId(), "AA==");
        var current = new PluginCatalogPackage(prerelease ? "8.0.0-beta.1" : "8.0", "https://example.test/new.jar", 100L, "ab",
                signature, null, prerelease ? null : "999.0", List.of(), null, List.of(), prerelease ? "beta" : "stable", false);
        var older = new PluginCatalogPackage("6.0", "https://example.test/old.jar", 100L, "cd",
                signature, null, null, List.of(), null, List.of(), "stable", false);
        var oldest = new PluginCatalogPackage("5.0", "https://example.test/oldest.jar", 100L, "ef",
                signature, null, null, List.of(), null, List.of(), "stable", false);
        var summary = entry("example", current);
        var complete = entry("example", current, oldest, older);
        when(statusService.report()).thenReturn(new PluginStatusReport(List.of()));
        when(catalogService.loadPage(eq(repository), any())).thenReturn(
                new PluginCatalogPage("g1", List.of(summary), null, 1L, Map.of(), false));
        when(catalogService.loadEntrySnapshot(eq(repository), eq("example"), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new PluginCatalogDetailPage(complete, "g1", null, 3L, false));
        when(catalogService.loadEntryPage(repository, "example", null, 24))
                .thenReturn(new PluginCatalogDetailPage(summary, "g1", "older", 3L, false));
        var market = new PluginMarketService(registry, catalogService, acquisitionService, statusService);
        var card = entryOf(market.catalog("official"), "example");
        assertThat(card.recommendedVersion()).isEqualTo("6.0");
        assertThat(card.packages()).extracting(PluginMarketPackageView::version)
                .containsExactlyElementsOf(prerelease ? List.of("6.0") : List.of("8.0", "6.0"));
        var detail = market.pluginDetail("official", "example");
        assertThat(detail.recommendedVersion()).isEqualTo("6.0");
        assertThat(detail.nextVersionCursor()).isEqualTo("older");
        assertThat(detail.packages()).extracting(PluginMarketPackageView::version).containsExactly(current.version(), "6.0");

        when(catalogService.loadEntrySnapshot(eq(repository), eq("example"), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new PluginCatalogDetailPage(complete, "g2", null, 3L, false),
                        new PluginCatalogDetailPage(complete, "g1", null, 3L, true))
                .thenThrow(new top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException(
                        top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode.CATALOG_UNAVAILABLE, "offline"));
        for (int attempt = 0; attempt < 3; attempt++) {
            var incomplete = entryOf(market.catalog("official"), "example");
            assertThat(incomplete.compatibilitySearchIncomplete()).isTrue();
            assertThat(incomplete.recommendedVersion()).isNull();
            assertThat(incomplete.installStatus()).isEqualTo(MarketInstallStatus.UNAVAILABLE);
        }
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

    @ParameterizedTest
    @ValueSource(strings = {
            "7.3.0-rc.2,7.3.0-rc.3,7.3.0-rc.10",
            "7.3.0-rc.10,7.3.0-rc.3,7.3.0-rc.2",
            "7.3.0-rc.3,7.3.0-rc.2,7.3.0-rc.10"
    })
    @DisplayName("未声明最新版时，列表与详情按版本语义选择最高版本，不依赖清单顺序")
    void selectsHighestVersionRegardlessOfPackageOrder(String versions) {
        PluginMarketService market = service(installed("b", "7.3.0-rc.2"));
        PluginCatalogEntry entry = entry("b", Stream.of(versions.split(","))
                .map(version -> pkg(version, "1.0")).toArray(PluginCatalogPackage[]::new));
        when(catalogService.loadPage(any(PluginRepository.class), any(PluginCatalogPageQuery.class)))
                .thenReturn(new PluginCatalogPage("manifest-v1", List.of(entry), null, 1L, Map.of(), false));
        when(catalogService.loadEntryPage(any(PluginRepository.class), eq("b"), isNull(), eq(24)))
                .thenReturn(new PluginCatalogDetailPage(entry, "manifest-v1", null, 3L, false));

        assertThat(List.of(entryOf(market.catalog(PluginRepository.OFFICIAL_ID), "b"),
                market.pluginDetail(PluginRepository.OFFICIAL_ID, "b"))).allSatisfy(view -> {
            assertThat(view.latestVersion()).isEqualTo("7.3.0-rc.10");
            assertThat(view.installStatus()).isEqualTo(MarketInstallStatus.UPDATE_AVAILABLE);
            assertThat(view.updateAvailable()).isTrue();
        });
    }

    @ParameterizedTest
    @CsvSource({"7.3.0-rc.3,7.3.0-rc.3", "7.3.0-rc.99,7.3.0-rc.10"})
    @DisplayName("保留清单显式指定的可用版本，指定版本不存在时回退到语义最高版本")
    void respectsAvailableDeclaredVersion(String declared, String expected) {
        PluginCatalogMarketMeta meta = new PluginCatalogMarketMeta(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, declared, null, null, null,
                false, false, false);
        PluginCatalogEntry entry = new PluginCatalogEntry("sample", null, null, null, meta,
                List.of(pkg("7.3.0-rc.2", "1.0"), pkg("7.3.0-rc.3", "1.0"), pkg("7.3.0-rc.10", "1.0")));

        PluginMarketEntryView view = PluginMarketEntryView.from(
                new PluginRepositoryRegistry(new PluginCatalogProperties()).defaultRepository().orElseThrow(),
                entry, true, "7.3.0-rc.2");

        assertThat(view.latestVersion()).isEqualTo(expected);
        assertThat(view.installStatus()).isEqualTo(MarketInstallStatus.UPDATE_AVAILABLE);
    }

    @Test
    @DisplayName("未安装且最新版本要求更高SDK → INCOMPATIBLE、compatible=false、compatibilityReason=要求版本")
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
