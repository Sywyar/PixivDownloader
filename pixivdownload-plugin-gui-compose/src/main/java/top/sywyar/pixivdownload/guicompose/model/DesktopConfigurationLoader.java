package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultArgument;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultRule;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSource;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultSummary;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;
import static top.sywyar.pixivdownload.guicompose.model.DesktopConfigurationController.*;
import static top.sywyar.pixivdownload.guicompose.model.GuiActionResponseSafety.safeJsonPath;

/**
 * 加载、迁移并校验核心与插件贡献的桌面配置目录。
 */
final class DesktopConfigurationLoader {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopConfigurationLoader.class);
    private static final String APP_OWNER = "app";

    private final DesktopConfigurationController model;
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final DesktopRepositorySettingsController repositories;
    private final Map<FieldKey, String> values;
    private final Map<FieldKey, String> savedValues;

    DesktopConfigurationLoader(DesktopConfigurationController model) {
        this.model = Objects.requireNonNull(model, "model");
        this.owner = model.owner;
        this.host = model.host;
        this.repositories = model.repositories;
        this.values = model.values;
        this.savedValues = model.savedValues;
    }

    synchronized void load() {
        List<ConfigField> fields = new ArrayList<>();
        Map<String, GuiConfigGroupContribution> groups = new LinkedHashMap<>();
        host.coreConfigGroups().forEach(group -> groups.put(
                group.groupId(),
                group
        ));
        for (GuiConfigFieldContribution spec : host.coreConfigFields()) {
            fields.add(new ConfigField(
                    new FieldKey(null, spec.key()),
                    null,
                    spec,
                    groups.get(spec.groupId()),
                    null,
                    hasConditions(spec)
            ));
        }
        Set<FieldKey> accepted = new LinkedHashSet<>(fields.stream().map(ConfigField::key).toList());
        List<PluginConfig> plugins = new ArrayList<>();
        for (DesktopUiPluginSnapshot source : owner.currentSources()) {
            List<GuiConfigContribution> contributions;
            try {
                contributions = source.configContributions();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (contributions == null) continue;
            List<GuiConfigContribution> safeContributions = contributions.stream().filter(Objects::nonNull).toList();
            List<WebRouteContribution> routes;
            try {
                List<WebRouteContribution> declared = source.routes();
                routes = declared == null ? List.of() : declared.stream().filter(Objects::nonNull).toList();
            } catch (RuntimeException ignored) {
                routes = List.of();
            }
            String namespace = pluginNamespace(source);
            plugins.add(new PluginConfig(
                    source.id(),
                    namespace,
                    pluginDisplayNameKey(source),
                    safeContributions,
                    routes
            ));
            for (GuiConfigContribution contribution : safeContributions) {
                for (GuiConfigGroupContribution group : contribution.groups()) {
                    if (validGroup(group)) groups.putIfAbsent(group.groupId(), group);
                }
            }
        }
        for (PluginConfig plugin : plugins) {
            for (GuiConfigContribution contribution : plugin.contributions()) {
                for (GuiConfigFieldContribution spec : contribution.fields()) {
                    FieldKey key = spec == null ? null : new FieldKey(plugin.owner(), spec.key());
                    if (!validField(spec) || !accepted.add(key)) continue;
                    GuiConfigGroupContribution group = groups.get(spec.groupId());
                    if (group == null) {
                        group = new GuiConfigGroupContribution(
                                spec.groupId(),
                                plugin.displayNameKey(),
                                plugin.namespace(),
                                10_000,
                                true
                        );
                        groups.put(spec.groupId(), group);
                    }
                    String namespace = spec.i18nNamespace() == null ? plugin.namespace() : spec.i18nNamespace();
                    fields.add(new ConfigField(
                            key,
                            plugin.owner(),
                            spec,
                            group,
                            namespace,
                            hasConditions(spec)
                    ));
                }
            }
        }
        Set<FieldKey> conditionSources = new LinkedHashSet<>();
        for (ConfigField field : fields) {
            java.util.stream.Stream.concat(
                    field.spec().enabledWhen().stream(),
                    field.spec().visibleWhen().stream()
            ).filter(Objects::nonNull).forEach(condition -> conditionSources.add(new FieldKey(
                    field.owner(),
                    condition.key()
            )));
        }
        fields = fields.stream().map(field -> new ConfigField(
                field.key(),
                field.owner(),
                field.spec(),
                field.group(),
                field.namespace(),
                conditionSources.contains(field.key())
        )).toList();
        Map<FieldKey, ConfigField> trustedFields = new LinkedHashMap<>();
        fields.forEach(field -> trustedFields.put(field.key(), field));
        Map<String, ConfigSection> sections = new LinkedHashMap<>();
        Set<String> conflictedSections = new LinkedHashSet<>();
        for (PluginConfig plugin : plugins) {
            for (GuiConfigContribution contribution : plugin.contributions()) {
                for (GuiConfigSectionContribution declaration : contribution.sections()) {
                    ConfigSection incoming = configSection(
                            plugin,
                            declaration,
                            groups,
                            trustedFields
                    );
                    if (incoming == null || conflictedSections.contains(incoming.id())) continue;
                    ConfigSection existing = sections.get(incoming.id());
                    if (existing == null) {
                        sections.put(incoming.id(), incoming);
                    } else if (existing.mergeable() && incoming.mergeable() && existing.group().groupId().equals(
                            incoming.group().groupId()) && existing.layout() == incoming.layout()) {
                        sections.put(incoming.id(), mergeSections(existing, incoming));
                    } else {
                        sections.remove(incoming.id());
                        conflictedSections.add(incoming.id());
                    }
                }
            }
        }
        Map<String, List<ConfigField>> byOwner = new LinkedHashMap<>();
        for (ConfigField field : fields)
            byOwner.computeIfAbsent(
                    field.owner() == null ? APP_OWNER : field.owner(),
                    ignored -> new ArrayList<>()
            ).add(field);
        Map<FieldKey, String> loaded = new LinkedHashMap<>();
        Set<FieldKey> storedCredentials = new LinkedHashSet<>();
        for (Map.Entry<String, List<ConfigField>> entry : byOwner.entrySet()) {
            boolean app = APP_OWNER.equals(entry.getKey());
            if (!app) {
                loaded.putAll(loadPluginConfiguration(
                        entry.getKey(),
                        entry.getValue(),
                        storedCredentials
                ));
                continue;
            }
            DesktopUiHost.ConfigFile file = host.applicationConfig();
            List<String> keys = entry.getValue().stream().map(field -> field.spec().key()).toList();
            Map<String, String> stored;
            boolean readSucceeded;
            try {
                stored = file.readAll(keys);
                readSucceeded = true;
            } catch (Exception failure) {
                LOG.warn(
                        host.message("gui.config.log.read-failed", safeMessage(failure)),
                        failure
                );
                stored = Map.of();
                readSucceeded = false;
            }
            Map<String, String> missing = new LinkedHashMap<>();
            for (ConfigField field : entry.getValue()) {
                String value = field.spec().sensitive() ? "" : stored.getOrDefault(
                        field.spec().key(),
                        field.spec().defaultValue()
                );
                loaded.put(field.key(), value == null ? field.spec().defaultValue() : value);
                if (!stored.containsKey(field.spec().key())) {
                    missing.put(field.spec().key(), field.spec().defaultValue());
                }
            }
            if (readSucceeded && !missing.isEmpty()) {
                try {
                    file.writeAll(missing);
                    LOG.info(host.message(
                            "gui.config.log.missing-keys.completed",
                            missing.size(),
                            String.join(", ", missing.keySet())
                    ));
                } catch (Exception failure) {
                    LOG.warn(
                            host.message(
                                    "gui.config.log.missing-keys.failed",
                                    safeMessage(failure)
                            ),
                            failure
                    );
                }
            }
        }
        try {
            Map<String, String> special = host.applicationConfig().readAll(List.of(
                    "app.language",
                    "app.gui-provider",
                    "app.theme",
                    "app.config-menu-expand-all"
            ));
            special.forEach((
                    key,
                    value
            ) -> savedValues.put(
                    new FieldKey(null, key),
                    value
            ));
        } catch (Exception ignored) {
            // 保持下方默认值继续生效。
        }
        Set<FieldKey> loadedKeys = Set.copyOf(loaded.keySet());
        values.keySet().removeIf(key -> key.key().startsWith("app.") || loadedKeys.contains(key));
        values.putAll(loaded);
        savedValues.putAll(loaded);
        if (Boolean.parseBoolean(loaded.getOrDefault(
                new FieldKey(null, "debug.enabled"),
                "false"
        ))) {
            model.debugUnlocked = true;
        }
        model.storedCredentialFields = Set.copyOf(storedCredentials);
        model.configFields = List.copyOf(fields);
        model.configSections = sections.values().stream().sorted(Comparator.comparingInt((ConfigSection section) -> section.group().order()).thenComparingInt(
                ConfigSection::order).thenComparing(ConfigSection::id)).toList();
        repositories.load();

        checkFieldDrift();
    }

    private void checkFieldDrift() {
        Map<String, List<ConfigField>> fieldsByOwner = new LinkedHashMap<>();
        for (ConfigField field : model.configFields) {
            if (field.owner() != null && field.spec().sensitive()) continue;
            fieldsByOwner.computeIfAbsent(
                    field.owner() == null ? APP_OWNER : field.owner(),
                    ignored -> new ArrayList<>()
            ).add(field);
        }
        for (Map.Entry<String, List<ConfigField>> entry : fieldsByOwner.entrySet()) {
            try {
                DesktopUiHost.ConfigFile file = APP_OWNER.equals(entry.getKey()) ? host.applicationConfig() : host.pluginConfig(
                        entry.getKey());
                List<String> keys = entry.getValue().stream().map(field -> field.spec().key()).toList();
                Map<String, String> stored = file.readAll(keys);
                for (String key : keys) {
                    if (!stored.containsKey(key))
                        LOG.warn(host.message("gui.config.log.field-drift", key));
                }
            } catch (Exception failure) {
                LOG.warn(
                        host.message(
                                "gui.config.log.field-drift-check.failed",
                                safeMessage(failure)
                        ),
                        failure
                );
            }
        }
    }

    private Map<FieldKey, String> loadPluginConfiguration(
            String owner,
            List<ConfigField> fields,
            Set<FieldKey> storedCredentials
    ) {
        DesktopUiHost.ConfigFile application = host.applicationConfig();
        DesktopUiHost.ConfigFile plugin = host.pluginConfig(owner);
        List<String> keys = fields.stream().map(field -> field.spec().key()).toList();
        Map<String, String> pluginValues;
        Map<String, String> legacyValues;
        Map<String, String> credentials;
        try {
            pluginValues = plugin.readAll(keys);
            legacyValues = application.readAll(keys);
            credentials = host.readCredentials(owner);
        } catch (Exception failure) {
            LOG.warn(
                    "Unable to read plugin-owned desktop configuration for {}",
                    owner,
                    failure
            );
            return defaultPluginValues(fields);
        }

        Map<FieldKey, String> loaded = new LinkedHashMap<>();
        Map<String, String> pluginWrites = new LinkedHashMap<>();
        Map<String, String> credentialWrites = new LinkedHashMap<>();
        Set<String> pluginRemovals = new LinkedHashSet<>();
        Set<String> legacyRemovals = new LinkedHashSet<>();
        Set<FieldKey> migratedCredentials = new LinkedHashSet<>();
        for (ConfigField field : fields) {
            String key = field.spec().key();
            if (field.spec().sensitive()) {
                String credential = credentials.getOrDefault(key, "");
                if (credential.isBlank()) {
                    credential = pluginValues.containsKey(key) ? pluginValues.get(key) : legacyValues.getOrDefault(
                            key,
                            ""
                    );
                    if (!credential.isBlank()) credentialWrites.put(key, credential);
                }
                if (!credential.isBlank()) migratedCredentials.add(field.key());
                if (pluginValues.containsKey(key)) pluginRemovals.add(key);
                if (legacyValues.containsKey(key)) legacyRemovals.add(key);
                loaded.put(field.key(), "");
                continue;
            }
            String value;
            if (pluginValues.containsKey(key)) {
                value = pluginValues.get(key);
            } else if (legacyValues.containsKey(key)) {
                value = legacyValues.get(key);
                pluginWrites.put(key, value);
            } else {
                value = field.spec().defaultValue();
                pluginWrites.put(key, value);
            }
            if (legacyValues.containsKey(key)) legacyRemovals.add(key);
            loaded.put(field.key(), value == null ? field.spec().defaultValue() : value);
        }

        if (pluginWrites.isEmpty() && credentialWrites.isEmpty() && pluginRemovals.isEmpty() && legacyRemovals.isEmpty()) {
            storedCredentials.addAll(migratedCredentials);
            return loaded;
        }
        try {
            DesktopUiHost.ConfigSnapshot applicationSnapshot = application.snapshot();
            DesktopUiHost.ConfigSnapshot pluginSnapshot = plugin.snapshot();
            DesktopUiHost.CredentialSnapshot credentialSnapshot = host.snapshotCredentials(owner);
            try {
                host.withCredentialLocks(
                        Set.of(owner),
                        () -> {
                            if (!pluginWrites.isEmpty()) plugin.writeAll(pluginWrites);
                            if (!credentialWrites.isEmpty())
                                host.updateCredentials(owner, credentialWrites);
                            if (!pluginRemovals.isEmpty()) plugin.removeAll(pluginRemovals);
                            if (!legacyRemovals.isEmpty()) application.removeAll(legacyRemovals);
                        }
                );
            } catch (IOException failure) {
                try {
                    application.restore(applicationSnapshot);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
                try {
                    plugin.restore(pluginSnapshot);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
                try {
                    host.restoreCredentials(owner, credentialSnapshot);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
            storedCredentials.addAll(migratedCredentials);
            return loaded;
        } catch (Exception failure) {
            LOG.warn(
                    "Unable to migrate plugin-owned desktop configuration for {}",
                    owner,
                    failure
            );
            Map<FieldKey, String> fallback = new LinkedHashMap<>();
            for (ConfigField field : fields) {
                String key = field.spec().key();
                if (field.spec().sensitive()) {
                    fallback.put(field.key(), "");
                    if (!credentials.getOrDefault(key, "").isBlank())
                        storedCredentials.add(field.key());
                } else {
                    fallback.put(
                            field.key(),
                            pluginValues.getOrDefault(
                                    key,
                                    legacyValues.getOrDefault(key, field.spec().defaultValue())
                            )
                    );
                }
            }
            return fallback;
        }
    }

    private static Map<FieldKey, String> defaultPluginValues(List<ConfigField> fields) {
        Map<FieldKey, String> defaults = new LinkedHashMap<>();
        for (ConfigField field : fields) {
            defaults.put(
                    field.key(),
                    field.spec().sensitive() ? "" : field.spec().defaultValue()
            );
        }
        return defaults;
    }

    private ConfigSection configSection(
            PluginConfig plugin,
            GuiConfigSectionContribution spec,
            Map<String, GuiConfigGroupContribution> groups,
            Map<FieldKey, ConfigField> trustedFields
    ) {
        if (spec == null || !validId(spec.sectionId())) return null;
        GuiConfigGroupContribution group = groups.get(spec.groupId());
        if (group == null || spec.layout() == null) return null;
        String namespace = spec.i18nNamespace() == null ? plugin.namespace() : spec.i18nNamespace();
        List<ConfigLayout> layouts = new ArrayList<>();
        Set<FieldKey> layoutFields = new LinkedHashSet<>();
        for (GuiConfigFieldLayoutContribution layout : spec.fieldLayouts()) {
            if (layout == null || !validOptionalId(layout.cardId())) continue;
            FieldKey key = new FieldKey(plugin.owner(), layout.fieldKey());
            if (!trustedFields.containsKey(key) || !layoutFields.add(key)) continue;
            String layoutNamespace = layout.i18nNamespace() == null ? plugin.namespace() : layout.i18nNamespace();
            layouts.add(new ConfigLayout(
                    key,
                    layout.cardId(),
                    LocalizedText.optional(layoutNamespace, layout.cardLabelKey()),
                    layout.order()
            ));
        }
        layouts.sort(Comparator.comparingInt(ConfigLayout::order).thenComparing(layout -> layout.field().key()));

        Set<String> noticeCardIds = new LinkedHashSet<>();
        if (spec.layout() == GuiConfigSectionLayout.CARD_SWITCHER) {
            layouts.stream().map(ConfigLayout::cardId).filter(Objects::nonNull).forEach(
                    noticeCardIds::add);
        }
        List<ConfigNotice> notices = withoutConflictedKeys(
                spec.notices().stream().filter(Objects::nonNull).filter(notice -> validId(notice.noticeId()) && !notice.textKey().isBlank()).map(
                        notice -> new ConfigNotice(
                                notice.noticeId(),
                                LocalizedText.key(
                                        notice.i18nNamespace() == null ? plugin.namespace() : notice.i18nNamespace(),
                                        notice.textKey()
                                ),
                                Set.copyOf(noticeCardIds),
                                notice.order()
                        )).toList(),
                ConfigNotice::id
        ).stream().sorted(Comparator.comparingInt(ConfigNotice::order).thenComparing(ConfigNotice::id)).toList();

        List<ConfigAction> configActions = withoutConflictedKeys(
                spec.actions().stream().filter(Objects::nonNull).map(action -> configAction(
                        plugin,
                        action,
                        trustedFields
                )).filter(Objects::nonNull).toList(),
                action -> action.spec().actionId()
        ).stream().sorted(Comparator.comparingInt((ConfigAction action) -> action.spec().order()).thenComparing(
                action -> action.spec().actionId())).toList();
        List<ConfigPreset> presets = withoutConflictedKeys(
                spec.presets().stream().filter(Objects::nonNull).map(preset -> configPreset(
                        plugin,
                        preset,
                        trustedFields
                )).filter(Objects::nonNull).toList(),
                preset -> preset.spec().presetId()
        ).stream().sorted(Comparator.comparingInt((ConfigPreset preset) -> preset.spec().order()).thenComparing(
                preset -> preset.spec().presetId())).toList();

        return new ConfigSection(
                spec.sectionId(),
                group,
                spec.layout(),
                spec.order(),
                spec.mergeable(),
                spec.contributesGroupVisibility(),
                LocalizedText.optional(namespace, spec.titleKey()),
                LocalizedText.optional(namespace, spec.helpKey()),
                LocalizedText.optional(namespace, spec.layoutLabelKey()),
                LocalizedText.optional(namespace, spec.layoutHelpKey()),
                LocalizedText.optional(namespace, spec.presetLabelKey()),
                LocalizedText.optional(namespace, spec.presetHelpKey()),
                List.copyOf(layouts),
                configActions,
                presets,
                notices
        );
    }

    private ConfigAction configAction(
            PluginConfig plugin,
            GuiConfigActionContribution action,
            Map<FieldKey, ConfigField> trustedFields
    ) {
        if (!validId(action.actionId()) || action.labelKey() == null || action.labelKey().isBlank() || !validOptionalId(
                action.cardId()) || !validGuiEndpoint(action.endpoint()) || !hasExactGuiPostRoute(
                        plugin.routes(),
                        action.endpoint()
                ) || !validActionPayload(
                        plugin.owner(),
                        action.payloadFields(),
                        trustedFields
                ) || !validActionResult(
                        action.resultRules(),
                        action.resultSummary()
                )) return null;
        String namespace = action.i18nNamespace() == null ? plugin.namespace() : action.i18nNamespace();
        return new ConfigAction(
                plugin.owner(),
                namespace,
                action,
                LocalizedText.key(namespace, action.labelKey()),
                LocalizedText.optional(namespace, action.helpKey()),
                LocalizedText.optional(namespace, action.sendingNoticeKey()),
                action.readTimeoutMillis() <= 0 ? 30_000 : action.readTimeoutMillis()
        );
    }

    private ConfigPreset configPreset(
            PluginConfig plugin,
            GuiConfigPresetContribution preset,
            Map<FieldKey, ConfigField> trustedFields
    ) {
        if (!validId(preset.presetId()) || preset.labelKey() == null || preset.labelKey().isBlank() || !validOptionalId(
                preset.cardId())) return null;
        List<String> references = new ArrayList<>(preset.values().keySet());
        references.addAll(preset.lockedFieldKeys());
        if (preset.matchFieldKey() != null) references.add(preset.matchFieldKey());
        for (String key : references) {
            ConfigField field = trustedFields.get(new FieldKey(plugin.owner(), key));
            if (field == null || field.spec().sensitive() || field.spec().type() == GuiConfigFieldType.PASSWORD) {
                return null;
            }
        }
        for (Map.Entry<String, String> entry : preset.values().entrySet()) {
            ConfigField field = trustedFields.get(new FieldKey(
                    plugin.owner(),
                    entry.getKey()
            ));
            if (!validPresetValue(field, entry.getValue())) return null;
        }
        String namespace = preset.i18nNamespace() == null ? plugin.namespace() : preset.i18nNamespace();
        return new ConfigPreset(
                plugin.owner(),
                namespace,
                preset,
                LocalizedText.key(namespace, preset.labelKey()),
                LocalizedText.optional(namespace, preset.helpKey())
        );
    }

    private boolean validPresetValue(ConfigField field, String value) {
        if (field == null) return false;
        try {
            host.requireSafeConfigValue(value);
            return switch (field.spec().type()) {
                case BOOL -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
                case ENUM -> field.spec().enumValues().contains(value);
                case INT -> {
                    int number = Integer.parseInt(value);
                    yield (field.spec().minValue() == null || number >= field.spec().minValue()) && (field.spec().maxValue() == null || number <= field.spec().maxValue());
                }
                case PORT -> {
                    int port = Integer.parseInt(value);
                    yield port >= 1 && port <= 65_535;
                }
                case PATH_DIR, PATH_FILE, STRING, PASSWORD -> true;
            };
        } catch (Exception ignored) {
            return false;
        }
    }

    private static ConfigSection mergeSections(
            ConfigSection first,
            ConfigSection second
    ) {
        List<ConfigLayout> layouts = new ArrayList<>(first.layouts());
        layouts.addAll(second.layouts());
        layouts.sort(Comparator.comparingInt(ConfigLayout::order).thenComparing(layout -> layout.field().key()));
        List<ConfigAction> mergedActions = new ArrayList<>(first.actions());
        mergedActions.addAll(second.actions());
        List<ConfigAction> actions = new ArrayList<>(withoutConflictedKeys(
                mergedActions,
                action -> List.of(action.owner(), action.spec().actionId())
        ));
        actions.sort(Comparator.comparingInt((ConfigAction action) -> action.spec().order()).thenComparing(
                action -> action.spec().actionId()));
        List<ConfigPreset> mergedPresets = new ArrayList<>(first.presets());
        mergedPresets.addAll(second.presets());
        List<ConfigPreset> presets = new ArrayList<>(withoutConflictedKeys(
                mergedPresets,
                preset -> List.of(preset.owner(), preset.spec().presetId())
        ));
        presets.sort(Comparator.comparingInt((ConfigPreset preset) -> preset.spec().order()).thenComparing(
                preset -> preset.spec().presetId()));
        Map<String, ConfigNotice> notices = new LinkedHashMap<>();
        java.util.stream.Stream.concat(
                first.notices().stream(),
                second.notices().stream()
        ).sorted(
                Comparator.comparingInt(ConfigNotice::order).thenComparing(ConfigNotice::id)).forEach(
                notice -> notices.merge(
                        notice.id(),
                        notice,
                        (existing, incoming) -> {
                            if (existing.cardIds().isEmpty() || incoming.cardIds().isEmpty()) {
                                return new ConfigNotice(
                                        existing.id(),
                                        existing.text(),
                                        Set.of(),
                                        existing.order()
                                );
                            }
                            Set<String> cardIds = new LinkedHashSet<>(existing.cardIds());
                            cardIds.addAll(incoming.cardIds());
                            return new ConfigNotice(
                                    existing.id(),
                                    existing.text(),
                                    Set.copyOf(cardIds),
                                    existing.order()
                            );
                        }
                ));
        return new ConfigSection(
                first.id(),
                first.group(),
                first.layout(),
                Math.min(first.order(), second.order()),
                true,
                first.contributesGroupVisibility() || second.contributesGroupVisibility(),
                first.title() == null ? second.title() : first.title(),
                first.help() == null ? second.help() : first.help(),
                first.layoutLabel() == null ? second.layoutLabel() : first.layoutLabel(),
                first.layoutHelp() == null ? second.layoutHelp() : first.layoutHelp(),
                first.presetLabel() == null ? second.presetLabel() : first.presetLabel(),
                first.presetHelp() == null ? second.presetHelp() : first.presetHelp(),
                List.copyOf(layouts),
                List.copyOf(actions),
                List.copyOf(presets),
                List.copyOf(notices.values())
        );
    }

    private static <T, K> List<T> withoutConflictedKeys(
            List<T> items,
            Function<T, K> key
    ) {
        Map<K, T> accepted = new LinkedHashMap<>();
        Set<K> conflicted = new LinkedHashSet<>();
        for (T item : items) {
            K value = key.apply(item);
            if (conflicted.contains(value)) continue;
            if (accepted.putIfAbsent(value, item) != null) {
                accepted.remove(value);
                conflicted.add(value);
            }
        }
        return List.copyOf(accepted.values());
    }

    private static boolean validActionPayload(
            String owner,
            List<GuiConfigActionPayloadField> mappings,
            Map<FieldKey, ConfigField> trustedFields
    ) {
        Set<String> paths = new LinkedHashSet<>();
        for (GuiConfigActionPayloadField mapping : mappings) {
            if (mapping == null || !safeJsonPath(
                    mapping.payloadPath(),
                    false,
                    false
            ) || (mapping.fieldKey() == null && mapping.literalValue().isBlank())) return false;
            if (mapping.fieldKey() != null && !trustedFields.containsKey(new FieldKey(
                    owner,
                    mapping.fieldKey()
            ))) return false;
            for (String path : paths) {
                if (path.equals(mapping.payloadPath()) || path.startsWith(mapping.payloadPath() + ".") || mapping.payloadPath().startsWith(
                        path + ".")) return false;
            }
            paths.add(mapping.payloadPath());
        }
        return true;
    }

    private static boolean validActionResult(
            List<GuiConfigActionResultRule> rules,
            GuiConfigActionResultSummary summary
    ) {
        boolean hasSummary = summary != null;
        if (summary != null && (!safeJsonPath(
                summary.arrayPath(),
                false,
                true
        ) || !safeJsonPath(
                summary.labelPath(),
                false,
                true
        ) || !safeJsonPath(
                summary.statusPath(),
                true,
                true
        ) || !safeJsonPath(summary.detailPath(), true, true))) return false;
        for (GuiConfigActionResultRule rule : rules) {
            if (rule == null || rule.noticeKey() == null || rule.noticeKey().isBlank())
                return false;
            for (GuiConfigActionResultCondition condition : rule.conditions()) {
                if (condition == null || !validResultReference(
                        condition.source(),
                        condition.path(),
                        hasSummary
                )) {
                    return false;
                }
            }
            for (GuiConfigActionResultArgument argument : rule.arguments()) {
                if (argument == null || !validResultReference(
                        argument.source(),
                        argument.path(),
                        hasSummary
                )) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validResultReference(
            GuiConfigActionResultSource source,
            String path,
            boolean hasSummary
    ) {
        if (source == null) return false;
        return switch (source) {
            case JSON -> safeJsonPath(path, false, true);
            case SUMMARY -> hasSummary && nullToEmpty(path).isBlank();
            case REACHABLE, HTTP_2XX, HTTP_STATUS, HTTP_STATUS_TEXT -> nullToEmpty(path).isBlank();
        };
    }

    private static boolean validGuiEndpoint(String endpoint) {
        return endpoint != null && !endpoint.isBlank() && !endpoint.startsWith("/") && !endpoint.contains(
                "://") && !endpoint.contains("?") && !endpoint.contains("#") && !endpoint.contains(
                "\\") && java.util.Arrays.stream(endpoint.split("/")).allMatch(part -> validId(part) && !".".equals(
                part) && !"..".equals(part));
    }

    private static boolean hasExactGuiPostRoute(
            List<WebRouteContribution> routes,
            String endpoint
    ) {
        String path = "/api/gui/" + endpoint;
        return routes.stream().anyMatch(route -> path.equals(route.pathPattern()) && route.accessPolicy() == AccessPolicy.GUI && route.acceptsMethod(
                HttpMethod.POST));
    }

    private static boolean validOptionalId(String value) {
        return value == null || validId(value);
    }

    private static String pluginNamespace(DesktopUiPluginSnapshot source) {
        try {
            String namespace = source.displayNamespace();
            return namespace == null || namespace.isBlank() ? source.id() : namespace;
        } catch (RuntimeException ignored) {
            return source.id();
        }
    }

    private static String pluginDisplayNameKey(DesktopUiPluginSnapshot source) {
        try {
            String key = source.displayNameKey();
            return key == null || key.isBlank() ? source.id() : key;
        } catch (RuntimeException ignored) {
            return source.id();
        }
    }

    private static boolean hasConditions(GuiConfigFieldContribution spec) {
        return !spec.enabledWhen().isEmpty() || !spec.visibleWhen().isEmpty();
    }

    private boolean validField(GuiConfigFieldContribution field) {
        if (field == null || field.type() == null || field.effect() == null || field.key() == null || field.key().isBlank() || field.labelKey() == null || field.labelKey().isBlank() || field.groupId() == null || field.groupId().isBlank())
            return false;
        try {
            host.requireSafeConfigKey(field.key());
            host.requireSafeConfigValue(field.defaultValue());
            if (field.type() == GuiConfigFieldType.ENUM) {
                if (field.enumValues().isEmpty() || !field.enumValues().contains(field.defaultValue()) || field.enumValues().stream().anyMatch(
                        Objects::isNull) || new LinkedHashSet<>(field.enumValues()).size() != field.enumValues().size())
                    return false;
                for (String value : field.enumValues()) host.requireSafeConfigValue(value);
            }
            if (field.type() == GuiConfigFieldType.INT || field.type() == GuiConfigFieldType.PORT) {
                int minimum = field.type() == GuiConfigFieldType.PORT ? 1 : field.minValue() == null ? Integer.MIN_VALUE : field.minValue();
                int maximum = field.type() == GuiConfigFieldType.PORT ? 65_535 : field.maxValue() == null ? Integer.MAX_VALUE : field.maxValue();
                int value = Integer.parseInt(field.defaultValue());
                if (minimum > maximum || value < minimum || value > maximum) return false;
            }
            return bindingId(new FieldKey("third-party", field.key())).length() <= 128;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean validGroup(GuiConfigGroupContribution group) {
        return group != null && group.visibleInTabs() && group.groupId() != null && group.groupId().matches(
                "[A-Za-z0-9][A-Za-z0-9._:-]{0,80}") && group.labelKey() != null && !group.labelKey().isBlank();
    }

    private static String bindingId(FieldKey key) {
        return "config." + safeId(key.owner() == null ? APP_OWNER : key.owner()) + "." + safeId(key.key());
    }
}
