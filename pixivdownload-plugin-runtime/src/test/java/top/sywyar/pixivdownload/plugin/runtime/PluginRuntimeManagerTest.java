package top.sywyar.pixivdownload.plugin.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginRuntimeLayout;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactSnapshot;
import top.sywyar.pixivdownload.runtimeprobe.BootstrapProbeFeaturePlugin;
import top.sywyar.pixivdownload.runtimeprobe.BootstrapProbePlugin;
import top.sywyar.pixivdownload.runtimeprobe.DependencyOrderProbeFeaturePlugin;
import top.sywyar.pixivdownload.runtimeprobe.DependencyOrderProbePlugin;
import top.sywyar.pixivdownload.runtimeprobe.IsolatedStaticProbeFeaturePlugin;
import top.sywyar.pixivdownload.runtimeprobe.IsolatedStaticProbePlugin;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageException;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageFixtures;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginArtifactVerificationService;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.isolation.IsolatedPluginSession;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.ArtifactVerificationRequest;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionResult;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginRuntimeVerificationSnapshot;

/**
 * PF4J 运行时管理封装的诊断边界测试：覆盖「插件目录不存在 / 空目录 / 含坏包」三类，
 * 证明坏包被隔离捕获、不致核心壳启动失败，且各状态可被后续流程据以判断。
 */
@DisplayName("PluginRuntimeManager 插件目录诊断与坏包隔离")
class PluginRuntimeManagerTest {

    private static final String PROBE_ID = "bootstrap-probe";

