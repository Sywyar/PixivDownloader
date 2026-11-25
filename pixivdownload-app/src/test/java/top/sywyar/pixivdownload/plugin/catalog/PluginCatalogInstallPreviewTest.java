package top.sywyar.pixivdownload.plugin.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogEntry;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.ProvenanceSnapshotState;

import java.nio.file.Files;
import java.nio.file.Path;
import top.sywyar.pixivdownload.plugin.PluginTestProvenance;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.install.PluginInstallService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.sdk.SdkVersion;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginCatalogInstallPreviewTest {
    private final PluginCatalogService catalog = mock(PluginCatalogService.class);
    private final PluginDependencyResolver dependencies = mock(PluginDependencyResolver.class);
    private final ExternalPluginInstaller installer = mock(ExternalPluginInstaller.class);
    private final PluginRuntimeManager runtime = mock(PluginRuntimeManager.class);
    private final ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
    private final PluginRepository repository = PluginRepository.official(true, 1000, 1000, 1024, 1024);
    private PluginCatalogInstallPreview service;
    @org.junit.jupiter.api.io.TempDir
    Path temp;

    @BeforeEach
    void prepare() {
        when(catalog.resolveRepository("official")).thenReturn(repository);
        when(coordinator.withMutationReservation(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(runtime.loadedDescriptors()).thenReturn(Map.of());
        when(runtime.packagePhases()).thenReturn(Map.of());
        inventory();
        service = new PluginCatalogInstallPreview(catalog, null, installer, runtime, coordinator, dependencies);
    }

    @Test
    void builtInFeatureCannotSupplyAnExternalPackageDependency() {
        entry("parent", "1.0.0", "a", "core", "plugin-market?");
        var preview = service.preview("official", "parent", "1.0.0");
        assertThat(preview.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.code()).isEqualTo("MISSING_DEPENDENCY");
            assertThat(conflict.pluginId()).isEqualTo("core");
        });
        verify(catalog, never()).loadEntry("official", "core");
    }

    @Test
    @DisplayName("依赖先于目标安装，可选依赖缺席不阻断，来源与活动消费者可见")
    void ordersDependenciesAndShowsConsumers() {
        entry("parent", "1.0.0", "a", "shared@1.0", "optional?");
        entry("shared", "1.1.0", "b");
        inventory(installed("consumer", "1.0.0", null, "shared@1.0"));
        when(runtime.activeDependents("shared")).thenReturn(List.of("consumer"));

        var preview = service.preview("official", "parent", "1.0.0");
        assertThat(preview.conflicts()).isEmpty();
        assertThat(preview.packages()).extracting(PluginCatalogInstallPreview.Item::pluginId)
                .containsExactly("shared", "parent");
        assertThat(preview.packages().get(0).consumers()).containsExactly("consumer");
        assertThat(preview.packages().get(0).activeConsumers()).containsExactly("consumer");
        assertThat(preview.packages().get(0).repositoryId()).isEqualTo("official");
        assertThat(preview.packages().get(0).restartImpact()).isEqualTo("PACKAGE_DECIDES");
    }

    @Test
    @DisplayName("已满足的跨来源依赖明确复用，不下载同名包")
    void reusesSatisfiedDependencyFromItsActualSource() {
        entry("parent", "1.0.0", "a", "shared@1.0");
        inventory(installed("shared", "1.0.0", "other"));
        var preview = service.preview("official", "parent", "1.0.0");
        assertThat(preview.packages().get(0).action()).isEqualTo("REUSE");
        assertThat(preview.packages().get(0).repositoryId()).isEqualTo("other");
        verify(catalog, never()).loadEntry(anyString(), eq("shared"));
    }

    @Test
    @DisplayName("跨来源替换与反向消费者不兼容在下载前阻断")
    void blocksCrossSourceReplacementAndReverseConflict() {
        entry("shared", "2.0.0", "a");
        inventory(installed("shared", "1.0.0", "other"), installed("consumer", "1.0.0", null, "shared@1.0"));
        var preview = service.preview("official", "shared", "2.0.0");
        assertThat(preview.conflicts()).extracting(PluginCatalogInstallPreview.Conflict::code)
                .contains("SOURCE_CONFLICT", "REVERSE_DEPENDENCY");
        var executed = new AtomicBoolean();
        assertThatThrownBy(() -> service.execute("official", "shared", "2.0.0", preview.fingerprint(),
                plan -> executed.getAndSet(true))).isInstanceOf(PluginCatalogException.class)
                .extracting(failure -> ((PluginCatalogException) failure).code())
                .isEqualTo(PluginCatalogErrorCode.INSTALL_PREVIEW_BLOCKED);
        assertThat(executed).isFalse();
    }

    @Test
    @DisplayName("同版本来源字节变化使旧预览失效，不执行安装")
    void rejectsChangedPackage() {
        entry("parent", "1.0.0", "a");
        var preview = service.preview("official", "parent", "1.0.0");
        entry("parent", "1.0.0", "b");
        assertThatThrownBy(() -> service.execute("official", "parent", "1.0.0", preview.fingerprint(),
                plan -> fail("must not execute"))).isInstanceOf(PluginCatalogException.class)
                .extracting(failure -> ((PluginCatalogException) failure).code())
                .isEqualTo(PluginCatalogErrorCode.INSTALL_PREVIEW_CHANGED);
    }

    @Test
    @DisplayName("安装集合或运行期启停变化后必须重新预览")
    void rejectsChangedInstallationAndRuntimeEpoch() {
        entry("parent", "1.0.0", "a");
        String initial = service.preview("official", "parent", "1.0.0").fingerprint();
        inventory(installed("parent", "0.1.0", null));
        String installed = service.preview("official", "parent", "1.0.0").fingerprint();
        when(coordinator.lifecycleMutationEpoch()).thenReturn(2L);
        assertThat(installed).isNotEqualTo(initial);
        assertThat(service.preview("official", "parent", "1.0.0").fingerprint()).isNotEqualTo(installed);
    }

    @Test
    @DisplayName("目录循环与同组依赖版本冲突可解释且不自动覆盖")
    void detectsCycleAndConflictingRequirements() {
        entry("parent", "1.0.0", "a", "shared@1.0", "other@1.0");
        entry("shared", "1.0.0", "b");
        entry("other", "1.0.0", "c", "shared@2.0", "parent@1.0");
        assertThat(service.preview("official", "parent", "1.0.0").conflicts())
                .extracting(PluginCatalogInstallPreview.Conflict::code).contains("CYCLE", "VERSION_CONFLICT");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"healthy", "middle"})
    void preflightsReusedProvidersBeforeInstallingAnySibling(String provider) throws Exception {
        Path plugins = temp.resolve("plugins");
        for (String id : List.of("healthy", "middle")) {
            var jar = PluginTestProvenance.stageRepairProbe(plugins, id, "1.0.0", "pixiv.desktop-only=true"
                    + (id.equals("middle") ? "\nplugin.dependencies=healthy" : ""));
            PluginTestProvenance.writeVerifiedLocalUpload(plugins, jar);
        }
        var toggles = new PluginToggleProperties();
        var downloader = mock(PluginPackageDownloader.class);
        try (var session = PluginBootstrapSession.createContext(plugins, PluginEnabledSnapshot.empty(), PluginTestProvenance.verifier()).start()) {
            var lifecycle = mock(PluginLifecycleService.class);
            var resolver = new PluginDependencyResolver(session.installer(), lifecycle, session.manager(), toggles);
            var mutations = new ExternalPluginLifecycleCoordinator(session.manager(), lifecycle, session.installer(),
                    mock(RecoveryModeService.class), resolver);
            var preview = new PluginCatalogInstallPreview(catalog, null, session.installer(), session.manager(), mutations, resolver);
            var acquisition = new PluginCatalogAcquisitionService(catalog, downloader, new PluginInstallService(mutations, resolver),
                    resolver, null, null, preview);
            for (String id : List.of("sibling", "parent")) {
                var refs = id.equals("parent") ? List.of("sibling", provider) : List.<String>of();
                var jar = PluginTestProvenance.stageRepairProbe(temp, id, "1.0.0",
                        "pixiv.lifecycle-policy=process-restart\nplugin.dependencies=" + String.join(",", refs));
                String hash = PluginPackageIntegrity.sha256Hex(jar);
                var pkg = new PluginCatalogPackage("1.0.0", "https://example.invalid/" + id + ".jar", Files.size(jar), hash,
                        PluginTestProvenance.signArtifact(id, "1.0.0", Files.size(jar), hash), null,
                        SdkVersion.MAJOR + "." + SdkVersion.MINOR, refs, null, List.of(), null, false);
                var entry = new PluginCatalogEntry(id, null, null, null, null, List.of(pkg));
                when(catalog.resolvePackage("official", id, "1.0.0")).thenReturn(new PluginCatalogService.ResolvedPackage(repository, entry, pkg));
                when(catalog.loadEntry("official", id)).thenReturn(entry);
                when(downloader.downloadToTemp(repository, pkg)).thenReturn(jar);
            }
            var ready = acquisition.preview("official", "parent", "1.0.0");
            assertThat(ready.conflicts()).isEmpty();
            toggles.setEnabled("healthy", false);
            var blocked = acquisition.preview("official", "parent", "1.0.0");
            assertThat(blocked.packages()).filteredOn(item -> provider.equals(item.pluginId())).singleElement()
                    .satisfies(item -> assertThat(item.action()).isEqualTo("BLOCKED"));
            assertThat(blocked.conflicts()).singleElement().satisfies(conflict -> {
                assertThat(conflict.pluginId()).isEqualTo("healthy");
                assertThat(conflict.arguments()).contains("DISABLED");
            });
            for (var fingerprint : List.of(ready.fingerprint(), blocked.fingerprint())) {
                assertThatThrownBy(() -> acquisition.installPreviewed("official", "parent", "1.0.0", null, fingerprint))
                        .isInstanceOf(PluginCatalogException.class);
            }
            verifyNoInteractions(downloader);
            assertThat(session.installer().listInstalled()).extracting(InstalledPlugin::id).containsExactlyInAnyOrder("healthy", "middle");
            toggles.setEnabled("healthy", true);
            String digest = session.installer().installedArtifactForVersion("healthy", "1.0.0").orElseThrow().artifactSha256();
            session.installer().revokeTrust("healthy");
            var untrusted = acquisition.preview("official", "parent", "1.0.0");
            assertThat(untrusted.conflicts()).singleElement().satisfies(conflict -> assertThat(conflict.pluginId()).isEqualTo("healthy"));
            assertThatThrownBy(() -> acquisition.installPreviewed("official", "parent", "1.0.0", null, untrusted.fingerprint()))
                    .isInstanceOf(PluginCatalogException.class);
            verifyNoInteractions(downloader);
            session.installer().approveTrust("healthy", digest);
            var receipt = acquisition.installPreviewed("official", "parent", "1.0.0", null,
                    acquisition.preview("official", "parent", "1.0.0").fingerprint());
            assertThat(receipt.accepted()).as(receipt.toString()).isTrue();
            assertThat(receipt.effectiveAfterRestart()).isTrue();
        }
        try (var restarted = PluginBootstrapSession.createContext(plugins, PluginEnabledSnapshot.empty(), PluginTestProvenance.verifier()).start()) {
            assertThat(restarted.status().startedPluginIds()).contains("parent", "sibling", provider, "healthy");
        }
    }

    @Test
    @DisplayName("未改变的预览执行原计划，各依赖只出现一次")
    void executesTheConfirmedPlan() {
        entry("parent", "1.0.0", "a", "shared@1.0", "shared@1.0");
        entry("shared", "1.0.0", "b");
        var preview = service.preview("official", "parent", "1.0.0");
        List<String> result = service.execute("official", "parent", "1.0.0", preview.fingerprint(),
                plan -> plan.packages().stream().map(value -> value.source().entry().pluginId()).toList());
        assertThat(result).containsExactly("shared", "parent");
    }

    private void inventory(InstalledPluginSnapshot... entries) {
        when(installer.snapshotInstalledWithProvenance(anyInt(), anyLong()))
                .thenReturn(new InstalledPluginInventorySnapshot(List.of(entries), false));
    }

    @Test
    @DisplayName("父包拒绝保留已经提交的依赖结果，来源在下载期间变化时不交给安装器")
    void previewedAcquisitionPreservesPartialSuccessAndChecksDownloadedSelection() throws Exception {
        entry("parent", "1.0.0", "a", "shared@1.0");
        entry("shared", "1.0.0", "b");
        var downloader = mock(PluginPackageDownloader.class);
        var installs = mock(top.sywyar.pixivdownload.plugin.install.PluginInstallService.class);
        var acquisition = new PluginCatalogAcquisitionService(catalog, downloader, installs,
                dependencies, null, null, service);
        when(downloader.downloadToTemp(any(), any())).thenAnswer(call ->
                java.nio.file.Files.createTempFile(temp, "package-", ".jar"));
        when(installs.installTrustedFile(any(), eq(false), any())).thenReturn(
                new top.sywyar.pixivdownload.plugin.install.PluginInstallReport(
                        top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome.INSTALLED,
                        true, false, "shared", "1.0.0", null, List.of(), List.of(), List.of()),
                new top.sywyar.pixivdownload.plugin.install.PluginInstallReport(
                        top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome.REJECTED_INTEGRITY,
                        false, false, "parent", "1.0.0", null, List.of(), List.of(), List.of()));
        var preview = service.preview("official", "parent", "1.0.0");
        clearInvocations(catalog);
        var report = acquisition.installPreviewed("official", "parent", "1.0.0", null, preview.fingerprint());
        assertThat(report.accepted()).isFalse();
        assertThat(report.dependencyInstallResults()).singleElement()
                .satisfies(result -> assertThat(result.pluginId()).isEqualTo("shared"));
        verify(catalog, times(2)).resolvePackage("official", "parent", "1.0.0");
        verify(catalog, times(2)).resolvePackage("official", "shared", "1.0.0");

        clearInvocations(installs);
        doAnswer(call -> {
            entry("shared", "1.0.0", "c");
            return java.nio.file.Files.createTempFile(temp, "changed-", ".jar");
        }).when(downloader).downloadToTemp(any(), any());
        assertThatThrownBy(() -> acquisition.installPreviewed("official", "parent", "1.0.0", null, preview.fingerprint()))
                .isInstanceOf(PluginCatalogException.class);
        verifyNoInteractions(installs);
        try (var files = java.nio.file.Files.list(temp)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("深度边界与累计包数量超限时停止计划，不把截断内容当作完整预览")
    void rejectsOverBudgetPlans() {
        for (int i = 0; i <= PluginCatalogInstallPreview.MAX_DEPTH; i++) {
            entry("depth-" + i, "1.0.0", "a", "depth-" + (i + 1) + "@1.0");
        }
        assertThatThrownBy(() -> service.preview("official", "depth-0", "1.0.0"))
                .isInstanceOf(PluginCatalogException.class);
        String[] refs = java.util.stream.IntStream.range(0, PluginCatalogInstallPreview.MAX_PACKAGES)
                .mapToObj(i -> "leaf-" + i + "@1.0").toArray(String[]::new);
        for (int i = 0; i < refs.length; i++) entry("leaf-" + i, "1.0.0", "a");
        entry("wide", "1.0.0", "b", refs);
        assertThatThrownBy(() -> service.preview("official", "wide", "1.0.0"))
                .isInstanceOf(PluginCatalogException.class);
        inventory(java.util.stream.IntStream.range(0, refs.length)
                .mapToObj(i -> installed("leaf-" + i, "1.0.0", null)).toArray(InstalledPluginSnapshot[]::new));
        assertThatThrownBy(() -> service.preview("official", "wide", "1.0.0"))
                .isInstanceOf(PluginCatalogException.class);
    }

    @Test
    @DisplayName("清点不完整时拒绝生成指纹，同一内容的清点可重复使用确认")
    void requiresCompleteStableInventory() {
        entry("parent", "1.0.0", "a");
        inventory(installed("previous", "1.0.0", null));
        String first = service.preview("official", "parent", "1.0.0").fingerprint();
        inventory(installed("previous", "1.0.0", null));
        assertThat(service.preview("official", "parent", "1.0.0").fingerprint()).isEqualTo(first);
        when(installer.snapshotInstalledWithProvenance(anyInt(), anyLong()))
                .thenReturn(new InstalledPluginInventorySnapshot(List.of(), true));
        assertThatThrownBy(() -> service.preview("official", "parent", "1.0.0"))
                .isInstanceOf(PluginCatalogException.class);
    }

    private InstalledPluginSnapshot installed(String id, String version, String source, String... deps) {
        var descriptor = new PluginDescriptor(id, id, version, VersionRequirement.unspecified(),
                PluginDependencyRef.parseList(String.join(",", deps)), null, null, id, null, null, null, PluginKind.FEATURE);
        var provenance = source == null ? null : mock(PluginProvenanceRecord.class);
        if (provenance != null) when(provenance.repositoryId()).thenReturn(source);
        return new InstalledPluginSnapshot(new InstalledPlugin(descriptor, Path.of(id + ".jar")), 10, "c".repeat(64),
                provenance == null ? ProvenanceSnapshotState.ABSENT : ProvenanceSnapshotState.PRESENT, provenance, 0);
    }

    private void entry(String id, String version, String hash, String... deps) {
        var pkg = new PluginCatalogPackage(version, "https://example.invalid/" + id + ".jar", 10L,
                hash.repeat(64), null, null, null, List.of(deps), null, List.of(), null, false);
        var entry = new PluginCatalogEntry(id, null, null, null, null, List.of(pkg));
        when(catalog.resolvePackage("official", id, version))
                .thenReturn(new PluginCatalogService.ResolvedPackage(repository, entry, pkg));
        when(catalog.loadEntry("official", id)).thenReturn(entry);
    }
}
