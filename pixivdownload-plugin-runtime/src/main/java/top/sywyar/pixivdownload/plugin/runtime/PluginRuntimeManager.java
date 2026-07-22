package top.sywyar.pixivdownload.plugin.runtime;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginDependency;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactLoadPlan;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactMaterializer;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactScanner;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginRuntimeLayout;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVerifier;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginArtifactVerificationService;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PixivPluginDiscoveryBridge;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.UnloadedPluginPackage;

/**
 * PF4J 外置插件物理生命周期封装。启动扫描与运行期变更共用单包 load/start/stop/unload 原语，
 * app 侧不会接触任何 PF4J 类型。
 */
public class PluginRuntimeManager {

    // 所有运行期包变更必须复用本类的单包原语，禁止在 app 侧直接操作 PF4J manager。

    private static final Logger log = LoggerFactory.getLogger(PluginRuntimeManager.class);
    private static final String ANSI_RED_BOLD = "\u001B[1;31m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final PluginPackageLimits PRODUCTION_PACKAGE_LIMITS = PluginPackageLimits.defaults();

    private final Path pluginsRoot;
    private final PluginRuntimeLayout layout;
    private final PluginArtifactMaterializer materializer;
    private PluginArtifactVerificationService verificationService;
    private Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver;
    private final PluginProvenanceStore provenanceStore;
    private final Map<String, RuntimeEntry> entries = new LinkedHashMap<>();
    private final Map<String, Long> generations = new LinkedHashMap<>();
    private final Set<PluginArtifactSnapshot> unconfirmedProductionSnapshots =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private volatile PluginManager pluginManager;
    private volatile PluginRuntimeStatus status;
    private PluginDevelopmentArtifacts.DevelopmentCacheSession developmentCacheSession;

    public PluginRuntimeManager(Path pluginsRoot) {
        this(pluginsRoot, new PluginSupplyChainVerifier());
    }

    public PluginRuntimeManager(Path pluginsRoot, PluginSupplyChainVerifier verifier) {
        this(pluginsRoot, fixedVerifier(verifier));
    }

    public PluginRuntimeManager(Path pluginsRoot,
                                Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver) {
        if (pluginsRoot == null) {
            throw new IllegalArgumentException("pluginsRoot must not be null");
        }
        this.pluginsRoot = pluginsRoot;
        this.layout = new PluginRuntimeLayout(pluginsRoot);
        this.materializer = new PluginArtifactMaterializer(layout);
        this.verifierResolver = Objects.requireNonNull(verifierResolver, "verifierResolver");
        this.verificationService = new PluginArtifactVerificationService(this.verifierResolver);
        this.provenanceStore = new PluginProvenanceStore(layout);
    }

    /** 由宿主在配置解析后刷新统一验签门面；必须发生在后续 load 原语进入 PF4J 前。 */
    public synchronized void updateVerifier(PluginSupplyChainVerifier verifier) {
        updateVerifierResolver(fixedVerifier(verifier));
    }

    /** 由宿主在配置解析后刷新按来源解析的验签门面；必须发生在后续 load 原语进入 PF4J 前。 */
    public synchronized void updateVerifierResolver(
            Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver) {
        this.verifierResolver = Objects.requireNonNull(verifierResolver, "verifierResolver");
        this.verificationService = new PluginArtifactVerificationService(this.verifierResolver);
    }

