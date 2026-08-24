package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.gui.DebugUnlockState;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.i18n.PluginContributionText;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 把宿主配置目录和插件配置贡献映射为 Swing 原生字段规格。
 * <p>
 * 每次调用都按当前 locale 重新解析文案，避免语言热切换后沿用旧标签。
 */
public final class ConfigFieldRegistry {

    private ConfigFieldRegistry() {
    }

    public static String groupMaintenance() {
        return groupLabel(GuiConfigGroups.MAINTENANCE)
                .orElseGet(() -> message("gui.config.group.maintenance"));
    }

    /** 插件 / 插件市场分组名（按当前 locale）。 */
    public static String groupPlugins() {
        return groupLabel(GuiConfigGroups.PLUGINS)
                .orElseGet(() -> message("gui.config.group.plugins"));
    }

    /** AI 配置分组名（按当前 locale）。 */
    public static String groupAi() {
        return groupLabel(GuiConfigGroups.AI)
                .orElseGet(() -> message("gui.config.group.ai"));
    }

    /** 通知分组名（按当前 locale）。 */
    public static String groupNotification() {
        return groupLabel(GuiConfigGroups.NOTIFICATION)
                .orElseGet(() -> message("gui.config.group.notification"));
    }

    /** 全部分组名（按当前 locale，保持顺序）。 */
    public static List<String> groups() {
        return snapshot().groups();
    }

    /** 合并插件 GUI 配置 contribution 后的分组名。 */
    public static List<String> groups(GuiConfigContributionSnapshot pluginContributions) {
        return snapshot(pluginContributions).groups();
    }

    /** 宿主核心配置字段。 */
    public static List<ConfigFieldSpec> allFields() {
        return coreFields();
    }

    /** 宿主字段与活动插件字段的合并结果。 */
    public static List<ConfigFieldSpec> allFields(GuiConfigContributionSnapshot pluginContributions) {
        return snapshot(pluginContributions).fields();
    }

    public static ConfigFieldSnapshot snapshot() {
        return snapshot(GuiConfigContributionSnapshot.empty());
    }

    public static ConfigFieldSnapshot snapshot(GuiConfigContributionSnapshot pluginContributions) {
        GuiConfigContributionSnapshot contributions = pluginContributions == null
                ? GuiConfigContributionSnapshot.empty()
                : pluginContributions;
        List<ConfigGroupSpec> mergedGroups = new ArrayList<>(coreGroupSpecs());
        Set<String> groupIds = mergedGroups.stream()
                .map(ConfigGroupSpec::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (ConfigGroupSpec group : contributions.groups()) {
            if (group != null && groupIds.add(group.id())) {
                mergedGroups.add(group);
            }
        }

        List<ConfigFieldSpec> fields = new ArrayList<>(coreFields());
        fields.addAll(contributions.fields());
        Set<String> groupsWithFields = fields.stream()
                .filter(ConfigFieldSpec::contributesGroupVisibility)
                .flatMap(field -> groupKeys(field.groupId(), field.group()).stream())
                .collect(java.util.stream.Collectors.toSet());
        List<GuiConfigSectionSpec> sections = contributions.sections();
        Set<String> groupsWithSections = sections.stream()
                .filter(GuiConfigSectionSpec::contributesGroupVisibility)
                .flatMap(section -> groupKeys(section.groupId(), section.group()).stream())
                .collect(java.util.stream.Collectors.toSet());
        List<ConfigGroupSpec> visibleGroups = mergedGroups.stream()
                .filter(ConfigGroupSpec::visibleInTabs)
                .filter(group -> shouldShowGroup(group, groupsWithFields, groupsWithSections))
                .sorted(Comparator.comparingInt(ConfigGroupSpec::order))
                .toList();
        return ConfigFieldSnapshot.withGroupSpecs(
                visibleGroups,
                fields,
                sections,
                contributions.diagnostics()
        );
    }

    private static boolean shouldShowGroup(
            ConfigGroupSpec group,
            Set<String> groupsWithFields,
            Set<String> groupsWithSections
    ) {
        return groupsWithFields.contains(group.id())
                || groupsWithFields.contains(group.label())
                || groupsWithSections.contains(group.id())
                || groupsWithSections.contains(group.label());
    }

    private static Set<String> groupKeys(String groupId, String groupLabel) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (groupId != null && !groupId.isBlank()) {
            keys.add(groupId.trim());
        }
        if (groupLabel != null && !groupLabel.isBlank()) {
            keys.add(groupLabel);
        }
        return keys;
    }

    static boolean hasGroupId(String groupId) {
        return coreGroupSpecs().stream().anyMatch(group -> group.id().equals(groupId));
    }

    static Optional<String> groupLabel(String groupId) {
        return coreGroupSpecs().stream()
                .filter(group -> group.id().equals(groupId))
                .map(ConfigGroupSpec::label)
                .findFirst();
    }

    static Optional<Integer> groupOrder(String groupId) {
        return coreGroupSpecs().stream()
                .filter(group -> group.id().equals(groupId))
                .map(ConfigGroupSpec::order)
                .findFirst();
    }

    static Optional<ConfigGroupSpec> coreGroupSpecByLabel(String label) {
        return coreGroupSpecs().stream()
                .filter(group -> group.label().equals(label))
                .findFirst();
    }

