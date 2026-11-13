package top.sywyar.pixivdownload.gui;

import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.gui.bootstrap.ApplicationRestartService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.transfer.EnvironmentTransferService;

/** HTTP 与桌面共用迁移实现，HTTP 预览由本后端实例持有。 */
@Configuration(proxyBeanMethods = false)
public class EnvironmentTransferConfiguration {
    @Bean
    top.sywyar.pixivdownload.transfer.EnvironmentExportService environmentExportService(
            org.springframework.context.ConfigurableApplicationContext context, ApplicationArguments arguments,
            ApplicationRestartService restart) {
        String[] args = arguments.getSourceArgs();
        return new top.sywyar.pixivdownload.transfer.EnvironmentExportService(context, args, session -> {
            var environment = DatabaseTarget.configurationEnvironment(args);
            var target = top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap.inspect(session, environment);
            var host = new AppDesktopUiHost(0, args, session, target);
            var toggles = org.springframework.boot.context.properties.bind.Binder.get(environment).bind("plugins",
                    top.sywyar.pixivdownload.plugin.PluginToggleProperties.class)
                    .orElseGet(top.sywyar.pixivdownload.plugin.PluginToggleProperties::new);
            var registry = new PluginRegistry(java.util.List.of(new top.sywyar.pixivdownload.plugin.CorePlugin()),
                    toggles, session.startupDiscovery());
            return new EnvironmentTransferService(host, () -> GuiLauncher.buildTransferPluginSnapshots(registry),
                    restart::requestRestart, session, args);
        }, restart::requestRestart);
    }

    @Bean
    EnvironmentTransferService httpEnvironmentTransfer(ApplicationArguments arguments, PluginBootstrapSession session,
                                                        DatabaseTarget target, PluginRegistry registry,
                                                        ApplicationRestartService restart) {
        var host = new AppDesktopUiHost(0, arguments.getSourceArgs(), session, target);
        return new EnvironmentTransferService(host, () -> GuiLauncher.buildTransferPluginSnapshots(registry),
                restart::requestRestart, session, arguments.getSourceArgs());
    }
}
