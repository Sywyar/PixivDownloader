package top.sywyar.pixivdownload.core.db.provider;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabasePoolProperties;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderBinding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;

/** 把宿主配置交给唯一插件会话；首次成功选择需落盘，之后丢失 owner 不再重新猜选。 */
public final class DatabaseBootstrap {
    private DatabaseBootstrap() {}

    /** 停机工具复用市场验签、撤销及启用策略；调用方持有并关闭整个会话。 */
    public static PluginBootstrapSession openToolSession(Environment environment) {
        var binder = Binder.get(environment);
        var properties = binder.bind("plugin-catalog", top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties.class)
                .orElseGet(top.sywyar.pixivdownload.plugin.catalog.PluginCatalogProperties::new);
        var repositories = new top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry(properties);
        var toggles = binder.bind("plugins", top.sywyar.pixivdownload.plugin.PluginToggleProperties.class)
                .orElseGet(top.sywyar.pixivdownload.plugin.PluginToggleProperties::new);
        var disabled = toggles.entrySet().stream().filter(entry -> entry.getValue() != null && !entry.getValue().isEnabled())
                .map(java.util.Map.Entry::getKey).toList();
        var session = PluginBootstrapSession.createProcess(RuntimeFiles.pluginsDirectory(),
                top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot.ofDisabled(disabled, java.util.List.of()),
                top.sywyar.pixivdownload.plugin.catalog.PluginCatalogTrustStores.verifierResolver(repositories));
        session.updateAdmissionPolicy(new top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogRevocationAdmissionPolicy(
                repositories, new top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore()));
        return session.start();
    }

    public static DatabaseTarget inspect(PluginBootstrapSession session, Environment environment) {
        var properties = Binder.get(environment).bind("database", DatabasePoolProperties.class)
                .orElseGet(DatabasePoolProperties::new);
        var selected = top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection.select(
                properties.getProvider(), environment.containsProperty("database.provider") ? "" : properties.getEngine(),
                session.manager().inspectPlugins(), session.enabledSnapshot());
        return DatabaseTarget.inspect(selected, settings(properties.getJdbcUrl(), properties.getUsername(),
                properties.getPassword(), environment.getProperty("download.root-folder", RuntimeFiles.DEFAULT_DOWNLOAD_ROOT)));
    }

    public static DatabaseProviderBinding bind(PluginBootstrapSession session, Environment environment) {
        var properties = Binder.get(environment).bind("database", DatabasePoolProperties.class)
                .orElseGet(DatabasePoolProperties::new);
        String root = environment.getProperty("download.root-folder", RuntimeFiles.DEFAULT_DOWNLOAD_ROOT);
        String initialEngine = environment.containsProperty("database.provider") ? ""
                : properties.getEngine().trim().toLowerCase(Locale.ROOT);
        return session.bindDatabase(properties.getProvider(), initialEngine, () -> {
            return settings(properties.getJdbcUrl(), properties.getUsername(), properties.getPassword(), root);
        }, binding -> {
            // 首次选择落盘失败后，本进程保持无库。
            try {
                persistInitialSelection(binding, environment);
            } catch (IOException failure) {
                throw new UncheckedIOException(new IOException("DATABASE_SELECTION_SAVE_FAILED"));
            }
            var settings = binding.settings().orElseThrow();
            try {
                binding.selection().provider().orElseThrow().prepareStorage(settings);
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("DATABASE_STORAGE_PREPARATION_FAILED");
            }
            return DatabaseProviderBinding.resolve(binding.selection(), () -> settings,
                    java.util.function.UnaryOperator.identity());
        });
    }

    /** 宿主只提供主库数据根和历史目录，文件名与搬迁单元由提供者识别。 */
    public static DatabaseConnectionSettings settings(String url, String user, String password, String downloadRoot) {
        var data = RuntimeFiles.dataDirectory().toAbsolutePath().normalize();
        var legacy = new java.util.ArrayList<java.nio.file.Path>();
        String root = RuntimeFiles.normalizeRootFolder(downloadRoot);
        if (!root.isBlank()) legacy.add(java.nio.file.Path.of(root));
        if (data.getParent() != null) legacy.add(data.getParent());
        return new DatabaseConnectionSettings(url, user, password, data, legacy);
    }

    private static void persistInitialSelection(DatabaseProviderBinding binding, Environment environment)
            throws IOException {
        if (!binding.available() || environment.containsProperty("database.provider")) return;
        var editor = new ConfigFileEditor(RuntimeFiles.resolveConfigYamlPath());
        String saved = editor.read("database.provider");
        if (saved == null) editor.write("database.provider", binding.selection().owner());
    }
}
