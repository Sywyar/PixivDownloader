package top.sywyar.pixivdownload.plugin.runtime;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginDependency;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;

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
import java.util.stream.Stream;

/**
 * PF4J 外置插件物理生命周期封装。启动扫描与运行期变更共用单包 load/start/stop/unload 原语，
 * app 侧不会接触任何 PF4J 类型。
 */
public class PluginRuntimeManager {

    // 所有运行期包变更必须复用本类的单包原语，禁止在 app 侧直接操作 PF4J manager。

    private static final Logger log = LoggerFactory.getLogger(PluginRuntimeManager.class);

    private final Path pluginsRoot;
    private final Map<String, RuntimeEntry> entries = new LinkedHashMap<>();
    private final Map<String, Long> generations = new LinkedHashMap<>();

    private volatile PluginManager pluginManager;
    private volatile PluginRuntimeStatus status;

    public PluginRuntimeManager(Path pluginsRoot) {
        if (pluginsRoot == null) {
            throw new IllegalArgumentException("pluginsRoot must not be null");
        }
        this.pluginsRoot = pluginsRoot;
    }

    /** 启动扫描。每个候选包分别 load/start，单包失败不会阻止其它包。 */
    public synchronized PluginRuntimeStatus start() {
        Path directory = pluginsRoot.toAbsolutePath().normalize();
        resetPluginManager();

        if (!Files.isDirectory(pluginsRoot)) {
            PluginDirectoryState state = Files.exists(pluginsRoot)
                    ? PluginDirectoryState.ABSENT : PluginDirectoryState.ABSENT;
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
        ensureManager();
        String packageId;
        try {
            packageId = pluginManager.loadPlugin(artifactPath.toAbsolutePath().normalize());
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
                wrapper.getPluginPath().toAbsolutePath().normalize(), wrapper.getDescriptor().getVersion(), generation,
                PluginRuntimePackagePhase.LOADED);
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
        PluginDiscoveryResult raw = inspectPlugins().toDiscoveryResult();
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
        if (pluginManager == null) {
            pluginManager = new DefaultPluginManager(pluginsRoot);
        }
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
        PluginDirectoryState state = Files.isDirectory(pluginsRoot)
                ? (entries.isEmpty() ? PluginDirectoryState.EMPTY : PluginDirectoryState.POPULATED)
                : PluginDirectoryState.ABSENT;
        this.status = new PluginRuntimeStatus(pluginsRoot.toAbsolutePath().normalize(), state,
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

    private static final class RuntimeEntry {
        private final String packageId;
        private final Path artifactPath;
        private final String version;
        private final long generation;
        private PluginRuntimePackagePhase phase;

        private RuntimeEntry(String packageId, Path artifactPath, String version, long generation,
                             PluginRuntimePackagePhase phase) {
            this.packageId = packageId;
            this.artifactPath = artifactPath;
            this.version = version;
            this.generation = generation;
            this.phase = phase;
        }
    }
}
