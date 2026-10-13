package top.sywyar.pixivdownload.gui.config;

import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.Binder;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.db.DatabasePoolProperties;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.migration.database.ProviderDatabaseMigration;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderBinding;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.database.DatabaseProviderSelection;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 桌面配置沿用 YAML 保存事务，草稿检查独立于 Spring 后端生命周期。 */
public final class DatabaseSettingsService implements DesktopDatabaseSettings {
    private static final Map<String, String> DEFAULTS = Map.of(
            "database.engine", "sqlite", "database.jdbc-url", "",
            "database.username", "", "database.password", "",
            "download.root-folder", RuntimeFiles.DEFAULT_DOWNLOAD_ROOT);
    private final DesktopUiHost.ConfigFile config;
    private final Supplier<Environment> environment;
    private final Supplier<Optional<DatabaseTarget>> activeTarget;
    private final Supplier<List<DatabaseProviderSelection.Result>> availableProviders;
    private static final AtomicBoolean checking = new AtomicBoolean();
    private DatabaseSwitchService migration;

    public DatabaseSettingsService(DesktopUiHost.ConfigFile config, Supplier<Environment> environment,
                                   Supplier<Optional<DatabaseTarget>> activeTarget,
                                   Supplier<List<DatabaseProviderSelection.Result>> availableProviders) {
        this.config = config;
        this.environment = environment;
        this.activeTarget = activeTarget;
        this.availableProviders = availableProviders;
    }

    public DatabaseSettingsService(DesktopUiHost.ConfigFile config, Supplier<Environment> environment,
                                   Supplier<Optional<DatabaseTarget>> activeTarget,
                                   Supplier<List<DatabaseProviderSelection.Result>> availableProviders,
                                   top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession session) {
        this(config, environment, activeTarget, availableProviders);
        if (session != null) migration = new DatabaseSwitchService(this, config, session,
                () -> top.sywyar.pixivdownload.gui.bootstrap.ApplicationRestartService.forBootstrap().requestRestart());
    }

    Map<String, String> draft(Map<String, String> updates) throws IOException {
        return prepare(updates, false, saved());
    }
    DatabaseTarget savedTarget() throws IOException { return target(saved()); }
    boolean hasActiveTarget() { return activeTarget.get().filter(DatabaseTarget::available).isPresent(); }
    DatabaseTarget draftTarget(Map<String, String> updates) throws IOException {
        var values = saved();
        values.putAll(updates);
        return target(values);
    }
    @Override public MigrationPlan previewMigration(Map<String, String> updates) throws IOException {
        if (migration == null) return DesktopDatabaseSettings.super.previewMigration(updates);
        return migration.preview(updates);
    }
    @Override public void startMigration(String token) throws IOException {
        if (migration == null) DesktopDatabaseSettings.super.startMigration(token);
        else migration.start(token);
    }
    @Override public void startMigration(String token, DesktopUiHost.IoOperation saveOtherSettings) throws IOException {
        if (migration == null) DesktopDatabaseSettings.super.startMigration(token, saveOtherSettings);
        else migration.start(token, saveOtherSettings);
    }
    @Override public MigrationState migrationState() throws IOException { return DatabaseSwitchService.state(); }

    @Override public List<ProviderChoice> providers() {
        return availableProviders.get().stream().filter(DatabaseProviderSelection.Result::available)
                .map(value -> new ProviderChoice(value.owner(), value.databaseId(), value.provider().orElseThrow().connectionFields())).toList();
    }

    @Override
    public Map<String, String> prepare(Map<String, String> updates) throws IOException {
        try {
            return prepare(updates, true, saved());
        } catch (IOException failure) {
            String key = DesktopDatabaseSettings.failureMessageKey(failure.getMessage());
            if (key.equals("gui.config.database.error.unknown")) throw failure;
            throw new IOException(MessageBundles.get(key));
        }
    }

    private Map<String, String> prepare(Map<String, String> updates, boolean saving,
                                        Map<String, String> saved) throws IOException {
        Map<String, String> normalized = new LinkedHashMap<>(updates);
        if (updates.keySet().stream().noneMatch(key -> key.equals("database.provider")
                || DEFAULTS.containsKey(key) && key.startsWith("database."))) {
            return normalized;
        }
        Map<String, String> selection = new LinkedHashMap<>(saved);
        selection.putAll(normalized);
        var provider = selected(selection);
        if (updates.containsKey("database.provider")) normalized.put("database.engine", provider.databaseId());
        try {
            var prepared = java.util.Objects.requireNonNull(provider.provider().orElseThrow().normalizeConfiguration(
                    top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap.settings(selection.get("database.jdbc-url"), selection.get("database.username"),
                            selection.get("database.password"), selection.get("download.root-folder")),
                    !provider.databaseId().equals(saved.get("database.engine"))
                            || saved.containsKey("database.provider") && !provider.owner().equals(saved.get("database.provider"))));
            normalized.put("database.jdbc-url", prepared.jdbcUrl());
            normalized.put("database.username", prepared.username());
            normalized.put("database.password", prepared.password());
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable invalid) {
            throw configurationFailure(invalid);
        }
        DatabaseTarget previous = saving && migration != null ? target(saved) : null;
        saved.putAll(normalized);
        DatabaseTarget next = target(saved);
        if (saving) {
            if (previous != null && (!previous.providerOwner().equals(next.providerOwner())
                    || !previous.description().equals(next.description())))
                throw new IOException(MessageBundles.get("gui.config.database.migration.required"));
            try {
                top.sywyar.pixivdownload.core.db.schema.PrivateDatabaseMigrationState.requireReady(next, availableProviders.get());
            } catch (IOException required) {
                throw new IOException(MessageBundles.get("gui.config.database.private-migration-required"));
            }
        }
        return normalized;
    }

