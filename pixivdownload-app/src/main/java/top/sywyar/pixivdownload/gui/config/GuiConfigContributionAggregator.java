package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigConditionOperator;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionPayloadField;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldLayoutContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigPresetContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Aggregates GUI configuration fields from active plugins into a classloader-aware immutable snapshot.
 */
public final class GuiConfigContributionAggregator {

    private static final String CORE_OWNER = "core";

    private GuiConfigContributionAggregator() {
    }

    public static GuiConfigContributionSnapshot from(PluginRegistry pluginRegistry) {
        if (pluginRegistry == null) {
            return GuiConfigContributionSnapshot.empty();
        }
        return fromRegisteredPlugins(pluginRegistry.registeredPlugins());
    }

    public static GuiConfigContributionSnapshot fromRegisteredPlugins(
            List<PluginRegistry.RegisteredPlugin> registeredPlugins) {
        if (registeredPlugins == null || registeredPlugins.isEmpty()) {
            return GuiConfigContributionSnapshot.empty();
        }

        List<GuiConfigContributionDiagnostic> diagnostics = new ArrayList<>();
        Map<String, GroupEntry> customGroups = new LinkedHashMap<>();
        List<PluginContributions> contributions = new ArrayList<>();

        for (PluginRegistry.RegisteredPlugin registered : registeredPlugins) {
            if (registered == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic("unknown", null,
                        "null registered plugin while aggregating GUI config contributions"));
                continue;
            }
            PixivFeaturePlugin plugin = registered.plugin();
            List<GuiConfigContribution> pluginContributions;
            try {
                pluginContributions = plugin.guiConfigContributions();
            } catch (RuntimeException e) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                        "GUI config contribution threw: " + safeMessage(e)));
                continue;
            }
            if (pluginContributions == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                        "GUI config contribution list is null"));
                continue;
            }
            PluginTextResolver textResolver = PluginTextResolver.create(registered, diagnostics);
            List<GuiConfigContribution> valid = new ArrayList<>();
            for (GuiConfigContribution contribution : pluginContributions) {
                if (contribution == null) {
                    diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                            "null GUI config contribution"));
                    continue;
                }
                valid.add(contribution);
                registerGroups(registered, textResolver, contribution.groups(), customGroups, diagnostics);
            }
            if (!valid.isEmpty()) {
                contributions.add(new PluginContributions(registered, textResolver, List.copyOf(valid)));
            }
        }

        List<AcceptedField> accepted = collectFields(contributions, customGroups, diagnostics);
        List<GuiConfigSectionSpec> sections = collectSections(contributions, customGroups, diagnostics);
        List<ConfigFieldSpec> fields = accepted.stream()
                .sorted(Comparator
                        .comparingInt(AcceptedField::groupOrder)
                        .thenComparingInt(AcceptedField::fieldOrder)
                        .thenComparing(AcceptedField::pluginId)
                        .thenComparing(AcceptedField::key))
                .map(AcceptedField::spec)
                .toList();

        List<ConfigGroupSpec> groups = customGroups.values().stream()
                .map(GroupEntry::spec)
                .toList();
        return new GuiConfigContributionSnapshot(groups, fields, sections, diagnostics);
    }

    private static void registerGroups(PluginRegistry.RegisteredPlugin registered,
                                       PluginTextResolver textResolver,
                                       List<GuiConfigGroupContribution> groups,
                                       Map<String, GroupEntry> customGroups,
                                       List<GuiConfigContributionDiagnostic> diagnostics) {
        for (GuiConfigGroupContribution group : groups) {
            if (group == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                        "null GUI config group contribution"));
                continue;
            }
            String groupId = normalize(group.groupId());
            if (groupId == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                        "GUI config group id is blank"));
                continue;
            }
            if (ConfigFieldRegistry.hasGroupId(groupId)) {
                continue;
            }
            if (normalize(group.labelKey()) == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                        "GUI config group label key is blank: " + groupId));
                continue;
            }
            GroupEntry existing = customGroups.get(groupId);
            if (existing != null && !existing.pluginId().equals(registered.id())) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                        "duplicate GUI config group id '" + groupId + "' from plugin '" + registered.id()
                                + "' conflicts with plugin '" + existing.pluginId() + "'"));
                continue;
            }
            String label = textResolver.groupText(groupId, group.i18nNamespace(), group.labelKey(), diagnostics);
            if (label == null) {
                continue;
            }
            customGroups.put(groupId, new GroupEntry(registered.id(),
                    new ConfigGroupSpec(groupId, label, group.order(), group.visibleInTabs())));
        }
    }

    private static List<AcceptedField> collectFields(List<PluginContributions> contributions,
                                                     Map<String, GroupEntry> customGroups,
                                                     List<GuiConfigContributionDiagnostic> diagnostics) {
        Map<String, String> ownerByKey = new LinkedHashMap<>();
        for (String key : ConfigFieldRegistry.coreFieldKeys()) {
            ownerByKey.put(key, CORE_OWNER);
        }
        Set<String> suppressedDuplicateKeys = new HashSet<>();
        List<AcceptedField> accepted = new ArrayList<>();

        for (PluginContributions pluginContributions : contributions) {
            PluginRegistry.RegisteredPlugin registered = pluginContributions.registered();
            PluginTextResolver textResolver = pluginContributions.textResolver();
            for (GuiConfigContribution contribution : pluginContributions.contributions()) {
                for (GuiConfigFieldContribution field : contribution.fields()) {
                    AcceptedField acceptedField = toField(registered, textResolver, field, customGroups, diagnostics);
                    if (acceptedField == null) {
                        continue;
                    }
                    String key = acceptedField.key();
                    String existingOwner = ownerByKey.get(key);
                    if (existingOwner != null || suppressedDuplicateKeys.contains(key)) {
                        diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), key,
                                duplicateMessage(key, registered.id(), existingOwner)));
                        if (!CORE_OWNER.equals(existingOwner)) {
                            accepted.removeIf(item -> item.key().equals(key));
                        }
                        suppressedDuplicateKeys.add(key);
                        continue;
                    }
                    ownerByKey.put(key, registered.id());
                    accepted.add(acceptedField);
                }
            }
        }
        return accepted;
    }

    private static AcceptedField toField(PluginRegistry.RegisteredPlugin registered,
                                         PluginTextResolver textResolver,
                                         GuiConfigFieldContribution field,
                                         Map<String, GroupEntry> customGroups,
                                         List<GuiConfigContributionDiagnostic> diagnostics) {
        if (field == null) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                    "null GUI config field contribution"));
            return null;
        }
        String key = normalize(field.key());
        if (key == null) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                    "GUI config field key is blank"));
            return null;
        }
        String groupId = normalize(field.groupId());
        if (groupId == null) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), key,
                    "GUI config field group id is blank"));
            return null;
        }
        if (!ConfigFieldRegistry.hasGroupId(groupId) && !customGroups.containsKey(groupId)) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), key,
                    "GUI config field references unknown group id '" + groupId + "'"));
            return null;
        }
        String labelKey = normalize(field.labelKey());
        if (labelKey == null) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), key,
                    "GUI config field label key is blank"));
            return null;
        }
        if (field.type() == null) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), key,
                    "GUI config field type is null"));
            return null;
        }
        if (field.type() == GuiConfigFieldType.ENUM && field.enumValues().isEmpty()) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), key,
                    "GUI config enum field has no enum values"));
            return null;
        }
        if (!validConditions(field.enabledWhen()) || !validConditions(field.visibleWhen())) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), key,
                    "GUI config field contains an invalid condition"));
            return null;
        }
        if (field.minValue() != null && field.maxValue() != null && field.minValue() > field.maxValue()) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), key,
                    "GUI config field minValue is greater than maxValue"));
            return null;
        }

        String label = textResolver.fieldText(key, field.i18nNamespace(), labelKey, "label", diagnostics);
        if (label == null) {
            return null;
        }
        String helpKey = normalize(field.helpKey());
        String help = "";
        if (helpKey != null) {
            help = textResolver.fieldText(key, field.i18nNamespace(), helpKey, "help", diagnostics);
            if (help == null) {
                return null;
            }
        }
        String groupLabel = ConfigFieldRegistry.groupLabel(groupId)
                .orElseGet(() -> customGroups.get(groupId).spec().label());
        FieldType type = mapFieldType(field.type(), field.sensitive());
        ConfigFieldSpec.Builder builder = ConfigFieldSpec.builder(key, label, type, groupLabel)
                .defaultValue(field.defaultValue())
                .help(help)
                .enabledWhen(predicate(field.enabledWhen()))
                .visibleWhen(predicate(field.visibleWhen()))
                .validator(validator(field));
        if (field.type() == GuiConfigFieldType.ENUM) {
            builder.enumValues(field.enumValues().toArray(String[]::new));
        }
        if (!field.requiresRestart()) {
            builder.hotReloadable();
        }
        int groupOrder = ConfigFieldRegistry.groupOrder(groupId)
                .orElseGet(() -> customGroups.get(groupId).spec().order());
        return new AcceptedField(registered.id(), key, builder.build(), groupOrder, field.order());
    }

    private static List<GuiConfigSectionSpec> collectSections(List<PluginContributions> contributions,
                                                              Map<String, GroupEntry> customGroups,
                                                              List<GuiConfigContributionDiagnostic> diagnostics) {
        Map<String, String> ownerBySectionId = new LinkedHashMap<>();
        List<GuiConfigSectionSpec> accepted = new ArrayList<>();
        for (PluginContributions pluginContributions : contributions) {
            PluginRegistry.RegisteredPlugin registered = pluginContributions.registered();
            PluginTextResolver textResolver = pluginContributions.textResolver();
            for (GuiConfigContribution contribution : pluginContributions.contributions()) {
                for (GuiConfigSectionContribution section : contribution.sections()) {
                    GuiConfigSectionSpec spec = toSection(registered, textResolver, section, customGroups, diagnostics);
                    if (spec == null) {
                        continue;
                    }
                    String existingOwner = ownerBySectionId.get(spec.sectionId());
                    if (existingOwner != null) {
                        diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), spec.sectionId(),
                                "duplicate GUI config section id '" + spec.sectionId() + "' from plugin '"
                                        + registered.id() + "' conflicts with plugin '" + existingOwner + "'"));
                        accepted.removeIf(item -> item.sectionId().equals(spec.sectionId()));
                        continue;
                    }
                    ownerBySectionId.put(spec.sectionId(), registered.id());
                    accepted.add(spec);
                }
            }
        }
        return accepted.stream()
                .sorted(Comparator
                        .comparingInt(GuiConfigSectionSpec::groupOrder)
                        .thenComparingInt(GuiConfigSectionSpec::order)
                        .thenComparing(GuiConfigSectionSpec::pluginId)
                        .thenComparing(GuiConfigSectionSpec::sectionId))
                .toList();
    }

    private static GuiConfigSectionSpec toSection(PluginRegistry.RegisteredPlugin registered,
                                                  PluginTextResolver textResolver,
                                                  GuiConfigSectionContribution section,
                                                  Map<String, GroupEntry> customGroups,
                                                  List<GuiConfigContributionDiagnostic> diagnostics) {
        if (section == null) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                    "null GUI config section contribution"));
            return null;
        }
        String sectionId = normalize(section.sectionId());
        if (sectionId == null) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                    "GUI config section id is blank"));
            return null;
        }
        String groupId = normalize(section.groupId());
        if (groupId == null) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                    "GUI config section group id is blank"));
            return null;
        }
        if (!ConfigFieldRegistry.hasGroupId(groupId) && !customGroups.containsKey(groupId)) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                    "GUI config section references unknown group id '" + groupId + "'"));
            return null;
        }
        if (section.layout() == null) {
            diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                    "GUI config section layout is null"));
            return null;
        }

        String title = optionalText(registered, textResolver, sectionId, "section", "title",
                section.i18nNamespace(), section.titleKey(), diagnostics);
        if (title == null) {
            return null;
        }
        String help = optionalText(registered, textResolver, sectionId, "section", "help",
                section.i18nNamespace(), section.helpKey(), diagnostics);
        if (help == null) {
            return null;
        }
        List<GuiConfigFieldLayoutSpec> fieldLayouts = fieldLayoutSpecs(
                registered, textResolver, sectionId, section.fieldLayouts(), diagnostics);
        List<GuiConfigActionSpec> actions = actionSpecs(
                registered, textResolver, sectionId, section.actions(), diagnostics);
        List<GuiConfigPresetSpec> presets = presetSpecs(
                registered, textResolver, sectionId, section.presets(), diagnostics);

        String groupLabel = ConfigFieldRegistry.groupLabel(groupId)
                .orElseGet(() -> customGroups.get(groupId).spec().label());
        int groupOrder = ConfigFieldRegistry.groupOrder(groupId)
                .orElseGet(() -> customGroups.get(groupId).spec().order());
        return new GuiConfigSectionSpec(registered.id(), sectionId, groupId, groupLabel,
                groupOrder, title, help, section.layout(), section.order(), fieldLayouts, actions, presets);
    }

    private static List<GuiConfigFieldLayoutSpec> fieldLayoutSpecs(PluginRegistry.RegisteredPlugin registered,
                                                                   PluginTextResolver textResolver,
                                                                   String sectionId,
                                                                   List<GuiConfigFieldLayoutContribution> layouts,
                                                                   List<GuiConfigContributionDiagnostic> diagnostics) {
        List<GuiConfigFieldLayoutSpec> accepted = new ArrayList<>();
        for (GuiConfigFieldLayoutContribution layout : layouts) {
            if (layout == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "null GUI config field layout contribution"));
                continue;
            }
            String fieldKey = normalize(layout.fieldKey());
            if (fieldKey == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "GUI config field layout field key is blank"));
                continue;
            }
            String cardLabel = optionalText(registered, textResolver, sectionId, "field layout",
                    "card label", layout.i18nNamespace(), layout.cardLabelKey(), diagnostics);
            if (cardLabel == null) {
                continue;
            }
            accepted.add(new GuiConfigFieldLayoutSpec(fieldKey, normalize(layout.cardId()), cardLabel, layout.order()));
        }
        return accepted.stream()
                .sorted(Comparator
                        .comparingInt(GuiConfigFieldLayoutSpec::order)
                        .thenComparing(GuiConfigFieldLayoutSpec::fieldKey))
                .toList();
    }

    private static List<GuiConfigActionSpec> actionSpecs(PluginRegistry.RegisteredPlugin registered,
                                                         PluginTextResolver textResolver,
                                                         String sectionId,
                                                         List<GuiConfigActionContribution> actions,
                                                         List<GuiConfigContributionDiagnostic> diagnostics) {
        Map<String, String> ownerByActionId = new LinkedHashMap<>();
        List<GuiConfigActionSpec> accepted = new ArrayList<>();
        for (GuiConfigActionContribution action : actions) {
            if (action == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "null GUI config action contribution"));
                continue;
            }
            String actionId = normalize(action.actionId());
            if (actionId == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "GUI config action id is blank"));
                continue;
            }
            String labelKey = normalize(action.labelKey());
            if (labelKey == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "GUI config action label key is blank: " + actionId));
                continue;
            }
            String endpoint = normalize(action.endpoint());
            if (endpoint == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "GUI config action endpoint is blank: " + actionId));
                continue;
            }
            if (!validGuiEndpoint(endpoint)) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "GUI config action endpoint must be a relative /api/gui/ path segment: " + actionId));
                continue;
            }
            String label = textResolver.actionText(sectionId, action.i18nNamespace(), labelKey,
                    "label", diagnostics);
            if (label == null) {
                continue;
            }
            String help = optionalText(registered, textResolver, sectionId, "action", "help",
                    action.i18nNamespace(), action.helpKey(), diagnostics);
            if (help == null) {
                continue;
            }
            if (ownerByActionId.putIfAbsent(actionId, registered.id()) != null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "duplicate GUI config action id '" + actionId + "' in section '" + sectionId + "'"));
                accepted.removeIf(item -> item.actionId().equals(actionId));
                continue;
            }
            accepted.add(new GuiConfigActionSpec(actionId, label, help, endpoint,
                    action.readTimeoutMillis() <= 0 ? 30_000 : action.readTimeoutMillis(),
                    action.order(), validPayloadFields(registered, sectionId, action.payloadFields(), diagnostics)));
        }
        return accepted.stream()
                .sorted(Comparator
                        .comparingInt(GuiConfigActionSpec::order)
                        .thenComparing(GuiConfigActionSpec::actionId))
                .toList();
    }

    private static List<GuiConfigActionPayloadField> validPayloadFields(PluginRegistry.RegisteredPlugin registered,
                                                                        String sectionId,
                                                                        List<GuiConfigActionPayloadField> payloadFields,
                                                                        List<GuiConfigContributionDiagnostic> diagnostics) {
        List<GuiConfigActionPayloadField> accepted = new ArrayList<>();
        for (GuiConfigActionPayloadField field : payloadFields) {
            if (field == null || normalize(field.payloadPath()) == null || normalize(field.fieldKey()) == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "GUI config action payload field contains blank path or field key"));
                continue;
            }
            accepted.add(field);
        }
        return List.copyOf(accepted);
    }

    private static boolean validGuiEndpoint(String endpoint) {
        return endpoint != null
                && !endpoint.isBlank()
                && !endpoint.startsWith("/")
                && !endpoint.contains("://")
                && !endpoint.contains("?")
                && !endpoint.contains("#")
                && !endpoint.contains("\\")
                && java.util.Arrays.stream(endpoint.split("/"))
                .allMatch(part -> !part.isBlank() && !".".equals(part) && !"..".equals(part));
    }

    private static List<GuiConfigPresetSpec> presetSpecs(PluginRegistry.RegisteredPlugin registered,
                                                         PluginTextResolver textResolver,
                                                         String sectionId,
                                                         List<GuiConfigPresetContribution> presets,
                                                         List<GuiConfigContributionDiagnostic> diagnostics) {
        Map<String, String> ownerByPresetId = new LinkedHashMap<>();
        List<GuiConfigPresetSpec> accepted = new ArrayList<>();
        for (GuiConfigPresetContribution preset : presets) {
            if (preset == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "null GUI config preset contribution"));
                continue;
            }
            String presetId = normalize(preset.presetId());
            if (presetId == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "GUI config preset id is blank"));
                continue;
            }
            String labelKey = normalize(preset.labelKey());
            if (labelKey == null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "GUI config preset label key is blank: " + presetId));
                continue;
            }
            String label = textResolver.presetText(sectionId, preset.i18nNamespace(), labelKey,
                    "label", diagnostics);
            if (label == null) {
                continue;
            }
            String help = optionalText(registered, textResolver, sectionId, "preset", "help",
                    preset.i18nNamespace(), preset.helpKey(), diagnostics);
            if (help == null) {
                continue;
            }
            if (ownerByPresetId.putIfAbsent(presetId, registered.id()) != null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), sectionId,
                        "duplicate GUI config preset id '" + presetId + "' in section '" + sectionId + "'"));
                accepted.removeIf(item -> item.presetId().equals(presetId));
                continue;
            }
            accepted.add(new GuiConfigPresetSpec(presetId, label, help, preset.order(),
                    normalize(preset.matchFieldKey()), preset.matchValue(), preset.values()));
        }
        return accepted.stream()
                .sorted(Comparator
                        .comparingInt(GuiConfigPresetSpec::order)
                        .thenComparing(GuiConfigPresetSpec::presetId))
                .toList();
    }

    private static String optionalText(PluginRegistry.RegisteredPlugin registered,
                                       PluginTextResolver textResolver,
                                       String diagnosticKey,
                                       String ownerType,
                                       String role,
                                       String namespace,
                                       String key,
                                       List<GuiConfigContributionDiagnostic> diagnostics) {
        String normalized = normalize(key);
        if (normalized == null) {
            return "";
        }
        return textResolver.text(diagnosticKey, ownerType, role, namespace, normalized, diagnostics);
    }

    private static String duplicateMessage(String key, String pluginId, String existingOwner) {
        if (existingOwner == null) {
            return "duplicate GUI config key '" + key + "' from plugin '" + pluginId
                    + "' was already suppressed by another duplicate";
        }
        if (CORE_OWNER.equals(existingOwner)) {
            return "duplicate GUI config key '" + key + "' from plugin '" + pluginId + "' conflicts with core";
        }
        return "duplicate GUI config key '" + key + "' from plugin '" + pluginId
                + "' conflicts with plugin '" + existingOwner + "'";
    }

    private static FieldType mapFieldType(GuiConfigFieldType type, boolean sensitive) {
        if (sensitive || type == GuiConfigFieldType.PASSWORD) {
            return FieldType.PASSWORD;
        }
        return switch (type) {
            case PATH_DIR -> FieldType.PATH_DIR;
            case PATH_FILE -> FieldType.PATH_FILE;
            case PORT -> FieldType.PORT;
            case BOOL -> FieldType.BOOL;
            case INT -> FieldType.INT;
            case STRING, PASSWORD -> FieldType.STRING;
            case ENUM -> FieldType.ENUM;
        };
    }

    private static ConfigFieldSpec.Validator validator(GuiConfigFieldContribution field) {
        if (field.type() == GuiConfigFieldType.PORT) {
            return value -> {
                try {
                    int port = Integer.parseInt(value);
                    return port >= 1 && port <= 65535 ? null : GuiMessages.get("gui.config.validation.port-range");
                } catch (NumberFormatException e) {
                    return GuiMessages.get("gui.config.validation.valid-port");
                }
            };
        }
        if (field.type() != GuiConfigFieldType.INT || (field.minValue() == null && field.maxValue() == null)) {
            return value -> null;
        }
        return value -> {
            try {
                int parsed = Integer.parseInt(value);
                if (field.minValue() != null && parsed < field.minValue()) {
                    return field.minValue() == 1
                            ? GuiMessages.get("gui.config.validation.positive-int")
                            : GuiMessages.get("gui.config.validation.non-negative-int");
                }
                if (field.maxValue() != null && parsed > field.maxValue()) {
                    return GuiMessages.get("gui.config.validation.valid-int");
                }
                return null;
            } catch (NumberFormatException e) {
                return GuiMessages.get("gui.config.validation.valid-int");
            }
        };
    }

    private static Predicate<ConfigSnapshot> predicate(List<GuiConfigCondition> conditions) {
        List<GuiConfigCondition> safe = conditions == null ? List.of() : List.copyOf(conditions);
        return snapshot -> {
            for (GuiConfigCondition condition : safe) {
                if (!matches(snapshot, condition)) {
                    return false;
                }
            }
            return true;
        };
    }

    private static boolean matches(ConfigSnapshot snapshot, GuiConfigCondition condition) {
        GuiConfigConditionOperator operator = condition.operator();
        return switch (operator) {
            case TRUE -> snapshot.isTrue(condition.key());
            case FALSE -> !snapshot.isTrue(condition.key());
            case EQUALS -> snapshot.equals(condition.key(), condition.value() == null ? "" : condition.value());
            case NOT_EQUALS -> !snapshot.equals(condition.key(), condition.value() == null ? "" : condition.value());
            case BLANK -> snapshot.get(condition.key()).isBlank();
            case NOT_BLANK -> snapshot.notBlank(condition.key());
        };
    }

    private static boolean validConditions(List<GuiConfigCondition> conditions) {
        if (conditions == null) {
            return true;
        }
        for (GuiConfigCondition condition : conditions) {
            if (condition == null || normalize(condition.key()) == null || condition.operator() == null) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record GroupEntry(String pluginId, ConfigGroupSpec spec) {
    }

    private record PluginContributions(PluginRegistry.RegisteredPlugin registered,
                                       PluginTextResolver textResolver,
                                       List<GuiConfigContribution> contributions) {
    }

    private record AcceptedField(String pluginId, String key, ConfigFieldSpec spec,
                                 int groupOrder, int fieldOrder) {
    }

    private static final class PluginTextResolver {
        private final PluginRegistry.RegisteredPlugin registered;
        private final List<I18nContribution> i18nContributions;
        private final String i18nFailure;
        private final boolean i18nWasNull;
        private boolean displayNamespaceResolved;
        private String displayNamespace;
        private String displayNamespaceFailure;

        private PluginTextResolver(PluginRegistry.RegisteredPlugin registered,
                                   List<I18nContribution> i18nContributions,
                                   String i18nFailure,
                                   boolean i18nWasNull) {
            this.registered = registered;
            this.i18nContributions = i18nContributions;
            this.i18nFailure = i18nFailure;
            this.i18nWasNull = i18nWasNull;
        }

        static PluginTextResolver create(PluginRegistry.RegisteredPlugin registered,
                                         List<GuiConfigContributionDiagnostic> diagnostics) {
            try {
                List<I18nContribution> i18n = registered.plugin().i18n();
                if (i18n == null) {
                    diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null,
                            "GUI config plugin i18n contribution list is null; using raw keys"));
                    return new PluginTextResolver(registered, List.of(), null, true);
                }
                return new PluginTextResolver(registered, List.copyOf(i18n), null, false);
            } catch (RuntimeException e) {
                String message = "GUI config plugin i18n threw: " + safeMessage(e);
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), null, message));
                return new PluginTextResolver(registered, List.of(), message, false);
            }
        }

        String groupText(String groupId, String explicitNamespace, String key,
                         List<GuiConfigContributionDiagnostic> diagnostics) {
            return text(groupId, "group", "label", explicitNamespace, key, diagnostics);
        }

        String fieldText(String fieldKey, String explicitNamespace, String key, String role,
                         List<GuiConfigContributionDiagnostic> diagnostics) {
            return text(fieldKey, "field", role, explicitNamespace, key, diagnostics);
        }

        String actionText(String sectionId, String explicitNamespace, String key, String role,
                          List<GuiConfigContributionDiagnostic> diagnostics) {
            return text(sectionId, "action", role, explicitNamespace, key, diagnostics);
        }

        String presetText(String sectionId, String explicitNamespace, String key, String role,
                          List<GuiConfigContributionDiagnostic> diagnostics) {
            return text(sectionId, "preset", role, explicitNamespace, key, diagnostics);
        }

        private String text(String diagnosticKey, String ownerType, String role,
                            String explicitNamespace, String key,
                            List<GuiConfigContributionDiagnostic> diagnostics) {
            if (i18nFailure != null) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), diagnosticKey,
                        "GUI config " + ownerType + " " + role + " i18n resolution failed for key '"
                                + key + "': " + i18nFailure));
                return null;
            }
            NamespaceResolution namespace = namespace(explicitNamespace);
            if (!namespace.success()) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), diagnosticKey,
                        "GUI config " + ownerType + " " + role + " display namespace threw for key '"
                                + key + "': " + namespace.error()));
                return null;
            }
            try {
                return ConfigFieldRegistry.pluginText(i18nContributions, registered.classLoader(),
                        namespace.namespace(), key);
            } catch (RuntimeException e) {
                diagnostics.add(new GuiConfigContributionDiagnostic(registered.id(), diagnosticKey,
                        "GUI config " + ownerType + " " + role + " text resolution failed for key '"
                                + key + "': " + safeMessage(e)));
                return null;
            }
        }

        private NamespaceResolution namespace(String explicitNamespace) {
            String normalized = normalize(explicitNamespace);
            if (normalized != null) {
                return NamespaceResolution.success(normalized);
            }
            if (i18nWasNull) {
                return NamespaceResolution.success(null);
            }
            if (!displayNamespaceResolved) {
                try {
                    displayNamespace = normalize(registered.plugin().displayNamespace());
                } catch (RuntimeException e) {
                    displayNamespaceFailure = safeMessage(e);
                }
                displayNamespaceResolved = true;
            }
            if (displayNamespaceFailure != null) {
                return NamespaceResolution.failure(displayNamespaceFailure);
            }
            return NamespaceResolution.success(displayNamespace);
        }
    }

    private record NamespaceResolution(boolean success, String namespace, String error) {
        static NamespaceResolution success(String namespace) {
            return new NamespaceResolution(true, namespace, null);
        }

        static NamespaceResolution failure(String error) {
            return new NamespaceResolution(false, null, error);
        }
    }
}
