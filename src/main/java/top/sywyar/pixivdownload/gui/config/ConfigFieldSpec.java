package top.sywyar.pixivdownload.gui.config;

import java.util.List;
import java.util.function.Predicate;

/**
 * 单个配置字段的元数据。UI 根据此 schema 自动渲染控件，无需为每个字段硬编码。
 */
public record ConfigFieldSpec(
        String key,
        String label,
        FieldType type,
        String group,
        String helpText,
        String defaultValue,
        Validator validator,
        List<String> enumValues,
        Predicate<ConfigSnapshot> enabledWhen,
        boolean requiresRestart
) {

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
        private String helpText = "";
        private String defaultValue = "";
        private Validator validator = v -> null;
        private List<String> enumValues = List.of();
        private Predicate<ConfigSnapshot> enabledWhen = snap -> true;
        private boolean requiresRestart = true;

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

        public Builder validator(Validator v) {
            this.validator = v;
            return this;
        }

        public Builder enumValues(String... values) {
            this.enumValues = List.of(values);
            return this;
        }

        public Builder enabledWhen(Predicate<ConfigSnapshot> pred) {
            this.enabledWhen = pred;
            return this;
        }

        public ConfigFieldSpec build() {
            return new ConfigFieldSpec(key, label, type, group, helpText, defaultValue,
                    validator, enumValues, enabledWhen, requiresRestart);
        }
    }
}
