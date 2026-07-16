package top.sywyar.pixivdownload.plugin.management;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 持久化外置插件的期望启用态。配置写入宿主拥有的 {@code config.yaml} 扁平键
 * {@code plugins.<id>.enabled}，成功后同步更新当前 Spring 上下文里的
 * {@link PluginToggleProperties}，使状态查询立即反映新值。
 */
@Service
public class PluginEnabledConfigurationService {

    private final PluginStatusService pluginStatusService;
    private final RequiredPluginPolicy requiredPluginPolicy;
    private final PluginToggleProperties pluginToggles;
    private final ConfigFileEditor editor;
    private final Object writeLock = new Object();

    @Autowired
    public PluginEnabledConfigurationService(PluginStatusService pluginStatusService,
                                             RequiredPluginPolicy requiredPluginPolicy,
                                             PluginToggleProperties pluginToggles) {
        this(pluginStatusService, requiredPluginPolicy, pluginToggles, RuntimeFiles.resolveConfigYamlPath());
    }

    PluginEnabledConfigurationService(PluginStatusService pluginStatusService,
                                      RequiredPluginPolicy requiredPluginPolicy,
                                      PluginToggleProperties pluginToggles,
                                      Path configPath) {
        this.pluginStatusService = Objects.requireNonNull(pluginStatusService, "pluginStatusService");
        this.requiredPluginPolicy = Objects.requireNonNull(requiredPluginPolicy, "requiredPluginPolicy");
        this.pluginToggles = Objects.requireNonNull(pluginToggles, "pluginToggles");
        this.editor = new ConfigFileEditor(Objects.requireNonNull(configPath, "configPath"));
    }

    /**
     * 写入插件期望启用态。内置插件与必选策略声明的插件均不是可选开关，任何写入都拒绝；
     * 未知或没有可用描述符的包也拒绝，避免把任意路径变量写进宿主配置。
     */
    public PluginEnabledState update(String id, boolean enabled) {
        String action = enabled ? "enable" : "disable";
        if (BuiltInPlugins.isBuiltIn(id)) {
            throw new PluginManagementException(PluginManagementErrorCode.BUILT_IN_PLUGIN,
                    id, action, null, "Built-in plugin enabled state cannot be configured: " + id);
        }
        if (requiredPluginPolicy.isRequired(id)) {
            throw new PluginManagementException(PluginManagementErrorCode.REQUIRED_PLUGIN,
                    id, action, null, "Required plugin enabled state cannot be configured: " + id);
        }

        PluginDescriptor descriptor = pluginStatusService.report().byId(id)
                .map(diagnostic -> diagnostic.descriptor())
                .orElse(null);
        if (descriptor == null) {
            throw new PluginManagementException(PluginManagementErrorCode.UNKNOWN_PLUGIN,
                    id, action, null, "Unknown plugin: " + id);
        }

        synchronized (writeLock) {
            try {
                editor.write("plugins." + descriptor.id() + ".enabled", Boolean.toString(enabled));
            } catch (IOException e) {
                throw new PluginManagementException(PluginManagementErrorCode.TOGGLE_PERSIST_FAILED,
                        id, action, null, "Failed to persist plugin enabled state: " + id);
            }
            pluginToggles.setEnabled(descriptor.id(), enabled);
        }
        return new PluginEnabledState(descriptor.id(), enabled, descriptor.lifecyclePolicy());
    }

    /** 插件启用态写入结果。 */
    public record PluginEnabledState(String id, boolean enabled, PluginLifecyclePolicy lifecyclePolicy) {
    }
}
