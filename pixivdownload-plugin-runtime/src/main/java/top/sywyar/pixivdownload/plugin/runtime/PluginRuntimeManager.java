package top.sywyar.pixivdownload.plugin.runtime;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 外置插件运行时管理封装：把 PF4J {@link PluginManager} 收口在本类内，向核心壳暴露
 * 「定位插件目录 → 扫描 → 加载 → 启动 → 诊断」一条可测试链路，避免业务代码直接散落使用 PF4J。
 *
 * <h2>设计取舍</h2>
 * <ul>
 *   <li><b>目录只读诊断、不创建。</b>插件目录缺失时报告 {@link PluginDirectoryState#ABSENT} 并输出缺失诊断，
 *       不静默创建目录（否则下次启动会变成「空目录」而非「缺失」，且会污染开发工作树）；目录创建归后续安装流程。</li>
 *   <li><b>逐包加载、隔离失败。</b>不直接用 PF4J 的批量 {@code loadPlugins()}（它会吞掉每包错误、无法报告失败明细），
 *       而是自行枚举候选包（{@code *.jar} / {@code *.zip}），逐个调用 {@link PluginManager#loadPlugin(Path)}，
 *       把每包的加载 / 启动失败捕获成 {@link PluginLoadFailure} 条目。坏包<b>绝不</b>致使核心壳启动失败。</li>
 *   <li><b>PF4J 实例惰性创建。</b>仅在目录存在且有候选包（{@link PluginDirectoryState#POPULATED}）时才 new
 *       {@link DefaultPluginManager}；缺失 / 空目录的常态路径完全不触碰 PF4J。</li>
 * </ul>
 *
 * <p>本类是 POJO（不带 Spring 注解），由核心壳侧的配置以 {@code @Bean} 装配并在启动期调用一次 {@link #start()}。
 * 当前运行时骨架不实现热安装 / 热卸载、不把外置插件桥接进核心 {@code PluginRegistry}（属后续桥接流程），
 * 只负责插件目录定位 / 扫描 / 加载 / 启动 / 诊断。
 */
public class PluginRuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(PluginRuntimeManager.class);

    private final Path pluginsRoot;

    private volatile PluginManager pluginManager;
    private volatile PluginRuntimeStatus status;

    public PluginRuntimeManager(Path pluginsRoot) {
        if (pluginsRoot == null) {
            throw new IllegalArgumentException("pluginsRoot must not be null");
        }
        this.pluginsRoot = pluginsRoot;
    }

    /**
     * 扫描插件目录并按需加载 / 启动外置插件，缓存并返回结果快照。可重复调用（每次重新扫描），
     * 当前由核心壳在启动期调用一次。任一异常路径都被收敛为诊断状态，<b>本方法不向调用方抛出</b>，
     * 以保证插件目录缺失 / 空 / 含坏包都不会让核心壳启动失败。
     */
    public PluginRuntimeStatus start() {
        Path directory = pluginsRoot.toAbsolutePath().normalize();

        if (!Files.exists(pluginsRoot)) {
            log.warn("Plugin directory not found: {} - no external plugins will be loaded; "
                    + "running with built-in plugins only.", directory);
            return cache(absent(directory));
        }
        if (!Files.isDirectory(pluginsRoot)) {
            log.warn("Plugin path exists but is not a directory: {} - no external plugins will be loaded.", directory);
            return cache(absent(directory));
        }

        List<Path> candidates;
        try {
            candidates = findCandidatePackages(pluginsRoot);
        } catch (IOException e) {
            log.warn("Failed to scan plugin directory {}: {} - treating as empty.", directory, e.toString());
            return cache(new PluginRuntimeStatus(
                    directory, PluginDirectoryState.EMPTY, List.of(), List.of(), List.of()));
        }

        if (candidates.isEmpty()) {
            log.info("Plugin directory is empty: {} - no external plugins to load.", directory);
            return cache(new PluginRuntimeStatus(
                    directory, PluginDirectoryState.EMPTY, List.of(), List.of(), List.of()));
        }

        return cache(loadAndStart(directory, candidates));
    }

    /** 上一次 {@link #start()} 的结果快照（未运行过时为空）。 */
    public Optional<PluginRuntimeStatus> status() {
        return Optional.ofNullable(status);
    }

    /**
     * 已创建的 PF4J {@link PluginManager}（仅当目录存在且有候选包时才会创建，否则为空）。
     * 供后续桥接流程把外置插件接入核心注册中心使用；当前运行时骨架不消费它的运行期行为。
     */
    public Optional<PluginManager> pluginManager() {
        return Optional.ofNullable(pluginManager);
    }

    /** 配置的插件目录（未规范化为绝对路径，规范化绝对路径见 {@link PluginRuntimeStatus#directory()}）。 */
    public Path pluginsRoot() {
        return pluginsRoot;
    }

    private PluginRuntimeStatus loadAndStart(Path directory, List<Path> candidates) {
        PluginManager manager = new DefaultPluginManager(pluginsRoot);
        this.pluginManager = manager;

        List<PluginLoadFailure> failures = new ArrayList<>();
        for (Path candidate : candidates) {
            String source = candidate.getFileName().toString();
            try {
                manager.loadPlugin(candidate);
            } catch (Exception e) {
                String reason = describe(e);
                failures.add(new PluginLoadFailure(source, reason));
                log.error("Failed to load plugin package {}: {}", source, reason);
            }
        }

        try {
            manager.startPlugins();
        } catch (Exception e) {
            log.error("Error while starting external plugins: {}", describe(e));
        }

        List<String> loaded = new ArrayList<>();
        List<String> started = new ArrayList<>();
        for (PluginWrapper wrapper : manager.getPlugins()) {
            String id = wrapper.getPluginId();
            loaded.add(id);
            if (wrapper.getPluginState() == PluginState.STARTED) {
                started.add(id);
            } else if (wrapper.getPluginState() == PluginState.FAILED) {
                failures.add(new PluginLoadFailure(id, describe(wrapper.getFailedException())));
            }
        }

        log.info("Plugin runtime: directory {} -> {} loaded, {} started, {} failed.",
                directory, loaded.size(), started.size(), failures.size());
        return new PluginRuntimeStatus(
                directory, PluginDirectoryState.POPULATED, loaded, started, failures);
    }

    private static PluginRuntimeStatus absent(Path directory) {
        return new PluginRuntimeStatus(
                directory, PluginDirectoryState.ABSENT, List.of(), List.of(), List.of());
    }

    private PluginRuntimeStatus cache(PluginRuntimeStatus result) {
        this.status = result;
        return result;
    }

    /**
     * 候选插件包：插件目录下的常规文件，文件名（小写）以 {@code .jar} 或 {@code .zip} 结尾、不以 {@code .} 开头。
     * 不把子目录当候选（外置插件以 jar / zip 发布；解压目录形态的开发模式不在当前骨架范围）。
     */
    private static List<Path> findCandidatePackages(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(PluginRuntimeManager::isCandidatePackage)
                    .sorted()
                    .toList();
        }
    }

    private static boolean isCandidatePackage(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith(".")) {
            return false;
        }
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        return (message != null && !message.isBlank()) ? message : t.getClass().getName();
    }
}
