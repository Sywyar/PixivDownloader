package top.sywyar.pixivdownload.plugin.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginRuntimeVerificationSnapshot;

/**
 * 一次插件运行时扫描 / 加载的结果快照（不可变）。核心壳与后续流程据此判断：
 * 插件目录是否就绪、加载了哪些外置插件、哪些包加载或启动失败。本类只描述事实，
 * 不携带「是否需补齐插件」之类的策略判定（策略判定归后续的插件加载 / 恢复流程）。
 *
 * @param directory        解析后的插件目录绝对路径
 * @param state            插件目录诊断状态
 * @param loadedPluginIds  成功加载的外置插件 id（含已启动与启动失败者）
 * @param startedPluginIds 成功启动的外置插件 id（{@code loadedPluginIds} 的子集）
 * @param failures         加载 / 启动失败的诊断条目（坏包被隔离捕获、不致命）
 * @param verifications    当前运行时对各冻结 artifact 字节保留的最新结构化离线复验结果
 */
public record PluginRuntimeStatus(
        Path directory,
        PluginDirectoryState state,
        List<String> loadedPluginIds,
        List<String> startedPluginIds,
        List<PluginLoadFailure> failures,
        List<PluginRuntimeVerificationSnapshot> verifications) {

    public PluginRuntimeStatus(Path directory,
                               PluginDirectoryState state,
                               List<String> loadedPluginIds,
                               List<String> startedPluginIds,
                               List<PluginLoadFailure> failures) {
        this(directory, state, loadedPluginIds, startedPluginIds, failures, List.of());
    }

    public PluginRuntimeStatus {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(state, "state");
        loadedPluginIds = List.copyOf(loadedPluginIds);
        startedPluginIds = List.copyOf(startedPluginIds);
        failures = List.copyOf(failures);
        verifications = List.copyOf(verifications);
    }

    static PluginRuntimeStatus populated(
            Path directory,
            Map<String, PluginRuntimePackagePhase> phases,
            List<PluginLoadFailure> failures) {
        return populated(directory, phases, failures, List.of());
    }

    static PluginRuntimeStatus populated(
            Path directory,
            Map<String, PluginRuntimePackagePhase> phases,
            List<PluginLoadFailure> failures,
            List<PluginRuntimeVerificationSnapshot> verifications) {
        return project(directory, PluginDirectoryState.POPULATED, phases, failures, verifications);
    }

    /** 用运行期包阶段刷新状态，并保留最近复验事实。 */
    PluginRuntimeStatus refreshed(Map<String, PluginRuntimePackagePhase> phases) {
        PluginDirectoryState refreshedState = Files.isDirectory(directory)
                ? (phases.isEmpty() ? PluginDirectoryState.EMPTY : PluginDirectoryState.POPULATED)
                : PluginDirectoryState.ABSENT;
        return project(directory, refreshedState, phases, List.of(), verifications);
    }

    /**
     * 替换同安装路径或同插件 id 的旧复验快照，并只保留最新的有界结果。
     */
    PluginRuntimeStatus withLatestRuntimeVerifications(
            Path attemptedPath,
            List<PluginRuntimeVerificationSnapshot> latest,
            int maximumSnapshots) {
        if (maximumSnapshots <= 0) {
            throw new IllegalArgumentException("maximumSnapshots must be positive");
        }
        Set<Path> replacedPaths = new LinkedHashSet<>();
        Set<String> replacedPluginIds = new LinkedHashSet<>();
        if (attemptedPath != null) {
            replacedPaths.add(attemptedPath.toAbsolutePath().normalize());
        }
        if (latest != null) {
            replacedPaths.addAll(latest.stream()
                    .map(PluginRuntimeVerificationSnapshot::artifactPath)
                    .toList());
            replacedPluginIds.addAll(latest.stream()
                    .map(PluginRuntimeVerificationSnapshot::pluginId)
                    .toList());
        }
        if (replacedPaths.isEmpty() && replacedPluginIds.isEmpty()) {
            return this;
        }
        List<PluginRuntimeVerificationSnapshot> merged = new ArrayList<>();
        verifications.stream()
                .filter(snapshot -> !replacedPaths.contains(snapshot.artifactPath())
                        && !replacedPluginIds.contains(snapshot.pluginId()))
                .forEach(merged::add);
        if (latest != null) {
            merged.addAll(latest);
        }
        if (merged.size() > maximumSnapshots) {
            merged.subList(0, merged.size() - maximumSnapshots).clear();
        }
        return new PluginRuntimeStatus(
                directory, state, loadedPluginIds, startedPluginIds, failures, merged);
    }

    private static PluginRuntimeStatus project(
            Path directory,
            PluginDirectoryState state,
            Map<String, PluginRuntimePackagePhase> phases,
            List<PluginLoadFailure> failures,
            List<PluginRuntimeVerificationSnapshot> verifications) {
        Objects.requireNonNull(phases, "phases");
        List<String> loaded = List.copyOf(phases.keySet());
        List<String> started = phases.entrySet().stream()
                .filter(entry -> entry.getValue() == PluginRuntimePackagePhase.STARTED)
                .map(Map.Entry::getKey)
                .toList();
        return new PluginRuntimeStatus(directory, state, loaded, started, failures, verifications);
    }

    /** 插件目录是否存在且为目录（{@link PluginDirectoryState#ABSENT} 取反）。 */
    public boolean directoryPresent() {
        return state != PluginDirectoryState.ABSENT;
    }

    /** 插件目录存在但没有候选插件包。 */
    public boolean empty() {
        return state == PluginDirectoryState.EMPTY;
    }

    /** 是否有任何加载 / 启动失败的插件包。 */
    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    /** 成功加载的外置插件数量。 */
    public int loadedCount() {
        return loadedPluginIds.size();
    }
}
