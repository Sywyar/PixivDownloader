package top.sywyar.pixivdownload.plugin.runtime;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginDependency;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactMaterializer;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginRuntimeLayout;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginArtifactVerificationService;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
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

    private final Path pluginsRoot;
    private final PluginRuntimeLayout layout;
    private final PluginArtifactMaterializer materializer;
    private PluginArtifactVerificationService verificationService;
    private Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver;
    private final PluginProvenanceStore provenanceStore;
    private final Map<String, RuntimeEntry> entries = new LinkedHashMap<>();
    private final Map<String, Long> generations = new LinkedHashMap<>();

    private volatile PluginManager pluginManager;
    private volatile PluginRuntimeStatus status;

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
            return startDevelopmentMode(directory);
        }

        if (!Files.isDirectory(pluginsRoot)) {
            PluginDirectoryState state = PluginDirectoryState.ABSENT;
            return cache(new PluginRuntimeStatus(directory, state, List.of(), List.of(), List.of()));
        }

        List<Path> candidates;
        try {
            candidates = findCandidatePackages(pluginsRoot);
        } catch (IOException e) {
            return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.EMPTY,
                    List.of(), List.of(), List.of(new PluginLoadFailure(directory.toString(), describe(e)))));
        }
        if (candidates.isEmpty()) {
            return cache(new PluginRuntimeStatus(directory, PluginDirectoryState.EMPTY,
                    List.of(), List.of(), List.of()));
        }

        ensureManager();
        List<PluginLoadFailure> failures = new ArrayList<>();
        for (Path candidate : candidates) {
            try {
                loadPlugin(candidate);
            } catch (RuntimeException e) {
                failures.add(new PluginLoadFailure(candidate.getFileName().toString(), describe(e)));
                log.error("Failed to load plugin package {}: {}", candidate.getFileName(), describe(e));
            }
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
        if (artifactPath == null || !Files.isRegularFile(artifactPath)) {
            throw new PluginRuntimeOperationException("plugin artifact not found: " + artifactPath);
        }
        PluginPackageInspection inspection = verifyBeforeLoad(artifactPath);
        PluginArtifactMaterializer.MaterializedPluginArtifact materialized =
                materializer.materialize(artifactPath, inspection);
        return loadPreparedPlugin(materialized.originalArtifactPath(), materialized.pf4jLoadPath(), pluginsRoot);
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

        List<PluginDevelopmentArtifacts.MaterializedDevelopmentPlugin> materializedPlugins = new ArrayList<>();
        for (PluginDevelopmentArtifacts.DevelopmentPluginArtifact artifact : discovery.artifacts()) {
            try {
                materializedPlugins.add(PluginDevelopmentArtifacts.materialize(artifact, discovery.cacheRoot()));
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
                        discovery.cacheRoot());
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

    private LoadedPluginPackage loadPreparedPlugin(Path artifactPath, Path pf4jLoadPath, Path pluginManagerRoot) {
        ensureManager(pluginManagerRoot);
        String packageId;
        try {
            packageId = pluginManager.loadPlugin(pf4jLoadPath);
        } catch (RuntimeException e) {
            throw new PluginRuntimeOperationException("failed to load plugin artifact " + artifactPath, e);
        }
        if (packageId == null || packageId.isBlank()) {
            throw new PluginRuntimeOperationException("PF4J returned no package id for " + artifactPath);
        }
        if (entries.containsKey(packageId)) {
            // 不得在重复加载分支调用 unloadPlugin：PF4J 返回的 id 可能指向原有 wrapper，
            // 此时卸载会错误释放仍在服务的旧 generation。
            throw new PluginRuntimeOperationException("plugin package already loaded: " + packageId);
        }
        PluginWrapper wrapper = pluginManager.getPlugin(packageId);
        if (wrapper == null) {
            throw new PluginRuntimeOperationException("PF4J did not retain loaded package: " + packageId);
        }
        long generation = generations.merge(packageId, 1L, Long::sum);
        RuntimeEntry entry = new RuntimeEntry(packageId,
                artifactPath.toAbsolutePath().normalize(), pf4jLoadPath.toAbsolutePath().normalize(),
                wrapper.getDescriptor().getVersion(), generation, PluginRuntimePackagePhase.LOADED);
        entries.put(packageId, entry);
        try {
            LoadedPluginPackage loaded = snapshot(entry, true);
            validateReleaseShape(loaded);
            refreshStatus();
            return loaded;
        } catch (RuntimeException failure) {
            entries.remove(packageId);
            try {
                pluginManager.unloadPlugin(packageId);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            refreshStatus();
            throw failure;
        }
    }

    /** 启动 PF4J 插件入口，并重新发现这一代的功能插件和 Spring 模块。 */
    public synchronized LoadedPluginPackage startPlugin(String packageId) {
        RuntimeEntry entry = requireEntry(packageId);
        if (entry.phase == PluginRuntimePackagePhase.STARTED) {
            return snapshot(entry, true);
        }
        PluginState result;
        try {
            result = pluginManager.startPlugin(packageId);
        } catch (RuntimeException e) {
            throw new PluginRuntimeOperationException("failed to start plugin package " + packageId, e);
        }
        if (result != PluginState.STARTED) {
            throw new PluginRuntimeOperationException("PF4J did not start plugin package " + packageId
                    + " (state=" + result + ")");
        }
        entry.phase = PluginRuntimePackagePhase.STARTED;
        refreshStatus();
        return snapshot(entry, true);
    }

    /** 停止 PF4J 插件入口但保留 wrapper/classloader。 */
    public synchronized LoadedPluginPackage stopPlugin(String packageId) {
        RuntimeEntry entry = requireEntry(packageId);
        if (entry.phase != PluginRuntimePackagePhase.STARTED) {
            return snapshot(entry, false);
        }
        PluginState result;
        try {
            result = pluginManager.stopPlugin(packageId);
        } catch (RuntimeException e) {
            throw new PluginRuntimeOperationException("failed to stop plugin package " + packageId, e);
        }
        if (result == PluginState.STARTED) {
            throw new PluginRuntimeOperationException("PF4J left plugin package started: " + packageId);
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
        } catch (RuntimeException e) {
            // 某些 PF4J 实现会先移除 wrapper、再在关闭 classloader 时抛异常。此时旧句柄已经
            // 不可恢复，必须同步删除本地 entry；调用方仍会收到失败并据此报告 JAR 未确认可替换。
            if (pluginManager.getPlugin(packageId) == null) {
                entries.remove(packageId);
                refreshStatus();
            }
            throw new PluginRuntimeOperationException("failed to unload plugin package " + packageId, e);
        }
        if (!unloaded || pluginManager.getPlugin(packageId) != null) {
            throw new PluginRuntimeOperationException("PF4J did not unload plugin package " + packageId);
        }
        entries.remove(packageId);
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

    /** 动态清点，不缓存插件对象或 classloader。 */
    public synchronized PluginInventory inspectPlugins() {
        return pluginManager == null ? PluginInventory.empty() : new PixivPluginDiscoveryBridge().inspect(pluginManager);
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
                        generation(item.sourcePluginId()).orElse(0L), item.plugin(), item.classLoader()))
                .toList();
        return new PluginDiscoveryResult(discovered, raw.failures());
    }

    public synchronized List<PluginContextModule> inspectContextModules() {
        return pluginManager == null ? List.of()
                : new PixivPluginDiscoveryBridge().inspectContextModules(pluginManager);
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
        if (previous == null && entries.isEmpty()) {
            // 已关闭（或从未扫描）：清空残余引用即返回，幂等。
            generations.clear();
            status = null;
            return;
        }
        pluginManager = null;
        entries.clear();
        generations.clear();
        status = null;
        if (previous == null) {
            return;
        }
        try {
            previous.stopPlugins();
        } catch (RuntimeException e) {
            log.warn("Error stopping plugins during runtime shutdown: {}", describe(e));
        }
        try {
            previous.unloadPlugins();
        } catch (RuntimeException e) {
            log.warn("Error unloading plugins during runtime shutdown: {}", describe(e));
        }
    }

    public Path pluginsRoot() {
        return pluginsRoot;
    }

    private LoadedPluginPackage snapshot(RuntimeEntry entry, boolean includeContributions) {
        PluginInventory inventory = PluginInventory.empty();
        List<PluginContextModule> modules = List.of();
        if (includeContributions && pluginManager != null) {
            PixivPluginDiscoveryBridge bridge = new PixivPluginDiscoveryBridge();
            inventory = bridge.inspectLoadedPackage(pluginManager, entry.packageId);
            modules = bridge.inspectLoadedContextModules(pluginManager, entry.packageId);
        }
        return new LoadedPluginPackage(entry.packageId, entry.artifactPath, entry.version, entry.generation,
                entry.phase, inventory, modules);
    }

    /** 当前发布格式要求一个物理包只贡献一个同 id 功能插件和至多一个 Spring 模块。 */
    private static void validateReleaseShape(LoadedPluginPackage loaded) {
        List<PluginInstallation> registrable = loaded.inventory().installations().stream()
                .filter(PluginInstallation::registrable)
                .toList();
        if (registrable.size() != 1 || !loaded.packageId().equals(registrable.get(0).id())) {
            throw new PluginRuntimeOperationException("external package " + loaded.packageId()
                    + " must contribute exactly one feature plugin with the same id");
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
    }

    private void ensureManager() {
        ensureManager(pluginsRoot);
    }

    private void ensureManager(Path root) {
        if (pluginManager == null) {
            pluginManager = new DefaultPluginManager(root);
        }
    }

    private PluginPackageInspection verifyBeforeLoad(Path artifactPath) {
        var inspection = PluginPackageReader.inspect(artifactPath);
        PluginProvenanceRecord provenance = provenanceStore.read(artifactPath).orElse(null);
        VerificationResult result = verificationService.verifyInstalled(artifactPath, inspection.descriptor(),
                provenance);
        try {
            if (provenance != null) {
                provenanceStore.write(artifactPath, provenance.withOfflineResult(result));
            }
        } catch (IOException e) {
            log.warn("Failed to persist plugin verification provenance for {}: {}",
                    artifactPath.getFileName(), e.toString());
        }
        if (!result.accepted()) {
            throw new PluginRuntimeOperationException("plugin verification failed before load: " + result.status());
        }
        return inspection;
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
        pluginManager = null;
        entries.clear();
        if (previous == null) {
            return;
        }
        try {
            previous.stopPlugins();
        } catch (RuntimeException e) {
            log.warn("Error stopping plugins during runtime reset: {}", describe(e));
        }
        try {
            previous.unloadPlugins();
        } catch (RuntimeException e) {
            log.warn("Error unloading plugins during runtime reset: {}", describe(e));
        }
    }

    private PluginRuntimeStatus cache(PluginRuntimeStatus value) {
        this.status = value;
        return value;
    }

    private static List<Path> findCandidatePackages(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(PluginRuntimeManager::isCandidatePackage).sorted().toList();
        }
    }

    private static boolean isCandidatePackage(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.startsWith(".") && (name.endsWith(".jar") || name.endsWith(".zip"));
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

    private static final class RuntimeEntry {
        private final String packageId;
        private final Path artifactPath;
        private final Path pf4jLoadPath;
        private final String version;
        private final long generation;
        private PluginRuntimePackagePhase phase;

        private RuntimeEntry(String packageId, Path artifactPath, Path pf4jLoadPath, String version,
                             long generation, PluginRuntimePackagePhase phase) {
            this.packageId = packageId;
            this.artifactPath = artifactPath;
            this.pf4jLoadPath = pf4jLoadPath;
            this.version = version;
            this.generation = generation;
            this.phase = phase;
        }
    }
}
