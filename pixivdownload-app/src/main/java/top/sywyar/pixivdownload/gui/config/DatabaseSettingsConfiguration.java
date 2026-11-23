package top.sywyar.pixivdownload.gui.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;

import java.util.Optional;

/** 后端管理入口复用桌面的数据库检查与耐久切换服务。 */
@Configuration(proxyBeanMethods = false)
public class DatabaseSettingsConfiguration {
    @Bean
    DatabaseSettingsService databaseSettingsService(Environment environment, DatabaseTarget target,
                                                     PluginBootstrapSession session) {
        return new DatabaseSettingsService(new ConfigFileEditor(RuntimeFiles.resolveConfigYamlPath()).asConfigFile(),
                () -> environment, () -> Optional.of(target), session::databaseProviders, session);
    }
}
