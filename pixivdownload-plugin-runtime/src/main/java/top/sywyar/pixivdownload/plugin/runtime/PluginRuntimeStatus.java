package top.sywyar.pixivdownload.plugin.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;

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
 */
public record PluginRuntimeStatus(
        Path directory,
        PluginDirectoryState state,
        List<String> loadedPluginIds,
        List<String> startedPluginIds,
        List<PluginLoadFailure> failures) {

    public PluginRuntimeStatus {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(state, "state");
        loadedPluginIds = List.copyOf(loadedPluginIds);
        startedPluginIds = List.copyOf(startedPluginIds);
        failures = List.copyOf(failures);
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