    /** 启动扫描。每个候选包分别 load/start，单包失败不会阻止其它包。 */
    public synchronized PluginRuntimeStatus start() {
        Path directory = pluginsRoot.toAbsolutePath().normalize();
        resetPluginManager();

        if (PluginDevelopmentArtifacts.enabled()) {
            try {
                beforeProductionScan(directory);
            } catch (IOException | RuntimeException e) {
                return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.EMPTY,
                        List.of(), List.of(), List.of(new PluginLoadFailure(directory.toString(), describe(e)))));
            }
            return startDevelopmentMode(directory);
        }
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.ABSENT,
                    List.of(), List.of(), List.of()));
        }

        if (Files.isRegularFile(directory, LinkOption.NOFOLLOW_LINKS)) {
            return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.ABSENT,
                    List.of(), List.of(), List.of()));
        }
        PluginArtifactScanner.ScanResult scan;
        try {
            beforeProductionScan(directory);
            scan = PluginArtifactScanner.scan(directory);
        } catch (IOException | RuntimeException e) {
            return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.EMPTY,
                    List.of(), List.of(), List.of(new PluginLoadFailure(directory.toString(), describe(e)))));
        }
        if (!scan.rootPresent()) {
            return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.ABSENT,
                    List.of(), List.of(), List.of()));
        }
        List<Path> candidates = scan.candidates();
        if (candidates.isEmpty()) {
            return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.EMPTY,
                    List.of(), List.of(), List.of()));
        }

        List<PreparedProductionArtifact> preparedCandidates = new ArrayList<>(candidates.size());
        List<PluginLoadFailure> failures = new ArrayList<>();
        try {
            // 在任何插件代码进入 PF4J 前冻结并校验本轮全部候选，后续依赖排序与加载只消费私有快照。
            for (Path candidate : candidates) {
                try {
                    preparedCandidates.add(prepareProductionArtifact(candidate));
                } catch (RuntimeException e) {
                    PluginLoadFailure failure = new PluginLoadFailure(
                            candidate.getFileName().toString(), describe(e));
                    failures.add(failure);
                    log.error("Failed to prepare plugin package {}: {}",
                            candidate.getFileName(), failure.reason());
                }
            }
            PluginArtifactLoadPlan loadPlan = PluginArtifactLoadPlan.createInspected(preparedCandidates.stream()
                    .map(PreparedProductionArtifact::loadPlanEntry)
                    .toList());
            failures.addAll(loadPlan.failures());
            for (PluginLoadFailure failure : loadPlan.failures()) {
                log.error("Failed to prepare plugin package {}: {}", failure.source(), failure.reason());
            }
            Map<Path, PreparedProductionArtifact> preparedByPath = new LinkedHashMap<>();
            for (PreparedProductionArtifact prepared : preparedCandidates) {
                preparedByPath.put(prepared.originalArtifact(), prepared);
            }
            Set<String> failedPluginIds = new LinkedHashSet<>(loadPlan.skippedPluginIds());
            for (PluginArtifactLoadPlan.Entry candidate : loadPlan.orderedEntries()) {
                Optional<PluginLoadFailure> blocked =
                        loadPlan.blockedByFailedRequiredDependency(candidate, failedPluginIds);
                if (blocked.isPresent()) {
                    failures.add(blocked.get());
                    failedPluginIds.add(candidate.pluginId());
                    log.error("Skipped plugin package {}: {}",
                            blocked.get().source(), blocked.get().reason());
                    continue;
                }
                PreparedProductionArtifact prepared = preparedByPath.get(
                        candidate.artifactPath().toAbsolutePath().normalize());
                if (prepared == null) {
                    throw new IllegalStateException("prepared plugin artifact disappeared from load plan: "
                            + candidate.artifactPath());
                }
                try {
                    loadPreparedProductionArtifact(prepared);
                } catch (RuntimeException e) {
                    failedPluginIds.add(candidate.pluginId());
                    failures.add(new PluginLoadFailure(
                            candidate.artifactPath().getFileName().toString(), describe(e)));
                    log.error("Failed to load plugin package {}: {}",
                            candidate.artifactPath().getFileName(), describe(e));
                }
            }
        } finally {
            preparedCandidates.forEach(PreparedProductionArtifact::close);
        }
        for (String packageId : List.copyOf(entries.keySet())) {
            try {
                startPlugin(packageId);
            } catch (RuntimeException e) {
                failures.add(new PluginLoadFailure(packageId, describe(e)));
                log.error("Failed to start plugin package {}: {}", packageId, describe(e));
            }
        }
        return cache(buildStatus(directory, failures));
    }

    /** 从明确路径加载一个插件包并创建新 generation；不会启动插件入口。 */
    public synchronized LoadedPluginPackage loadPlugin(Path artifactPath) {
        try {
            beforeProductionScan(pluginsRoot);
        } catch (IOException e) {
            throw new PluginRuntimeOperationException(
                    "plugin directory is not safe for an artifact load", e);
        }
        if (artifactPath == null) {
            throw new PluginRuntimeOperationException("plugin artifact not found: null");
        }
        if (PluginDevelopmentArtifacts.enabled() && Files.isDirectory(artifactPath)) {
            return loadDevelopmentPlugin(artifactPath);
        }
        PreparedProductionArtifact prepared = prepareProductionArtifact(artifactPath);
        try {
            return loadPreparedProductionArtifact(prepared);
        } finally {
            prepared.close();
        }
    }

    private LoadedPluginPackage loadDevelopmentPlugin(Path classesDirectory) {
        Path normalizedClasses = classesDirectory.toAbsolutePath().normalize();
        PluginDevelopmentArtifacts.DevelopmentDiscovery discovery =
                PluginDevelopmentArtifacts.discover(pluginsRoot);
        PluginDevelopmentArtifacts.DevelopmentPluginArtifact artifact = discovery.artifacts().stream()
                .filter(candidate -> candidate.classesDirectory().equals(normalizedClasses))
                .findFirst()
                .orElseThrow(() -> new PluginRuntimeOperationException(
                        "development plugin artifact not found: " + normalizedClasses));
        PluginDescriptor descriptor = PluginPackageReader.inspectDescriptor(artifact.descriptorPath());
        if (entries.containsKey(descriptor.id())) {
            throw new PluginRuntimeOperationException("plugin package already loaded: " + descriptor.id());
        }
        PluginDevelopmentArtifacts.DevelopmentCacheSession session =
                ensureDevelopmentCacheSession(discovery.cacheRoot());
        PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin materialized =
                PluginDevelopmentArtifacts.materialize(artifact, session);
        return loadPreparedPlugin(materialized.classesDirectory(), materialized.pf4jLoadPath(),
                session.sessionRoot(), materialized.descriptor(), null);
    }

    private PluginRuntimeStatus startDevelopmentMode(Path productionDirectory) {
        PluginDevelopmentArtifacts.DevelopmentDiscovery discovery;
        try {
            discovery = PluginDevelopmentArtifacts.discover(pluginsRoot);
        } catch (RuntimeException e) {
            return cache(new PluginRuntimeStatus(productionDirectory, PluginDirectoryState.ABSENT,
                    List.of(), List.of(), List.of(new PluginLoadFailure(
                    PluginDevelopmentArtifacts.ROOT_PROPERTY, describe(e)))));
        }
        printDevelopmentModeBanner(productionDirectory, discovery);
        Path developmentRoot = discovery.developmentRoot();
        if (!Files.isDirectory(developmentRoot)) {
            return cache(new PluginRuntimeStatus(developmentRoot, PluginDirectoryState.ABSENT,
                    List.of(), List.of(), List.of()));
        }
        List<PluginLoadFailure> failures = new ArrayList<>(developmentSourceFailures(discovery));
        if (discovery.artifacts().isEmpty()) {
            return cache(new PluginRuntimeStatus(developmentRoot, PluginDirectoryState.EMPTY,
                    List.of(), List.of(), failures));
        }

        PluginDevelopmentArtifacts.DevelopmentCacheSession session;
        try {
            session = ensureDevelopmentCacheSession(discovery.cacheRoot());
        } catch (RuntimeException e) {
            failures.add(new PluginLoadFailure(discovery.cacheRoot().toString(), describe(e)));
            log.error("Failed to open plugin development cache session {}", discovery.cacheRoot(), e);
            return cache(buildStatus(developmentRoot, failures));
        }
        List<PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin> materializedPlugins = new ArrayList<>();
        for (PluginDevelopmentArtifacts.DevelopmentPluginArtifact artifact : discovery.artifacts()) {
            try {
                materializedPlugins.add(PluginDevelopmentArtifacts.materialize(artifact, session));
            } catch (RuntimeException e) {
                failures.add(new PluginLoadFailure(artifact.moduleRoot().getFileName().toString(), describe(e)));
                log.error("Failed to materialize development plugin module {}",
                        artifact.moduleRoot().getFileName(), e);
            }
        }
        for (PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin materialized
                : PluginDevelopmentArtifacts.dependencyOrder(materializedPlugins)) {
            try {
                loadPreparedPlugin(materialized.classesDirectory(), materialized.pf4jLoadPath(),
                        session.sessionRoot(), materialized.descriptor(), null);
            } catch (RuntimeException e) {
                failures.add(new PluginLoadFailure(materialized.descriptor().id(), describe(e)));
                log.error("Failed to load development plugin module {}",
                        materialized.moduleRoot().getFileName(), e);
            }
        }
        for (String packageId : List.copyOf(entries.keySet())) {
            try {
                startPlugin(packageId);
            } catch (RuntimeException e) {
                failures.add(new PluginLoadFailure(packageId, describe(e)));
                log.error("Failed to start plugin package {}", packageId, e);
            }
        }
        return cache(buildStatus(developmentRoot, failures));
    }

    private static List<PluginLoadFailure> developmentSourceFailures(
            PluginDevelopmentArtifacts.DevelopmentDiscovery discovery) {
        if (discovery.sourceOnlyModules().isEmpty()) {
            return List.of();
        }
        return discovery.sourceOnlyModules().stream()
                .map(module -> new PluginLoadFailure(module.pluginId(),
                        "development plugin module has plugin.properties in source resources but no compiled "
                                + "target/classes/plugin.properties: " + module.moduleRoot()))
                .toList();
    }

    private static void printDevelopmentModeBanner(Path productionDirectory,
                                                   PluginDevelopmentArtifacts.DevelopmentDiscovery discovery) {
        redLine("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        redLine("PIXIVDOWNLOAD PLUGIN DEVELOPMENT MODE ENABLED");
        redLine("The plugins directory is ignored: " + productionDirectory);
        redLine("Development root: " + discovery.developmentRoot());
        redLine("Development cache: " + discovery.cacheRoot());
        redLine("Compiled plugin modules: " + discovery.artifacts().size()
                + displayModules(discovery.artifacts().stream()
                .map(artifact -> artifact.moduleRoot().getFileName().toString()).toList()));
        if (!discovery.sourceOnlyModules().isEmpty()) {
            redLine("Source plugin modules without target/classes output: "
                    + displayModules(discovery.sourceOnlyModules().stream()
                    .map(module -> module.moduleRoot().getFileName().toString()).toList()));
            redLine("Compile these modules before launching; otherwise required plugins may keep recovery mode active.");
        }
        redLine("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
    }

    private static String displayModules(List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return " (none)";
        }
        return " [" + String.join(", ", modules) + "]";
    }

    private static void redLine(String message) {
        System.err.println(ANSI_RED_BOLD + message + ANSI_RESET);
    }

    private LoadedPluginPackage loadPreparedPlugin(Path artifactPath, Path pf4jLoadPath, Path pluginManagerRoot,
                                                    PluginDescriptor packageDescriptor,
                                                    PluginArtifactSnapshot productionSnapshot) {
        if (entries.containsKey(packageDescriptor.id())) {
            closeProductionSnapshot(productionSnapshot);
            throw new PluginRuntimeOperationException("plugin package already loaded: " + packageDescriptor.id());
        }
        try {
            ensureManager(pluginManagerRoot);
        } catch (Throwable failure) {
            closeProductionSnapshot(productionSnapshot);
            throw operationFailure("failed to initialize plugin runtime before loading " + artifactPath, failure);
        }
        Set<String> wrappersBeforeLoad;
        try {
            wrappersBeforeLoad = loadedWrapperIds();
        } catch (Throwable failure) {
            closeProductionSnapshot(productionSnapshot);
            throw operationFailure("failed to inspect plugin runtime before loading " + artifactPath, failure);
        }
        String packageId;
        try {
            packageId = pluginManager.loadPlugin(pf4jLoadPath);
        } catch (Throwable failure) {
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw operationFailure("failed to load plugin artifact " + artifactPath, failure);
        }
        if (packageId == null || packageId.isBlank()) {
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "PF4J returned no package id for " + artifactPath);
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw failure;
        }
        if (entries.containsKey(packageId)) {
            // 不得在重复加载分支调用 unloadPlugin：PF4J 返回的 id 可能指向原有 wrapper，
            // 此时卸载会错误释放仍在服务的旧 generation。
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "plugin package already loaded: " + packageId);
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw failure;
        }
        PluginWrapper wrapper;
        try {
            wrapper = pluginManager.getPlugin(packageId);
        } catch (Throwable failure) {
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw operationFailure("failed to inspect loaded plugin package " + packageId, failure);
        }
        if (wrapper == null) {
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "PF4J did not retain loaded package: " + packageId);
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw failure;
        }
        String version;
        try {
            version = wrapper.getDescriptor().getVersion();
        } catch (Throwable failure) {
            cleanupNewWrappers(wrappersBeforeLoad, failure,
                    artifactPath, pf4jLoadPath, packageDescriptor, productionSnapshot);
            throw operationFailure("failed to inspect loaded plugin descriptor " + packageId, failure);
        }
        long generation = generations.merge(packageId, 1L, Long::sum);
        RuntimeEntry entry = new RuntimeEntry(packageId,
                artifactPath.toAbsolutePath().normalize(), pf4jLoadPath.toAbsolutePath().normalize(),
                version, generation, PluginRuntimePackagePhase.LOADED,
                packageDescriptor, productionSnapshot);
        entries.put(packageId, entry);
        try {
            LoadedPluginPackage loaded = snapshot(entry, true);
            entry.descriptor = validateReleaseShape(loaded);
            refreshStatus();
            return loaded;
        } catch (Throwable failure) {
            boolean released = false;
            try {
                released = pluginManager.unloadPlugin(packageId);
            } catch (Throwable cleanupFailure) {
                addSuppressedSafely(failure, cleanupFailure);
            }
            try {
                if (pluginManager.getPlugin(packageId) == null) {
                    RuntimeEntry removed = entries.remove(packageId);
                    if (released) {
                        releaseProductionSnapshot(removed);
                    } else {
                        retainUnconfirmedProductionSnapshot(removed);
                    }
                }
            } catch (Throwable inspectionFailure) {
                addSuppressedSafely(failure, inspectionFailure);
            }
            refreshStatusSafely(failure);
            throw operationFailure("failed to validate loaded plugin package " + packageId, failure);
        }
    }

    /** 启动 PF4J 插件入口，并返回 load 准入时固化的本代功能插件与 Spring 模块快照。 */
    public synchronized LoadedPluginPackage startPlugin(String packageId) {
        RuntimeEntry entry = requireEntry(packageId);
        if (entry.phase == PluginRuntimePackagePhase.STARTED) {
            try {
                return snapshot(entry, true);
            } catch (Throwable failure) {
                throw operationFailure("failed to inspect started plugin package " + packageId, failure);
            }
        }
        PluginRuntimePackagePhase previousPhase = entry.phase;
        PluginState result;
        try {
            result = pluginManager.startPlugin(packageId);
        } catch (Throwable failure) {
            reconcileEntryWithWrapper(entry, previousPhase, failure);
            refreshStatusSafely(failure);
            throw operationFailure("failed to start plugin package " + packageId, failure);
        }
        if (result != PluginState.STARTED) {
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "PF4J did not start plugin package " + packageId + " (state=" + result + ")");
            reconcileEntryWithWrapper(entry, previousPhase, failure);
            refreshStatusSafely(failure);
            throw failure;
        }
        entry.phase = PluginRuntimePackagePhase.STARTED;
        refreshStatus();
        try {
            return snapshot(entry, true);
        } catch (Throwable failure) {
            throw operationFailure("failed to inspect started plugin package " + packageId, failure);
        }
    }

    /** 停止 PF4J 插件入口但保留 wrapper/classloader。 */
    public synchronized LoadedPluginPackage stopPlugin(String packageId) {
        RuntimeEntry entry = requireEntry(packageId);
        if (entry.phase != PluginRuntimePackagePhase.STARTED) {
            try {
                return snapshot(entry, false);
            } catch (Throwable failure) {
                throw operationFailure("failed to inspect stopped plugin package " + packageId, failure);
            }
        }
        PluginState result;
        try {
            result = pluginManager.stopPlugin(packageId);
        } catch (Throwable failure) {
            reconcileEntryWithWrapper(entry, PluginRuntimePackagePhase.STOPPED, failure);
            refreshStatusSafely(failure);
            throw operationFailure("failed to stop plugin package " + packageId, failure);
        }
        if (result == PluginState.STARTED) {
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "PF4J left plugin package started: " + packageId);
            reconcileEntryWithWrapper(entry, PluginRuntimePackagePhase.STOPPED, failure);
            refreshStatusSafely(failure);
            throw failure;
        }
        entry.phase = PluginRuntimePackagePhase.STOPPED;
        refreshStatus();
        return snapshot(entry, false);
    }

    /**
     * 物理卸载并关闭 classloader。存在已加载的非可选反向依赖时拒绝，避免 PF4J 隐式级联卸载。
     */
    public synchronized UnloadedPluginPackage unloadPlugin(String packageId) {
        RuntimeEntry entry = requireEntry(packageId);
        List<String> dependents = activeDependents(packageId);
        if (!dependents.isEmpty()) {
            throw new PluginRuntimeOperationException("plugin package " + packageId
                    + " is required by loaded package(s): " + String.join(", ", dependents));
        }
        if (entry.phase == PluginRuntimePackagePhase.STARTED) {
            stopPlugin(packageId);
        }
        boolean unloaded;
        try {
            unloaded = pluginManager.unloadPlugin(packageId);
        } catch (Throwable failure) {
            // 某些 PF4J 实现会先移除 wrapper、再在关闭 classloader 时抛异常。此时旧句柄已经
            // 不可恢复，必须同步删除本地 entry；调用方仍会收到失败并据此报告 JAR 未确认可替换。
            reconcileEntryWithWrapper(entry, PluginRuntimePackagePhase.STOPPED, failure);
            refreshStatusSafely(failure);
            throw operationFailure("failed to unload plugin package " + packageId, failure);
        }
        boolean wrapperPresent;
        try {
            wrapperPresent = pluginManager.getPlugin(packageId) != null;
        } catch (Throwable failure) {
            reconcileEntryWithWrapper(entry, PluginRuntimePackagePhase.STOPPED, failure);
            refreshStatusSafely(failure);
            throw operationFailure("failed to verify unloaded plugin package " + packageId, failure);
        }
        if (!unloaded || wrapperPresent) {
            PluginRuntimeOperationException failure = new PluginRuntimeOperationException(
                    "PF4J did not unload plugin package " + packageId);
            reconcileEntryWithWrapper(entry, PluginRuntimePackagePhase.STOPPED, failure);
            refreshStatusSafely(failure);
            throw failure;
        }
        RuntimeEntry removed = entries.remove(packageId);
        releaseProductionSnapshot(removed);
        refreshStatus();
        return new UnloadedPluginPackage(entry.packageId, entry.artifactPath, entry.version, entry.generation);
    }

    /** 当前已加载包的纯值阶段快照。 */
    public synchronized Map<String, PluginRuntimePackagePhase> packagePhases() {
        Map<String, PluginRuntimePackagePhase> result = new LinkedHashMap<>();
        entries.forEach((id, entry) -> result.put(id, entry.phase));
        return Map.copyOf(result);
    }

    public synchronized Optional<Long> generation(String packageId) {
        RuntimeEntry entry = entries.get(packageId);
        return entry == null ? Optional.empty() : Optional.of(entry.generation);
    }

    public synchronized Optional<Path> artifactPath(String packageId) {
        RuntimeEntry entry = entries.get(packageId);
        return entry == null ? Optional.empty() : Optional.of(entry.artifactPath);
    }

    /** 当前已加载 generation 的纯值描述符；停止服务不移除，物理卸载时随 runtime entry 一并释放。 */
    public synchronized Optional<PluginDescriptor> loadedDescriptor(String packageId) {
        RuntimeEntry entry = entries.get(packageId);
        return entry == null ? Optional.empty() : Optional.of(entry.descriptor);
    }

    /** 全部已加载 generation 的纯值描述符快照，包含 LOADED / STARTED / STOPPED。 */
    public synchronized Map<String, PluginDescriptor> loadedDescriptors() {
        Map<String, PluginDescriptor> result = new LinkedHashMap<>();
        entries.forEach((id, entry) -> result.put(id, entry.descriptor));
        return Map.copyOf(result);
    }

    /** 当前已加载的非可选反向依赖包。 */
    public synchronized List<String> activeDependents(String packageId) {
        if (pluginManager == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (PluginWrapper wrapper : pluginManager.getPlugins()) {
            if (wrapper.getPluginId().equals(packageId)) {
                continue;
            }
            for (PluginDependency dependency : wrapper.getDescriptor().getDependencies()) {
                if (!dependency.isOptional() && packageId.equals(dependency.getPluginId())) {
                    result.add(wrapper.getPluginId());
                }
            }
        }
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    public Optional<PluginRuntimeStatus> status() {
        return Optional.ofNullable(status);
    }

    /** 仅供 runtime 内发现桥接和既有测试观测；app 不应消费 PF4J 类型。 */
    public Optional<PluginManager> pluginManager() {
        return Optional.ofNullable(pluginManager);
    }

    /** 汇总当前 STARTED generation 在 load 准入时固化的 provider 快照，不重新调用插件 getter。 */
    public synchronized PluginInventory inspectPlugins() {
        if (pluginManager == null) {
            return PluginInventory.empty();
        }
        List<PluginInstallation> installations = new ArrayList<>();
        List<PluginContextModule> contextModules = new ArrayList<>();
        List<PluginLoadFailure> failures = new ArrayList<>();
        for (RuntimeEntry entry : entries.values()) {
            if (entry.phase != PluginRuntimePackagePhase.STARTED) {
                continue;
            }
            PluginInventory captured = contributionSnapshot(entry);
            installations.addAll(captured.installations());
            contextModules.addAll(captured.contextModules());
            failures.addAll(captured.failures());
        }
        return new PluginInventory(installations, contextModules, failures);
    }

    public synchronized PluginDiscoveryResult discoverFeaturePlugins() {
        return toDiscoveryResult(inspectPlugins());
    }

    /**
     * 从既有 inventory 投影发现结果（不重新清点 / 不再次调用 provider），仅把当前 generation 盖到每条 discovered 上。
     * 供 bootstrap 会话在启动期同一次清点内同时产出 inventory 与 discovery，避免对 provider 重复调用。
     */
    public synchronized PluginDiscoveryResult toDiscoveryResult(PluginInventory inventory) {
        PluginInventory source = inventory == null ? PluginInventory.empty() : inventory;
        PluginDiscoveryResult raw = source.toDiscoveryResult();
        List<DiscoveredFeaturePlugin> discovered = raw.discovered().stream()
                .map(item -> new DiscoveredFeaturePlugin(item.sourcePluginId(),
                        item.featurePluginId(), generation(item.sourcePluginId()).orElse(0L),
                        item.plugin(), item.classLoader()))
                .toList();
        return new PluginDiscoveryResult(discovered, raw.failures());
    }

    public synchronized List<PluginContextModule> inspectContextModules() {
        return inspectPlugins().contextModules();
    }

    /** 当前所有 STARTED 包的代际快照。 */
    public synchronized List<LoadedPluginPackage> startedPackages() {
        return entries.values().stream()
                .filter(entry -> entry.phase == PluginRuntimePackagePhase.STARTED)
                .sorted(Comparator.comparing(entry -> entry.packageId))
                .map(entry -> snapshot(entry, true))
                .toList();
    }

    /**
     * 进程级关闭：停止全部已启动插件、卸载全部插件、释放 PF4J classloader / 文件句柄、清空内部 entry / status / generation
     * 引用。多次调用安全（幂等）；批量 stop / unload 各自 best-effort——任一抛错只记日志、不影响另一批清退，不致核心退出失败。
     * 供唯一 bootstrap session 在进程最终退出（PROCESS）或 context 销毁（CONTEXT）时统一关闭运行时；禁止 app 侧经
     * {@link #pluginManager()} 直接操作 PF4J。不抛异常、不吞掉 JVM 致命 Error。
     */
    public synchronized void shutdown() {
        PluginManager previous = pluginManager;
        PluginDevelopmentArtifacts.DevelopmentCacheSession previousDevelopmentSession = developmentCacheSession;
        if (previous == null && entries.isEmpty() && previousDevelopmentSession == null
                && unconfirmedProductionSnapshots.isEmpty()) {
            // 已关闭（或从未扫描）：清空残余引用即返回，幂等。
            generations.clear();
            status = null;
            return;
        }
        List<RuntimeEntry> previousEntries = List.copyOf(entries.values());
        List<PluginArtifactSnapshot> previousUnconfirmedSnapshots =
                List.copyOf(unconfirmedProductionSnapshots);
        pluginManager = null;
        developmentCacheSession = null;
        entries.clear();
        generations.clear();
        status = null;
        boolean released = previous == null || bestEffortStopAndUnload(previous, "shutdown");
        unconfirmedProductionSnapshots.clear();
        closeProductionSnapshots(previousEntries, previousUnconfirmedSnapshots, released, "shutdown");
        closeDevelopmentCacheSession(previousDevelopmentSession, released, "shutdown");
    }

    public Path pluginsRoot() {
        return pluginsRoot;
    }

    /** Bootstrap-owned managers override this hook to establish the directory recovery gate. */
    protected void beforeProductionScan(Path directory) throws IOException {
        // The default manager has no bootstrap session ownership.
    }

    private LoadedPluginPackage snapshot(RuntimeEntry entry, boolean includeContributions) {
        PluginInventory inventory = PluginInventory.empty();
        List<PluginContextModule> modules = List.of();
        if (includeContributions && pluginManager != null) {
            inventory = contributionSnapshot(entry);
            modules = inventory.contextModules();
        }
        return new LoadedPluginPackage(entry.packageId, entry.artifactPath, entry.version, entry.generation,
                entry.phase, inventory, modules);
    }

    /**
     * 每个物理 generation 恰好读取一次 provider 的 feature/configuration 声明并固化为宿主快照。
     * load、start、Spring 接入与状态查询只复用该快照，禁止状态化 getter 改变已验证身份或制造半份装配。
     */
    private PluginInventory contributionSnapshot(RuntimeEntry entry) {
        if (entry.contributionSnapshot == null) {
            PixivPluginDiscoveryBridge bridge = new PixivPluginDiscoveryBridge();
            entry.contributionSnapshot = attachPackageMetadata(
                    bridge.inspectLoadedPackage(pluginManager, entry.packageId));
        }
        return entry.contributionSnapshot;
    }

    /**
     * 发现桥接从运行期插件实例重建功能元数据；包级替代关系与生命周期策略只存在于清单，
     * 因此按当前 runtime entry 重新附着，确保 load/start 后仍保留已验签的包元数据。
     */
    private PluginInventory attachPackageMetadata(PluginInventory inventory) {
        List<PluginInstallation> installations = inventory.installations().stream()
                .map(installation -> {
                    RuntimeEntry entry = entries.get(installation.descriptor().sourcePluginId());
                    if (entry == null) {
                        return installation;
                    }
                    PluginDescriptor descriptor = installation.descriptor()
                            .withPackageMetadataFrom(entry.descriptor);
                    return new PluginInstallation(descriptor, installation.status(), installation.classLoader(),
                            installation.plugin());
                })
                .toList();
        return new PluginInventory(installations, inventory.contextModules(), inventory.failures());
    }

    /** 当前发布格式要求物理包与唯一功能插件同 id，并至多声明一个 Spring 模块。 */
    private static PluginDescriptor validateReleaseShape(LoadedPluginPackage loaded) {
        List<PluginInstallation> registrable = loaded.inventory().installations().stream()
                .filter(PluginInstallation::registrable)
                .toList();
        if (registrable.size() != 1) {
            throw new PluginRuntimeOperationException("external package " + loaded.packageId()
                    + " must expose exactly one registrable feature plugin");
        }
        PluginDescriptor featureDescriptor = registrable.get(0).descriptor();
        if (!loaded.packageId().equals(featureDescriptor.id())) {
            throw new PluginRuntimeOperationException("external package " + loaded.packageId()
                    + " must contribute a feature plugin with the same id");
        }
        if (loaded.contextModules().size() > 1) {
            throw new PluginRuntimeOperationException("external package " + loaded.packageId()
                    + " declared multiple context modules");
        }
        if (!loaded.contextModules().isEmpty()
                && !loaded.packageId().equals(loaded.contextModules().get(0).sourcePluginId())) {
            throw new PluginRuntimeOperationException("external package " + loaded.packageId()
                    + " declared a context module for another package");
        }
        return featureDescriptor;
    }

    private void ensureManager() {
        ensureManager(pluginsRoot);
    }

    private void ensureManager(Path root) {
        if (pluginManager == null) {
            pluginManager = new DefaultPluginManager(root);
        }
    }

    private PreparedProductionArtifact prepareProductionArtifact(Path artifactPath) {
        Path attemptedPath = Objects.requireNonNull(artifactPath, "artifactPath")
                .toAbsolutePath().normalize();
        PluginArtifactSnapshot snapshot = PluginArtifactSnapshot.create(
                layout, attemptedPath, PRODUCTION_PACKAGE_LIMITS.maxArchiveBytes());
        try {
            Path frozenArtifact = snapshot.snapshotArtifact();
            PluginPackageVerifier.verify(frozenArtifact, PRODUCTION_PACKAGE_LIMITS);
            PluginPackageInspection inspection = PluginPackageReader.inspect(
                    frozenArtifact, PRODUCTION_PACKAGE_LIMITS);
            if (inspection.innerJarEntry() != null) {
                throw new PluginRuntimeOperationException(
                        "installed plugin package must be canonical and cannot contain an inner plugin jar: "
                                + snapshot.originalArtifact());
            }
            PluginProvenanceRecord provenance = provenanceStore.read(snapshot.originalArtifact()).orElse(null);
            VerificationResult result = verificationService.verifyInstalled(
                    frozenArtifact, inspection.descriptor(), provenance);
            try {
                if (provenance != null) {
                    provenanceStore.write(snapshot.originalArtifact(), provenance.withOfflineResult(
                            result, inspection.descriptor().id(), inspection.descriptor().version()));
                }
            } catch (IOException e) {
                log.warn("Failed to persist plugin verification provenance for {}: {}",
                        snapshot.originalArtifact().getFileName(), e.toString());
            }
            if (!result.accepted()) {
                throw new PluginRuntimeOperationException(
                        "plugin verification failed before load: " + result.status());
            }
            return new PreparedProductionArtifact(snapshot, inspection, result.sha256());
        } catch (Throwable failure) {
            snapshot.close();
            rethrowFatal(failure);
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new PluginRuntimeOperationException(
                    "failed to prepare plugin artifact " + snapshot.originalArtifact(), failure);
        }
    }

    private LoadedPluginPackage loadPreparedProductionArtifact(PreparedProductionArtifact prepared) {
        if (entries.containsKey(prepared.inspection().descriptor().id())) {
            throw new PluginRuntimeOperationException(
                    "plugin package already loaded: " + prepared.inspection().descriptor().id());
        }
        PluginArtifactMaterializer.MaterializedPluginArtifact materialized = materializer.materialize(
                prepared.snapshot(), prepared.inspection(), prepared.verifiedSha256());
        PluginArtifactSnapshot ownedSnapshot = prepared.detachSnapshot();
        return loadPreparedPlugin(materialized.originalArtifactPath(), materialized.pf4jLoadPath(), pluginsRoot,
                prepared.inspection().descriptor(), ownedSnapshot);
    }

    private RuntimeEntry requireEntry(String packageId) {
        RuntimeEntry entry = entries.get(packageId);
        if (entry == null || pluginManager == null) {
            throw new PluginRuntimeOperationException("plugin package is not loaded: " + packageId);
        }
        return entry;
    }

    private PluginRuntimeStatus buildStatus(Path directory, List<PluginLoadFailure> failures) {
        List<String> loaded = List.copyOf(entries.keySet());
        List<String> started = entries.values().stream()
                .filter(entry -> entry.phase == PluginRuntimePackagePhase.STARTED)
                .map(entry -> entry.packageId).toList();
        return new PluginRuntimeStatus(directory, PluginDirectoryState.POPULATED, loaded, started, failures);
    }

    private void refreshStatus() {
        if (status == null && entries.isEmpty()) {
            return;
        }
        Path directory = status == null ? pluginsRoot.toAbsolutePath().normalize() : status.directory();
        PluginDirectoryState state = Files.isDirectory(directory)
                ? (entries.isEmpty() ? PluginDirectoryState.EMPTY : PluginDirectoryState.POPULATED)
                : PluginDirectoryState.ABSENT;
        this.status = new PluginRuntimeStatus(directory, state,
                List.copyOf(entries.keySet()), entries.values().stream()
                .filter(entry -> entry.phase == PluginRuntimePackagePhase.STARTED)
                .map(entry -> entry.packageId).toList(), List.of());
    }

    private synchronized void resetPluginManager() {
        PluginManager previous = pluginManager;
        PluginDevelopmentArtifacts.DevelopmentCacheSession previousDevelopmentSession = developmentCacheSession;
        List<RuntimeEntry> previousEntries = List.copyOf(entries.values());
        List<PluginArtifactSnapshot> previousUnconfirmedSnapshots =
                List.copyOf(unconfirmedProductionSnapshots);
        pluginManager = null;
        developmentCacheSession = null;
        entries.clear();
        boolean released = previous == null || bestEffortStopAndUnload(previous, "reset");
        unconfirmedProductionSnapshots.clear();
        closeProductionSnapshots(previousEntries, previousUnconfirmedSnapshots, released, "reset");
        closeDevelopmentCacheSession(previousDevelopmentSession, released, "reset");
    }

    private PluginRuntimeStatus cache(PluginRuntimeStatus value) {
        this.status = value;
        return value;
    }

    private static boolean bestEffortStopAndUnload(PluginManager manager, String action) {
        Throwable fatal = null;
        boolean releasedCleanly = true;
        try {
            manager.stopPlugins();
        } catch (Throwable failure) {
            if (isFatal(failure)) {
                fatal = failure;
            } else {
                releasedCleanly = false;
                log.warn("Error stopping plugins during runtime {}: {}", action, describe(failure));
            }
        }
        try {
            manager.unloadPlugins();
        } catch (Throwable failure) {
            if (isFatal(failure)) {
                if (fatal == null) {
                    fatal = failure;
                } else {
                    addSuppressedSafely(fatal, failure);
                }
            } else {
                releasedCleanly = false;
                log.warn("Error unloading plugins during runtime {}: {}", action, describe(failure));
            }
        }
        if (fatal != null) {
            rethrowFatal(fatal);
        }
        try {
            if (!manager.getPlugins().isEmpty()) {
                releasedCleanly = false;
                log.warn("Plugin wrappers remain after runtime {}; development cache session will be retained",
                        action);
            }
        } catch (Throwable failure) {
            rethrowFatal(failure);
            releasedCleanly = false;
            log.warn("Failed to verify plugin wrapper release during runtime {}: {}", action, describe(failure));
        }
        return releasedCleanly;
    }

    private PluginDevelopmentArtifacts.DevelopmentCacheSession ensureDevelopmentCacheSession(Path cacheRoot) {
        Path normalizedCacheRoot = cacheRoot.toAbsolutePath().normalize();
        PluginDevelopmentArtifacts.DevelopmentCacheSession current = developmentCacheSession;
        if (current != null) {
            if (!current.cacheRoot().equals(normalizedCacheRoot)) {
                throw new PluginRuntimeOperationException("plugin development cache root changed while active: "
                        + current.cacheRoot() + " -> " + normalizedCacheRoot);
            }
            return current;
        }
        if (pluginManager != null) {
            throw new PluginRuntimeOperationException(
                    "cannot open plugin development cache session after PF4J manager initialization");
        }
        PluginDevelopmentArtifacts.DevelopmentCacheSession opened =
                PluginDevelopmentArtifacts.openSession(normalizedCacheRoot);
        developmentCacheSession = opened;
        return opened;
    }

    private static void closeDevelopmentCacheSession(
            PluginDevelopmentArtifacts.DevelopmentCacheSession session, boolean runtimeReleased, String action) {
        if (session == null) {
            return;
        }
        if (!runtimeReleased) {
            log.warn("Retaining plugin development cache session {} because runtime {} did not release cleanly",
                    session.sessionRoot(), action);
            return;
        }
        try {
            session.close();
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to clean plugin development cache session {} during {}: {}",
                    session.sessionRoot(), action, e.toString());
        }
    }

    private static void closeProductionSnapshots(
            List<RuntimeEntry> previousEntries,
            List<PluginArtifactSnapshot> unconfirmedSnapshots,
            boolean runtimeReleased,
            String action) {
        Set<PluginArtifactSnapshot> unconfirmed = Collections.newSetFromMap(new IdentityHashMap<>());
        unconfirmed.addAll(unconfirmedSnapshots);
        Set<PluginArtifactSnapshot> releasable = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RuntimeEntry entry : previousEntries) {
            if (entry.productionSnapshot != null && !unconfirmed.contains(entry.productionSnapshot)) {
                releasable.add(entry.productionSnapshot);
            }
        }
        if (!unconfirmed.isEmpty()) {
            log.warn("Retaining {} plugin artifact workspace(s) because their classloader release is unconfirmed",
                    unconfirmed.size());
        }
        if (releasable.isEmpty()) {
            return;
        }
        if (!runtimeReleased) {
            log.warn("Retaining {} plugin artifact workspace(s) because runtime {} did not release cleanly",
                    releasable.size(), action);
            return;
        }
        releasable.forEach(PluginRuntimeManager::closeProductionSnapshot);
    }

    private void releaseProductionSnapshot(RuntimeEntry removedEntry) {
        if (removedEntry == null || removedEntry.productionSnapshot == null) {
            return;
        }
        PluginArtifactSnapshot snapshot = removedEntry.productionSnapshot;
        if (unconfirmedProductionSnapshots.contains(snapshot)) {
            return;
        }
        // 一个失败 load 可能留下多个 wrapper；它们共享同一 snapshot，只有最后一个 entry 移除后才关闭。
        boolean stillReferenced = entries.values().stream()
                .anyMatch(entry -> entry.productionSnapshot == snapshot);
        if (!stillReferenced) {
            closeProductionSnapshot(snapshot);
        }
    }

    private void retainUnconfirmedProductionSnapshot(RuntimeEntry removedEntry) {
        if (removedEntry != null) {
            retainUnconfirmedProductionSnapshot(removedEntry.productionSnapshot);
        }
    }

    private void retainUnconfirmedProductionSnapshot(PluginArtifactSnapshot snapshot) {
        if (snapshot != null) {
            unconfirmedProductionSnapshots.add(snapshot);
        }
    }

    private static void closeProductionSnapshot(PluginArtifactSnapshot snapshot) {
        if (snapshot != null) {
            snapshot.close();
        }
    }

    private Set<String> loadedWrapperIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (PluginWrapper wrapper : pluginManager.getPlugins()) {
            ids.add(wrapper.getPluginId());
        }
        return ids;
    }

    /** load 原语抛错时只清理由本次调用新增的 wrapper，绝不触碰先前在场的同 id 代际。 */
    private void cleanupNewWrappers(
            Set<String> wrappersBeforeLoad,
            Throwable primaryFailure,
            Path artifactPath,
            Path pf4jLoadPath,
            PluginDescriptor packageDescriptor,
            PluginArtifactSnapshot productionSnapshot) {
        Set<String> current;
        try {
            current = loadedWrapperIds();
        } catch (Throwable inspectionFailure) {
            addSuppressedSafely(primaryFailure, inspectionFailure);
            retainUnconfirmedProductionSnapshot(productionSnapshot);
            return;
        }
        current.removeAll(wrappersBeforeLoad);
        boolean observedNewWrapper = !current.isEmpty();
        boolean workspaceMayStillBeReferenced = false;
        for (String pluginId : current) {
            boolean unloaded = false;
            try {
                unloaded = pluginManager.unloadPlugin(pluginId);
            } catch (Throwable cleanupFailure) {
                addSuppressedSafely(primaryFailure, cleanupFailure);
            }
            PluginWrapper remaining = null;
            boolean remainingInspected = false;
            try {
                remaining = pluginManager.getPlugin(pluginId);
                remainingInspected = true;
            } catch (Throwable inspectionFailure) {
                addSuppressedSafely(primaryFailure, inspectionFailure);
                workspaceMayStillBeReferenced = true;
                retainUnconfirmedProductionSnapshot(productionSnapshot);
            }
            if (!unloaded || remaining != null) {
                addSuppressedSafely(primaryFailure, new PluginRuntimeOperationException(
                        "PF4J retained wrapper after failed load cleanup: " + pluginId));
            }
            if (remaining != null) {
                workspaceMayStillBeReferenced = true;
                retainResidualWrapper(pluginId, remaining, artifactPath, pf4jLoadPath, packageDescriptor,
                        productionSnapshot, primaryFailure);
            } else if (remainingInspected && !unloaded) {
                // wrapper 已从 manager 消失但 unload 未确认成功，无法证明 classloader 已释放。
                workspaceMayStillBeReferenced = true;
                retainUnconfirmedProductionSnapshot(productionSnapshot);
            }
        }
        if (!workspaceMayStillBeReferenced) {
            if (observedNewWrapper) {
                closeProductionSnapshot(productionSnapshot);
            } else {
                // PF4J 可在创建 classloader 后、注册 wrapper 前抛错；看不到新增 wrapper 不能证明句柄已释放。
                retainUnconfirmedProductionSnapshot(productionSnapshot);
            }
        }
        refreshStatusSafely(primaryFailure);
    }

    private void retainResidualWrapper(
            String pluginId,
            PluginWrapper wrapper,
            Path artifactPath,
            Path pf4jLoadPath,
            PluginDescriptor packageDescriptor,
            PluginArtifactSnapshot productionSnapshot,
            Throwable primaryFailure) {
        if (entries.containsKey(pluginId)) {
            retainUnconfirmedProductionSnapshot(productionSnapshot);
            return;
        }
        try {
            long generation = generations.merge(pluginId, 1L, Long::sum);
            PluginRuntimePackagePhase phase = wrapper.getPluginState() == PluginState.STARTED
                    ? PluginRuntimePackagePhase.STARTED : PluginRuntimePackagePhase.LOADED;
            entries.put(pluginId, new RuntimeEntry(
                    pluginId,
                    artifactPath.toAbsolutePath().normalize(),
                    pf4jLoadPath.toAbsolutePath().normalize(),
                    wrapper.getDescriptor().getVersion(),
                    generation,
                    phase,
                    packageDescriptor,
                    productionSnapshot));
        } catch (Throwable retentionFailure) {
            addSuppressedSafely(primaryFailure, retentionFailure);
            retainUnconfirmedProductionSnapshot(productionSnapshot);
        }
    }

    /** PF4J 可能先改变 wrapper 状态再抛错；错误边界前把本地 entry 对齐到可观测事实。 */
    private void reconcileEntryWithWrapper(
            RuntimeEntry entry, PluginRuntimePackagePhase nonStartedPhase, Throwable primaryFailure) {
        try {
            PluginWrapper wrapper = pluginManager.getPlugin(entry.packageId);
            if (wrapper == null) {
                RuntimeEntry removed = entries.remove(entry.packageId);
                // 本方法只在 PF4J 原语抛错或返回异常状态时调用；wrapper 消失不等于 classloader 已释放。
                retainUnconfirmedProductionSnapshot(removed);
                return;
            }
            entry.phase = wrapper.getPluginState() == PluginState.STARTED
                    ? PluginRuntimePackagePhase.STARTED : nonStartedPhase;
        } catch (Throwable inspectionFailure) {
            addSuppressedSafely(primaryFailure, inspectionFailure);
        }
    }

    private void refreshStatusSafely(Throwable primaryFailure) {
        try {
            refreshStatus();
        } catch (Throwable refreshFailure) {
            addSuppressedSafely(primaryFailure, refreshFailure);
        }
    }

    private static PluginRuntimeOperationException operationFailure(String message, Throwable failure) {
        rethrowFatal(failure);
        return new PluginRuntimeOperationException(message, failure);
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void addSuppressedSafely(Throwable target, Throwable suppressed) {
        if (target == null || suppressed == null || target == suppressed) {
            return;
        }
        try {
            target.addSuppressed(suppressed);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败。
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getName() : error.getMessage();
    }

    private static Function<PluginPackageOrigin, PluginSupplyChainVerifier> fixedVerifier(
            PluginSupplyChainVerifier verifier) {
        PluginSupplyChainVerifier fixed = Objects.requireNonNull(verifier, "verifier");
        return origin -> fixed;
    }

    private static final class PreparedProductionArtifact implements AutoCloseable {
        private PluginArtifactSnapshot snapshot;
        private final PluginPackageInspection inspection;
        private final String verifiedSha256;

        private PreparedProductionArtifact(PluginArtifactSnapshot snapshot,
                                           PluginPackageInspection inspection,
                                           String verifiedSha256) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.inspection = Objects.requireNonNull(inspection, "inspection");
            this.verifiedSha256 = Objects.requireNonNull(verifiedSha256, "verifiedSha256");
        }

        private Path originalArtifact() {
            return snapshot().originalArtifact();
        }

        private PluginPackageInspection inspection() {
            return inspection;
        }

        private String verifiedSha256() {
            return verifiedSha256;
        }

        private PluginArtifactSnapshot snapshot() {
            if (snapshot == null) {
                throw new IllegalStateException("prepared plugin artifact ownership was already transferred");
            }
            return snapshot;
        }

        private PluginArtifactLoadPlan.Entry loadPlanEntry() {
            return new PluginArtifactLoadPlan.Entry(originalArtifact(), inspection.descriptor());
        }

        private PluginArtifactSnapshot detachSnapshot() {
            PluginArtifactSnapshot detached = snapshot();
            snapshot = null;
            return detached;
        }

        @Override
        public void close() {
            PluginArtifactSnapshot current = snapshot;
            snapshot = null;
            closeProductionSnapshot(current);
        }
    }

    private static final class RuntimeEntry {
        private final String packageId;
        private final Path artifactPath;
        private final Path pf4jLoadPath;
        private final String version;
        private final long generation;
        private PluginRuntimePackagePhase phase;
        private PluginDescriptor descriptor;
        private PluginInventory contributionSnapshot;
        private final PluginArtifactSnapshot productionSnapshot;

        private RuntimeEntry(String packageId, Path artifactPath, Path pf4jLoadPath, String version,
                             long generation, PluginRuntimePackagePhase phase, PluginDescriptor descriptor,
                             PluginArtifactSnapshot productionSnapshot) {
            this.packageId = packageId;
            this.artifactPath = artifactPath;
            this.pf4jLoadPath = pf4jLoadPath;
            this.version = version;
            this.generation = generation;
            this.phase = phase;
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.productionSnapshot = productionSnapshot;
        }
    }
}
