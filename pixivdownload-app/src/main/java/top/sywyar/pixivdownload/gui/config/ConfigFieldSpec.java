package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 单个配置字段的元数据。UI 根据此 schema 自动渲染控件，无需为每个字段硬编码。
 */
public record ConfigFieldSpec(
        String key,
        String label,
        FieldType type,
        String group,
        String ownerPluginId,
        String helpText,
        String defaultValue,
        Validator validator,
        List<String> enumValues,
        Map<String, String> enumValueLabels,
        Predicate<ConfigSnapshot> enabledWhen,
        Predicate<ConfigSnapshot> visibleWhen,
        List<GuiConfigCondition> visibleWhenConditions,
        boolean requiresRestart,
        boolean contributesGroupVisibility
) {

    public static final String CORE_OWNER = "core";

    public ConfigFieldSpec {
        ownerPluginId = ownerPluginId == null || ownerPluginId.isBlank()
                ? CORE_OWNER
                : ownerPluginId.trim();
        visibleWhenConditions = visibleWhenConditions == null
                ? List.of()
                : List.copyOf(visibleWhenConditions);
    }

    public boolean pluginContributed() {
        return !CORE_OWNER.equals(ownerPluginId);
    }

    @FunctionalInterface
    public interface Validator {
        /** 返回 null 表示验证通过，返回非空字符串为错误提示 */
        String validate(String value);
    }

    /** 构建器，简化常用配置 */
    public static Builder builder(String key, String label, FieldType type, String group) {
        return new Builder(key, label, type, group);
    }

    public static class Builder {
        private final String key;
        private final String label;
        private final FieldType type;
        private final String group;
        private String ownerPluginId = CORE_OWNER;
        private String helpText = "";
        private String defaultValue = "";
        private Validator validator = v -> null;
        private List<String> enumValues = List.of();
        private Map<String, String> enumValueLabels = Map.of();
        private Predicate<ConfigSnapshot> enabledWhen = snap -> true;
        private Predicate<ConfigSnapshot> visibleWhen = snap -> true;
        private List<GuiConfigCondition> visibleWhenConditions = List.of();
        private boolean requiresRestart = true;
        private boolean contributesGroupVisibility = true;

        private Builder(String key, String label, FieldType type, String group) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.group = group;
        }

        public Builder help(String helpText) {
            this.helpText = helpText;
            return this;
        }

        public Builder defaultValue(String v) {
            this.defaultValue = v;
            return this;
        }

        public Builder ownerPluginId(String ownerPluginId) {
            this.ownerPluginId = ownerPluginId == null || ownerPluginId.isBlank()
                    ? CORE_OWNER
                    : ownerPluginId.trim();
            return this;
        }

        public Builder validator(Validator v) {
            this.validator = v;
            return this;
        }

        public Builder enumValues(String... values) {
            this.enumValues = List.of(values);
            return this;
        }

        public Builder enumValueLabels(Map<String, String> labels) {
            this.enumValueLabels = labels == null ? Map.of() : Map.copyOf(labels);
            return this;
        }

        public Builder enabledWhen(Predicate<ConfigSnapshot> pred) {
            this.enabledWhen = pred;
            return this;
        }

        public Builder visibleWhen(Predicate<ConfigSnapshot> pred) {
            this.visibleWhen = pred;
            return this;
        }

        public Builder visibleWhenConditions(List<GuiConfigCondition> conditions) {
            this.visibleWhenConditions = conditions == null ? List.of() : List.copyOf(conditions);
            return this;
        }

        public Builder hotReloadable() {
            this.requiresRestart = false;
            return this;
        }

        public Builder contributesGroupVisibility(boolean contributesGroupVisibility) {
            this.contributesGroupVisibility = contributesGroupVisibility;
            return this;
        }

        public ConfigFieldSpec build() {
            return new ConfigFieldSpec(key, label, type, group, ownerPluginId, helpText, defaultValue,
                    validator, enumValues, enumValueLabels, enabledWhen, visibleWhen,
                    visibleWhenConditions, requiresRestart, contributesGroupVisibility);
        }
    }
}
