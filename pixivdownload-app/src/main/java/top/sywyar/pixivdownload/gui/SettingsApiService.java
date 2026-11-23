package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.gui.config.DatabaseSettingsService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.ConfigFile;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** 按可信字段声明读写设置，与桌面共用配置锁、凭据 owner 锁和回滚合同。 */
@Service
public final class SettingsApiService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SettingsApiService.class);
    private final DesktopUiHost host;
    private final Supplier<List<DesktopUiPluginSnapshot>> plugins;
    private final DatabaseSettingsService database;
    private java.util.function.Consumer<String> applyLocalPresets = ignored -> {};

    @Autowired
    public SettingsApiService(ApplicationArguments arguments, PluginBootstrapSession session, DatabaseTarget target,
                              PluginRegistry registry, DatabaseSettingsService database,
                              top.sywyar.pixivdownload.core.importing.LocalImportConfig localImports) {
        this(new AppDesktopUiHost(0, arguments.getSourceArgs(), session, target),
                () -> GuiLauncher.buildDesktopUiPluginSnapshots(GuiLauncher.buildDesktopUiPluginSources(registry)), database);
        applyLocalPresets = localImports::setPresets;
    }

    SettingsApiService(DesktopUiHost host, Supplier<List<DesktopUiPluginSnapshot>> plugins, DatabaseSettingsService database) {
        this.host = host;
        this.plugins = plugins;
        this.database = database;
    }

    public enum Action { KEEP, SET, CLEAR }
    public record Change(@HttpApiNullable String owner, @HttpApiNullable String packageId, long generation, String key, @HttpApiNullable Action action, @HttpApiNullable String value) {
        @Override public String toString() { return "Change[redacted]"; }
    }
    public record Field(@HttpApiNullable String owner, @HttpApiNullable String packageId, long generation, String key, String groupId, String labelKey, String helpKey,
                        @HttpApiNullable String namespace, GuiConfigFieldType type, GuiConfigEffect effect, List<String> enumValues,
                        @HttpApiNullable Integer minValue, @HttpApiNullable Integer maxValue, List<GuiConfigCondition> requiredWhen,
                        List<GuiConfigCondition> enabledWhen, List<GuiConfigCondition> visibleWhen,
                        boolean sensitive, @HttpApiNullable String value, boolean stored, @HttpApiNullable String defaultValue,
                        Map<String, String> enumValueLabelKeys) {}
    public record Preset(@HttpApiNullable String owner, @HttpApiNullable String packageId, long generation, String sectionId,
                         String id, @HttpApiNullable String namespace, String labelKey, String helpKey, Map<String, String> values) {}

    public List<Preset> presets() throws IOException {
        var result = new ArrayList<Preset>();
        var owners = catalog();
        appendPresets(result, owners.get(null), host.coreConfigSections());
        for (var plugin : plugins.get()) {
            var owner = owners.get(plugin.id());
            if (owner == null || owner.generation() != plugin.generation() || !Objects.equals(owner.packageId(), plugin.packageId())) continue;
            appendPresets(result, owner, plugin.configContributions().stream().flatMap(value -> value.sections().stream()).toList());
        }
        return List.copyOf(result);
    }

    private static void appendPresets(List<Preset> result, Owner owner, List<GuiConfigSectionContribution> sections) {
        for (var section : sections) for (var preset : section.presets()) {
            if (preset.values().keySet().stream().anyMatch(key -> !owner.fields().containsKey(key) || owner.fields().get(key).sensitive())) {
                LOG.warn("Settings preset rejected owner={} reason=invalid-fields", owner.id());
                continue;
            }
            result.add(new Preset(owner.id(), owner.packageId(), owner.generation(), section.sectionId(), preset.presetId(),
                    preset.i18nNamespace() == null ? owner.namespace() : preset.i18nNamespace(), preset.labelKey(), preset.helpKey(), preset.values()));
        }
    }
    public record Saved(@HttpApiNullable String owner, String key, GuiConfigEffect effect) {}
    static final class InvalidField extends IllegalArgumentException {
        final String owner;
        final String key;
        final String messageKey;
        final String variable;
        final String field;
        final String reason;
        InvalidField(String code, String owner, String key) {
            this(code, owner, key, null, null);
        }
        InvalidField(String code, String owner, String key, String messageKey, String variable) {
            this(code, owner, key, messageKey, variable, null);
        }
        InvalidField(String code, String owner, String key, String messageKey, String variable, String field) {
            this(code, owner, key, messageKey, variable, field, code.equals("SETTINGS_REQUIRED") ? "required" : "invalid_value");
        }
        InvalidField(String code, String owner, String key, String messageKey, String variable, String field, String reason) {
            super(code);
            this.field = field;
            this.reason = reason;
            this.owner = owner;
            this.key = key;
            this.messageKey = messageKey;
            this.variable = variable;
        }
    }
    private record Owner(String id, String packageId, long generation, String namespace,
                         Map<String, GuiConfigFieldContribution> fields, ConfigFile file) {}

    public List<Field> fields() throws IOException {
        var result = new ArrayList<Field>();
        host.applicationConfig().withWriteLock(() -> {
            var owners = catalog();
            host.withCredentialLocks(pluginOwners(owners.values()), () -> {
                for (var owner : owners.values()) {
                    var normal = owner.file().readAll(owner.fields().keySet());
                    var secrets = owner.id() == null ? normal : host.readCredentials(owner.id());
                    for (var field : owner.fields().values()) {
                        String value = field.sensitive() ? null : normal.getOrDefault(field.key(), field.defaultValue());
                        if (owner.id() == null && field.key().equals("database.jdbc-url")) value = database.editableSettings().jdbcUrl();
                        result.add(new Field(owner.id(), owner.packageId(), owner.generation(), field.key(), field.groupId(), field.labelKey(), field.helpKey(),
                                field.i18nNamespace() == null ? owner.namespace() : field.i18nNamespace(), field.type(),
                                field.effect(), field.enumValues(), field.minValue(), field.maxValue(), field.requiredWhen(),
                                field.enabledWhen(), field.visibleWhen(), field.sensitive(), value,
                                field.sensitive() && !secrets.getOrDefault(field.key(), "").isBlank(),
                                field.sensitive() ? null : field.defaultValue(), field.enumValueLabelKeys()));
                    }
                }
            });
        });
        return List.copyOf(result);
    }

    static InvalidField invalidChange(Change change, String field, String code) {
        return new InvalidField(code, change == null ? null : change.owner(), change == null ? null : change.key(),
                null, null, field);
    }

    static InvalidField missingChange(Change change, String field) {
        return new InvalidField("INVALID_TOOL_REQUEST", change == null ? null : change.owner(), change == null ? null : change.key(),
                null, null, field, "required");
    }

    public List<Saved> save(List<Change> changes, boolean validateOnly) throws IOException {
        if (changes == null || changes.isEmpty()) throw missingChange(null, "/changes");
        var saved = new ArrayList<Saved>();
        host.applicationConfig().withWriteLock(() -> {
            var owners = catalog();
            var updates = new LinkedHashMap<Owner, Map<String, String>>();
            var seen = new HashSet<List<String>>();
            for (int i = 0; i < changes.size(); i++) {
                var change = changes.get(i);
                String path = "/changes/" + i;
                if (change == null) throw missingChange(null, path);
                if (change.key() == null) throw missingChange(change, path + "/key");
                var owner = owners.get(change.owner());
                if (owner == null) throw invalidChange(change, path + "/owner", "SETTINGS_OWNER_CHANGED");
                if (!Objects.equals(owner.packageId(), change.packageId()))
                    throw invalidChange(change, path + "/packageId", "SETTINGS_OWNER_CHANGED");
                if (owner.generation() != change.generation())
                    throw invalidChange(change, path + "/generation", "SETTINGS_OWNER_CHANGED");
                var field = owner.fields().get(change.key());
                if (field == null || !seen.add(Arrays.asList(change.owner(), change.key())))
                    throw invalidChange(change, path + "/key", "INVALID_TOOL_REQUEST");
                Action action = change.action() == null ? Action.SET : change.action();
                if (action != Action.SET && !field.sensitive())
                    throw invalidChange(change, path + "/action", "INVALID_TOOL_REQUEST");
                if (action != Action.SET && change.value() != null)
                    throw invalidChange(change, path + "/value", "INVALID_TOOL_REQUEST");
                if (action == Action.KEEP) continue;
                if (action == Action.SET && (change.value() == null || field.sensitive() && change.value().isBlank()))
                    throw missingChange(change, path + "/value");
                String value = action == Action.CLEAR ? "" : change.value();
                updates.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(change.key(), value);
                saved.add(new Saved(owner.id(), field.key(), field.effect()));
            }
            try {
                host.withCredentialLocks(pluginOwners(updates.keySet()), () -> persist(updates, validateOnly));
            } catch (InvalidField invalid) {
                for (int i = 0; i < changes.size(); i++) {
                    Change change = changes.get(i);
                    if (Objects.equals(change.owner(), invalid.owner) && Objects.equals(change.key(), invalid.key))
                        throw new InvalidField(invalid.getMessage(), invalid.owner, invalid.key, invalid.messageKey, invalid.variable,
                                "/changes/" + i + "/value");
                }
                throw invalid;
            }
        });
        if (!validateOnly) LOG.info("Settings saved through HTTP fields={} owners={}", saved.size(),
                saved.stream().map(Saved::owner).distinct().count());
        return List.copyOf(saved);
    }

    private void persist(Map<Owner, Map<String, String>> updates, boolean validateOnly) throws IOException {
        var normal = new LinkedHashMap<Owner, Map<String, String>>();
        var secrets = new LinkedHashMap<String, Map<String, String>>();
        for (var entry : updates.entrySet()) {
            var owner = entry.getKey();
            var values = new LinkedHashMap<String, String>();
            owner.fields().forEach((key, field) -> values.put(key, field.defaultValue()));
            values.putAll(owner.file().readAll(owner.fields().keySet()));
            if (owner.id() != null) values.putAll(host.readCredentials(owner.id()));
            values.putAll(entry.getValue());
            for (var field : owner.fields().values()) {
                if (field.missingRequiredValue(values)) throw new InvalidField("SETTINGS_REQUIRED", owner.id(), field.key());
            }
            for (var update : entry.getValue().entrySet()) {
                var field = owner.fields().get(update.getKey());
                try { validate(owner, field, update.getValue()); }
                catch (IOException | IllegalArgumentException invalid) {
                    Throwable reason = invalid.getCause() == null ? invalid : invalid.getCause();
                    if (reason instanceof top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException template)
                        throw new InvalidField("DIRECTORY_TEMPLATE_" + template.reason().replace('-', '_').toUpperCase(Locale.ROOT), owner.id(), field.key(),
                                "directory-template." + template.reason(), template.variable());
                    if (reason instanceof top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.VariableException variable)
                        throw new InvalidField(variable.getMessage(), owner.id(), field.key(),
                                "local-import.error." + variable.getMessage(), variable.variable());
                    throw new InvalidField("INVALID_SETTINGS_VALUE", owner.id(), field.key());
                }
                if (field.sensitive() && owner.id() != null)
                    secrets.computeIfAbsent(owner.id(), ignored -> new LinkedHashMap<>()).put(update.getKey(), update.getValue());
                else normal.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(update.getKey(), update.getValue());
            }
        }
        for (var entry : normal.entrySet()) if (entry.getKey().id() == null)
            entry.setValue(database.prepareForApi(entry.getValue()));
        if (validateOnly) return;
        var snapshots = new LinkedHashMap<Owner, DesktopUiHost.ConfigSnapshot>();
        var credentialSnapshots = new LinkedHashMap<String, DesktopUiHost.CredentialSnapshot>();
        for (var owner : normal.keySet()) snapshots.put(owner, owner.file().snapshot());
        for (var owner : secrets.keySet()) credentialSnapshots.put(owner, host.snapshotCredentials(owner));
        try {
            for (var entry : normal.entrySet()) entry.getKey().file().writeAll(entry.getValue());
            for (var entry : secrets.entrySet()) host.updateCredentials(entry.getKey(), entry.getValue());
            for (var entry : normal.entrySet()) if (entry.getKey().id() == null && entry.getValue().containsKey("local-import.presets"))
                applyLocalPresets.accept(entry.getValue().get("local-import.presets"));
        } catch (IOException | RuntimeException failure) {
            for (var entry : snapshots.entrySet()) {
                try { entry.getKey().file().restore(entry.getValue()); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            for (var entry : credentialSnapshots.entrySet()) {
                try { host.restoreCredentials(entry.getKey(), entry.getValue()); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    private void validate(Owner owner, GuiConfigFieldContribution field, String value) throws IOException {
        host.requireSafeConfigValue(value);
        boolean valid = switch (field.type()) {
            case BOOL -> Set.of("true", "false").contains(value);
            case INT, PORT -> {
                int number = Integer.parseInt(value);
                yield (field.type() != GuiConfigFieldType.PORT || number >= 1 && number <= 65535)
                        && (field.minValue() == null || number >= field.minValue())
                        && (field.maxValue() == null || number <= field.maxValue());
            }
            case ENUM -> field.enumValues().contains(value);
            case MULTI_ENUM -> GuiConfigFieldType.validMultiSelection(value, field.enumValues());
            case TIME -> host.validMaintenanceTime(value);
            case DIRECTORY_TEMPLATE -> { host.validateDirectoryTemplate(value); yield true; }
            case PATH_TEMPLATES -> { host.parseLocalImportPresets(value); yield true; }
            default -> true;
        };
        if (!valid) throw new IllegalArgumentException("INVALID_SETTINGS_VALUE");
        if (owner.id() == null) host.validateCoreConfigValue(field.key(), value);
    }

    boolean hasSettingsOwner(String owner) {
        return owner == null || plugins.get().stream().anyMatch(plugin -> plugin.id().equals(owner)
                && plugin.configContributions().stream().anyMatch(group -> !group.fields().isEmpty()));
    }

    private Map<String, Owner> catalog() throws IOException {
        var owners = new LinkedHashMap<String, Owner>();
        var snapshots = plugins.get();
        var core = new ArrayList<>(host.coreConfigFields());
        var languages = new ArrayList<String>();
        languages.add("");
        host.visibleLocales().forEach(locale -> languages.add(locale.tag()));
        var themes = new LinkedHashSet<>(List.of("system", "light", "dark"));
        snapshots.forEach(plugin -> plugin.themes().forEach(theme -> themes.add(theme.themeId())));
        var providers = new ArrayList<String>();
        providers.add("");
        snapshots.stream().filter(DesktopUiPluginSnapshot::desktopUiProvider).forEach(plugin -> providers.add(plugin.id()));
        core.add(preference("app.language", "language", GuiConfigFieldType.ENUM, "", languages));
        core.add(preference("app.theme", "theme", GuiConfigFieldType.ENUM, "system", List.copyOf(themes)));
        core.add(preference("app.gui-provider", "provider", GuiConfigFieldType.ENUM, "", providers));
        core.add(preference("app.config-menu-expand-all", "config-menu-expand-all", GuiConfigFieldType.BOOL, "false", List.of()));
        if (core.stream().noneMatch(field -> field.key().equals("local-import.presets")))
            core.add(new GuiConfigFieldContribution("local-import.presets", "tools", "local-import.presets",
                    "local-import.presets-save-direct-help", null, GuiConfigFieldType.PATH_TEMPLATES, "[]", 0,
                    false, GuiConfigEffect.HOT_RELOAD, List.of(), List.of(), List.of(), null, null, true, Map.of()));
        owners.put(null, new Owner(null, null, 0, null, fields(core), host.applicationConfig()));
        for (var plugin : snapshots) {
            var declarations = plugin.configContributions().stream().flatMap(value -> value.fields().stream()).toList();
            if (declarations.isEmpty()) continue;
            try {
                owners.put(plugin.id(), new Owner(plugin.id(), plugin.packageId(), plugin.generation(), plugin.displayNamespace(),
                        fields(declarations), host.pluginConfig(plugin.id())));
            } catch (IllegalArgumentException | IOException invalid) {
                LOG.warn("Settings schema rejected owner={} failureType={}", plugin.id(), invalid.getClass().getName());
            }
        }
        return owners;
    }

    private static GuiConfigFieldContribution preference(String key, String label, GuiConfigFieldType type,
                                                          String defaultValue, List<String> options) {
        return new GuiConfigFieldContribution(key, "interface", "gui.interface." + label + ".label",
                "gui.interface." + label + ".help", null, type, defaultValue, 0, false,
                GuiConfigEffect.PROCESS_RESTART, options, List.of(), List.of(), null, null, true, Map.of());
    }

    private Map<String, GuiConfigFieldContribution> fields(List<GuiConfigFieldContribution> fields) throws IOException {
        var result = new LinkedHashMap<String, GuiConfigFieldContribution>();
        for (var field : fields) {
            if (field == null || field.type() == null || result.putIfAbsent(host.requireSafeConfigKey(field.key()), field) != null)
                throw new IllegalArgumentException("INVALID_SETTINGS_SCHEMA");
        }
        for (var field : result.values()) {
            if (java.util.stream.Stream.of(field.requiredWhen(), field.enabledWhen(), field.visibleWhen())
                    .flatMap(Collection::stream).anyMatch(condition -> condition == null || condition.operator() == null
                            || !result.containsKey(condition.key())))
                throw new IllegalArgumentException("INVALID_SETTINGS_SCHEMA");
        }
        return result;
    }

    private static List<String> pluginOwners(Collection<Owner> owners) {
        return owners.stream().map(Owner::id).filter(Objects::nonNull).distinct().sorted().toList();
    }
}
