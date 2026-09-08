package top.sywyar.pixivdownload.gui;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;

import java.util.function.BiConsumer;

/** 把后台插件故障送入现有生命周期和桌面提示，随 Spring context 注册和释放。 */
@Component
final class PluginFailureNotifications implements SmartLifecycle {
    private final PluginRegistry registry;
    private final PluginRuntimeManager runtime;
    private final BiConsumer<PluginRegistry.RegisteredPlugin, String> notification = this::notifyFailure;
    private PluginRuntimeManager previousRuntime;
    private volatile boolean running;

    PluginFailureNotifications(PluginRegistry registry, PluginRuntimeManager runtime) {
        this.registry = registry;
        this.runtime = runtime;
    }

    @Override public void start() {
        if (running) return;
        previousRuntime = GuiLauncher.bindPluginFailureRuntime(runtime);
        registry.addFailureListener(notification);
        running = true;
    }

    private void notifyFailure(PluginRegistry.RegisteredPlugin plugin, String diagnostic) {
        runtime.reportPluginFailure(plugin.packageId(), plugin.generation(), new IllegalStateException(diagnostic));
        DesktopUiDialogs.showMessageDialog(null,
                MessageBundles.get("plugin.dialog.execution-failed", plugin.id(), diagnostic),
                MessageBundles.get("gui.dialog.warning.title"), DesktopUiDialogs.WARNING_MESSAGE);
    }

    @Override public void stop() {
        registry.removeFailureListener(notification);
        GuiLauncher.restorePluginFailureRuntime(runtime, previousRuntime);
        running = false;
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return -100; }
}
