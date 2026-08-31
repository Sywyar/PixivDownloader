package top.sywyar.pixivdownload.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.context.WebApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.registry.web.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.CommittedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PreparedPluginTransaction;
import top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustDecision;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-external-douyin",
        "setup.browser.auto-open=false"
})
@ContextConfiguration(initializers = DouyinExternalPluginBootContextTest.DouyinPluginBootstrapInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("externalDouyinJarStaged")
@DisplayName("外置 douyin 插件经真实上下文接入中性能力")
class DouyinExternalPluginBootContextTest {

    private static final String DOUYIN_CLASSES_PROPERTY = "douyin.plugin.classes";
    private static final String THIRD_PARTY_PACKAGE_PROPERTY = "douyin.third-party.package";
    private static final String THIRD_PARTY_MODE_PROPERTY = "douyin.third-party.mode";
    private static final String THIRD_PARTY_SIGNATURE_PROPERTY = "douyin.third-party.signature";
    private static final String THIRD_PARTY_PUBLIC_KEY_PROPERTY = "douyin.third-party.public-key";
    private static final String THIRD_PARTY_KEY_ID = "douyin-third-party-ci";
    private static final String THIRD_PARTY_REPOSITORY_ID = "douyin-third-party-ci";
    private static final Path PLUGINS_DIR = Path.of("target/test-runtime/plugins-external-douyin");
    private static final Set<String> DOUYIN_SCHEDULE_SOURCE_TYPES = Set.of(
            "douyin.user",
            "douyin.search",
            "douyin.collection",
            "douyin.music",
            "douyin.account.own-works",
            "douyin.account.liked-works",
            "douyin.account.favorite-works",
            "douyin.account.favorite-folder",
            "douyin.account.favorite-collection");
    private static final StageResult STAGE = stageExternalDouyinJar();
    private static final boolean STAGED = STAGE.staged();

    static {
        if (STAGED) {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
            System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
            System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, PLUGINS_DIR.toString());
        }
    }

    @SuppressWarnings("unused")
    static boolean externalDouyinJarStaged() {
        return STAGED;
    }

    @Autowired
    private PluginRuntimeManager pluginRuntimeManager;
    @Autowired
    private PluginRuntimeStatus pluginRuntimeStatus;
    @Autowired
    private PluginDiscoveryResult pluginDiscoveryResult;
    @Autowired
    private PluginRegistry pluginRegistry;
    @Autowired
    private RouteAccessRegistry routeAccessRegistry;
    @Autowired
    private StaticResourceRegistry staticResourceRegistry;
    @Autowired
    private NavigationRegistry navigationRegistry;
    @Autowired
    private ExternalPluginContextManager externalPluginContextManager;
    @Autowired
    private ExternalPluginLifecycleCoordinator lifecycleCoordinator;
    @Autowired
    private PluginLifecycleService pluginLifecycleService;
    @Autowired
    private WebApplicationContext applicationContext;
    @Autowired
    private WebI18nBundleRegistry webI18nBundleRegistry;
    @Autowired
    private ScheduleCapabilityRegistry scheduleCapabilityRegistry;

    @AfterAll
    void releasePluginsAndCleanup() {
        if (pluginRuntimeManager != null) {
            pluginRuntimeManager.shutdown();
        }
        deleteRecursivelyQuietly(PLUGINS_DIR);
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
    }

    @Test
    @DisplayName("运行时状态 / 发现结果 Bean：POPULATED、started 含 douyin、无失败、发现 douyin")
    void runtimeStatusAndDiscoveryBeansSeeDouyin() {
        assertThat(pluginRuntimeStatus.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(pluginRuntimeStatus.startedPluginIds()).contains("douyin");
        assertThat(pluginRuntimeStatus.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.discovered())
                .extracting(DiscoveredFeaturePlugin::featurePluginId).contains("douyin");
        assertThat(pluginRuntimeManager.loadedDescriptor("douyin"))
                .get()
                .satisfies(descriptor -> assertThat(descriptor.version()).isEqualTo("1.0.0"));
    }

    @Test
    @DisplayName("第三方验收包经生产风险确认边界安装并持久化对应信任")
    void thirdPartyPackageUsesExpectedTrustBoundary() throws IOException {
        assumeTrue(STAGE.mode() != null);
        assertThat(PluginDevelopmentArtifacts.enabled()).isFalse();
        assertThat(STAGE.initialOutcome()).isEqualTo(PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED);
        PluginProvenanceRecord provenance = new PluginProvenanceStore(PLUGINS_DIR)
                .read(STAGE.installedArtifact()).orElseThrow();

        if ("signed".equals(STAGE.mode())) {
            assertThat(provenance.source()).isEqualTo(PluginPackageSource.MARKET_CATALOG);
            assertThat(provenance.officialRepository()).isFalse();
            assertThat(provenance.status()).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(provenance.trustDecision().approvalType())
                    .isEqualTo(PluginTrustDecision.ApprovalType.PUBLISHER);
        } else {
            assertThat(provenance.source()).isEqualTo(PluginPackageSource.LOCAL_UPLOAD);
            assertThat(provenance.status()).isEqualTo(VerificationStatus.UNSIGNED_ALLOWED);
            assertThat(provenance.trustDecision().approvalType())
                    .isEqualTo(PluginTrustDecision.ApprovalType.EXACT_ARTIFACT);
        }
    }

    @Test
    @DisplayName("PluginRegistry Bean 包含外置 douyin")
    void pluginRegistryBeanContainsDouyinAsExternal() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .contains("douyin");
        assertThat(pluginRegistry.source("douyin")).contains(PluginSource.EXTERNAL);
        assertThat(externalDouyinClassLoader()).isNotSameAs(getClass().getClassLoader());
    }

    @Test
    @DisplayName("douyin 子上下文经中性注册中心原子发布九类计划来源")
    void douyinScheduleSourcesArePublishedFromExternalChildContext() {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("douyin").orElseThrow();
        ClassLoader externalCl = externalDouyinClassLoader();
        var sourceExecutors = child.getBeansOfType(ScheduledSourceExecutor.class).values();

        assertThat(sourceExecutors).hasSize(9)
                .allSatisfy(executor -> assertThat(executor.getClass().getClassLoader())
                        .isSameAs(externalCl));
        assertThat(sourceExecutors)
                .extracting(ScheduledSourceExecutor::sourceType)
                .containsExactlyInAnyOrderElementsOf(DOUYIN_SCHEDULE_SOURCE_TYPES);
        assertThat(applicationContext.getBeansOfType(ScheduledSourceExecutor.class).values())
                .noneMatch(executor -> DOUYIN_SCHEDULE_SOURCE_TYPES.contains(executor.sourceType()));

        assertThat(scheduleCapabilityRegistry.snapshotView().owners())
                .filteredOn(owner -> owner.owner().featurePluginId().equals("douyin"))
                .singleElement()
                .satisfies(owner -> {
                    assertThat(owner.owner().packageId()).isEqualTo("douyin");
                    assertThat(owner.sourceTypes())
                            .containsExactlyInAnyOrderElementsOf(DOUYIN_SCHEDULE_SOURCE_TYPES);
                    assertThat(owner.sourceDescriptors())
                            .extracting(descriptor -> descriptor.sourceType())
                            .containsExactlyInAnyOrderElementsOf(DOUYIN_SCHEDULE_SOURCE_TYPES);
                    assertThat(owner.workTypes()).containsExactly("douyin");
                    assertThat(owner.credentialPolicyIds()).containsExactly("douyin.cookie");
                    assertThat(owner.guardIds()).containsExactly("douyin.risk");
                    assertThat(owner.sourceDescriptors())
                            .filteredOn(descriptor -> descriptor.sourceType()
                                    .equals("douyin.account.favorite-folder"))
                            .singleElement()
                            .satisfies(descriptor -> assertThat(descriptor.frontend().moduleUrl())
                                    .isEqualTo("/pixiv-douyin-download/douyin-schedule-sources.js"));
                });

        try (SchedulePlanningLease planning = scheduleCapabilityRegistry
                .prepareSource("douyin.account.favorite-folder").orElseThrow()) {
            assertThat(scheduleCapabilityRegistry.activate(planning)).isTrue();
            assertThat(planning.owner().featurePluginId()).isEqualTo("douyin");
            assertThat(planning.sourceExecutor()).isPresent();
            assertThat(planning.sourceExecutor().orElseThrow().getClass().getClassLoader())
                    .isSameAs(externalCl);
        }
    }

    @Test
    @DisplayName("第三方 douyin 停启保留代际，物理重载换代并恢复完整能力足迹")
    void thirdPartyDouyinTrustSurvivesLifecycleReplacement() {
        long initialGeneration = pluginLifecycleService.generation("douyin").orElseThrow();
        ClassLoader initialClassLoader = externalDouyinClassLoader();
        ConfigurableApplicationContext initialContext =
                externalPluginContextManager.contextFor("douyin").orElseThrow();

        lifecycleCoordinator.stop("douyin");
        assertThat(pluginLifecycleService.phase("douyin")).contains(PluginRuntimePhase.STOPPED);
        assertThat(externalPluginContextManager.contextFor("douyin")).isEmpty();
        assertThat(initialContext.isActive()).isFalse();
        assertDouyinServiceFootprint(false);

        lifecycleCoordinator.start("douyin");
        assertThat(pluginLifecycleService.phase("douyin")).contains(PluginRuntimePhase.STARTED);
        assertThat(pluginLifecycleService.generation("douyin")).contains(initialGeneration);
        assertThat(externalDouyinClassLoader()).isSameAs(initialClassLoader);
        assertDouyinServiceFootprint(true);

        ConfigurableApplicationContext restartedContext =
                externalPluginContextManager.contextFor("douyin").orElseThrow();
        lifecycleCoordinator.reload("douyin");
        assertThat(pluginLifecycleService.phase("douyin")).contains(PluginRuntimePhase.STARTED);
        assertThat(pluginLifecycleService.generation("douyin").orElseThrow()).isGreaterThan(initialGeneration);
        assertThat(externalDouyinClassLoader()).isNotSameAs(initialClassLoader);
        assertThat(restartedContext.isActive()).isFalse();
        assertDouyinServiceFootprint(true);
    }

    @Test
    @DisplayName("douyin provider 由外置子 ApplicationContext 托管")
    void externalDouyinChildContextHostsProviderBean() throws Exception {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("douyin").orElseThrow();
        ClassLoader externalCl = externalDouyinClassLoader();
        assertThat(child.getParent()).isSameAs(applicationContext);
        assertThat(child.getClassLoader()).isSameAs(externalCl);

        Class<?> providerClass =
                externalCl.loadClass("top.sywyar.pixivdownload.douyin.gallery.DouyinGalleryDataProvider");
        assertThat(child.getBeanNamesForType(providerClass)).isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(providerClass)).isEmpty();
    }

    @Test
    @DisplayName("douyin i18n 提供来源显示名")
    void douyinI18nProvidesSourceDisplayName() {
        WebI18nBundleRegistry.RegisteredBundle bundle = webI18nBundleRegistry.resolve("douyin");

        assertThat(bundle).isNotNull();
        assertThat(bundle.load(Locale.SIMPLIFIED_CHINESE)).containsEntry("source.douyin", "抖音");
        assertThat(bundle.load(Locale.ENGLISH)).containsEntry("source.douyin", "Douyin");
        assertThat(bundle.load(Locale.SIMPLIFIED_CHINESE))
                .containsEntry("nav.gallery", "抖音")
                .containsEntry("gallery.page.title", "抖音画廊")
                .containsEntry("detail.page-title", "抖音作品详情");
        assertThat(externalDouyinClassLoader()
                .getResource("static/pixiv-douyin-download/douyin-gallery-frontend.js")).isNull();
        assertThat(getClass().getClassLoader()
                .getResource("static/pixiv-douyin-download/douyin-gallery-frontend.js")).isNull();
    }

    @Test
    @DisplayName("douyin 管理员画廊路由、静态资源与类型切换导航经外置 classloader 注册")
    void douyinGalleryWebContributionsAreClassloaderAware() {
        ClassLoader externalCl = externalDouyinClassLoader();
        List<String> adminRoutes = List.of(
                "/pixiv-douyin-gallery.html", "/pixiv-douyin-gallery/**",
                "/pixiv-douyin.html", "/pixiv-douyin/**", "/api/douyin/gallery/**");

        assertThat(routeAccessRegistry.routes())
                .filteredOn(route -> route.pluginId().equals("douyin")
                        && adminRoutes.contains(route.route().pathPattern()))
                .hasSize(adminRoutes.size())
                .allSatisfy(route -> assertThat(route.route().accessPolicy()).isEqualTo(AccessPolicy.ADMIN));
        assertThat(staticResourceRegistry.resources())
                .filteredOn(resource -> resource.pluginId().equals("douyin"))
                .allSatisfy(resource -> assertThat(resource.classLoader()).isSameAs(externalCl))
                .extracting(resource -> resource.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/pixiv-douyin-gallery.html", "/pixiv-douyin.html",
                        "/pixiv-douyin-gallery/", "/pixiv-douyin/", "/pixiv-douyin-download/");
        assertThat(navigationRegistry.navigation())
                .filteredOn(item -> item.pluginId().equals("douyin"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.navigation().id()).isEqualTo("douyin-gallery-type-switch");
                    assertThat(item.navigation().placements())
                            .containsExactly(NavigationPlacements.GALLERY_TYPE_SWITCH);
                    assertThat(item.navigation().visibleTo()).isEqualTo(AccessPolicy.ADMIN);
                    assertThat(item.navigation().href()).isEqualTo("/pixiv-douyin-gallery.html?view=all");
                });
        assertThat(externalCl.getResource("static/pixiv-douyin-gallery.html")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-douyin-gallery/pixiv-douyin-gallery.css")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-douyin.html")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-douyin/pixiv-douyin.css")).isNotNull();
        assertThat(getClass().getClassLoader().getResource("static/pixiv-douyin.html")).isNull();
    }

    private ClassLoader externalDouyinClassLoader() {
        return pluginRegistry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("douyin")).findFirst().orElseThrow().classLoader();
    }

    private void assertDouyinServiceFootprint(boolean present) {
        assertThat(routeAccessRegistry.routes().stream().anyMatch(route -> route.pluginId().equals("douyin")))
                .isEqualTo(present);
        assertThat(staticResourceRegistry.resources().stream()
                .anyMatch(resource -> resource.pluginId().equals("douyin")))
                .isEqualTo(present);
        assertThat(scheduleCapabilityRegistry.snapshotView().owners().stream()
                .anyMatch(owner -> owner.owner().featurePluginId().equals("douyin")))
                .isEqualTo(present);
    }

    private static StageResult stageExternalDouyinJar() {
        String thirdPartyPackage = System.getProperty(THIRD_PARTY_PACKAGE_PROPERTY);
        if (thirdPartyPackage != null && !thirdPartyPackage.isBlank()) {
            try {
                return installThirdPartyPackage(Path.of(thirdPartyPackage));
            } catch (IOException | RuntimeException ex) {
                throw new IllegalStateException("无法安装第三方 Douyin 验收包", ex);
            }
        }
        try {
            String configured = System.getProperty(DOUYIN_CLASSES_PROPERTY);
            if (configured == null || configured.isBlank()) {
                return StageResult.skipped();
            }
            Path classes = Path.of(configured);
            if (!Files.isDirectory(classes) || !Files.exists(classes.resolve("plugin.properties"))) {
                return StageResult.skipped();
            }
            deleteRecursivelyQuietly(PLUGINS_DIR);
            Files.createDirectories(PLUGINS_DIR);
            Path jar = PLUGINS_DIR.resolve("douyin-plugin.jar");
            zipDirectoryAsJar(classes, jar);
            PluginTestProvenance.writeVerifiedLocalUpload(PLUGINS_DIR, jar, "douyin", "1.0.0");
            return new StageResult(true, PluginTestProvenance.verifier(), null, null, jar);
        } catch (IOException | RuntimeException ex) {
            return StageResult.skipped();
        }
    }

    private static StageResult installThirdPartyPackage(Path source) throws IOException {
        if (PluginDevelopmentArtifacts.enabled()) {
            throw new IllegalStateException("third-party package verification requires production mode");
        }
        String mode = System.getProperty(THIRD_PARTY_MODE_PROPERTY, "").trim();
        if (!mode.equals("signed") && !mode.equals("unsigned")) {
            throw new IllegalArgumentException("unsupported third-party package mode: " + mode);
        }
        if (!Files.isRegularFile(source)) {
            throw new IOException("third-party Douyin package is missing: " + source);
        }
        deleteRecursivelyQuietly(PLUGINS_DIR);
        Files.createDirectories(PLUGINS_DIR);

        SignatureMetadata signature = null;
        PluginSupplyChainVerifier verifier = new PluginSupplyChainVerifier();
        if (mode.equals("signed")) {
            Path signatureFile = Path.of(System.getProperty(THIRD_PARTY_SIGNATURE_PROPERTY, ""));
            signature = new ObjectMapper().readValue(signatureFile.toFile(), SignatureMetadata.class);
            String publicKey = System.getProperty(THIRD_PARTY_PUBLIC_KEY_PROPERTY, "");
            TrustedPluginKey key = new TrustedPluginKey(
                    THIRD_PARTY_KEY_ID,
                    SignatureMetadata.ED25519,
                    publicKey,
                    TrustedPluginKey.State.ACTIVE,
                    "Douyin Third-Party CI",
                    "Douyin third-party compatibility canary",
                    false);
            verifier = new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key)));
        }

        String sha256 = PluginPackageIntegrity.sha256Hex(source);
        PluginPackageOrigin origin = mode.equals("signed")
                ? PluginPackageOrigin.forTrustedCatalog(
                        THIRD_PARTY_REPOSITORY_ID,
                        false,
                        Files.size(source),
                        sha256,
                        signature)
                : PluginPackageOrigin.localUnsignedUpload(null);
        try (ExternalPluginInstaller installer = new ExternalPluginInstaller(
                PLUGINS_DIR, PluginPackageLimits.defaults(), verifier)) {
            if (!installer.recoverPendingTransactions().safeToScan()) {
                throw new IllegalStateException("third-party package recovery gate is blocked");
            }
            PluginInstallResult pending = installFully(installer, source, origin);
            if (pending.outcome() != PluginInstallOutcome.TRUST_CONFIRMATION_REQUIRED
                    || pending.trustRequirement() == null) {
                throw new IllegalStateException("third-party package did not require trust confirmation: "
                        + pending.outcome());
            }
            PluginPackageOrigin confirmed = mode.equals("signed")
                    ? PluginPackageOrigin.forTrustedCatalog(
                            THIRD_PARTY_REPOSITORY_ID,
                            false,
                            Files.size(source),
                            sha256,
                            signature,
                            Map.of(),
                            Map.of(),
                            false,
                            pending.trustRequirement().artifactSha256())
                    : PluginPackageOrigin.localUnsignedUpload(pending.trustRequirement().artifactSha256());
            PluginInstallResult installed = installFully(installer, source, confirmed);
            if (installed.outcome() != PluginInstallOutcome.INSTALLED || installed.installedPath() == null) {
                throw new IllegalStateException("third-party package installation failed: " + installed.outcome());
            }
            return new StageResult(
                    true, verifier, mode, pending.outcome(), installed.installedPath());
        }
    }

    private static PluginInstallResult installFully(
            ExternalPluginInstaller installer,
            Path source,
            PluginPackageOrigin origin) {
        PreparedPluginTransaction prepared = installer.prepareTransaction(source, false, origin);
        if (!prepared.readyToCommit()) {
            return prepared.result();
        }
        CommittedPluginTransaction committed = installer.commitTransaction(prepared);
        installer.verifyCommittedTarget(committed);
        installer.markActivated(committed);
        installer.completeTransaction(committed);
        return prepared.result();
    }

    public static final class DouyinPluginBootstrapInitializer
            implements org.springframework.context.ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            PluginTestProvenance.registerBootstrapSession(
                    context, PluginEnabledSnapshot.empty(), STAGE.verifier());
        }
    }

    private static void zipDirectoryAsJar(Path sourceDir, Path jarPath) throws IOException {
        try (OutputStream out = Files.newOutputStream(jarPath);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            List<Path> files;
            try (var walk = Files.walk(sourceDir)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private record StageResult(
            boolean staged,
            PluginSupplyChainVerifier verifier,
            String mode,
            PluginInstallOutcome initialOutcome,
            Path installedArtifact) {

        private static StageResult skipped() {
            return new StageResult(false, PluginTestProvenance.verifier(), null, null, null);
        }
    }
}