    @Test
    @DisplayName("撤销策略在 PF4J 加载前拒绝已验签的目录制品")
    void admissionPolicyRejectsVerifiedCatalogArtifactBeforePf4jLoad() throws IOException {
        Path plugins = tempDir.resolve("admission-rejected");
        Path artifact = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeDependencyOrderProbeJarWithMarker(artifact, PROBE_ID, "revoked-bytes");
        writeCatalogProvenance(plugins, artifact, PROBE_ID, PROBE_VERSION);
        PluginSupplyChainVerifier verifier = mock(PluginSupplyChainVerifier.class);
        when(verifier.verifyArtifact(any())).thenAnswer(invocation -> {
            ArtifactVerificationRequest request = invocation.getArgument(0);
            return new VerificationResult(VerificationStatus.VERIFIED, request.pluginId(), request.version(),
                    "test-key", SignatureMetadata.ED25519, "Test Publisher", "Test Trust", Instant.now(),
                    Files.size(request.artifactPath()), PluginPackageIntegrity.sha256Hex(request.artifactPath()),
                    "VERIFIED");
        });
        var manager = new top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager(plugins, ignored -> verifier);
        manager.updateAdmissionPolicy(ignored -> PluginArtifactAdmissionResult.reject(
                "PLUGIN_REVOKED", "verified revocation match"));

        PluginRuntimeStatus status = manager.start();

        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.failures()).singleElement().satisfies(failure ->
                assertThat(failure.reason()).contains("PLUGIN_REVOKED"));
        manager.shutdown();
    }
    private static final String PROBE_VERSION = "1.0.0";

    @TempDir
    Path tempDir;

    @AfterEach
    void clearProbeMarker() {
        System.clearProperty("bootstrap.probe.marker");
        List.of(
                IsolatedPluginSession.INITIALIZE_TIMEOUT_PROPERTY,
                IsolatedPluginSession.COMMAND_TIMEOUT_PROPERTY,
                IsolatedPluginSession.SHUTDOWN_TIMEOUT_PROPERTY,
                IsolatedPluginSession.RESTART_ATTEMPTS_PROPERTY,
                IsolatedPluginSession.RESTART_INITIAL_DELAY_PROPERTY,
                IsolatedPluginSession.RESTART_MAX_DELAY_PROPERTY,
                IsolatedPluginSession.STDERR_MAX_BYTES_PROPERTY
        ).forEach(System::clearProperty);
    }

    @Test
    @DisplayName("显式声明式生产包只在独立 worker 中实例化并以进程退出完成清退")
    void runsDeclarativePackageInWorkerAndTerminatesItOnStop() throws IOException {
        Path plugins = tempDir.resolve("plugins-isolated-default");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeDeclarativeProbeJar(jar);
        writeLocalProvenance(plugins, jar);
        top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager manager =
                new top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager(plugins, () -> true);
        Path hostMarker = tempDir.resolve("isolated-host-marker.log");
        System.setProperty("bootstrap.probe.marker", hostMarker.toString());
        try {
            LoadedPluginPackage loaded = manager.loadPlugin(jar);
            assertThat(loaded.phase()).isEqualTo(PluginRuntimePackagePhase.LOADED);
            assertThat(manager.pluginManagerForTest()).isEmpty();
            assertThat(manager.isolatedWorkerAliveForTest(PROBE_ID)).isFalse();
            assertThat(hostMarker).doesNotExist();

            LoadedPluginPackage initialized = manager.initializePlugin(PROBE_ID);
            assertThat(initialized.inventory().installations()).hasSize(1);
            assertThat(manager.isolatedWorkerAliveForTest(PROBE_ID)).isTrue();
            assertThat(manager.isolatedWorkerPidForTest(PROBE_ID))
                    .isPositive()
                    .isNotEqualTo(ProcessHandle.current().pid());
            assertThat(manager.pluginManagerForTest()).isEmpty();
            assertThat(hostMarker).doesNotExist();

            manager.startPlugin(PROBE_ID);
            assertThat(manager.packagePhases().get(PROBE_ID))
                    .isEqualTo(PluginRuntimePackagePhase.STARTED);
            manager.stopPlugin(PROBE_ID);
            assertThat(manager.isolatedWorkerAliveForTest(PROBE_ID)).isFalse();
            assertThat(manager.packagePhases().get(PROBE_ID))
                    .isEqualTo(PluginRuntimePackagePhase.STOPPED);

            manager.unloadPlugin(PROBE_ID);
            assertThat(manager.generation(PROBE_ID)).isEmpty();
            assertThat(manager.isPhysicalRuntimeInitialized()).isFalse();
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("声明式 worker 崩溃后报告 CRASHED 并按有界退避恢复同一 generation")
    void reportsAndRecoversCrashedDeclarativeWorker() throws Exception {
        Path plugins = tempDir.resolve("plugins-isolated-recovery");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeDeclarativeProbeJar(jar);
        writeLocalProvenance(plugins, jar);
        System.setProperty(IsolatedPluginSession.RESTART_ATTEMPTS_PROPERTY, "2");
        System.setProperty(IsolatedPluginSession.RESTART_INITIAL_DELAY_PROPERTY, "250");
        System.setProperty(IsolatedPluginSession.RESTART_MAX_DELAY_PROPERTY, "500");
        top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager manager =
                new top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager(plugins, () -> true);
        LinkedBlockingQueue<top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager.WorkerEvent> events =
                new LinkedBlockingQueue<>();
        manager.addWorkerListener(events::add);
        try {
            manager.loadPlugin(jar);
            manager.startPlugin(PROBE_ID);
            long firstPid = manager.isolatedWorkerPidForTest(PROBE_ID);

            ProcessHandle.of(firstPid).orElseThrow().destroyForcibly();

            top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager.WorkerEvent crashed =
                    events.poll(10, TimeUnit.SECONDS);
            assertThat(crashed).isNotNull();
            assertThat(crashed.type()).isEqualTo(
                    top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager.WorkerEventType.CRASHED);
            assertThat(crashed.pluginId()).isEqualTo(PROBE_ID);
            assertThat(crashed.generation()).isEqualTo(manager.generation(PROBE_ID).orElseThrow());
            assertThat(crashed.crashCount()).isEqualTo(1);
            assertThat(manager.packagePhases().get(PROBE_ID))
                    .isEqualTo(PluginRuntimePackagePhase.CRASHED);
            assertThat(manager.status().orElseThrow().failures()).last().satisfies(failure -> {
                assertThat(failure.status())
                        .isEqualTo(top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.CRASHED);
                assertThat(failure.phase()).isEqualTo("worker-exit");
                assertThat(failure.generation()).isEqualTo(crashed.generation());
                assertThat(failure.version()).isEqualTo(PROBE_VERSION);
                assertThat(failure.occurrenceCount()).isEqualTo(1);
            });

            top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager.WorkerEvent recovered =
                    events.poll(10, TimeUnit.SECONDS);
            assertThat(recovered).isNotNull();
            assertThat(recovered.type()).isEqualTo(
                    top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager.WorkerEventType.RECOVERED);
            assertThat(recovered.restartAttempt()).isEqualTo(1);
            assertThat(manager.packagePhases().get(PROBE_ID))
                    .isEqualTo(PluginRuntimePackagePhase.STARTED);
            assertThat(manager.isolatedWorkerPidForTest(PROBE_ID))
                    .isPositive()
                    .isNotEqualTo(firstPid);
            assertThat(manager.status().orElseThrow().failures()).isNotEmpty();
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("隔离 worker 只把有界静态纯值和插件自有资源代理回宿主")
    void projectsOnlyBoundedStaticContributionsFromIsolatedWorker() throws IOException {
        Path plugins = tempDir.resolve("plugins-isolated-static");
        Path jar = plugins.resolve("isolated-static-probe-1.0.0.jar");
        writeIsolatedStaticProbeJar(jar);
        writeLocalProvenance(plugins, jar, "isolated-static-probe", PROBE_VERSION);
        top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager manager =
                new top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager(plugins, () -> true);
        try {
            manager.loadPlugin(jar);
            PluginInstallation installation = manager.initializePlugin("isolated-static-probe")
                    .inventory().installations().get(0);

            assertThat(installation.plugin().routes())
                    .extracting(route -> route.pathPattern())
                    .containsExactly("/isolated-static/**");
            assertThat(installation.plugin().staticResources())
                    .extracting(resource -> resource.publicPathPrefix())
                    .containsExactly("/isolated-static/");
            assertThat(installation.plugin().i18n())
                    .extracting(contribution -> contribution.namespace())
                    .containsExactly("isolated-static");
            assertThat(installation.plugin().navigation())
                    .extracting(item -> item.href())
                    .containsExactly("/isolated-static/index.html");
            assertThat(installation.classLoader().getResource("static/isolated-static/index.html"))
                    .isNotNull();
            assertThat(installation.classLoader().getResource(
                    "top/sywyar/pixivdownload/plugin/runtime/PluginRuntimeManager.class"))
                    .isNull();

            manager.startPlugin("isolated-static-probe");
            installation.plugin().start();
            installation.plugin().stop();
            long workerPid = manager.isolatedWorkerPidForTest("isolated-static-probe");

            manager.shutdown();

            assertThat(manager.isolatedWorkerAliveForTest("isolated-static-probe")).isFalse();
            assertThat(ProcessHandle.of(workerPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            try (var workspaces = Files.list(plugins.resolve("runtime"))) {
                assertThat(workspaces).isEmpty();
            }
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("显式声明式开发目录降级为宿主完全信任并在状态中如实显示")
    void runsDeclarativeDevelopmentDirectoryAsHostFullTrust() throws IOException {
        Path repositoryRoot = tempDir.resolve("repo-isolated-development");
        Path pluginsRoot = repositoryRoot.resolve("plugins");
        Files.createDirectories(pluginsRoot);
        Path moduleRoot = repositoryRoot.resolve("pixivdownload-plugin-bootstrap-probe");
        writeDeclarativeProbeSourceDescriptor(moduleRoot);
        Path classesDirectory = moduleRoot.resolve("target/classes");
        writeDeclarativeProbeClassesDirectory(classesDirectory);
        PluginRuntimeManager manager = new PluginRuntimeManager(pluginsRoot);
        String previousEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        Path marker = tempDir.resolve("isolated-development-marker.log");
        System.setProperty("bootstrap.probe.marker", marker.toString());
        try {
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.setProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, repositoryRoot.toString());

            manager.loadPlugin(classesDirectory);
            assertThat(manager.loadedDescriptor(PROBE_ID)).hasValueSatisfying(descriptor ->
                    assertThat(descriptor.executionMode())
                            .isEqualTo(PluginExecutionMode.HOST_PROCESS_FULL_TRUST));
            assertThat(marker).doesNotExist();

            LoadedPluginPackage initialized = manager.initializePlugin(PROBE_ID);
            assertThat(initialized.inventory().installations()).singleElement()
                    .satisfies(installation -> assertThat(installation.descriptor().executionMode())
                            .isEqualTo(PluginExecutionMode.HOST_PROCESS_FULL_TRUST));
            assertThat(Files.readAllLines(marker, StandardCharsets.UTF_8)).containsExactly("load");
            manager.startPlugin(PROBE_ID);
            assertThat(manager.inspectPlugins().installations()).singleElement()
                    .satisfies(installation -> assertThat(installation.descriptor().executionMode())
                            .isEqualTo(PluginExecutionMode.HOST_PROCESS_FULL_TRUST));
        } finally {
            manager.shutdown();
            restoreProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
        }
    }

    @Test
    @DisplayName("显式宿主完全信任包保留生命周期策略并支持标准 PluginWrapper 构造器")
    void preservesManifestLifecyclePolicyAcrossRuntimeDiscovery() throws IOException {
        Path plugins = tempDir.resolve("plugins");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, true, "process-restart");
        writeLocalProvenance(plugins, jar);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        Path marker = tempDir.resolve("load-boundary.log");
        System.setProperty("bootstrap.probe.marker", marker.toString());

        LoadedPluginPackage loaded = manager.loadPlugin(jar);
        assertThat(manager.isDevelopmentArtifact(PROBE_ID)).isFalse();
        assertThat(loaded.inventory().installations()).isEmpty();
        assertThat(marker).doesNotExist();

        LoadedPluginPackage initialized = manager.initializePlugin(PROBE_ID);
        assertThat(Files.readAllLines(marker, StandardCharsets.UTF_8)).containsExactly("load");
        assertThat(initialized.inventory().installations()).singleElement()
                .satisfies(installation -> {
                    assertThat(installation.descriptor().lifecyclePolicy())
                            .isEqualTo(PluginLifecyclePolicy.PROCESS_RESTART);
                    assertThat(installation.descriptor().executionMode())
                            .isEqualTo(PluginExecutionMode.HOST_PROCESS_FULL_TRUST);
                });
        assertThat(manager.loadedDescriptor(PROBE_ID)).hasValueSatisfying(descriptor ->
                assertThat(descriptor.lifecyclePolicy()).isEqualTo(PluginLifecyclePolicy.PROCESS_RESTART));

        LoadedPluginPackage started = manager.startPlugin(PROBE_ID);
        assertThat(started.inventory().installations()).singleElement()
                .satisfies(installation -> assertThat(installation.descriptor().lifecyclePolicy())
                        .isEqualTo(PluginLifecyclePolicy.PROCESS_RESTART));
        assertThat(manager.inspectPlugins().installations()).singleElement()
                .satisfies(installation -> assertThat(installation.descriptor().lifecyclePolicy())
                        .isEqualTo(PluginLifecyclePolicy.PROCESS_RESTART));
        manager.shutdown();
    }

    @Test
    @DisplayName("同代 stop/start 复用声明快照而物理 reload 为新代各读取一次")
    void capturesProviderDeclarationsOncePerGeneration() throws Exception {
        Path plugins = tempDir.resolve("plugins-provider-snapshot");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, true);
        writeLocalProvenance(plugins, jar);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);

        manager.loadPlugin(jar);
        manager.startPlugin(PROBE_ID);
        Path firstPf4jPath = manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                .getPluginPath().toAbsolutePath().normalize();
        Path firstWorkspace = firstPf4jPath.getParent();
        manager.inspectPlugins();
        manager.inspectContextModules();
        manager.discoverFeaturePlugins();

        Object firstProvider = manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID).getPlugin();
        long firstGeneration = manager.generation(PROBE_ID).orElseThrow();
        assertThat(invokeInt(firstProvider, "featurePluginCalls")).isEqualTo(1);
        assertThat(invokeInt(firstProvider, "configurationClassesCalls")).isZero();

        manager.stopPlugin(PROBE_ID);
        manager.startPlugin(PROBE_ID);
        assertThat(manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID).getPluginPath()
                .toAbsolutePath().normalize()).isEqualTo(firstPf4jPath);
        manager.inspectPlugins();
        assertThat(invokeInt(firstProvider, "featurePluginCalls")).isEqualTo(1);
        assertThat(invokeInt(firstProvider, "configurationClassesCalls")).isZero();

        manager.stopPlugin(PROBE_ID);
        manager.unloadPlugin(PROBE_ID);
        assertThat(firstWorkspace).doesNotExist();
        manager.loadPlugin(jar);
        manager.startPlugin(PROBE_ID);
        Path secondPf4jPath = manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                .getPluginPath().toAbsolutePath().normalize();
        Object secondProvider = manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID).getPlugin();
        assertThat(secondProvider).isNotSameAs(firstProvider);
        assertThat(secondPf4jPath).isNotEqualTo(firstPf4jPath);
        assertThat(manager.generation(PROBE_ID).orElseThrow()).isGreaterThan(firstGeneration);
        assertThat(invokeInt(secondProvider, "featurePluginCalls")).isEqualTo(1);
        assertThat(invokeInt(secondProvider, "configurationClassesCalls")).isZero();
        manager.shutdown();
    }

    @Test
    @DisplayName("生产模式拒绝带开发态来源证明的未签名插件")
    void productionModeRejectsDevelopmentOnlyProvenance() throws IOException {
        Path plugins = tempDir.resolve("production-plugins");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, false);
        writeLocalProvenance(plugins, jar);
        top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager manager =
                new top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager(plugins);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.reason())
                        .contains("development-only plugin requires active development mode"));
        manager.shutdown();
    }

    @Test
    @DisplayName("PF4J start 已变更 wrapper 后抛 Error 时本地阶段复核为 STARTED")
    void startErrorAfterPf4jMutationReconcilesEntry() throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, true);
        writeLocalProvenance(plugins, jar);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        manager.loadPlugin(jar);
        PluginManager delegate = manager.pluginManagerForTest().orElseThrow();
        PluginManager faulting = spy(delegate);
        doAnswer(invocation -> {
            delegate.startPlugin(PROBE_ID);
            throw new AssertionError("start failed after state mutation");
        }).when(faulting).startPlugin(PROBE_ID);
        replacePluginManager(manager, faulting);

        assertThatThrownBy(() -> manager.startPlugin(PROBE_ID))
                .isInstanceOf(top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException.class)
                .hasCauseInstanceOf(AssertionError.class);

        assertThat(manager.packagePhases().get(PROBE_ID))
                .isEqualTo(top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase.STARTED);
        manager.shutdown();
    }

    @Test
    @DisplayName("PF4J unload 已移除 wrapper 后抛 Error 时同步删除本地 entry")
    void unloadErrorAfterPf4jMutationRemovesEntry() throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, true);
        writeLocalProvenance(plugins, jar);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        manager.loadPlugin(jar);
        PluginManager delegate = manager.pluginManagerForTest().orElseThrow();
        Path retainedWorkspace = delegate.getPlugin(PROBE_ID).getPluginPath()
                .toAbsolutePath().normalize().getParent();
        PluginManager faulting = spy(delegate);
        doAnswer(invocation -> {
            delegate.unloadPlugin(PROBE_ID);
            throw new AssertionError("unload failed after wrapper removal");
        }).when(faulting).unloadPlugin(PROBE_ID);
        replacePluginManager(manager, faulting);

        assertThatThrownBy(() -> manager.unloadPlugin(PROBE_ID))
                .isInstanceOf(top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException.class)
                .hasCauseInstanceOf(AssertionError.class);

        assertThat(manager.packagePhases()).doesNotContainKey(PROBE_ID);
        assertThat(manager.generation(PROBE_ID)).isEmpty();
        manager.shutdown();
        assertThat(retainedWorkspace).exists();
        PluginArtifactSnapshot.cleanupAbandonedWorkspaces(new PluginRuntimeLayout(plugins));
    }

    @Test
    @DisplayName("load 抛错且 cleanup 返回 false 时保留残余 wrapper 的可观测 entry")
    void loadFailureWithIncompleteCleanupRetainsResidualEntry() throws Exception {
        Path plugins = tempDir.resolve("plugins");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, true);
        writeLocalProvenance(plugins, jar);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        PluginManager faulting = mock(PluginManager.class);
        PluginWrapper wrapper = mock(PluginWrapper.class);
        AtomicReference<Path> attemptedLoadPath = new AtomicReference<>();
        org.pf4j.PluginDescriptor pf4jDescriptor = mock(org.pf4j.PluginDescriptor.class);
        when(faulting.getPlugins()).thenReturn(List.of(), List.of(wrapper));
        when(wrapper.getPluginId()).thenReturn(PROBE_ID);
        when(wrapper.getPluginState()).thenReturn(PluginState.CREATED);
        when(wrapper.getDescriptor()).thenReturn(pf4jDescriptor);
        when(pf4jDescriptor.getVersion()).thenReturn(PROBE_VERSION);
        doAnswer(invocation -> {
            attemptedLoadPath.set(invocation.getArgument(0));
            throw new AssertionError("load failed after wrapper creation");
        }).when(faulting).loadPlugin(any(Path.class));
        when(faulting.unloadPlugin(PROBE_ID)).thenReturn(false);
        when(faulting.getPlugin(PROBE_ID)).thenReturn(wrapper);
        replacePluginManager(manager, faulting);

        assertThatThrownBy(() -> manager.loadPlugin(jar))
                .isInstanceOf(top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException.class)
                .hasCauseInstanceOf(AssertionError.class)
                .satisfies(failure -> assertThat(failure.getCause().getSuppressed())
                        .anyMatch(suppressed -> suppressed.getMessage().contains("retained wrapper")));

        assertThat(manager.packagePhases().get(PROBE_ID))
                .isEqualTo(top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase.LOADED);
        Path retainedWorkspace = attemptedLoadPath.get().toAbsolutePath().normalize().getParent();
        assertThat(retainedWorkspace).exists();
        manager.shutdown();
        assertThat(retainedWorkspace).exists();
        PluginArtifactSnapshot.cleanupAbandonedWorkspaces(new PluginRuntimeLayout(plugins));
    }

    @Test
    @DisplayName("load 抛错且未暴露新增 wrapper 时保留无法确认已释放的 snapshot")
    void loadFailureWithoutObservableWrapperRetainsUnconfirmedSnapshot() throws Exception {
        Path plugins = tempDir.resolve("plugins-hidden-loader-failure");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, true);
        writeLocalProvenance(plugins, jar);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        PluginManager faulting = mock(PluginManager.class);
        AtomicReference<Path> attemptedLoadPath = new AtomicReference<>();
        when(faulting.getPlugins()).thenReturn(List.of(), List.of(), List.of());
        doAnswer(invocation -> {
            attemptedLoadPath.set(invocation.getArgument(0));
            throw new AssertionError("load failed after an unregistered classloader may have been created");
        }).when(faulting).loadPlugin(any(Path.class));
        replacePluginManager(manager, faulting);

        assertThatThrownBy(() -> manager.loadPlugin(jar))
                .isInstanceOf(top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException.class)
                .hasCauseInstanceOf(AssertionError.class);

        Path retainedWorkspace = attemptedLoadPath.get().toAbsolutePath().normalize().getParent();
        assertThat(manager.packagePhases()).isEmpty();
        assertThat(retainedWorkspace).exists();
        manager.shutdown();
        assertThat(retainedWorkspace).exists();
        PluginArtifactSnapshot.cleanupAbandonedWorkspaces(new PluginRuntimeLayout(plugins));
    }

    @Test
    @DisplayName("同一次失败 load 留下两个 wrapper 时最后一个卸载前不释放共享 snapshot")
    void multipleResidualWrappersReleaseSharedSnapshotOnlyAfterLastUnload() throws Exception {
        Path plugins = tempDir.resolve("plugins-multiple-residuals");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, true);
        writeLocalProvenance(plugins, jar);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        PluginManager faulting = mock(PluginManager.class);
        PluginWrapper firstWrapper = mock(PluginWrapper.class);
        PluginWrapper secondWrapper = mock(PluginWrapper.class);
        org.pf4j.PluginDescriptor firstDescriptor = mock(org.pf4j.PluginDescriptor.class);
        org.pf4j.PluginDescriptor secondDescriptor = mock(org.pf4j.PluginDescriptor.class);
        AtomicReference<Path> attemptedLoadPath = new AtomicReference<>();
        when(firstWrapper.getPluginId()).thenReturn("residual-one");
        when(secondWrapper.getPluginId()).thenReturn("residual-two");
        when(firstWrapper.getPluginState()).thenReturn(PluginState.CREATED);
        when(secondWrapper.getPluginState()).thenReturn(PluginState.CREATED);
        when(firstWrapper.getDescriptor()).thenReturn(firstDescriptor);
        when(secondWrapper.getDescriptor()).thenReturn(secondDescriptor);
        when(firstDescriptor.getVersion()).thenReturn(PROBE_VERSION);
        when(secondDescriptor.getVersion()).thenReturn(PROBE_VERSION);
        when(faulting.getPlugins()).thenReturn(
                List.of(), List.of(firstWrapper, secondWrapper), List.of());
        doAnswer(invocation -> {
            attemptedLoadPath.set(invocation.getArgument(0));
            throw new AssertionError("load failed after two wrappers were created");
        }).when(faulting).loadPlugin(any(Path.class));
        when(faulting.unloadPlugin("residual-one")).thenReturn(false, true);
        when(faulting.unloadPlugin("residual-two")).thenReturn(false, true);
        when(faulting.getPlugin("residual-one")).thenReturn(firstWrapper).thenReturn(null);
        when(faulting.getPlugin("residual-two")).thenReturn(secondWrapper).thenReturn(null);
        replacePluginManager(manager, faulting);

        assertThatThrownBy(() -> manager.loadPlugin(jar))
                .isInstanceOf(top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException.class)
                .hasCauseInstanceOf(AssertionError.class);

        Path retainedWorkspace = attemptedLoadPath.get().toAbsolutePath().normalize().getParent();
        assertThat(manager.packagePhases()).containsOnlyKeys("residual-one", "residual-two");
        assertThat(retainedWorkspace).exists();

        manager.unloadPlugin("residual-one");
        assertThat(retainedWorkspace).exists();

        manager.unloadPlugin("residual-two");
        assertThat(retainedWorkspace).doesNotExist();
        manager.shutdown();
    }

    @Test
    @DisplayName("失败 load 后无法枚举 wrapper 时同一 manager 后续启动不得清理未知 snapshot")
    void unknownWrapperInspectionKeepsAbandonedWorkspaceCleanupUnsafe() throws Exception {
        Path plugins = tempDir.resolve("plugins-unknown-wrapper-inspection");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, true);
        writeLocalProvenance(plugins, jar);
        PluginProvenanceStore provenanceStore = new PluginProvenanceStore(plugins);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        PluginManager faulting = mock(PluginManager.class);
        AtomicReference<Path> attemptedLoadPath = new AtomicReference<>();
        when(faulting.getPlugins()).thenReturn(List.of())
                .thenThrow(new AssertionError("wrapper inspection unavailable"));
        doAnswer(invocation -> {
            attemptedLoadPath.set(invocation.getArgument(0));
            throw new AssertionError("load failed after unknown mutation");
        }).when(faulting).loadPlugin(any(Path.class));
        replacePluginManager(manager, faulting);

        assertThatThrownBy(() -> manager.loadPlugin(jar))
                .isInstanceOf(top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException.class)
                .hasCauseInstanceOf(AssertionError.class);
        Path retainedWorkspace = attemptedLoadPath.get().toAbsolutePath().normalize().getParent();
        assertThat(retainedWorkspace).exists();

        manager.shutdown();
        provenanceStore.delete(jar);
        Files.delete(jar);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.EMPTY);
        assertThat(retainedWorkspace).exists();
        PluginArtifactSnapshot.cleanupAbandonedWorkspaces(new PluginRuntimeLayout(plugins));
    }

    @Test
    @DisplayName("shutdown 的 stop 抛非 fatal Error 后仍继续 unload")
    void shutdownContinuesUnloadAfterNonFatalStopError() throws Exception {
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir.resolve("plugins"));
        PluginManager faulting = mock(PluginManager.class);
        doAnswer(invocation -> {
            throw new AssertionError("stop all failed");
        }).when(faulting).stopPlugins();
        replacePluginManager(manager, faulting);

        manager.shutdown();

        verify(faulting).unloadPlugins();
        assertThat(manager.pluginManagerForTest()).isEmpty();
    }

    @Test
    @DisplayName("插件目录不存在：报告 ABSENT、不加载任何插件、不创建目录、不构造 PF4J 实例")
    void absentDirectoryIsReportedAndNotCreated() {
        Path missing = tempDir.resolve("does-not-exist");
        PluginRuntimeManager manager = new PluginRuntimeManager(missing);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.ABSENT);
        assertThat(status.directoryPresent()).isFalse();
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.startedPluginIds()).isEmpty();
        assertThat(status.failures()).isEmpty();
        assertThat(status.directory()).isEqualTo(missing.toAbsolutePath().normalize());
        // 缺失目录的常态路径不创建目录、不触碰 PF4J
        assertThat(Files.exists(missing)).isFalse();
        assertThat(manager.pluginManagerForTest()).isEmpty();
        assertThat(manager.status()).contains(status);
    }

    @Test
    @DisplayName("插件路径存在但不是目录：报告 ABSENT、不致命")
    void nonDirectoryPathIsReportedAbsent() throws IOException {
        Path file = tempDir.resolve("plugins-as-file");
        Files.writeString(file, "not a directory", StandardCharsets.UTF_8);
        PluginRuntimeManager manager = new PluginRuntimeManager(file);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.ABSENT);
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.failures()).isEmpty();
        assertThat(manager.pluginManagerForTest()).isEmpty();
    }

    @Test
    @DisplayName("空插件目录：报告 EMPTY、可被后续流程判定为需补齐")
    void emptyDirectoryIsReportedEmpty() {
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.EMPTY);
        assertThat(status.directoryPresent()).isTrue();
        assertThat(status.empty()).isTrue();
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.failures()).isEmpty();
        // 空目录路径不构造 PF4J 实例
        assertThat(manager.pluginManagerForTest()).isEmpty();
    }

    @Test
    @DisplayName("目录只含非插件包文件（无 jar/zip）：按候选包口径判为 EMPTY")
    void directoryWithOnlyNonPackagesIsEmpty() throws IOException {
        Files.writeString(tempDir.resolve("README.txt"), "hello", StandardCharsets.UTF_8);
        Files.createDirectory(tempDir.resolve("some-subdir"));
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.EMPTY);
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.failures()).isEmpty();
    }

    @Test
    @DisplayName("含坏包：报告 POPULATED、坏包被隔离捕获成失败条目、不抛异常、不致核心壳启动失败")
    void brokenPackageIsIsolatedNotFatal() throws IOException {
        // 一个伪装成 .jar 的文本文件——PF4J 解析其描述符时必然失败
        Path broken = tempDir.resolve("broken-plugin.jar");
        Files.writeString(broken, "this is not a valid plugin jar", StandardCharsets.UTF_8);
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);

        // start() 必须正常返回（不向上抛出），坏包不能让核心壳启动失败
        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.startedPluginIds()).isEmpty();
        assertThat(status.hasFailures()).isTrue();
        assertThat(status.failures()).hasSize(1);
        assertThat(status.failures().get(0).source()).isEqualTo("broken-plugin.jar");
        assertThat(status.failures().get(0).reason()).isNotBlank();
        // 坏包在完整准入前被隔离，不应为它构造 PF4J 实例。
        assertThat(manager.pluginManagerForTest()).isEmpty();
    }

    @Test
    @DisplayName("开发模式：忽略 plugins 目录内容，并从模块 target/classes 加载与物理重载")
    void developmentModeLoadsCompiledModuleClassesAndIgnoresPluginsDirectory() throws IOException {
        Path repositoryRoot = tempDir.resolve("repo");
        Path pluginsRoot = repositoryRoot.resolve("plugins");
        Files.createDirectories(pluginsRoot);
        Files.writeString(pluginsRoot.resolve("broken-plugin.jar"),
                "this should be ignored in development mode", StandardCharsets.UTF_8);
        Path moduleRoot = repositoryRoot.resolve("pixivdownload-plugin-bootstrap-probe");
        writeProbeSourceDescriptor(moduleRoot);
        Path classesDirectory = moduleRoot.resolve("target/classes");
        writeProbeClassesDirectory(classesDirectory, true);
        PluginRuntimeManager manager = new PluginRuntimeManager(pluginsRoot);
        String previousEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        Path developmentSessionRoot = null;
        try {
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.clearProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);

            PluginRuntimeStatus status = manager.start();

            Path pf4jPath = manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                    .getPluginPath().toAbsolutePath().normalize();
            ClassLoader firstClassLoader = manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                    .getPluginClassLoader();
            assertThat(status.state()).isEqualTo(PluginDirectoryState.POPULATED);
            assertThat(status.directory()).isEqualTo(repositoryRoot.toAbsolutePath().normalize());
            assertThat(status.loadedPluginIds()).containsExactly(PROBE_ID);
            assertThat(status.startedPluginIds()).containsExactly(PROBE_ID);
            assertThat(status.failures()).isEmpty();
            assertThat(manager.artifactPath(PROBE_ID)).contains(classesDirectory.toAbsolutePath().normalize());
            assertThat(manager.isDevelopmentArtifact(PROBE_ID)).isTrue();
            assertThat(pf4jPath).startsWith(repositoryRoot.resolve("target/pixivdownload-plugin-dev-runtime")
                    .toAbsolutePath().normalize());
            developmentSessionRoot = pf4jPath.getParent();
            assertThat(developmentSessionRoot.getFileName().toString()).startsWith(".session-");
            assertThat(pf4jPath.getFileName().toString()).startsWith(PROBE_ID + "-" + PROBE_VERSION + "-");
            assertThat(pf4jPath.resolve("classes/top/sywyar/pixivdownload/runtimeprobe/"
                    + "BootstrapProbePlugin.class")).exists();
            assertThat(pf4jPath.resolve("lib/private-lib.jar")).exists();
            assertThat(manager.loadedDescriptor(PROBE_ID)).get()
                    .extracting(PluginDescriptor::id).isEqualTo(PROBE_ID);
            Path activeCacheMarker = pf4jPath.resolve("active-generation.marker");
            Files.writeString(activeCacheMarker, "active", StandardCharsets.UTF_8);
            assertThatThrownBy(() -> manager.loadPlugin(classesDirectory))
                    .hasMessageContaining("plugin package already loaded");
            assertThat(activeCacheMarker).exists();
            Path outsideDevelopmentRoot = tempDir.resolve("outside-development-root/classes");
            Files.createDirectories(outsideDevelopmentRoot);
            assertThatThrownBy(() -> manager.loadPlugin(outsideDevelopmentRoot))
                    .hasMessageContaining("development plugin artifact not found");
            manager.stopPlugin(PROBE_ID);
            assertThat(manager.loadedDescriptors()).containsKey(PROBE_ID);
            manager.startPlugin(PROBE_ID);
            long firstGeneration = manager.generation(PROBE_ID).orElseThrow();

            manager.unloadPlugin(PROBE_ID);
            assertThat(manager.isDevelopmentArtifact(PROBE_ID)).isFalse();
            LoadedPluginPackage reloaded = manager.loadPlugin(classesDirectory);
            assertThat(manager.isDevelopmentArtifact(PROBE_ID)).isTrue();
            manager.startPlugin(PROBE_ID);
            ClassLoader reloadedClassLoader = manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                    .getPluginClassLoader();
            Path reloadedPf4jPath = manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                    .getPluginPath().toAbsolutePath().normalize();

            assertThat(reloaded.artifactPath()).isEqualTo(classesDirectory.toAbsolutePath().normalize());
            assertThat(reloaded.generation()).isGreaterThan(firstGeneration);
            assertThat(reloadedClassLoader).isNotSameAs(firstClassLoader);
            assertThat(reloadedPf4jPath).isNotEqualTo(pf4jPath);
            assertThat(reloadedPf4jPath.getParent()).isEqualTo(developmentSessionRoot);
            assertThat(activeCacheMarker).exists();
            assertThat(manager.packagePhases().get(PROBE_ID))
                    .isEqualTo(PluginRuntimePackagePhase.STARTED);
        } finally {
            manager.shutdown();
            restoreProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
        }
        assertThat(developmentSessionRoot).isNotNull().doesNotExist();
    }

    @Test
    @DisplayName("开发模式：同一仓库的两个运行时使用独立会话并只清理自身缓存")
    void developmentModeManagersUseIsolatedCacheSessions() throws IOException {
        Path repositoryRoot = tempDir.resolve("repo-isolated-sessions");
        Path pluginsRoot = repositoryRoot.resolve("plugins");
        Files.createDirectories(pluginsRoot);
        Path moduleRoot = repositoryRoot.resolve("pixivdownload-plugin-bootstrap-probe");
        writeProbeSourceDescriptor(moduleRoot);
        writeProbeClassesDirectory(moduleRoot.resolve("target/classes"), false);
        PluginRuntimeManager firstManager = new PluginRuntimeManager(pluginsRoot);
        PluginRuntimeManager secondManager = new PluginRuntimeManager(pluginsRoot);
        String previousEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        Path firstSessionRoot = null;
        Path secondSessionRoot = null;
        try {
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.clearProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);

            assertThat(firstManager.start().failures()).isEmpty();
            assertThat(secondManager.start().failures()).isEmpty();
            Path firstPf4jPath = firstManager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                    .getPluginPath().toAbsolutePath().normalize();
            Path secondPf4jPath = secondManager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                    .getPluginPath().toAbsolutePath().normalize();
            firstSessionRoot = firstPf4jPath.getParent();
            secondSessionRoot = secondPf4jPath.getParent();

            assertThat(firstSessionRoot).isNotEqualTo(secondSessionRoot);
            assertThat(firstPf4jPath).isNotEqualTo(secondPf4jPath);
            assertThat(firstPf4jPath).isDirectory();
            assertThat(secondPf4jPath).isDirectory();

            firstManager.shutdown();

            assertThat(firstSessionRoot).doesNotExist();
            assertThat(secondSessionRoot).isDirectory();
            assertThat(secondPf4jPath).isDirectory();
            assertThat(secondManager.packagePhases().get(PROBE_ID))
                    .isEqualTo(PluginRuntimePackagePhase.STARTED);
        } finally {
            firstManager.shutdown();
            secondManager.shutdown();
            restoreProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
        }
        assertThat(firstSessionRoot).isNotNull().doesNotExist();
        assertThat(secondSessionRoot).isNotNull().doesNotExist();
    }

    @Test
    @DisplayName("开发模式：PF4J 未完全卸载时保留会话缓存")
    void developmentModeRetainsCacheSessionWhenPf4jDoesNotReleaseCleanly() throws Exception {
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir.resolve("plugins-retained-session"));
        PluginDevelopmentArtifacts.DevelopmentCacheSession session =
                PluginDevelopmentArtifacts.openSession(tempDir.resolve("development-cache"));
        Path activeSnapshot = session.sessionRoot().resolve("active-snapshot");
        Path activeMarker = activeSnapshot.resolve("active.marker");
        Files.createDirectories(activeSnapshot);
        Files.writeString(activeMarker, "active", StandardCharsets.UTF_8);
        PluginManager faulting = mock(PluginManager.class);
        PluginWrapper retainedWrapper = mock(PluginWrapper.class);
        doAnswer(invocation -> {
            throw new AssertionError("unload all failed");
        }).when(faulting).unloadPlugins();
        when(faulting.getPlugins()).thenReturn(List.of(retainedWrapper));
        replacePluginManager(manager, faulting);
        replaceDevelopmentCacheSession(manager, session);
        try {
            manager.shutdown();

            verify(faulting).stopPlugins();
            verify(faulting).unloadPlugins();
            assertThat(session.sessionRoot()).isDirectory();
            assertThat(activeMarker).hasContent("active");
        } finally {
            session.close();
        }
        assertThat(session.sessionRoot()).doesNotExist();
    }

    @Test
    @DisplayName("开发模式：默认开发根从模块工作目录回溯到仓库根")
    void developmentModeFindsRepositoryRootFromModuleWorkingDirectory() throws IOException {
        Path repositoryRoot = tempDir.resolve("repo-from-app");
        Files.createDirectories(repositoryRoot);
        Files.writeString(repositoryRoot.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Path pluginsRoot = repositoryRoot.resolve("pixivdownload-app/plugins");
        Files.createDirectories(pluginsRoot);
        Path moduleRoot = repositoryRoot.resolve("pixivdownload-plugin-bootstrap-probe");
        writeProbeSourceDescriptor(moduleRoot);
        Path classesDirectory = moduleRoot.resolve("target/classes");
        writeProbeClassesDirectory(classesDirectory, false);
        PluginRuntimeManager manager = new PluginRuntimeManager(pluginsRoot);
        String previousEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        try {
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.clearProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);

            PluginRuntimeStatus status = manager.start();

            assertThat(status.state()).isEqualTo(PluginDirectoryState.POPULATED);
            assertThat(status.directory()).isEqualTo(repositoryRoot.toAbsolutePath().normalize());
            assertThat(status.loadedPluginIds()).containsExactly(PROBE_ID);
            assertThat(status.startedPluginIds()).containsExactly(PROBE_ID);
            assertThat(status.failures()).isEmpty();
        } finally {
            manager.shutdown();
            restoreProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
        }
    }

    @Test
    @DisplayName("开发模式：红色输出提示忽略 plugins，并列出未编译的源码插件模块")
    void developmentModePrintsRedBannerAndReportsSourceOnlyModules() throws IOException {
        Path repositoryRoot = tempDir.resolve("repo-source-only");
        Path pluginsRoot = repositoryRoot.resolve("plugins");
        Files.createDirectories(pluginsRoot);
        writeProbeSourceDescriptor(repositoryRoot.resolve("pixivdownload-plugin-bootstrap-probe"));
        PluginRuntimeManager manager = new PluginRuntimeManager(pluginsRoot);
        String previousEnabled = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        String previousRoot = System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);
        PrintStream previousErr = System.err;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            System.clearProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY);

            PluginRuntimeStatus status = manager.start();

            assertThat(status.state()).isEqualTo(PluginDirectoryState.EMPTY);
            assertThat(status.failures()).hasSize(1);
            assertThat(status.failures().get(0).source()).isEqualTo(PROBE_ID);
            assertThat(status.failures().get(0).reason()).contains("target/classes/plugin.properties");
        } finally {
            System.setErr(previousErr);
            manager.shutdown();
            restoreProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY, previousRoot);
        }
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("\u001B[1;31m")
                .contains("PIXIVDOWNLOAD PLUGIN DEVELOPMENT MODE ENABLED")
                .contains("The plugins directory is ignored")
                .contains("Source plugin modules without target/classes output")
                .contains("pixivdownload-plugin-bootstrap-probe");
    }

    @Test
    @DisplayName("开发模式：按 plugin.dependencies 将依赖模块排在依赖方之前加载")
    void developmentModeOrdersMaterializedPluginsByDependencies() {
        PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin mail =
                materializedDevelopmentPlugin("mail", List.of(new PluginDependencyRef("notification", "1.0", false)));
        PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin notification =
                materializedDevelopmentPlugin("notification", List.of());

        List<String> orderedIds = PluginDevelopmentArtifacts.dependencyOrder(List.of(mail, notification)).stream()
                .map(plugin -> plugin.descriptor().id())
                .toList();

        assertThat(orderedIds).containsExactly("notification", "mail");
    }

    @Test
    @DisplayName("启动扫描：按 plugin.dependencies 拓扑排序，依赖的依赖先于依赖方加载")
    void startupOrdersRootArtifactsByTransitiveDependencies() throws IOException {
        Path plugins = tempDir.resolve("ordered-root-artifacts");
        Files.createDirectories(plugins);
        writeDependencyOrderProbeJar(plugins.resolve("mail-1.0.0.jar"), "mail",
                List.of(new PluginDependencyRef("notification", "1.0", false)));
        writeDependencyOrderProbeJar(plugins.resolve("notification-1.0.0.jar"), "notification",
                List.of(new PluginDependencyRef("base", "1.0", false)));
        writeDependencyOrderProbeJar(plugins.resolve("zz-base-1.0.0.jar"), "base", List.of());
        writeLocalProvenance(plugins, plugins.resolve("mail-1.0.0.jar"), "mail", PROBE_VERSION);
        writeLocalProvenance(plugins, plugins.resolve("notification-1.0.0.jar"), "notification", PROBE_VERSION);
        writeLocalProvenance(plugins, plugins.resolve("zz-base-1.0.0.jar"), "base", PROBE_VERSION);
        Path marker = tempDir.resolve("dependency-order-marker.txt");
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        String previousMarker = System.getProperty("dependency.order.probe.marker");
        try {
            System.setProperty("dependency.order.probe.marker", marker.toString());

            PluginRuntimeStatus status = manager.start();

            assertThat(status.state()).isEqualTo(PluginDirectoryState.POPULATED);
            assertThat(status.failures()).isEmpty();
            assertThat(status.loadedPluginIds()).containsExactly("base", "notification", "mail");
            assertThat(status.startedPluginIds()).containsExactly("base", "notification", "mail");
            assertThat(Files.readAllLines(marker, StandardCharsets.UTF_8))
                    .containsSubsequence("load:base", "load:notification", "load:mail")
                    .containsSubsequence("start:base", "start:notification", "start:mail");
        } finally {
            manager.shutdown();
            restoreProperty("dependency.order.probe.marker", previousMarker);
        }
    }

    @Test
    @DisplayName("启动扫描：缺少必需依赖时跳过依赖方，不把半加载包交给 PF4J")
    void startupSkipsPluginWithMissingRequiredDependencyBeforePf4jLoad() throws IOException {
        Path plugins = tempDir.resolve("missing-required-dependency");
        Files.createDirectories(plugins);
        Path mail = plugins.resolve("mail-1.0.0.jar");
        writeDependencyOrderProbeJar(mail, "mail",
                List.of(new PluginDependencyRef("notification", "1.0", false)));
        writeLocalProvenance(plugins, mail, "mail", PROBE_VERSION);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.startedPluginIds()).isEmpty();
        assertThat(status.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.source()).isEqualTo("mail-1.0.0.jar");
            assertThat(failure.reason()).contains("missing required dependency: notification");
        });
        assertThat(manager.pluginManagerForTest()).isEmpty();
        manager.shutdown();
    }

    @Test
    @DisplayName("重新扫描 POPULATED→EMPTY：清理陈旧 PF4J 实例，pluginManager() 与发现结果均为空")
    void rescanFromPopulatedToEmptyClearsStaleManager() throws IOException {
        Path artifact = tempDir.resolve("bootstrap-probe.jar");
        writeProbeJar(artifact, true);
        writeLocalProvenance(tempDir, artifact);
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);

        PluginRuntimeStatus first = manager.start();
        assertThat(first.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(manager.pluginManagerForTest()).isPresent();

        // 移除候选包后重新扫描：目录转为空
        Files.delete(artifact);
        PluginRuntimeStatus second = manager.start();

        assertThat(second.state()).isEqualTo(PluginDirectoryState.EMPTY);
        // 关键：不得读到上一轮的陈旧 PF4J 实例
        assertThat(manager.pluginManagerForTest()).isEmpty();
        assertThat(manager.discoverFeaturePlugins().discovered()).isEmpty();
        assertThat(manager.discoverFeaturePlugins().failures()).isEmpty();
        assertThat(manager.status()).contains(second);
    }

    @Test
    @DisplayName("重新扫描 POPULATED→ABSENT：清理陈旧 PF4J 实例，pluginManager() 与发现结果均为空")
    void rescanFromPopulatedToAbsentClearsStaleManager() throws IOException {
        Path pluginsRoot = tempDir.resolve("plugins");
        Files.createDirectory(pluginsRoot);
        Path artifact = pluginsRoot.resolve("bootstrap-probe.jar");
        writeProbeJar(artifact, true);
        PluginProvenanceStore provenanceStore = new PluginProvenanceStore(pluginsRoot);
        writeLocalProvenance(pluginsRoot, artifact);
        PluginRuntimeManager manager = new PluginRuntimeManager(pluginsRoot);

        PluginRuntimeStatus first = manager.start();
        assertThat(first.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(manager.pluginManagerForTest()).isPresent();

        // 删除整个插件目录后重新扫描：目录转为缺失
        manager.unloadPlugin(PROBE_ID);
        provenanceStore.delete(artifact);
        Files.delete(artifact);
        Files.delete(provenanceStore.provenanceDir());
        Files.delete(pluginsRoot.resolve("runtime"));
        Files.delete(pluginsRoot);
        PluginRuntimeStatus second = manager.start();

        assertThat(second.state()).isEqualTo(PluginDirectoryState.ABSENT);
        assertThat(manager.pluginManagerForTest()).isEmpty();
        assertThat(manager.discoverFeaturePlugins().discovered()).isEmpty();
    }

    @Test
    @DisplayName("未运行 start()（无 PF4J 实例）时发现结果为空")
    void discoverBeforeStartIsEmpty() {
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);
        assertThat(manager.discoverFeaturePlugins().discovered()).isEmpty();
        assertThat(manager.discoverFeaturePlugins().hasFailures()).isFalse();
    }

    @Test
    @DisplayName("未运行 start() 前 status() 为空，运行后缓存结果")
    void statusIsCachedAfterStart() {
        PluginRuntimeManager manager = new PluginRuntimeManager(tempDir);
        assertThat(manager.status()).isEmpty();

        PluginRuntimeStatus status = manager.start();

        assertThat(manager.status()).contains(status);
    }

    @Test
    @DisplayName("启动复验写回失败仍保留绑定当前字节的结构化 HASH_MISMATCH")
    void startupRetainsHashMismatchWhenProvenanceWriteBackFails() throws IOException {
        Path plugins = tempDir.resolve("startup-verification-write-failure");
        Path artifact = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeDependencyOrderProbeJarWithMarker(artifact, PROBE_ID, "installed-bytes");
        writeCatalogProvenance(plugins, artifact, PROBE_ID, PROBE_VERSION);
        writeDependencyOrderProbeJarWithMarker(artifact, PROBE_ID, "changed-current-bytes");
        long currentSize = Files.size(artifact);
        String currentSha256 = PluginPackageIntegrity.sha256Hex(artifact);
        PluginRuntimeManager manager = new FailingProvenanceWritePluginRuntimeManager(plugins);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.source()).isEqualTo(artifact.getFileName().toString());
            assertThat(failure.reason()).contains("HASH_MISMATCH");
        });
        assertThat(status.verifications()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.artifactPath()).isEqualTo(artifact.toAbsolutePath().normalize());
            assertThat(snapshot.pluginId()).isEqualTo(PROBE_ID);
            assertThat(snapshot.version()).isEqualTo(PROBE_VERSION);
            assertThat(snapshot.artifactSizeBytes()).isEqualTo(currentSize);
            assertThat(snapshot.artifactSha256()).isEqualTo(currentSha256);
            assertThat(snapshot.result().status()).isEqualTo(VerificationStatus.HASH_MISMATCH);
            assertThat(snapshot.binds(artifact, PROBE_ID, PROBE_VERSION, currentSize, currentSha256)).isTrue();
            assertThat(snapshot.binds(artifact.resolveSibling("replacement.jar"),
                    PROBE_ID, PROBE_VERSION, currentSize, currentSha256)).isFalse();
            assertThat(snapshot.binds(artifact, "replacement", PROBE_VERSION,
                    currentSize, currentSha256)).isFalse();
            assertThat(snapshot.binds(artifact, PROBE_ID, "2.0.0",
                    currentSize, currentSha256)).isFalse();
            assertThat(snapshot.binds(artifact, PROBE_ID, PROBE_VERSION,
                    currentSize, "f".repeat(64))).isFalse();
        });
        assertThat(new PluginProvenanceStore(plugins).read(artifact)).hasValueSatisfying(
                provenance -> assertThat(provenance.offlineStatus()).isNull());
        manager.shutdown();
    }

    @Test
    @DisplayName("运行期重新加载会用最新结构化复验替换同路径启动快照")
    void runtimeReloadRetainsLatestVerificationWhenProvenanceWriteBackFails() throws IOException {
        Path plugins = tempDir.resolve("runtime-verification-write-failure");
        Path artifact = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeDependencyOrderProbeJarWithMarker(artifact, PROBE_ID, "startup-bytes");
        writeLocalProvenance(plugins, artifact);
        String startupSha256 = PluginPackageIntegrity.sha256Hex(artifact);
        PluginRuntimeManager manager = new FailingProvenanceWritePluginRuntimeManager(plugins);

        PluginRuntimeStatus started = manager.start();
        assertThat(started.verifications()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.artifactSha256()).isEqualTo(startupSha256);
            assertThat(snapshot.result().status()).isEqualTo(VerificationStatus.UNSIGNED_ALLOWED);
        });
        manager.stopPlugin(PROBE_ID);
        manager.unloadPlugin(PROBE_ID);
        writeDependencyOrderProbeJarWithMarker(artifact, PROBE_ID, "changed-runtime-bytes");
        long changedSize = Files.size(artifact);
        String changedSha256 = PluginPackageIntegrity.sha256Hex(artifact);

        assertThatThrownBy(() -> manager.loadPlugin(artifact))
                .isInstanceOf(PluginRuntimeOperationException.class)
                .hasMessageContaining("HASH_MISMATCH");

        PluginRuntimeStatus afterReload = manager.status().orElseThrow();
        assertThat(afterReload.verifications()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.artifactPath()).isEqualTo(artifact.toAbsolutePath().normalize());
            assertThat(snapshot.artifactSha256()).isEqualTo(changedSha256);
            assertThat(snapshot.artifactSha256()).isNotEqualTo(startupSha256);
            assertThat(snapshot.result().status()).isEqualTo(VerificationStatus.HASH_MISMATCH);
            assertThat(snapshot.binds(
                    artifact, PROBE_ID, PROBE_VERSION, changedSize, changedSha256)).isTrue();
        });
        assertThat(new PluginProvenanceStore(plugins).read(artifact)).hasValueSatisfying(
                provenance -> assertThat(provenance.offlineStatus()).isNull());
        manager.shutdown();
    }

    @Test
    @DisplayName("运行期复验尚未产出结果就失败时会失效同路径旧快照")
    void runtimeReloadInvalidatesOldVerificationBeforeReadingProvenance() throws IOException {
        Path plugins = tempDir.resolve("runtime-invalid-provenance");
        Path artifact = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeDependencyOrderProbeJarWithMarker(artifact, PROBE_ID, "same-bytes");
        writeLocalProvenance(plugins, artifact);
        PluginProvenanceStore provenanceStore = new PluginProvenanceStore(plugins);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);

        PluginRuntimeStatus started = manager.start();
        assertThat(started.verifications()).singleElement();
        manager.stopPlugin(PROBE_ID);
        manager.unloadPlugin(PROBE_ID);
        Files.writeString(provenanceStore.sidecarPath(artifact), "broken=provenance\n",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> manager.loadPlugin(artifact))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plugin provenance is invalid");

        assertThat(manager.status().orElseThrow().verifications()).isEmpty();
        manager.shutdown();
    }

    @Test
    @DisplayName("JAR-with-lib：先验签原始 jar，再物化到 plugins/runtime，并保持 artifactPath 指向原始 jar")
    void jarWithPrivateLibrariesIsMaterializedButExposesOriginalArtifact() throws IOException {
        Path plugins = tempDir.resolve("plugins-with-lib");
        Files.createDirectories(plugins);
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, true);
        writeLocalProvenance(plugins, jar);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);

        LoadedPluginPackage loaded = manager.loadPlugin(jar);
        manager.startPlugin(PROBE_ID);

        Path pf4jPath = manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                .getPluginPath().toAbsolutePath().normalize();
        assertThat(loaded.artifactPath()).isEqualTo(jar.toAbsolutePath().normalize());
        assertThat(manager.artifactPath(PROBE_ID)).contains(jar.toAbsolutePath().normalize());
        assertThat(pf4jPath).startsWith(plugins.resolve(PluginRuntimeLayout.RUNTIME_DIR).toAbsolutePath().normalize());
        assertThat(pf4jPath).isNotEqualTo(jar.toAbsolutePath().normalize());
        assertThat(pf4jPath.resolve("plugin.properties")).exists();
        assertThat(pf4jPath.resolve("classes/top/sywyar/pixivdownload/runtimeprobe/BootstrapProbePlugin.class"))
                .exists();
        assertThat(pf4jPath.resolve("lib/private-lib.jar")).exists();
        assertThat(plugins.resolve(PROBE_ID + "-" + PROBE_VERSION)).doesNotExist();
        Path workspace = pf4jPath.getParent();
        assertThat(workspace.getFileName().toString()).startsWith(".artifact-snapshot-");
        manager.shutdown();
        assertThat(workspace).doesNotExist();
    }

    @Test
    @DisplayName("JAR-with-lib：相同字节的不同加载代际使用互不复用的私有 workspace")
    void identicalArtifactsUseIsolatedProductionWorkspaces() throws IOException {
        Path plugins = tempDir.resolve("plugins-reusable-cache");
        Path fullOffline = plugins.resolve("full-offline");
        Path portable = plugins.resolve("portable");
        Files.createDirectories(fullOffline);
        Files.createDirectories(portable);
        Path firstJar = fullOffline.resolve("pixivdownload-plugin-gui-swing-1.0.0.jar");
        Path secondJar = portable.resolve("pixivdownload-plugin-gui-swing.jar");
        writeProbeJar(firstJar, true);
        Files.write(secondJar, Files.readAllBytes(firstJar));
        writeLocalProvenance(plugins, firstJar);
        writeLocalProvenance(plugins, secondJar);
        assertThat(PluginPackageIntegrity.sha256Hex(secondJar))
                .isEqualTo(PluginPackageIntegrity.sha256Hex(firstJar));

        PluginRuntimeManager firstManager = new PluginRuntimeManager(plugins);
        LoadedPluginPackage firstLoaded = firstManager.loadPlugin(firstJar);
        firstManager.startPlugin(PROBE_ID);
        Path firstPf4jPath = firstManager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                .getPluginPath().toAbsolutePath().normalize();
        Path firstWorkspace = firstPf4jPath.getParent();
        assertThat(firstLoaded.artifactPath()).isEqualTo(firstJar.toAbsolutePath().normalize());
        assertThat(firstWorkspace).exists();
        firstManager.shutdown();
        assertThat(firstWorkspace).doesNotExist();

        PluginRuntimeManager secondManager = new PluginRuntimeManager(plugins);
        LoadedPluginPackage secondLoaded = secondManager.loadPlugin(secondJar);
        secondManager.startPlugin(PROBE_ID);
        Path secondPf4jPath = secondManager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                .getPluginPath().toAbsolutePath().normalize();
        Path secondWorkspace = secondPf4jPath.getParent();

        assertThat(secondLoaded.artifactPath()).isEqualTo(secondJar.toAbsolutePath().normalize());
        assertThat(secondManager.artifactPath(PROBE_ID)).contains(secondJar.toAbsolutePath().normalize());
        assertThat(secondPf4jPath).isNotEqualTo(firstPf4jPath);
        assertThat(secondWorkspace).exists();
        secondManager.shutdown();
        assertThat(secondWorkspace).doesNotExist();
    }

    @Test
    @DisplayName("ZIP 兼容：不让 PF4J 直接加载根 zip，而是物化到 plugins/runtime 目录后加载一次")
    void zipPackageIsMaterializedBeforePf4jLoad() throws IOException {
        Path plugins = tempDir.resolve("plugins-zip");
        Files.createDirectories(plugins);
        Path zip = plugins.resolve("bootstrap-probe-1.0.0.zip");
        writeProbeExplodedZip(zip);
        writeLocalProvenance(plugins, zip);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);

        LoadedPluginPackage loaded = manager.loadPlugin(zip);

        Path pf4jPath = manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                .getPluginPath().toAbsolutePath().normalize();
        assertThat(loaded.artifactPath()).isEqualTo(zip.toAbsolutePath().normalize());
        assertThat(pf4jPath).startsWith(plugins.resolve(PluginRuntimeLayout.RUNTIME_DIR).toAbsolutePath().normalize());
        assertThat(pf4jPath).isDirectory();
        assertThat(pf4jPath).isNotEqualTo(zip.toAbsolutePath().normalize());
        assertThat(plugins.resolve(PROBE_ID + "-" + PROBE_VERSION)).doesNotExist();
        Path workspace = pf4jPath.getParent();
        manager.shutdown();
        assertThat(workspace).doesNotExist();
    }

    @Test
    @DisplayName("直接加载在离线复验后替换原路径仍只从同一冻结 snapshot 进入 PF4J")
    void productionLoadContinuesFromFrozenSnapshotAfterVerification() throws Exception {
        Path plugins = tempDir.resolve("plugins-frozen-direct");
        Path jar = plugins.resolve("bootstrap-probe-1.0.0.jar");
        writeProbeJar(jar, true);
        writeLocalProvenance(plugins, jar);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        PluginArtifactVerificationService delegate = verificationService(manager);
        PluginArtifactVerificationService replacing = spy(delegate);
        AtomicReference<Path> verifiedPath = new AtomicReference<>();
        doAnswer(invocation -> {
            VerificationResult result = (VerificationResult) invocation.callRealMethod();
            verifiedPath.set(invocation.getArgument(0));
            Files.writeString(jar, "replaced-after-verification", StandardCharsets.UTF_8);
            return result;
        }).when(replacing).verifyInstalled(any(Path.class), any(PluginDescriptor.class), any());
        replaceVerificationService(manager, replacing);

        LoadedPluginPackage loaded = manager.loadPlugin(jar);

        Path pf4jPath = manager.pluginManagerForTest().orElseThrow().getPlugin(PROBE_ID)
                .getPluginPath().toAbsolutePath().normalize();
        assertThat(loaded.artifactPath()).isEqualTo(jar.toAbsolutePath().normalize());
        assertThat(verifiedPath.get()).isNotEqualTo(jar.toAbsolutePath().normalize());
        assertThat(verifiedPath.get().getParent()).isEqualTo(pf4jPath.getParent());
        assertThat(Files.readString(jar, StandardCharsets.UTF_8)).isEqualTo("replaced-after-verification");
        manager.shutdown();
    }

    @Test
    @DisplayName("启动批量在首个插件执行代码前已冻结后续候选并沿用同一字节")
    void startupLoadsPreFrozenCandidateAfterEarlierPluginReplacesOriginal() throws IOException {
        Path plugins = tempDir.resolve("startup-frozen-batch");
        Files.createDirectories(plugins);
        Path base = plugins.resolve("base-1.0.0.jar");
        Path dependent = plugins.resolve("dependent-1.0.0.jar");
        writeDependencyOrderProbeJar(base, "base", List.of());
        writeDependencyOrderProbeJar(dependent, "dependent",
                List.of(new PluginDependencyRef("base", "1.0", false)));
        writeLocalProvenance(plugins, base, "base", PROBE_VERSION);
        writeLocalProvenance(plugins, dependent, "dependent", PROBE_VERSION);
        String previousTrigger = System.getProperty("dependency.order.probe.replace-trigger");
        String previousTarget = System.getProperty("dependency.order.probe.replace-target");
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        try {
            System.setProperty("dependency.order.probe.replace-trigger", "base");
            System.setProperty("dependency.order.probe.replace-target", dependent.toString());

            PluginRuntimeStatus status = manager.start();

            assertThat(status.failures()).isEmpty();
            assertThat(status.loadedPluginIds()).containsExactly("base", "dependent");
            assertThat(Files.readString(dependent, StandardCharsets.UTF_8))
                    .isEqualTo("replaced-by-dependency-order-probe");
        } finally {
            manager.shutdown();
            restoreProperty("dependency.order.probe.replace-trigger", previousTrigger);
            restoreProperty("dependency.order.probe.replace-target", previousTarget);
        }
    }

    @Test
    @DisplayName("已安装的根 inner JAR ZIP 在创建 PF4J manager 前按非规范形态拒绝")
    void installedInnerJarZipIsRejectedBeforePf4jLoad() throws IOException {
        Path plugins = tempDir.resolve("plugins-inner-jar");
        Files.createDirectories(plugins);
        Path zip = PluginPackageFixtures.singleJarZip(plugins.resolve("probe.zip"), "probe.jar",
                PROBE_ID, PROBE_VERSION, "1.0", BootstrapProbePlugin.class.getName());
        writeLocalProvenance(plugins, zip);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);

        assertThatThrownBy(() -> manager.loadPlugin(zip))
                .isInstanceOf(top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException.class)
                .hasMessageContaining("canonical")
                .hasMessageContaining("inner plugin jar");
        assertThat(manager.pluginManagerForTest()).isEmpty();
        assertThat(manager.packagePhases()).isEmpty();
    }

    @Test
    @DisplayName("启动整轮递归 entry 累计超限后不再读取或验证后续候选")
    void startupStopsPreparingCandidatesAfterCumulativeEntryBudgetExceeded() throws IOException {
        Path plugins = tempDir.resolve("startup-entry-budget");
        Files.createDirectories(plugins);
        Path first = plugins.resolve("alpha.jar");
        Path second = plugins.resolve("beta.jar");
        Path third = plugins.resolve("gamma.jar");
        writeDependencyOrderProbeJar(first, "alpha", List.of());
        writeDependencyOrderProbeJar(second, "beta", List.of());
        writeDependencyOrderProbeJar(third, "gamma", List.of());
        writeLocalProvenance(plugins, first, "alpha", PROBE_VERSION);
        writeLocalProvenance(plugins, second, "beta", PROBE_VERSION);
        writeLocalProvenance(plugins, third, "gamma", PROBE_VERSION);
        PluginPackageVerifier.VerificationUsage firstUsage = PluginPackageVerifier.verifyAndMeasure(
                first, PluginPackageLimits.defaults());
        PluginPackageVerifier.VerificationUsage secondUsage = PluginPackageVerifier.verifyAndMeasure(
                second, PluginPackageLimits.defaults());
        PluginPackageVerifier.VerificationUsage thirdUsage = PluginPackageVerifier.verifyAndMeasure(
                third, PluginPackageLimits.defaults());
        int entryBudget = firstUsage.entryCount() + secondUsage.entryCount() - 1;
        long byteBudget = firstUsage.totalUncompressedBytes() + secondUsage.totalUncompressedBytes()
                + thirdUsage.totalUncompressedBytes();
        CountingPluginRuntimeManager manager = new CountingPluginRuntimeManager(
                plugins, entryBudget, byteBudget);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.loadedPluginIds()).containsExactly("alpha");
        assertThat(status.failures()).hasSize(2)
                .extracting(PluginLoadFailure::source)
                .containsExactly("beta.jar", "gamma.jar");
        assertThat(status.failures())
                .allSatisfy(failure -> assertThat(failure.reason())
                        .contains("cumulative resource budget exceeded"));
        assertThat(manager.provenanceReadCount()).isEqualTo(2);
        assertThat(manager.packageVerificationCount()).isEqualTo(2);
        assertThat(manager.verificationLimits()).hasSize(2);
        assertThat(manager.verificationLimits().get(1).maxEntries())
                .isEqualTo(secondUsage.entryCount() - 1);
        manager.shutdown();
    }

    @Test
    @DisplayName("启动扫描会扣除 MALFORMED 候选的实际用量并在累计预算耗尽后停止")
    void startupChargesMalformedCandidateUsageBeforeStopping() throws IOException {
        Path plugins = tempDir.resolve("startup-malformed-budget");
        Files.createDirectories(plugins);
        Path malformed = plugins.resolve("alpha-malformed.jar");
        writeDependencyOrderProbeJarWithPrivateLibrary(
                malformed, "alpha", "not-a-jar".getBytes(StandardCharsets.UTF_8));

        assertFailedCandidateConsumesStartupBudget(
                plugins, malformed, PluginPackageException.Reason.MALFORMED,
                "nested plugin jar is malformed");
    }

    @Test
    @DisplayName("启动扫描会扣除 TOO_LARGE 候选的实际用量并在累计预算耗尽后停止")
    void startupChargesTooLargeCandidateUsageBeforeStopping() throws IOException {
        Path plugins = tempDir.resolve("startup-too-large-budget");
        Files.createDirectories(plugins);
        Path tooLarge = plugins.resolve("alpha-too-large.jar");
        writeDependencyOrderProbeJarWithPrivateLibrary(
                tooLarge, "alpha", privateLibraryBytes(new byte[256 * 1024]));

        assertFailedCandidateConsumesStartupBudget(
                plugins, tooLarge, PluginPackageException.Reason.TOO_LARGE,
                "compression ratio too high");
    }

    @Test
    @DisplayName("启动扫描会扣除归档读取失败前的实际用量并在累计预算耗尽后停止")
    void startupChargesReadFailureUsageBeforeStopping() throws IOException {
        Path plugins = tempDir.resolve("startup-read-failure-budget");
        Files.createDirectories(plugins);
        Path readFailure = plugins.resolve("alpha-read-failure.jar");
        writeDependencyOrderProbeJarWithPrivateLibrary(
                readFailure, "alpha", corruptedPrivateLibraryBytes());

        assertFailedCandidateConsumesStartupBudget(
                plugins, readFailure, PluginPackageException.Reason.MALFORMED,
                "not a valid zip package");
    }

    @Test
    @DisplayName("启动整轮两个包的实际解压字节累计超限时只准入预算内候选")
    void startupRejectsSecondPackageWhenCumulativeUncompressedBudgetExceeded() throws IOException {
        Path plugins = tempDir.resolve("startup-byte-budget");
        Files.createDirectories(plugins);
        Path first = plugins.resolve("alpha.jar");
        Path second = plugins.resolve("beta.jar");
        writeDependencyOrderProbeJar(first, "alpha", List.of());
        writeDependencyOrderProbeJar(second, "beta", List.of());
        writeLocalProvenance(plugins, first, "alpha", PROBE_VERSION);
        writeLocalProvenance(plugins, second, "beta", PROBE_VERSION);
        PluginPackageVerifier.VerificationUsage firstUsage = PluginPackageVerifier.verifyAndMeasure(
                first, PluginPackageLimits.defaults());
        PluginPackageVerifier.VerificationUsage secondUsage = PluginPackageVerifier.verifyAndMeasure(
                second, PluginPackageLimits.defaults());
        int entryBudget = firstUsage.entryCount() + secondUsage.entryCount();
        long byteBudget = firstUsage.totalUncompressedBytes()
                + secondUsage.totalUncompressedBytes() - 1L;
        CountingPluginRuntimeManager manager = new CountingPluginRuntimeManager(
                plugins, entryBudget, byteBudget);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.loadedPluginIds()).containsExactly("alpha");
        assertThat(status.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.source()).isEqualTo("beta.jar");
            assertThat(failure.reason()).contains("cumulative resource budget exceeded");
        });
        assertThat(manager.verificationLimits()).hasSize(2);
        assertThat(manager.verificationLimits().get(1).maxTotalUncompressedBytes())
                .isEqualTo(secondUsage.totalUncompressedBytes() - 1L);
        manager.shutdown();
    }

    @Test
    @DisplayName("启动 provenance 累计预算为后续候选下发剩余上限且耗尽后不再打开文件")
    void startupRejectsSecondPackageWhenCumulativeProvenanceBudgetExceeded() throws IOException {
        Path plugins = tempDir.resolve("startup-provenance-budget");
        Files.createDirectories(plugins);
        Path first = plugins.resolve("alpha.jar");
        Path second = plugins.resolve("beta.jar");
        Path third = plugins.resolve("gamma.jar");
        writeDependencyOrderProbeJar(first, "alpha", List.of());
        writeDependencyOrderProbeJar(second, "beta", List.of());
        writeDependencyOrderProbeJar(third, "gamma", List.of());
        writeLocalProvenance(plugins, first, "alpha", PROBE_VERSION);
        writeLocalProvenance(plugins, second, "beta", PROBE_VERSION);
        writeLocalProvenance(plugins, third, "gamma", PROBE_VERSION);
        PluginProvenanceStore provenanceStore = new PluginProvenanceStore(plugins);
        long firstSidecarBytes = provenanceStore.measureManagedSidecarStrict(first).orElseThrow().byteCount();
        long secondSidecarBytes = provenanceStore.measureManagedSidecarStrict(second).orElseThrow().byteCount();
        CountingPluginRuntimeManager manager = new CountingPluginRuntimeManager(
                plugins,
                PluginRuntimeManager.MAX_STARTUP_VERIFICATION_ENTRIES,
                PluginRuntimeManager.MAX_STARTUP_VERIFICATION_UNCOMPRESSED_BYTES,
                firstSidecarBytes + secondSidecarBytes - 1L);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.loadedPluginIds()).containsExactly("alpha");
        assertThat(status.failures()).hasSize(2)
                .extracting(PluginLoadFailure::source)
                .containsExactly("beta.jar", "gamma.jar");
        assertThat(status.failures()).allSatisfy(failure -> assertThat(failure.reason())
                .contains("provenance sidecar cumulative byte budget exceeded"));
        assertThat(manager.provenanceReadCount()).isEqualTo(2);
        assertThat(manager.packageVerificationCount()).isEqualTo(1);
        assertThat(manager.provenanceReadLimits()).containsExactly(
                firstSidecarBytes + secondSidecarBytes - 1L,
                secondSidecarBytes - 1L);
        manager.shutdown();
    }

    @Test
    @DisplayName("启动会扣除无效 provenance 的实际读取字节并在累计耗尽后停止")
    void startupChargesInvalidProvenanceBytesBeforeStopping() throws IOException {
        Path plugins = tempDir.resolve("startup-invalid-provenance-budget");
        Files.createDirectories(plugins);
        Path first = plugins.resolve("alpha.jar");
        Path second = plugins.resolve("beta.jar");
        Path third = plugins.resolve("gamma.jar");
        writeDependencyOrderProbeJar(first, "alpha", List.of());
        writeDependencyOrderProbeJar(second, "beta", List.of());
        writeDependencyOrderProbeJar(third, "gamma", List.of());
        PluginProvenanceStore provenanceStore = new PluginProvenanceStore(plugins);
        byte[] invalidProvenance = {(byte) 0xC3, (byte) 0x28};
        Path invalidSidecar = provenanceStore.sidecarPath(first);
        Files.createDirectories(invalidSidecar.getParent());
        Files.write(invalidSidecar, invalidProvenance);
        writeLocalProvenance(plugins, second, "beta", PROBE_VERSION);
        writeLocalProvenance(plugins, third, "gamma", PROBE_VERSION);
        long secondSidecarBytes = provenanceStore.measureManagedSidecarStrict(second).orElseThrow().byteCount();
        long provenanceBudget = invalidProvenance.length + secondSidecarBytes - 1L;
        CountingPluginRuntimeManager manager = new CountingPluginRuntimeManager(
                plugins,
                PluginRuntimeManager.MAX_STARTUP_VERIFICATION_ENTRIES,
                PluginRuntimeManager.MAX_STARTUP_VERIFICATION_UNCOMPRESSED_BYTES,
                provenanceBudget);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.failures()).satisfiesExactly(
                failure -> {
                    assertThat(failure.source()).isEqualTo("alpha.jar");
                    assertThat(failure.reason()).contains("plugin provenance is invalid");
                },
                failure -> {
                    assertThat(failure.source()).isEqualTo("beta.jar");
                    assertThat(failure.reason()).contains("provenance sidecar cumulative byte budget exceeded");
                },
                failure -> {
                    assertThat(failure.source()).isEqualTo("gamma.jar");
                    assertThat(failure.reason()).contains("provenance sidecar cumulative byte budget exceeded");
                });
        assertThat(manager.provenanceReadCount()).isEqualTo(2);
        assertThat(manager.packageVerificationCount()).isZero();
        assertThat(manager.provenanceReadLimits()).containsExactly(
                provenanceBudget, secondSidecarBytes - 1L);
        manager.shutdown();
    }

    @Test
    @DisplayName("启动兼容读取会接受等价 provenance 双副本并收敛旧位置")
    void startupAcceptsEquivalentCurrentAndLegacyProvenanceCopies() throws IOException {
        Path plugins = tempDir.resolve("startup-equivalent-provenance-copies");
        Files.createDirectories(plugins);
        Path artifact = plugins.resolve("alpha.jar");
        writeDependencyOrderProbeJar(artifact, "alpha", List.of());
        writeLocalProvenance(plugins, artifact, "alpha", PROBE_VERSION);
        PluginProvenanceStore provenanceStore = new PluginProvenanceStore(plugins);
        Path current = provenanceStore.sidecarPath(artifact);
        Path legacy = provenanceStore.managedSidecarPaths(artifact).stream()
                .filter(path -> !path.equals(current))
                .findFirst()
                .orElseThrow();
        Files.copy(current, legacy);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.loadedPluginIds()).containsExactly("alpha");
        assertThat(status.failures()).isEmpty();
        assertThat(current).exists();
        assertThat(legacy).doesNotExist();
        manager.shutdown();
    }

    @Test
    @DisplayName("单个候选 provenance 文件形态错误不会阻断后续有效插件")
    void startupIsolatesProvenanceIoFailureToCurrentCandidate() throws IOException {
        Path plugins = tempDir.resolve("startup-provenance-io-isolation");
        Files.createDirectories(plugins);
        Path invalid = plugins.resolve("alpha.jar");
        Path valid = plugins.resolve("beta.jar");
        writeDependencyOrderProbeJar(invalid, "alpha", List.of());
        writeDependencyOrderProbeJar(valid, "beta", List.of());
        PluginProvenanceStore provenanceStore = new PluginProvenanceStore(plugins);
        Path invalidSidecar = provenanceStore.sidecarPath(invalid);
        Files.createDirectories(invalidSidecar.getParent());
        Files.createDirectory(invalidSidecar);
        writeLocalProvenance(plugins, valid, "beta", PROBE_VERSION);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.loadedPluginIds()).containsExactly("beta");
        assertThat(status.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.source()).isEqualTo("alpha.jar");
            assertThat(failure.reason()).contains("not a regular file");
        });
        manager.shutdown();
    }

    @Test
    @DisplayName("同一插件换用新 artifact 路径复验时替换旧路径快照")
    void runtimeVerificationReplacesPriorArtifactPathForSamePlugin() throws IOException {
        Path plugins = tempDir.resolve("runtime-verification-path-replacement");
        Files.createDirectories(plugins);
        Path first = plugins.resolve("alpha-1.0.0.jar");
        Path replacement = plugins.resolve("alpha-2.0.0.jar");
        writeDependencyOrderProbeJarWithMarker(first, "alpha", "first");
        writeLocalProvenance(plugins, first, "alpha", PROBE_VERSION);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);
        assertThat(manager.start().verifications()).singleElement()
                .extracting(PluginRuntimeVerificationSnapshot::artifactPath)
                .isEqualTo(first.toAbsolutePath().normalize());
        manager.stopPlugin("alpha");
        manager.unloadPlugin("alpha");
        writeDependencyOrderProbeJarWithMarker(replacement, "alpha", "replacement");
        writeLocalProvenance(plugins, replacement, "alpha", PROBE_VERSION);

        manager.loadPlugin(replacement);

        assertThat(manager.status().orElseThrow().verifications()).singleElement()
                .extracting(PluginRuntimeVerificationSnapshot::artifactPath)
                .isEqualTo(replacement.toAbsolutePath().normalize());
        manager.shutdown();
    }

    @Test
    @DisplayName("启动扫描遇同 plugin id 的两份 artifact 时两份都不加载")
    void startupRejectsEveryArtifactWithDuplicatePluginId() throws IOException {
        Path plugins = tempDir.resolve("startup-duplicate-id");
        Files.createDirectories(plugins);
        Path first = plugins.resolve("a-base.jar");
        Path second = plugins.resolve("b-base.jar");
        writeDependencyOrderProbeJar(first, "base", List.of());
        writeDependencyOrderProbeJar(second, "base", List.of());
        writeLocalProvenance(plugins, first, "base", PROBE_VERSION);
        writeLocalProvenance(plugins, second, "base", PROBE_VERSION);
        PluginRuntimeManager manager = new PluginRuntimeManager(plugins);

        PluginRuntimeStatus status = manager.start();

        assertThat(status.loadedPluginIds()).isEmpty();
        assertThat(status.startedPluginIds()).isEmpty();
        assertThat(status.failures()).hasSize(2)
                .allSatisfy(failure -> assertThat(failure.reason()).contains("duplicate plugin id base"));
        assertThat(manager.pluginManagerForTest()).isEmpty();
        manager.shutdown();
    }

    @Test
    @DisplayName("构造参数为 null 时立即抛出")
    void nullRootRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PluginRuntimeManager(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertFailedCandidateConsumesStartupBudget(
            Path plugins,
            Path failedCandidate,
            PluginPackageException.Reason expectedReason,
            String expectedFailureFragment) throws IOException {
        Path second = plugins.resolve("beta.jar");
        Path third = plugins.resolve("gamma.jar");
        writeDependencyOrderProbeJar(second, "beta", List.of());
        writeDependencyOrderProbeJar(third, "gamma", List.of());
        writeLocalProvenance(plugins, failedCandidate, "alpha", PROBE_VERSION);
        writeLocalProvenance(plugins, second, "beta", PROBE_VERSION);
        writeLocalProvenance(plugins, third, "gamma", PROBE_VERSION);
        PluginPackageVerifier.VerificationUsage failedUsage = failedVerificationUsage(
                failedCandidate, expectedReason, expectedFailureFragment);
        PluginPackageVerifier.VerificationUsage secondUsage = PluginPackageVerifier.verifyAndMeasure(
                second, PluginPackageLimits.defaults());
        PluginPackageVerifier.VerificationUsage thirdUsage = PluginPackageVerifier.verifyAndMeasure(
                third, PluginPackageLimits.defaults());
        int entryBudget = failedUsage.entryCount() + secondUsage.entryCount() - 1;
        long byteBudget = failedUsage.totalUncompressedBytes()
                + secondUsage.totalUncompressedBytes()
                + thirdUsage.totalUncompressedBytes()
                + 1L;
        CountingPluginRuntimeManager manager = new CountingPluginRuntimeManager(
                plugins, entryBudget, byteBudget);
        try {
            PluginRuntimeStatus status = manager.start();

            assertThat(status.loadedPluginIds()).isEmpty();
            assertThat(status.failures()).satisfiesExactly(
                    failure -> {
                        assertThat(failure.source()).isEqualTo(failedCandidate.getFileName().toString());
                        assertThat(failure.reason()).contains(expectedFailureFragment);
                    },
                    failure -> {
                        assertThat(failure.source()).isEqualTo("beta.jar");
                        assertThat(failure.reason()).contains("cumulative resource budget exceeded");
                    },
                    failure -> {
                        assertThat(failure.source()).isEqualTo("gamma.jar");
                        assertThat(failure.reason()).contains("cumulative resource budget exceeded");
                    });
            assertThat(manager.provenanceReadCount()).isEqualTo(2);
            assertThat(manager.packageVerificationCount()).isEqualTo(2);
            assertThat(manager.verificationLimits()).hasSize(2);
            assertThat(manager.verificationLimits().get(0).maxEntries()).isEqualTo(entryBudget);
            assertThat(manager.verificationLimits().get(1).maxEntries())
                    .isEqualTo(secondUsage.entryCount() - 1);
        } finally {
            manager.shutdown();
        }
    }

    private static PluginPackageVerifier.VerificationUsage failedVerificationUsage(
            Path artifact,
            PluginPackageException.Reason expectedReason,
            String expectedFailureFragment) {
        PluginPackageException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> PluginPackageVerifier.verifyAndMeasure(artifact, PluginPackageLimits.defaults()),
                PluginPackageException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.reason()).isEqualTo(expectedReason);
        assertThat(failure).hasMessageContaining(expectedFailureFragment);
        assertThat(failure.hasVerificationUsage()).isTrue();
        assertThat(failure.consumedEntries()).isPositive();
        assertThat(failure.consumedUncompressedBytes()).isPositive();
        return new PluginPackageVerifier.VerificationUsage(
                failure.consumedEntries(), failure.consumedUncompressedBytes());
    }

    private static void writeProbeJar(Path jar, boolean privateLib) throws IOException {
        writeProbeJar(jar, privateLib, null);
    }

    private static void writeProbeJar(Path jar, boolean privateLib, String lifecyclePolicy) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            addDescriptor(zos, lifecyclePolicy);
            addClassEntry(zos, BootstrapProbePlugin.class, "");
            addClassEntry(zos, BootstrapProbeFeaturePlugin.class, "");
            if (privateLib) {
                zos.putNextEntry(new ZipEntry("lib/private-lib.jar"));
                zos.write(nestedJarBytes());
                zos.closeEntry();
            }
        }
    }

    private static void writeDeclarativeProbeJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            addDescriptor(zos, "hot-reload", "declarative-process");
            addClassEntry(zos, BootstrapProbePlugin.class, "");
            addClassEntry(zos, BootstrapProbeFeaturePlugin.class, "");
        }
    }

    private static void writeIsolatedStaticProbeJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            String descriptor = "plugin.id=isolated-static-probe\n"
                    + "plugin.version=" + PROBE_VERSION + "\n"
                    + "plugin.requires=1.0\n"
                    + "plugin.class=" + IsolatedStaticProbePlugin.class.getName() + "\n"
                    + "plugin.provider=test\n"
                    + "plugin.description=isolated static probe\n"
                    + "pixiv.kind=feature\n"
                    + "pixiv.display-namespace=isolated-static\n"
                    + "pixiv.display-name-key=plugin.name\n"
                    + "pixiv.description-key=plugin.summary\n"
                    + "pixiv.execution-mode=declarative-process\n";
            zos.putNextEntry(new ZipEntry("plugin.properties"));
            zos.write(descriptor.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            addClassEntry(zos, IsolatedStaticProbePlugin.class, "");
            addClassEntry(zos, IsolatedStaticProbeFeaturePlugin.class, "");
            zos.putNextEntry(new ZipEntry("static/isolated-static/index.html"));
            zos.write("<!doctype html><title>isolated</title>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("i18n/web/isolatedstatic.properties"));
            zos.write("plugin.name=Isolated\nplugin.summary=Static\nnav.home=Home\n"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static void writeProbeExplodedZip(Path zip) throws IOException {
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            addDescriptor(zos);
            addClassEntry(zos, BootstrapProbePlugin.class, "classes/");
            addClassEntry(zos, BootstrapProbeFeaturePlugin.class, "classes/");
        }
    }

    private static void writeDependencyOrderProbeJar(Path jar, String pluginId,
                                                     List<PluginDependencyRef> dependencies) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            addDependencyOrderDescriptor(zos, pluginId, dependencies);
            addClassEntry(zos, DependencyOrderProbePlugin.class, "");
            addClassEntry(zos, DependencyOrderProbeFeaturePlugin.class, "");
        }
    }

    private static void writeDependencyOrderProbeJarWithMarker(
            Path jar, String pluginId, String marker) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            addDependencyOrderDescriptor(zos, pluginId, List.of());
            addClassEntry(zos, DependencyOrderProbePlugin.class, "");
            addClassEntry(zos, DependencyOrderProbeFeaturePlugin.class, "");
            zos.putNextEntry(new ZipEntry("verification-marker.txt"));
            zos.write(marker.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private static void writeDependencyOrderProbeJarWithPrivateLibrary(
            Path jar,
            String pluginId,
            byte[] privateLibrary) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            addDependencyOrderDescriptor(zos, pluginId, List.of());
            addClassEntry(zos, DependencyOrderProbePlugin.class, "");
            addClassEntry(zos, DependencyOrderProbeFeaturePlugin.class, "");
            zos.putNextEntry(new ZipEntry("lib/private-lib.jar"));
            zos.write(privateLibrary);
            zos.closeEntry();
        }
    }

    private static void writeProbeClassesDirectory(Path classesDirectory, boolean privateLib) throws IOException {
        Files.createDirectories(classesDirectory);
        String props = "plugin.id=" + PROBE_ID + "\nplugin.version=" + PROBE_VERSION + "\nplugin.requires=1.0\n"
                + "plugin.class=" + BootstrapProbePlugin.class.getName() + "\n"
                + "plugin.provider=test\nplugin.description=bootstrap probe\n"
                + "pixiv.kind=feature\n"
                + "pixiv.lifecycle-policy=process-restart\n"
                + "pixiv.execution-mode=host-process-full-trust\n";
        Files.writeString(classesDirectory.resolve("plugin.properties"), props, StandardCharsets.UTF_8);
        copyClassFile(classesDirectory, BootstrapProbePlugin.class);
        copyClassFile(classesDirectory, BootstrapProbeFeaturePlugin.class);
        if (privateLib) {
            Path lib = classesDirectory.resolve("lib/private-lib.jar");
            Files.createDirectories(lib.getParent());
            Files.write(lib, nestedJarBytes());
        }
    }

    private static void copyClassFile(Path classesDirectory, Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        Path target = classesDirectory.resolve(entry);
        Files.createDirectories(target.getParent());
        try (InputStream in = type.getResourceAsStream("/" + entry)) {
            assertThat(in).as("class resource must be compiled: " + type.getName()).isNotNull();
            Files.copy(in, target);
        }
    }

    private static void replacePluginManager(
            PluginRuntimeManager runtimeManager, PluginManager pluginManager) throws ReflectiveOperationException {
        Field field = top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager.class
                .getDeclaredField("pluginManager");
        field.setAccessible(true);
        field.set(runtimeManager, pluginManager);
    }

    private static PluginArtifactVerificationService verificationService(PluginRuntimeManager runtimeManager)
            throws ReflectiveOperationException {
        Field field = top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager.class
                .getDeclaredField("verificationService");
        field.setAccessible(true);
        return (PluginArtifactVerificationService) field.get(runtimeManager);
    }

    private static void replaceVerificationService(
            PluginRuntimeManager runtimeManager, PluginArtifactVerificationService verificationService)
            throws ReflectiveOperationException {
        Field field = top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager.class
                .getDeclaredField("verificationService");
        field.setAccessible(true);
        field.set(runtimeManager, verificationService);
    }

    private static int invokeInt(Object target, String methodName) throws ReflectiveOperationException {
        return (int) target.getClass().getMethod(methodName).invoke(target);
    }

    private static void replaceDevelopmentCacheSession(
            PluginRuntimeManager runtimeManager,
            PluginDevelopmentArtifacts.DevelopmentCacheSession session) throws ReflectiveOperationException {
        Field field = top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager.class
                .getDeclaredField("developmentCacheSession");
        field.setAccessible(true);
        field.set(runtimeManager, session);
    }

    private PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin materializedDevelopmentPlugin(
            String pluginId, List<PluginDependencyRef> dependencies) {
        Path moduleRoot = tempDir.resolve("pixivdownload-plugin-" + pluginId);
        PluginDescriptor descriptor = new PluginDescriptor(pluginId, pluginId, PROBE_VERSION,
                VersionRequirement.of(1, 0), dependencies, "com.example." + pluginId.replace("-", "") + ".Plugin",
                null, pluginId, null, null, null, PluginKind.FEATURE);
        return new PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin(
                moduleRoot, moduleRoot.resolve("target/classes"),
                tempDir.resolve("cache").resolve(pluginId), descriptor);
    }

    private static void addDescriptor(ZipOutputStream zos) throws IOException {
        addDescriptor(zos, null);
    }

    private static void addDescriptor(ZipOutputStream zos, String lifecyclePolicy) throws IOException {
        addDescriptor(zos, lifecyclePolicy != null ? lifecyclePolicy : "process-restart",
                "host-process-full-trust");
    }

    private static void addDescriptor(
            ZipOutputStream zos,
            String lifecyclePolicy,
            String executionMode) throws IOException {
        String props = "plugin.id=" + PROBE_ID + "\nplugin.version=" + PROBE_VERSION + "\nplugin.requires=1.0\n"
                + "plugin.class=" + BootstrapProbePlugin.class.getName() + "\n"
                + "plugin.provider=test\nplugin.description=bootstrap probe\n"
                + "pixiv.kind=feature\n"
                + (lifecyclePolicy != null ? "pixiv.lifecycle-policy=" + lifecyclePolicy + "\n" : "")
                + (executionMode != null ? "pixiv.execution-mode=" + executionMode + "\n" : "");
        zos.putNextEntry(new ZipEntry("plugin.properties"));
        zos.write(props.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void addDependencyOrderDescriptor(ZipOutputStream zos, String pluginId,
                                                     List<PluginDependencyRef> dependencies) throws IOException {
        StringBuilder props = new StringBuilder()
                .append("plugin.id=").append(pluginId).append('\n')
                .append("plugin.version=").append(PROBE_VERSION).append('\n')
                .append("plugin.requires=1.0\n")
                .append("plugin.class=").append(DependencyOrderProbePlugin.class.getName()).append('\n')
                .append("plugin.provider=test\n")
                .append("plugin.description=").append(pluginId).append(" probe\n")
                .append("pixiv.kind=feature\n")
                .append("pixiv.lifecycle-policy=process-restart\n")
                .append("pixiv.execution-mode=host-process-full-trust\n");
        if (!dependencies.isEmpty()) {
            props.append("plugin.dependencies=");
            for (int i = 0; i < dependencies.size(); i++) {
                PluginDependencyRef dependency = dependencies.get(i);
                if (i > 0) {
                    props.append(',');
                }
                props.append(dependency.pluginId());
                if (dependency.optional()) {
                    props.append('?');
                }
                props.append('@').append(dependency.versionSupport());
            }
        }
        zos.putNextEntry(new ZipEntry("plugin.properties"));
        zos.write(props.toString().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void addClassEntry(ZipOutputStream zos, Class<?> type, String prefix) throws IOException {
        String entry = prefix + type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertThat(in).as("class resource must be compiled: " + type.getName()).isNotNull();
            bytes = in.readAllBytes();
        }
        zos.putNextEntry(new ZipEntry(entry));
        zos.write(bytes);
        zos.closeEntry();
    }

    private static byte[] nestedJarBytes() throws IOException {
        try (var out = new java.io.ByteArrayOutputStream();
             var zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("private/Marker.txt"));
            zos.write("private-lib".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.finish();
            return out.toByteArray();
        }
    }

    private static byte[] privateLibraryBytes(byte[] payload) throws IOException {
        try (var out = new ByteArrayOutputStream();
             var zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("private/payload.bin"));
            zos.write(payload);
            zos.closeEntry();
            zos.finish();
            return out.toByteArray();
        }
    }

    private static byte[] corruptedPrivateLibraryBytes() throws IOException {
        byte[] payload = "corrupted-private-library".getBytes(StandardCharsets.UTF_8);
        byte[] archive;
        try (var out = new ByteArrayOutputStream();
             var zos = new ZipOutputStream(out)) {
            CRC32 crc = new CRC32();
            crc.update(payload);
            ZipEntry entry = new ZipEntry("private/payload.bin");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(crc.getValue());
            zos.putNextEntry(entry);
            zos.write(payload);
            zos.closeEntry();
            zos.finish();
            archive = out.toByteArray();
        }
        int payloadOffset = indexOf(archive, payload);
        if (payloadOffset < 0) {
            throw new IOException("failed to locate stored private-library payload");
        }
        archive[payloadOffset] ^= 0x01;
        return archive;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void writeLocalProvenance(Path pluginsDir, Path artifact) throws IOException {
        writeLocalProvenance(pluginsDir, artifact, PROBE_ID, PROBE_VERSION);
    }

    private static void writeLocalProvenance(Path pluginsDir, Path artifact, String pluginId, String version)
            throws IOException {
        VerificationResult result = new VerificationResult(VerificationStatus.UNSIGNED_ALLOWED,
                pluginId, version, null, null, null, null, Instant.now(), Files.size(artifact),
                PluginPackageIntegrity.sha256Hex(artifact), "UNSIGNED_ALLOWED");
        new PluginProvenanceStore(pluginsDir).write(artifact, PluginPackageOrigin.localUpload(), result);
    }

    private static void writeCatalogProvenance(
            Path pluginsDir, Path artifact, String pluginId, String version) throws IOException {
        long size = Files.size(artifact);
        String sha256 = PluginPackageIntegrity.sha256Hex(artifact);
        SignatureMetadata signature = new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519, "test-key", "c2ln");
        PluginProvenanceRecord provenance = new PluginProvenanceRecord(
                PluginPackageSource.MARKET_CATALOG,
                "test-repository",
                false,
                false,
                size,
                sha256,
                size,
                sha256,
                signature,
                VerificationStatus.VERIFIED,
                signature.keyId(),
                "Test Publisher",
                "Test Trust",
                Instant.now(),
                null,
                null,
                "VERIFIED");
        new PluginProvenanceStore(pluginsDir).write(artifact, provenance);
    }

    private static void writeProbeSourceDescriptor(Path moduleRoot) throws IOException {
        Path sourceResources = moduleRoot.resolve("src/main/resources");
        Files.createDirectories(sourceResources);
        Files.writeString(sourceResources.resolve("plugin.properties"),
                "plugin.id=" + PROBE_ID + "\nplugin.version=" + PROBE_VERSION + "\nplugin.requires=1.0\n"
                        + "plugin.class=" + BootstrapProbePlugin.class.getName() + "\n"
                        + "pixiv.kind=feature\n"
                        + "pixiv.lifecycle-policy=process-restart\n"
                        + "pixiv.execution-mode=host-process-full-trust\n",
                StandardCharsets.UTF_8);
    }

    private static void writeDeclarativeProbeSourceDescriptor(Path moduleRoot) throws IOException {
        Path sourceResources = moduleRoot.resolve("src/main/resources");
        Files.createDirectories(sourceResources);
        Files.writeString(sourceResources.resolve("plugin.properties"),
                "plugin.id=" + PROBE_ID + "\nplugin.version=" + PROBE_VERSION + "\nplugin.requires=1.0\n"
                        + "plugin.class=" + BootstrapProbePlugin.class.getName() + "\n"
                        + "pixiv.execution-mode=declarative-process\n",
                StandardCharsets.UTF_8);
    }

    private static void writeDeclarativeProbeClassesDirectory(Path classesDirectory) throws IOException {
        Files.createDirectories(classesDirectory);
        Files.writeString(classesDirectory.resolve("plugin.properties"),
                "plugin.id=" + PROBE_ID + "\nplugin.version=" + PROBE_VERSION + "\nplugin.requires=1.0\n"
                        + "plugin.class=" + BootstrapProbePlugin.class.getName() + "\n"
                        + "pixiv.execution-mode=declarative-process\n",
                StandardCharsets.UTF_8);
        copyClassFile(classesDirectory, BootstrapProbePlugin.class);
        copyClassFile(classesDirectory, BootstrapProbeFeaturePlugin.class);
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    private static class PluginRuntimeManager
            extends top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager {

        private PluginRuntimeManager(Path pluginsRoot) {
            super(pluginsRoot, () -> true);
        }

        private PluginRuntimeManager(Path pluginsRoot,
                                     int maximumStartupVerificationEntries,
                                     long maximumStartupVerificationUncompressedBytes,
                                     long maximumStartupProvenanceBytes) {
            super(pluginsRoot, maximumStartupVerificationEntries,
                    maximumStartupVerificationUncompressedBytes, maximumStartupProvenanceBytes,
                    () -> true);
        }
    }

    private static final class CountingPluginRuntimeManager extends PluginRuntimeManager {

        private final AtomicInteger provenanceReadCount = new AtomicInteger();
        private final AtomicInteger packageVerificationCount = new AtomicInteger();
        private final List<Long> provenanceReadLimits = new ArrayList<>();
        private final List<PluginPackageLimits> verificationLimits = new ArrayList<>();

        private CountingPluginRuntimeManager(Path pluginsRoot,
                                             int maximumStartupVerificationEntries,
                                             long maximumStartupVerificationUncompressedBytes) {
            this(pluginsRoot, maximumStartupVerificationEntries,
                    maximumStartupVerificationUncompressedBytes,
                    PluginRuntimeManager.MAX_STARTUP_PROVENANCE_BYTES);
        }

        private CountingPluginRuntimeManager(Path pluginsRoot,
                                             int maximumStartupVerificationEntries,
                                             long maximumStartupVerificationUncompressedBytes,
                                             long maximumStartupProvenanceBytes) {
            super(pluginsRoot, maximumStartupVerificationEntries,
                    maximumStartupVerificationUncompressedBytes, maximumStartupProvenanceBytes);
        }

        @Override
        Optional<PluginProvenanceStore.MeasuredProvenance> readMeasuredStartupProvenance(
                Path artifactPath,
                long maximumBytes) throws IOException {
            provenanceReadCount.incrementAndGet();
            provenanceReadLimits.add(maximumBytes);
            return super.readMeasuredStartupProvenance(artifactPath, maximumBytes);
        }

        @Override
        PluginPackageVerifier.VerificationUsage verifyAndMeasureProductionPackage(
                Path frozenArtifact,
                PluginPackageLimits limits) {
            packageVerificationCount.incrementAndGet();
            verificationLimits.add(limits);
            return super.verifyAndMeasureProductionPackage(frozenArtifact, limits);
        }

        private int provenanceReadCount() {
            return provenanceReadCount.get();
        }

        private int packageVerificationCount() {
            return packageVerificationCount.get();
        }

        private List<Long> provenanceReadLimits() {
            return List.copyOf(provenanceReadLimits);
        }

        private List<PluginPackageLimits> verificationLimits() {
            return List.copyOf(verificationLimits);
        }
    }

    private static final class FailingProvenanceWritePluginRuntimeManager extends PluginRuntimeManager {

        private FailingProvenanceWritePluginRuntimeManager(Path pluginsRoot) {
            super(pluginsRoot);
        }

        @Override
        void persistOfflineVerification(Path artifactPath, PluginProvenanceRecord provenance) throws IOException {
            throw new IOException("simulated provenance write-back failure");
        }
    }
}
