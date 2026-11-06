package top.sywyar.pixivdownload.plugin.runtime.discovery;

import org.pf4j.Plugin;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;

import java.util.ArrayList;
import java.util.List;

/** 已验证描述符决定元数据与配置类；只从插件实例提取行为，并隔离单个包的初始化失败。 */
public final class PixivPluginDiscoveryBridge {

    private static final Logger log = LoggerFactory.getLogger(PixivPluginDiscoveryBridge.class);

    /**
     * 按已验证包描述符初始化单个 runtime entry。身份、展示、类型和配置类名单只取 descriptor；插件实例只提供行为与
     * contribution，不再有机会在发现阶段改写宿主已经验签的元数据。
     */
    public PluginInventory inspectLoadedPackage(PluginManager manager, PluginDescriptor descriptor) {
        if (manager == null || descriptor == null) {
            return PluginInventory.empty();
        }
        String packageId = descriptor.sourcePluginId();
        List<PluginInstallation> installations = new ArrayList<>();
        List<PluginContextModule> contextModules = new ArrayList<>();
        List<PluginLoadFailure> failures = new ArrayList<>();
        try {
            PluginWrapper wrapper = manager.getPlugin(packageId);
            if (wrapper == null || wrapper.getPluginState() == PluginState.FAILED
                    || wrapper.getPluginState() == PluginState.UNLOADED) {
                return PluginInventory.empty();
            }
            inspectVerifiedWrapper(wrapper, descriptor, installations, contextModules, failures);
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            failures.add(fail(packageId, "unexpected discovery failure: " + describe(failure)));
        }
        return new PluginInventory(installations, contextModules, failures);
    }

    private void inspectVerifiedWrapper(
            PluginWrapper wrapper,
            PluginDescriptor descriptor,
            List<PluginInstallation> installations,
            List<PluginContextModule> contextModules,
            List<PluginLoadFailure> failures) {
        String sourcePluginId = descriptor.sourcePluginId();
        if (!sourcePluginId.equals(wrapper.getPluginId())) {
            failures.add(fail(sourcePluginId, "loaded package id does not match verified descriptor"));
            return;
        }
        ClassLoader classLoader = wrapper.getPluginClassLoader();
        if (!descriptor.isSdkCompatible()) {
            installations.add(new PluginInstallation(
                    descriptor, PluginStatus.INCOMPATIBLE, classLoader, null));
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
                    + pluginClassName));
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

        List<Class<?>> configurationClasses = new ArrayList<>();
        try {
            for (String className : descriptor.configurationClassNames()) {
                Class<?> configurationClass = Class.forName(className, false, classLoader);
                if (configurationClass.getClassLoader() != classLoader) {
                    throw new IllegalArgumentException(
                            "configuration class must be owned by the plugin classloader: " + className);
                }
                configurationClasses.add(configurationClass);
            }
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            failures.add(fail(sourcePluginId,
                    "descriptor configuration class failed to load: " + describe(failure)));
            return;
        }

        try {
            var module = new PluginContextModule(sourcePluginId, classLoader, configurationClasses,
                    featurePlugin.privateSchema(), featurePlugin.privateStorageMigrations(), featurePlugin.sharedMainStorage());
            if (module.requiresContext()) contextModules.add(module);
        } catch (Throwable failure) {
            throwIfJvmFatal(failure);
            failures.add(fail(sourcePluginId, "storage declaration failed: " + describe(failure)));
            return;
        }
        installations.add(new PluginInstallation(
                descriptor, PluginStatus.STARTED, classLoader, featurePlugin));
    }

    private static PluginLoadFailure fail(String sourcePluginId, String reason) {
        log.error("External plugin discovery failed for {}: {}", sourcePluginId, reason);
        return new PluginLoadFailure(sourcePluginId, reason);
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