    static Set<String> coreFieldKeys() {
        return coreFields().stream()
                .map(ConfigFieldSpec::key)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<ConfigGroupSpec> coreGroupSpecs() {
        return SwingHost.host().coreConfigGroups().stream()
                .map(ConfigFieldRegistry::coreGroupSpec)
                .toList();
    }

    private static ConfigGroupSpec coreGroupSpec(GuiConfigGroupContribution group) {
        return new ConfigGroupSpec(
                group.groupId(),
                message(group.labelKey()),
                group.order(),
                group.visibleInTabs()
        );
    }

    private static List<ConfigFieldSpec> coreFields() {
        Map<String, ConfigGroupSpec> groups = new LinkedHashMap<>();
        for (ConfigGroupSpec group : coreGroupSpecs()) {
            groups.put(group.id(), group);
        }
        return SwingHost.host().coreConfigFields().stream()
                .map(field -> coreField(field, groups))
                .toList();
    }

    private static ConfigFieldSpec coreField(
            GuiConfigFieldContribution field,
            Map<String, ConfigGroupSpec> groups
    ) {
        ConfigGroupSpec group = Optional.ofNullable(groups.get(field.groupId()))
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown core GUI config group: " + field.groupId()
                ));
        Predicate<ConfigSnapshot> visible = GuiConfigContributionAggregator.predicate(field.visibleWhen());
        if ("debug.enabled".equals(field.key())) {
            Predicate<ConfigSnapshot> declaredVisibility = visible;
            visible = snapshot -> DebugUnlockState.isUnlocked() && declaredVisibility.test(snapshot);
        }

        ConfigFieldSpec.Builder builder = ConfigFieldSpec.builder(
                        field.key(),
                        message(field.labelKey()),
                        GuiConfigContributionAggregator.mapFieldType(field.type(), field.sensitive()),
                        group.label()
                )
                .groupId(group.id())
                .defaultValue(field.defaultValue())
                .help(field.helpKey().isBlank() ? "" : message(field.helpKey()))
                .enabledWhen(GuiConfigContributionAggregator.predicate(field.enabledWhen()))
                .visibleWhen(visible)
                .visibleWhenConditions(field.visibleWhen())
                .validator(coreValidator(field))
                .contributesGroupVisibility(field.contributesGroupVisibility());
        if (field.type() == GuiConfigFieldType.ENUM) {
            builder.enumValues(field.enumValues().toArray(String[]::new));
            Map<String, String> labels = new LinkedHashMap<>();
            field.enumValueLabelKeys().forEach((value, key) -> labels.put(value, message(key)));
            builder.enumValueLabels(labels);
        }
        builder.effect(field.effect());
        return builder.build();
    }

    private static ConfigFieldSpec.Validator coreValidator(GuiConfigFieldContribution field) {
        if (field.type() == GuiConfigFieldType.PORT) {
            return value -> {
                try {
                    int port = Integer.parseInt(value);
                    return port >= 1 && port <= 65_535
                            ? null
                            : message("gui.config.validation.port-range");
                } catch (NumberFormatException e) {
                    return message("gui.config.validation.valid-port");
                }
            };
        }
        if (field.key().startsWith("maintenance.") && field.key().endsWith(".time")) {
            return value -> SwingHost.host().validMaintenanceTime(value)
                    ? null
                    : message("gui.config.validation.time-hh-mm");
        }
        if (field.type() != GuiConfigFieldType.INT
                || field.minValue() == null && field.maxValue() == null) {
            return value -> null;
        }
        return value -> validateInteger(field, value);
    }

    private static String validateInteger(GuiConfigFieldContribution field, String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (field.minValue() != null && parsed < field.minValue()) {
                return switch (field.minValue()) {
                    case 0 -> message("gui.config.validation.non-negative-int");
                    case 1 -> message("gui.config.validation.positive-int");
                    case 8 -> message("gui.config.validation.min-eight-int");
                    case 60 -> message("gui.config.validation.schedule-defer-min");
                    case 1000 -> message("gui.config.validation.schedule-tick-min");
                    default -> message("gui.config.validation.valid-int");
                };
            }
            if (field.maxValue() != null && parsed > field.maxValue()) {
                return message("gui.config.validation.valid-int");
            }
            return null;
        } catch (NumberFormatException e) {
            return message("gui.config.validation.valid-int");
        }
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    /**
     * 解析插件注册期物化的纯 i18n key，缺失时回退 key 本身。
     */
    static String pluginText(PixivFeaturePlugin plugin, String namespace, String key) {
        return pluginText(plugin, plugin.getClass().getClassLoader(), namespace, key);
    }

    static String pluginText(
            PixivFeaturePlugin plugin,
            ClassLoader classLoader,
            String namespace,
            String key
    ) {
        List<I18nContribution> contributions = plugin.i18n();
        ClassLoader effectiveClassLoader = classLoader == null
                ? plugin.getClass().getClassLoader()
                : classLoader;
        return pluginText(contributions, effectiveClassLoader, namespace, key);
    }

    static String pluginText(
            List<I18nContribution> contributions,
            ClassLoader classLoader,
            String namespace,
            String key
    ) {
        return PluginContributionText.resolve(contributions, classLoader, namespace, key);
    }
}
