package top.sywyar.pixivdownload.plugin.runtime.discovery;

import org.pf4j.Plugin;
import org.pf4j.PluginDependency;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * PF4J 外置插件发现桥接：把 PF4J {@link PluginManager} 已加载 / 启动的外置插件，连同其插件框架描述符
 * （{@link org.pf4j.PluginDescriptor}）与功能插件元数据，转换为核心可消费的中性载体——
 * {@link PluginInventory}（含 {@link PluginInstallation}：统一 {@link PluginDescriptor} + 基线状态 + classloader +
 * 实例）以及既有的 {@link PluginDiscoveryResult} 发现契约（{@link #discover}）。本类是 PF4J 与核心壳之间唯一接触
 * {@code org.pf4j} 的桥接点——产出全部为 {@code plugin.api} 契约 + 描述符 / 状态模型 + JDK 类型，不向外泄露任何
 * PF4J 实现类型。
 *
 * <h2>发现 / 清点规则</h2>
 * <ul>
 *   <li><b>只看已启动插件。</b>仅 {@link PluginState#STARTED} 的插件参与；未启动 / 失败的包由
 *       {@link PluginRuntimeManager} 在加载 / 启动阶段已捕获为失败诊断。</li>
 *   <li><b>先校核心 API 兼容、再提取贡献。</b>读插件包描述符的 {@code requires} 并据
 *       {@link PluginApiRequirement#isSatisfiedByCurrentApi()} 判定：不兼容的包标记
 *       {@link PluginStatus#INCOMPATIBLE} 并<b>拒绝提取</b>其功能插件（不信任不兼容插件的贡献），从而拒绝接入；
 *       兼容的包才经入口契约 {@link PixivPluginProvider} 提取 {@link PixivFeaturePlugin}。</li>
 *   <li><b>统一映射描述符。</b>每个功能插件映射出统一 {@link PluginDescriptor}：id / displayName / kind 取功能插件，
 *       version / requires / dependencies / plugin-class 取所属插件包的 PF4J 描述符。</li>
 *   <li><b>classloader 边界严格。</b>每条安装条目带其插件 classloader（资源解析依据），绝不用核心壳应用 classloader
 *       误读外置插件资源。</li>
 *   <li><b>诊断清晰、隔离失败。</b>主类未实现入口契约、入口方法抛错 / 返回 {@code null}、功能 id 与包 id
 *       不同都被收敛为
 *       {@link PluginLoadFailure} 条目并记日志，<b>不</b>抛出、<b>不</b>影响其它插件、<b>不</b>致核心壳启动失败。</li>
 * </ul>
 */
public final class PixivPluginDiscoveryBridge {

    private static final Logger log = LoggerFactory.getLogger(PixivPluginDiscoveryBridge.class);

    /**
     * 遍历 {@link PluginManager} 已启动的外置插件，清点其功能插件安装条目（含兼容性基线状态）与失败诊断。
     * 本方法只向上保留 JVM 致命错误；任一插件的其它清点失败被隔离为
     * {@link PluginInventory#failures()} 条目。
     */
    public PluginInventory inspect(PluginManager manager) {
        if (manager == null) {
            return PluginInventory.empty();
        }
        List<PluginInstallation> installations = new ArrayList<>();
        List<PluginContextModule> contextModules = new ArrayList<>();
        List<PluginLoadFailure> failures = new ArrayList<>();
        try {
            for (PluginWrapper wrapper : manager.getPlugins()) {
                try {
                    if (wrapper.getPluginState() != PluginState.STARTED) {
                        continue;
                    }
                    inspectWrapper(wrapper, installations, contextModules, failures);
                } catch (Throwable failure) {
                    throwIfJvmFatal(failure);
                    failures.add(fail(diagnosticSource(wrapper),
                            "unexpected discovery failure: " + describe(failure)));
                }
            }
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            failures.add(fail("<plugin-manager>",
                    "failed to enumerate plugin wrappers: " + describe(failure)));
        }
        return new PluginInventory(installations, contextModules, failures);
    }

    /** 清点一个已加载包；允许 RESOLVED/STOPPED，用于在 PF4J start 前建立新的代际句柄。 */
    public PluginInventory inspectLoadedPackage(PluginManager manager, String packageId) {
        if (manager == null || packageId == null) {
            return PluginInventory.empty();
        }
        List<PluginInstallation> installations = new ArrayList<>();
        List<PluginContextModule> contextModules = new ArrayList<>();
        List<PluginLoadFailure> failures = new ArrayList<>();
        try {
            PluginWrapper wrapper = manager.getPlugin(packageId);
            if (wrapper == null || wrapper.getPluginState() == PluginState.FAILED
                    || wrapper.getPluginState() == PluginState.UNLOADED) {
                return PluginInventory.empty();
            }
            inspectWrapper(wrapper, installations, contextModules, failures);
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            failures.add(fail(packageId, "unexpected discovery failure: " + describe(failure)));
        }
        return new PluginInventory(installations, contextModules, failures);
    }

    /**
     * 发现可接入核心注册中心的外置功能插件（既有发现契约）。等价于 {@code inspect(manager).toDiscoveryResult()}：
     * 兼容且已启动的插件进入 {@link PluginDiscoveryResult#discovered()}，不兼容 / 失败的并入
     * {@link PluginDiscoveryResult#failures()}。
     */
    public PluginDiscoveryResult discover(PluginManager manager) {
        return inspect(manager).toDiscoveryResult();
    }

    private void inspectWrapper(PluginWrapper wrapper,
                                List<PluginInstallation> installations,
                                List<PluginContextModule> contextModules,
                                List<PluginLoadFailure> failures) {
        String sourcePluginId = wrapper.getPluginId();
        ClassLoader classLoader = wrapper.getPluginClassLoader();
        org.pf4j.PluginDescriptor pf4jDescriptor = wrapper.getDescriptor();
        PluginApiRequirement requires = PluginApiRequirement.parse(
                pf4jDescriptor != null ? pf4jDescriptor.getRequires() : null);

        // 先校核心 API 兼容：不兼容的插件包不提取贡献，仅记一条 INCOMPATIBLE 安装条目（拒绝接入）。
        if (!requires.isSatisfiedByCurrentApi()) {
            log.warn("External plugin {} is incompatible with core API {}: requires {}",
                    sourcePluginId, top.sywyar.pixivdownload.plugin.api.PluginApiVersion.VERSION, requires.display());
            installations.add(new PluginInstallation(
                    packageDescriptor(sourcePluginId, pf4jDescriptor, requires),
                    PluginStatus.INCOMPATIBLE, classLoader, null));
            return;
        }

        Plugin plugin;
        try {
            plugin = wrapper.getPlugin();
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            failures.add(fail(sourcePluginId, "failed to obtain plugin instance: " + describe(failure)));
            return;
        }
        if (!(plugin instanceof PixivPluginProvider provider)) {
            String pluginClassName = plugin == null ? "null" : plugin.getClass().getName();
            failures.add(fail(sourcePluginId, "plugin main class does not implement PixivPluginProvider: "
                    + pluginClassName + " (ensure pixivdownload-plugin-api is provided by the host and not "
                    + "bundled inside the plugin, otherwise the contract type is loaded by a different classloader)"));
            return;
        }

        PixivFeaturePlugin featurePlugin;
        try {
            featurePlugin = provider.featurePlugin();
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            failures.add(fail(sourcePluginId, "featurePlugin() threw: " + describe(failure)));
            return;
        }
        if (featurePlugin == null) {
            failures.add(fail(sourcePluginId, "featurePlugin() returned null"));
            return;
        }
        String featurePluginId;
        try {
            featurePluginId = featurePlugin.id();
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            failures.add(fail(sourcePluginId, "featurePlugin().id() threw: " + describe(failure)));
            return;
        }
        if (!sourcePluginId.equals(featurePluginId)) {
            failures.add(fail(sourcePluginId, "featurePlugin() id must match package id '" + sourcePluginId
                    + "': got '" + String.valueOf(featurePluginId) + "'"));
            return;
        }
        PluginDescriptor descriptor;
        try {
            descriptor = featureDescriptor(featurePluginId, featurePlugin, sourcePluginId, pf4jDescriptor, requires);
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            failures.add(fail(sourcePluginId,
                    "feature plugin metadata getter threw: " + describe(failure)));
            return;
        }

        List<Class<?>> configurationClasses;
        try {
            List<Class<?>> declared = provider.configurationClasses();
            if (declared == null) {
                failures.add(fail(sourcePluginId, "configurationClasses() returned null"));
                return;
            }
            configurationClasses = List.copyOf(declared);
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            failures.add(fail(sourcePluginId, "configurationClasses() failed: " + describe(failure)));
            return;
        }

        PluginContextModule contextModule = null;
        if (!configurationClasses.isEmpty()) {
            try {
                contextModule = new PluginContextModule(sourcePluginId, classLoader, configurationClasses);
            } catch (Throwable failure) {
                throwIfJvmFatal(failure);
                failures.add(fail(sourcePluginId, "configurationClasses() produced an invalid module: "
                        + describe(failure)));
                return;
            }
        }
        installations.add(new PluginInstallation(
                descriptor, PluginStatus.STARTED, classLoader, featurePlugin));
        if (contextModule != null) {
            contextModules.add(contextModule);
        }
    }

    /** 兼容插件包内某个功能插件的统一描述符：身份 / 展示取功能插件，版本 / 依赖 / 主类取插件包。 */
    private static PluginDescriptor featureDescriptor(String featurePluginId, PixivFeaturePlugin featurePlugin,
                                                      String sourcePluginId,
                                                      org.pf4j.PluginDescriptor pf4jDescriptor,
                                                      PluginApiRequirement requires) {
        return new PluginDescriptor(
                featurePluginId,
                sourcePluginId,
                pf4jDescriptor != null ? pf4jDescriptor.getVersion() : null,
                requires,
                dependencyRefs(pf4jDescriptor),
                pf4jDescriptor != null ? pf4jDescriptor.getPluginClass() : null,
                featurePlugin.displayNamespace(),
                featurePlugin.displayName(),
                featurePlugin.description(),
                featurePlugin.iconKey(),
                featurePlugin.colorToken(),
                featurePlugin.kind());
    }

    /**
     * 不兼容插件包的包级描述符（不提取功能插件，故展示名退化为包描述 / 包 id、类别按外置默认 FEATURE）。
     * 简介 i18n key 与展示 token（{@code description} / {@code iconKey} / {@code colorToken}）无功能插件实例可取、
     * 一律为 {@code null}，由消费端按默认回退。
     */
    private static PluginDescriptor packageDescriptor(String sourcePluginId,
                                                      org.pf4j.PluginDescriptor pf4jDescriptor,
                                                      PluginApiRequirement requires) {
        String displayName = sourcePluginId;
        if (pf4jDescriptor != null && pf4jDescriptor.getPluginDescription() != null
                && !pf4jDescriptor.getPluginDescription().isBlank()) {
            displayName = pf4jDescriptor.getPluginDescription();
        }
        return new PluginDescriptor(
                sourcePluginId,
                sourcePluginId,
                pf4jDescriptor != null ? pf4jDescriptor.getVersion() : null,
                requires,
                dependencyRefs(pf4jDescriptor),
                pf4jDescriptor != null ? pf4jDescriptor.getPluginClass() : null,
                null,
                displayName,
                null,
                null,
                null,
                PluginKind.FEATURE);
    }

    private static List<PluginDependencyRef> dependencyRefs(org.pf4j.PluginDescriptor pf4jDescriptor) {
        if (pf4jDescriptor == null || pf4jDescriptor.getDependencies() == null) {
            return List.of();
        }
        List<PluginDependencyRef> refs = new ArrayList<>();
        for (PluginDependency dependency : pf4jDescriptor.getDependencies()) {
            refs.add(new PluginDependencyRef(
                    dependency.getPluginId(), dependency.getPluginVersionSupport(), dependency.isOptional()));
        }
        return refs;
    }

    private static PluginLoadFailure fail(String sourcePluginId, String reason) {
        log.error("External plugin discovery failed for {}: {}", sourcePluginId, reason);
        return new PluginLoadFailure(sourcePluginId, reason);
    }

    private static String diagnosticSource(PluginWrapper wrapper) {
        if (wrapper == null) {
            return "<unknown-plugin>";
        }
        try {
            String pluginId = wrapper.getPluginId();
            return pluginId == null || pluginId.isBlank() ? "<unknown-plugin>" : pluginId;
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            return "<unknown-plugin>";
        }
    }

    private static void throwIfJvmFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError virtualMachineError) {
            throw virtualMachineError;
        }
        if (failure instanceof ThreadDeath threadDeath) {
            throw threadDeath;
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message;
        try {
            message = t.getMessage();
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            message = null;
        }
        return (message != null && !message.isBlank()) ? message : t.getClass().getName();
    }
}