    @Override
    public CheckResult check(Map<String, String> updates) throws IOException {
        if (!checking.compareAndSet(false, true)) return new CheckResult(Outcome.BUSY, "", "");
        try {
            Map<String, String> draft = saved();
            draft.putAll(prepare(updates, false, draft));
            DatabaseTarget target = target(draft);
            if (target.localFile().filter(file -> !Files.exists(file)).isPresent()) {
                return new CheckResult(Outcome.NOT_CREATED, target.description(), "");
            }
            try {
                target.checkConnection();
                return new CheckResult(Outcome.CONNECTED, target.description(), "");
            } catch (SQLException failure) {
                String code = ProviderDatabaseMigration.safeFailure(failure);
                return new CheckResult(Outcome.FAILED, target.description(),
                        code.startsWith("SQLSTATE_") ? code.substring("SQLSTATE_".length()) : code);
            }
        } catch (IOException failure) {
            return new CheckResult(Outcome.FAILED, "", ProviderDatabaseMigration.safeFailure(failure));
        } finally {
            checking.set(false);
        }
    }

    @Override
    public State state() throws IOException {
        Map<String, String> saved = saved();
        Map<String, String> effective = new LinkedHashMap<>();
        List<String> overrides = new ArrayList<>();
        Optional<DatabaseTarget> active = activeTarget.get();
        try {
            Environment env = environment.get();
            DatabasePoolProperties properties = Binder.get(env).bind("database", DatabasePoolProperties.class)
                    .orElseGet(DatabasePoolProperties::new);
            effective.put("database.engine", properties.getEngine());
            effective.put("database.jdbc-url", properties.getJdbcUrl());
            effective.put("database.username", properties.getUsername());
            effective.put("database.password", properties.getPassword());
            if (env.containsProperty("database.provider")) effective.put("database.provider", properties.getProvider());
            effective.put("download.root-folder", env.getProperty("download.root-folder", RuntimeFiles.DEFAULT_DOWNLOAD_ROOT));
            for (String key : DEFAULTS.keySet()) {
                String value = effective.get(key);
                if (!value.equals(saved.get(key))) overrides.add(key);
            }
            if (!java.util.Objects.equals(effective.get("database.provider"), saved.get("database.provider"))) {
                overrides.add("database.provider");
            }
            DatabaseTarget next = target(effective);
            return new State(active.map(DatabaseTarget::description).orElse(""), next.description(), overrides,
                    active.filter(DatabaseTarget::available).map(value -> !value.sameConfiguration(next)).orElse(false));
        } catch (RuntimeException | IOException invalid) {
            return new State(active.map(DatabaseTarget::description).orElse(""), "", overrides,
                    active.filter(DatabaseTarget::available).isPresent());
        }
    }

    @Override
    public URI migrationGuide() {
        return URI.create("https://sywyar.github.io/PixivDownloader/#/en/configuration?id=main-database-and-existing-data");
    }

    /** 管理员编辑投影不含密码；无法验证的 URL 不回显，以免损坏配置里夹带凭据。 */
    public EditableSettings editableSettings() throws IOException {
        Map<String, String> values = saved();
        boolean valid;
        String provider = values.getOrDefault("database.provider", "");
        try {
            target(values);
            if (provider.isEmpty()) provider = selected(values).owner();
            valid = true;
        } catch (IOException invalid) { valid = false; }
        return new EditableSettings(provider, valid ? values.get("database.jdbc-url") : "",
                values.get("database.username"), !values.get("database.password").isEmpty(), !valid);
    }

    public record EditableSettings(String provider, String jdbcUrl, String username,
                                   boolean passwordStored, boolean connectionRedacted) {}

    private Map<String, String> saved() throws IOException {
        Map<String, String> result = new LinkedHashMap<>(DEFAULTS);
        var keys = new java.util.LinkedHashSet<>(DEFAULTS.keySet());
        keys.add("database.provider");
        result.putAll(config.readAll(keys));
        return result;
    }

    private DatabaseProviderSelection.Result selected(Map<String, String> values) throws IOException {
        var candidates = availableProviders.get().stream().filter(DatabaseProviderSelection.Result::available)
                .filter(value -> values.containsKey("database.provider")
                        ? value.owner().equals(values.get("database.provider"))
                        : value.databaseId().equals(values.get("database.engine")))
                .toList();
        if (candidates.size() != 1) throw new IOException("DATABASE_PROVIDER_UNAVAILABLE");
        return candidates.get(0);
    }

    private DatabaseTarget target(Map<String, String> values) throws IOException {
        try {
            var selection = selected(values);
            var settings = top.sywyar.pixivdownload.core.db.provider.DatabaseBootstrap.settings(
                    values.get("database.jdbc-url"), values.get("database.username"),
                    values.get("database.password"), values.get("download.root-folder"));
            var resolved = java.util.Objects.requireNonNull(selection.provider().orElseThrow().resolve(settings));
            var binding = new DatabaseProviderBinding(selection, Optional.of(settings), Optional.of(resolved),
                    java.util.Objects.requireNonNull(resolved.localFile()));
            return DatabaseTarget.fromBinding(binding);
        } catch (IOException controlled) {
            throw controlled;
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable invalid) {
            throw configurationFailure(invalid);
        }
    }

    private static IOException configurationFailure(Throwable failure) {
        String code = failure instanceof Exception exception ? ProviderDatabaseMigration.safeFailure(exception) : "MIGRATION_FAILED";
        if (DesktopDatabaseSettings.failureMessageKey(code).equals("gui.config.database.error.unknown")) {
            code = "DATABASE_CONFIGURATION_INVALID";
        }
        return new IOException(code);
    }
}
