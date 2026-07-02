package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure data declaration for one GUI configuration field.
 *
 * @param key            global config key written to config.yaml
 * @param groupId        stable group id; built-in ids are available from {@link GuiConfigGroups}
 * @param labelKey       i18n key for the field label
 * @param helpKey        optional i18n key for help text
 * @param i18nNamespace  optional i18n namespace; blank means the plugin display namespace
 * @param type           control type
 * @param defaultValue   value used when the key is absent
 * @param order          field ordering hint inside plugin-contributed fields
 * @param sensitive      whether the value is secret and must be rendered as a password field
 * @param requiresRestart whether saving a changed value requires a restart
 * @param enumValues     allowed values for {@link GuiConfigFieldType#ENUM}
 * @param enabledWhen    all conditions that must match for the field to be enabled
 * @param visibleWhen    all conditions that must match for the field to be visible
 * @param minValue       optional numeric minimum for INT-like values
 * @param maxValue       optional numeric maximum for INT-like values
 * @param contributesGroupVisibility whether this field alone should make its group visible in tabs
 * @param enumValueLabelKeys optional enum value to i18n label key mapping
 */
public record GuiConfigFieldContribution(
        String key,
        String groupId,
        String labelKey,
        String helpKey,
        String i18nNamespace,
        GuiConfigFieldType type,
        String defaultValue,
        int order,
        boolean sensitive,
        boolean requiresRestart,
        List<String> enumValues,
        List<GuiConfigCondition> enabledWhen,
        List<GuiConfigCondition> visibleWhen,
        Integer minValue,
        Integer maxValue,
        boolean contributesGroupVisibility,
        Map<String, String> enumValueLabelKeys
) {

    public GuiConfigFieldContribution {
        helpKey = helpKey == null ? "" : helpKey;
        i18nNamespace = blankToNull(i18nNamespace);
        defaultValue = defaultValue == null ? "" : defaultValue;
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        enabledWhen = enabledWhen == null ? List.of() : List.copyOf(enabledWhen);
        visibleWhen = visibleWhen == null ? List.of() : List.copyOf(visibleWhen);
        enumValueLabelKeys = enumValueLabelKeys == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(enumValueLabelKeys));
    }

    public GuiConfigFieldContribution(String key, String groupId, String labelKey,
                                      GuiConfigFieldType type, String defaultValue, int order) {
        this(key, groupId, labelKey, "", null, type, defaultValue, order,
                false, true, List.of(), List.of(), List.of(), null, null, true, Map.of());
    }

    public GuiConfigFieldContribution(String key, String groupId, String labelKey, String helpKey,
                                      GuiConfigFieldType type, String defaultValue, int order,
                                      boolean sensitive, boolean requiresRestart) {
        this(key, groupId, labelKey, helpKey, null, type, defaultValue, order,
                sensitive, requiresRestart, List.of(), List.of(), List.of(), null, null, true, Map.of());
    }

    public GuiConfigFieldContribution(
            String key,
            String groupId,
            String labelKey,
            String helpKey,
            String i18nNamespace,
            GuiConfigFieldType type,
            String defaultValue,
            int order,
            boolean sensitive,
            boolean requiresRestart,
            List<String> enumValues,
            List<GuiConfigCondition> enabledWhen,
            List<GuiConfigCondition> visibleWhen,
            Integer minValue,
            Integer maxValue
    ) {
        this(key, groupId, labelKey, helpKey, i18nNamespace, type, defaultValue, order,
                sensitive, requiresRestart, enumValues, enabledWhen, visibleWhen, minValue, maxValue, true, Map.of());
    }

    public GuiConfigFieldContribution(
            String key,
            String groupId,
            String labelKey,
            String helpKey,
            String i18nNamespace,
            GuiConfigFieldType type,
            String defaultValue,
            int order,
            boolean sensitive,
            boolean requiresRestart,
            List<String> enumValues,
            List<GuiConfigCondition> enabledWhen,
            List<GuiConfigCondition> visibleWhen,
            Integer minValue,
            Integer maxValue,
            boolean contributesGroupVisibility
    ) {
        this(key, groupId, labelKey, helpKey, i18nNamespace, type, defaultValue, order,
                sensitive, requiresRestart, enumValues, enabledWhen, visibleWhen, minValue, maxValue,
                contributesGroupVisibility, Map.of());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
